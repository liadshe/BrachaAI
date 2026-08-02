import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';
import { OPEN_TASK_FILTER } from './taskFilters';

export interface CascadeResult {
    deletedCalls: number;
    deletedTasks: number;
}

/**
 * Deletes a contact along with everything that points at it.
 *
 * This is deliberately not a transaction: MongoDB multi-document transactions
 * require a replica set, and DATABASE_URL may point at a standalone instance.
 * Instead the contact is deleted LAST, so a mid-sequence failure leaves the
 * contact in place and the user can safely retry — each earlier delete is
 * idempotent. Deleting the contact first would orphan its calls and tasks.
 *
 * Resolves null when no contact with that id belongs to the user.
 */
export const deleteContactCascade = async (
    userId: string,
    contactId: string,
): Promise<CascadeResult | null> => {
    const contact = await Contact.findOne({ _id: contactId, userId });
    if (!contact) {
        return null;
    }

    const taskResult = await Task.deleteMany({ contactId, userId });
    const callResult = await Call.deleteMany({ contactId, userId });
    await Contact.deleteOne({ _id: contactId, userId });

    return {
        deletedCalls: callResult.deletedCount ?? 0,
        deletedTasks: taskResult.deletedCount ?? 0,
    };
};

/**
 * Every contact the user owns, each carrying how many open tasks point at it.
 *
 * Two queries for the whole list rather than two per contact: the Contacts page loads the
 * entire address book at once, so an N+1 here would scale with it. Same shape as
 * `briefingService.fetchRelated`, and deliberately not a `$group` aggregation — the
 * aggregation pipeline skips Mongoose's string-to-ObjectId coercion, and a miscast
 * `userId` there matches nothing silently, which would look like every badge missing
 * rather than like an error.
 */
export const getContactsWithOpenTaskCounts = async (userId: string): Promise<any[]> => {
    const contacts = (await Contact.find({ userId }).sort({ name: 1 }).lean()) as any[];
    if (contacts.length === 0) {
        return [];
    }

    const openTasks = (await Task.find({ userId, ...OPEN_TASK_FILTER })
        .select('contactId')
        .lean()) as any[];

    // Keyed on String(...) throughout: .lean() hands back ObjectIds, and two ObjectIds for
    // the same document are not === each other.
    const counts = new Map<string, number>();
    for (const task of openTasks) {
        const key = String(task.contactId);
        counts.set(key, (counts.get(key) ?? 0) + 1);
    }

    return contacts.map(contact => ({
        ...contact,
        openTaskCount: counts.get(String(contact._id)) ?? 0,
    }));
};
