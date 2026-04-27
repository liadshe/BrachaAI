import mongoose, { Schema, Document } from 'mongoose';

const TaskSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    title: { type: String, required: true },
    description: { type: String },
    priority: { type: String, enum: ['LOW', 'MEDIUM', 'HIGH'], default: 'LOW' },
    status: { type: String, enum: ['todo', 'in-progress', 'done'], default: 'todo' },
});

export default mongoose.model('Task', TaskSchema);