import { describe, it, expect } from 'vitest';
import { isObjectId, validateObjectIdList, MAX_BULK_IDS } from './objectId';

const VALID_A = '507f1f77bcf86cd799439011';
const VALID_B = '507f191e810c19729de860ea';

describe('isObjectId', () => {
    it('accepts a 24-character hex string', () => {
        expect(isObjectId(VALID_A)).toBe(true);
    });

    it('accepts uppercase hex', () => {
        expect(isObjectId(VALID_A.toUpperCase())).toBe(true);
    });

    it('rejects a 12-character string', () => {
        // Mongoose's own ObjectId.isValid accepts any 12-character string,
        // which would let "hello world!" through. This must not.
        expect(isObjectId('hello world!')).toBe(false);
    });

    it('rejects non-hex characters', () => {
        expect(isObjectId('507f1f77bcf86cd7994390zz')).toBe(false);
    });

    it('rejects non-strings', () => {
        expect(isObjectId(42)).toBe(false);
        expect(isObjectId(null)).toBe(false);
        expect(isObjectId(undefined)).toBe(false);
        expect(isObjectId({})).toBe(false);
    });
});

describe('validateObjectIdList', () => {
    it('accepts a list of valid ids', () => {
        const result = validateObjectIdList([VALID_A, VALID_B]);
        expect(result).toEqual({ ok: true, ids: [VALID_A, VALID_B] });
    });

    it('rejects a missing value', () => {
        const result = validateObjectIdList(undefined);
        expect(result.ok).toBe(false);
    });

    it('rejects a non-array', () => {
        const result = validateObjectIdList(VALID_A);
        expect(result.ok).toBe(false);
    });

    it('rejects an empty array', () => {
        const result = validateObjectIdList([]);
        expect(result.ok).toBe(false);
    });

    it('rejects more than MAX_BULK_IDS entries', () => {
        const tooMany = Array.from({ length: MAX_BULK_IDS + 1 }, () => VALID_A);
        const result = validateObjectIdList(tooMany);
        expect(result.ok).toBe(false);
    });

    it('accepts exactly MAX_BULK_IDS entries', () => {
        const atLimit = Array.from({ length: MAX_BULK_IDS }, () => VALID_A);
        expect(validateObjectIdList(atLimit).ok).toBe(true);
    });

    it('rejects the whole list when one id is malformed', () => {
        const result = validateObjectIdList([VALID_A, 'not-an-id']);
        expect(result.ok).toBe(false);
    });
});
