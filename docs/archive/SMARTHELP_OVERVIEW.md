# SmartHelp+ — Project Overview

**AI-powered smartphone guidance assistant for elderly users.**  
Android app + Node.js backend relay + Google Gemini Live API for real-time voice guidance and visual highlighting.

---

## Table of Contents

1. [Project Summary](#project-summary)
2. [Tech Stack](#tech-stack)
3. [System Architecture](#system-architecture)
4. [Backend: Server](#backend-server)
5. [Backend: Agents and Modules](#backend-agents-and-modules)
6. [Prompt Strategy](#prompt-strategy)
7. [Android App](#android-app)
8. [WebSocket Message Protocol](#websocket-message-protocol)
9. [Design Language](#design-language)
10. [Setup Guide](#setup-guide)

---

## Project Summary

SmartHelp+ is a Final Year Project (FYP) that helps elderly users navigate their smartphones through real-time voice guidance and on-screen visual highlights. The user speaks a goal (e.g., "Send a WhatsApp message to my son"), and the system plans the steps, guides the user turn by turn with audio, and draws an arrow overlay on the exact UI element the user needs to tap.

**Core capabilities:**

- Real-time voice guidance for everyday phone tasks
- Visual highlight arrow pointing to the correct tap target
- Multi-step task planning with structured steps and auto-verification
- Bilingual support — English and Mandarin (中文)
- Floating ball mode and minimal voice-first overlay on Android
- Auto-verification loop after each user action
- Recovery guidance when the user cannot find the target

---

## Tech Stack

| Layer | Technology |
|---|---|
| Android App | Java, Android SDK 26–34, OkHttp3, Material Design |
| Backend | Node.js 18+, Express, `ws` (WebSocket) |
| AI Planning | Gemini 2.5 Flash (`gemini-2.5-flash`) |
| AI Realtime Guidance | Gemini 2.5 Flash Native Audio (`models/gemini-2.5-flash-native-audio-preview-12-2025`) |
| Audio I/O | 16 kHz PCM input → 24 kHz playback output |
| Comms | WebSocket (`ws://<server-ip>:3000/live`) |
| No database | Fully stateless, real-time streaming |

---

## System Architecture

```
User speaks or types
  └── Android captures mic audio / screenshot / typed text
        └── Sends over WebSocket to Node.js server
              └── OrchestratorAgent receives message
                    ├── AndroidMessageParser parses type (audio | text | image)
                    ├── TaskAgent plans steps (if new goal)
                    ├── PromptContextBuilder builds context block (CHAT | NAVIGATE | VERIFY | RETRY | HELP)
                    └── GeminiLiveAgent sends screenshot + context to Gemini Live
                          └── Gemini returns:
                                ├── Audio response (streamed back to Android)
                                └── highlightElement(x%, y%, completed) tool call
                                      └── OrchestratorAgent validates coordinates
                                            ├── Sends highlight to Android overlay
                                            ├── If completed=true → advance step or signal taskComplete
                                            └── If completed=false → request verify screenshot
```

### Key Architectural Rules

- One `OrchestratorAgent` instance per Android client connection — state is scoped per session.
- Only `TaskAgent` and Gemini Live are model-facing. All other modules are deterministic helpers.
- Coordinates are percentage-based (0–100%), not pixels. Out-of-range values are clamped.
- Gemini reconnects automatically on non-fatal close codes. Android stays connected during reconnect.
- Android inter-service communication uses Broadcast receivers.

---

## Backend: Server

**Entry point:** `server/src/index.js` — Express + WebSocket server on port 3000.

```
server/src/
├── index.js                    # Server entry: Express + WebSocket listener
├── prompts/
│   ├── guidance.system.txt     # Gemini Live guidance system prompt
│   └── task-planner.system.txt # TaskAgent planner system prompt
└── agents/
    ├── orchestratorAgent.js    # Main workflow controller
    ├── geminiLiveAgent.js      # Gemini Live WebSocket I/O layer
    ├── liveGuidanceConfig.js   # Static Gemini Live config (system prompt, tool, voice)
    ├── taskAgent.js            # Multi-step task planner
    ├── taskSchema.js           # Shared planner response schema
    ├── promptContextBuilder.js # Builds CHAT/NAVIGATE/VERIFY/RETRY/HELP context blocks
    └── androidMessageParser.js # Parses Android WebSocket messages
```

---

## Backend: Agents and Modules

### OrchestratorAgent (`orchestratorAgent.js`)

Main workflow controller. One instance per Android client.

**Responsibilities:**
- Owns the Android WebSocket connection
- Maintains task state across multiple turns
- Routes `text` / `audio` / `image` messages
- Builds the correct prompt context before each Gemini turn
- Interprets `highlightElement` tool calls and decides next step
- Handles Gemini reconnect on session close

**Workflow states:**

| State | Trigger |
|---|---|
| `CHAT` | New user input, no active task, or missing information |
| `NAVIGATE` | Current step needs guidance; screenshot received |
| `VERIFY` | User completed an action; screenshot sent for verification |
| `RETRY` | Previous verify failed; re-examine the screenshot |
| `HELP` | 3 consecutive verify failures; switch to descriptive recovery |

---

### GeminiLiveAgent (`geminiLiveAgent.js`)

Gemini Live WebSocket I/O transport layer.

- Opens a WebSocket to the Gemini Live API using `LiveGuidanceConfig` setup message
- Streams PCM audio chunks from Android to Gemini
- Emits events: `ready`, `audio`, `toolCall`, `transcription`, `thinking`, `error`, `close`

---

### LiveGuidanceConfig (`liveGuidanceConfig.js`)

Static configuration for the Gemini Live session:

- **System prompt:** loaded from `src/prompts/guidance.system.txt`
- **Tool:** `highlightElement(x, y, completed)` — Gemini's only structured output
- **Voice:** Aoede
- **Response modality:** AUDIO only

**`highlightElement` parameters:**

| Param | Type | Description |
|---|---|---|
| `x` | integer | Screen width % (0 = left, 100 = right). Center of target element. |
| `y` | integer | Screen height % (0 = top, 100 = bottom). Center of target element. |
| `completed` | boolean | `true` = EXPECTED_RESULT confirmed on screen. `false` = still guiding. |

---

### TaskAgent (`taskAgent.js`)

Multi-step planner using `gemini-2.5-flash`.

- Takes a user's natural language goal and plans structured steps
- Falls back gracefully with `ask_user` steps when information is missing (e.g., recipient name)
- Each step contains: `instruction`, `action`, `target`, `matchHints`, `avoidHints`, `expectedResult`
- Retries with `gemini-2.5-flash-lite` on 503 / high-demand errors

---

### PromptContextBuilder (`promptContextBuilder.js`)

Deterministic helper that builds structured text blocks attached to each screenshot.

**Supported modes:**

| Mode | When used |
|---|---|
| `CHAT` | Conversational reply; no tool call expected |
| `NAVIGATE` | Guide the user to tap a specific target |
| `VERIFY` | Check screenshot to confirm step completion |
| `RETRY` | Re-examine after a failed verify |
| `HELP` | Descriptive recovery after 3 consecutive failures |

Each block includes: `MODE`, `OUTPUT_LANGUAGE`, `GOAL`, `STEP_INDEX`, `INSTRUCTION`, `ACTION`, `TARGET`, `MATCH_HINTS`, `AVOID_HINTS`, `EXPECTED_RESULT`, and mode-specific `RULES`.

---

### AndroidMessageParser (`androidMessageParser.js`)

Parses raw WebSocket messages from Android:

| Message type | Payload |
|---|---|
| `audio` | Base64 PCM 16 kHz microphone chunk |
| `image` | Base64 JPEG screenshot + optional `query` + optional `verify` flag |
| `text` | Typed user query string |

---

## Prompt Strategy

| Prompt | File | Purpose |
|---|---|---|
| Guidance system prompt | `src/prompts/guidance.system.txt` | Rules for Gemini Live: how to highlight, how to verify, elder-friendly speech style |
| Task planner system prompt | `src/prompts/task-planner.system.txt` | Rules for TaskAgent: how to generate structured step plans |

**Guidance prompt principles:**
- Max ~10 words per spoken instruction
- No markdown in audio output
- Bilingual English + Mandarin output controlled by `OUTPUT_LANGUAGE` field
- Confidence triage before choosing coordinates: HIGH → precise target; LOW → center (50, 50) + scroll instruction
- Never highlight a similar-looking but incorrect element

---

## Android App

**Location:** `android/app/src/main/java/com/smarthelp/app/`

| File | Role |
|---|---|
| `MainActivity.java` | Main screen, mic button, session start/stop |
| `OverlayService.java` | Floating overlay service, draws guidance UI on top of other apps |
| `ScreenCaptureService.java` | Screen capture using Android MediaProjection API |
| `GeminiLiveClient.java` | WebSocket client + microphone audio streaming to server |
| `HighlightOverlayView.java` | Draws the animated highlight arrow at given x%, y% coordinates |
| `SmartHelpAccessibilityService.java` | Accessibility service integration |
| `SplashActivity.java` | Splash / onboarding screen |
| `SettingsActivity.java` | App settings (server IP, language, etc.) |
| `AppPrefs.java` | Shared preference helpers |
| `SiriWaveView.java` | Animated waveform shown when listening |
| `VoiceWaveView.java` | Voice activity visualization |
| `PrivacySafety.java` | Privacy/safety utilities |

**Required Android permissions:**
- Overlay (draw over other apps)
- Screen capture (MediaProjection)
- Microphone
- Notification

---

## WebSocket Message Protocol

### Android → Server

| Type | Payload |
|---|---|
| `audio` | `{ type: "audio", data: "<base64 PCM 16kHz>" }` |
| `image` | `{ type: "image", data: "<base64 JPEG>", query?: string, verify?: boolean }` |
| `text` | `{ type: "text", query: string }` |

### Server → Android

| Type | Payload |
|---|---|
| `connecting` | Keepalive during Gemini reconnect |
| `ready` | Gemini session is ready |
| `audio` | `{ type: "audio", mimeType: string, data: "<base64>" }` |
| `highlight` | `{ type: "highlight", x: number, y: number, completed: boolean }` |
| `requestScreenshot` | Prompt Android to send next screenshot |
| `taskComplete` | `{ type: "taskComplete", goal: string }` |
| `transcription` | `{ type: "transcription", text: string }` |
| `error` | `{ type: "error", message: string }` |

---

## Design Language

**Visual identity:** Calm blue, senior-friendly, voice-first.

### Color Palette

| Token | Value |
|---|---|
| Primary | `#1565C0` |
| Primary dark | `#0D47A1` |
| Primary light | `#42A5F5` |
| Accent | `#00ACC1` |
| Background | `#E3F2FD` |
| Surface | `#FFFFFF` |
| Success | `#10B981` |
| Warning | `#F59E0B` |
| Error | `#EF4444` |

### Typography Scale

| Style | Size |
|---|---|
| Display | 34 sp |
| Headline | 28 sp |
| Title | 22 sp |
| Body | 17 sp |
| Guidance text | 18 sp |
| Caption | 13 sp |

### Design Principles

- One primary action per screen
- Large touch targets
- Short, action-first text
- High contrast between text and background
- Stable control positions
- Voice-first overlay — no chat input bar, no competing buttons
- Mic interaction communicates state: pulse on hold → "正在听... / Listening..." → "已收到 / Received" → "处理中... / Processing..."

### Layout Pattern

Every screen follows a two-zone composition:

1. **Hero area** — blue gradient with centered branding and primary action
2. **Body surface** — white rounded sheet with content sections

---

## Setup Guide

### Server

```bash
cd SmartHelp/server
npm install
# Create .env file:
# GEMINI_API_KEY=your_api_key_here
# LOG_GEMINI_THINKING=false   (optional)
npm start
```

Server listens on `ws://0.0.0.0:3000/live`.

### Android

1. Open `SmartHelp/android` in Android Studio
2. Sync Gradle
3. Go to app Settings and enter the server IP address
4. Run on a physical Android device (API 26+)
5. Grant permissions: Overlay, Screen Capture, Microphone, Notification

---

## Project Status

MVP in active development for Final Year Project submission.

**Current focus:**
- Reliable multi-step task planning
- Clear, elderly-friendly guidance speech
- Safer step verification and retry logic
- Prompt evaluation tooling (`server/promptfoo/`)
