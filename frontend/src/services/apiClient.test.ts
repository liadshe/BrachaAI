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
    });

    it('logs out when the refresh itself fails', async () => {
        setSession('expired', 'dead-refresh');
        mock.onPost(/\/auth\/refresh$/).reply(401);
        clientMock.onGet('/calls').reply(401);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(window.location.hash).toBe('#/login');
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
    });

    it('passes non-401 errors through untouched', async () => {
        setSession('good', 'good-refresh');
        clientMock.onGet('/calls').reply(500);

        await expect(apiClient.get('/calls')).rejects.toBeTruthy();
        expect(getAccessToken()).toBe('good');
    });
});
