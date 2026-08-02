import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../models/Contact', () => ({ default: { find: vi.fn(), findOne: vi.fn() } }));
vi.mock('../models/Call', () => ({ default: { find: vi.fn() } }));
vi.mock('../models/Task', () => ({ default: { find: vi.fn() } }));

import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { getBriefings, getBriefing, MAX_OPEN_TASKS } from './briefingService';

const USER_ID = '507f1f77bcf86cd799439011';
const CONTACT_ID = '507f191e810c19729de860ea';
const OTHER_CONTACT_ID = '507f191e810c19729de860eb';

/** Mongoose query builders are chainable; resolve at .lean(). */
const chain = (result: any) => ({
    sort: vi.fn().mockReturnThis(),
    select: vi.fn().mockReturnThis(),
    lean: vi.fn().mockResolvedValue(result),
});

const contact = (id: string, name: string) => ({ _id: id, name, phone: '+972501234567' });

beforeEach(() => {
    vi.mocked(Contact.find).mockReset();
    vi.mocked(Contact.findOne).mockReset();
    vi.mocked(Call.find).mockReset();
    vi.mocked(Task.find).mockReset();

    vi.mocked(Contact.find).mockReturnValue(chain([contact(CONTACT_ID, 'David Cohen')]) as any);
    vi.mocked(Contact.findOne).mockReturnValue(chain(contact(CONTACT_ID, 'David Cohen')) as any);
    vi.mocked(Call.find).mockReturnValue(chain([]) as any);
    vi.mocked(Task.find).mockReturnValue(chain([]) as any);
});

describe('getBriefings', () => {
    it('returns an empty array without querying calls or tasks when the user has no contacts', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([]) as any);

        const result = await getBriefings(USER_ID);

        expect(result).toEqual([]);
        expect(Call.find).not.toHaveBeenCalled();
        expect(Task.find).not.toHaveBeenCalled();
    });

    it('scopes every query to the user', async () => {
        await getBriefings(USER_ID);

        expect(Contact.find).toHaveBeenCalledWith({ userId: USER_ID });
        expect(vi.mocked(Call.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({ userId: USER_ID });
    });

    it('treats a task as open only when completed is false and status is not done', async () => {
        await getBriefings(USER_ID);

        expect(vi.mocked(Task.find).mock.calls[0][0]).toMatchObject({
            completed: false,
            status: { $ne: 'done' },
        });
    });

    it('picks the most recent call that actually has a summary', async () => {
        vi.mocked(Call.find).mockReturnValue(chain([
            { contactId: CONTACT_ID, callSummary: 'newest with summary', callDateTime: new Date('2026-07-28T10:00:00Z') },
            { contactId: CONTACT_ID, callSummary: 'older', callDateTime: new Date('2026-07-20T10:00:00Z') },
        ]) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.lastCall).toEqual({
            summary: 'newest with summary',
            dateTime: new Date('2026-07-28T10:00:00Z'),
        });
    });

    it('excludes calls with no summary from the query, so a pending analysis cannot mask the last useful call', async () => {
        await getBriefings(USER_ID);

        expect(vi.mocked(Call.find).mock.calls[0][0]).toMatchObject({
            callSummary: { $nin: [null, ''] },
        });
    });

    it('reports lastCall as null when the contact has no summarised calls', async () => {
        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.lastCall).toBeNull();
    });

    it('sorts open tasks HIGH before MEDIUM before LOW', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'low', priority: 'LOW' },
            { _id: 't2', contactId: CONTACT_ID, title: 'high', priority: 'HIGH' },
            { _id: 't3', contactId: CONTACT_ID, title: 'medium', priority: 'MEDIUM' },
        ]) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.openTasks.map(t => t.title)).toEqual(['high', 'medium', 'low']);
    });

    it('caps the returned tasks but reports the untruncated count', async () => {
        const many = Array.from({ length: 40 }, (_, i) => ({
            _id: `t${i}`, contactId: CONTACT_ID, title: `task ${i}`, priority: 'LOW',
        }));
        vi.mocked(Task.find).mockReturnValue(chain(many) as any);

        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.openTasks).toHaveLength(MAX_OPEN_TASKS);
        expect(briefing.openTaskCount).toBe(40);
    });

    it('does not leak one contact calls or tasks onto another', async () => {
        vi.mocked(Contact.find).mockReturnValue(chain([
            contact(CONTACT_ID, 'David Cohen'),
            contact(OTHER_CONTACT_ID, 'Ruth Levi'),
        ]) as any);
        vi.mocked(Call.find).mockReturnValue(chain([
            { contactId: CONTACT_ID, callSummary: 'davids call', callDateTime: new Date('2026-07-28T10:00:00Z') },
        ]) as any);
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'davids task', priority: 'HIGH' },
        ]) as any);

        const [david, ruth] = await getBriefings(USER_ID);

        expect(david.openTasks).toHaveLength(1);
        expect(ruth.openTasks).toEqual([]);
        expect(ruth.lastCall).toBeNull();
    });

    it('exposes the contact identity the card needs', async () => {
        const [briefing] = await getBriefings(USER_ID);

        expect(briefing.contactId).toBe(CONTACT_ID);
        expect(briefing.name).toBe('David Cohen');
        expect(briefing.phone).toBe('+972501234567');
    });
});

describe('getBriefing', () => {
    it('returns null when the contact does not belong to the user', async () => {
        vi.mocked(Contact.findOne).mockReturnValue(chain(null) as any);

        expect(await getBriefing(USER_ID, CONTACT_ID)).toBeNull();
    });

    it('scopes the contact lookup to the user', async () => {
        await getBriefing(USER_ID, CONTACT_ID);

        expect(Contact.findOne).toHaveBeenCalledWith({ _id: CONTACT_ID, userId: USER_ID });
    });

    it('builds the same shape as the collection endpoint', async () => {
        vi.mocked(Task.find).mockReturnValue(chain([
            { _id: 't1', contactId: CONTACT_ID, title: 'Send contract', priority: 'HIGH' },
        ]) as any);

        const briefing = await getBriefing(USER_ID, CONTACT_ID);

        expect(briefing).toEqual({
            contactId: CONTACT_ID,
            name: 'David Cohen',
            phone: '+972501234567',
            lastCall: null,
            openTasks: [{ id: 't1', title: 'Send contract', priority: 'HIGH' }],
            openTaskCount: 1,
        });
    });
});
