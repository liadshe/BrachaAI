import OpenAI from 'openai';
import dotenv from 'dotenv';

dotenv.config();

export interface AiAnalysis {
    summary: string;
    tasks: AiTask[];
}

export interface AiTask {
    title: string;
    description: string;
    priority: 'LOW' | 'MEDIUM' | 'HIGH';
    dueDate: string;
}

export const analyzeTranscript = async (transcript: string, businessDescription: string = ''): Promise<AiAnalysis> => {
    const openai = new OpenAI({ 
    apiKey: process.env.OPENAI_API_KEY 
});
    const currentDate = new Date().toISOString().split('T')[0];
    const response = await openai.chat.completions.create({
        model: "gpt-4o",
        messages: [
            { 
                role: "system", 
                content: `You process phone call transcripts. 
Current Date: ${currentDate}

Use the provided business description to understand the user's company and call context.
If a business description is not provided, proceed based only on the transcript.

Respond ONLY with valid JSON — no markdown, no extra text:
{
  "summary": "...",
  "tasks": [
    { 
      "title": "short action item title", 
      "description": "more detail if needed", 
      "priority": "LOW",
      "dueDate": "YYYY-MM-DD" 
    }
  ]
}

PRIORITY RULES:
- HIGH: urgent or time-sensitive — has a near deadline, was explicitly stressed, or blocks something important.
- MEDIUM: important but not urgent — clear commitment with no tight deadline.
- LOW: nice-to-do or vague — no urgency, informational.

DUE DATE RULES:
- Analyze the transcript for explicit or implicit deadlines (e.g., "tomorrow", "by next Friday", "EOD").
- Calculate the exact date in YYYY-MM-DD format using the Current Date provided above.
- If NO deadline or timeframe is mentioned for a task, set the dueDate to exactly 3 days after the Current Date.

SUMMARY RULES:
- Make it highly informative and effective. Do not just state that a call happened; capture the core objective, key decisions made, and unresolved issues.
- For business calls: State the main outcome and summarize what both parties agreed upon in 2-3 concise sentences.
- For personal/casual calls without tasks: Describe the core topic in 1 concise sentence (e.g., "Discussed weekend plans and family updates").
- NEVER return an empty summary unless the transcript is completely silent or incomprehensible. Do not use phrases like "no relevant information".

TASKS RULES:
- Only extract clear action items with a responsible party.
- If the call is purely personal with nothing to do, return an empty tasks array.
- Deduplicate tasks: Merge repeated mentions of the same action into a single task.
- When merging, retain the most complete description and the highest priority stated.

LANGUAGE RULES:
- Answer in Hebrew. Every piece of human-readable text you produce must be Hebrew: "summary", and every task's "title" and "description".
- This holds regardless of the transcript's language. If the call was in English or any other language, translate your answer into Hebrew. Do NOT mirror the transcript's language.
- Do NOT translate the machine-read parts, or the response fails to parse:
  - The JSON keys stay exactly as written above: summary, tasks, title, description, priority, dueDate.
  - "priority" stays one of LOW, MEDIUM, HIGH — English, capitalised.
  - "dueDate" stays in YYYY-MM-DD digits.
- Keep people's names, company names, and product names in their original spelling rather than transliterating them.`
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
