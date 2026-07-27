"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.getOrCreateContact = exports.PLACEHOLDER_PHONE = void 0;
const Contact_1 = __importDefault(require("../models/Contact"));
exports.PLACEHOLDER_PHONE = '000-000-000';
/** Digits only, preserving a leading '+'. Returns null for unusable input. */
const normalizePhone = (raw) => {
    if (!raw)
        return null;
    const trimmed = String(raw).trim();
    const prefix = trimmed.startsWith('+') ? '+' : '';
    const digits = trimmed.replace(/\D/g, '');
    return digits ? prefix + digits : null;
};
const getOrCreateContact = async (userId, contactName, callerNumber = null) => {
    const phone = normalizePhone(callerNumber);
    // Phone is the strongest identifier — prefer it over the recorded name.
    if (phone) {
        const byPhone = await Contact_1.default.findOne({ userId, phone });
        if (byPhone)
            return byPhone;
    }
    const byName = await Contact_1.default.findOne({ userId, name: contactName });
    if (byName) {
        // Backfill a real number over the placeholder, but never overwrite a known one.
        if (phone && byName.phone === exports.PLACEHOLDER_PHONE) {
            byName.phone = phone;
            await byName.save();
            console.log(`Backfilled phone for contact ${contactName}`);
        }
        return byName;
    }
    const created = await Contact_1.default.create({
        userId,
        name: contactName,
        phone: phone ?? exports.PLACEHOLDER_PHONE, // schema requires a phone
        isVip: false,
    });
    console.log(`Created new contact: ${contactName}`);
    return created;
};
exports.getOrCreateContact = getOrCreateContact;
