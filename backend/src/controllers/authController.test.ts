import { describe, it, expect, vi, beforeEach } from 'vitest';
import jwt from 'jsonwebtoken';

vi.mock('../services/refreshTokenService', () => ({
    issueRefreshToken: vi.fn(),
    rotateRefreshToken: vi.fn(),
    revokeRefreshToken: vi.fn(),
}));

vi.mock('../models/User', () => ({
    default: {
        findOne: vi.fn(),
        create: vi.fn(),
    },
}));

vi.mock('bcryptjs', () => ({
    default: {
        hash: vi.fn().mockResolvedValue('hashed-password'),
        compare: vi.fn().mockResolvedValue(true),
    },
}));

import * as refreshTokenService from '../services/refreshTokenService';
import User from '../models/User';
import bcrypt from 'bcryptjs';
import { refresh, logout, deviceToken, signup, login } from './authController';

const USER_ID = '507f1f77bcf86cd799439011';

const baseUserDoc = {
    _id: USER_ID,
    name: 'Ada',
    email: 'ada@example.com',
    phoneNumber: '+15550000000',
    password: 'hashed-password',
    settings: { autoCallRecording: false },
    permissions: { microphone: false, contacts: false },
    profilePicture: undefined,
};

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
    vi.mocked(User.findOne).mockReset();
    vi.mocked(User.create).mockReset();
    vi.mocked(bcrypt.compare).mockReset().mockResolvedValue(true as never);
    vi.mocked(bcrypt.hash).mockReset().mockResolvedValue('hashed-password' as never);
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

// Pins the '7d' TTL staged in backend/src/config/jwt.ts: that value, not a comment, is
// the only thing standing between this release and broken background uploads on the
// shipped Android uploader (see ACCESS_TOKEN_TTL for why). Also pins that the minted
// token's `id` claim matches the rotated/authenticated user, closing a gap where nothing
// previously verified `refresh` signs the *rotated* user's id rather than, say, the id
// embedded in the presented (pre-rotation) refresh token.
describe('access token claims', () => {
    const SEVEN_DAYS_IN_SECONDS = 7 * 24 * 60 * 60;

    it('refresh mints a 7-day token for the rotated user', async () => {
        vi.mocked(refreshTokenService.rotateRefreshToken).mockResolvedValue({
            userId: USER_ID,
            refreshToken: 'rotated-refresh',
        });
        const res = makeRes();

        await refresh({ body: { refreshToken: 'old', client: 'web' } } as any, res);

        const payload = res.json.mock.calls[0][0];
        const decoded = jwt.decode(payload.token) as { id: string; iat: number; exp: number };

        expect(decoded.id).toBe(USER_ID);
        expect(decoded.exp - decoded.iat).toBe(SEVEN_DAYS_IN_SECONDS);
    });

    it('deviceToken mints a 7-day token for the authenticated user', async () => {
        const res = makeRes();

        await deviceToken({ user: { id: USER_ID } } as any, res);

        const payload = res.json.mock.calls[0][0];
        const decoded = jwt.decode(payload.token) as { id: string; iat: number; exp: number };

        expect(decoded.id).toBe(USER_ID);
        expect(decoded.exp - decoded.iat).toBe(SEVEN_DAYS_IN_SECONDS);
    });
});

describe('signup', () => {
    const validBody = {
        name: 'Ada',
        email: 'ada@example.com',
        phoneNumber: '+15550000000',
        password: 'plaintext-pw',
        client: 'web',
    };

    beforeEach(() => {
        // Neither the email nor the phone-number uniqueness check finds an existing user.
        vi.mocked(User.findOne).mockResolvedValue(null as any);
        vi.mocked(User.create).mockResolvedValue(baseUserDoc as any);
    });

    it('returns token, refreshToken and user on success', async () => {
        const res = makeRes();

        await signup({ body: validBody } as any, res);

        expect(res.status).toHaveBeenCalledWith(201);
        const payload = res.json.mock.calls[0][0];
        expect(typeof payload.token).toBe('string');
        expect(payload.refreshToken).toBe('new-refresh');
        expect(payload.user.id).toBe(USER_ID);
    });

    it('degrades to a token-only response when refresh token issuance fails', async () => {
        vi.mocked(refreshTokenService.issueRefreshToken).mockRejectedValue(new Error('mongo down'));
        const res = makeRes();

        await signup({ body: validBody } as any, res);

        // A refresh-token write failure must not turn a working signup into a 500.
        expect(res.status).toHaveBeenCalledWith(201);
        const payload = res.json.mock.calls[0][0];
        expect(typeof payload.token).toBe('string');
        expect('refreshToken' in payload).toBe(false);
        expect(payload.user.id).toBe(USER_ID);
    });
});

describe('login', () => {
    const validBody = { email: 'ada@example.com', password: 'plaintext-pw', client: 'web' };

    beforeEach(() => {
        vi.mocked(User.findOne).mockResolvedValue(baseUserDoc as any);
    });

    it('returns token, refreshToken and user on success', async () => {
        const res = makeRes();

        await login({ body: validBody } as any, res);

        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(typeof payload.token).toBe('string');
        expect(payload.refreshToken).toBe('new-refresh');
        expect(payload.user.id).toBe(USER_ID);
    });

    it('degrades to a token-only response when refresh token issuance fails', async () => {
        vi.mocked(refreshTokenService.issueRefreshToken).mockRejectedValue(new Error('mongo down'));
        const res = makeRes();

        await login({ body: validBody } as any, res);

        // A refresh-token write failure must not turn a working login into a 500.
        expect(res.status).toHaveBeenCalledWith(200);
        const payload = res.json.mock.calls[0][0];
        expect(typeof payload.token).toBe('string');
        expect('refreshToken' in payload).toBe(false);
        expect(payload.user.id).toBe(USER_ID);
    });
});
