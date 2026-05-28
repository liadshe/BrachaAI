import mongoose, { Schema, Document } from 'mongoose';

export interface IUser extends Document {
    name: string;
    email: string;
    password?: string;
    profilePicture?: string;
    settings: {
        googleCalendarSync: boolean;
        autoCallRecording: boolean;
    };
    permissions: {
        microphone: boolean;
        contacts: boolean;
    };
}

const UserSchema = new Schema({
    name: { type: String, required: true },
    email: { type: String, required: true, unique: true },
    password: { type: String }, // Optional for now if using other auth methods later
    profilePicture: { type: String },
    settings: {
        googleCalendarSync: { type: Boolean, default: false },
        autoCallRecording: { type: Boolean, default: false },
    },
    permissions: {
        microphone: { type: Boolean, default: false },
        contacts: { type: Boolean, default: false },
    }
});


export default mongoose.model<IUser>('User', UserSchema);