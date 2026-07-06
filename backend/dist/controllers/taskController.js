"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.updateTask = exports.createTask = exports.getTasksSummary = exports.getTasks = void 0;
const Task_1 = __importDefault(require("../models/Task"));
const getTasks = async (req, res) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Fetching tasks for userId: ${userId}`);
        const tasks = await Task_1.default.find({ userId }).populate('contactId').sort({ createdAt: -1 });
        console.log(`[DEBUG] Found ${tasks.length} tasks`);
        res.status(200).json(tasks);
    }
    catch (error) {
        console.error('Get tasks error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getTasks = getTasks;
const getTasksSummary = async (req, res) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Summary requested for userId: ${userId}`);
        const open = await Task_1.default.countDocuments({ userId, completed: false });
        const closedToday = await Task_1.default.countDocuments({
            userId,
            completed: true,
            updatedAt: { $gte: new Date(new Date().setHours(0, 0, 0, 0)) }
        });
        // Simple overdue comparison - if task is not completed and due date is earlier than now
        // Standard ISO date comparison
        const overdue = await Task_1.default.countDocuments({
            userId,
            completed: false,
            dueDate: { $lt: new Date().toISOString() }
        });
        console.log(`[DEBUG] Stats: open=${open}, overdue=${overdue}, closedToday=${closedToday}`);
        res.status(200).json({ open, overdue, closedToday });
    }
    catch (error) {
        console.error('Summary error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getTasksSummary = getTasksSummary;
const createTask = async (req, res) => {
    try {
        const userId = req.user?.id;
        const { contactId, title, priority, dueDate, description } = req.body;
        const task = await Task_1.default.create({
            userId,
            contactId,
            title,
            description,
            priority,
            dueDate,
            completed: false
        });
        res.status(201).json(task);
    }
    catch (error) {
        console.error('Create task error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.createTask = createTask;
const updateTask = async (req, res) => {
    try {
        const userId = req.user?.id;
        const { id } = req.params;
        const updates = req.body;
        if (updates.completed !== undefined) {
            updates.status = updates.completed ? 'done' : 'todo';
        }
        const task = await Task_1.default.findOneAndUpdate({ _id: id, userId }, updates, { new: true }).populate('contactId');
        if (!task) {
            return res.status(404).json({ message: 'Task not found' });
        }
        res.status(200).json(task);
    }
    catch (error) {
        console.error('Update task error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.updateTask = updateTask;
