"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.handleIncomingAndroidCall = exports.getCalls = void 0;
const Call_1 = __importDefault(require("../models/Call"));
const userService = __importStar(require("../services/userService"));
const callService = __importStar(require("../services/callService"));
const aiService = __importStar(require("../services/aiService"));
const taskService_1 = require("../services/taskService");
const parseFilenameDate = (dateString) => {
    if (!dateString)
        return new Date(); // Fallback to now if no date is provided
    try {
        // Expected format from Android: "YYMMDD_HHMMSS" e.g. "260415_165702"
        const [datePart, timePart] = dateString.split('_');
        if (!datePart || !timePart || datePart.length < 6 || timePart.length < 6) {
            const fallback = new Date(dateString);
            return isNaN(fallback.getTime()) ? new Date() : fallback;
        }
        const year = parseInt(datePart.substring(0, 2), 10) + 2000;
        const month = parseInt(datePart.substring(2, 4), 10) - 1;
        const day = parseInt(datePart.substring(4, 6), 10);
        const hour = parseInt(timePart.substring(0, 2), 10);
        const minute = parseInt(timePart.substring(2, 4), 10);
        const second = parseInt(timePart.substring(4, 6), 10);
        const parsedDate = new Date(year, month, day, hour, minute, second);
        return isNaN(parsedDate.getTime()) ? new Date() : parsedDate;
    }
    catch (error) {
        console.error("Failed to parse date string, falling back to current time", error);
        return new Date();
    }
};
const getCalls = async (req, res) => {
    try {
        const userId = req.user?.id;
        console.log(`[DEBUG] Fetching calls for userId: ${userId}`);
        const calls = await Call_1.default.find({ userId }).populate('contactId').sort({ callDateTime: -1 });
        console.log(`[DEBUG] Found ${calls.length} calls`);
        res.status(200).json(calls);
    }
    catch (error) {
        console.error('Get calls error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getCalls = getCalls;
const handleIncomingAndroidCall = async (req, res) => {
    try {
        // Extract fields from req.body based on Android JSON schema
        const { contactName, phoneNumber, callType, date, transcript } = req.body;
        const validCallTypes = ['INCOMING', 'OUTGOING', 'UNKNOWN'];
        const normalizedCallType = typeof callType === 'string' && validCallTypes.includes(callType.toUpperCase())
            ? callType.toUpperCase()
            : 'UNKNOWN';
        // Dynamically fetch first user from DB as active user
        const firstUser = await userService.getFirstUser();
        const activeUserId = firstUser ? firstUser.id : "65f1234567890abcdef12345";
        console.log(`[DEBUG] Android call webhook. Mapping to activeUserId: ${activeUserId}`);
        // Parse the date string into a Date object
        const actualCallDate = parseFilenameDate(date);
        // Identify/Create the Contact (passing phoneNumber)
        const contact = await userService.getOrCreateContact(activeUserId, contactName, phoneNumber);
        // Pass the actualCallDate and normalizedCallType to saveRawCall
        const call = await callService.saveRawCall(activeUserId, contact.id, transcript, actualCallDate, normalizedCallType);
        // Analyze using AI (Now returns an object {summary, tasks, mood})
        const analysis = await aiService.analyzeTranscript(transcript);
        // Update the call with summary and mood
        await callService.updateCallWithAnalysis(call.id, analysis.summary);
        console.log(`Processed: ${analysis.summary}`);
        // Save the generated tasks
        if (analysis?.tasks &&
            Array.isArray(analysis.tasks) &&
            analysis.tasks.length > 0) {
            await (0, taskService_1.createTasksFromAi)(activeUserId, contact.id, analysis.tasks);
            console.log(`Tasks created: ${analysis.tasks?.length ?? 0}`);
        }
        res.status(200).json({ success: true, analysis });
    }
    catch (error) {
        console.error("Controller Error:", error);
        res.status(500).json({ success: false });
    }
};
exports.handleIncomingAndroidCall = handleIncomingAndroidCall;
