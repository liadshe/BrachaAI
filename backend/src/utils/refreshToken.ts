import crypto from 'crypto';

export const REFRESH_TOKEN_TTL_DAYS = 90;

/**
 * An opaque 256-bit handle, deliberately not a JWT: it must be revocable by
 * deleting the stored row, which a self-validating token could not be.
 */
export const generateRefreshToken = (): string =>
    crypto.randomBytes(32).toString('hex');

/** Only this value is persisted — a database leak must not yield live sessions. */
export const hashRefreshToken = (raw: string): string =>
    crypto.createHash('sha256').update(raw).digest('hex');

export const refreshTokenExpiry = (now: Date = new Date()): Date => {
    const expiry = new Date(now.getTime());
    expiry.setDate(expiry.getDate() + REFRESH_TOKEN_TTL_DAYS);
    return expiry;
};
