import mongoose from 'mongoose';
import Call from '../models/Call';

export const saveRawCall = async (
    userId: string, 
    contactId: string | mongoose.Types.ObjectId, 
    transcript: string,
    callDate: Date
) => {
    return await Call.create({
        userId,
        contactId,
        fullTranscript: transcript,
        callDateTime: callDate,
    });
};

export const updateCallWithAnalysis = async (callId: string, summary: string) => {
    return await Call.findByIdAndUpdate(callId, { callSummary: summary }, { returnDocument: 'after' });
};