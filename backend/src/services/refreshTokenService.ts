import RefreshToken, { RefreshClient } from '../models/RefreshToken';
import {
    generateRefreshToken,
    hashRefreshToken,
    refreshTokenExpiry,
} from '../utils/refreshToken';

/**
 * Issues a fresh refresh token for one client, replacing whatever that client held.
 * The upsert on (userId, client) is what keeps web and native independent.
 */
export const issueRefreshToken = async (
    userId: string,
    client: RefreshClient
): Promise<string> => {
    const raw = generateRefreshToken();

    await RefreshToken.findOneAndUpdate(
        { userId, client },
        {
            userId,
            client,
            tokenHash: hashRefreshToken(raw),
            expiresAt: refreshTokenExpiry(),
            createdAt: new Date(),
        },
        { upsert: true, new: true }
    );

    return raw;
};

/**
 * Verifies and rotates. Returns null for anything the caller should treat as a real
 * logout — unknown token, wrong client, or expired.
 */
export const rotateRefreshToken = async (
    raw: string,
    client: RefreshClient
): Promise<{ userId: string; refreshToken: string } | null> => {
    const existing = await RefreshToken.findOne({
        tokenHash: hashRefreshToken(raw),
        client,
    });

    if (!existing) {
        return null;
    }

    if (existing.expiresAt.getTime() <= Date.now()) {
        await RefreshToken.deleteOne({ _id: existing._id });
        return null;
    }

    const userId = existing.userId.toString();
    const refreshToken = await issueRefreshToken(userId, client);

    return { userId, refreshToken };
};

/** Used by logout. Deletes only the row for the presented token. */
export const revokeRefreshToken = async (raw: string): Promise<void> => {
    await RefreshToken.deleteOne({ tokenHash: hashRefreshToken(raw) });
};
