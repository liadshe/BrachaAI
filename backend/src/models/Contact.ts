import mongoose, { Schema, Document } from 'mongoose';

export interface IContact extends Document {
    userId: mongoose.Types.ObjectId;
    name: string;
    phone: string;
    isVip: boolean;
}

const ContactSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    name: { type: String, default: 'unknown' },
    phone: { type: String, required: true },
    isVip: { type: Boolean, default: false }
});

export default mongoose.model<IContact>('Contact', ContactSchema);