import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import SettingsPage from './SettingsPage';

const clientMock = new MockAdapter(apiClient);

const ROW = 'Automatic Call Recording';

const renderPage = () =>
    render(
        <MemoryRouter>
            <SettingsPage />
        </MemoryRouter>
    );

beforeEach(() => {
    clientMock.reset();
    localStorage.clear();
});

afterEach(() => {
    delete (window as any).BrachaNative;
});

/**
 * The app cannot start a recording — the phone's dialer does that — so this row is a
 * shortcut to the dialer's setting and nothing else. Where there is no dialer to open, the
 * row must not exist at all.
 */
describe('SettingsPage automatic call recording row', () => {
    it('is absent in a plain browser', () => {
        renderPage();

        expect(screen.queryByText(ROW)).toBeNull();
    });

    it('is absent inside an older APK whose bridge predates the method', () => {
        (window as any).BrachaNative = { setAuth: vi.fn(), clearAuth: vi.fn() };

        renderPage();

        expect(screen.queryByText(ROW)).toBeNull();
    });

    it('opens the phone settings when tapped', () => {
        const openCallRecordingSettings = vi.fn();
        (window as any).BrachaNative = { openCallRecordingSettings };

        renderPage();
        fireEvent.click(screen.getByText(ROW).closest('button')!);

        expect(openCallRecordingSettings).toHaveBeenCalledTimes(1);
    });

    it('offers no switch, and writes nothing to the backend', () => {
        (window as any).BrachaNative = { openCallRecordingSettings: vi.fn() };

        renderPage();
        const row = screen.getByText(ROW).closest('button')!;

        expect(row.querySelector('input[type="checkbox"]')).toBeNull();
        expect(screen.getByText('Enable it in your Phone app settings')).toBeTruthy();

        fireEvent.click(row);

        expect(clientMock.history.put).toHaveLength(0);
    });

    it('hides the whole Call Settings card in a plain browser', () => {
        renderPage();

        expect(screen.queryByText('Call Settings')).toBeNull();
    });
});
