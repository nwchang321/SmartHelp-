"""
NavigationReactAgent — decides the single next action for an in-progress task.

Returns strict JSON matching one of four shapes:
  guide / recover / ask_user / complete.

Inputs:
  ctx        — { rawGoal, outputLanguage, intentResult, history, retryCount,
                 lastFailureReason, collectedInfo, ... }
  observation — { screen_summary, accessibility, last_action, target_app, blocker_info }

The agent never produces a multi-step plan; it is called once per loop turn.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from typing import Any, Optional

from google import genai

from prompt_registry import PromptRegistry

logger = logging.getLogger("smarthelp.navigation_react")

DEFAULT_REACT_MODEL = "gemini-2.5-flash-lite"
DEFAULT_REACT_MAX_ATTEMPTS = 3
DEFAULT_REACT_TIMEOUT_SECONDS = 25.0
REACT_HISTORY_LIMIT = 3  # last 3 (action, result) pairs included in context
MAX_REACT_ITERATIONS = 12  # safety cap; executor enforces, agent advertises.

RETRYABLE_HINTS = ["503", "UNAVAILABLE", "high demand", "temporarily unavailable"]

NAVIGATION_REACT_SCHEMA = {
    "type": "object",
    "properties": {
        "status": {"type": "string", "enum": ["continue", "needs_info", "completed"]},
        "action": {"type": "string", "enum": ["guide", "recover", "ask_user", "complete"]},
        "gesture": {"type": "string"},
        "target": {"type": "string"},
        "instruction": {"type": "string"},
        "expected_result": {"type": "string"},
        "match_hints": {"type": "array", "items": {"type": "string"}},
        "avoid_hints": {"type": "array", "items": {"type": "string"}},
        "needs_vision": {"type": "boolean"},
        "recovery_reason": {"type": "string"},
        "question": {"type": "string"},
        "missing_field": {"type": "string"},
    },
    "required": ["status", "action"],
}


def _is_retryable(err: Exception) -> bool:
    msg = str(err)
    return any(h in msg for h in RETRYABLE_HINTS)


def _env_int(name: str, default: int, minimum: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return max(minimum, int(raw))
    except ValueError:
        logger.warning("Invalid %s=%r; using default %s", name, raw, default)
        return default


def _env_float(name: str, default: float, minimum: float) -> float:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return max(minimum, float(raw))
    except ValueError:
        logger.warning("Invalid %s=%r; using default %s", name, raw, default)
        return default


class NavigationReactAgent:
    def __init__(
        self,
        *,
        model: str | None = None,
        prompt_registry: PromptRegistry | None = None,
        client: Any = None,
    ) -> None:
        self._client = client or genai.Client(api_key=os.environ.get("GOOGLE_API_KEY"))
        self._model = model or os.environ.get("GEMINI_REACT_MODEL", DEFAULT_REACT_MODEL)
        self._prompt_registry = prompt_registry or PromptRegistry()
        self._max_attempts = _env_int("GEMINI_REACT_MAX_ATTEMPTS", DEFAULT_REACT_MAX_ATTEMPTS, 1)
        self._timeout_seconds = _env_float(
            "GEMINI_REACT_TIMEOUT_SECONDS",
            DEFAULT_REACT_TIMEOUT_SECONDS,
            5.0,
        )
        self._system_prompt: str | None = None
        self._examples: str | None = None

    def _get_system_prompt(self) -> str:
        if not self._system_prompt:
            self._system_prompt = self._prompt_registry.load_navigation_react_system_prompt()
        return self._system_prompt

    def _get_examples(self) -> str:
        if self._examples is None:
            self._examples = self._prompt_registry.load_navigation_react_examples()
        return self._examples

    async def decide_next(self, ctx: dict, observation: dict | None = None) -> dict:
        observation = observation or {}
        prompt = self._prompt_registry.build_navigation_react_user_prompt(ctx, observation)

        for attempt in range(self._max_attempts):
            try:
                response = await asyncio.wait_for(
                    asyncio.get_event_loop().run_in_executor(
                        None,
                        lambda: self._client.models.generate_content(
                            model=self._model,
                            config={
                                "system_instruction": (
                                    self._get_system_prompt()
                                    + "\n\n"
                                    + self._get_examples()
                                ),
                                "response_mime_type": "application/json",
                                "response_schema": NAVIGATION_REACT_SCHEMA,
                                "temperature": 0.2,
                            },
                            contents=prompt,
                        ),
                    ),
                    timeout=self._timeout_seconds,
                )
                text = (getattr(response, "text", "") or "").strip()
                result = _parse_json_object(text)
                logger.info(
                    "ReAct decision: status=%s action=%s target=%s",
                    result.get("status"), result.get("action"), result.get("target"),
                )
                return self._normalize(result)
            except Exception as err:
                retryable = (
                    isinstance(err, (json.JSONDecodeError, ValueError, asyncio.TimeoutError))
                    or _is_retryable(err)
                    or "timed out" in str(err).lower()
                )
                logger.warning(
                    "ReAct decide_next failed (attempt %d, retryable=%s): %s",
                    attempt + 1, retryable, err,
                )
                if retryable and attempt < self._max_attempts - 1:
                    await asyncio.sleep(0.5)
                    continue
                raise

    @staticmethod
    def _normalize(result: dict) -> dict:
        """Defensive: ensure required fields exist and are typed correctly."""
        status = result.get("status") or "continue"
        action = result.get("action") or "guide"
        if action not in {"guide", "recover", "ask_user", "complete"}:
            return NavigationReactAgent._clarify("I need a clearer screen before the next step.", "screen_context")
        out: dict = {"status": status, "action": action}
        if action in ("guide", "recover"):
            target = str(result.get("target") or "").strip()
            instruction = str(result.get("instruction") or "").strip()
            expected_result = str(result.get("expected_result") or "").strip()
            if not target or not instruction or not expected_result:
                return NavigationReactAgent._clarify(
                    "I need a clearer screen before the next step.",
                    "screen_context",
                )
            out["gesture"] = (result.get("gesture") or "tap").lower()
            out["target"] = target
            out["instruction"] = instruction
            out["expected_result"] = expected_result
            out["match_hints"] = list(result.get("match_hints") or [])
            out["avoid_hints"] = list(result.get("avoid_hints") or [])
            out["needs_vision"] = bool(result.get("needs_vision", True))
            out["recovery_reason"] = result.get("recovery_reason")
        elif action == "ask_user":
            out["question"] = str(result.get("question") or "Can you tell me what you see on the screen?").strip()
            out["missing_field"] = str(result.get("missing_field") or "detail").strip()
        elif action == "complete":
            out["instruction"] = str(result.get("instruction") or "All done.").strip()
        return out

    @staticmethod
    def _clarify(question: str, missing_field: str) -> dict:
        return {
            "status": "needs_info",
            "action": "ask_user",
            "question": question,
            "missing_field": missing_field,
        }


def _parse_json_object(text: str) -> dict:
    """Parse strict JSON, with a small guard for accidental fenced output."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S | re.I)
        if fenced:
            return json.loads(fenced.group(1))
        embedded = re.search(r"\{.*\}", text, re.S)
        if embedded:
            return json.loads(embedded.group(0))
        raise
