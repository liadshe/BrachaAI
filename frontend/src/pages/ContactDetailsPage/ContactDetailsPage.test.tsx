import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { MemoryRouter, HashRouter, Routes, Route } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';
import apiClient from '@/services/apiClient';
import ContactDetailsPage, { HIGHLIGHT_MS } from './ContactDetailsPage';

const clientMock = new MockAdapter(apiClient);

const CONTACT_ID = '507f191e810c19729de860eb';
const FIRST_CALL_ID = '507f191e810c19729de860ea';
const SECOND_CALL_ID = '507f191e810c19729de860ec';

const call = (id: string, summary: string) => ({
    _id: id,
    contactId: { _id: CONTACT_ID, name: 'David Cohen' },
    callSummary: summary,
    fullTranscript: 'David: hello',
    callDateTime: '2026-04-15T13:57:02.000Z',
});

/** The card is the ancestor carrying the highlight class, not the summary text itself. */
const cardFor = (summary: string) => screen.getByText(summary).closest('div[class*="callCard"]')!;

const renderAt = (path: string) =>
    render(
        <MemoryRouter initialEntries={[path]}>
            <Routes>
                <Route path="/contacts/:id" element={<ContactDetailsPage />} />
            </Routes>
        </MemoryRouter>
    );

// jsdom implements no layout, so Element.scrollIntoView does not exist and calling it
// would throw rather than fail an assertion. Stubbed so it can also be asserted on.
const scrollIntoView = vi.fn();

beforeEach(() => {
    clientMock.reset();
    scrollIntoView.mockClear();
    Element.prototype.scrollIntoView = scrollIntoView;

    clientMock.onGet(`/contacts/${CONTACT_ID}`).reply(200, {
        _id: CONTACT_ID,
        name: 'David Cohen',
        phone: '050-000-0000',
    });
    clientMock.onGet('/tasks').reply(200, []);
    clientMock.onGet('/calls').reply(200, [
        call(FIRST_CALL_ID, 'Booked catering for the 2nd of July.'),
        call(SECOND_CALL_ID, 'Confirmed the delivery address.'),
    ]);
});

afterEach(() => {
    vi.useRealTimers();
    // jsdom keeps one window across tests in a file, so a hash left behind would
    // leak into the next render.
    window.location.hash = '';
});

describe('ContactDetailsPage deep-linked call', () => {
    it('scrolls to and marks the call named in the URL', async () => {
        renderAt(`/contacts/${CONTACT_ID}?call=${SECOND_CALL_ID}`);

        await screen.findByText('Confirmed the delivery address.');

        expect(cardFor('Confirmed the delivery address.').className).toContain('highlightedCard');
        expect(scrollIntoView).toHaveBeenCalledTimes(1);
    });

    it('marks only the linked call, leaving the rest of the history alone', async () => {
        renderAt(`/contacts/${CONTACT_ID}?call=${SECOND_CALL_ID}`);

        await screen.findByText('Booked catering for the 2nd of July.');

        expect(cardFor('Booked catering for the 2nd of July.').className).not.toContain('highlightedCard');
    });

    it('drops the mark once the user has had time to see it', async () => {
        // Fake timers must be installed before the render that schedules the fade,
        // otherwise the clock swap leaves the real timer running and nothing to advance.
        vi.useFakeTimers();
        renderAt(`/contacts/${CONTACT_ID}?call=${FIRST_CALL_ID}`);

        // Settles the fetches, which resolve as microtasks rather than on the clock.
        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        expect(cardFor('Booked catering for the 2nd of July.').className).toContain('highlightedCard');

        act(() => {
            vi.advanceTimersByTime(HIGHLIGHT_MS);
        });

        expect(cardFor('Booked catering for the 2nd of July.').className).not.toContain('highlightedCard');
    });

    it('shows an ordinary contact page when no call is named', async () => {
        renderAt(`/contacts/${CONTACT_ID}`);

        await screen.findByText('Booked catering for the 2nd of July.');

        expect(cardFor('Booked catering for the 2nd of July.').className).not.toContain('highlightedCard');
        expect(scrollIntoView).not.toHaveBeenCalled();
    });

    it('reads the call out of a hash URL, which is what actually ships', async () => {
        // App.tsx mounts a HashRouter, so the real address is /#/contacts/:id?call=<id>.
        // The other tests use MemoryRouter and would keep passing even if the query
        // were lost to the hash, which is the one way this could break only in production.
        window.location.hash = `#/contacts/${CONTACT_ID}?call=${SECOND_CALL_ID}`;

        render(
            <HashRouter>
                <Routes>
                    <Route path="/contacts/:id" element={<ContactDetailsPage />} />
                </Routes>
            </HashRouter>
        );

        await screen.findByText('Confirmed the delivery address.');

        expect(cardFor('Confirmed the delivery address.').className).toContain('highlightedCard');
    });

    it('ignores a link to a call this contact no longer has', async () => {
        // A link kept after the call was deleted. The contact is still the right
        // destination, so the page loads normally rather than erroring or jumping.
        renderAt(`/contacts/${CONTACT_ID}?call=aaaaaaaaaaaaaaaaaaaaaaaa`);

        await screen.findByText('Booked catering for the 2nd of July.');

        expect(screen.queryByText('Confirmed the delivery address.')).toBeTruthy();
        expect(scrollIntoView).not.toHaveBeenCalled();
    });
});
