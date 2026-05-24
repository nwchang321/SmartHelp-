"""End-to-end ReAct flow tests for TaskExecutor.

Uses stub IntentSafetyAgent + stub NavigationReactAgent, no real Gemini calls.
"""

import asyncio

import pytest

from task_executor import TaskExecutor
from task_state_machine import TaskStateMachine


class _StubIntentSafety:
    def __init__(self, response=None):
        self.calls = 0
        self.response = response or {
            "allowed": True,
            "intent": "navigation_task",
            "goal": "",
            "target_app": None,
            "language": "en",
            "risk_level": "normal",
            "missing_info": [],
            "message": None,
            "intent_raw": {},
            "safety_raw": {"safe": True, "needsConfirm": False, "isSafetyCheckRequest": False, "ruleId": None},
            "intent_category": "other",
        }

    async def analyze(self, goal, language=None):
        self.calls += 1
        out = dict(self.response)
        out["goal"] = goal
        return out


class _StubReact:
    """Plays a scripted sequence of decisions."""

    def __init__(self, *decisions):
        self.decisions = list(decisions)
        self.last_ctx = None
        self.last_obs = None

    async def decide_next(self, ctx, observation):
        self.last_ctx = ctx
        self.last_obs = observation
        if not self.decisions:
            raise AssertionError("decide_next called more times than scripted")
        return self.decisions.pop(0)


def _guide(target="A", instruction="Tap A.", expected="A opens.", **extra):
    base = {
        "status": "continue",
        "action": "guide",
        "gesture": "tap",
        "target": target,
        "instruction": instruction,
        "expected_result": expected,
        "match_hints": [],
        "avoid_hints": [],
        "needs_vision": True,
        "recovery_reason": None,
    }
    base.update(extra)
    return base


def _recover(target, reason):
    return {
        "status": "continue",
        "action": "recover",
        "gesture": "tap",
        "target": target,
        "instruction": f"Tap {target}.",
        "expected_result": "Return to previous screen.",
        "match_hints": [],
        "avoid_hints": [],
        "needs_vision": True,
        "recovery_reason": reason,
    }


def _ask(question, field):
    return {
        "status": "needs_info",
        "action": "ask_user",
        "question": question,
        "missing_field": field,
    }


def _complete(instruction="Done."):
    return {"status": "completed", "action": "complete", "instruction": instruction}


def _make_executor(react_stub, *, intent_safety=None, intent_agent=None):
    tsm = TaskStateMachine("test")
    emitted: list = []
    config = {
        "intentSafetyAgent": intent_safety or _StubIntentSafety(),
        "navigationReactAgent": react_stub,
        "reactMode": True,
    }
    if intent_agent is not None:
        config["intentAgent"] = intent_agent
    te = TaskExecutor(tsm, config=config)
    te.on_output = lambda msg: emitted.append(msg)
    return te, tsm, emitted


def _simulate_verify(te, tsm, vision_result):
    tsm.dispatch("guidance_ready", {})
    tsm.dispatch("user_action_detected")
    te.active_guide_request = {"mode": "VERIFY", "screenshot": {"meta": {"accessibility": []}}}
    asyncio.run(te.on_guide_tool_call(vision_result))


def test_first_decision_emits_screenshot_request():
    react = _StubReact(_guide("WhatsApp"))
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "open WhatsApp")
    asyncio.run(te.run())
    assert tsm.get_state() == "GUIDING"
    assert tsm.ctx["currentAction"]["target"] == "WhatsApp"
    assert [m["type"] for m in emitted] == ["screenshot_request"]
    assert tsm.ctx["plan"]["steps"][0]["target"] == "WhatsApp"


def test_safety_block_stops_before_react_planning():
    react = _StubReact()
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "Send my OTP code to the caller")

    asyncio.run(te.run())

    assert tsm.get_state() == "IDLE"
    assert emitted == [
        {
            "type": "chat",
            "text": "Verification codes and passwords are your private information. Never share them with anyone, including bank staff or police.",
            "expectsReply": False,
        }
    ]


