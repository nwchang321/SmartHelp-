# SmartHelp+ Prompt Analysis Report

> Generated: 2026-04-08  
> Scope: `guidance.system.txt`, `task-planner.system.txt`, `promptContextBuilder.js`, `liveGuidanceConfig.js`, `taskAgent.js`

---

## System Architecture Overview

```
Elderly User (voice/tap)
    ↓
Android App (GeminiLiveClient.java)
    ↓ WebSocket
Node.js Server
    ├── TaskAgent          → gemini-2.5-flash   (task planning, one-shot)
    └── GeminiLiveAgent    → gemini-2.5-flash-native-audio-preview-12-2025 (real-time guidance)
            ↓ tool call
        highlightElement(x, y, completed)
            ↓
        OverlayService (on-screen arrow + highlight)
```

**Two prompts in play:**
- `task-planner.system.txt` — used by TaskAgent to break user goal into steps (JSON output)
- `guidance.system.txt` — used by GeminiLive to look at screenshots and guide the user (audio output + tool call)

---

## Issues in `guidance.system.txt`

### 🔴 Issue 1 — VERIFY intermediate steps say NOTHING (anxiety-inducing for elderly)

**Current rule:**
```
If EXPECTED_RESULT is clearly visible and IS_LAST_STEP is false:
  → highlightElement(x=50, y=50, completed=true)
  → say NOTHING
```

**Problem:**  
The elderly user just performed an action and receives complete silence. They do not know if they succeeded or failed. The FYP report (Chapter 2.6.2) cites research showing that undifferentiated or absent feedback causes cognitive overload and task abandonment in elderly users. Silence is the worst possible feedback signal.

**Fix:**
```
If EXPECTED_RESULT is clearly visible and IS_LAST_STEP is false:
  → highlightElement(x=50, y=50, completed=true)
  → say ONE brief acknowledgment: "Good." / "Done." or "好" / "对了"
  → Do NOT describe what comes next. The system moves automatically.
```

---

### 🔴 Issue 2 — x=50, y=50 used for two opposite meanings (semantic collision)

**Current usage:**
| Situation | x | y | completed |
|-----------|---|---|-----------|
| Target not on screen → ask user to swipe | 50 | 50 | false |
| Step verified as complete | 50 | 50 | true |

**Problem:**  
The same coordinate pair (50, 50) signals two completely opposite states. Gemini must infer meaning purely from `completed`, but the tool description does not communicate this dual role. This causes the model to sometimes call `completed=true` with (50, 50) when it actually cannot find the target, silently skipping a step.

**Fix — separate the two meanings in the tool description:**
```js
// In liveGuidanceConfig.js
description: `Highlight one UI element on screen.
  x, y = center of tappable element as screen percentages (0-100).
  completed=false: guiding. Point to the exact target center.
  completed=true: EXPECTED_RESULT is confirmed visible. Use x=50, y=50 ONLY for this case.
  Target not visible: use x=50, y=50, completed=false — means "not found, instruct swipe/scroll".
  NEVER use x=50, y=50, completed=true unless you have confirmed EXPECTED_RESULT is on screen.`
```

---

### 🔴 Issue 3 — Tone rules missing: no warmth, no reassurance for technostress

**Problem:**  
The FYP report (Chapter 1.1, 2.4.1) explicitly identifies "technostress" as the core problem for elderly users. Raha et al. (2022) states that effective LLM guidance for elderly requires prompts that enforce simple, non-judgmental language. The current system prompt has zero emotional/tone guidance. When verification fails, Gemini re-guides mechanically with no reassurance, potentially increasing anxiety.

**Fix — add a TONE section to the system prompt:**
```
TONE RULES:
- Always sound calm, patient, and encouraging. Never rushed or robotic.
- When VERIFY fails, begin with one brief reassurance before re-guiding.
  English: "Almost there." / "Try again." / "No worries."
  Chinese: "快了" / "再试一次" / "没关系"
- Never use the words: wrong, error, failed, incorrect, mistake.
- Never repeat the same instruction verbatim more than once. Rephrase simply.
```

---

### 🟡 Issue 4 — Speech length hard limit is too rigid

**Current rule:**
```
Chinese: 2-10 characters
English: 2-8 words
```

