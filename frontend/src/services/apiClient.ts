import axios from 'axios';

const apiClient = axios.create({
    baseURL: '' + (import.meta.env.VITE_API_URL as string),
});

// Automatically inject JWT token into all outgoing requests
apiClient.interceptors.request.use((config) => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
}, (error) => {
    return Promise.reject(error);
});

apiClient.interceptors.response.use((response) => {
    return response;
}, (error) => {
    if (error.response && error.response.status === 401) {
        console.warn('Unauthorized request. Logging out user.');
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.BrachaNative?.clearAuth();
        // The app runs from file:///android_asset/www/index.html under HashRouter, so a
        // path-based redirect resolves to file:///login (ERR_FILE_NOT_FOUND) and dead-ends
        // the app until it's force-stopped. Use a hash redirect instead.
        window.location.hash = '#/login';
    }
    return Promise.reject(error);
});

export default apiClient;
