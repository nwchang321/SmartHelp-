"""Tests for TaskStateMachine — ported from server/test/taskStateMachine.test.js"""

import pytest
from task_state_machine import TaskStateMachine, detect_language


@pytest.fixture
def fsm():
    return TaskStateMachine("test-session")


class TestDetectLanguage:
    def test_chinese(self):
        assert detect_language("帮我打开设置") == "zh"

    def test_malay(self):
        assert detect_language("Buka tetapan telefon") == "ms"

    def test_english(self):
        assert detect_language("Open phone settings") == "en"

    def test_empty(self):
        assert detect_language("") == "en"

    def test_none(self):
        assert detect_language(None) == "en"


class TestInitialState:
    def test_starts_idle(self, fsm):
        assert fsm.get_state() == "IDLE"

    def test_session_id(self, fsm):
        assert fsm.get_context()["sessionId"] == "test-session"


class TestGoalFlow:
    def test_idle_to_intake(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        assert fsm.get_state() == "INTAKE"

    def test_task_language_uses_payload_language(self, fsm):
        fsm.dispatch("user_goal_received", {"goal": "\u5e2e\u6211\u6253\u5f00\u8bbe\u7f6e", "language": "zh"})
        assert fsm.get_context()["outputLanguage"] == "zh"

    def test_task_language_detects_chinese_when_missing(self, fsm):
        fsm.dispatch("user_goal_received", "\u5e2e\u6211\u6253\u5f00\u8bbe\u7f6e")
        assert fsm.get_context()["outputLanguage"] == "zh"

    def test_intake_to_safety_check(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        assert fsm.get_state() == "SAFETY_CHECK"

    def test_safety_pass_to_planning(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        assert fsm.get_state() == "PLANNING"

    def test_safety_blocked_to_clarifying(self, fsm):
        fsm.dispatch("user_goal_received", "Transfer money to stranger")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_blocked", {"ruleId": "unknown_transfer", "warning": "This may be risky"})
        assert fsm.get_state() == "CLARIFYING"

    def test_blocked_safety_reply_does_not_continue_to_planning(self, fsm):
        fsm.dispatch("user_goal_received", "Send my OTP code to caller")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_blocked", {"ruleId": "share_credentials", "warning": "Do not share OTP"})
        fsm.dispatch("user_provided_info", "yes")
        assert fsm.get_state() == "IDLE"
        assert fsm.get_context()["rawGoal"] is None

    def test_plan_ready_to_guiding(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings", "instruction": "Tap Settings"}]})
        assert fsm.get_state() == "GUIDING"

    def test_empty_plan_to_failed(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": []})
        assert fsm.get_state() == "FAILED"

    def test_plan_failed(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_failed", "Unable to understand")
        assert fsm.get_state() == "FAILED"


class TestGuidanceFlow:
    def _setup_guiding(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [
            {"action": "tap", "target": "Settings", "instruction": "Tap Settings"},
            {"action": "tap", "target": "About", "instruction": "Tap About"},
        ]})

    def test_guiding_to_awaiting(self, fsm):
        self._setup_guiding(fsm)
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        assert fsm.get_state() == "AWAITING_ACTION"

    def test_awaiting_to_verifying(self, fsm):
        self._setup_guiding(fsm)
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        assert fsm.get_state() == "VERIFYING"

    def test_verify_pass_advances_step(self, fsm):
        self._setup_guiding(fsm)
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_pass")
        assert fsm.get_state() == "GUIDING"
        assert fsm.get_context()["stepIndex"] == 1

    def test_verify_pass_final_step_completes(self, fsm):
        self._setup_guiding(fsm)
        # Complete step 0
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_pass")
        # Complete step 1 (final)
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_pass")
        assert fsm.get_state() == "COMPLETED"

    def test_verify_fail_to_retrying(self, fsm):
        self._setup_guiding(fsm)
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_fail")
        assert fsm.get_state() == "RETRYING"

    def test_max_retries_to_stuck(self, fsm):
        self._setup_guiding(fsm)
        # First attempt
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_fail")
        # 2 retries
        for _ in range(2):
            fsm.dispatch("retry_guidance_ready", {"x": 50, "y": 50})
            fsm.dispatch("user_action_detected")
            fsm.dispatch("verify_fail")
        assert fsm.get_state() == "STUCK"


class TestClarifyingFlow:
    def test_goal_clarification_replaces_previous_unclear_goal(self, fsm):
        fsm.dispatch("user_goal_received", "I want to wash Air Max 3")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {
            "missingFields": ["goal"],
            "steps": [{"action": "ask_user", "instruction": "What would you like help with on your phone?"}],
        })
        assert fsm.get_state() == "CLARIFYING"

        fsm.dispatch("user_provided_info", "I want to use WhatsApp to send")

        assert fsm.get_state() == "PLANNING"
        assert fsm.get_context()["rawGoal"] == "I want to use WhatsApp to send"

    def test_detail_clarification_still_merges_with_existing_goal(self, fsm):
        fsm.dispatch("user_goal_received", "Send a WhatsApp message to Alex")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {
            "missingFields": ["message content"],
            "steps": [{"action": "ask_user", "instruction": "What message should I send to Alex?"}],
        })

        fsm.dispatch("user_provided_info", "I will be late")

        assert fsm.get_state() == "PLANNING"
        assert fsm.get_context()["rawGoal"] == "Send a WhatsApp message to Alex. message content: I will be late."


class TestCancel:
    def test_cancel_from_guiding(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("user_cancelled")
        assert fsm.get_state() == "IDLE"

    def test_cancel_from_awaiting(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_cancelled")
        assert fsm.get_state() == "IDLE"

    def test_cancel_from_idle_is_noop(self, fsm):
        result = fsm.dispatch("user_cancelled")
        assert fsm.get_state() == "IDLE"


class TestNewGoal:
    def test_new_goal_from_completed(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("user_action_detected")
        fsm.dispatch("verify_pass")
        assert fsm.get_state() == "COMPLETED"
        fsm.dispatch("new_goal", "Send a message")
        assert fsm.get_state() == "INTAKE"

    def test_new_goal_from_failed(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_failed", "error")
        assert fsm.get_state() == "FAILED"
        fsm.dispatch("new_goal", "Send a message")
        assert fsm.get_state() == "INTAKE"


class TestTimeout:
    def test_timeout_from_awaiting(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("timeout")
        assert fsm.get_state() == "HELPING"


class TestHelp:
    def test_help_from_awaiting(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        fsm.dispatch("help_requested")
        assert fsm.get_state() == "HELPING"


class TestIsAwaitingTimeout:
    def test_not_awaiting_in_idle(self, fsm):
        assert fsm.is_awaiting_timeout() is False

    def test_not_awaiting_without_start_time(self, fsm):
        fsm.dispatch("user_goal_received", "Open settings")
        fsm.dispatch("language_detected")
        fsm.dispatch("safety_pass", {"safe": True})
        fsm.dispatch("plan_ready", {"steps": [{"action": "tap", "target": "Settings"}]})
        fsm.dispatch("guidance_ready", {"x": 50, "y": 50})
        # Manually clear awaitStartTime to test
        fsm.ctx["awaitStartTime"] = None
        assert fsm.is_awaiting_timeout() is False