def test_verify_pass_triggers_replan_and_next_decision():
    react = _StubReact(_guide("A"), _guide("B"), _complete())
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "two-step task")
    asyncio.run(te.run())
    assert tsm.ctx["currentAction"]["target"] == "A"

    _simulate_verify(te, tsm, {"x": 50, "y": 50, "completed": True, "screen_summary": "A opened"})
    assert tsm.ctx["currentAction"]["target"] == "B"
    assert tsm.ctx["lastObservation"]["screen_summary"] == "A opened"

    _simulate_verify(te, tsm, {"x": 50, "y": 50, "completed": True, "screen_summary": "B opened"})
    assert tsm.get_state() == "COMPLETED"


def test_verify_fail_passes_retry_reason_to_next_decision():
    react = _StubReact(_guide("WhatsApp"), _recover("Back", "Wrong app opened"))
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "open WhatsApp")
    asyncio.run(te.run())

    _simulate_verify(te, tsm, {
        "x": 50, "y": 50, "completed": False,
        "screen_summary": "WeChat is open",
        "target_location_text": "WhatsApp icon not visible",
    })
    assert tsm.ctx["currentAction"]["action"] == "recover"
    assert react.last_ctx["lastFailureReason"] == "WhatsApp icon not visible"
    assert tsm.ctx["reactHistory"][0]["outcome"] == "fail"


def test_ask_user_goes_to_clarifying():
    react = _StubReact(_ask("Who do you want to message?", "recipient_name"))
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "send message")
    asyncio.run(te.run())
    assert tsm.get_state() == "CLARIFYING"
    assert tsm.ctx["pendingQuestion"].startswith("Who do you")


def test_complete_decision_goes_to_completed_state():
    react = _StubReact(_complete("Settings is open."))
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "open Settings")
    asyncio.run(te.run())
    assert tsm.get_state() == "COMPLETED"


def test_blocker_routes_through_replan_with_reason():
    react = _StubReact(_guide("WhatsApp"), _recover("Close popup", "Permission popup blocking"))
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "open WhatsApp")
    asyncio.run(te.run())

    _simulate_verify(te, tsm, {
        "x": 50, "y": 50, "completed": False,
        "blockerDetected": True,
        "blockerReason": "Notifications permission popup is visible",
    })
    assert react.last_ctx["lastFailureReason"] == "Notifications permission popup is visible"
    assert tsm.ctx["currentAction"]["action"] == "recover"


def test_iteration_budget_routes_to_stuck():
    react = _StubReact(*[_guide("X") for _ in range(10)])
    te, tsm, emitted = _make_executor(react)
    te.max_react_iterations = 2
    tsm.dispatch("user_goal_received", "looping task")
    asyncio.run(te.run())
    for _ in range(5):
        if tsm.get_state() != "GUIDING":
            break
        _simulate_verify(te, tsm, {"x": 50, "y": 50, "completed": True, "screen_summary": "still X"})
    assert tsm.get_state() == "STUCK"
    assert tsm.ctx["failReason"] == "ReAct iteration budget exceeded"


def test_react_history_is_bounded_to_ten_entries():
    """The executor must not let reactHistory grow unbounded across long sessions."""
    react = _StubReact(*[_guide(f"S{i}") for i in range(20)])
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "long task")
    asyncio.run(te.run())
    for _ in range(15):
        if tsm.get_state() != "GUIDING":
            break
        _simulate_verify(te, tsm, {"x": 50, "y": 50, "completed": True, "screen_summary": "next"})
    history = tsm.ctx["reactHistory"]
    assert len(history) <= 10, f"reactHistory grew to {len(history)} entries; should be capped at 10"


