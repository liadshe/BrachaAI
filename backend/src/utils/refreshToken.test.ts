import { describe, it, expect } from 'vitest';
import {
    generateRefreshToken,
    hashRefreshToken,
    refreshTokenExpiry,
    REFRESH_TOKEN_TTL_DAYS,
} from './refreshToken';

describe('generateRefreshToken', () => {
    it('returns 64 hex characters (256 bits)', () => {
        expect(generateRefreshToken()).toMatch(/^[0-9a-f]{64}$/);
    });

    it('does not repeat', () => {
        const tokens = new Set(Array.from({ length: 100 }, generateRefreshToken));
        expect(tokens.size).toBe(100);
    });
});

describe('hashRefreshToken', () => {
    it('is deterministic', () => {
        expect(hashRefreshToken('abc')).toBe(hashRefreshToken('abc'));
    });

    it('differs for different inputs', () => {
        expect(hashRefreshToken('abc')).not.toBe(hashRefreshToken('abd'));
    });

    // The whole point of hashing: a database dump must not yield usable tokens.
    it('never returns the raw token', () => {
        const raw = generateRefreshToken();
        expect(hashRefreshToken(raw)).not.toBe(raw);
    });
});

describe('refreshTokenExpiry', () => {
    it('is REFRESH_TOKEN_TTL_DAYS in the future', () => {
        const now = new Date('2026-01-01T00:00:00Z');
        const expected = new Date('2026-01-01T00:00:00Z');
        expected.setDate(expected.getDate() + REFRESH_TOKEN_TTL_DAYS);
        expect(refreshTokenExpiry(now).toISOString()).toBe(expected.toISOString());
    });

    it('uses a 90 day window', () => {
        expect(REFRESH_TOKEN_TTL_DAYS).toBe(90);
    });
});
