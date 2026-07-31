import { describe, it, expect, beforeEach, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import apiClient from './apiClient';
import { establishSession, endSession } from './session';
import { getAccessToken, getRefreshToken, setSession } from './authTokens';

const clientMock = new MockAdapter(apiClient);

const setAuth = vi.fn();
const clearAuth = vi.fn();

beforeEach(() => {
    localStorage.clear();
    clientMock.reset();
    setAuth.mockReset();
    clearAuth.mockReset();
    (window as any).BrachaNative = { setAuth, clearAuth };
});

describe('establishSession', () => {
    it('stores the web pair and hands native its own separate pair', async () => {
        clientMock.onPost('/auth/device-token').reply(200, {
            token: 'native-access',
            refreshToken: 'native-refresh',
        });

        await establishSession({
            token: 'web-access',
            refreshToken: 'web-refresh',
            user: { id: '1' },
        });

        expect(getAccessToken()).toBe('web-access');
        expect(getRefreshToken()).toBe('web-refresh');
        // Native must NOT receive the web pair — rotation is per client.
        expect(setAuth).toHaveBeenCalledWith('native-access', 'native-refresh');
    });

    it('still completes the web login when device-token provisioning fails', async () => {
        clientMock.onPost('/auth/device-token').reply(500);

        await establishSession({
            token: 'web-access',
            refreshToken: 'web-refresh',
            user: { id: '1' },
        });

        expect(getAccessToken()).toBe('web-access');
        expect(setAuth).not.toHaveBeenCalled();
    });
});

describe('endSession', () => {
    it('revokes server-side, clears storage and tells native', async () => {
        setSession('acc', 'ref', { id: '1' });
        clientMock.onPost('/auth/logout').reply(204);

        await endSession();

        expect(clientMock.history.post[0].data).toContain('ref');
        expect(getAccessToken()).toBeNull();
        expect(clearAuth).toHaveBeenCalled();
    });

    it('clears locally even when the server call fails', async () => {
        setSession('acc', 'ref', { id: '1' });
        clientMock.onPost('/auth/logout').reply(500);

        await endSession();

        expect(getAccessToken()).toBeNull();
        expect(clearAuth).toHaveBeenCalled();
    });
});
