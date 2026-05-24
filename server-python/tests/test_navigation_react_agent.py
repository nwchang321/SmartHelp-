"""Tests for NavigationReactAgent.

The agent's `decide_next` makes a Gemini call internally. We stub that call by
swapping in a fake `genai`-compatible client.
"""

import asyncio
import json

import pytest

from navigation_react_agent import NavigationReactAgent


class _FakeResponse:
    def __init__(self, payload):
        self.text = payload if isinstance(payload, str) else json.dumps(payload)


class _FakeModels:
    def __init__(self, payload):
        self.payload = payload
        self.last_call_args = None

    def generate_content(self, **kwargs):
        self.last_call_args = kwargs
        return _FakeResponse(self.payload)


class _FakeClient:
    def __init__(self, payload):
        self.models = _FakeModels(payload)


def _make_agent(payload):
    client = _FakeClient(payload)
    return NavigationReactAgent(client=client), client


def test_guide_action_normalized():
    agent, client = _make_agent({
        "status": "continue",
        "action": "guide",
        "gesture": "TAP",
        "target": "WhatsApp",
        "instruction": "Tap WhatsApp.",
        "expected_result": "WhatsApp opens.",
        "match_hints": ["green icon"],
        "needs_vision": True,
    })
    decision = asyncio.run(agent.decide_next({"rawGoal": "open WhatsApp", "outputLanguage": "en"}, {}))
    assert decision["status"] == "continue"
    assert decision["action"] == "guide"
    assert decision["gesture"] == "tap"
    assert decision["target"] == "WhatsApp"
    assert decision["needs_vision"] is True
    assert decision["match_hints"] == ["green icon"]
    assert decision["avoid_hints"] == []


def test_recover_action_carries_reason():
    agent, _ = _make_agent({
        "status": "continue",
        "action": "recover",
        "gesture": "tap",
        "target": "Back",
        "instruction": "Tap Back.",
        "expected_result": "Return to previous screen.",
        "recovery_reason": "Wrong app open",
    })
    decision = asyncio.run(agent.decide_next({"rawGoal": "open WhatsApp"}, {"screen_summary": "WeChat is open"}))
    assert decision["action"] == "recover"
    assert decision["recovery_reason"] == "Wrong app open"


def test_ask_user_action_shape():
    agent, _ = _make_agent({
        "status": "needs_info",
        "action": "ask_user",
        "question": "Who should I send it to?",
        "missing_field": "recipient_name",
    })
    decision = asyncio.run(agent.decide_next({}, {}))
    assert decision["status"] == "needs_info"
    assert decision["action"] == "ask_user"
    assert decision["question"].startswith("Who should")
    assert decision["missing_field"] == "recipient_name"


def test_blank_ask_user_gets_default_question():
    agent, _ = _make_agent({
        "status": "needs_info",
        "action": "ask_user",
        "question": "",
        "missing_field": "",
    })
    decision = asyncio.run(agent.decide_next({}, {}))
    assert decision["action"] == "ask_user"
    assert decision["question"]
    assert decision["missing_field"] == "detail"


def test_complete_action_shape():
    agent, _ = _make_agent({
        "status": "completed",
        "action": "complete",
        "instruction": "All done.",
    })
    decision = asyncio.run(agent.decide_next({}, {}))
    assert decision["status"] == "completed"
    assert decision["action"] == "complete"
    assert decision["instruction"] == "All done."


def test_observation_and_retry_reason_in_prompt():
    agent, client = _make_agent({
        "status": "continue",
        "action": "recover",
        "gesture": "tap",
        "target": "Home",
        "instruction": "Tap Home.",
        "expected_result": "Home screen visible.",
        "recovery_reason": "Bad screen",
    })
    ctx = {
        "rawGoal": "open WhatsApp",
        "outputLanguage": "en",
        "retryCount": 1,
        "lastFailureReason": "WhatsApp icon not visible",
        "reactHistory": [{"action": "guide", "target": "WhatsApp", "outcome": "fail"}],
        "intentResult": {"intent_category": "open_app", "target_app": "WhatsApp"},
    }
    asyncio.run(agent.decide_next(ctx, {"screen_summary": "Home page", "accessibility": []}))
    prompt = client.models.last_call_args["contents"]
    assert "WhatsApp icon not visible" in prompt
    assert "Home page" in prompt
    assert "RETRY_REASON" in prompt
    assert "guide(WhatsApp)->fail" in prompt


def test_fenced_json_output_is_accepted():
    agent, _ = _make_agent("""```json
{"status":"completed","action":"complete","instruction":"All done."}
```""")
    decision = asyncio.run(agent.decide_next({"rawGoal": "open Settings"}, {}))
    assert decision["action"] == "complete"


def test_incomplete_guide_decision_becomes_clarification():
    agent, _ = _make_agent({
        "status": "continue",
        "action": "guide",
        "gesture": "tap",
        "target": "",
        "instruction": "",
        "expected_result": "",
    })
    decision = asyncio.run(agent.decide_next({"rawGoal": "open WhatsApp"}, {}))
    assert decision["action"] == "ask_user"
    assert decision["missing_field"] == "screen_context"


def test_invalid_retry_env_uses_defaults(monkeypatch):
    monkeypatch.setenv("GEMINI_REACT_MAX_ATTEMPTS", "many")
    monkeypatch.setenv("GEMINI_REACT_TIMEOUT_SECONDS", "slow")
    agent, _ = _make_agent({
        "status": "completed",
        "action": "complete",
        "instruction": "All done.",
    })
    assert agent._max_attempts == 3
    assert agent._timeout_seconds == 25.0
