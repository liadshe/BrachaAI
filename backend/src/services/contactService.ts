import Contact from '../models/Contact';
import Call from '../models/Call';
import Task from '../models/Task';

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
