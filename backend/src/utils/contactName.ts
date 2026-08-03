/**
 * The label phone recorders put in front of the other party's name, which the Android
 * client reads straight out of the recording's filename — "Call Mom_260415_165702.m4a".
 * It names the recording, not the person, so it must not become the contact.
 *
 * Anchored and followed by a separator so a contact genuinely called "Callie" or
 * "Caller ID" is left alone, and replaced once so "Call Call Center" keeps its name.
 */
const CALL_PREFIX = /^call[ _]+/i;

/**
 * Strips the recorder's label from a contact name.
 *
 * Also applied server-side, not just in the Android parser that produces it: already
 * installed clients and uploads sitting in `PendingUploadStore` keep sending the old
 * name, and every one of them would otherwise create a "Call ..." contact.
 *
 * A name that is *only* the label is left as-is — a nameless contact is worse than a
 * badly named one. Non-string input passes through untouched so this can sit in front
 * of callers that don't validate their input.
 */
export const stripCallPrefix = <T>(name: T): T | string => {
    if (typeof name !== 'string') return name;

    const stripped = name.replace(CALL_PREFIX, '').trim();
    return stripped || name.trim();
};
