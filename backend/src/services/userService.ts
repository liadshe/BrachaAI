import Contact from '../models/Contact';
import User from '../models/User';

export const getOrCreateContact = async (userId: string, contactName: string) => {

    // Check if the contact already exists for this specific business owner
    let contact = await Contact.findOne({ userId, name: contactName });

    if (!contact) {
        contact = await Contact.create({
            userId,
            name: contactName,
            phone: "000-000-000", // Placeholder until we get real Caller ID
            isVip: false
        });
        console.log(`👤 Created new contact: ${contactName}`);
    }
    return contact;
};

export const getFirstUser = async () => {
    const user = await User.findOne();
    return user;
};