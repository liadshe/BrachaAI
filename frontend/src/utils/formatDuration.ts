/**
 * A call's length as a clock reading — `0:45`, `4:32`, `1:12:40`.
 *
 * Returns null for a duration we do not have, which callers must render as *nothing*
 * rather than as a zero. Every call recorded before the Android app started measuring
 * duration lands here, and the previous implementation turned that missing value into a
 * confident "0 min" for all of them.
 *
 * Clock style rather than rounded minutes so a 45-second call reads as 45 seconds.
 */
export const formatDuration = (seconds?: number): string | null => {
    if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) {
        return null;
    }

    const total = Math.floor(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;
    const padded = String(secs).padStart(2, '0');

    return hours > 0
        ? `${hours}:${String(minutes).padStart(2, '0')}:${padded}`
        : `${minutes}:${padded}`;
};
