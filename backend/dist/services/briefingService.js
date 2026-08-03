"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getBriefing = exports.getBriefings = exports.MAX_OPEN_TASKS = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const Call_1 = __importDefault(require("../models/Call"));
const Task_1 = __importDefault(require("../models/Task"));
const taskFilters_1 = require("./taskFilters");
/**
 * Sent to the device per contact. The card shows fewer still; this bound only keeps the
 * sync payload from ballooning for a contact with a long backlog.
 */
exports.MAX_OPEN_TASKS = 5;
/** Excludes calls still awaiting AI analysis, so they cannot mask the last useful summary. */
const SUMMARISED_CALL_FILTER = { callSummary: { $nin: [null, ''] } };
const PRIORITY_RANK = { HIGH: 0, MEDIUM: 1, LOW: 2 };
const assemble = (contact, calls, tasks) => {
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
        openTasks: open.slice(0, exports.MAX_OPEN_TASKS).map(task => ({
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
const fetchRelated = async (userId, contactIds) => {
    const [calls, tasks] = await Promise.all([
        Call_1.default.find({ userId, contactId: { $in: contactIds }, ...SUMMARISED_CALL_FILTER })
            .sort({ callDateTime: -1 })
            .select('contactId callSummary callDateTime')
            .lean(),
        Task_1.default.find({ userId, contactId: { $in: contactIds }, ...taskFilters_1.OPEN_TASK_FILTER })
            .sort({ createdAt: -1 })
            .select('contactId title priority')
            .lean(),
    ]);
    return { calls: calls, tasks: tasks };
};
const getBriefings = async (userId) => {
    const contacts = (await Contact_1.default.find({ userId }).sort({ name: 1 }).lean());
    if (contacts.length === 0) {
        return [];
    }
    const { calls, tasks } = await fetchRelated(userId, contacts.map(c => c._id));
    return contacts.map(contact => assemble(contact, calls, tasks));
};
exports.getBriefings = getBriefings;
const getBriefing = async (userId, contactId) => {
    const contact = await Contact_1.default.findOne({ _id: contactId, userId }).lean();
    if (!contact) {
        return null;
    }
    const { calls, tasks } = await fetchRelated(userId, [contact._id]);
    return assemble(contact, calls, tasks);
};
exports.getBriefing = getBriefing;