**Problem:**  
Enforcing a character/word ceiling causes Gemini to truncate naturally clear instructions:
- "点击下方绿色的'发送'按钮" = 12 characters, perfectly clear → gets cut
- "Type your phone number in the box" = 8 words, clear → exactly at limit

Hard limits also create perverse incentives: Gemini may choose a cryptic short phrase over a clear longer one.

**Fix — replace with principle-based rules:**
```
SPEECH LENGTH:
- One action per sentence. Never chain two instructions.
- Prefer under 10 words (EN) or 12 characters (ZH).
- For ACTION=type with short text: speak the text aloud.
- For ACTION=type with long text: say "Type this" / "输入这个" only — do not read it out.
- Never explain the whole task. Guide only the current moment.
```

---

### 🟡 Issue 5 — Context blocks repeat RULES already in system prompt (conflict risk)

**Problem:**  
`promptContextBuilder.js` appends a `RULES:` section to every context block sent to Gemini Live. These rules overlap significantly with the system prompt. Duplicated instructions cause two problems:

1. **Weight dilution** — the model sees the same rule twice and may treat neither as authoritative
2. **Silent divergence** — if either copy is updated and the other is not, behavior drifts unpredictably

Example of duplication:
- System prompt: `"Call highlightElement exactly once per NAVIGATE or VERIFY turn."`
- Context block NAVIGATE: `"completed MUST be false."`
- Context block VERIFY: `"CRITICAL: Loading/spinner screens are NOT completion."`

**Fix:**  
Context blocks should contain **data fields only**. All behavioral rules belong exclusively in the system prompt.

```
// Context block should only be:
MODE: NAVIGATE
OUTPUT_LANGUAGE: Chinese
GOAL: Send a WhatsApp message to daughter
STEP_INDEX: 2/4
INSTRUCTION: Tap the chat with your daughter
ACTION: tap
TARGET: daughter's chat
MATCH_HINTS: profile photo | name label | recent message
AVOID_HINTS: group chats | unknown contacts
EXPECTED_RESULT: Chat conversation screen is open
```

---

### 🟡 Issue 6 — No description grounding before tool call (visual hallucination risk)

**Research finding:**  
"Visual Description Grounding Reduces Hallucinations" (arxiv 2405.15683) demonstrates that requiring models to describe what they see before producing coordinates significantly reduces grounding errors in UI tasks.

**Current behavior:**  
Gemini receives a screenshot and immediately outputs coordinates without any internal validation step.

**Fix — add a grounding instruction to the system prompt:**
```
VISUAL GROUNDING:
- Before calling highlightElement, internally confirm you can see the TARGET.
- If you can locate TARGET with high confidence: use its center coordinates.
- If you cannot locate TARGET or confidence is low: use x=50, y=50, completed=false
  and say one direction (swipe left / scroll down / etc.).
- Never guess. A visually similar element is not the correct element.
```

---

### 🟡 Issue 7 — No latency awareness directive

**Official Gemini Live API guidance:**  
For real-time audio sessions, the system prompt should instruct the model not to perform visible reasoning chains, as this increases time-to-first-audio significantly.

**Fix:**
```
RESPONSE TIMING:
- Respond immediately to user intent. Do not reason out loud.
- Do not summarize what the user said or asked.
- Call highlightElement as the first action — do not delay it until after speaking.
- Keep the audio response to one sentence maximum.
```

---

## Issues in `task-planner.system.txt`

### 🔴 Issue 8 — No Malaysian app ecosystem context

**Problem:**  
The FYP report (Chapter 1.1) identifies the following as the primary apps elderly Malaysians need help with: TNG eWallet, MySejahtera, DuitNow, Shopee, Lazada, Grab, online banking (Maybank2u, CIMB Clicks, RHB Now). The current COMMON TASK TEMPLATES only covers: Call, SMS, WhatsApp, Open App.

