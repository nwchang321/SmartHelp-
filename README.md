# SmartHelp+

> AI-powered, voice-first smartphone guidance assistant for elderly users.
> Final Year Project — Bachelor of Computer Science (Hons), UTS.

SmartHelp+ helps older Android users complete everyday phone tasks one step at a time. The user says (or taps) what they want to do — *"send a WhatsApp message to my daughter"*, *"open the camera"* — and the app captures the current screen, asks an AI pipeline what the next step is, then **speaks a short instruction and draws a highlight on the exact button to tap**. After the user taps, the system takes another screenshot, verifies progress, and continues until the task is done.

The app **never taps for the user**. It only guides.

---

## Architecture at a glance

```text
┌────────────────────┐    LiveKit WebRTC   ┌──────────────────────┐
│   Android client   │  ─────────────────▶ │   LiveKit Cloud      │
│                    │   audio + video     │   (room/media SFU)   │
│  - OverlayService  │  ◀─────────────────  │                      │
│  - ScreenCapture   │   data channel JSON └──────────┬───────────┘
│  - Accessibility   │                                │
│  - TTS playback    │                                ▼
└────────┬───────────┘                     ┌──────────────────────┐
         │  HTTP :8765 (token issue)       │ Python LiveKit Agent │
         └─────────────────────────────────▶│  (multi-stage AI)    │
                                            └──────────┬───────────┘
                                                       │
                       ┌───────────────────────────────┴────────────────┐
                       │      Gemini multi-agent pipeline               │
                       │  STT → Intent → Safety → ReAct → Executor     │
                       │  → Vision Grounding / Accessibility Fast Path │
                       │  → TTS                                         │
                       └───────────────────────────────────────────────┘
```

The Python agent replaces the older Node.js WebSocket server entirely.

---

## Tech stack

| Layer | Technology |
|---|---|
| Android app | Java, Android SDK 26–34, Material Components |
| Android capabilities | MediaProjection, AccessibilityService, SpeechRecognizer, Overlay Window, TTS |
| Transport | LiveKit WebRTC — Audio Track (mic + TTS) + Data Channel (JSON messages) |
| Token server | Python HTTP on port `8765` (issues short-lived LiveKit tokens) |
| AI runtime | Python LiveKit Agent (`agent.py`) |
| STT | Google Cloud Speech-to-Text (primary) + Gemini STT (fallback) |
| Intent / ReAct navigation | `gemini-2.5-flash-lite` |
| Vision grounding | `gemini-3-flash-preview` |
| TTS | Gemini TTS (voice = **Aoede**, ZH/EN) |
| Storage | SharedPreferences + in-memory state (no DB) |

Each model is overridable via env vars (`GEMINI_INTENT_MODEL`, `GEMINI_REACT_MODEL`, `GEMINI_VISION_MODEL`, `GEMINI_TTS_MODEL`).

---

## Project structure

```text
SmartHelp/
├── android/                          # Android native app (Java)
│   └── app/src/main/java/com/smarthelp/app/
│       ├── MainActivity.java
│       ├── OverlayService.java       # Main runtime UX controller
│       ├── ScreenCaptureService.java # MediaProjection-based capture
│       ├── HighlightOverlayView.java # On-screen arrow + highlight
│       ├── SmartHelpAccessibilityService.java
│       ├── network/                  # ServerConnection — LiveKit client
│       ├── input/ overlay/ session/
│       └── ...
│
├── server-python/                    # Python LiveKit Agent (the AI brain)
│   ├── agent.py                      # Entry point — SmartHelpAgent
│   ├── token_server.py               # HTTP token issuer (:8765)
│   ├── intent_agent.py               # Intent classification
│   ├── intent_safety_agent.py        # Risk gate before navigation
│   ├── task_executor.py              # ReAct-controlled guidance loop
│   ├── task_state_machine.py         # IDLE / INTAKE / GUIDING / VERIFYING / ...
│   ├── vision_grounding_tool.py      # Pixel coordinates for highlight
│   ├── navigation_react_agent.py     # ReAct loop for navigation
│   ├── accessibility_fast_path.py    # Bypass vision when a11y node info is available
│   ├── safety_gate.py                # High-risk prompt reminder
│   ├── voice_synthesizer.py          # Gemini TTS wrapper
│   ├── prompt_registry.py            # Centralised prompt templates
│   ├── prompts/                      # Prompt files by stage
│   └── tests/                        # pytest suite
│
├── docs/
│   ├── architecture/                 # Current architecture references
│   │   ├── MULTI_AGENT_REACT_PLAN.md
│   │   └── SMARTHELP_MULTIAGENT_RUNTIME_DIAGRAMS.md
│   ├── testing/                      # Test plans, evidence, templates
│   ├── design.md                     # Visual design guide
│   ├── UI_DESIGN_SPEC.md             # UI spec
│   └── archive/                      # Old Node.js-era docs (kept for history)
│
├── branding/                         # Logos, color assets
├── overview.md                       # Long-form project overview (Chinese)
├── start-all.bat / start-all.ps1     # One-click launcher (Windows)
├── stop-all.bat
└── _run-agent.bat                    # Auto-restart wrapper used by start-all
```

