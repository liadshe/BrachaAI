import { Request, Response } from "express";
import * as userService from "../services/userService";
import * as callService from "../services/callService";
import * as aiService from "../services/aiService";
import { createTasksFromAi } from "../services/taskService";

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

export const handleIncomingAndroidCall = async (
  req: Request,
  res: Response,
) => {
  try {
    // Extract 'date' from req.body
    const { contactName, date, transcript } = req.body; 
    const mockUserId = "65f1234567890abcdef12345";

    // Parse the date string into a Date object
    const actualCallDate = parseFilenameDate(date); 


    // Identify/Create the Contact
    const contact = await userService.getOrCreateContact(
      mockUserId,
      contactName,
    );

    // Pass the actualCallDate as the 4th parameter
    const call = await callService.saveRawCall(
      mockUserId,
      contact.id,
      transcript,
      actualCallDate 
    );

    // Analyze using AI (Now returns an object {summary, tasks, mood})
    const analysis = await aiService.analyzeTranscript(transcript);

    // Update the call with summary and mood
    await callService.updateCallWithAnalysis(call.id, analysis.summary);

    console.log(`Processed: ${analysis.summary}`);
    
    // Save the generated tasks
    if (
      analysis?.tasks &&
      Array.isArray(analysis.tasks) &&
      analysis.tasks.length > 0
    ) {
      await createTasksFromAi(mockUserId, contact.id, analysis.tasks);
      console.log(`Tasks created: ${analysis.tasks?.length ?? 0}`);
    }

    res.status(200).json({ success: true, analysis });
  } catch (error) {
    console.error("Controller Error:", error);
    res.status(500).json({ success: false });
  }
};