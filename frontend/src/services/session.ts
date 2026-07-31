import apiClient from './apiClient';
import { setSession, clearSession, getRefreshToken } from './authTokens';

interface LoginResponse {
    token: string;
    refreshToken: string;
    user: unknown;
}

/**
 * Provisions (or tops up) native's own token pair via /auth/device-token and pushes it
 * across the bridge. This is the *only* thing that keeps native's session alive now that
 * AuthLanding sends a returning user straight to /home instead of through login: without
 * it, native's token would eventually expire with no way to renew, the background
 * uploader would 401 forever, and the queued-transcript/durability logic would delete
 * recordings whose transcripts can never be delivered.
 *
 * window.BrachaNative.setAuth also triggers CallMonitorService.requestFlush on the native
 * side (see MainActivity.kt), so this call doubles as "retry the pending-upload queue".
 *
 * Must never throw into a caller's render/navigation path — failures here only affect a
 * background upload, and that retries on its own.
 */
export const provisionNativeSession = async (): Promise<void> => {
    if (!window.BrachaNative) {
        return;
    }

    try {
        const { data } = await apiClient.post('/auth/device-token');
        window.BrachaNative.setAuth(data.token, data.refreshToken);
    } catch (error) {
        console.error('Could not provision the native device token:', error);
    }
};

/**
 * Establishes both sessions. The WebView is the only thing that can perform a login, so
 * it also provisions the background uploader's credentials — a *separate* pair, because
 * rotation is per client and sharing one would make the two invalidate each other.
 */
export const establishSession = async (response: LoginResponse): Promise<void> => {
    if (!response?.token || !response?.refreshToken) {
        throw new Error('Login response missing token or refreshToken');
    }

    setSession(response.token, response.refreshToken, response.user);

    await provisionNativeSession();
};

export const endSession = async (): Promise<void> => {
    const refreshToken = getRefreshToken();

    try {
        if (refreshToken) {
            await apiClient.post('/auth/logout', { refreshToken });
        }
    } catch (error) {
        // Local state is the source of truth for the UI; a failed revoke must not trap
        // the user in a session they have already left.
        console.error('Server-side logout failed:', error);
    }

    clearSession();
    window.BrachaNative?.clearAuth();
};
