import OpenAI from 'openai';
import dotenv from 'dotenv';

dotenv.config();

export interface AiAnalysis {
    summary: string;
    tasks: string[];
    mood: string;
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
                content: `You are a business assistant. Analyze the transcript and return a JSON object...` 
            },
            { role: "user", content: transcript }
        ],
        response_format: { type: "json_object" }
    });

    const content = response.choices[0].message.content || "{}";
    console.log("AI RAW RESPONSE:", content);
    
    // Explicitly cast the parsed JSON to our new Interface
    return JSON.parse(content) as AiAnalysis;
};