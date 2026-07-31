import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/callService', () => ({
    deleteCallsByIds: vi.fn(),
    saveRawCall: vi.fn(),
    updateCallWithAnalysis: vi.fn(),
    markAnalysisFailed: vi.fn(),
}));

import * as callService from '../services/callService';
import { bulkDeleteCalls } from './callController';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    return res;
};

// The unauthenticated case passes null, not undefined: an explicit
// `undefined` argument triggers the default parameter, which would silently
// hand back an authenticated request and make the 401 test assert nothing.
const makeReq = (body: any, userId: string | null = USER_ID) =>
    ({ body, user: userId ? { id: userId } : undefined }) as any;

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
