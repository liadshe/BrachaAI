/**
 * Single owner of session storage keys. `token` and `user` keep their historical names
 * so existing installs are not logged out by the upgrade itself.
 */
export const STORAGE_KEYS = {
    token: 'token',
    refreshToken: 'refreshToken',
    user: 'user',
} as const;

export const getAccessToken = (): string | null =>
    localStorage.getItem(STORAGE_KEYS.token);

export const getRefreshToken = (): string | null =>
    localStorage.getItem(STORAGE_KEYS.refreshToken);

export const setSession = (token: string, refreshToken: string, user?: unknown): void => {
    localStorage.setItem(STORAGE_KEYS.token, token);
    localStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken);
    if (user !== undefined) {
        localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
    }
    mirrorToNative();
};

export const clearSession = (): void => {
    localStorage.removeItem(STORAGE_KEYS.token);
    localStorage.removeItem(STORAGE_KEYS.refreshToken);
    localStorage.removeItem(STORAGE_KEYS.user);
    window.BrachaNative?.clearWebSession?.();
};

/**
 * Copies the current session into native's EncryptedSharedPreferences.
 *
 * The WebView runs from a file:// origin, whose localStorage does NOT survive the app
 * process being killed — verified on device: backgrounding the app keeps the session,
 * swiping it away loses it. Android exposes no API to force a localStorage flush, so
 * native storage is the only durable copy.
 *
 * Deliberately called from inside setSession rather than by its callers: refresh tokens
 * rotate on every use, and a mirror that missed a rotation would restore an already-dead
 * refresh token on the next launch — a logout that looks exactly like the bug this fixes.
 */
const mirrorToNative = (): void => {
    const token = localStorage.getItem(STORAGE_KEYS.token);
    const refreshToken = localStorage.getItem(STORAGE_KEYS.refreshToken);
    if (!token || !refreshToken) return;

    window.BrachaNative?.setWebSession?.(
        token,
        refreshToken,
        localStorage.getItem(STORAGE_KEYS.user) ?? ''
    );
};

/**
 * Restores the session from native storage when localStorage has lost it.
 *
 * Must run before anything reads the token — see index.tsx, where it is called before
 * React renders, since AuthLanding decides the app's entire startup route on it.
 *
 * Writes localStorage directly rather than via setSession: the values came *from* native,
 * so mirroring them straight back is pointless work.
 *
 * @returns true if a session was restored.
 */
export const hydrateSessionFromNative = (): boolean => {
    if (localStorage.getItem(STORAGE_KEYS.token)) return false;

    let raw: string | undefined;
    try {
        raw = window.BrachaNative?.getWebSession?.();
    } catch {
        return false;
    }
    if (!raw) return false;

    try {
        const { token, refreshToken, user } = JSON.parse(raw);
        if (!token || !refreshToken) return false;

        localStorage.setItem(STORAGE_KEYS.token, token);
        localStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken);
        if (user) {
            localStorage.setItem(STORAGE_KEYS.user, user);
        }
        return true;
    } catch {
        // A corrupt mirror must not brick startup; the user just logs in again.
        return false;
    }
};

/**
 * The stored user profile, or `{}` if absent or unparseable.
 *
 * Callers used to inline `JSON.parse(localStorage.getItem('user') || '{}')` during render.
 * That throws on malformed stored state — including the literal string "undefined", which
 * an older build could write — and a throw during render unmounts the whole tree, which
 * showed up as a blank white screen on upgraded installs.
 */
export const getStoredUser = (): Record<string, any> => {
    const raw = localStorage.getItem(STORAGE_KEYS.user);
    if (!raw) return {};
    try {
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
        return {};
    }
};
