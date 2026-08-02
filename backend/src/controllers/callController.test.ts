import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Call', () => ({ default: { find: vi.fn(), deleteMany: vi.fn() } }));
vi.mock('../services/userService', () => ({ getOrCreateContact: vi.fn() }));
vi.mock('../services/callService', () => ({
    deleteCallsByIds: vi.fn(),
    saveRawCall: vi.fn(),
    updateCallWithAnalysis: vi.fn(),
    markAnalysisFailed: vi.fn(),
}));
vi.mock('../services/aiService', () => ({ analyzeTranscript: vi.fn() }));
vi.mock('../services/taskService', () => ({ createTasksFromAi: vi.fn() }));

import * as userService from '../services/userService';
import * as callService from '../services/callService';
import * as aiService from '../services/aiService';
import { bulkDeleteCalls, handleIncomingAndroidCall } from './callController';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';
const CONTACT_ID = '507f191e810c19729de860ea';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    res.headersSent = false;
    return res;
};

// The unauthenticated case passes null, not undefined: an explicit
// `undefined` argument triggers the default parameter, which would silently
// hand back an authenticated request and make the 401 test assert nothing.
const makeReq = (body: any, userId: string | null = USER_ID) =>
    ({ body, user: userId ? { id: userId } : undefined }) as any;

const validBody = (overrides: Record<string, unknown> = {}) => ({
    contactName: 'David Cohen',
    date: '260802_101500',
    transcript: 'shalom',
    callerNumber: '0541234567',
    ...overrides,
});

/** The 5th positional argument of saveRawCall. */
const savedCallLength = () => vi.mocked(callService.saveRawCall).mock.calls[0][4];

describe('bulkDeleteCalls', () => {
    beforeEach(() => {
        vi.mocked(callService.deleteCallsByIds).mockReset();
    });

    it('deletes the listed calls and returns the count', async () => {
        vi.mocked(callService.deleteCallsByIds).mockResolvedValue(2);
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A, CALL_B] }), res);

        expect(callService.deleteCallsByIds).toHaveBeenCalledWith(USER_ID, [CALL_A, CALL_B]);
        expect(res.status).toHaveBeenCalledWith(200);
        expect(res.json).toHaveBeenCalledWith({ deletedCount: 2 });
    });

    it('returns 400 when ids is missing', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({}), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 when ids is empty', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [] }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 for a malformed id instead of throwing a CastError', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A, 'not-an-id'] }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 400 for more than 200 ids', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: Array.from({ length: 201 }, () => CALL_A) }), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 401 when there is no authenticated user', async () => {
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A] }, null), res);

        expect(res.status).toHaveBeenCalledWith(401);
        expect(callService.deleteCallsByIds).not.toHaveBeenCalled();
    });

    it('returns 500 when the service throws', async () => {
        vi.mocked(callService.deleteCallsByIds).mockRejectedValue(new Error('db down'));
        const res = makeRes();

        await bulkDeleteCalls(makeReq({ ids: [CALL_A] }), res);

        expect(res.status).toHaveBeenCalledWith(500);
    });
});

describe('handleIncomingAndroidCall call length', () => {
    beforeEach(() => {
        vi.mocked(userService.getOrCreateContact).mockReset();
        vi.mocked(callService.saveRawCall).mockReset();
        vi.mocked(aiService.analyzeTranscript).mockReset();

        vi.mocked(userService.getOrCreateContact).mockResolvedValue({ id: CONTACT_ID } as any);
        vi.mocked(callService.saveRawCall).mockResolvedValue({ id: 'call-1' } as any);
        // Analysis is fire-and-forget after the response; resolve it so nothing dangles.
        vi.mocked(aiService.analyzeTranscript).mockResolvedValue({ summary: 's', tasks: [] } as any);
    });

    it('persists a valid duration', async () => {
        await handleIncomingAndroidCall(makeReq(validBody({ callLength: 272 })), makeRes());

        expect(savedCallLength()).toBe(272);
    });

    it('rounds a fractional duration to whole seconds', async () => {
        await handleIncomingAndroidCall(makeReq(validBody({ callLength: 272.6 })), makeRes());

        expect(savedCallLength()).toBe(273);
    });

    it('accepts a call with no duration at all', async () => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody()), res);

        expect(savedCallLength()).toBeUndefined();
        expect(res.status).toHaveBeenCalledWith(201);
    });

    // Each of these must still save the call. A 400 here is destructive: the Android
    // client treats 400 as permanent, drops the payload and never retries it, so a
    // malformed duration would cost the whole transcript.
    it.each([
        ['null', null],
        ['a string', '272'],
        ['a negative number', -5],
        ['NaN', NaN],
        ['Infinity', Infinity],
        ['an object', { seconds: 272 }],
    ])('saves the call without a duration when callLength is %s', async (_label, callLength) => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody({ callLength })), res);

        expect(callService.saveRawCall).toHaveBeenCalled();
        expect(savedCallLength()).toBeUndefined();
        expect(res.status).toHaveBeenCalledWith(201);
        expect(res.status).not.toHaveBeenCalledWith(400);
    });

    it('still rejects a call with no transcript', async () => {
        const res = makeRes();

        await handleIncomingAndroidCall(makeReq(validBody({ transcript: '' })), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(callService.saveRawCall).not.toHaveBeenCalled();
    });
});
