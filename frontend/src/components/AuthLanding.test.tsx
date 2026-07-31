import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import AuthLanding from './AuthLanding';

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
});
