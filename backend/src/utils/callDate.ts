/**
 * The timezone the filename's wall clock is assumed to have been written in.
 *
 * Android sends the recording's filename stamp — "260415_165702", YYMMDD_HHMMSS — which is
 * device-local wall-clock time with no offset attached. Building it with
 * `new Date(y, m, d, ...)` reads those digits in the *server process's* timezone, so the
 * same call landed at 13:57Z from a dev box in Israel and at 16:57Z from the production
 * container (node:20-alpine, no TZ set, therefore UTC) — three hours late on every call.
 *
 * Interpreting the digits in a named zone instead makes the stored instant independent of
 * wherever the container happens to run. The proper long-term fix is for the client to send
 * an unambiguous instant (it already computes one in `FilenameParser.toEpochMillis`), but
 * that only helps once every installed app has updated; this fixes already-shipped clients.
 */
export const CALL_TIMEZONE = 'Asia/Jerusalem';

const zoneFormatter = new Intl.DateTimeFormat('en-US', {
    timeZone: CALL_TIMEZONE,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
});

/** How far ahead of UTC CALL_TIMEZONE is at a given instant, in milliseconds. */
const zoneOffsetAt = (instant: number): number => {
    const fields: Record<string, string> = {};
    for (const { type, value } of zoneFormatter.formatToParts(instant)) {
        fields[type] = value;
    }
    // 'en-US' with hour12:false renders midnight as hour 24 rather than 0. Date.UTC rolls
    // that into the next day, which is the same instant, so it needs no special-casing.
    const asIfUtc = Date.UTC(
        Number(fields.year),
        Number(fields.month) - 1,
        Number(fields.day),
        Number(fields.hour),
        Number(fields.minute),
        Number(fields.second),
    );
    return asIfUtc - instant;
};

/**
 * The UTC instant named by a wall clock reading in CALL_TIMEZONE.
 *
 * The offset to apply depends on the instant being solved for, which is what we don't have
 * yet, so this guesses with the offset in force at the naive instant and then re-reads the
 * offset at that candidate. Two passes is exact everywhere except the hour DST skips, which
 * names no instant at all — there the result lands just past the transition.
 *
 * Twice-occurring wall times (the autumn fall-back hour) resolve to the later of the two
 * instants — the filename cannot distinguish them, so the only guarantee worth making is
 * that the same stamp always yields the same instant.
 */
const zonedWallClockToUtc = (
    year: number,
    monthIndex: number,
    day: number,
    hours: number,
    minutes: number,
    seconds: number,
): Date => {
    const naive = Date.UTC(year, monthIndex, day, hours, minutes, seconds);
    const candidate = naive - zoneOffsetAt(naive);
    return new Date(naive - zoneOffsetAt(candidate));
};

/** YYMMDD_HHMMSS, the only shape Android sends. */
const STAMP = /^(\d{2})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})$/;

/**
 * Converts Android's filename stamp into the instant the call actually happened.
 *
 * Never throws and never rejects: `AudioProcessor.NON_RETRYABLE_CODES` treats a 400 as
 * permanent — the client drops the payload and never retries — so failing an upload over an
 * unreadable stamp would destroy the transcript to protect a timestamp. An unusable stamp
 * falls back to now, which is what the caller has always done.
 */
export const parseFilenameDate = (dateString: unknown): Date => {
    if (typeof dateString !== 'string') return new Date();

    const match = STAMP.exec(dateString.trim());
    if (!match) {
        if (dateString !== '') {
            console.error(`Unparseable call date stamp "${dateString}", falling back to now`);
        }
        return new Date();
    }

    const [, yy, mm, dd, hh, mi, ss] = match.map(Number);
    const year = yy + 2000;
    const monthIndex = mm - 1;

    // The regex proves the fields are digits, not that they name a real date: "260931" and
    // "241500" both match. Date.UTC silently rolls those over into a plausible-looking wrong
    // answer, so the naive value is round-tripped and anything that moved is rejected.
    const naive = new Date(Date.UTC(year, monthIndex, dd, hh, mi, ss));
    const isRealDate =
        naive.getUTCFullYear() === year &&
        naive.getUTCMonth() === monthIndex &&
        naive.getUTCDate() === dd &&
        naive.getUTCHours() === hh &&
        naive.getUTCMinutes() === mi &&
        naive.getUTCSeconds() === ss;

    if (!isRealDate) {
        console.error(`Call date stamp "${dateString}" is not a real date, falling back to now`);
        return new Date();
    }

    return zonedWallClockToUtc(year, monthIndex, dd, hh, mi, ss);
};
