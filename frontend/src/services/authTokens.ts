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
};

export const clearSession = (): void => {
    localStorage.removeItem(STORAGE_KEYS.token);
    localStorage.removeItem(STORAGE_KEYS.refreshToken);
    localStorage.removeItem(STORAGE_KEYS.user);
};
