import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import AuthLanding from './AuthLanding';

const clientMock = new MockAdapter(apiClient);
const setAuth = vi.fn();
const clearAuth = vi.fn();

const renderAt = () =>
    render(
        <MemoryRouter initialEntries={['/']}>
            <Routes>
                <Route path="/" element={<AuthLanding />} />
                <Route path="/home" element={<div>HOME</div>} />
                <Route path="/login" element={<div>LOGIN</div>} />
            </Routes>
        </MemoryRouter>
    );

beforeEach(() => {
    localStorage.clear();
    clientMock.reset();
    setAuth.mockReset();
    clearAuth.mockReset();
    (window as any).BrachaNative = { setAuth, clearAuth };
});

describe('AuthLanding', () => {
    // The actual bug: a stored token was ignored and the user saw login every launch.
    it('sends an authenticated user to home', () => {
        localStorage.setItem('token', 'stored-token');
        renderAt();
        expect(screen.getByText('HOME')).toBeTruthy();
    });

    it('sends an unauthenticated user to login', () => {
        renderAt();
        expect(screen.getByText('LOGIN')).toBeTruthy();
    });

    it('treats an empty token as unauthenticated', () => {
        localStorage.setItem('token', '');
        renderAt();
        expect(screen.getByText('LOGIN')).toBeTruthy();
    });

    // Native's token is never refreshed by login again once a stored token sends the
    // user straight home, so this boot-time top-up is the only thing keeping it alive.
    it('provisions the native session when a token is present', async () => {
        localStorage.setItem('token', 'stored-token');
        clientMock.onPost('/auth/device-token').reply(200, {
            token: 'native-access',
            refreshToken: 'native-refresh',
        });

        renderAt();

        await waitFor(() => {
            expect(setAuth).toHaveBeenCalledWith('native-access', 'native-refresh');
        });
    });

    it('does not provision the native session when there is no token', async () => {
        renderAt();

        // Nothing to await on, so give any stray microtask a turn before asserting.
        await new Promise((resolve) => setTimeout(resolve, 0));

        expect(clientMock.history.post.length).toBe(0);
        expect(setAuth).not.toHaveBeenCalled();
    });

    it('still navigates home immediately even if device-token provisioning fails', async () => {
        localStorage.setItem('token', 'stored-token');
        clientMock.onPost('/auth/device-token').reply(500);

        renderAt();

        // The redirect must not wait on the network call.
        expect(screen.getByText('HOME')).toBeTruthy();

        await waitFor(() => {
            expect(clientMock.history.post.length).toBe(1);
        });
        expect(setAuth).not.toHaveBeenCalled();
    });
});
