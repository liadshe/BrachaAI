import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import HomePage from './HomePage';

const clientMock = new MockAdapter(apiClient);

const renderPage = () =>
    render(
        <MemoryRouter>
            <HomePage />
        </MemoryRouter>
    );

const call = (overrides: Record<string, unknown> = {}) => ({
    _id: '507f191e810c19729de860ea',
    contactId: { _id: '507f191e810c19729de860eb', name: 'David Cohen' },
    callSummary: 'Booked catering for the 2nd of July.',
    callDateTime: '2026-04-15T13:57:02.000Z',
    ...overrides,
});

beforeEach(() => {
    clientMock.reset();
    clientMock.onGet('/tasks/summary').reply(200, { open: 0, overdue: 0, closedToday: 0 });
});

describe('HomePage call duration', () => {
    it('shows how long the call lasted next to the time it happened', async () => {
        clientMock.onGet('/calls').reply(200, [call({ callLength: 272 })]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.getByText(/4:32/)).toBeTruthy();
    });

    it('renders a sub-minute call as seconds rather than collapsing it to zero', async () => {
        clientMock.onGet('/calls').reply(200, [call({ callLength: 45 })]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.getByText(/0:45/)).toBeTruthy();
    });

    it('shows the time alone when the call carries no duration', async () => {
        // Every call recorded before callLength shipped, and any call the phone
        // could not measure. Showing a fabricated "0 min" is worse than showing nothing.
        clientMock.onGet('/calls').reply(200, [call()]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(/•/)).toBeNull();
    });

    it('shows the time alone when the duration is an explicit null', async () => {
        // What the API sends for a call the phone could not measure.
        clientMock.onGet('/calls').reply(200, [call({ callLength: null })]);

        renderPage();

        await screen.findByText('David Cohen');
        expect(screen.queryByText(/•/)).toBeNull();
    });
});
