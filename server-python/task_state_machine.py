"""
TaskStateMachine — Translated from TaskStateMachine.js
Manages the finite-state machine for task guidance sessions.
"""

from __future__ import annotations

import logging
import re
import time
from copy import deepcopy
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

logger = logging.getLogger("smarthelp.fsm")


def detect_language(goal: str) -> str:
    text = (goal or "").strip().lower()
    if re.search(r"[\u4e00-\u9fff]", text):
        return "zh"
    if re.search(
        r"\b(adakah|buka|hantar|duit|akaun|selamat|tolong|kepada|untuk|imbas|padam|pasang|muat turun|kata laluan)\b",
        text,
        re.IGNORECASE,
    ):
        return "ms"
    return "en"


def _normalize_language(language: str | None) -> str | None:
    value = (language or "").strip().lower()
    mapping = {
        "zh": "zh", "chinese": "zh", "zh-cn": "zh", "zh_hans": "zh",
        "ms": "ms", "malay": "ms", "ms-my": "ms",
        "en": "en", "english": "en", "en-us": "en", "en-gb": "en",
    }
    return mapping.get(value)


def _normalize_goal_payload(payload: Any) -> tuple[str, str | None]:
    if isinstance(payload, dict):
        goal = str(payload.get("goal") or payload.get("text") or "").strip()
        lang = _normalize_language(payload.get("language"))
        return goal, lang
    return str(payload or "").strip(), None


def _build_fallback_merged_goal(goal: str, field_key: str, info: str) -> str:
    base = (goal or "").strip()
    detail = (info or "").strip()
    if not detail:
        return base
    if not base:
        return detail
    return f"{base}. {field_key}: {detail}."


def _reset_task_context(ctx: dict, payload: Any) -> None:
    goal, lang = _normalize_goal_payload(payload)
    ctx["outputLanguage"] = lang or detect_language(goal)
    ctx["rawGoal"] = goal
    ctx["safetyResult"] = None
    ctx["safetyRuleId"] = None
    ctx["isSafetyCheckRequest"] = False
    ctx["plan"] = None
    ctx["stepIndex"] = 0
    ctx["retryCount"] = 0
    ctx["awaitStartTime"] = None
    ctx["pendingQuestion"] = None
    ctx["collectedInfo"] = {}
    ctx["guidanceResult"] = None
    ctx["blockerInfo"] = None
    ctx["failReason"] = None
    ctx["completionTime"] = None
    ctx["stateHistory"] = []
    ctx["intentResult"] = None
    ctx["intentSafetyResult"] = None
    ctx["currentAction"] = None
    ctx["reactHistory"] = []
    ctx["reactIterations"] = 0
    ctx["lastFailureReason"] = None
    ctx["lastObservation"] = None


def _clear_active_task(ctx: dict) -> None:
    ctx["rawGoal"] = None
    ctx["safetyResult"] = None
    ctx["safetyRuleId"] = None
    ctx["isSafetyCheckRequest"] = False
    ctx["plan"] = None
    ctx["stepIndex"] = 0
    ctx["retryCount"] = 0
    ctx["awaitStartTime"] = None
    ctx["pendingQuestion"] = None
    ctx["collectedInfo"] = {}
    ctx["guidanceResult"] = None
    ctx["blockerInfo"] = None
    ctx["failReason"] = None
    ctx["completionTime"] = None
    ctx["intentResult"] = None
    ctx["intentSafetyResult"] = None
    ctx["currentAction"] = None
    ctx["reactHistory"] = []
    ctx["reactIterations"] = 0
    ctx["lastFailureReason"] = None
    ctx["lastObservation"] = None


# ────────────────────────────────────────────────────────────────────────────
# Transition table — each entry returns the next state
# ────────────────────────────────────────────────────────────────────────────

def _t_idle_user_goal_received(ctx, payload):
    _reset_task_context(ctx, payload)
    return "INTAKE"


def _t_intake_language_detected(ctx, payload):
    return "SAFETY_CHECK"


def _t_safety_pass(ctx, payload):
    ctx["safetyResult"] = "pass"
    ctx["safetyRuleId"] = (payload or {}).get("ruleId") if isinstance(payload, dict) else None
    ctx["isSafetyCheckRequest"] = bool((payload or {}).get("isSafetyCheckRequest")) if isinstance(payload, dict) else False
    ctx["pendingQuestion"] = None
    return "PLANNING"


