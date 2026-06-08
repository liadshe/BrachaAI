import { Response } from 'express';
import Contact from '../models/Contact';
import { AuthRequest } from '../middleware/authMiddleware';

export const getClients = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Fetching clients for userId: ${userId}`);
        const clients = await Contact.find({ userId }).sort({ name: 1 });
        console.log(`[DEBUG] Found ${clients.length} clients`);
        res.status(200).json(clients);
    } catch (error) {
        console.error('Get clients error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
