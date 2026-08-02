import { Response } from 'express';
import Contact from '../models/Contact';
import { AuthRequest } from '../middleware/authMiddleware';
import * as contactService from '../services/contactService';
import { isObjectId } from '../utils/objectId';

export const getContacts = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const contacts = await contactService.getContactsWithOpenTaskCounts(userId as string);
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

export const deleteContact = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }

        const contactId = req.params.id;
        if (!isObjectId(contactId)) {
            return res.status(400).json({ message: 'invalid contact id' });
        }

        const result = await contactService.deleteContactCascade(userId, contactId);
        if (!result) {
            return res.status(404).json({ message: 'Contact not found' });
        }

        res.status(200).json(result);
    } catch (error) {
        console.error('Delete contact error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
