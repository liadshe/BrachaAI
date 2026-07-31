import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Contact', () => ({
    default: { findOne: vi.fn(), deleteOne: vi.fn() },
}));
vi.mock('../models/Call', () => ({
    default: { deleteMany: vi.fn() },
}));
vi.mock('../models/Task', () => ({
    default: { deleteMany: vi.fn() },
}));

import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { deleteContactCascade } from './contactService';

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
