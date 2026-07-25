"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.updateCallWithAnalysis = exports.saveRawCall = void 0;
const Call_1 = __importDefault(require("../models/Call"));
const saveRawCall = async (userId, contactId, transcript, callDate, callType = 'UNKNOWN') => {
    return await Call_1.default.create({
        userId,
        contactId,
        fullTranscript: transcript,
        callDateTime: callDate,
        callType: callType
    });
};
exports.saveRawCall = saveRawCall;
const updateCallWithAnalysis = async (callId, summary) => {
    return await Call_1.default.findByIdAndUpdate(callId, { callSummary: summary }, { returnDocument: 'after' });
};
exports.updateCallWithAnalysis = updateCallWithAnalysis;
