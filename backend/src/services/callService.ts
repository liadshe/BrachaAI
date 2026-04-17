import mongoose from 'mongoose';
import Call from '../models/Call';

export const saveRawCall = async (
    userId: string, 
    contactId: string | mongoose.Types.ObjectId, 
    transcript: string
) => {
    return await Call.create({
        userId,
        contactId,
        fullTranscript: transcript,
        callDateTime: new Date(),
    });
};

export const updateCallWithAnalysis = async (callId: string, summary: string) => {
    return await Call.findByIdAndUpdate(callId, { callSummary: summary }, { returnDocument: 'after' });
};