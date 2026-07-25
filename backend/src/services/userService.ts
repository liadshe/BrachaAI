import Contact from '../models/Contact';
import User from '../models/User';

export const getOrCreateContact = async (userId: string, contactName: string, phoneNumber?: string) => {

    // Try to find by name first
    let contact = await Contact.findOne({ userId, name: contactName });

    // If not found by name and we have a phone number, try finding by phone
    if (!contact && phoneNumber && phoneNumber !== "Unknown") {
        contact = await Contact.findOne({ userId, phone: phoneNumber });
    }

    if (!contact) {
        contact = await Contact.create({
            userId,
            name: contactName,
            phone: phoneNumber || "000-000-000",
            isVip: false
        });
        console.log(`👤 Created new contact: ${contactName} (${phoneNumber || 'no phone'})`);
    } else if (phoneNumber && phoneNumber !== "Unknown" && contact.phone === "000-000-000") {
        // Update phone number if it was a placeholder
        contact.phone = phoneNumber;
        await contact.save();
        console.log(`📞 Updated phone for contact: ${contactName}`);
    }
    
    return contact;
};

export const getFirstUser = async () => {
    const user = await User.findOne();
    return user;
};
