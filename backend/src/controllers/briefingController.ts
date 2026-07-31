import { Response } from 'express';
import { AuthRequest } from '../middleware/authMiddleware';
import * as briefingService from '../services/briefingService';
import { isObjectId } from '../utils/objectId';

export const getBriefings = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const briefings = await briefingService.getBriefings(userId);
        res.status(200).json(briefings);
    } catch (error) {
        console.error('Get briefings error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const getBriefingByContactId = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const contactId = req.params.contactId;
        if (!isObjectId(contactId)) {
            return res.status(400).json({ message: 'invalid contact id' });
        }

        const briefing = await briefingService.getBriefing(userId, contactId);
        if (!briefing) {
            return res.status(404).json({ message: 'Contact not found' });
        }

        res.status(200).json(briefing);
    } catch (error) {
        console.error('Get briefing error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
