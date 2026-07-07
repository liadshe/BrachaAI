"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getFirstUser = exports.getOrCreateContact = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const User_1 = __importDefault(require("../models/User"));
const getOrCreateContact = async (userId, contactName) => {
    // Check if the contact already exists for this specific business owner
    let contact = await Contact_1.default.findOne({ userId, name: contactName });
    if (!contact) {
        contact = await Contact_1.default.create({
            userId,
            name: contactName,
            phone: "000-00000", // place holder 
            isVip: false
        });
        console.log(`👤 Created new contact: ${contactName}`);
    }
    return contact;
};
exports.getOrCreateContact = getOrCreateContact;
const getFirstUser = async () => {
    const user = await User_1.default.findOne();
    return user;
};
exports.getFirstUser = getFirstUser;