def _t_safety_blocked(ctx, payload):
    ctx["safetyResult"] = "blocked"
    ctx["safetyRuleId"] = (payload or {}).get("ruleId") if isinstance(payload, dict) else None
    ctx["isSafetyCheckRequest"] = False
    ctx["pendingQuestion"] = (payload or {}).get("warning", payload) if isinstance(payload, dict) else payload
    return "CLARIFYING"


def _t_safety_confirm_needed(ctx, payload):
    ctx["safetyResult"] = "confirm_needed"
    ctx["safetyRuleId"] = (payload or {}).get("ruleId") if isinstance(payload, dict) else None
    ctx["isSafetyCheckRequest"] = False
    ctx["pendingQuestion"] = (payload or {}).get("question", payload) if isinstance(payload, dict) else payload
    return "CLARIFYING"


def _t_plan_ready(ctx, plan):
    ctx["plan"] = plan
    ctx["retryCount"] = 0
    ctx["blockerInfo"] = None
    ctx["guidanceResult"] = None
    steps = (plan or {}).get("steps") or []
    if not steps:
        ctx["failReason"] = "Planner returned an empty plan."
        return "FAILED"
    if steps[0].get("action") == "ask_user":
        ctx["pendingQuestion"] = steps[0].get("instruction")
        ctx["stepIndex"] = 0
        return "CLARIFYING"
    ctx["pendingQuestion"] = None
    ctx["stepIndex"] = 0
    return "GUIDING"


def _t_plan_failed(ctx, reason):
    ctx["failReason"] = reason or "Unable to understand this task."
    return "FAILED"


def _t_clarifying_user_provided_info(ctx, payload):
    if ctx.get("safetyResult") == "blocked":
        _clear_active_task(ctx)
        return "IDLE"

    if isinstance(payload, dict):
        info = payload.get("info", payload)
        field_key = payload.get("fieldKey") or ((ctx.get("plan") or {}).get("missingFields") or ["detail"])[0]
        merged_goal = payload.get("mergedGoal")
    else:
        info = payload
        missing = (ctx.get("plan") or {}).get("missingFields") or ["detail"]
        field_key = missing[0] if missing else "detail"
        merged_goal = None
    if not merged_goal:
        if field_key == "goal":
            merged_goal = str(info or "").strip()
        else:
            merged_goal = _build_fallback_merged_goal(ctx.get("rawGoal"), field_key, str(info or ""))
    ctx["collectedInfo"][field_key] = str(info or "")
    ctx["rawGoal"] = merged_goal
    ctx["pendingQuestion"] = None
    ctx["plan"] = None
    ctx["stepIndex"] = 0
    ctx["retryCount"] = 0
    ctx["guidanceResult"] = None
    ctx["blockerInfo"] = None
    ctx["failReason"] = None
    return "PLANNING"


def _t_guidance_ready(ctx, result):
    ctx["guidanceResult"] = result
    ctx["awaitStartTime"] = time.time() * 1000
    return "AWAITING_ACTION"


def _t_user_action_detected(ctx, payload):
    ctx["awaitStartTime"] = None
    return "VERIFYING"


def _t_help_requested(ctx, payload):
    ctx["awaitStartTime"] = None
    return "HELPING"


def _t_timeout(ctx, payload):
    ctx["awaitStartTime"] = None
    return "HELPING"


def _t_verify_pass(ctx, payload):
    next_index = ctx["stepIndex"] + 1
    plan = ctx.get("plan")
    if not plan or next_index >= len(plan.get("steps", [])):
        ctx["completionTime"] = time.time()
        return "COMPLETED"
    next_step = plan["steps"][next_index]
    ctx["retryCount"] = 0
    ctx["blockerInfo"] = None
    if next_step.get("action") == "ask_user":
        ctx["stepIndex"] = next_index
        ctx["pendingQuestion"] = next_step.get("instruction")
        return "CLARIFYING"
    ctx["stepIndex"] = next_index
    return "GUIDING"


def _t_verify_fail(ctx, payload):
    ctx["retryCount"] += 1
    if ctx["retryCount"] >= ctx.get("maxRetries", 3):
        return "STUCK"
    return "RETRYING"


