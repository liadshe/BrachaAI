import OpenAI from 'openai';
import dotenv from 'dotenv';

dotenv.config();

export interface AiAnalysis {
    summary: string;
    tasks: AiTask[];
}

export interface AiTask {
    task: string;
    assignee: string;
    deadline: string;
}

export const analyzeTranscript = async (transcript: string): Promise<AiAnalysis> => {
    const openai = new OpenAI({ 
    apiKey: process.env.OPENAI_API_KEY 
});

    const response = await openai.chat.completions.create({
        model: "gpt-4o",
        messages: [
            { 
                role: "system", 
                content: `You are a business assistant. Analyze the call transcript and return ONLY a JSON object with this exact structure:
{
  "summary": "brief summary of the call",
  "tasks": [
    { "task": "task description", "assignee": "person name or empty string", "deadline": "date string or empty string" }
  ]
}
Return an empty array for tasks if there are none.`
            },
            { role: "user", content: transcript }
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