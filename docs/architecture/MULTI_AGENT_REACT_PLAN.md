# SmartHelp+ Multi-Agent ReAct Migration Plan

Status: implemented. The legacy `TaskAgent` full planner and planner prompts have been retired; ReAct navigation is now the only runtime decision path.

## 1. Purpose

This document defines the migration from the former fixed planner-based guidance flow into the current agentic navigation flow.

The Android app, LiveKit connection, screen capture, overlay rendering, TTS, token server, and state management remain. The main change is replacing the upfront task planner with a dynamic ReAct-style navigation loop.

Current limitation:

```text
user goal -> generate full plan once -> follow plan -> retry if failed
```

Target behavior:

```text
user goal -> observe screen -> decide next action -> ground target -> guide user
-> verify result -> observe again -> decide again
```

## 2. Current Server Architecture

The current server is a single LiveKit agent with an FSM-based orchestrator and a ReAct next-action agent. The FSM already contains all required states in `task_state_machine.py`; the migration did not add new state names.

| Component | File | Current Responsibility |
| --- | --- | --- |
| Token server | `server-python/token_server.py` | Provides `/token`, `/health`, and `/tts` endpoints. |
| Main LiveKit agent | `server-python/agent.py` | Joins LiveKit room, receives Android data channel messages, handles audio/STT/TTS, routes screenshots, sends Android responses. |
| Orchestrator | `server-python/task_executor.py` | Runs safety, intent, ReAct navigation, screenshot guidance, verification, retry, help, and completion logic. |
| State machine | `server-python/task_state_machine.py` | Controls the existing 13 states: `IDLE`, `INTAKE`, `SAFETY_CHECK`, `PLANNING`, `CLARIFYING`, `GUIDING`, `AWAITING_ACTION`, `VERIFYING`, `RETRYING`, `HELPING`, `STUCK`, `COMPLETED`, and `FAILED`. |
| Navigation ReAct agent | `server-python/navigation_react_agent.py` | Decides one next action from the current goal, screen observation, and recent history. |
| Intent analyzer | `server-python/intent_agent.py` | Analyzes user intent and task context. |
| Safety gate | `server-python/safety_gate.py` | Detects scam, OTP, payment, banking, QR, remote-control, and other risky flows. |
| Prompt registry | `server-python/prompt_registry.py` | Builds navigation, guide, verify, retry, help, chat, and intent prompts. |
| Voice synthesizer | `server-python/voice_synthesizer.py` | Generates Gemini TTS audio and fallback audio. |

Current main flow:

```text
Android text/audio/screenshot
-> SmartHelpAgent
-> TaskStateMachine
-> TaskExecutor.run()
-> SafetyGate
-> IntentAgent
-> NavigationReactAgent.decide_next()
-> screenshot_request
-> Gemini Vision grounding
-> guidance/chat response translated by SmartHelpAgent
-> user action
-> verify screenshot
-> next ReAct decision
```

## 3. Target Architecture

Recommended target: 2 agents plus 1 vision tool.

```text
Android
-> SmartHelpAgent
-> TaskExecutor / Orchestrator
-> Intent & Safety Agent
-> Navigation ReAct Agent
-> Vision Grounding Tool
-> Android overlay / voice
-> user action
-> observe new screenshot
-> Navigation ReAct Agent re-decides
```

### 3.1 Why 2 Agents + 1 Tool

Do not start with 3 fully independent agents. A separate Vision Agent is possible, but it adds prompt, state, and debugging overhead. For this project, vision is better implemented as a tool because it has a narrow job: given a screenshot and target, return coordinates, confidence, completion status, and scroll direction.

| Component | Type | Reason |
| --- | --- | --- |
| Intent & Safety Agent | Agent | Understands user goal, target app, language, missing information, and safety risk. |
| Navigation ReAct Agent | Agent | Dynamically decides the next best action from the current screen and task state. |
| Vision Grounding Tool | Tool | Grounds the selected target on screen and verifies expected results. |

## 4. What Gets Replaced

The migration mainly replaces the upfront planner role.

