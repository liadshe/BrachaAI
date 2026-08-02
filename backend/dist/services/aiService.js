"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.analyzeTranscript = void 0;
const openai_1 = __importDefault(require("openai"));
const dotenv_1 = __importDefault(require("dotenv"));
dotenv_1.default.config();
const analyzeTranscript = async (transcript, businessDescription = '') => {
    const openai = new openai_1.default({
        apiKey: process.env.OPENAI_API_KEY
    });
    const response = await openai.chat.completions.create({
        model: "gpt-4o",
        messages: [
            {
                role: "system",
                content: `You process phone call transcripts.
  Use the provided business description to understand the user's company and call context.
  If a business description is not provided, proceed based only on the transcript.

  Respond ONLY with valid JSON — no markdown, no extra text:
  {
    "summary": "...",
    "tasks": [
      { "title": "short action item title", "description": "more detail if needed", "priority": "LOW" }
    ]
  }
  Priority must be one of: LOW, MEDIUM, HIGH.

  PRIORITY RULES:
  - HIGH: urgent or time-sensitive — has a near deadline, was explicitly stressed, or blocks something important.
    Example: "Send the contract today", "Call back the client before 5pm", "Fix the bug before launch"
  - MEDIUM: important but not urgent — clear commitment with no tight deadline.
    Example: "Schedule a follow-up meeting", "Prepare the presentation for next week"
  - LOW: nice-to-do or vague — no deadline, no urgency, informational.
    Example: "Look into the new pricing options", "Check if the report is ready"

  SUMMARY RULES — always write something meaningful:
  - If the call has decisions, commitments, or next steps → state them concisely (1-2 sentences).
    Good: "Launch pushed to June 15; Sarah sends updated brief by Friday."
  - If the call is personal or casual with no tasks → describe what it was about in 1 short sentence.
    Good: "Talked about the Dexter series mom is watching."
    Good: "Brother mentioned he's currently in Haifa."
    Good: "Mom listed food options available at home."
  - NEVER return an empty summary unless the transcript is completely silent, blank, or incomprehensible.
  - Do NOT use phrases like "no relevant information" or "nothing to summarize" — always describe the topic.

  TASKS RULES:
  - Only extract clear action items with a responsible party or deadline.
  - If the call is purely personal with nothing to do, return an empty tasks array.

  IMPORTANT: Detect the language of the transcript and write the summary and all task text in that same language. Do not translate.`
            },
            { role: "user", content: `Business description: ${businessDescription || 'None provided'}\n\nTranscript:\n${transcript}` }
        ],
        response_format: { type: "json_object" }
    });
    const content = response.choices[0].message.content || "{}";
    const parsed = JSON.parse(content);
    console.log("AI RAW RESPONSE:", JSON.stringify(parsed, null, 2));
    return {
        summary: parsed.summary ?? parsed.Summary ?? parsed.SUMMARY ?? "",
        tasks: parsed.tasks ?? parsed.Tasks ?? parsed.TASKS ?? [],
    };
};
exports.analyzeTranscript = analyzeTranscript;
