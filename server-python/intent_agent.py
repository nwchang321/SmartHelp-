"""
IntentAgent — Translated from intentAgent.js
Analyzes user intent using Gemini LLM with heuristic fallback.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from typing import Any, Optional

logger = logging.getLogger("smarthelp.intent")

from google import genai

from prompt_registry import PromptRegistry

INTENT_SCHEMA = {
    "type": "object",
    "properties": {
        "status": {"type": "string", "enum": ["ok", "unclear", "blocked"]},
        "detected_language": {"type": "string", "enum": ["en", "zh", "ms", "mixed"]},
        "intent_category": {"type": "string"},
        "target_app": {"type": "string"},
        "target_feature": {"type": "string"},
        "target_person": {"type": "string"},
        "task_goal": {"type": "string"},
        "elderly_friendly_goal": {"type": "string"},
        "safety_level": {"type": "string", "enum": ["normal", "caution", "blocked"]},
        "missing_information": {"type": "array", "items": {"type": "string"}},
        "confidence": {"type": "number"},
    },
    "required": ["status", "detected_language", "intent_category", "safety_level", "confidence"],
}

RETRYABLE_HINTS = ["503", "UNAVAILABLE", "high demand", "temporarily unavailable"]

APP_KEYWORDS = {
    "whatsapp": "WhatsApp", "wechat": "WeChat", "grab": "Grab", "shopee": "Shopee",
    "settings": "Settings", "chrome": "Chrome", "camera": "Camera", "phone": "Phone",
    "contacts": "Contacts", "gallery": "Gallery", "touchngo": "Touch 'n Go eWallet",
    "tng": "Touch 'n Go eWallet", "maybank": "Maybank", "cimb": "CIMB",
    "gmail": "Gmail", "youtube": "YouTube", "facebook": "Facebook",
    "instagram": "Instagram", "telegram": "Telegram",
}

CATEGORY_KEYWORDS = {
    "open_app": ["open", "buka", "打开", "launch", "start", "run"],
    "send_message": ["message", "send", "mesej", "hantar", "发", "消息", "chat", "whatsapp", "wechat", "telegram"],
    "make_call": ["call", "phone", "telefon", "打电话", "拨打", "ring"],
    "change_settings": ["settings", "setting", "tetapan", "设置", "font", "wifi", "bluetooth", "volume", "brightness"],
    "make_payment": ["pay", "bayar", "付", "transfer", "duit", "send money", "转账"],
    "check_scam": ["scam", "penipuan", "诈骗", "suspicious", "curiga", "可疑"],
    "install_app": ["install", "pasang", "安装", "download", "muat turun", "uninstall"],
}

DEFAULT_INTENT_MODEL = "gemini-2.5-flash-lite"


def _detect_language(goal: str) -> str:
    text = (goal or "").strip()
    if re.search(r"[\u4e00-\u9fff]", text):
        return "mixed" if re.search(r"[a-zA-Z]", text) else "zh"
    if re.search(r"\b(buka|hantar|duit|akaun|selamat|tolong|kepada|untuk|imbas|padam|pasang)\b", text, re.I):
        return "ms"
    return "en"


def _extract_app(goal: str) -> str | None:
    lower = goal.lower()
    for keyword, name in APP_KEYWORDS.items():
        if keyword in lower:
            return name
    return None


def _extract_category(goal: str) -> str:
    lower = goal.lower()
    for category, keywords in CATEGORY_KEYWORDS.items():
        if any(kw in lower for kw in keywords):
            return category
    return "other"


def _extract_person(goal: str) -> str | None:
    patterns = [
        re.compile(r"\b(?:to|for|给|kepada)\s+(?:my\s+)?([a-z][a-z\s'-]{1,20})\b", re.I),
        re.compile(r"\b(?:son|daughter|wife|husband|mother|father|mom|dad|sister|brother|grandma|grandpa)\b", re.I),
        re.compile(r"\b(?:儿子|女儿|老婆|老公|妈妈|爸爸|姐姐|妹妹|哥哥|弟弟)\b"),
    ]
    for p in patterns:
        m = p.search(goal)
        if m:
            return m.group(1) if m.lastindex else m.group(0)
    return None


def _build_fallback_intent(user_goal: str) -> dict:
    goal = (user_goal or "").strip()
    return {
        "status": "unclear" if len(goal) < 2 else "ok",
        "detected_language": _detect_language(goal),
        "intent_category": _extract_category(goal),
        "target_app": _extract_app(goal),
        "target_feature": None,
        "target_person": _extract_person(goal),
        "task_goal": goal,
        "elderly_friendly_goal": f"Help the user with: {goal}",
        "safety_level": "normal",
        "missing_information": [],
        "confidence": 0.5,
    }


def _is_retryable(err: Exception) -> bool:
    msg = str(err)
    return any(h in msg for h in RETRYABLE_HINTS)


class IntentAgent:
    def __init__(self, *, model: str | None = None, prompt_registry: PromptRegistry | None = None):
        self._client = genai.Client(api_key=os.environ.get("GOOGLE_API_KEY"))
        self._model = model or os.environ.get("GEMINI_INTENT_MODEL", DEFAULT_INTENT_MODEL)
        self._prompt_registry = prompt_registry or PromptRegistry()
        self._system_prompt: str | None = None

    def _get_system_prompt(self) -> str:
        if not self._system_prompt:
            self._system_prompt = self._prompt_registry.load_intent_system_prompt()
        return self._system_prompt

    async def analyze_intent(self, user_goal: str, **kwargs) -> dict:
        goal = (user_goal or "").strip()
        if len(goal) < 2:
            logger.info("Goal too short, using fallback")
            return _build_fallback_intent(goal)

        logger.info("Analyzing intent (length=%d)", len(goal))

        for attempt in range(2):
            try:
                response = await asyncio.wait_for(
                    asyncio.get_event_loop().run_in_executor(
                        None,
                        lambda: self._client.models.generate_content(
                            model=self._model,
                            config={
                                "system_instruction": self._get_system_prompt(),
                                "response_mime_type": "application/json",
                                "response_schema": INTENT_SCHEMA,
                                "temperature": 0.2,
                            },
                            contents=goal,
                        ),
                    ),
                    timeout=15.0,
                )
                result = json.loads(response.text.strip())
                logger.info("Intent: category=%s, app=%s, confidence=%s", result.get('intent_category'), result.get('target_app'), result.get('confidence'))
                return result
            except Exception as err:
                retryable = _is_retryable(err) or "timed out" in str(err).lower()
                logger.warning("LLM call failed (attempt %d, retryable=%s): %s", attempt + 1, retryable, err)
                if retryable and attempt == 0:
                    await asyncio.sleep(0.5)
                    continue
                break

        logger.info("Using heuristic fallback")
        return _build_fallback_intent(goal)
