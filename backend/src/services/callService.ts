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
    return await Call.findByIdAndUpdate(
        callId,
        { callSummary: summary, analysisStatus: 'done' },
        { returnDocument: 'after' }
    );
};

export const markAnalysisFailed = async (callId: string) => {
    return await Call.findByIdAndUpdate(
        callId,
        { analysisStatus: 'failed' },
        { returnDocument: 'after' }
    );
};

export const deleteCallsByIds = async (userId: string, ids: string[]): Promise<number> => {
    const result = await Call.deleteMany({ _id: { $in: ids }, userId });
    return result.deletedCount ?? 0;
};