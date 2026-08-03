import mongoose, { Schema, Document } from 'mongoose';

const CallSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    fullTranscript: { type: String, required: true },
    callSummary: { type: String },
    callType: {
        type: String,
        enum: ['incoming', 'outgoing', 'missed'],
        default: 'incoming',
    },
    analysisStatus: {
        type: String,
        enum: ['pending', 'done', 'failed'],
        default: 'pending',
    },
    callDateTime: { type: Date, default: Date.now },
    callLength: { type: Number }, // in seconds
});

export default mongoose.model('Call', CallSchema);