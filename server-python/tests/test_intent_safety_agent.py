"""Tests for IntentSafetyAgent — the wrapper that combines IntentAgent + SafetyGate."""

import asyncio

import pytest

from intent_safety_agent import IntentSafetyAgent
from safety_gate import SafetyGate


class _FakeIntent:
    """Stub IntentAgent that returns a canned result, no LLM call."""

    def __init__(self, result):
        self.result = result
        self.calls = 0

    async def analyze_intent(self, goal, **kwargs):
        self.calls += 1
        out = dict(self.result)
        out.setdefault("task_goal", goal)
        return out


def _intent_ok(**overrides):
    base = {
        "status": "ok",
        "detected_language": "en",
        "intent_category": "send_message",
        "target_app": "WhatsApp",
        "target_feature": None,
        "target_person": None,
        "task_goal": "",
        "safety_level": "normal",
        "missing_information": [],
        "confidence": 0.9,
    }
    base.update(overrides)
    return base


@pytest.fixture
def gate():
    return SafetyGate()


def test_normal_navigation_task(gate):
    agent = IntentSafetyAgent(intent_agent=_FakeIntent(_intent_ok()), safety_gate=gate)
    result = asyncio.run(agent.analyze("send a WhatsApp message to my daughter", "en"))
    assert result["allowed"] is True
    assert result["intent"] == "navigation_task"
    assert result["risk_level"] == "normal"
    assert result["target_app"] == "WhatsApp"
    assert "intent_raw" in result and "safety_raw" in result


def test_blocked_otp_share(gate):
    agent = IntentSafetyAgent(intent_agent=_FakeIntent(_intent_ok()), safety_gate=gate)
    result = asyncio.run(agent.analyze("tell me my OTP code 1234", "en"))
    assert result["allowed"] is False
    assert result["intent"] == "blocked_task"
    assert result["risk_level"] == "blocked"
    assert result["message"]  # warning text present


def test_confirm_needed_for_money_transfer(gate):
    agent = IntentSafetyAgent(intent_agent=_FakeIntent(_intent_ok(intent_category="make_payment")), safety_gate=gate)
    result = asyncio.run(agent.analyze("transfer money to my son", "en"))
    assert result["allowed"] is False
    assert result["intent"] == "confirmation_required"
    assert result["risk_level"] == "confirm_needed"


def test_safety_check_request(gate):
    agent = IntentSafetyAgent(intent_agent=_FakeIntent(_intent_ok()), safety_gate=gate)
    result = asyncio.run(agent.analyze("is this a scam?", "en"))
    assert result["allowed"] is True
    assert result["intent"] == "safety_check_request"


def test_needs_info_when_missing_information(gate):
    intent = _FakeIntent(_intent_ok(missing_information=["recipient_name"], status="unclear"))
    agent = IntentSafetyAgent(intent_agent=intent, safety_gate=gate)
    result = asyncio.run(agent.analyze("send message", "en"))
    assert result["allowed"] is True
    assert result["intent"] == "needs_info"
    assert result["missing_info"] == ["recipient_name"]


def test_language_normalization(gate):
    intent = _FakeIntent(_intent_ok(detected_language="mixed"))
    agent = IntentSafetyAgent(intent_agent=intent, safety_gate=gate)
    result = asyncio.run(agent.analyze("帮我开 WhatsApp", "zh"))
    # "mixed" should normalize to "zh"
    assert result["language"] == "zh"


def test_unclear_when_intent_status_unclear_and_no_missing(gate):
    intent = _FakeIntent(_intent_ok(status="unclear", missing_information=[]))
    agent = IntentSafetyAgent(intent_agent=intent, safety_gate=gate)
    result = asyncio.run(agent.analyze("hmm", "en"))
    assert result["allowed"] is True
    assert result["intent"] == "unclear"


def test_intent_failure_falls_back(gate):
    class FailingIntent:
        async def analyze_intent(self, *a, **k):
            raise RuntimeError("simulated LLM failure")
    agent = IntentSafetyAgent(intent_agent=FailingIntent(), safety_gate=gate)
    result = asyncio.run(agent.analyze("open WhatsApp", "en"))
    # SafetyGate still runs; intent falls back to defaults
    assert result["allowed"] is True
    assert result["intent_raw"]["status"] == "unclear"
