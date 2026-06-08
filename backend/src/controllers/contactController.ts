import { Response } from 'express';
import Contact from '../models/Contact';
import { AuthRequest } from '../middleware/authMiddleware';

export const getContacts = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const contacts = await Contact.find({ userId }).sort({ name: 1 });
        res.status(200).json(contacts);
    } catch (error) {
        console.error('Get contacts error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const getContactById = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const contactId = req.params.id;
        const contact = await Contact.findOne({ _id: contactId, userId });
        if (!contact) {
            return res.status(404).json({ message: 'Contact not found' });
        }
        res.status(200).json(contact);
    } catch (error) {
        console.error('Get contact by id error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
