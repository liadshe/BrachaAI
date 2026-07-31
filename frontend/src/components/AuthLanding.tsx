import { Navigate } from 'react-router-dom';

/**
 * The app boots at file:///android_asset/www/index.html with no hash, so HashRouter
 * resolves to "/". That route used to render LoginPage unconditionally, which is why a
 * perfectly good stored token was ignored on every launch.
 *
 * Only presence is checked. An expired access token is handled by the refresh
 * interceptor in apiClient, not here — route guards must stay synchronous.
 */
const AuthLanding: React.FC = () => {
    const token = localStorage.getItem('token');

    return <Navigate to={token ? '/home' : '/login'} replace />;
};

export default AuthLanding;