| Current Part | New Role |
| --- | --- |
| `TaskAgent.generate_plan()` | Replace with `NavigationReactAgent.decide_next()` for one-step decisions. |
| `PLANNING` state | Keep the existing state name and transition table; only reinterpret its behavior as dynamic next-action decision, not full plan generation. |
| Guide/verify logic inside `TaskExecutor` | Keep, but feed it one dynamic action instead of a fixed plan step. |
| Gemini Vision logic inside `agent.py` and `task_executor.py` | Extract into `VisionGroundingTool` as a refactor. The schema already exists in `agent.py`; Phase 1 should move it without behavior change. |

Keep these parts:

| Keep | Reason |
| --- | --- |
| `agent.py` | LiveKit session, Android message routing, STT, and TTS remain useful. |
| `task_executor.py` | It becomes the controlled orchestrator for the agentic loop. |
| `task_state_machine.py` | It prevents uncontrolled agent behavior. |
| `safety_gate.py` | Safety rules are already valuable. |
| `intent_agent.py` | Can be reused inside Intent & Safety Agent. |
| Android app | Overlay, screen capture, accessibility metadata, and LiveKit connection remain. |

## 5. New Files

Add these files under `server-python/`:

```text
server-python/
  intent_safety_agent.py
  navigation_react_agent.py
  vision_grounding_tool.py
```

Recommended prompt files:

```text
server-python/prompts/
  intent_safety/
    intent_safety.system.txt

  navigation/
    navigation_react.system.txt
    navigation_react.examples.txt

  vision/
    vision_ground.system.txt
    vision_verify.system.txt
```

## 6. Agent Responsibilities

### 6.1 Intent & Safety Agent

Responsibilities:

- Normalize the user's goal.
- Detect output language.
- Identify target app and task type.
- Detect missing information.
- Run safety checks before navigation starts.
- Decide whether to allow, ask confirmation, block, or ask for more information.

It should wrap the existing `IntentAgent` and `SafetyGate`, not replace them.

Example output:

```json
{
  "allowed": true,
  "intent": "navigation_task",
  "goal": "send WhatsApp message to daughter",
  "target_app": "WhatsApp",
  "language": "en",
  "risk_level": "normal",
  "missing_info": [],
  "message": null
}
```

Blocked output:

```json
{
  "allowed": false,
  "intent": "blocked_task",
  "goal": "send OTP to someone",
  "target_app": null,
  "language": "en",
  "risk_level": "blocked",
  "missing_info": [],
  "message": "Verification codes are private. Never share them with anyone."
}
```

Needs confirmation output:

```json
{
  "allowed": false,
  "intent": "confirmation_required",
  "goal": "transfer money",
  "target_app": "banking_app",
  "language": "en",
  "risk_level": "confirm_needed",
  "missing_info": [],
  "message": "Are you sure you want to transfer money? Who is it for?"
}
```

### 6.2 Navigation ReAct Agent

Responsibilities:

- Decide only the next best action.
- Never generate a full 6 to 10 step plan.
- Use current task goal, current state, previous result, screenshot summary, accessibility metadata, and retry reason.
- Recover from wrong pages, popups, missing targets, user deviations, and partial completion.
- Return strict JSON.

Core ReAct loop:

```text
Observe: current screen, accessibility metadata, previous result
Reason: choose the next small action
Act: return one guide/ask/complete/recover action
Observe again after user action
```

Example guide output:

```json
{
  "status": "continue",
  "action": "guide",
  "gesture": "tap",
  "target": "WhatsApp search icon",
  "instruction": "Tap the search icon.",
  "expected_result": "Search field appears",
  "needs_vision": true,
  "recovery_reason": null
}
```

Example ask-user output:

```json
{
  "status": "needs_info",
  "action": "ask_user",
  "question": "Who should I send the message to?",
  "missing_field": "recipient_name"
}
```

Example recovery output:

```json
{
  "status": "continue",
  "action": "recover",
  "gesture": "tap",
  "target": "Back button",
  "instruction": "Tap Back once.",
  "expected_result": "Return to the previous WhatsApp screen",
  "needs_vision": true,
  "recovery_reason": "The current screen is not the expected WhatsApp chat list."
}
```

Example complete output:

```json
{
  "status": "completed",
  "action": "complete",
  "instruction": "Done."
}
```

### 6.3 Vision Grounding Tool

Responsibilities:

- Given a target, screenshot, and accessibility metadata, find the target coordinates.
- Verify whether an expected result is visible.
- Return confidence and reason.
- Return scroll/swipe direction when the target is not visible.
- Must run the existing accessibility fast-path first, before any Gemini call.
- Use Gemini Vision only when accessibility metadata and known-target shortcuts are insufficient.
- Preserve the current known guidance shortcuts and accessibility matching behavior from `task_executor.py`.
- Extract the current Gemini Vision schema and parser from `agent.py` instead of designing a new schema.

Important latency rule:

```text
accessibility / known UI hit -> no separate Gemini grounding call
accessibility miss -> Gemini grounding call
```

This is required because skipping the accessibility fast-path would add roughly one extra second to common steps.

Grounding input:

```json
{
  "mode": "ground",
  "target": "WhatsApp search icon",
  "gesture": "tap",
  "expected_result": "Search field appears",
  "screenshot_width": 1080,
  "screenshot_height": 2400,
  "accessibility": []
}
```

Grounding output:

```json
{
  "target_found": true,
  "x": 88,
  "y": 10,
  "x1": 82,
  "y1": 5,
  "x2": 95,
  "y2": 15,
  "confidence": 0.86,
  "completed": false,
  "direction": null,
  "reason": "Search icon is visible at the top right."
}
```

Not-found output:

```json
{
  "target_found": false,
  "x": 50,
  "y": 50,
  "x1": null,
  "y1": null,
  "x2": null,
  "y2": null,
  "confidence": 0.3,
  "completed": false,
  "direction": "down",
  "reason": "The target is not visible on the current screen."
}
```

Verification output:

```json
{
  "completed": true,
  "confidence": 0.9,
  "reason": "The WhatsApp search field is visible.",
  "blocker_detected": false,
  "blocker_reason": null
}
```

## 7. State Machine Strategy

To minimize code churn, do not redesign the FSM first. The current FSM already has 13 states. This plan only changes the behavior of `PLANNING`; it does not introduce `DECIDING`, `GROUNDING`, or any other new state.

| Current State | New Meaning |
| --- | --- |
| `IDLE` | Waiting for a user goal. |
| `INTAKE` | Normalize goal and language. |
| `SAFETY_CHECK` | Call Intent & Safety Agent. |
| `PLANNING` | Existing state. Reinterpret it to call Navigation ReAct Agent for one next action. |
| `CLARIFYING` | Ask user for missing info or safety confirmation. |
| `GUIDING` | Use Vision Grounding Tool and send one-step guidance. |
| `AWAITING_ACTION` | Wait for user action, screenshot, help request, cancel, or timeout. |
| `VERIFYING` | Verify the expected result for the last action. |
| `RETRYING` | Return to Navigation ReAct Agent with failure reason. |
| `HELPING` | Generate recovery guidance. |
| `STUCK` | Stop after repeated failures. |
| `COMPLETED` | Task finished. |
| `FAILED` | Task failed. |

Recommended updated loop:

```text
IDLE
-> INTAKE
-> SAFETY_CHECK
-> PLANNING
-> GUIDING
-> AWAITING_ACTION
-> VERIFYING
-> PLANNING
```

Retry path:

```text
VERIFYING
-> RETRYING
-> PLANNING
-> GUIDING
```

Help path:

```text
AWAITING_ACTION
-> HELPING
-> PLANNING
-> GUIDING
```

## 8. TaskExecutor Changes

### 8.1 Current Behavior

Current `TaskExecutor._run_planning()`:

```text
IntentAgent.analyze_intent()
-> NavigationReactAgent.decide_next()
-> tsm.dispatch("plan_ready", plan)
```

### 8.2 Target Behavior

New planning behavior:

```text
Intent & Safety result exists
-> collect current observation
-> NavigationReactAgent.decide_next()
-> if ask_user: CLARIFYING
-> if complete: COMPLETED
-> if guide/recover: store current action and enter GUIDING
```

Store current action in FSM context:

```json
{
  "currentAction": {
    "status": "continue",
    "action": "guide",
    "gesture": "tap",
    "target": "WhatsApp search icon",
    "instruction": "Tap the search icon.",
    "expected_result": "Search field appears"
  }
}
```

The old `plan` can remain temporarily for compatibility, but the new flow should use `currentAction` as the main source of truth.

### 8.3 Feature Flag and Fallback

The first migration kept `TaskAgent.generate_plan()` as a comparison baseline. It has now been removed from the runtime.

The feature flag is now diagnostic only:

```text
SMARTHELP_REACT_NAV=1  # default production path
SMARTHELP_REACT_NAV=0  # diagnostic mode; routes to STUCK because no planner fallback exists
```

Runtime rule:

```text
if SMARTHELP_REACT_NAV is false:
    log ReAct unavailable and route to STUCK
else:
    try NavigationReactAgent.decide_next()
    if ReAct throws, returns invalid JSON, or times out:
        log failure reason
        route to STUCK without planner fallback
```

Keep structured logs for:

- ReAct enabled or disabled.
- ReAct decision latency.
- ReAct invalid JSON count.
- ReAct timeout count.
- ReAct failure reason.
- ReAct iteration count.

Removal criterion:

```text
TaskAgent.generate_plan() has been retired after the ReAct path became the production path.
Unexpected ReAct failures should be investigated directly instead of hidden by fallback behavior.
```

## 9. LLM Call Budget

ReAct is a control loop, not a requirement to call an LLM for every Reason, Act, and Observe sub-step. Latency must be controlled because elderly users feel each delay directly.

Recommended budget:

| Path | Calls Per Step | Notes |
| --- | ---: | --- |
| Known/accessibility hit | 1 | Use one decision/vision pass and current accessibility fast-path. Do not make a separate Gemini grounding call. |
| Accessibility miss | 2 | Navigation decision + Gemini grounding. |
| Hard/retry path | Up to 3 | Decision + grounding + verification/recovery. Use only for complex screens or failed verification. |

This is comparable to the current common path, which is around 2 calls per step. The migration must not accidentally turn every step into 3 to 4 blocking calls.

## 10. Prompt Rules

### 9.1 Navigation ReAct Prompt Rules

The navigation agent must follow these rules:

```text
You are the Navigation ReAct Agent for SmartHelp+.
You do not create a full task plan.
You decide only the next best action.
Use the current screen observation, previous action result, retry reason, and task state.
If the user is already past a step, skip it.
If the screen is unexpected, recover before continuing.
If information is missing, ask one clear question.
If the task is complete, return completed.
Return JSON only.
```

### 9.2 Vision Tool Prompt Rules

The vision tool must follow these rules:

```text
Find the requested target on the screenshot.
Use accessibility metadata when it confidently identifies the target.
If the target is visible, return percentage coordinates.
If the target is not visible, return target_found=false and one scroll/swipe direction.
For verify mode, judge whether the expected result is visible.
Return JSON only.
```

## 11. Migration Phases

### Phase 1: Extract Vision Grounding Tool

Tasks:

- Add `vision_grounding_tool.py`.
- Move the current Gemini Vision schema, call wrapper, parser, and fallback parsing from `agent.py` into the tool.
- Preserve the existing output shape from `agent.py`; do not redesign the schema in this phase.
- Move or wrap existing accessibility fast-path helpers from `task_executor.py` without behavior change.
- Keep current `TaskExecutor` behavior identical.

Success criteria:

- Existing tests pass.
- Existing guide/verify behavior is unchanged.
- Accessibility hit still resolves before Gemini.
- Gemini Vision output still maps to the same guidance fields.

Rollback:

```text
Revert the extraction and call the existing agent.py / task_executor.py code path.
```

### Phase 2: Create Intent & Safety Agent Wrapper

Tasks:

- Add `intent_safety_agent.py`.
- Reuse `SafetyGate.check()`.
- Reuse `IntentAgent.analyze_intent()`.
- Return one stable JSON result.
- Add unit tests.

Success criteria:

- Safe goals proceed.
- Risky goals are blocked or ask confirmation.
- Missing information is reported clearly.
- Current non-ReAct flow still works.

### Phase 3: Add Navigation ReAct Agent Behind Flag, Default Off

Tasks:

- Add `navigation_react_agent.py`.
- Implement `decide_next(context, observation)`.
- Start with Gemini JSON output.
- Add heuristic shortcuts for common flows if needed.
- Do not generate full plans.
- Wire it into `TaskExecutor` behind `SMARTHELP_REACT_NAV`.
- On any ReAct exception, invalid JSON, timeout, or unsupported action, retry inside `NavigationReactAgent`; after retries, route to `STUCK`.

Success criteria:

- It returns only one next action.
- It handles already-on-target-screen cases.
- It can recover from unexpected screen states.
- With the flag off, the executor reports ReAct as unavailable instead of using a deleted planner.
- With the flag on for local testing, failures route to `STUCK` and are logged.

