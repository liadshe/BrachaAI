import mongoose, { Schema, Document } from 'mongoose';

export interface ITask extends Document {
    userId: mongoose.Types.ObjectId;
    contactId: mongoose.Types.ObjectId;
    title: string;
    description?: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
    status: 'todo' | 'in-progress' | 'done';
    dueDate?: string;
    completed: boolean;
}

const TaskSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    title: { type: String, required: true },
    description: { type: String },
    priority: { type: String, enum: ['LOW', 'MEDIUM', 'HIGH'], default: 'LOW' },
    status: { type: String, enum: ['todo', 'in-progress', 'done'], default: 'todo' },
    dueDate: { type: String },
    completed: { type: Boolean, default: false }
}, { timestamps: true });

export default mongoose.model<ITask>('Task', TaskSchema);