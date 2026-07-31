import { describe, it, expect, beforeEach, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import axios from 'axios';
import apiClient from './apiClient';
import { setSession, getAccessToken, getRefreshToken } from './authTokens';

const mock = new MockAdapter(axios);
const clientMock = new MockAdapter(apiClient);

beforeEach(() => {
    localStorage.clear();
    mock.reset();
    clientMock.reset();
    window.location.hash = '';
});

describe('apiClient 401 handling', () => {
    it('refreshes and retries the original request', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(200, {
            token: 'fresh',
            refreshToken: 'rotated',
        });
        clientMock.onGet('/calls').replyOnce(401).onGet('/calls').reply(200, { ok: true });

        const res = await apiClient.get('/calls');

        expect(res.data).toEqual({ ok: true });
        expect(getAccessToken()).toBe('fresh');
        expect(getRefreshToken()).toBe('rotated');
        expect(clientMock.history.get[1].headers?.Authorization).toBe('Bearer fresh');
    });

    it('logs out when the refresh itself is rejected with 401', async () => {
        setSession('expired', 'dead-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(401);
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
    });

    it('logs out when the refresh itself is rejected with 403', async () => {
        setSession('expired', 'dead-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(403);
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
    });

    it('leaves the session intact when the refresh request fails with a network error', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).networkError();
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        // A dropped connection to /auth/refresh is not the server rejecting the
        // session — losing connectivity for 15 minutes is routine, and logging out
        // here would reintroduce the spurious-logout bug this feature exists to fix.
        expect(getAccessToken()).toBe('expired');
        expect(getRefreshToken()).toBe('good-refresh');
        expect(window.location.hash).toBe('');
    });

    it('leaves the session intact when the refresh request fails with a 500', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(500);
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBe('expired');
        expect(getRefreshToken()).toBe('good-refresh');
        expect(window.location.hash).toBe('');
    });

    it('logs out when there is no refresh token at all (upgraded install)', async () => {
        localStorage.setItem('token', 'legacy-7day-token');
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
    });

    // Rotation is single-use, so a second concurrent refresh would invalidate the first.
    it('issues exactly one refresh for concurrent 401s', async () => {
        setSession('expired', 'good-refresh');
        const refreshSpy = vi.fn().mockReturnValue([200, { token: 'fresh', refreshToken: 'rotated' }]);
        mock.onPost(/\/auth\/refresh$/).reply(refreshSpy);
        clientMock
            .onGet('/a').replyOnce(401).onGet('/a').reply(200, { r: 'a' })
            .onGet('/b').replyOnce(401).onGet('/b').reply(200, { r: 'b' })
            .onGet('/c').replyOnce(401).onGet('/c').reply(200, { r: 'c' });

        const results = await Promise.all([
            apiClient.get('/a'),
            apiClient.get('/b'),
            apiClient.get('/c'),
        ]);

        expect(results.map((r) => r.data.r)).toEqual(['a', 'b', 'c']);
        expect(refreshSpy).toHaveBeenCalledTimes(1);
    });

    it('does not retry the same request twice', async () => {
        setSession('expired', 'good-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(200, { token: 'fresh', refreshToken: 'rotated' });
        clientMock.onGet('/calls').reply(401);   // still 401 even after refresh

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();
        expect(clientMock.history.get.filter((r) => r.url === '/calls')).toHaveLength(2);

        // A 401 on the retried request despite a fresh, valid token means the endpoint
        // denied this specific request, not that the session died — must not log out.
        expect(getAccessToken()).toBe('fresh');
        expect(window.location.hash).toBe('');
    });

    it('passes non-401 errors through untouched', async () => {
        setSession('good', 'good-refresh');
        clientMock.onGet('/calls').reply(500);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();
        expect(getAccessToken()).toBe('good');
    });
});
