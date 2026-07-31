import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
    getAccessToken,
    getRefreshToken,
    setSession,
    clearSession,
    hydrateSessionFromNative,
    getStoredUser,
} from './authTokens';

const setWebSession = vi.fn();
const getWebSession = vi.fn();
const clearWebSession = vi.fn();

beforeEach(() => {
    localStorage.clear();
    setWebSession.mockReset();
    getWebSession.mockReset();
    clearWebSession.mockReset();
    delete (window as any).BrachaNative;
});

const installBridge = () => {
    (window as any).BrachaNative = { setWebSession, getWebSession, clearWebSession };
};

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

// file:// localStorage does not survive the app process being killed, so native holds the
// durable copy. These cover the actual reported bug: log in, swipe the app away, reopen.
describe('native mirroring', () => {
    it('mirrors the session to native on every write', () => {
        installBridge();
        setSession('acc', 'ref', { id: '1', name: 'Ben' });

        expect(setWebSession).toHaveBeenCalledWith('acc', 'ref', JSON.stringify({ id: '1', name: 'Ben' }));
    });

    // Refresh tokens rotate on use. A mirror that missed a rotation would restore a dead
    // token on the next launch — a logout indistinguishable from the bug being fixed.
    it('re-mirrors after a token rotation', () => {
        installBridge();
        setSession('acc', 'ref', { id: '1' });
        setSession('rotated-acc', 'rotated-ref');

        expect(setWebSession).toHaveBeenLastCalledWith('rotated-acc', 'rotated-ref', JSON.stringify({ id: '1' }));
    });

    it('tells native to drop the mirror on logout', () => {
        installBridge();
        setSession('acc', 'ref', { id: '1' });
        clearSession();

        expect(clearWebSession).toHaveBeenCalled();
    });

    it('does not error when no bridge is present', () => {
        expect(() => setSession('acc', 'ref')).not.toThrow();
        expect(() => clearSession()).not.toThrow();
    });
});

describe('hydrateSessionFromNative', () => {
    it('restores a session localStorage has lost', () => {
        installBridge();
        getWebSession.mockReturnValue(
            JSON.stringify({ token: 'acc', refreshToken: 'ref', user: JSON.stringify({ id: '1' }) })
        );

        expect(hydrateSessionFromNative()).toBe(true);
        expect(getAccessToken()).toBe('acc');
        expect(getRefreshToken()).toBe('ref');
        expect(getStoredUser()).toEqual({ id: '1' });
    });

    // localStorage wins when it has a value: it may hold a newer rotation than the mirror.
    it('leaves an existing localStorage session untouched', () => {
        installBridge();
        localStorage.setItem('token', 'live');
        localStorage.setItem('refreshToken', 'live-ref');

        expect(hydrateSessionFromNative()).toBe(false);
        expect(getAccessToken()).toBe('live');
        expect(getWebSession).not.toHaveBeenCalled();
    });

    it('does nothing when native has no mirror', () => {
        installBridge();
        getWebSession.mockReturnValue('');

        expect(hydrateSessionFromNative()).toBe(false);
        expect(getAccessToken()).toBeNull();
    });

    it('does nothing in a plain browser with no bridge', () => {
        expect(hydrateSessionFromNative()).toBe(false);
        expect(getAccessToken()).toBeNull();
    });

    it('survives a corrupt mirror rather than bricking startup', () => {
        installBridge();
        getWebSession.mockReturnValue('{not json');

        expect(hydrateSessionFromNative()).toBe(false);
        expect(getAccessToken()).toBeNull();
    });

    it('ignores a mirror missing the refresh token', () => {
        installBridge();
        getWebSession.mockReturnValue(JSON.stringify({ token: 'acc' }));

        expect(hydrateSessionFromNative()).toBe(false);
        expect(getAccessToken()).toBeNull();
    });

    it('survives the bridge throwing', () => {
        installBridge();
        getWebSession.mockImplementation(() => { throw new Error('bridge blew up'); });

        expect(hydrateSessionFromNative()).toBe(false);
    });
});

// A throw here unmounts the React tree, which is what produced the blank white screen on
// upgraded installs.
describe('getStoredUser', () => {
    it('returns the stored profile', () => {
        localStorage.setItem('user', JSON.stringify({ id: '1', name: 'Ben' }));
        expect(getStoredUser()).toEqual({ id: '1', name: 'Ben' });
    });

    it('returns {} when nothing is stored', () => {
        expect(getStoredUser()).toEqual({});
    });

    // An older build could write this via JSON.stringify(undefined).
    it('returns {} for the literal string "undefined" instead of throwing', () => {
        localStorage.setItem('user', 'undefined');
        expect(() => getStoredUser()).not.toThrow();
        expect(getStoredUser()).toEqual({});
    });

    it('returns {} for malformed JSON', () => {
        localStorage.setItem('user', '{"half":');
        expect(getStoredUser()).toEqual({});
    });

    it('returns {} when the stored value is not an object', () => {
        localStorage.setItem('user', '"just a string"');
        expect(getStoredUser()).toEqual({});
    });
});
