import axios from 'axios';

const apiClient = axios.create({
    baseURL: (import.meta.env.VITE_API_URL as string) || 'http://localhost:3000/api',
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

// Optional: Handle 401 Unauthorized globally by logging out the user
apiClient.interceptors.response.use((response) => {
    return response;
}, (error) => {
    if (error.response && error.response.status === 401) {
        console.warn('Unauthorized request. Logging out user.');
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = '/login';
    }
    return Promise.reject(error);
});

export default apiClient;
