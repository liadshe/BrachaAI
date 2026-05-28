import { Request, Response } from 'express';
import Task from '../models/Task';

export const getTasks = async (req: Request, res: Response) => {
    try {
        const tasks = await Task.find().sort({ createdAt: -1 });
        res.status(200).json(tasks);
    } catch (error) {
        console.error('Get tasks error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const createTask = async (req: Request, res: Response) => {
    try {
        const { userId, contactId, title, priority, dueDate } = req.body;
        const task = await Task.create({
            userId,
            contactId,
            title,
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
