import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import ContactsPage from './ContactsPage';

const clientMock = new MockAdapter(apiClient);

/**
 * Anchored on purpose. The page renders BottomNav, which has a "Tasks" nav label, so an
 * unanchored /task/i would match the nav and the no-badge assertions could never pass.
 */
const BADGE_TEXT = /^\d+ tasks?$/;

const renderPage = () =>
    render(
        <MemoryRouter>
            <ContactsPage />
        </MemoryRouter>
    );

const contact = (overrides: Record<string, unknown> = {}) => ({
    _id: '507f191e810c19729de860ea',
    name: 'David Cohen',
    phone: '+972541234567',
    openTaskCount: 0,
    ...overrides,
});

beforeEach(() => {
    clientMock.reset();
});

describe('ContactsPage task badge', () => {
    it('shows the open-task count on the card', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 2 })]);

        renderPage();

        expect(await screen.findByText('2 tasks')).toBeTruthy();
    });

    it('uses the singular for one task', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 1 })]);

        renderPage();

        expect(await screen.findByText('1 task')).toBeTruthy();
    });

    it('shows no badge when the contact has no open tasks', async () => {
        clientMock.onGet('/contacts').reply(200, [contact({ openTaskCount: 0 })]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(BADGE_TEXT)).toBeNull();
    });

    it('shows no badge when the backend omits the count entirely', async () => {
        // An older backend, or a deploy where only the frontend has shipped.
        const { openTaskCount, ...withoutCount } = contact();
        clientMock.onGet('/contacts').reply(200, [withoutCount]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(BADGE_TEXT)).toBeNull();
    });
});
