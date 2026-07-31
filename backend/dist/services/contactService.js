"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.deleteContactCascade = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const Call_1 = __importDefault(require("../models/Call"));
const Task_1 = __importDefault(require("../models/Task"));
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
