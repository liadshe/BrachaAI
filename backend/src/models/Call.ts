import mongoose, { Schema, Document } from 'mongoose';

const CallSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    fullTranscript: { type: String, required: true },
    callSummary: { type: String },
    callDateTime: { type: Date, default: Date.now },
    callLength: { type: Number }, // in seconds
});

export default mongoose.model('Call', CallSchema);