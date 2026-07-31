import axios from 'axios';
import { getAccessToken, getRefreshToken, setSession, clearSession } from './authTokens';

const baseURL = '' + (import.meta.env.VITE_API_URL as string);

const apiClient = axios.create({ baseURL });

apiClient.interceptors.request.use((config) => {
    const token = getAccessToken();
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
}, (error) => Promise.reject(error));

// Distinguishes "there was nothing to refresh with" (legacy install, no refreshToken
// in storage) from a network/server failure talking to /auth/refresh. Both throw with
// no error.response, but only the former means the session is unrecoverable.
class MissingRefreshTokenError extends Error {}

const performRefresh = async (): Promise<string> => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
        throw new MissingRefreshTokenError('No refresh token stored');
    }

    // Deliberately a bare axios call, not apiClient: routing it through the instance
    // would re-enter this interceptor on failure and recurse.
    const { data } = await axios.post(`${baseURL}/auth/refresh`, {
        refreshToken,
        client: 'web',
    });

    // Guard against a malformed backend response silently writing the string
    // "undefined" into localStorage, which would then look like a valid (but
    // permanently broken) refresh token on the next attempt.
    if (!data?.token || !data?.refreshToken) {
        throw new Error('Refresh response missing token or refreshToken');
    }

    setSession(data.token, data.refreshToken);
    return data.token;
};

const failSession = () => {
    clearSession();
    window.BrachaNative?.clearAuth();
    // The app runs from file:///android_asset/www/index.html under HashRouter, so a
    // path-based redirect resolves to file:///login (ERR_FILE_NOT_FOUND) and dead-ends
    // the app until it's force-stopped. Use a hash redirect instead.
    window.location.hash = '#/login';
};

/**
 * Shared in-flight refresh. Refresh tokens are single-use, so two concurrent refreshes
 * would rotate each other out and log the user out spuriously — every 401 that arrives
 * while one is running must wait for that same promise.
 *
 * Failure classification (and the failSession() side effect) lives in this single
 * shared promise chain rather than in each caller's catch block, so a failed refresh
 * that's being awaited by several concurrent 401s still only logs the user out once.
 */
let refreshInFlight: Promise<string> | null = null;

const startRefresh = (): Promise<string> => {
    if (!refreshInFlight) {
        refreshInFlight = performRefresh()
            .catch((refreshError) => {
                const status = refreshError?.response?.status;
                const sessionIsDead = refreshError instanceof MissingRefreshTokenError || status === 401 || status === 403;

                if (sessionIsDead) {
                    // The server (or the absence of a refresh token) authoritatively
                    // rejects the session. Nothing left to retry with — log out.
                    console.warn('Refresh token rejected. Logging out user.', refreshError);
                    failSession();
                } else {
                    // Network drop, timeout, or a 5xx from /auth/refresh. The access
                    // token is now short-lived (15 min), so this is routine — e.g.
                    // losing connectivity mid-refresh. Logging out here would
                    // reintroduce the spurious-logout bug this feature exists to fix.
                    // Leave the session intact; the next request will retry the refresh.
                    console.warn('Refresh request failed transiently. Leaving session intact.', refreshError);
                }

                throw refreshError;
            })
            .finally(() => {
                refreshInFlight = null;
            });
    }
    return refreshInFlight;
};

apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        const original = error.config;

        if (error.response?.status !== 401 || !original || original._retry) {
            return Promise.reject(error);
        }

        original._retry = true;

        let token: string;
        try {
            token = await startRefresh();
        } catch {
            return Promise.reject(error);
        }

        original.headers = original.headers ?? {};
        original.headers.Authorization = `Bearer ${token}`;
        // Deliberately outside the try/catch above: if the retried request still 401s
        // despite a fresh token, that's the endpoint denying this specific request
        // (e.g. forbidden), not proof the session is dead. Let it propagate to the
        // caller as a normal rejection instead of logging the user out.
        return apiClient(original);
    }
);

export default apiClient;
