import apiClient from './apiClient';
import { setSession, clearSession, getRefreshToken } from './authTokens';

interface LoginResponse {
    token: string;
    refreshToken: string;
    user: unknown;
}

/**
 * Establishes both sessions. The WebView is the only thing that can perform a login, so
 * it also provisions the background uploader's credentials — a *separate* pair, because
 * rotation is per client and sharing one would make the two invalidate each other.
 */
export const establishSession = async (response: LoginResponse): Promise<void> => {
    setSession(response.token, response.refreshToken, response.user);

    try {
        const { data } = await apiClient.post('/auth/device-token');
        window.BrachaNative?.setAuth(data.token, data.refreshToken);
    } catch (error) {
        // The web login has already succeeded; only background uploads are affected, and
        // they retry from the pending queue once native gets a token on a later login.
        console.error('Could not provision the native device token:', error);
    }
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
