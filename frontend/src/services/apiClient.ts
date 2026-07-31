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

/**
 * Shared in-flight refresh. Refresh tokens are single-use, so two concurrent refreshes
 * would rotate each other out and log the user out spuriously — every 401 that arrives
 * while one is running must wait for that same promise.
 */
let refreshInFlight: Promise<string> | null = null;

const performRefresh = async (): Promise<string> => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
        throw new Error('No refresh token stored');
    }

    // Deliberately a bare axios call, not apiClient: routing it through the instance
    // would re-enter this interceptor on failure and recurse.
    const { data } = await axios.post(`${baseURL}/auth/refresh`, {
        refreshToken,
        client: 'web',
    });

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

apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
        const original = error.config;

        if (error.response?.status !== 401 || !original || original._retry) {
            return Promise.reject(error);
        }

        original._retry = true;

        try {
            if (!refreshInFlight) {
                refreshInFlight = performRefresh().finally(() => {
                    refreshInFlight = null;
                });
            }
            const token = await refreshInFlight;

            original.headers = original.headers ?? {};
            original.headers.Authorization = `Bearer ${token}`;
            return apiClient(original);
        } catch {
            console.warn('Refresh failed. Logging out user.');
            failSession();
            return Promise.reject(error);
        }
    }
);

export default apiClient;
