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
exports.bulkDeleteCalls = exports.handleIncomingAndroidCall = exports.getCalls = void 0;
const Call_1 = __importDefault(require("../models/Call"));
const userService = __importStar(require("../services/userService"));
const callService = __importStar(require("../services/callService"));
const aiService = __importStar(require("../services/aiService"));
const taskService_1 = require("../services/taskService");
const objectId_1 = require("../utils/objectId");
const parseFilenameDate = (dateString) => {
    if (!dateString)
        return new Date(); // Fallback to now if no date is provided
    try {
        // Expected format from Android: "260415_165702" (YYMMDD_HHMMSS)
        const [datePart, timePart] = dateString.split('_');
        const year = parseInt(datePart.substring(0, 2)) + 2000;
        const month = parseInt(datePart.substring(2, 4)) - 1;
        const day = parseInt(datePart.substring(4, 6));
        const hour = parseInt(timePart.substring(0, 2));
        const minute = parseInt(timePart.substring(2, 4));
        const second = parseInt(timePart.substring(4, 6));
        return new Date(year, month, day, hour, minute, second);
    }
    catch (error) {
        console.error("Failed to parse date string, falling back to current time", error);
        return new Date();
    }
};
/**
 * Whole seconds, or undefined for anything we will not stand behind.
 *
 * Deliberately never an error. `AudioProcessor.NON_RETRYABLE_CODES` treats a 400 as
 * permanent — the client drops the payload and never retries it — so rejecting a call
 * over a malformed duration would destroy the transcript to protect an integer.
 */
const parseCallLength = (raw) => {
    if (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 0) {
        return undefined;
    }
    return Math.round(raw);
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
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ success: false, message: 'Unauthenticated' });
        }
        const { contactName, date, transcript, callerNumber, callLength } = req.body;
        if (!transcript) {
            return res.status(400).json({ success: false, message: 'transcript is required' });
        }
        console.log(`[DEBUG] Android call webhook for userId: ${userId}`);
        const actualCallDate = parseFilenameDate(date);
        const contact = await userService.getOrCreateContact(userId, contactName, callerNumber ?? null);
        const call = await callService.saveRawCall(userId, contact.id, transcript, actualCallDate, parseCallLength(callLength));
        const businessDescription = req.user?.businessDescription || '';
        // Respond as soon as the call is durable. Analysis is slow and may fail;
        // making the client wait on it would turn AI errors into duplicate uploads.
        res.status(201).json({ success: true, callId: call.id, analysisStatus: 'pending' });
        void runAnalysis(call.id, userId, contact.id, transcript, businessDescription);
    }
    catch (error) {
        console.error("Controller Error:", error);
        if (!res.headersSent)
            res.status(500).json({ success: false });
    }
};
exports.handleIncomingAndroidCall = handleIncomingAndroidCall;
const runAnalysis = async (callId, userId, contactId, transcript, businessDescription) => {
    try {
        const analysis = await aiService.analyzeTranscript(transcript, businessDescription);
        await callService.updateCallWithAnalysis(callId, analysis.summary);
        console.log(`Processed: ${analysis.summary}`);
        if (analysis?.tasks &&
            Array.isArray(analysis.tasks) &&
            analysis.tasks.length > 0) {
            await (0, taskService_1.createTasksFromAi)(userId, contactId, analysis.tasks);
            console.log(`Tasks created: ${analysis.tasks.length}`);
        }
    }
    catch (error) {
        console.error(`Analysis failed for call ${callId}:`, error);
        try {
            await callService.markAnalysisFailed(callId);
        }
        catch (markFailedError) {
            console.error(`Failed to mark analysis as failed for call ${callId}:`, markFailedError);
        }
    }
};
const bulkDeleteCalls = async (req, res) => {
    try {
        const userId = req.user?.id;
        if (!userId) {
            return res.status(401).json({ message: 'Unauthenticated' });
        }
        const validation = (0, objectId_1.validateObjectIdList)(req.body?.ids);
        if (!validation.ok) {
            return res.status(400).json({ message: validation.message });
        }
        const deletedCount = await callService.deleteCallsByIds(userId, validation.ids);
        res.status(200).json({ deletedCount });
    }
    catch (error) {
        console.error('Bulk delete calls error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.bulkDeleteCalls = bulkDeleteCalls;
