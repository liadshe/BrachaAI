"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getContactById = exports.getContacts = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const getContacts = async (req, res) => {
    try {
        const userId = req.user?.id;
        const contacts = await Contact_1.default.find({ userId }).sort({ name: 1 });
        res.status(200).json(contacts);
    }
    catch (error) {
        console.error('Get contacts error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getContacts = getContacts;
const getContactById = async (req, res) => {
    try {
        const userId = req.user?.id;
        const contactId = req.params.id;
        const contact = await Contact_1.default.findOne({ _id: contactId, userId });
        if (!contact) {
            return res.status(404).json({ message: 'Contact not found' });
        }
        res.status(200).json(contact);
    }
    catch (error) {
        console.error('Get contact by id error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getContactById = getContactById;
