import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Contact', () => ({
    default: { find: vi.fn(), findOne: vi.fn(), deleteOne: vi.fn() },
}));
vi.mock('../models/Call', () => ({
    default: { deleteMany: vi.fn() },
}));
vi.mock('../models/Task', () => ({
    default: { find: vi.fn(), deleteMany: vi.fn() },
}));

import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { deleteContactCascade, getContactsWithOpenTaskCounts } from './contactService';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

describe('deleteContactCascade', () => {
    beforeEach(() => {
        vi.mocked(Contact.findOne).mockReset();
        vi.mocked(Contact.deleteOne).mockReset();
        vi.mocked(Call.deleteMany).mockReset();
        vi.mocked(Task.deleteMany).mockReset();

        vi.mocked(Contact.findOne).mockResolvedValue({ _id: CONTACT_ID } as any);
        vi.mocked(Contact.deleteOne).mockResolvedValue({ deletedCount: 1 } as any);
        vi.mocked(Call.deleteMany).mockResolvedValue({ deletedCount: 12 } as any);
        vi.mocked(Task.deleteMany).mockResolvedValue({ deletedCount: 4 } as any);
    });

    it('returns null and deletes nothing when the contact does not belong to the user', async () => {
        vi.mocked(Contact.findOne).mockResolvedValue(null as any);

        const result = await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(result).toBeNull();
        expect(Task.deleteMany).not.toHaveBeenCalled();
        expect(Call.deleteMany).not.toHaveBeenCalled();
        expect(Contact.deleteOne).not.toHaveBeenCalled();
    });

    it('scopes the ownership lookup to the user', async () => {
        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(Contact.findOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('deletes the contact tasks, calls, and the contact itself, all scoped by user', async () => {
        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(Task.deleteMany).toHaveBeenCalledWith({ contactId: CONTACT_ID, userId: USER_ID });
        expect(Call.deleteMany).toHaveBeenCalledWith({ contactId: CONTACT_ID, userId: USER_ID });
        expect(Contact.deleteOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('deletes the contact last so a mid-sequence failure never orphans calls', async () => {
        const order: string[] = [];
        vi.mocked(Task.deleteMany).mockImplementation((async () => {
            order.push('tasks');
            return { deletedCount: 4 };
        }) as any);
        vi.mocked(Call.deleteMany).mockImplementation((async () => {
            order.push('calls');
            return { deletedCount: 12 };
        }) as any);
        vi.mocked(Contact.deleteOne).mockImplementation((async () => {
            order.push('contact');
            return { deletedCount: 1 };
        }) as any);

        await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(order).toEqual(['tasks', 'calls', 'contact']);
    });

    it('reports how many calls and tasks were removed', async () => {
        const result = await deleteContactCascade(USER_ID, CONTACT_ID);

        expect(result).toEqual({ deletedCalls: 12, deletedTasks: 4 });
    });
});

/** Mongoose query builders are chainable; resolve at .lean(). */
const chain = (result: any) => ({
    sort: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    lean: vi.fn().mockResolvedValue(result),
});

const OTHER_CONTACT_ID = '507f191e810c19729de860eb';

describe('getContactsWithOpenTaskCounts', () => {
    beforeEach(() => {
        vi.mocked(Contact.find).mockReset();
        vi.mocked(Task.find).mockReset();

        vi.mocked(Contact.find).mockReturnValue(chain([
            { _id: CONTACT_ID, name: 'David Cohen', phone: '+972541234567' },
            { _id: OTHER_CONTACT_ID, name: 'Noa Levi', phone: '+972541234568' },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([]) as any);
    });

    it('returns an empty array without querying tasks when the user has no contacts', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result).toEqual([]);
        expect(Task.find).not.toHaveBeenCalled();
    });

    it('scopes both queries to the user', async () => {
        await getContactsWithOpenTaskCounts(USER_ID);

        expect(Contact.find).toHaveBeenCalledWith({ userId: USER_ID });
        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
    });

    it('counts a task as open only when completed is false and status is not done', async () => {
        await getContactsWithOpenTaskCounts(USER_ID);

        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({
            completed: false,
            status: { $ne: 'done' },
        });
    });

    it('counts each contact open tasks', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { contactId: CONTACT_ID },
            { contactId: CONTACT_ID },
            { contactId: OTHER_CONTACT_ID },
        ]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({ name: 'David Cohen', openTaskCount: 2 });
        expect(result[1]).toMatchObject({ name: 'Noa Levi', openTaskCount: 1 });
    });

    it('reports zero rather than omitting a contact with no open tasks', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([{ contactId: CONTACT_ID }]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result).toHaveLength(2);
        expect(result[1]).toMatchObject({ name: 'Noa Levi', openTaskCount: 0 });
    });

    it('matches contacts to tasks by id even when the ids arrive as ObjectId-like objects', async () => {
        // .lean() returns real ObjectIds, not strings; a === comparison would silently
        // count zero for every contact.
        const asObjectId = (hex: string) => ({ toString: () => hex });
        vi.mocked(Contact.find).mockReturnValue(chain([
            { _id: asObjectId(CONTACT_ID), name: 'David Cohen' },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([
            { contactId: asObjectId(CONTACT_ID) },
        ]) as any);

        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({ openTaskCount: 1 });
    });

    it('preserves the contact fields alongside the count', async () => {
        const result = await getContactsWithOpenTaskCounts(USER_ID);

        expect(result[0]).toMatchObject({
            _id: CONTACT_ID,
            name: 'David Cohen',
            phone: '+972541234567',
        });
    });
});
