"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getContactsWithOpenTaskCounts = exports.deleteContactCascade = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const Call_1 = __importDefault(require("../models/Call"));
const Task_1 = __importDefault(require("../models/Task"));
const taskFilters_1 = require("./taskFilters");
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
const deleteContactCascade = async (userId, contactId) => {
    const contact = await Contact_1.default.findOne({ _id: contactId, userId });
    if (!contact) {
        return null;
    }
    const taskResult = await Task_1.default.deleteMany({ contactId, userId });
    const callResult = await Call_1.default.deleteMany({ contactId, userId });
    await Contact_1.default.deleteOne({ _id: contactId, userId });
    return {
        deletedCalls: callResult.deletedCount ?? 0,
        deletedTasks: taskResult.deletedCount ?? 0,
    };
};
exports.deleteContactCascade = deleteContactCascade;
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
const getContactsWithOpenTaskCounts = async (userId) => {
    const contacts = (await Contact_1.default.find({ userId }).sort({ name: 1 }).lean());
    if (contacts.length === 0) {
        return [];
    }
    const openTasks = (await Task_1.default.find({ userId, ...taskFilters_1.OPEN_TASK_FILTER })
        .select('contactId')
        .lean());
    // Keyed on String(...) throughout: .lean() hands back ObjectIds, and two ObjectIds for
    // the same document are not === each other.
    const counts = new Map();
    for (const task of openTasks) {
        const key = String(task.contactId);
        counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return contacts.map(contact => ({
        ...contact,
        openTaskCount: counts.get(String(contact._id)) ?? 0,
    }));
};
exports.getContactsWithOpenTaskCounts = getContactsWithOpenTaskCounts;