def test_react_prompt_includes_only_last_three_history_entries():
    """build_navigation_react_user_prompt should slice history to the last 3 entries."""
    from prompt_registry import PromptRegistry
    pr = PromptRegistry()
    ctx = {
        "rawGoal": "test",
        "outputLanguage": "en",
        "reactHistory": [
            {"action": "guide", "target": "A", "outcome": "fail"},
            {"action": "guide", "target": "B", "outcome": "fail"},
            {"action": "guide", "target": "C", "outcome": "ok"},
            {"action": "guide", "target": "D", "outcome": "ok"},
            {"action": "guide", "target": "E", "outcome": "ok"},
        ],
    }
    prompt = pr.build_navigation_react_user_prompt(ctx, {})
    # Last three only: C, D, E
    assert "guide(C)->ok" in prompt
    assert "guide(D)->ok" in prompt
    assert "guide(E)->ok" in prompt
    assert "guide(A)" not in prompt
    assert "guide(B)" not in prompt


def test_unsolicited_screenshot_in_awaiting_state_triggers_verify():
    """When Android's accessibility tap detection sends a verify screenshot
    while the FSM is in AWAITING_ACTION, the executor should jump straight to
    VERIFY mode instead of waiting for the timeout to fire HELP."""
    react = _StubReact(_guide("WhatsApp"), _guide("Next"), _complete())
    te, tsm, emitted = _make_executor(react)
    te.gemini_ready = True
    tsm.dispatch("user_goal_received", "open WhatsApp then more")
    asyncio.run(te.run())
    assert tsm.get_state() == "GUIDING"

    # In real flow, the NAVIGATE screenshot would have been processed and
    # vision result delivered before guidance_ready fires. Simulate that
    # cleanup so pending_mode is correctly None at AWAITING_ACTION entry.
    te.pending_mode = None
    te.pending_screenshot = None
    tsm.dispatch("guidance_ready", {})
    assert tsm.get_state() == "AWAITING_ACTION"

    # Android sends a screenshot without server asking (tap detected).
    asyncio.run(te.on_screenshot("img-b64", 1080, 2400, {"accessibility": []}))

    # Either VERIFY started immediately and possibly progressed further, but
    # we must not have stayed in AWAITING_ACTION.
    assert tsm.get_state() != "AWAITING_ACTION", (
        "Unsolicited screenshot should have advanced the FSM out of AWAITING_ACTION"
    )


def test_help_completion_in_react_mode_replans_instead_of_completing():
    """Regression: HELP-mode `completed=True` must trigger react_replan, not
    cascade to verify_pass + COMPLETED (which only had 1 synthetic step)."""
    react = _StubReact(_guide("WhatsApp"), _guide("Search"), _complete())
    te, tsm, emitted = _make_executor(react)
    tsm.dispatch("user_goal_received", "open WhatsApp then search son")
    asyncio.run(te.run())
    assert tsm.ctx["currentAction"]["target"] == "WhatsApp"

    # Simulate the user being slow: FSM times out, HELPING state is entered,
    # then a HELP-mode screenshot arrives showing WhatsApp finally opened.
    tsm.dispatch("guidance_ready", {})
    tsm.dispatch("timeout")  # AWAITING_ACTION → HELPING
    assert tsm.get_state() == "HELPING"
    te.active_guide_request = {"mode": "HELP", "screenshot": {"meta": {"accessibility": []}}}
    asyncio.run(te.on_guide_tool_call({
        "x": 17, "y": 95, "completed": True,
        "screen_summary": "WhatsApp chat list visible",
    }))
    # Must re-plan to the next ReAct decision, NOT mark task complete.
    assert tsm.get_state() != "COMPLETED", "HELP completion should not end ReAct task"
    assert tsm.ctx["currentAction"]["target"] == "Search"


def test_react_failure_routes_to_stuck_without_alternate_fallback():
    class FailingReact:
        async def decide_next(self, ctx, observation):
            raise RuntimeError("simulated Gemini outage")

    te, tsm, emitted = _make_executor(FailingReact())
    tsm.dispatch("user_goal_received", "open WhatsApp")
    asyncio.run(te.run())
    assert tsm.get_state() == "STUCK"
    assert tsm.ctx["failReason"] == "ReAct navigation could not decide the next action"
    assert emitted[-1]["type"] == "stuck"