def _t_blocker_detected(ctx, blocker_info):
    ctx["blockerInfo"] = blocker_info
    return "GUIDING"


def _t_retry_guidance_ready(ctx, result):
    ctx["guidanceResult"] = result
    ctx["awaitStartTime"] = time.time() * 1000
    return "AWAITING_ACTION"


def _t_help_delivered(ctx, payload):
    ctx["awaitStartTime"] = time.time() * 1000
    return "AWAITING_ACTION"


def _t_user_retry(ctx, payload):
    ctx["retryCount"] = 0
    return "GUIDING"


def _t_new_goal(ctx, payload):
    _reset_task_context(ctx, payload)
    return "INTAKE"


# ── ReAct-mode transitions (additive; legacy events untouched) ─────────────


def _t_react_replan(ctx, payload):
    """Re-enter PLANNING after a verify pass/fail in ReAct mode.

    Keeps goal, intent, language, collectedInfo, and reactHistory. Clears the
    per-step state so NavigationReactAgent decides the next action fresh.
    """
    ctx["plan"] = None
    ctx["stepIndex"] = 0
    ctx["retryCount"] = 0
    ctx["blockerInfo"] = None
    ctx["guidanceResult"] = None
    ctx["pendingQuestion"] = None
    if isinstance(payload, dict):
        reason = payload.get("retryReason")
        if reason:
            ctx["lastFailureReason"] = reason
        elif payload.get("clearFailure"):
            ctx["lastFailureReason"] = None
    return "PLANNING"


def _t_react_complete(ctx, payload):
    ctx["completionTime"] = time.time()
    return "COMPLETED"


def _t_react_stuck(ctx, payload):
    if isinstance(payload, dict) and payload.get("reason"):
        ctx["failReason"] = payload["reason"]
    return "STUCK"


def _t_react_ask_user(ctx, payload):
    """ReAct path's CLARIFYING entry; payload carries the question + missing_field."""
    if isinstance(payload, dict):
        ctx["pendingQuestion"] = payload.get("question")
        missing_field = payload.get("missing_field") or "detail"
        # Stash the missing field on a synthetic plan so the legacy
        # _t_clarifying_user_provided_info path can complete cleanly.
        ctx["plan"] = {
            "task": ctx.get("rawGoal"),
            "needsUserInput": True,
            "missingFields": [missing_field],
            "steps": [],
        }
    else:
        ctx["pendingQuestion"] = str(payload or "")
    return "CLARIFYING"


TRANSITIONS: dict[str, dict[str, Callable]] = {
    "IDLE": {"user_goal_received": _t_idle_user_goal_received},
    "INTAKE": {"language_detected": _t_intake_language_detected},
    "SAFETY_CHECK": {
        "safety_pass": _t_safety_pass,
        "safety_blocked": _t_safety_blocked,
        "safety_confirm_needed": _t_safety_confirm_needed,
    },
    "PLANNING": {
        "plan_ready": _t_plan_ready,
        "plan_failed": _t_plan_failed,
        "react_complete": _t_react_complete,
        "react_ask_user": _t_react_ask_user,
        "react_stuck": _t_react_stuck,
    },
    "CLARIFYING": {"user_provided_info": _t_clarifying_user_provided_info},
    "GUIDING": {"guidance_ready": _t_guidance_ready},
    "AWAITING_ACTION": {
        "user_action_detected": _t_user_action_detected,
        "help_requested": _t_help_requested,
        "timeout": _t_timeout,
    },
    "VERIFYING": {
        "verify_pass": _t_verify_pass,
        "verify_fail": _t_verify_fail,
        "blocker_detected": _t_blocker_detected,
        "react_replan": _t_react_replan,
    },
    "RETRYING": {
        "retry_guidance_ready": _t_retry_guidance_ready,
        "react_replan": _t_react_replan,
    },
    "HELPING": {"help_delivered": _t_help_delivered, "react_replan": _t_react_replan},
    "STUCK": {"user_retry": _t_user_retry},
    "COMPLETED": {"new_goal": _t_new_goal},
    "FAILED": {"new_goal": _t_new_goal},
}


