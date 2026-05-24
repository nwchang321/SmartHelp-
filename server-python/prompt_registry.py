"""
PromptRegistry — Translated from prompts/index.js + core/PromptRegistry.js + contextBuilder.js
Loads prompt templates from disk and builds context-enriched prompts.
"""

from __future__ import annotations

import os
from functools import lru_cache
from typing import Any

_BASE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompts")

GUIDE_MODE_FILES = {
    "NAVIGATE": "guide/guide.navigate.txt",
    "VERIFY": "guide/guide.verify.txt",
    "RETRY": "guide/guide.retry.txt",
    "HELP": "guide/guide.help.txt",
}


def _format_output_language(language: str | None) -> str:
    # SmartHelp+ is locked to English-only output regardless of the language
    # field sent from the Android client. This guarantees all LLM responses
    # (navigation instructions, guidance speech, intent rationale, etc.) come
    # back in English so they match the STT pipeline (which is also en-US
    # locked) and the English-only UI.
    return "English"


def _format_list(values: list | None) -> str:
    if values and isinstance(values, list) and len(values) > 0:
        return " | ".join(str(v) for v in values)
    return "none"


def _format_accessibility_controls(values: list | None, target: str | None = None, match_hints: list | None = None) -> str:
    """Format up to 40 accessibility entries, prioritising those whose label
    matches the current TARGET or MATCH_HINTS so the relevant control is never
    truncated away on dense screens (photo pickers, attachment sheets, etc.)."""
    if not isinstance(values, list) or not values:
        return "none"

    target_lc = (target or "").strip().lower()
    hints_lc = [str(h).strip().lower() for h in (match_hints or []) if h]
    # Strip stopwords from target ("button", "icon", "the") so partial matches work
    target_tokens = [t for t in target_lc.replace("-", " ").split() if t and t not in {"the", "a", "an", "button", "icon", "field", "bar", "label"}]

    def relevance(item: dict) -> int:
        label_lc = str(item.get("label") or "").lower()
        if not label_lc:
            return -1
        score = 0
        if target_lc and target_lc in label_lc:
            score += 10
        for tok in target_tokens:
            if tok in label_lc:
                score += 4
        for h in hints_lc:
            if h and h in label_lc:
                score += 3
        if item.get("clickable"):
            score += 1
        if item.get("enabled", True):
            score += 1
        return score

    # Stable sort: keep original order for equal-score items
    indexed = list(enumerate(values))
    indexed.sort(key=lambda pair: (-relevance(pair[1]) if isinstance(pair[1], dict) else 0, pair[0]))
    ordered = [item for _, item in indexed]

    lines = []
    for item in ordered[:40]:
        if not isinstance(item, dict):
            continue
        label = str(item.get("label") or "").strip()
        if not label:
            continue
        role = str(item.get("className") or "").split(".")[-1]
        clickable = "clickable" if item.get("clickable") else "not_clickable"
        enabled = "enabled" if item.get("enabled", True) else "disabled"
        x = item.get("x")
        y = item.get("y")
        x1 = item.get("x1")
        y1 = item.get("y1")
        x2 = item.get("x2")
        y2 = item.get("y2")
        lines.append(f"- {label} [{role} {clickable} {enabled}] center={x},{y} box={x1},{y1},{x2},{y2}")
    return "\n".join(lines) if lines else "none"


def _guide_anchor(mode: str) -> str:
    anchors = {
        "NAVIGATE": "Based on the context above and the attached screenshot, guide the user to the next action now.",
        "VERIFY": "Based on the context above and the attached screenshot, decide whether this step is complete following the VERIFY checklist.",
        "RETRY": "Based on the context above and the attached screenshot, re-guide the same step with different wording than the previous turn.",
        "HELP": "Based on the context above, describe the TARGET so the user can find it on their own.",
    }
    return anchors.get(mode, "Based on the context above and the attached screenshot, respond per the mode rules.")


