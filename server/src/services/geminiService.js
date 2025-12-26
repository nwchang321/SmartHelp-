const { GoogleGenerativeAI } = require('@google/generative-ai');

const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);

// System prompt for elderly-friendly guidance with precise positioning
const SYSTEM_PROMPT = `You are SmartHelp+. Analyze screenshots and guide elderly users.

RESPOND WITH THIS EXACT JSON FORMAT:
{"instruction":"Please tap [button]","highlight":{"x":50,"y":50},"completed":false}

RULES:
- instruction: Short sentence telling user what to tap (start with "Please tap...")
- highlight.x: Horizontal position 0-100 (0=left, 100=right)
- highlight.y: Vertical position 0-100 (0=top, 100=bottom)
- completed: Set to TRUE if the user's goal is achieved, FALSE if more steps needed

WHEN TO SET completed=true:
- User wanted to open Settings → Settings screen is now visible
- User wanted to change font size → Font size settings are showing
- User wanted to send a message → Message was sent successfully
- The screen shows the result the user wanted

POSITION EXAMPLES:
- Top-right corner: x=92, y=8
- Top-left corner: x=8, y=8
- Bottom-center: x=50, y=95
- Center screen: x=50, y=50

OUTPUT ONLY THE JSON. NO OTHER TEXT.`;

// Normalize Gemini response to expected format
// Handles both old format (guidance.voiceText) and new format (instruction)
function normalizeResponse(parsed) {
    let instruction = null;
    let highlight = { x: 50, y: 50 };
    let completed = false;

    // Try new format first: { instruction, highlight: {x, y}, completed }
    if (parsed.instruction) {
        instruction = parsed.instruction;
    }
    // Try old format: { guidance: { voiceText, steps, highlight } }
    else if (parsed.guidance) {
        instruction = parsed.guidance.voiceText ||
                      (parsed.guidance.steps && parsed.guidance.steps[0]?.description) ||
                      'Please tap the highlighted area';
    }

    // Parse highlight coordinates
    if (parsed.highlight && typeof parsed.highlight.x === 'number') {
        highlight = {
            x: Math.round(parsed.highlight.x),
            y: Math.round(parsed.highlight.y)
        };
    } else if (parsed.guidance?.highlight) {
        // Old format might have position string like "top-right"
        const pos = parsed.guidance.highlight.position || '';
        highlight = positionToPercent(pos);
    }

    // Check if task is completed
    if (parsed.completed === true) {
        completed = true;
    }

    return { instruction, highlight, completed };
}

// Convert position string to percentage coordinates
function positionToPercent(position) {
    const positions = {
        'top-left': { x: 15, y: 10 },
        'top-center': { x: 50, y: 10 },
        'top-right': { x: 85, y: 10 },
        'center-left': { x: 15, y: 50 },
        'center': { x: 50, y: 50 },
        'center-right': { x: 85, y: 50 },
        'bottom-left': { x: 15, y: 90 },
        'bottom-center': { x: 50, y: 90 },
        'bottom-right': { x: 85, y: 90 },
    };
    return positions[position] || { x: 50, y: 50 };
}

async function analyzeScreenshot(base64Image, userQuery = null) {
    try {
        const model = genAI.getGenerativeModel({
            model: 'gemini-2.5-pro',
            generationConfig: {
                temperature: 0.2,  // Lower for more consistent positioning
                topK: 32,
                topP: 0.9,
                maxOutputTokens: 2048,
                responseMimeType: "application/json",  // Force JSON output
            }
        });

        // Build the prompt
        let prompt = SYSTEM_PROMPT;
        if (userQuery) {
            prompt += `\n\nUser wants to: "${userQuery}"\nTell them what to tap next.`;
        } else {
            prompt += `\n\nWhat should the user tap on this screen?`;
        }

        const imagePart = {
            inlineData: {
                mimeType: 'image/jpeg',
                data: base64Image
            }
        };

        console.log('[Gemini] Analyzing screenshot...');
        const result = await model.generateContent([prompt, imagePart]);
        const response = await result.response;
        const text = response.text();
        console.log('[Gemini] Raw response:', text.substring(0, 200) + '...');

        // Parse JSON response - handle potential markdown code blocks
        let jsonStr = text;

        // Remove markdown code blocks if present
        jsonStr = jsonStr.replace(/```json\n?/g, '').replace(/```\n?/g, '');

        // Find JSON object
        const jsonMatch = jsonStr.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
            let cleanJson = jsonMatch[0];

            // Fix common JSON issues
            // Remove trailing commas before } or ]
            cleanJson = cleanJson.replace(/,(\s*[}\]])/g, '$1');

            try {
                const parsed = JSON.parse(cleanJson);

                // Normalize response to expected format
                const normalized = normalizeResponse(parsed);
                console.log('[Gemini] Instruction:', normalized.instruction);
                console.log('[Gemini] Highlight x:', normalized.highlight?.x, 'y:', normalized.highlight?.y);
                return normalized;
            } catch (parseError) {
                console.error('[Gemini] JSON parse error:', parseError.message);
                console.log('[Gemini] Raw JSON:', cleanJson.substring(0, 500));
            }
        }

        // Fallback if response is not valid JSON
        console.log('[Gemini] Could not parse JSON, using fallback');
        return {
            instruction: 'I can see your screen. What would you like help with?',
            highlight: {
                x: 50,
                y: 50
            }
        };

    } catch (error) {
        console.error('[Gemini] API Error:', error);
        throw new Error(`Failed to analyze screenshot: ${error.message}`);
    }
}

async function testConnection() {
    try {
        const model = genAI.getGenerativeModel({ model: 'gemini-2.5-pro' });
        const result = await model.generateContent('Say "SmartHelp+ is ready!" in exactly those words.');
        return result.response.text();
    } catch (error) {
        throw new Error(`Gemini connection failed: ${error.message}`);
    }
}

module.exports = {
    analyzeScreenshot,
    testConnection
};
