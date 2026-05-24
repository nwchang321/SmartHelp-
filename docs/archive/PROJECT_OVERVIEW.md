# SmartHelp+ - Project Overview

AI-powered smartphone guidance assistant for elderly users.  
Android app + Node.js backend relay + Google Gemini Live API.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Android App | Java, Android SDK 26-34, OkHttp3, Material Design |
| Backend | Node.js 18+, Express, ws |
| AI Planning | Gemini 2.5 Flash Lite |
| AI Realtime Guidance | Gemini 2.5 Flash Native Audio |
| Audio | 16kHz PCM in -> 24kHz playback out |

## Backend Structure

```text
server/src/
├── index.js
├── prompts/
│   ├── guidance.system.txt
│   └── task-planner.system.txt
└── agents/
    ├── orchestratorAgent.js
    ├── geminiLiveAgent.js
    ├── liveGuidanceConfig.js
    ├── taskAgent.js
    ├── taskSchema.js
    ├── promptContextBuilder.js
    └── androidMessageParser.js
```

## Architectural Note

The backend is best understood as a workflow-based architecture:

- `OrchestratorAgent` owns state and routing
- `TaskAgent` is the planner
- `GeminiLiveAgent` is the realtime model transport
- `PromptContextBuilder` is a deterministic prompt/context helper
- `AndroidMessageParser` is a deterministic parser helper

This is intentionally simpler than a true multi-agent system. The helper modules are not autonomous agents.

## Main Flow

```text
User speaks or types
  -> Android sends text/audio/image over WebSocket
  -> OrchestratorAgent plans task if needed
  -> PromptContextBuilder creates a strict mode block
  -> GeminiLiveAgent sends screenshot + context to Gemini Live
  -> Gemini returns audio + highlightElement tool call
  -> Orchestrator validates the tool call and updates task state
  -> Android shows highlight / plays audio / sends verify screenshot
```

## Prompt Strategy

- Planner prompt lives in `src/prompts/task-planner.system.txt`
- Live guidance prompt lives in `src/prompts/guidance.system.txt`
- Prompt eval scaffolding lives in `server/promptfoo/`

## Status

MVP in active development for Final Year Project work.  
Current focus: reliable step planning, clear elderly-friendly guidance, safer verification, and prompt evaluation.
