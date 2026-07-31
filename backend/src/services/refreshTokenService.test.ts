import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/RefreshToken', () => ({
    default: {
        findOneAndUpdate: vi.fn(),
        findOne: vi.fn(),
        deleteOne: vi.fn(),
    },
}));

import RefreshToken from '../models/RefreshToken';
import { hashRefreshToken } from '../utils/refreshToken';
import { issueRefreshToken, rotateRefreshToken, revokeRefreshToken } from './refreshTokenService';

const USER_ID = '507f1f77bcf86cd799439011';

beforeEach(() => {
    vi.mocked(RefreshToken.findOneAndUpdate).mockReset().mockResolvedValue({} as any);
    vi.mocked(RefreshToken.findOne).mockReset();
    vi.mocked(RefreshToken.deleteOne).mockReset().mockResolvedValue({} as any);
});

describe('issueRefreshToken', () => {
    it('returns a raw token and stores only its hash', async () => {
        const raw = await issueRefreshToken(USER_ID, 'web');

        expect(raw).toMatch(/^[0-9a-f]{64}$/);
        const [filter, update] = vi.mocked(RefreshToken.findOneAndUpdate).mock.calls[0];
        expect(filter).toEqual({ userId: USER_ID, client: 'web' });
        expect((update as any).tokenHash).toBe(hashRefreshToken(raw));
        expect(JSON.stringify(update)).not.toContain(raw);
    });

    it('upserts so a client only ever holds one row', async () => {
        await issueRefreshToken(USER_ID, 'native');
        const options = vi.mocked(RefreshToken.findOneAndUpdate).mock.calls[0][2];
        expect(options).toMatchObject({ upsert: true });
    });
});

describe('rotateRefreshToken', () => {
    const future = () => new Date(Date.now() + 60_000);

    it('issues a new token and returns the owning user', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: future(),
        } as any);

        const result = await rotateRefreshToken('old-raw-token', 'web');

        expect(result?.userId).toBe(USER_ID);
        expect(result?.refreshToken).toMatch(/^[0-9a-f]{64}$/);
        expect(result?.refreshToken).not.toBe('old-raw-token');
    });

    it('looks the token up by hash, never by raw value', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue(null);

        await rotateRefreshToken('old-raw-token', 'web');

        expect(RefreshToken.findOne).toHaveBeenCalledWith({
            tokenHash: hashRefreshToken('old-raw-token'),
            client: 'web',
        });
    });

    it('rejects an unknown token', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue(null);
        expect(await rotateRefreshToken('nope', 'web')).toBeNull();
    });

    it('rejects and deletes an expired token', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: new Date(Date.now() - 60_000),
        } as any);

        expect(await rotateRefreshToken('stale', 'web')).toBeNull();
        expect(RefreshToken.deleteOne).toHaveBeenCalledWith({ _id: 'row1' });
    });

    // The per-client isolation guarantee: a web rotation must only touch the web row.
    it('only writes the row for the presented client', async () => {
        vi.mocked(RefreshToken.findOne).mockResolvedValue({
            _id: 'row1',
            userId: { toString: () => USER_ID },
            expiresAt: future(),
        } as any);

        await rotateRefreshToken('old-raw-token', 'web');

        for (const call of vi.mocked(RefreshToken.findOneAndUpdate).mock.calls) {
            expect(call[0]).toEqual({ userId: USER_ID, client: 'web' });
        }
        expect(RefreshToken.deleteOne).not.toHaveBeenCalled();
    });
});

describe('revokeRefreshToken', () => {
    it('deletes by hash', async () => {
        await revokeRefreshToken('some-token');
        expect(RefreshToken.deleteOne).toHaveBeenCalledWith({
            tokenHash: hashRefreshToken('some-token'),
        });
    });
});
