export const MAX_BULK_IDS = 200;

const OBJECT_ID_PATTERN = /^[0-9a-f]{24}$/i;

/**
 * Mongoose's ObjectId.isValid() returns true for any 12-character string,
 * so it cannot be used to validate untrusted input. This checks the actual
 * 24-character hex form that arrives over JSON.
 */
export const isObjectId = (value: unknown): value is string =>
    typeof value === 'string' && OBJECT_ID_PATTERN.test(value);

export type IdListValidation =
    | { ok: true; ids: string[] }
    | { ok: false; message: string };

export const validateObjectIdList = (value: unknown): IdListValidation => {
    if (!Array.isArray(value)) {
        return { ok: false, message: 'ids must be an array' };
    }
    if (value.length === 0) {
        return { ok: false, message: 'ids must not be empty' };
    }
    if (value.length > MAX_BULK_IDS) {
        return { ok: false, message: `ids must contain at most ${MAX_BULK_IDS} items` };
    }
    for (const id of value) {
        if (!isObjectId(id)) {
            return { ok: false, message: 'ids must all be valid object ids' };
        }
    }
    return { ok: true, ids: value as string[] };
};
