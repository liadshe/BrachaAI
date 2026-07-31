import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import { getAccessToken } from '@/services/authTokens';
import { provisionNativeSession } from '@/services/session';

/**
 * The app boots at file:///android_asset/www/index.html with no hash, so HashRouter
 * resolves to "/". That route used to render LoginPage unconditionally, which is why a
 * perfectly good stored token was ignored on every launch.
 *
 * Only presence is checked. An expired access token is handled by the refresh
 * interceptor in apiClient, not here — route guards must stay synchronous.
 *
 * A returning user never passes through login again, which used to be the only place
 * that topped up native's separate token pair. Fire off that top-up here, in an effect,
 * so it can never delay or fail this redirect — see provisionNativeSession for why it
 * matters (it is the only thing keeping native's session from expiring for good).
 */
const AuthLanding: React.FC = () => {
    const token = getAccessToken();

    useEffect(() => {
        if (token) {
            void provisionNativeSession();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return <Navigate to={token ? '/home' : '/login'} replace />;
};

export default AuthLanding;
