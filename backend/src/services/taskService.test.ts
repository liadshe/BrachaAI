import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Task', () => ({
    default: { create: vi.fn() },
}));

import Task from '../models/Task';
import { createTasksFromAi } from './taskService';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';

const createdTitles = () =>
    vi.mocked(Task.create).mock.calls.map(([doc]: any[]) => doc.title);

describe('createTasksFromAi', () => {
    beforeEach(() => {
        vi.mocked(Task.create).mockReset();
        vi.mocked(Task.create).mockImplementation(async (doc: any) => doc);
    });

    it('creates one task per distinct action item', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'HIGH' },
            { title: 'Book the meeting room', description: '', priority: 'LOW' },
        ]);

        expect(createdTitles()).toEqual(['Send the contract', 'Book the meeting room']);
    });

    it('collapses a task the AI emitted once per mention in the call', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'MEDIUM' },
            { title: 'Book the meeting room', description: '', priority: 'LOW' },
            { title: 'Send the contract', description: '', priority: 'MEDIUM' },
        ]);

        expect(createdTitles()).toEqual(['Send the contract', 'Book the meeting room']);
    });

    it('treats casing, spacing and trailing punctuation as the same task', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: '  send   the CONTRACT.  ', description: '', priority: 'LOW' },
            { title: 'Send the contract!', description: '', priority: 'LOW' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
    });

    it('keeps the highest priority mention of a duplicated task', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: 'Send the contract', description: '', priority: 'HIGH' },
            { title: 'Send the contract', description: '', priority: 'MEDIUM' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({ priority: 'HIGH' });
    });

    it('keeps the most detailed description of a duplicated task', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: 'Send the contract', description: 'Signed PDF, before Friday', priority: 'LOW' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({
            description: 'Signed PDF, before Friday',
        });
    });

    it('keeps the first occurrence position when merging duplicates', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: 'Book the meeting room', description: '', priority: 'LOW' },
            { title: 'Send the contract', description: '', priority: 'HIGH' },
        ]);

        expect(createdTitles()).toEqual(['Send the contract', 'Book the meeting room']);
    });

    it('drops entries with no usable title instead of creating empty tasks', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: '   ', description: 'x', priority: 'LOW' },
            { description: 'y', priority: 'LOW' },
            { title: 'Send the contract', description: '', priority: 'LOW' },
        ]);

        expect(createdTitles()).toEqual(['Send the contract']);
    });

    it('does not merge distinct tasks that merely share words', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: 'Send the invoice', description: '', priority: 'LOW' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(2);
    });

    it('deduplicates non-latin titles too', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'לשלוח את החוזה', description: '', priority: 'LOW' },
            { title: 'לשלוח את החוזה.', description: '', priority: 'HIGH' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({ priority: 'HIGH' });
    });

    it('stores the deadline the AI resolved from the call', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW', dueDate: '2026-08-13' },
        ]);

        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({
            dueDate: '2026-08-13',
        });
    });

    it('keeps the tighter deadline when a task is mentioned with two dates', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW', dueDate: '2026-08-20' },
            { title: 'Send the contract', description: '', priority: 'LOW', dueDate: '2026-08-13' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({
            dueDate: '2026-08-13',
        });
    });

    it('takes the deadline from whichever mention actually carried one', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW' },
            { title: 'Send the contract', description: '', priority: 'LOW', dueDate: '2026-08-13' },
        ]);

        expect(Task.create).toHaveBeenCalledTimes(1);
        expect(vi.mocked(Task.create).mock.calls[0][0]).toMatchObject({
            dueDate: '2026-08-13',
        });
    });

    it('leaves the deadline unset rather than storing a value that is not a date', async () => {
        await createTasksFromAi(USER_ID, CONTACT_ID, [
            { title: 'Send the contract', description: '', priority: 'LOW', dueDate: 'מחר' },
            { title: 'Book the room', description: '', priority: 'LOW', dueDate: '2026-02-30' },
            { title: 'Call the client', description: '', priority: 'LOW', dueDate: '' },
        ]);

        for (const [doc] of vi.mocked(Task.create).mock.calls as any[][]) {
            expect(doc.dueDate).toBeUndefined();
        }
    });
});
