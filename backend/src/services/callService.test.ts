import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Call', () => ({
    default: {
        deleteMany: vi.fn(),
    },
}));

import Call from '../models/Call';
import { deleteCallsByIds } from './callService';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';

describe('deleteCallsByIds', () => {
    beforeEach(() => {
        vi.mocked(Call.deleteMany).mockReset();
    });

    it('scopes the delete to the calling user', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 2 } as any);

        await deleteCallsByIds(USER_ID, [CALL_A, CALL_B]);

        // The userId clause is what stops one user deleting another user's
        // calls by guessing ObjectIds. Assert on the exact filter.
        expect(Call.deleteMany).toHaveBeenCalledWith({
            _id: { $in: [CALL_A, CALL_B] },
            userId: USER_ID,
        });
    });

    it('returns the number of documents actually deleted', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 2 } as any);

        const deleted = await deleteCallsByIds(USER_ID, [CALL_A, CALL_B]);

        expect(deleted).toBe(2);
    });

    it('returns 0 when the driver omits deletedCount', async () => {
        vi.mocked(Call.deleteMany).mockResolvedValue({} as any);

        const deleted = await deleteCallsByIds(USER_ID, [CALL_A]);

        expect(deleted).toBe(0);
    });
});
