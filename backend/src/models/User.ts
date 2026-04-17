import mongoose, { Schema, Document } from 'mongoose';

export interface IUser extends Document {
    name: string;
    email: string;
    phone: string;
    avatar?: string;
    permissions: string[];
}

const UserSchema = new Schema({
    name: { type: String, required: true },
    email: { type: String, required: true, unique: true },
    phone: { type: String, required: true },
    avatar: { type: String },
    permissions: [{ type: String, default: ['standard'] }]
});

export default mongoose.model<IUser>('User', UserSchema);