import { describe, it, expect, beforeEach } from 'vitest';
import {
    getAccessToken,
    getRefreshToken,
    setSession,
    clearSession,
} from './authTokens';

beforeEach(() => {
    localStorage.clear();
});

describe('authTokens', () => {
    it('round-trips a session', () => {
        setSession('acc', 'ref', { id: '1', name: 'Ben' });

        expect(getAccessToken()).toBe('acc');
        expect(getRefreshToken()).toBe('ref');
        expect(JSON.parse(localStorage.getItem('user')!)).toEqual({ id: '1', name: 'Ben' });
    });

    it('leaves the stored user alone when none is supplied', () => {
        setSession('acc', 'ref', { id: '1', name: 'Ben' });
        setSession('acc2', 'ref2');

        expect(getAccessToken()).toBe('acc2');
        expect(JSON.parse(localStorage.getItem('user')!)).toEqual({ id: '1', name: 'Ben' });
    });

    it('clears everything', () => {
        setSession('acc', 'ref', { id: '1' });
        clearSession();

        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
        expect(localStorage.getItem('user')).toBeNull();
    });

    it('reports null for a missing token', () => {
        expect(getAccessToken()).toBeNull();
        expect(getRefreshToken()).toBeNull();
    });
});
