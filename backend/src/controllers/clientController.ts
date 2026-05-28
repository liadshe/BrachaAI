import { Request, Response } from 'express';
import Contact from '../models/Contact';

export const getClients = async (req: Request, res: Response) => {
    try {
        const clients = await Contact.find().sort({ name: 1 });
        res.status(200).json(clients);
    } catch (error) {
        console.error('Get clients error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
