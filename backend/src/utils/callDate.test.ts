import { describe, it, expect } from 'vitest';
import { parseFilenameDate } from './callDate';

/**
 * Every expectation is written as an absolute UTC instant on purpose. Asserting on
 * `getHours()` or a formatted string would read the result back through the test
 * runner's own timezone — which is exactly the bug under test, so the assertion
 * would pass on a UTC-3 machine and on a UTC machine alike while the stored
 * instant differed by hours.
 */
describe('parseFilenameDate', () => {
    it('reads a summer stamp as Israel Daylight Time (UTC+3)', () => {
        // 2026-08-02 10:15:00 in Jerusalem is 07:15:00Z.
        expect(parseFilenameDate('260802_101500').toISOString()).toBe('2026-08-02T07:15:00.000Z');
    });

    it('reads a winter stamp as Israel Standard Time (UTC+2)', () => {
        // 2026-01-15 10:15:00 in Jerusalem is 08:15:00Z.
        expect(parseFilenameDate('260115_101500').toISOString()).toBe('2026-01-15T08:15:00.000Z');
    });

    it('reads the moment before the spring-forward transition', () => {
        // Israel moves 02:00 -> 03:00 on 2026-03-27. 01:59:59 is still UTC+2.
        expect(parseFilenameDate('260327_015959').toISOString()).toBe('2026-03-26T23:59:59.000Z');
    });

    it('reads the moment after the spring-forward transition', () => {
        // 03:00:00 the same morning is already UTC+3.
        expect(parseFilenameDate('260327_030000').toISOString()).toBe('2026-03-27T00:00:00.000Z');
    });

    it('resolves an ambiguous autumn stamp deterministically', () => {
        // Israel moves 02:00 -> 01:00 on 2026-10-25, so 01:30 happens twice: once at
        // 22:30Z under IDT and again at 23:30Z under IST. The filename cannot say which,
        // so either is defensible; what matters is that the answer is always the same one.
        expect(parseFilenameDate('261025_013000').toISOString()).toBe('2026-10-24T23:30:00.000Z');
    });

    it('handles the midnight stamp that formatters render as hour 24', () => {
        expect(parseFilenameDate('260802_000000').toISOString()).toBe('2026-08-01T21:00:00.000Z');
    });

    const fallsBackToNow = (label: string, input: unknown) => {
        it(`falls back to now for ${label}`, () => {
            const before = Date.now();
            const parsed = parseFilenameDate(input as string);
            expect(parsed.getTime()).toBeGreaterThanOrEqual(before);
            expect(parsed.getTime()).toBeLessThanOrEqual(Date.now());
        });
    };

    // The fallback is `now`, never a throw: `AudioProcessor.NON_RETRYABLE_CODES` treats a
    // 400 as permanent, so rejecting a call over a malformed stamp would destroy the
    // transcript to protect a timestamp.
    fallsBackToNow('an empty string', '');
    fallsBackToNow('a missing value', undefined);
    fallsBackToNow('a non-string value', 1754126100000);
    fallsBackToNow('a stamp with no separator', '260802101500');
    fallsBackToNow('a truncated stamp', '260802_1015');
    fallsBackToNow('non-numeric digits', '26AA02_101500');
    fallsBackToNow('month 13', '261302_101500');
    fallsBackToNow('day 31 of a 30-day month', '260931_101500');
    fallsBackToNow('hour 24', '260802_241500');
    fallsBackToNow('minute 60', '260802_106000');
});