**Fix — add Malaysian-specific templates:**
```
TOUCH N' GO TOP UP:
1. tap → Touch 'n Go eWallet app icon
2. tap → Reload / Top Up button
3. tap → payment method (credit card / online banking)
4. type → amount field with the reload amount

DUITNOW TRANSFER (from banking app):
1. tap → banking app icon (Maybank / CIMB / RHB etc.)
2. tap → Transfer or DuitNow option
3. tap → recipient field or "New Transfer"
4. type → recipient field with phone number or IC number
5. type → amount field with transfer amount
6. tap → Confirm / Next button

GRAB FOOD ORDER:
1. tap → Grab app icon
2. tap → Food option
3. tap → restaurant or search field
4. tap → menu item

SHOPEE / LAZADA SEARCH:
1. tap → app icon
2. tap → search bar at top
3. type → search bar with the product name
4. tap → search result
```

---

### 🟡 Issue 9 — 6-step hard cap forces over-simplification of complex tasks

**Problem:**  
Mobile banking tasks (login → biometric → navigate → enter recipient → enter amount → confirm → OTP → done) routinely require 7–9 steps. Forcing compression to 6 steps causes the planner to merge steps (e.g., "open app and go to transfer page" as one step), which then confuses the guidance agent since the screenshot may be mid-way.

**Fix:**
```
// In task-planner.system.txt
- Maximum 8 steps. Hard cap is 8, not 6.
- If a task genuinely needs more than 8 steps, return the first logical sub-goal only
  (e.g., stop after "successfully logged in" for a banking task).
- OTP verification and biometric authentication each count as 1 step.

// In taskSchema.js
maxItems: 8   // change from 6
```

---

### 🟡 Issue 10 — Safety rules do not cover Malaysian scam patterns

**Problem:**  
Malaysia has a high prevalence of "Macau scam" (Penipuan Modus Macau), QR code scams, and impersonation scams via WhatsApp. The current safety rules are generic.

**Fix — add explicit scam pattern detection:**
```
SCAM DETECTION — return ask_user step with safety warning if ANY of these match:
- User asked to transfer money to a stranger, "government account", or unknown number
- User received a QR code via WhatsApp/SMS from someone they do not know personally
- User was told to enter an OTP sent to their phone by someone else
- User mentions receiving a call from "police", "PDRM", "bank officer", or "government"
- User asked to install a remote control app (AnyDesk, TeamViewer, etc.)
- User asked to scan a QR code to "verify" their bank account

For any of the above, return:
  action: ask_user
  instruction: "Are you sure this is safe? This sounds like it could be a scam. 
                Please check with a family member before continuing."
```

---

### 🟡 Issue 11 — No Malay language in planner examples

**Problem:**  
The target users are Malaysian elderly who may speak in Malay or mixed Malay-Chinese (Manglish). The planner examples only show English. If a user says "Saya nak hantar duit kat anak saya" (I want to send money to my child), the planner must handle Malay input correctly.

**Fix:**
```
// Add to task-planner.system.txt
LANGUAGE HANDLING:
- Accept input in English, Malay, Mandarin, or mixed.
- Normalize the goal into English for internal step planning.
- Common Malay terms: "hantar duit" = transfer money, "top up" = reload,
  "mesej" = message, "gambar" = photo, "nombor telefon" = phone number.
```

---

## Issues in `promptContextBuilder.js`

### 🔴 Issue 12 — RETRY mode outputs `MODE: NAVIGATE` (bug)

**Location:** `promptContextBuilder.js` line 124

**Current code:**
```js
if (mode === 'RETRY' && currentStep) {
    return (
        `MODE: NAVIGATE\n`   // ← hardcoded NAVIGATE, not RETRY
        ...
        `RETRY_REASON: ${reason}\n`
```

**Problem:**  
The system prompt defines four modes: NAVIGATE, VERIFY, CHAT, HELP. There is no RETRY mode. So Gemini sees `MODE: NAVIGATE` but also a `RETRY_REASON` field it does not know how to interpret. The NAVIGATE rules apply, and the retry context is ignored.

**Fix:**
```js
// Option A — add RETRY as a recognized mode in guidance.system.txt
`MODE: RETRY\n`

// And add to guidance.system.txt:
MODE: RETRY
- Same as NAVIGATE but the previous attempt failed.
- Re-examine the screenshot carefully — the screen may have changed.
- If the same target is still visible, re-highlight it with greater precision.
- Begin with one brief reassurance ("Try again." / "再试一次"), then re-guide.
- completed MUST be false.

// Option B — keep MODE: NAVIGATE but surface retry context more clearly
`MODE: NAVIGATE\n` +
`CONTEXT: Previous attempt failed — ${reason}. Re-examine the screenshot before acting.\n`
```

