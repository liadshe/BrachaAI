import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Call', () => ({
    default: {
        create: vi.fn(),
        deleteMany: vi.fn(),
    },
}));

import Call from '../models/Call';
import { deleteCallsByIds, saveRawCall } from './callService';

const USER_ID = '507f1f77bcf86cd799439011';
const CALL_A = '507f191e810c19729de860ea';
const CALL_B = '507f191e810c19729de860eb';
const CONTACT_ID = '507f191e810c19729de860ec';

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

describe('saveRawCall call length', () => {
    beforeEach(() => {
        vi.mocked(Call.create).mockReset();
        vi.mocked(Call.create).mockResolvedValue({ id: 'call-1' } as any);
    });

    it('stores the duration when one is given', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', new Date('2026-08-02T10:15:00Z'), 272);

        expect(vi.mocked(Call.create).mock.calls[0][0]).toMatchObject({ callLength: 272 });
    });

    it('omits the field entirely when the duration is unknown', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', new Date('2026-08-02T10:15:00Z'));

        // Absent, not null: an explicit null would be a stored claim that we measured
        // nothing, and it would defeat the `callLength?: number` optionality downstream.
        expect(vi.mocked(Call.create).mock.calls[0][0]).not.toHaveProperty('callLength');
    });
});

describe('saveRawCall call direction', () => {
    const DATE = new Date('2026-08-02T10:15:00Z');

    beforeEach(() => {
        vi.mocked(Call.create).mockReset();
        vi.mocked(Call.create).mockResolvedValue({ id: 'call-1' } as any);
    });

    const savedWith = () => vi.mocked(Call.create).mock.calls[0][0];

    it.each(['incoming', 'outgoing', 'missed'])('stores %s as given', async (direction) => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', DATE, 272, direction);

        expect(savedWith()).toMatchObject({ callType: direction });
    });

    it('omits the field entirely when the device could not determine a direction', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', DATE, 272, undefined);

        // The regression this guards: defaulting an unknown direction to 'incoming' made
        // every outgoing call display as an incoming one.
        expect(savedWith()).not.toHaveProperty('callType');
    });

    it('omits the field rather than coercing an unrecognised direction', async () => {
        await saveRawCall(USER_ID, CONTACT_ID, 'shalom', DATE, 272, 'sideways');

        expect(savedWith()).not.toHaveProperty('callType');
    });
});
