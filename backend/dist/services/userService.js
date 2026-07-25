"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getFirstUser = exports.getOrCreateContact = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
const User_1 = __importDefault(require("../models/User"));
const getOrCreateContact = async (userId, contactName, phoneNumber) => {
    // Try to find by name first
    let contact = await Contact_1.default.findOne({ userId, name: contactName });
    // If not found by name and we have a phone number, try finding by phone
    if (!contact && phoneNumber && phoneNumber !== "Unknown") {
        contact = await Contact_1.default.findOne({ userId, phone: phoneNumber });
    }
    if (!contact) {
        contact = await Contact_1.default.create({
            userId,
            name: contactName,
            phone: phoneNumber || "000-000-000",
            isVip: false
        });
        console.log(`👤 Created new contact: ${contactName} (${phoneNumber || 'no phone'})`);
    }
    else if (phoneNumber && phoneNumber !== "Unknown" && contact.phone === "000-000-000") {
        // Update phone number if it was a placeholder
        contact.phone = phoneNumber;
        await contact.save();
        console.log(`📞 Updated phone for contact: ${contactName}`);
    }
    return contact;
};
exports.getOrCreateContact = getOrCreateContact;
const getFirstUser = async () => {
    const user = await User_1.default.findOne();
    return user;
};
exports.getFirstUser = getFirstUser;