---

## AI pipeline (multi-agent)

Each user utterance flows through staged agents, not a single end-to-end model:

1. **STT** — Google Cloud STT (Gemini STT as fallback) transcribes the mic audio.
2. **Intent Agent** (`intent_agent.py`) — classifies the user's goal.
3. **Intent Safety Agent** (`intent_safety_agent.py`) — blocks high-risk goals (banking transfers, OTP entry, unknown links) before navigation starts.
4. **Navigation ReAct Agent** (`navigation_react_agent.py`) — decides one next action at a time from the goal, observations, accessibility metadata, and verification history.
5. **Task State Machine** (`task_state_machine.py`) — drives state across `IDLE → INTAKE → GUIDING → AWAITING_ACTION → VERIFYING → RETRYING → HELPING → COMPLETED`.
6. **Task Executor** (`task_executor.py`) — runs the ReAct loop and decides what to ask the user and what visual cue to show.
7. **Vision Grounding** (`vision_grounding_tool.py`) — given the current screenshot, returns pixel coords for the highlight.
8. **Accessibility Fast Path** (`accessibility_fast_path.py`) — when `AccessibilityNodeInfo` already pinpoints the target, skip vision.
9. **TTS** (`voice_synthesizer.py`) — synthesises the short voice instruction (Aoede voice).

The Android side stays thin: it captures, sends, plays, highlights, and waits for the user to tap.

---

## Getting started

### Prerequisites

- Windows 10/11 (the launcher scripts are Windows-targeted)
- Android Studio + ADB (`platform-tools`)
- Python 3.13 with a virtualenv at `server-python/venv/`
- A connected Android device or emulator (API ≥ 26)
- A `.env` file in `server-python/` with:
  - `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`
  - `GOOGLE_API_KEY` (Gemini)
  - `GOOGLE_APPLICATION_CREDENTIALS` (Google Cloud STT service account JSON)

### Install Python deps

```powershell
cd server-python
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

### Run

One-click (recommended):

```powershell
.\start-all.bat
```

This starts the token server, launches the LiveKit Agent under an auto-restart wrapper, and sets up `adb reverse tcp:8765`.

To stop:

```powershell
.\stop-all.bat
```

### Build the Android app

Open `android/` in Android Studio, build & install onto the device, then grant:
- Microphone
- Display over other apps
- Accessibility Service (SmartHelp+)
- Screen capture (prompt appears on first use)

---

## Testing

Python unit tests:

```powershell
cd server-python
.\venv\Scripts\python.exe -m pytest
```

Manual / edge-case test plans and evidence live in [docs/testing/](docs/testing/).

---

## Documentation

- [overview.md](overview.md) — current long-form project description (Chinese)
- [docs/architecture/MULTI_AGENT_REACT_PLAN.md](docs/architecture/MULTI_AGENT_REACT_PLAN.md) — multi-agent design rationale
- [docs/architecture/SMARTHELP_MULTIAGENT_RUNTIME_DIAGRAMS.md](docs/architecture/SMARTHELP_MULTIAGENT_RUNTIME_DIAGRAMS.md) — runtime sequence diagrams
- [docs/design.md](docs/design.md) — visual design language
- [docs/UI_DESIGN_SPEC.md](docs/UI_DESIGN_SPEC.md) — UI specification
- [docs/archive/](docs/archive/) — pre-rewrite documentation, kept for historical reference only

---

## Safety boundaries

By design, SmartHelp+ refuses to guide through:
- Banking transfers, OTP / TAC / PIN entry, payment confirmations
- Remote app installation
- Suspicious links or unknown QR codes

These are intercepted by the **Intent Safety Agent** and **Safety Gate** before any plan is generated.

---

## License & credits

SmartHelp+ is licensed under the [MIT License](LICENSE).

Final Year Project, Bachelor of Computer Science (Hons), School of Computing and Creative Media, University of Technology Sarawak.
