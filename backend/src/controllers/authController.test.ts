import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/refreshTokenService', () => ({
    issueRefreshToken: vi.fn(),
    rotateRefreshToken: vi.fn(),
    revokeRefreshToken: vi.fn(),
}));

import * as refreshTokenService from '../services/refreshTokenService';
import { refresh, logout, deviceToken } from './authController';

const USER_ID = '507f1f77bcf86cd799439011';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    res.send = vi.fn().mockReturnValue(res);
    return res;
};

beforeEach(() => {
    vi.mocked(refreshTokenService.rotateRefreshToken).mockReset();
    vi.mocked(refreshTokenService.revokeRefreshToken).mockReset().mockResolvedValue(undefined);
    vi.mocked(refreshTokenService.issueRefreshToken).mockReset().mockResolvedValue('new-refresh');
});

describe('refresh', () => {
    it('returns a new access token and the rotated refresh token', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });
        const res = makeRes();

        await refresh({ body: { refreshToken: 'old', client: 'web' } } as any, res);

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'web');
        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(payload.refreshToken).toBe('rotated-refresh');
        expect(typeof payload.token).toBe('string');
    });

    it('defaults the client to web when omitted', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });

        await refresh({ body: { refreshToken: 'old' } } as any, makeRes());

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'web');
    });

    it('passes the native client through', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });

        await refresh({ body: { refreshToken: 'old', client: 'native' } } as any, makeRes());

        expect(refreshTokenService.rotateRefreshToken).toHaveBeenCalledWith('old', 'native');
    });

    it('returns 400 when no refresh token is supplied', async () => {
        const res = makeRes();
        await refresh({ body: {} } as any, res);
        expect(res.status).toHaveBeenCalledWith(400);
        expect(refreshTokenService.rotateRefreshToken).not.toHaveBeenCalled();
    });

    it('returns 401 when the token is rejected', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue(null);
        const res = makeRes();

        await refresh({ body: { refreshToken: 'bad' } } as any, res);

        expect(res.status).toHaveBeenCalledWith(401);
    });

    it('rejects an unknown client value without hitting the service', async () => {
        const res = makeRes();
        await refresh({ body: { refreshToken: 'x', client: 'evil' } } as any, res);
        expect(res.status).toHaveBeenCalledWith(400);
        expect(refreshTokenService.rotateRefreshToken).not.toHaveBeenCalled();
    });
});

describe('logout', () => {
    it('revokes the presented token and returns 204', async () => {
        const res = makeRes();
        await logout({ body: { refreshToken: 'tok' } } as any, res);
        expect(refreshTokenService.revokeRefreshToken).toHaveBeenCalledWith('tok');
        expect(res.status).toHaveBeenCalledWith(204);
    });

    // Logging out without a stored token must still succeed, or the UI can get stuck.
    it('succeeds when no token is supplied', async () => {
        const res = makeRes();
        await logout({ body: {} } as any, res);
        expect(refreshTokenService.revokeRefreshToken).not.toHaveBeenCalled();
        expect(res.status).toHaveBeenCalledWith(204);
    });
});

describe('deviceToken', () => {
    it('issues a native pair for the authenticated user', async () => {
        const res = makeRes();

        await deviceToken({ user: { id: USER_ID } } as any, res);

        expect(refreshTokenService.issueRefreshToken).toHaveBeenCalledWith(USER_ID, 'native');
        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(payload.refreshToken).toBe('new-refresh');
        expect(typeof payload.token).toBe('string');
    });
});
