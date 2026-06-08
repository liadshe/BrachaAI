import { Request, Response } from 'express';
import Task from '../models/Task';
import { AuthRequest } from '../middleware/authMiddleware';

export const getTasks = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Fetching tasks for userId: ${userId}`);
        const tasks = await Task.find({ userId }).populate('contactId').sort({ createdAt: -1 });
        console.log(`[DEBUG] Found ${tasks.length} tasks`);
        res.status(200).json(tasks);
    } catch (error) {
        console.error('Get tasks error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const getTasksSummary = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Summary requested for userId: ${userId}`);
        
        const open = await Task.countDocuments({ userId, completed: false });
        
        const closedToday = await Task.countDocuments({
            userId,
            completed: true,
            updatedAt: { $gte: new Date(new Date().setHours(0, 0, 0, 0)) }
        });
        
        // Simple overdue comparison - if task is not completed and due date is earlier than now
        // Standard ISO date comparison
        const overdue = await Task.countDocuments({
            userId,
            completed: false,
            dueDate: { $lt: new Date().toISOString() }
        });
        
        console.log(`[DEBUG] Stats: open=${open}, overdue=${overdue}, closedToday=${closedToday}`);
        res.status(200).json({ open, overdue, closedToday });
    } catch (error) {
        console.error('Summary error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const createTask = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const { contactId, title, priority, dueDate, description } = req.body;
        const task = await Task.create({
            userId,
            contactId,
            title,
            description,
            priority,
            dueDate,
            completed: false
        });
        res.status(201).json(task);
    } catch (error) {
        console.error('Create task error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const updateTask = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const { id } = req.params;
        const updates = req.body;

        if (updates.completed !== undefined) {
            updates.status = updates.completed ? 'done' : 'todo';
        }

        const task = await Task.findOneAndUpdate(
            { _id: id, userId },
            updates,
            { new: true }
        ).populate('contactId');

        if (!task) {
            return res.status(404).json({ message: 'Task not found' });
        }

        res.status(200).json(task);
    } catch (error) {
        console.error('Update task error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
