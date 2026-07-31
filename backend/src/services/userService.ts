import Contact from '../models/Contact';
import User from '../models/User';

export const PLACEHOLDER_PHONE = '000-000-000';

/** Digits only, preserving a leading '+'. Returns null for unusable input. */
const normalizePhone = (raw?: string | null): string | null => {
    if (!raw) return null;
    const trimmed = String(raw).trim();
    const prefix = trimmed.startsWith('+') ? '+' : '';
    const digits = trimmed.replace(/\D/g, '');
    return digits ? prefix + digits : null;
};

export const getOrCreateContact = async (
    userId: string,
    contactName: string,
    callerNumber: string | null = null,
) => {
    const phone = normalizePhone(callerNumber);

    // Phone is the strongest identifier — prefer it over the recorded name.
    if (phone) {
        const byPhone = await Contact.findOne({ userId, phone });
        if (byPhone) return byPhone;
    }

    const byName = await Contact.findOne({ userId, name: contactName });
    if (byName) {
        // Backfill a real number over the placeholder, but never overwrite a known one.
        if (phone && byName.phone === PLACEHOLDER_PHONE) {
            byName.phone = phone;
            await byName.save();
            console.log(`Backfilled phone for contact ${contactName}`);
        }
        return byName;
    }

    const created = await Contact.create({
        userId,
        name: contactName,
        phone: phone ?? PLACEHOLDER_PHONE, // schema requires a phone
        isVip: false,
    });
    console.log(`Created new contact: ${contactName}`);
    return created;
};