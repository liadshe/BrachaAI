import { Request, Response } from 'express';
import * as userService from '../services/userService';
import * as callService from '../services/callService';
import * as aiService from '../services/aiService';
import { createTasksFromAi } from '../services/taskService';

export const handleIncomingAndroidCall = async (req: Request, res: Response) => {
    try {
        const { contactName, transcript } = req.body;
        const mockUserId = "65f1234567890abcdef12345"; 

        // 1. Identify/Create the Contact
        const contact = await userService.getOrCreateContact(mockUserId, contactName);

        // 2. Save the initial call
        const call = await callService.saveRawCall(mockUserId, contact.id, transcript);

        // 3. Analyze using AI (Now returns an object {summary, tasks, mood})
        const analysis = await aiService.analyzeTranscript(transcript);

        // 4. Update the call with summary and mood
        await callService.updateCallWithAnalysis(call.id, analysis.summary);
        // (You can also add analysis.mood to your Call model later!)

        // 5. Save the generated tasks
        if (analysis.tasks && analysis.tasks.length > 0) {
            await createTasksFromAi(mockUserId, contact.id, analysis.tasks);
        }

        console.log(`✅ Processed: ${analysis.summary}`);
        console.log(`📝 Tasks created: ${analysis.tasks.length}`);

        res.status(200).json({ success: true, analysis });

    } catch (error) {
        console.error("Controller Error:", error);
        res.status(500).json({ success: false });
    }
};