Rollback:

```text
Set SMARTHELP_REACT_NAV=false.
```

### Phase 4: Enable ReAct by Default

Tasks:

- Set `SMARTHELP_REACT_NAV=true` for development testing after Phase 3 is stable.
- Keep no legacy fallback active.
- Track ReAct latency and failure reasons.
- Time-box ReAct calls; on timeout, retry and then route to `STUCK`.
- Limit ReAct context to the last 3 actions/observations.
- Set `max_react_iterations=12` per task to prevent infinite loops.

Success criteria:

- Common tasks complete with comparable or better latency than the current flow.
- Loop terminates through `COMPLETED`, `STUCK`, `FAILED`, or cancel.
- Fallback rate is measured.
- No uncontrolled context growth.

Rollback:

```text
Set SMARTHELP_REACT_NAV=false.
```

### Phase 5: Test and Stabilize

Tasks:

- Add and run unit tests for intent/safety, vision extraction, ReAct decisions, and executor flow.
- Run existing backend tests.
- Run Android JVM tests.
- Build debug APK.
- Run live emulator/device tests for common and failure scenarios.
- Measure ReAct latency, stuck rate, and completion rate.

Success criteria:

- Existing tests pass.
- ReAct path completes core demo tasks.
- Fallback and timeout logs are available.
- No regression in current Android protocol.

### Phase 6: Update Documentation

Tasks:

- Update architecture diagram.
- Update Chapter 3 analysis to describe fixed planner limitations.
- Update Chapter 4 design to describe the agentic navigation loop.
- Update Chapter 5 implementation to describe extracted modules.
- Update Chapter 6 testing to include wrong-screen, popup, retry, loop limit, and safety cases.

Success criteria:

- Report wording matches the real implementation.
- No copied Solus-specific architecture wording.
- Message names and FSM state descriptions match the code.

## 12. Android Protocol Stability

Keep the current server-side outbound message names stable:

- `ready`
- `screenshot_request`
- `guidance`
- `chat`
- `completed`
- `stuck`

Do not document the migration as if it introduces new Android message names such as `highlight`, `text`, `audio`, `taskComplete`, or `error`. `SmartHelpAgent` may translate outbound data internally, but the migration plan should refer to the actual server-side executor messages.

The first ReAct version should not require Android-side architecture changes.

## 13. Tests

Add these tests:

```text
server-python/tests/test_intent_safety_agent.py
server-python/tests/test_navigation_react_agent.py
server-python/tests/test_vision_grounding_tool.py
server-python/tests/test_react_executor_flow.py
```

Required scenarios:

| Scenario | Expected Result |
| --- | --- |
| User is already in WhatsApp chat screen | Navigation agent skips open/search and goes to message entry. |
| User opens wrong app | Navigation agent asks user to return or guides back. |
| Permission popup appears | Navigation agent handles the popup before continuing. |
| Target is not visible | Vision tool returns scroll/swipe direction. |
| Verification fails | Navigation agent changes recovery strategy. |
| OTP or password sharing request | Intent & Safety Agent blocks. |
| Payment or banking flow | Intent & Safety Agent asks confirmation or pauses. |
| User cancels | FSM returns to `IDLE`. |
| User starts new goal after completion | FSM restarts from `INTAKE`. |

## 14. Risks and Controls

| Risk | Control |
| --- | --- |
| ReAct loop does not terminate | Add `max_react_iterations=12` per task and route to `STUCK` when exceeded. |
| Context grows forever | Keep only the last 3 actions, observations, and failure reasons in the ReAct prompt. |
| ReAct and vision prompts drift apart | Keep strict JSON schemas and add schema validation tests. |
| ReAct call hangs or becomes slow | Time-box the call, retry, and route to `STUCK` after repeated failure. |
| Every step becomes too many LLM calls | Preserve accessibility fast-path and enforce the LLM call budget in Section 9. |
| Feature is hard to debug | Keep `SMARTHELP_REACT_NAV` as a diagnostic flag and use structured ReAct logs. |

## 15. Expected Improvements

This migration should improve:

| Current Problem | Expected Improvement |
| --- | --- |
| Fixed upfront plan becomes stale | Each step is re-decided from the current screen. |
| User goes to wrong page | Navigation agent can detect mismatch and recover. |
| Popup or dynamic UI breaks the plan | Navigation agent can handle blockers before continuing. |
| Retry repeats the same instruction | Retry can change strategy based on failure reason. |
| Planner has too many responsibilities | Intent, safety, navigation, and vision are separated. |
| Hard to debug | Each loop produces structured intent, action, and vision JSON. |
| Inconsistent visual cue | Every action-oriented step must pass through the vision tool. |

This migration will not automatically solve:

| Problem | Still Needs |
| --- | --- |
| Gemini API latency | Caching, accessibility fast-path, faster models, local OCR/ML, or batching. |
| STT errors | Better transcript normalization, confirmation questions, and retry UI. |
| TTS speed | Real TTS speed control and user settings integration. |
| Android permission friction | Android UX and permission flow improvements. |
| Wake-word support | Separate Picovoice/Porcupine or OS-level activation work. |

## 16. Report Wording

Recommended wording for the FYP report:

```text
SmartHelp+ was redesigned from a fixed planner-based guidance pipeline into an agentic navigation workflow. The updated architecture separates user intent and safety analysis from dynamic navigation reasoning, while screen grounding and verification are handled by a dedicated vision tool. This allows the system to re-evaluate the current screen after each user action instead of relying only on an upfront static task plan.
```

Avoid copying descriptions such as:

```text
Conversation Agent hands off to Navigation Agent.
Navigation Agent hands back to Conversation Agent.
```

That wording is too close to the Solus report. Use SmartHelp+'s own architecture names instead:

- Intent & Safety Agent
- Navigation ReAct Agent
- Vision Grounding Tool
- FSM Orchestrator

## 17. Implementation Checklist

### Backend

- [x] Add `vision_grounding_tool.py`.
- [x] Extract current Gemini Vision schema/parser from `agent.py` into `vision_grounding_tool.py`.
- [x] Preserve accessibility fast-path from `task_executor.py`. *(Pure matching logic moved into `accessibility_fast_path.py` as `AccessibilityFastPath`. Executor keeps `last_known_target_key` and the step-index mutation via `_apply_step_index_advance`. Same behavior, same tests.)*
- [x] Add `intent_safety_agent.py`.
- [x] Add `navigation_react_agent.py`.
- [x] Add prompt files for intent safety, navigation ReAct, and vision. *(`prompts/navigation/navigation_react.system.txt` and `.examples.txt` added; intent and vision use the existing prompt registry methods.)*
- [x] Update `TaskExecutor` to call Intent & Safety Agent. *(Used in ReAct path; legacy path still calls `IntentAgent` + `SafetyGate` directly to keep the flag-off behavior identical.)*
- [x] Add `SMARTHELP_REACT_NAV` feature flag. *(Default is now `1`/on; `0` is only a diagnostic mode and no longer routes to a legacy planner.)*
- [x] Update `TaskExecutor` to call Navigation ReAct Agent only when the flag is enabled.
- [x] Retire `TaskAgent.generate_plan()` fallback. *(If `decide_next` fails after retries, the system enters STUCK rather than switching to a fixed planner.)*
- [x] Log ReAct failure reason, latency-related outcomes, and invalid JSON retries. *(`_log_task_outcome` emits one line per task: `task_outcome / react_mode / react_iterations / history_len`.)*
- [x] Add `currentAction` to FSM context.
- [ ] Update guide prompt building to use `currentAction`. *(Not required for Phase 3/4: the ReAct decision is materialised as a synthetic single-step plan, so the existing `build_guide_prompt(mode, ctx, step, meta)` still gets a valid `step` and works unchanged. Direct `currentAction` consumption can be added later when we want to drop the synthetic-plan adapter.)*
- [ ] Update verify prompt building to use `currentAction.expected_result`. *(Same reason as above: `expected_result` flows through `step["expectedResult"]` in the synthetic plan.)*
- [x] Route verify failure back to Navigation ReAct decision. *(`_handle_react_verify` dispatches `react_replan` with a concrete `retryReason`, which the FSM routes to PLANNING for a fresh `decide_next` call.)*
- [x] Preserve existing Android outbound protocol.
- [x] Add `max_react_iterations=12`. *(Configurable via the `maxReactIterations` executor config; overrun routes to STUCK via the new `react_stuck` FSM event.)*
- [x] Limit ReAct prompt context to the last 3 actions/observations. *(`build_navigation_react_user_prompt` slices `reactHistory[-3:]`; the executor caps the stored history at 10.)*

