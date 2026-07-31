"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.deleteContact = exports.getContactById = exports.getContacts = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const contactService = __importStar(require("../services/contactService"));
const objectId_1 = require("../utils/objectId");
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
const deleteContact = async (req, res) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }
        const contactId = req.params.id;
        if (!(0, objectId_1.isObjectId)(contactId)) {
            return res.status(400).json({ message: 'invalid contact id' });
        }
        const result = await contactService.deleteContactCascade(userId, contactId);
        if (!result) {
            return res.status(404).json({ message: 'Contact not found' });
        }
        res.status(200).json(result);
    }
    catch (error) {
        console.error('Delete contact error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.deleteContact = deleteContact;
