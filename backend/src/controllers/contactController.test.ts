import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/contactService', () => ({
    deleteContactCascade: vi.fn(),
}));
vi.mock('../models/Contact', () => ({
    default: { find: vi.fn(), findOne: vi.fn() },
}));

import * as contactService from '../services/contactService';
import { deleteContact } from './contactController';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

const makeRes = () => {
    const res: any = {};
    res.status = vi.fn().mockReturnValue(res);
    res.json = vi.fn().mockReturnValue(res);
    return res;
};

// The unauthenticated case passes null, not undefined: an explicit
// `undefined` argument triggers the default parameter, which would silently
// hand back an authenticated request and make the 401 test assert nothing.
const makeReq = (id: string, userId: string | null = USER_ID) =>
    ({ params: { id }, user: userId ? { id: userId } : undefined }) as any;

describe('deleteContact', () => {
    beforeEach(() => {
        vi.mocked(contactService.deleteContactCascade).mockReset();
    });

    it('returns the cascade counts on success', async () => {
        vi.mocked(contactService.deleteContactCascade).mockResolvedValue({
            deletedCalls: 12,
            deletedTasks: 4,
        });
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(contactService.deleteContactCascade).toHaveBeenCalledWith(USER_ID, CONTACT_ID);
        expect(res.status).toHaveBeenCalledWith(200);
        expect(res.json).toHaveBeenCalledWith({ deletedCalls: 12, deletedTasks: 4 });
    });

    it('returns 404 when the contact belongs to another user', async () => {
        vi.mocked(contactService.deleteContactCascade).mockResolvedValue(null);
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(res.status).toHaveBeenCalledWith(404);
    });

    it('returns 400 for a malformed contact id without touching the database', async () => {
        const res = makeRes();

        await deleteContact(makeReq('not-an-id'), res);

        expect(res.status).toHaveBeenCalledWith(400);
        expect(contactService.deleteContactCascade).not.toHaveBeenCalled();
    });

    it('returns 401 when there is no authenticated user', async () => {
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID, null), res);

        expect(res.status).toHaveBeenCalledWith(401);
        expect(contactService.deleteContactCascade).not.toHaveBeenCalled();
    });

    it('returns 500 when the service throws', async () => {
        vi.mocked(contactService.deleteContactCascade).mockRejectedValue(new Error('db down'));
        const res = makeRes();

        await deleteContact(makeReq(CONTACT_ID), res);

        expect(res.status).toHaveBeenCalledWith(500);
    });
});