class TaskStateMachine:
    """Finite state machine for SmartHelp+ task guidance sessions."""

    def __init__(self, session_id: str, *, max_retries: int = 3, await_timeout_ms: int = 10000):
        self.ctx: dict[str, Any] = {
            "sessionId": session_id,
            "connectedAt": time.time(),
            "outputLanguage": "en",
            "currentState": "IDLE",
            "previousState": None,
            "rawGoal": None,
            "safetyResult": None,
            "safetyRuleId": None,
            "isSafetyCheckRequest": False,
            "plan": None,
            "stepIndex": 0,
            "retryCount": 0,
            "maxRetries": max_retries,
            "awaitStartTime": None,
            "awaitTimeoutMs": await_timeout_ms,
            "pendingQuestion": None,
            "collectedInfo": {},
            "guidanceResult": None,
            "blockerInfo": None,
            "failReason": None,
            "completionTime": None,
            "stateHistory": [],
            "intentResult": None,
            "intentSafetyResult": None,
            "currentAction": None,
            "reactHistory": [],
            "reactIterations": 0,
            "lastFailureReason": None,
            "lastObservation": None,
        }
        self._listeners: list[Callable] = []

    # ── Event emitter ──────────────────────────────────────────────────────
    def on(self, event: str, callback: Callable) -> None:
        if event == "stateChange":
            self._listeners.append(callback)

    def off(self, event: str, callback: Callable) -> None:
        if event == "stateChange":
            self._listeners = [l for l in self._listeners if l is not callback]

    def _emit(self, prev: str, next_s: str, ctx: dict, trigger: str) -> None:
        for listener in self._listeners:
            try:
                listener(prev, next_s, ctx, trigger)
            except Exception:
                pass

    # ── Core ───────────────────────────────────────────────────────────────
    def dispatch(self, event: str, payload: Any = None) -> str:
        current = self.ctx["currentState"]

        # Global cancel from any non-IDLE state
        if event == "user_cancelled" and current != "IDLE":
            _clear_active_task(self.ctx)
            return self._commit_transition(current, "IDLE", event)

        handlers = TRANSITIONS.get(current)
        if not handlers or event not in handlers:
            logger.warning("No transition: %s + %s", current, event)
            return current

        next_state = handlers[event](self.ctx, payload)
        return self._commit_transition(current, next_state, event)

    def _commit_transition(self, prev: str, next_s: str, trigger: str) -> str:
        if not next_s:
            return prev
        self.ctx["previousState"] = prev
        self.ctx["currentState"] = next_s
        self.ctx["stateHistory"].append({
            "from": prev, "to": next_s, "trigger": trigger, "timestamp": time.time()
        })
        if len(self.ctx["stateHistory"]) > 100:
            self.ctx["stateHistory"] = self.ctx["stateHistory"][-100:]
        self._emit(prev, next_s, self.get_context(), trigger)
        return next_s

    def get_state(self) -> str:
        return self.ctx["currentState"]

    def get_context(self) -> dict:
        c = {**self.ctx}
        c["collectedInfo"] = {**self.ctx["collectedInfo"]}
        c["guidanceResult"] = {**self.ctx["guidanceResult"]} if self.ctx["guidanceResult"] else None
        c["blockerInfo"] = {**self.ctx["blockerInfo"]} if self.ctx["blockerInfo"] else None
        c["stateHistory"] = list(self.ctx["stateHistory"])
        c["reactHistory"] = list(self.ctx.get("reactHistory") or [])
        current = self.ctx.get("currentAction")
        c["currentAction"] = {**current} if current else None
        observation = self.ctx.get("lastObservation")
        c["lastObservation"] = {**observation} if observation else None
        return c

    def get_current_step(self) -> dict | None:
        plan = self.ctx.get("plan")
        if not plan:
            return None
        steps = plan.get("steps") or []
        idx = self.ctx.get("stepIndex", 0)
        return steps[idx] if idx < len(steps) else None

    def get_completed_steps(self) -> list:
        plan = self.ctx.get("plan")
        if not plan or not plan.get("steps"):
            return []
        return [
            s for s in plan["steps"][:self.ctx["stepIndex"]]
            if s.get("action") != "ask_user"
        ]

    def is_awaiting_timeout(self) -> bool:
        if self.ctx["currentState"] != "AWAITING_ACTION":
            return False
        start = self.ctx.get("awaitStartTime")
        if not start:
            return False
        return (time.time() * 1000 - start) >= self.ctx.get("awaitTimeoutMs", 60000)