---

## Issues in `liveGuidanceConfig.js` and `taskAgent.js`

### 🟡 Issue 13 — Tool description missing "when to call" context

**Official Gemini function calling guidance:**  
Tool descriptions should specify not just parameters, but when and why the function should be called.

**Current description (truncated):**
```
"Highlight one UI element on screen. x,y = screen percentages (0-100)..."
```

**Fix:**
```js
description: `Call this function exactly ONCE per NAVIGATE or VERIFY turn.
  Do NOT call in CHAT or HELP modes.
  Call it before or at the same time as speaking — never after.
  x, y = center of the tappable element as screen percentages (0=left/top, 100=right/bottom).
  completed=false: you are guiding the user to this element.
  completed=true: EXPECTED_RESULT is confirmed visible on screen. Use x=50, y=50.
  If target not found: use x=50, y=50, completed=false and say one scroll/swipe direction.`
```

---

### 🟡 Issue 14 — TaskAgent uses `gemini-2.5-flash` for task planning

**Problem:**  
Complex tasks like mobile banking, government apps, and multi-step DuitNow transfers require strong reasoning to produce correct step sequences. `gemini-2.5-flash` is a better fit than `flash-lite` for planner reliability while staying lightweight enough for this app.

**Fix:**
```js
// taskAgent.js line 32
model: 'gemini-2.5-flash'
```

---

## Summary Table

| # | Priority | Problem | File |
|---|----------|---------|------|
| 1 | 🔴 Critical | VERIFY intermediate steps say NOTHING → elderly anxiety | `guidance.system.txt` |
| 2 | 🔴 Critical | x=50,y=50 means both "not found" and "completed" → model confusion | `guidance.system.txt` + `liveGuidanceConfig.js` |
| 3 | 🔴 Critical | No tone/warmth rules for elderly users under technostress | `guidance.system.txt` |
| 4 | 🔴 Critical | RETRY mode outputs `MODE: NAVIGATE` hardcoded bug | `promptContextBuilder.js:124` |
| 5 | 🔴 Critical | Tool description missing "when to call" context | `liveGuidanceConfig.js` |
| 6 | 🟡 Recommended | Hard character/word limits → replace with principles | `guidance.system.txt` |
| 7 | 🟡 Recommended | Context blocks duplicate system prompt RULES | `promptContextBuilder.js` |
| 8 | 🟡 Recommended | No description grounding before tool call (hallucination risk) | `guidance.system.txt` |
| 9 | 🟡 Recommended | No latency awareness directive for real-time audio | `guidance.system.txt` |
| 10 | 🟡 Recommended | No Malaysian app templates (TNG, DuitNow, Grab, Shopee) | `task-planner.system.txt` |
| 11 | 🟡 Recommended | 6-step cap too low for banking/government tasks → raise to 8 | `task-planner.system.txt` + `taskSchema.js` |
| 12 | 🟡 Recommended | Safety rules don't cover Malaysian scam patterns | `task-planner.system.txt` |
| 13 | 🟡 Recommended | No Malay language handling in planner | `task-planner.system.txt` |
| 14 | 🟢 Nice to have | Use `gemini-2.5-flash` for planner reliability | `taskAgent.js:32` |
| 15 | 🟢 Nice to have | Annotate screenshots with UI element numbers (FYP2) | `ScreenCaptureService.java` |

---

## References

- FYP1 Report: SmartHelp+ (Ngu Wen Chang, 2024)
- Google Gemini Live API Documentation — [ai.google.dev/gemini-api/docs/live-api](https://ai.google.dev/gemini-api/docs/live-api)
- Google Cloud Live API Overview — [cloud.google.com/vertex-ai/generative-ai/docs/live-api](https://cloud.google.com/vertex-ai/generative-ai/docs/live-api)
- "Visual Description Grounding Reduces Hallucinations" — arxiv 2405.15683
- "Navigating the Digital World as Humans Do" — arxiv 2410.05243
- Raha et al. (2022) — Prompt engineering for elderly-appropriate LLM guidance
- Sweller et al. (2011) — Cognitive Load Theory
- Nielsen Norman Group (2020) — Usability testing with elderly participants
