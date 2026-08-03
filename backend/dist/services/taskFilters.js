"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.OPEN_TASK_FILTER = void 0;
/**
 * A task is open only when both fields agree. `createTasksFromAi` writes `status` and lets
 * `completed` default; `updateTask` writes both. On disagreement we drop the task rather
 * than risk showing a finished one — during a call, or as a count on a contact card.
 *
 * Shared rather than duplicated: the Contacts page badge and the Android call overlay
 * describe the same tasks to the same user minutes apart, and a drift between two copies
 * would surface as a card and an overlay disagreeing, with no obvious cause.
 */
exports.OPEN_TASK_FILTER = { completed: false, status: { $ne: 'done' } };