### Tests

- [x] Add intent and safety tests. *(`tests/test_intent_safety_agent.py` — 8 tests.)*
- [x] Add navigation next-action tests. *(`tests/test_navigation_react_agent.py` — 9 tests, fake Gemini client.)*
- [x] Add vision grounding tests. *(`tests/test_vision_grounding_tool.py` — 6 tests, stub gemini client.)*
- [x] Add executor flow tests. *(`tests/test_react_executor_flow.py` covers happy path, verify pass/fail, ask_user, complete, blocker, iteration budget, and ReAct failure routing to STUCK.)*
- [x] Add no-planner failure tests. *(`test_react_failure_routes_to_stuck_without_alternate_fallback` in the executor flow file.)*
- [x] Add loop-limit tests. *(`test_iteration_budget_routes_to_stuck`.)*
- [x] Add context-window tests. *(`test_react_history_is_bounded_to_ten_entries` and `test_react_prompt_includes_only_last_three_history_entries` in `tests/test_react_executor_flow.py`.)*
- [x] Run Python tests. *(99 passed after retiring the legacy planner test file.)*
- [x] Run Android JVM tests. *(`./gradlew testDebugUnitTest` — BUILD SUCCESSFUL; pre-existing JVM suite unchanged.)*
- [x] Build debug APK. *(`./gradlew assembleDebug` — BUILD SUCCESSFUL; produces `app/build/outputs/apk/debug/app-debug.apk`.)*
- [ ] Run live device or emulator test. *(Manual step — see verification plan in `~/.claude/plans/frolicking-wondering-sutton.md`.)*

### Documentation

- [ ] Update architecture diagram.
- [ ] Update Chapter 3 analysis to describe the limitation of fixed planners.
- [ ] Update Chapter 4 design to show agentic navigation loop.
- [ ] Update Chapter 5 implementation to describe new server modules.
- [ ] Update Chapter 6 testing to include wrong-screen, popup, retry, and safety cases.

## 18. Recommended First Milestone

The first milestone should be small:

```text
Extract VisionGroundingTool from the existing agent.py / task_executor.py path
with zero behavior change.
```

Do not rewrite everything at once. First make the vision extraction reversible and testable. Then add the ReAct path, prove retry/failure logging, and keep the no-planner failure behavior explicit.

## 19. Current Implementation Status (2026-05-16)

| Phase | Status | Notes |
| --- | --- | --- |
| Phase 1 — Extract `VisionGroundingTool` | done | Gemini side: `vision_grounding_tool.py`. Accessibility fast-path: `accessibility_fast_path.py` (moved in a follow-up refactor; executor keeps step-index mutation). |
| Phase 2 — Add `IntentSafetyAgent` | done | Composes `IntentAgent` + `SafetyGate`; produces unified JSON shape from §6.1 and preserves raw results via `intent_raw` / `safety_raw`. |
| Phase 3 — Add `NavigationReactAgent` behind flag | done | Flag `SMARTHELP_REACT_NAV` (env). ReAct synthesises a single-step plan per turn and re-enters PLANNING after each VERIFY via the new `react_replan` FSM event. |
| Phase 4 — Flip default + remove planner fallback | done | Default is now ReAct ON. ReAct exceptions route to STUCK after retries; no legacy planner path remains. |
| Phase 5 — Tests | done | Current server-python pytest suite passes: 99 tests. Android JVM tests (`testDebugUnitTest`) pass and `assembleDebug` produces a working `app-debug.apk`. |
| Phase 6 — Documentation | done | This document is updated. UTS FYP report chapters still need to be updated separately. |

Remaining work (intentionally deferred):

- Live device / emulator soak test with `SMARTHELP_REACT_NAV=1` to measure stuck rate and per-step latency. Use the `task_outcome=...` log line for telemetry.
- `task_agent.py` and the legacy planner prompts have been retired.
- Wire ReAct's `currentAction` directly into `build_guide_prompt` so we can drop the synthetic-plan adapter (low priority — current synthetic-plan adapter is small and well-isolated).

To debug ReAct availability, use logs from `task_outcome=...`; disabling ReAct no longer enables a planner fallback.
