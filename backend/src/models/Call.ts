import mongoose, { Schema, Document } from 'mongoose';

const CallSchema = new Schema({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    contactId: { type: Schema.Types.ObjectId, ref: 'Contact', required: true },
    fullTranscript: { type: String, required: true },
    callSummary: { type: String },
    // Deliberately has no default. A call whose direction the device could not determine
    // must leave this unset, so the client can render it neutrally. Defaulting to
    // 'incoming' is what made every call — including every outgoing one — display as an
    // incoming call whenever the direction was missing from the upload.
    callType: {
        type: String,
        enum: ['incoming', 'outgoing', 'missed'],
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