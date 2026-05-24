"""
TaskExecutor — Translated from TaskExecutor.js
Orchestrates task guidance: planning, screenshot processing, guidance, verification.
Sends messages to the Android client via LiveKit Data Channel.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from typing import Any, Callable, Optional

logger = logging.getLogger("smarthelp.executor")

from task_state_machine import TaskStateMachine
from intent_agent import IntentAgent
from safety_gate import SafetyGate
from prompt_registry import PromptRegistry
from intent_safety_agent import IntentSafetyAgent
from navigation_react_agent import NavigationReactAgent, MAX_REACT_ITERATIONS
from accessibility_fast_path import AccessibilityFastPath


def _react_mode_enabled() -> bool:
    # ReAct is the production navigation path. The flag remains only as an
    # internal test/diagnostic escape hatch; there is no alternate navigation fallback.
    value = os.environ.get("SMARTHELP_REACT_NAV", "1").strip().lower()
    return value in ("1", "true", "yes", "on")


class TaskExecutor:
    """Drives the task guidance loop, interacting with Gemini and the Android client."""

    def __init__(self, tsm: TaskStateMachine, *, config: dict | None = None):
        config = config or {}
        self.tsm = tsm
        self.safety_gate = config.get("safetyGate") or SafetyGate()
        self.intent_agent = config.get("intentAgent") or IntentAgent(model=config.get("intentModel"))
        self.prompt_registry = config.get("promptRegistry") or PromptRegistry()

        self.react_mode: bool = config.get("reactMode") if config.get("reactMode") is not None else _react_mode_enabled()
        self.intent_safety_agent: IntentSafetyAgent | None = (
            config.get("intentSafetyAgent")
            or (IntentSafetyAgent(intent_agent=self.intent_agent, safety_gate=self.safety_gate) if self.react_mode else None)
        )
        self.navigation_react_agent: NavigationReactAgent | None = (
            config.get("navigationReactAgent")
            or (NavigationReactAgent(prompt_registry=self.prompt_registry) if self.react_mode else None)
        )
        self.max_react_iterations: int = int(config.get("maxReactIterations", MAX_REACT_ITERATIONS))

        self.on_output: Callable[[dict], None] | None = None
        self.on_send_image_to_gemini: Callable[[str, str, str | None], None] | None = None  # (base64jpeg, prompt, mode) -> None

        self.gemini_ready = False
        self.pending_mode: str | None = None
        self.pending_screenshot: dict | None = None
        self.active_guide_request: dict | None = None
        self.guide_timeout_ms: int = config.get("guideTimeoutMs", 25000)
        self._guide_timeout_task: asyncio.Task | None = None
        self.last_known_target_key: str | None = None
        # Tracks the last fast-path emission keyed as "<rule_key>::<step_index>".
        # Used to suppress duplicate TTS when the same accessibility match keeps
        # firing on consecutive screenshots for the same step (e.g. user hovers
        # on the WhatsApp attachment sheet without tapping anything).
        self._last_fast_path_dedup_key: str | None = None
        self.fast_path: AccessibilityFastPath = config.get("fastPath") or AccessibilityFastPath()

        logger.info("TaskExecutor init: react_mode=%s", self.react_mode)

    def set_gemini_ready(self, ready: bool) -> None:
        self.gemini_ready = ready

    async def on_gemini_ready(self) -> None:
        self.gemini_ready = True
        if self.pending_mode and self.pending_screenshot:
            await self._process_screenshot(self.pending_screenshot)

    def on_gemini_disconnected(self) -> None:
        self.gemini_ready = False
        if self.active_guide_request:
            self.pending_mode = self.active_guide_request.get("mode")
            self.pending_screenshot = self.active_guide_request.get("screenshot")
            self.active_guide_request = None
        self._clear_guide_timeout()

    async def run(self) -> None:
        state = self.tsm.get_state()
        ctx = self.tsm.get_context()

        if state == "INTAKE":
            self.tsm.dispatch("language_detected")
            await self.run()
        elif state == "SAFETY_CHECK":
            await self._run_safety_check(ctx)
        elif state == "PLANNING":
            await self._run_planning(ctx)
        elif state == "CLARIFYING":
            if ctx.get("safetyResult") == "blocked":
                self._emit_chat(ctx.get("pendingQuestion"), ctx.get("outputLanguage"), False)
                self.tsm.dispatch("user_cancelled")
                return
            self._emit_chat(ctx.get("pendingQuestion"), ctx.get("outputLanguage"))
        elif state == "GUIDING":
            await self._prepare_guidance("NAVIGATE")
        elif state == "VERIFYING":
            await self._prepare_guidance("VERIFY")
        elif state == "RETRYING":
            await self._prepare_guidance("RETRY")
        elif state == "HELPING":
            await self._prepare_guidance("HELP")
        elif state == "COMPLETED":
            self._clear_guide_state()
            self._emit_completed(ctx)
        elif state == "STUCK":
            self._clear_guide_state()
            self._emit_stuck(ctx.get("outputLanguage"))
        elif state == "FAILED":
            self._clear_guide_state()
            self._emit_failed(ctx.get("failReason"), ctx.get("outputLanguage"))
        elif state == "IDLE":
            self._clear_guide_state()
            if ctx.get("previousState") and ctx["previousState"] != "IDLE":
                self._emit_chat(self._get_idle_message(ctx.get("outputLanguage")), ctx.get("outputLanguage"), True)

    # ── Safety & Planning ──────────────────────────────────────────────────

    async def _run_safety_check(self, ctx: dict) -> None:
        result = self.safety_gate.check(ctx.get("rawGoal"), ctx.get("outputLanguage"))
        if result["safe"]:
            self.tsm.dispatch("safety_pass", result)
        elif result.get("needsConfirm"):
            self.tsm.dispatch("safety_confirm_needed", result)
        else:
            self.tsm.dispatch("safety_blocked", result)
        await self.run()

    async def _run_planning(self, ctx: dict) -> None:
        self.last_known_target_key = None
        if not self.react_mode or self.navigation_react_agent is None:
            logger.error("ReAct navigation is unavailable; no alternate fallback is configured")
            self.tsm.dispatch("react_stuck", {"reason": "ReAct navigation is unavailable"})
            await self.run()
            return
        try:
            await self._run_react_planning(ctx)
            return
        except Exception as err:
            logger.error("ReAct navigation failed; routing to STUCK without alternate fallback: %s", err)
            self.tsm.dispatch(
                "react_stuck",
                {"reason": "ReAct navigation could not decide the next action"},
            )
        await self.run()

    async def _run_react_planning(self, ctx: dict) -> None:
        """ReAct mode planning: one Gemini call decides the next single action."""
        fsm_ctx = self.tsm.ctx

        if fsm_ctx.get("intentSafetyResult") is None and self.intent_safety_agent is not None:
            unified = await self.intent_safety_agent.analyze(
                ctx.get("rawGoal"), language=ctx.get("outputLanguage")
            )
            fsm_ctx["intentSafetyResult"] = unified
            fsm_ctx["intentResult"] = unified.get("intent_raw")
            if unified.get("allowed") is False and unified.get("intent") == "blocked_task":
                fsm_ctx["safetyResult"] = "blocked"
                fsm_ctx["safetyRuleId"] = (unified.get("safety_raw") or {}).get("ruleId")
                self.tsm.dispatch(
                    "react_ask_user",
                    {
                        "question": unified.get("message"),
                        "missing_field": "safety_blocked",
                    },
                )
                await self.run()
                return

        iterations = int(fsm_ctx.get("reactIterations") or 0)
        if iterations >= self.max_react_iterations:
            logger.warning(
                "ReAct iteration budget exceeded (%d); routing to STUCK",
                iterations,
            )
            self.tsm.dispatch("react_stuck", {"reason": "ReAct iteration budget exceeded"})
            await self.run()
            return

        observation = fsm_ctx.get("lastObservation") or {}
        decision = await self.navigation_react_agent.decide_next(self.tsm.get_context(), observation)
        fsm_ctx["reactIterations"] = iterations + 1
        fsm_ctx["currentAction"] = decision

        action = decision.get("action")
        if action == "complete":
            self.tsm.dispatch("react_complete")
        elif action == "ask_user":
            self.tsm.dispatch(
                "react_ask_user",
                {
                    "question": decision.get("question"),
                    "missing_field": decision.get("missing_field"),
                },
            )
        else:  # guide / recover
            plan = self._react_decision_to_plan(decision, ctx.get("rawGoal"))
            self.tsm.dispatch("plan_ready", plan)
        await self.run()

    @staticmethod
    def _react_decision_to_plan(decision: dict, raw_goal: str | None) -> dict:
        """Translate one ReAct decision into the single-step plan shape the FSM expects."""
        step = {
            "instruction": decision.get("instruction") or "Follow the highlighted target.",
            "action": (decision.get("gesture") or "tap").lower(),
            "target": decision.get("target") or "",
            "matchHints": decision.get("match_hints") or [],
            "avoidHints": decision.get("avoid_hints") or [],
            "expectedResult": decision.get("expected_result") or "",
            "reactAction": decision.get("action"),  # "guide" or "recover"
        }
        if decision.get("recovery_reason"):
            step["recoveryReason"] = decision["recovery_reason"]
        return {
            "task": raw_goal,
            "needsUserInput": False,
            "missingFields": [],
            "steps": [step],
        }

    # ── Guidance ───────────────────────────────────────────────────────────

    async def _prepare_guidance(self, mode: str) -> None:
        if self.active_guide_request:
            return
        if self.pending_mode == mode and not self.pending_screenshot:
            return

        self.pending_mode = mode
        if self.pending_screenshot:
            if self.gemini_ready:
                await self._process_screenshot(self.pending_screenshot)
            return
        self._emit({"type": "screenshot_request"})

    async def on_screenshot(self, image: str, width: int | None, height: int | None, meta: dict | None = None) -> None:
        meta = meta or {}
        self.pending_screenshot = {"image": image, "width": width, "height": height, "meta": meta}

        # Android's accessibility service fires a verify screenshot ~900ms
        # after the user taps. If we are still waiting in AWAITING_ACTION and
        # haven't asked for a screenshot ourselves, treat the unsolicited
        # screenshot as proof that the user acted and jump straight to VERIFY
        # — otherwise the FSM would wait the full AWAITING timeout (15s) and
        # then process the screenshot in HELP mode, which costs the user a
        # whole step of latency.
        if not self.pending_mode and self.tsm.get_state() == "AWAITING_ACTION":
            logger.info("Unsolicited screenshot in AWAITING_ACTION; treating as user_action_detected")
            self.tsm.dispatch("user_action_detected")
            await self.run()

        if not self.pending_mode or not self.gemini_ready:
            return
        await self._process_screenshot(self.pending_screenshot)

    async def _process_screenshot(self, packet: dict) -> None:
        try:
            mode = self.pending_mode
            if not mode:
                return

            ctx = self.tsm.get_context()
            step = self.tsm.get_current_step()

            if not packet:
                return

            # Fast-path known targets — accessibility-only, skips Gemini call
            if mode == "VERIFY" and self.fast_path.should_complete_known_target(step, self.last_known_target_key):
                self.pending_mode = None
                self.pending_screenshot = None
                self.last_known_target_key = None
                self.tsm.dispatch("verify_pass")
                await self.run()
                return

            known = self.fast_path.resolve_pre_vision(mode, ctx, step, packet, self.tsm.ctx.get("plan"))
            if known:
                # De-dup: when the same accessibility-derived target was just
                # emitted (e.g. user is still on the WhatsApp attachment sheet
                # and accessibility keeps re-matching the gallery tile every
                # screenshot), don't re-speak "Tap Gallery" again. Without this
                # guard the user hears the same instruction 2-3 times in a row
                # which sounds like two voices overlapping.
                step_index = self.tsm.ctx.get("stepIndex", 0)
                dedup_key = f"{known.get('key')}::{step_index}"
                if dedup_key == self._last_fast_path_dedup_key and mode == "NAVIGATE":
                    logger.info(
                        "Fast-path dedup: skipping re-emit of %s (already spoken for step %s)",
                        known.get("key"), step_index,
                    )
                    self.pending_mode = None
                    self.pending_screenshot = None
                    return
                self._apply_step_index_advance(known)
                self.pending_mode = None
                self.pending_screenshot = None
                self._emit_known_guidance(mode, known)
                self._last_fast_path_dedup_key = dedup_key
                return

            self.pending_mode = None
            self.pending_screenshot = None
            self.active_guide_request = {
                "mode": mode, "screenshot": packet,
                "audioBuffers": [], "audioMimeType": None, "helpDelivered": False,
            }

            prompt = self.prompt_registry.build_guide_prompt(mode, ctx, step, {
                "retryReason": "Previous attempt did not achieve the expected result" if mode == "RETRY" else None,
                "blockerInfo": ctx.get("blockerInfo"),
                "userQuery": (packet.get("meta") or {}).get("query") or ctx.get("rawGoal"),
                "screenshotWidth": packet.get("width"),
                "screenshotHeight": packet.get("height"),
                "accessibility": (packet.get("meta") or {}).get("accessibility") or [],
            })

            # Send to Gemini via the callback
            if self.on_send_image_to_gemini:
                self.on_send_image_to_gemini(packet["image"], prompt, mode)

            self._start_guide_timeout()
        except Exception as err:
            logger.error("Error processing screenshot: %s", err)
            self._clear_guide_state()

    def on_audio_chunk(self, audio: dict | None) -> None:
        if not self.active_guide_request or not self.active_guide_request.get("mode"):
            return

        data = audio.get("data") if audio else None
        if data:
            buffers = self.active_guide_request["audioBuffers"]
            if len(buffers) >= 500:
                buffers.pop(0)
            buffers.append(data)
            if not self.active_guide_request.get("audioMimeType"):
                self.active_guide_request["audioMimeType"] = audio.get("mimeType", "audio/pcm")

        if self.active_guide_request.get("mode") == "HELP" and not self.active_guide_request.get("helpDelivered"):
            self.active_guide_request["helpDelivered"] = True
            if self.tsm.get_state() == "HELPING":
                self.tsm.dispatch("help_delivered")

    async def on_guide_tool_call(self, result: dict) -> None:
        self._clear_guide_timeout()
        request = self.active_guide_request
        request_mode = (request or {}).get("mode") or self._mode_from_state(self.tsm.get_state())
        audio = self._build_combined_audio(request) if request else None
        step = self.tsm.get_current_step() or {}
        highlight = {
            "x": result.get("x", 50), "y": result.get("y", 50),
            "x1": result.get("x1"), "y1": result.get("y1"),
            "x2": result.get("x2"), "y2": result.get("y2"),
            "completed": bool(result.get("completed")),
            "blockerDetected": bool(result.get("blockerDetected")),
            "blockerReason": result.get("blockerReason"),
            "direction": result.get("direction"),
            "target": step.get("target"),
            "matchHints": step.get("matchHints") or [],
        }
        known_override = self.fast_path.resolve_post_vision_override(request_mode, request, result, step)
        if known_override:
            result.update(known_override)
            highlight.update(known_override)
        self.active_guide_request = None

        if request_mode == "NAVIGATE":
            self.tsm.dispatch("guidance_ready", result)
            self._emit_guidance(audio, highlight, "NAVIGATE")
            return

        if request_mode == "RETRY":
            self.tsm.dispatch("retry_guidance_ready", result)
            self._emit_guidance(audio, highlight, "RETRY")
            return

        if request_mode == "HELP":
            # If vision says the user already finished the action (often the
            # case when the help timeout fires just as the elderly user taps
            # through), advance the state machine instead of staying stuck in
            # HELPING. Otherwise emit the help guidance like NAVIGATE/RETRY.
            if result.get("completed"):
                # ReAct: the synthetic plan only carries one step, so a plain
                # verify_pass would terminate the task at COMPLETED. Re-plan
                # instead so NavigationReactAgent decides whether more steps
                # remain (e.g. opening WhatsApp is done -> next, find contact).
                if self.react_mode:
                    self._capture_observation_from_vision(result, request)
                    self._append_react_history(step, "ok")
                    if self.tsm.get_state() == "HELPING":
                        self.tsm.dispatch("help_delivered")
                    if self.tsm.get_state() == "AWAITING_ACTION":
                        self.tsm.dispatch("user_action_detected")
                    if self.tsm.get_state() == "VERIFYING":
                        self.tsm.dispatch("react_replan", {"clearFailure": True})
                    await self.run()
                    return
                if self.tsm.get_state() == "HELPING":
                    self.tsm.dispatch("help_delivered")
                if self.tsm.get_state() == "AWAITING_ACTION":
                    self.tsm.dispatch("user_action_detected")
                if self.tsm.get_state() == "VERIFYING":
                    self.tsm.dispatch("verify_pass")
                await self.run()
                return
            self._emit_guidance(audio, highlight, "HELP")
            # Ensure HELPING exits even when Gemini returned text but no audio
            # (audio_chunk path normally dispatches help_delivered, but it
            # never fires if there is no audio).
            if self.tsm.get_state() == "HELPING":
                self.tsm.dispatch("help_delivered")
            return

        if request_mode != "VERIFY":
            return

        self._capture_observation_from_vision(result, request)

        if self.react_mode:
            await self._handle_react_verify(audio, highlight, result, step)
            return

        if result.get("blockerDetected"):
            self.tsm.dispatch("blocker_detected", {"reason": result.get("blockerReason", "Unexpected blocker visible")})
            self.tsm.dispatch("guidance_ready", result)
            self._emit_guidance(audio, highlight, "VERIFY")
            return

        if result.get("completed"):
            self.tsm.dispatch("verify_pass")
            await self.run()
            return

        self.tsm.dispatch("verify_fail")
        if self.tsm.get_state() == "STUCK":
            await self.run()
            return

        self.tsm.dispatch("retry_guidance_ready", result)
        self._emit_guidance(audio, highlight, "RETRY")

    # ── ReAct VERIFY handling ──────────────────────────────────────────────

    def _capture_observation_from_vision(self, result: dict, request: dict | None) -> None:
        """Stash the latest screen_summary + accessibility for the next decide_next."""
        accessibility = ((request or {}).get("screenshot", {}).get("meta", {}) or {}).get("accessibility") or []
        self.tsm.ctx["lastObservation"] = {
            "screen_summary": result.get("screen_summary"),
            "target_found": result.get("target_found"),
            "target_location_text": result.get("target_location_text"),
            "accessibility": accessibility,
            "blocker_info": (
                {"reason": result.get("blockerReason")} if result.get("blockerDetected") else None
            ),
        }

    def _append_react_history(self, step: dict | None, outcome: str, reason: str | None = None) -> None:
        history = self.tsm.ctx.setdefault("reactHistory", [])
        entry = {
            "action": (step or {}).get("reactAction") or (step or {}).get("action") or "?",
            "target": (step or {}).get("target") or "",
            "outcome": outcome,
        }
        if reason:
            entry["reason"] = reason
        history.append(entry)
        # Bound history at 10 entries; prompt only uses last 3.
        if len(history) > 10:
            del history[: len(history) - 10]

    async def _handle_react_verify(self, audio, highlight, result: dict, step: dict | None) -> None:
        blocker_detected = bool(result.get("blockerDetected"))
        completed = bool(result.get("completed"))

        if blocker_detected:
            reason = result.get("blockerReason") or "Unexpected blocker visible"
            self.tsm.ctx["blockerInfo"] = {"reason": reason}
            self._append_react_history(step, "blocker", reason)
            self.tsm.dispatch("react_replan", {"retryReason": reason})
            await self.run()
            return

        if completed:
            self._append_react_history(step, "ok")
            self.tsm.dispatch("react_replan", {"clearFailure": True})
            await self.run()
            return

        # Verify failed: feed a concrete retry reason to the next decide_next.
        retry_reason = (
            result.get("target_location_text")
            or "Previous attempt did not achieve the expected result"
        )
        self._append_react_history(step, "fail", retry_reason)
        self.tsm.dispatch("react_replan", {"retryReason": retry_reason})
        await self.run()

    # ── Known guidance shortcuts ───────────────────────────────────────────
    # Pure matching logic lives in AccessibilityFastPath. This executor still
    # owns FSM-state mutation (step-index advance, last_known_target_key) and
    # the FSM event dispatch in _emit_known_guidance.

    def _apply_step_index_advance(self, known: dict) -> None:
        """If the fast-path matched a future step (e.g. visible WhatsApp contact),
        advance the FSM step index and clear per-step state."""
        advance_to = known.pop("advance_to_step_index", None)
        if advance_to is None:
            return
        fsm_ctx = self.tsm.ctx
        fsm_ctx["stepIndex"] = advance_to
        fsm_ctx["retryCount"] = 0
        fsm_ctx["blockerInfo"] = None
        fsm_ctx["guidanceResult"] = None

    def _emit_known_guidance(self, mode: str, known: dict) -> None:
        highlight = {
            "x": known["x"], "y": known["y"],
            "x1": known.get("x1"), "y1": known.get("y1"), "x2": known.get("x2"), "y2": known.get("y2"),
            "completed": bool(known.get("completed", False)),
            "blockerDetected": bool(known.get("blockerDetected", False)),
            "blockerReason": known.get("blockerReason"),
            "direction": known.get("direction"),
            "target": known.get("target") or "Settings", "matchHints": known.get("matchHints") or ["Settings", "gear"],
        }
        self.last_known_target_key = known["key"]
        if mode == "NAVIGATE":
            self.tsm.dispatch("guidance_ready", highlight)
        elif mode == "VERIFY":
            if highlight["blockerDetected"]:
                self.tsm.dispatch("blocker_detected", {"reason": highlight.get("blockerReason") or "Known UI blocker"})
                self.tsm.dispatch("guidance_ready", highlight)
            else:
                self.tsm.dispatch("verify_fail")
        elif mode == "HELP":
            pass
        else:
            self.tsm.dispatch("retry_guidance_ready", highlight)
        self._emit_guidance(None, highlight, mode, known.get("text"), speak=False)
        if mode == "HELP" and self.tsm.get_state() == "HELPING":
            self.tsm.dispatch("help_delivered")

    # ── Timeouts ───────────────────────────────────────────────────────────

    def _start_guide_timeout(self) -> None:
        self._clear_guide_timeout()
        request = self.active_guide_request
        if not request or self.guide_timeout_ms <= 0:
            return

        async def _timeout():
            await asyncio.sleep(self.guide_timeout_ms / 1000.0)
            await self._handle_guide_timeout(request)

        self._guide_timeout_task = asyncio.ensure_future(_timeout())

    def _clear_guide_timeout(self) -> None:
        if self._guide_timeout_task:
            self._guide_timeout_task.cancel()
            self._guide_timeout_task = None

    async def _handle_guide_timeout(self, request: dict) -> None:
        if self.active_guide_request is not request:
            return

        mode = request.get("mode") or self._mode_from_state(self.tsm.get_state())
        ctx = self.tsm.get_context()
        audio = self._build_combined_audio(request)
        highlight = {"x": 50, "y": 50, "completed": False, "blockerDetected": False, "blockerReason": None, "direction": None}
        text = self._get_guide_timeout_message(ctx.get("outputLanguage"), mode)

        self.active_guide_request = None

        if mode == "NAVIGATE":
            self.tsm.dispatch("guidance_ready", highlight)
            self._emit_guidance(audio, highlight, "NAVIGATE", text, speak=False)
            return
        if mode == "RETRY":
            self.tsm.dispatch("retry_guidance_ready", highlight)
            self._emit_guidance(audio, highlight, "RETRY", text, speak=False)
            return
        if mode == "VERIFY":
            self.tsm.dispatch("verify_fail")
            if self.tsm.get_state() == "STUCK":
                await self.run()
                return
            self.tsm.dispatch("retry_guidance_ready", highlight)
            self._emit_guidance(audio, highlight, "RETRY", text, speak=False)
            return
        if mode == "HELP":
            if self.tsm.get_state() == "HELPING":
                self.tsm.dispatch("help_delivered")
            self._emit_chat(text, ctx.get("outputLanguage"), True)

    def _clear_guide_state(self) -> None:
        self._clear_guide_timeout()
        self.pending_mode = None
        self.pending_screenshot = None
        self.active_guide_request = None
        self.last_known_target_key = None
        # Reset fast-path dedup so the next task / step can fire its first
        # instruction even if it happens to share a rule key with whatever
        # we just finished.
        self._last_fast_path_dedup_key = None

    def pause_for_sensitive_screen(self, app_package: str | None, language: str | None) -> dict | None:
        self._clear_guide_state()
        return self.safety_gate.handle_sensitive_screen(app_package, language)

    # ── Emit helpers ───────────────────────────────────────────────────────

    def _emit(self, msg: dict) -> None:
        if self.on_output:
            self.on_output(msg)

    def _emit_guidance(
        self,
        audio: dict | None,
        highlight: dict,
        mode: str,
        text: str | None = None,
        speak: bool = True,
    ) -> None:
        msg: dict = {"type": "guidance", "audio": audio, "highlight": highlight, "mode": mode}
        if text:
            msg["text"] = text
            msg["speak"] = speak
        self._emit(msg)

    def _emit_chat(self, text: str | None, lang: str | None = None, expects_reply: bool = True) -> None:
        self._emit({"type": "chat", "text": text, "expectsReply": expects_reply})

    def _emit_completed(self, ctx: dict) -> None:
        self._log_task_outcome("completed", ctx)
        # If the final ReAct decision included a USER-FINAL-ACTION instruction
        # (e.g. "Tap the green Send button on the right to send your photo"),
        # surface that instruction as the completion message instead of the
        # generic "All done!". This preserves the hand-off-to-user pattern:
        # the assistant marks the task complete the moment the user knows
        # what to press, without auto-tapping irreversible buttons (Send /
        # Pay / Call / Delete / Post).
        final_action = ctx.get("currentAction") or {}
        final_instruction = (final_action.get("instruction") or "").strip()
        is_handoff = bool(final_instruction) and any(
            kw in final_instruction.lower()
            for kw in ("tap the", "press the", "when you're ready", "when ready")
        )
        completion_text = final_instruction if is_handoff else self._get_completed_message(ctx.get("outputLanguage"))
        self._emit({
            "type": "completed",
            "text": completion_text,
            "goal": (ctx.get("plan") or {}).get("task") or ctx.get("rawGoal"),
        })

    def _emit_stuck(self, lang: str | None) -> None:
        self._log_task_outcome("stuck", self.tsm.get_context())
        self._emit({"type": "stuck", "text": self._get_stuck_message(lang)})

    def _emit_failed(self, reason: str | None, lang: str | None) -> None:
        self._log_task_outcome("failed", self.tsm.get_context())
        self._emit({"type": "chat", "text": self._get_failed_message(lang), "expectsReply": True, "reason": reason})

    def _log_task_outcome(self, outcome: str, ctx: dict) -> None:
        logger.info(
            "task_outcome=%s react_mode=%s react_iterations=%s history_len=%s",
            outcome,
            self.react_mode,
            ctx.get("reactIterations"),
            len(ctx.get("reactHistory") or []),
        )

    # ── User-facing fallback messages ──────────────────────────────────────
    # SmartHelp+ is English-only. The `lang` argument is retained for signature
    # compatibility with older callers but is ignored — every fallback returns
    # English so that the TTS layer never has to switch voices.

    @staticmethod
    def _get_idle_message(lang: str | None) -> str:
        return "No worries, just let me know."

    @staticmethod
    def _get_completed_message(lang: str | None) -> str:
        return "All done!"

    @staticmethod
    def _get_stuck_message(lang: str | None) -> str:
        return "Step too tricky. Ask for help."

    @staticmethod
    def _get_failed_message(lang: str | None) -> str:
        return "Didn't catch that. Try again."

    @staticmethod
    def _get_guide_timeout_message(lang: str | None, mode: str) -> str:
        return "Try once more, I'll help." if mode == "VERIFY" else "Hold on, looking again."

    @staticmethod
    def _mode_from_state(state: str) -> str | None:
        return {"GUIDING": "NAVIGATE", "VERIFYING": "VERIFY", "RETRYING": "RETRY", "HELPING": "HELP"}.get(state)

    @staticmethod
    def _build_combined_audio(request: dict | None) -> dict | None:
        if not request or not request.get("audioBuffers"):
            return None
        return {"mimeType": request.get("audioMimeType", "audio/pcm"), "data": "".join(request["audioBuffers"])}

    def cleanup(self) -> None:
        self._clear_guide_state()
