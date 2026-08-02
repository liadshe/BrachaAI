import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { OPEN_TASK_FILTER } from './taskFilters';

export interface BriefingTask {
    id: string;
    title: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface BriefingLastCall {
    summary: string;
    dateTime: Date;
}

export interface Briefing {
    contactId: string;
    name: string;
    phone: string;
    lastCall: BriefingLastCall | null;
    openTasks: BriefingTask[];
    openTaskCount: number;
}

/**
 * Sent to the device per contact. The card shows fewer still; this bound only keeps the
 * sync payload from ballooning for a contact with a long backlog.
 */
export const MAX_OPEN_TASKS = 5;

/** Excludes calls still awaiting AI analysis, so they cannot mask the last useful summary. */
const SUMMARISED_CALL_FILTER = { callSummary: { $nin: [null, ''] } };

const PRIORITY_RANK: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };

const assemble = (contact: any, calls: any[], tasks: any[]): Briefing => {
    const id = String(contact._id);

    // `calls` arrives sorted newest-first, so the first hit for a contact is its latest.
    const latest = calls.find(call => String(call.contactId) === id);

    const open = tasks
        .filter(task => String(task.contactId) === id)
        .sort((a, b) => (PRIORITY_RANK[a.priority] ?? 99) - (PRIORITY_RANK[b.priority] ?? 99));

    return {
        contactId: id,
        name: contact.name,
        phone: contact.phone,
        lastCall: latest
            ? { summary: latest.callSummary, dateTime: latest.callDateTime }
            : null,
        openTasks: open.slice(0, MAX_OPEN_TASKS).map(task => ({
            id: String(task._id),
            title: task.title,
            priority: task.priority,
        })),
        openTaskCount: open.length,
    };
};

/**
 * Two queries for the whole contact list rather than two per contact — the device syncs
 * every contact at once, and an N+1 here would scale with the address book.
 */
const fetchRelated = async (userId: string, contactIds: any[]) => {
    const [calls, tasks] = await Promise.all([
        Call.find({ userId, contactId: { $in: contactIds }, ...SUMMARISED_CALL_FILTER })
            .sort({ callDateTime: -1 })
            .select('contactId callSummary callDateTime')
            .lean(),
        Task.find({ userId, contactId: { $in: contactIds }, ...OPEN_TASK_FILTER })
            .sort({ createdAt: -1 })
            .select('contactId title priority')
            .lean(),
    ]);
    return { calls: calls as any[], tasks: tasks as any[] };
};

export const getBriefings = async (userId: string): Promise<Briefing[]> => {
    const contacts = (await Contact.find({ userId }).sort({ name: 1 }).lean()) as any[];
    if (contacts.length === 0) {
        return [];
    }

    const { calls, tasks } = await fetchRelated(userId, contacts.map(c => c._id));
    return contacts.map(contact => assemble(contact, calls, tasks));
};

export const getBriefing = async (
    userId: string,
    contactId: string,
): Promise<Briefing | null> => {
    const contact = await Contact.findOne({ _id: contactId, userId }).lean();
    if (!contact) {
        return null;
    }

    const { calls, tasks } = await fetchRelated(userId, [(contact as any)._id]);
    return assemble(contact, calls, tasks);
};
