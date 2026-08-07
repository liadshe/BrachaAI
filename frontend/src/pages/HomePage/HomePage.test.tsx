import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import { LONG_PRESS_MS } from '@/hooks/useMultiSelect';
import HomePage from './HomePage';

const clientMock = new MockAdapter(apiClient);

const renderPage = () =>
    render(
        <MemoryRouter>
            <HomePage />
        </MemoryRouter>
    );

/** Reports wherever the router ended up, so navigation is asserted on the URL. */
const LocationProbe: React.FC = () => {
    const location = useLocation();
    return <div data-testid="location">{location.pathname}{location.search}</div>;
};

const renderPageWithRouting = () =>
    render(
        <MemoryRouter initialEntries={['/home']}>
            <Routes>
                <Route path="/home" element={<HomePage />} />
                <Route path="/contacts/:id" element={<LocationProbe />} />
            </Routes>
        </MemoryRouter>
    );

/** The card is the ancestor carrying the tap handlers, not the name text itself. */
const cardFor = (name: string) => screen.getByText(name).closest('div[class*="insightItem"]')!;

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

describe('HomePage call navigation', () => {
    it('opens the tapped call inside its own contact', async () => {
        clientMock.onGet('/calls').reply(200, [call()]);

        renderPageWithRouting();
        await screen.findByText('David Cohen');

        fireEvent.click(cardFor('David Cohen'));

        // The call id rides along so the contact page can scroll to and mark this
        // call, rather than dropping the user at the top of the contact history.
        expect(screen.getByTestId('location').textContent).toBe(
            '/contacts/507f191e810c19729de860eb?call=507f191e810c19729de860ea'
        );
    });

    it('sends each call to its own contact rather than to the first one', async () => {
        clientMock.onGet('/calls').reply(200, [
            call(),
            call({
                _id: 'bbbbbbbbbbbbbbbbbbbbbbbb',
                contactId: { _id: 'cccccccccccccccccccccccc', name: 'Noa Levi' },
            }),
        ]);

        renderPageWithRouting();
        await screen.findByText('Noa Levi');

        fireEvent.click(cardFor('Noa Levi'));

        expect(screen.getByTestId('location').textContent).toBe(
            '/contacts/cccccccccccccccccccccccc?call=bbbbbbbbbbbbbbbbbbbbbbbb'
        );
    });

    it('selects on a long press instead of navigating away', async () => {
        // Tap-to-open and long-press-to-select share one gesture handler, so opening
        // calls must not cost the bulk-delete flow that already lived on these cards.
        clientMock.onGet('/calls').reply(200, [call()]);

        renderPageWithRouting();
        await screen.findByText('David Cohen');

        const card = cardFor('David Cohen');

        vi.useFakeTimers();
        try {
            fireEvent.pointerDown(card, { clientX: 100, clientY: 100 });
            act(() => {
                vi.advanceTimersByTime(LONG_PRESS_MS);
            });
            fireEvent.pointerUp(card);
            // The click the browser fires after the press that opened selection mode.
            fireEvent.click(card);
        } finally {
            vi.useRealTimers();
        }

        expect(screen.getByText('1 selected')).toBeTruthy();
        expect(screen.queryByTestId('location')).toBeNull();
    });

    it('toggles a call off instead of opening it while a selection is open', async () => {
        clientMock.onGet('/calls').reply(200, [call()]);

        renderPageWithRouting();
        await screen.findByText('David Cohen');

        const card = cardFor('David Cohen');

        vi.useFakeTimers();
        try {
            fireEvent.pointerDown(card, { clientX: 100, clientY: 100 });
            act(() => {
                vi.advanceTimersByTime(LONG_PRESS_MS);
            });
            fireEvent.pointerUp(card);
            fireEvent.click(card);
        } finally {
            vi.useRealTimers();
        }

        fireEvent.click(card);

        expect(screen.queryByText('1 selected')).toBeNull();
        expect(screen.queryByTestId('location')).toBeNull();
    });
});
