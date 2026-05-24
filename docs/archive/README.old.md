# SmartHelp+

AI-powered smartphone guidance assistant for elderly users.
Android app + Node.js backend + Gemini Live API for real-time voice guidance and visual highlighting.

## Project Structure

```text
SmartHelp/
├── server/
│   ├── src/
│   │   ├── index.js                    # WebSocket + Express server (port 3000)
│   │   ├── prompts/                    # Externalized LLM prompt files
│   │   └── agents/
│   │       ├── orchestratorAgent.js    # Main workflow controller
│   │       ├── geminiLiveAgent.js      # Gemini Live WebSocket I/O layer
│   │       ├── liveGuidanceConfig.js   # Static Gemini Live prompt + tool config
│   │       ├── taskAgent.js            # Multi-step task planner
│   │       ├── taskSchema.js           # Shared planner response schema
│   │       ├── promptContextBuilder.js # Builds CHAT/NAVIGATE/VERIFY context blocks
│   │       └── androidMessageParser.js # Parses Android WebSocket messages
│   ├── promptfoo/                      # Prompt eval tooling
│   ├── AGENTS.md
│   └── package.json
└── android/
    └── app/src/main/
```

## Runtime Architecture

```text
Android App
    │  WebSocket (ws://<server-ip>:3000/live)
    ▼
index.js ─── one OrchestratorAgent per Android client connection
    │
OrchestratorAgent ─── workflow controller: CHAT | NAVIGATE | VERIFY | RETRY
    ├── AndroidMessageParser  parses messages: audio | text | image
    ├── TaskAgent             plans steps with gemini-2.5-flash
    ├── PromptContextBuilder  builds strict screenshot context blocks
    └── GeminiLiveAgent       sends/receives Gemini Live WebSocket traffic
                 │
                 └── uses LiveGuidanceConfig for system prompt + highlightElement tool
```

This is a controlled workflow, not a free-form multi-agent swarm. Only `TaskAgent` and Gemini Live are model-facing. The parser and context builder are deterministic helpers.

## Core Features

- Real-time voice guidance for everyday phone tasks
- Visual highlight arrow that points to the correct tap target
- Multi-step task planning with structured steps and verification
- Chat / Navigate / Verify routing controlled by the server
- Ask-user fallback when task details are missing
- Bilingual support for English and Mandarin
- Floating ball mode and quick actions on Android
- Auto-verification loop after user actions

## Message Protocol

### Android -> Server

- `audio`: base64 PCM 16kHz microphone chunk
- `image`: base64 JPEG screenshot, optional `query`, optional `verify`
- `text`: typed query

### Server -> Android

- `ready`
- `audio`
- `highlight`
- `requestScreenshot`
- `taskComplete`
- `transcription`
- `error`

## Prompt Files

- [`server/src/prompts/task-planner.system.txt`](server/src/prompts/task-planner.system.txt)
- [`server/src/prompts/guidance.system.txt`](server/src/prompts/guidance.system.txt)

See [`server/AGENTS.md`](server/AGENTS.md) for prompt component notes.

## Setup

### Server

Open one terminal for the LiveKit token server:

```powershell
cd "C:\Users\HP\Downloads\FYP 1\SmartHelp\server-python"
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
python token_server.py
```

Open a second terminal for the SmartHelp+ LiveKit agent:

```powershell
cd "C:\Users\HP\Downloads\FYP 1\SmartHelp\server-python"
.\venv\Scripts\Activate.ps1
python agent.py dev
```

Natural AI voice is enabled by default when `GOOGLE_API_KEY` is present in
`server-python/.env`. The token server exposes `/tts` for startup/fixed prompts,
the LiveKit agent sends Gemini TTS WAV audio for guidance replies, and the app
falls back to Android local TTS only if Gemini TTS is unavailable. Optional
voice settings:

```powershell
GEMINI_TTS_MODEL=gemini-3.1-flash-tts-preview
GEMINI_TTS_VOICE=Kore
SMARTHELP_AI_TTS=true
```

For a physical Android device connected over USB, map the phone's local port to
the PC token server before launching the app:

```powershell
adb reverse tcp:8765 tcp:8765
```

The Android app now requests `http://127.0.0.1:8765/token` automatically on
startup. On an emulator it also falls back to `http://10.0.2.2:8765/token`.

### Android

1. Open `SmartHelp/android` in Android Studio
2. Sync Gradle
3. Run on a physical device or emulator
4. Grant overlay, screen capture, microphone, and notification permissions
