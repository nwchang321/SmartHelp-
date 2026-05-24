"""
IntentSafetyAgent — composes IntentAgent + SafetyGate into a single gate that
the NavigationReactAgent can consume.

This wrapper does NOT replace the underlying agents. It calls both and emits
one unified JSON document, while also surfacing the raw results for callers
that still use the legacy shapes.

Unified output shape (see docs/architecture/MULTI_AGENT_REACT_PLAN.md §6.1):
    {
      "allowed": bool,
      "intent": "navigation_task" | "blocked_task" | "confirmation_required"
                | "safety_check_request" | "needs_info" | "unclear",
      "goal": str,
      "target_app": str | None,
      "language": "en" | "zh" | "ms",
      "risk_level": "normal" | "confirm_needed" | "blocked",
      "missing_info": list[str],
      "message": str | None,
      "intent_raw": dict,   # full IntentAgent.analyze_intent() result
      "safety_raw": dict,   # full SafetyGate.check() result
    }
"""

from __future__ import annotations

import logging
from typing import Any, Optional

from intent_agent import IntentAgent
from safety_gate import SafetyGate

logger = logging.getLogger("smarthelp.intent_safety")


def _normalize_language(value: Any, fallback: str | None = None) -> str:
    if value in ("en", "zh", "ms"):
        return value
    if value == "mixed":
        return "zh"
    if fallback in ("en", "zh", "ms"):
        return fallback
    return "en"


class IntentSafetyAgent:
    """Composes intent analysis and safety gating into one call."""

    def __init__(
        self,
        *,
        intent_agent: IntentAgent | None = None,
        safety_gate: SafetyGate | None = None,
    ) -> None:
        self._intent = intent_agent or IntentAgent()
        self._safety = safety_gate or SafetyGate()

    async def analyze(self, goal: str, language: str | None = None) -> dict:
        normalized_goal = (goal or "").strip()

        # Safety runs first because it is cheap, deterministic, and a hard block
        # short-circuits the more expensive intent LLM call.
        safety_raw = self._safety.check(normalized_goal, language)

        intent_raw: dict
        try:
            intent_raw = await self._intent.analyze_intent(normalized_goal, language=language)
        except Exception as err:  # pragma: no cover — defensive; IntentAgent has its own fallback
            logger.warning("Intent analysis failed in wrapper: %s", err)
            intent_raw = {
                "status": "unclear",
                "detected_language": language or "en",
                "intent_category": "other",
                "target_app": None,
                "safety_level": "normal",
                "missing_information": [],
                "confidence": 0.0,
            }

        detected_language = _normalize_language(intent_raw.get("detected_language"), language)
        target_app = intent_raw.get("target_app")
        missing_info = list(intent_raw.get("missing_information") or [])
        intent_category = intent_raw.get("intent_category") or "other"

        if not safety_raw.get("safe"):
            if safety_raw.get("needsConfirm"):
                unified = {
                    "allowed": False,
                    "intent": "confirmation_required",
                    "goal": normalized_goal,
                    "target_app": target_app,
                    "language": detected_language,
                    "risk_level": "confirm_needed",
                    "missing_info": missing_info,
                    "message": safety_raw.get("question"),
                }
            else:
                unified = {
                    "allowed": False,
                    "intent": "blocked_task",
                    "goal": normalized_goal,
                    "target_app": target_app,
                    "language": detected_language,
                    "risk_level": "blocked",
                    "missing_info": missing_info,
                    "message": safety_raw.get("warning"),
                }
        elif safety_raw.get("isSafetyCheckRequest"):
            unified = {
                "allowed": True,
                "intent": "safety_check_request",
                "goal": normalized_goal,
                "target_app": target_app,
                "language": detected_language,
                "risk_level": "normal",
                "missing_info": missing_info,
                "message": None,
            }
        elif missing_info:
            unified = {
                "allowed": True,
                "intent": "needs_info",
                "goal": normalized_goal,
                "target_app": target_app,
                "language": detected_language,
                "risk_level": "normal",
                "missing_info": missing_info,
                "message": None,
            }
        elif intent_raw.get("status") == "unclear":
            unified = {
                "allowed": True,
                "intent": "unclear",
                "goal": normalized_goal,
                "target_app": target_app,
                "language": detected_language,
                "risk_level": "normal",
                "missing_info": missing_info,
                "message": None,
            }
        else:
            unified = {
                "allowed": True,
                "intent": "navigation_task",
                "goal": normalized_goal,
                "target_app": target_app,
                "language": detected_language,
                "risk_level": "normal",
                "missing_info": missing_info,
                "message": None,
            }

        unified["intent_raw"] = intent_raw
        unified["safety_raw"] = safety_raw
        unified["intent_category"] = intent_category
        logger.info(
            "IntentSafety: allowed=%s intent=%s risk=%s app=%s lang=%s",
            unified["allowed"], unified["intent"], unified["risk_level"],
            unified["target_app"], unified["language"],
        )
        return unified
