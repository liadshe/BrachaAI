import { Response } from "express";
import Call from "../models/Call";
import * as userService from "../services/userService";
import * as callService from "../services/callService";
import * as aiService from "../services/aiService";
import { createTasksFromAi } from "../services/taskService";
import { AuthRequest } from "../middleware/authMiddleware";
import { validateObjectIdList } from "../utils/objectId";

const parseFilenameDate = (dateString: string): Date => {
  if (!dateString) return new Date(); // Fallback to now if no date is provided

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
  } catch (error) {
    console.error("Failed to parse date string, falling back to current time", error);
    return new Date();
  }
};

export const getCalls = async (req: AuthRequest, res: Response) => {
  try {
    const userId = req.user?.id;
    console.log(`[DEBUG] Fetching calls for userId: ${userId}`);
    const calls = await Call.find({ userId }).populate('contactId').sort({ callDateTime: -1 });
    console.log(`[DEBUG] Found ${calls.length} calls`);
    res.status(200).json(calls);
  } catch (error) {
    console.error('Get calls error:', error);
    res.status(500).json({ message: 'Internal server error' });
  }
};

export const handleIncomingAndroidCall = async (
  req: AuthRequest,
  res: Response,
) => {
  try {
    const userId = req.user?.id;
    if (!userId) {
      return res.status(401).json({ success: false, message: 'Unauthenticated' });
    }

    const { contactName, date, transcript, callerNumber } = req.body;
    if (!transcript) {
      return res.status(400).json({ success: false, message: 'transcript is required' });
    }

    console.log(`[DEBUG] Android call webhook for userId: ${userId}`);

    const actualCallDate = parseFilenameDate(date);
    const contact = await userService.getOrCreateContact(userId, contactName, callerNumber ?? null);
    const call = await callService.saveRawCall(
      userId,
      contact.id,
      transcript,
      actualCallDate
    );

    const businessDescription = req.user?.businessDescription || '';

    // Respond as soon as the call is durable. Analysis is slow and may fail;
    // making the client wait on it would turn AI errors into duplicate uploads.
    res.status(201).json({ success: true, callId: call.id, analysisStatus: 'pending' });

    void runAnalysis(call.id, userId, contact.id, transcript, businessDescription);
  } catch (error) {
    console.error("Controller Error:", error);
    if (!res.headersSent) res.status(500).json({ success: false });
  }
};

const runAnalysis = async (
  callId: string,
  userId: string,
  contactId: string,
  transcript: string,
  businessDescription: string,
) => {
  try {
    const analysis = await aiService.analyzeTranscript(transcript, businessDescription);
    await callService.updateCallWithAnalysis(callId, analysis.summary);
    console.log(`Processed: ${analysis.summary}`);

    if (
      analysis?.tasks &&
      Array.isArray(analysis.tasks) &&
      analysis.tasks.length > 0
    ) {
      await createTasksFromAi(userId, contactId, analysis.tasks);
      console.log(`Tasks created: ${analysis.tasks.length}`);
    }
  } catch (error) {
    console.error(`Analysis failed for call ${callId}:`, error);
    try {
      await callService.markAnalysisFailed(callId);
    } catch (markFailedError) {
      console.error(`Failed to mark analysis as failed for call ${callId}:`, markFailedError);
    }
  }
};

export const bulkDeleteCalls = async (req: AuthRequest, res: Response) => {
  try {
    const userId = req.user?.id;
    if (!userId) {
      return res.status(401).json({ message: 'Unauthenticated' });
    }

    const validation = validateObjectIdList(req.body?.ids);
    if (!validation.ok) {
      return res.status(400).json({ message: validation.message });
    }

    const deletedCount = await callService.deleteCallsByIds(userId, validation.ids);
    res.status(200).json({ deletedCount });
  } catch (error) {
    console.error('Bulk delete calls error:', error);
    res.status(500).json({ message: 'Internal server error' });
  }
};