class PromptRegistry:
    """Loads .txt prompt templates and builds context-enriched prompts."""

    def __init__(self, base_dir: str | None = None):
        self._base_dir = base_dir or _BASE_DIR
        self._cache: dict[str, str] = {}

    def _load(self, relative_path: str) -> str:
        if relative_path not in self._cache:
            full_path = os.path.join(self._base_dir, relative_path)
            with open(full_path, "r", encoding="utf-8") as f:
                self._cache[relative_path] = f.read().strip()
        return self._cache[relative_path]

    # ── Template loaders ───────────────────────────────────────────────────
    def build_guide_shared_prompt(self) -> str:
        return self._load("guide/guide.shared.txt")

    def build_guide_mode_prompt(self, mode: str) -> str:
        mode_key = (mode or "").upper()
        file = GUIDE_MODE_FILES.get(mode_key)
        if not file:
            raise ValueError(f"Unsupported guide prompt mode: {mode}")
        return self._load(file)

    def build_chat_template(self) -> str:
        return self._load("chat/chat.txt")

    def load_intent_system_prompt(self) -> str:
        return self._load("intent/intent.system.txt")

    def load_navigation_react_system_prompt(self) -> str:
        return self._load("navigation/navigation_react.system.txt")

    def load_navigation_react_examples(self) -> str:
        return self._load("navigation/navigation_react.examples.txt")

    def build_navigation_react_user_prompt(self, ctx: dict, observation: dict) -> str:
        """Build the per-turn user prompt for the Navigation ReAct Agent.

        Includes goal, language, intent, last action history, retry reason,
        and the latest observation (screen summary + accessibility controls).
        """
        intent = ctx.get("intentResult") or {}
        history = ctx.get("reactHistory") or []
        collected = ctx.get("collectedInfo") or {}

        lines = [
            f"OUTPUT_LANGUAGE: {_format_output_language(ctx.get('outputLanguage'))}",
            f"GOAL: {ctx.get('rawGoal') or 'none'}",
        ]

        intent_parts = []
        if intent.get("intent_category"):
            intent_parts.append(f"CATEGORY: {intent['intent_category']}")
        if intent.get("target_app"):
            intent_parts.append(f"TARGET_APP: {intent['target_app']}")
        if intent.get("target_feature"):
            intent_parts.append(f"TARGET_FEATURE: {intent['target_feature']}")
        if intent.get("target_person"):
            intent_parts.append(f"TARGET_PERSON: {intent['target_person']}")
        if intent.get("safety_level") and intent["safety_level"] != "normal":
            intent_parts.append(f"SAFETY_LEVEL: {intent['safety_level']}")
        if intent_parts:
            lines.append(f"INTENT: {' | '.join(intent_parts)}")

        if collected:
            lines.append(
                "COLLECTED_INFO: "
                + ", ".join(f"{k}={v}" for k, v in collected.items() if v)
            )

        retry_count = int(ctx.get("retryCount") or 0)
        if retry_count > 0:
            lines.append(f"RETRY_COUNT: {retry_count}")
        last_failure = ctx.get("lastFailureReason")
        if last_failure:
            lines.append(f"RETRY_REASON: {last_failure}")

        if history:
            history_text_parts = []
            for entry in history[-3:]:
                if not isinstance(entry, dict):
                    continue
                action = entry.get("action") or "?"
                target = entry.get("target") or ""
                outcome = entry.get("outcome") or "?"
                history_text_parts.append(f"{action}({target})->{outcome}")
            if history_text_parts:
                lines.append("LAST_ACTIONS: " + " | ".join(history_text_parts))

        blocker = observation.get("blocker_info") or {}
        if blocker.get("reason"):
            lines.append(f"BLOCKER: {blocker['reason']}")

        screen_summary = observation.get("screen_summary")
        if screen_summary:
            lines.append(f"SCREEN_SUMMARY: {screen_summary}")
        else:
            lines.append("SCREEN_SUMMARY: <not available yet — decide based on goal + last actions>")

        accessibility = observation.get("accessibility")
        if accessibility:
            lines.append("ACCESSIBILITY_CONTROLS:")
            # ReAct turns don't have a single TARGET, but intent target_feature
            # and the goal text are decent relevance signals.
            react_target = intent.get("target_feature") or intent.get("target_app") or ""
            react_hints = [ctx.get("rawGoal") or ""]
            lines.append(_format_accessibility_controls(accessibility, react_target, react_hints))

        anchor = "Return one JSON action object matching the schema."
        return "<observation>\n" + "\n".join(lines) + "\n</observation>\n\n" + anchor

    # ── Context builders ───────────────────────────────────────────────────
    def build_guide_prompt(self, mode: str, ctx: dict, step: dict | None, meta: dict | None = None) -> str:
        """Build mode-specific prompt + structured context block for Gemini guidance."""
        meta = meta or {}
        normalized_mode = (mode or "NAVIGATE").upper()
        safe_step = step or {
            "instruction": "Find the next safe entry point",
            "action": "tap",
            "target": "relevant item",
            "matchHints": [],
            "avoidHints": [],
            "expectedResult": "The next relevant screen is visible",
        }
        total_steps = max(len((ctx.get("plan") or {}).get("steps") or []), 1)
        current_index = ctx.get("stepIndex", 0) if isinstance(ctx.get("stepIndex"), int) else 0
        step_label = f"{current_index + 1}/{total_steps}"

        lines = [
            f"MODE: {normalized_mode}",
            f"OUTPUT_LANGUAGE: {_format_output_language(ctx.get('outputLanguage'))}",
            f"GOAL: {ctx.get('rawGoal') or meta.get('userQuery') or 'none'}",
            f"STEP_INDEX: {step_label}",
        ]

        if normalized_mode == "VERIFY":
            lines.append(f"IS_LAST_STEP: {current_index >= total_steps - 1}")
            lines.append(f"LAST_INSTRUCTION: {safe_step.get('instruction', '')}")
        else:
            lines.append(f"INSTRUCTION: {safe_step.get('instruction', '')}")

        lines.append(f"ACTION: {safe_step.get('action', '')}")
        lines.append(f"TARGET: {safe_step.get('target', '')}")
        lines.append(f"MATCH_HINTS: {_format_list(safe_step.get('matchHints'))}")
        lines.append(f"AVOID_HINTS: {_format_list(safe_step.get('avoidHints'))}")
        lines.append(f"EXPECTED_RESULT: {safe_step.get('expectedResult', '')}")
        if normalized_mode != "HELP":
            if meta.get("screenshotWidth") and meta.get("screenshotHeight"):
                lines.append(f"SCREENSHOT_SIZE: {meta['screenshotWidth']}x{meta['screenshotHeight']}")
            lines.append(
                "COORDINATE_SYSTEM: x/y are percentages of the full attached screenshot, "
                "including status/navigation bars; choose the tappable center of the UI element, "
                "not the label text or surrounding card."
            )
            lines.append(
                "BOUNDING_BOX: when a target is visible, also return x1/y1/x2/y2 as a tight box "
                "around the tappable UI element in the same full-screenshot percentage coordinate system."
            )

        # Intent context
        intent = ctx.get("intentResult")
        if intent and isinstance(intent, dict):
            intent_parts = []
            if intent.get("intent_category"):
                intent_parts.append(f"CATEGORY: {intent['intent_category']}")
            if intent.get("target_app"):
                intent_parts.append(f"TARGET_APP: {intent['target_app']}")
            if intent.get("target_feature"):
                intent_parts.append(f"TARGET_FEATURE: {intent['target_feature']}")
            if intent.get("target_person"):
                intent_parts.append(f"TARGET_PERSON: {intent['target_person']}")
            if intent.get("safety_level") and intent["safety_level"] != "normal":
                intent_parts.append(f"SAFETY_LEVEL: {intent['safety_level']}")
            if intent_parts:
                lines.append(f"INTENT: {' | '.join(intent_parts)}")

        if meta.get("userQuery") and meta["userQuery"] != ctx.get("rawGoal"):
            lines.append(f"USER_QUERY: {meta['userQuery']}")

        # LAST_ACTIONS — feeds the guide.verify <verification_order> rule 5
        # (SAME TARGET REPEATED — ANTI-LOOP). Without history the model can't
        # detect when it's been highlighting the same wrong target turn after
        # turn (e.g. snapping to the contact avatar in the chat header).
        history = ctx.get("reactHistory") or []
        if history:
            history_parts = []
            for entry in history[-3:]:
                if not isinstance(entry, dict):
                    continue
                a = entry.get("action") or "?"
                t = entry.get("target") or ""
                o = entry.get("outcome") or "?"
                history_parts.append(f"{a}({t})->{o}")
            if history_parts:
                lines.append("LAST_ACTIONS: " + " | ".join(history_parts))
            # Anti-loop hint: surface explicit warning when the same target
            # was highlighted on the immediately previous turn AND that turn
            # did not succeed. Strongly biases the model away from re-issuing
            # the same coordinates.
            current_target = (safe_step.get("target") or "").strip().lower()
            if current_target:
                recent = [e for e in history[-3:] if isinstance(e, dict)]
                same_target_recent = [
                    e for e in recent
                    if (e.get("target") or "").strip().lower() == current_target
                    and e.get("outcome") not in ("ok", None)
                ]
                if len(same_target_recent) >= 1:
                    lines.append(
                        f"REPEAT_TARGET_WARNING: previous turn already highlighted "
                        f"'{safe_step.get('target')}' and it did not succeed — "
                        f"change strategy per verify rule 5; do NOT re-issue the "
                        f"same coordinates or snap to a prominent corner icon."
                    )

        accessibility = meta.get("accessibility")
        if accessibility:
            lines.append("ACCESSIBILITY_CONTROLS:")
            # Pass TARGET + MATCH_HINTS so the relevant control is preserved
            # when the list is truncated to 40 entries (dense photo pickers).
            lines.append(_format_accessibility_controls(
                accessibility,
                safe_step.get("target"),
                safe_step.get("matchHints"),
            ))

        blocker = meta.get("blockerInfo") or {}
        if blocker.get("reason"):
            lines.append(f"BLOCKER_INFO: {blocker['reason']}")

        if normalized_mode == "RETRY":
            lines.append(f"RETRY_COUNT: {ctx.get('retryCount', 0)}")
            if meta.get("retryReason"):
                lines.append(f"RETRY_REASON: {meta['retryReason']}")

        context_block = f"<context>\n" + "\n".join(lines) + "\n</context>"
        shared_template = self.build_guide_shared_prompt()
        mode_template = self.build_guide_mode_prompt(mode)
        anchor = _guide_anchor(normalized_mode)

        return f"{shared_template}\n\n{mode_template}\n\n{context_block}\n\n{anchor}"

    def build_chat_prompt(self, ctx: dict, step: dict | None = None) -> str:
        lines = [
            "MODE: CHAT",
            f"OUTPUT_LANGUAGE: {_format_output_language(ctx.get('outputLanguage'))}",
            f"GOAL: {ctx.get('rawGoal') or 'none'}",
        ]
        if step and step.get("action") == "ask_user":
            lines.append(f"CLARIFICATION_QUESTION: {step.get('instruction', '')}")
        lines.append(f"MISSING_FIELDS: {_format_list((ctx.get('plan') or {}).get('missingFields'))}")

        context_block = f"<context>\n" + "\n".join(lines) + "\n</context>"
        anchor = "Based on the context above, reply with one short sentence in OUTPUT_LANGUAGE."
        template = self.build_chat_template()

        return f"{template}\n\n{context_block}\n\n{anchor}"

    def clear_cache(self) -> None:
        self._cache.clear()
