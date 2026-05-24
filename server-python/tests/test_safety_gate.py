"""Tests for SafetyGate — scam pattern detection and safety checks."""

import pytest
from safety_gate import SafetyGate


@pytest.fixture
def gate():
    return SafetyGate()


class TestSafeGoals:
    def test_empty_goal(self, gate):
        result = gate.check("")
        assert result["safe"] is True
        assert result["ruleId"] is None

    def test_none_goal(self, gate):
        result = gate.check(None)
        assert result["safe"] is True

    def test_normal_goal(self, gate):
        result = gate.check("Open the settings app", "en")
        assert result["safe"] is True
        assert result["needsConfirm"] is False

    def test_normal_goal_chinese(self, gate):
        result = gate.check("帮我打开设置", "zh")
        assert result["safe"] is True

    def test_normal_goal_malay(self, gate):
        result = gate.check("Buka aplikasi mesej", "ms")
        assert result["safe"] is True


class TestBlockedPatterns:
    def test_impersonation_en(self, gate):
        result = gate.check("The police told me to transfer money to a safe account", "en")
        assert result["safe"] is False
        assert result["needsConfirm"] is False
        assert result["ruleId"] == "impersonation"

    def test_remote_control_en(self, gate):
        result = gate.check("Install AnyDesk for remote control", "en")
        assert result["safe"] is False
        assert result["ruleId"] == "remote_control"

    def test_share_credentials_en(self, gate):
        result = gate.check("Send the OTP code to the caller", "en")
        assert result["safe"] is False
        assert result["ruleId"] == "share_credentials"

    def test_unknown_transfer_en(self, gate):
        result = gate.check("Transfer money to a stranger", "en")
        assert result["safe"] is False
        assert result["ruleId"] == "unknown_transfer"

    def test_impersonation_zh(self, gate):
        result = gate.check("警察让我转账到安全账户", "zh")
        assert result["safe"] is False
        assert result["ruleId"] == "impersonation"

    def test_share_credentials_zh(self, gate):
        result = gate.check("把验证码发给对方", "zh")
        assert result["safe"] is False
        assert result["ruleId"] == "share_credentials"

    def test_remote_control_ms(self, gate):
        result = gate.check("Pasang TeamViewer untuk kawalan jauh", "ms")
        assert result["safe"] is False
        assert result["ruleId"] == "remote_control"

    def test_blocked_has_warning(self, gate):
        result = gate.check("Install AnyDesk for remote control", "en")
        assert "warning" in result
        assert len(result["warning"]) > 0


class TestConfirmPatterns:
    def test_money_transfer_en(self, gate):
        result = gate.check("I want to send money to my friend", "en")
        assert result["safe"] is False
        assert result["needsConfirm"] is True
        assert result["ruleId"] == "money_transfer"

    def test_qr_scan_en(self, gate):
        result = gate.check("Scan this QR code", "en")
        assert result["safe"] is False
        assert result["needsConfirm"] is True
        assert result["ruleId"] == "qr_scan"

    def test_app_install_en(self, gate):
        result = gate.check("Install this new app", "en")
        assert result["safe"] is False
        assert result["needsConfirm"] is True
        assert result["ruleId"] == "app_install"

    def test_delete_action_en(self, gate):
        result = gate.check("Delete all my photos", "en")
        assert result["safe"] is False
        assert result["needsConfirm"] is True
        assert result["ruleId"] == "delete_action"

    def test_confirm_has_question(self, gate):
        result = gate.check("Transfer money to my friend", "en")
        assert "question" in result
        assert len(result["question"]) > 0


class TestSafetyCheckRequests:
    def test_is_scam_check_en(self, gate):
        result = gate.check("Is this a scam?", "en")
        assert result["safe"] is True
        assert result["isSafetyCheckRequest"] is True

    def test_check_suspicious_en(self, gate):
        result = gate.check("Check if this link is safe", "en")
        assert result["safe"] is True
        assert result["isSafetyCheckRequest"] is True

    def test_is_scam_check_zh(self, gate):
        result = gate.check("这是不是诈骗？", "zh")
        assert result["safe"] is True
        assert result["isSafetyCheckRequest"] is True

    def test_safety_check_ms(self, gate):
        result = gate.check("Betul ke ini scam?", "ms")
        assert result["safe"] is True
        assert result["isSafetyCheckRequest"] is True


class TestSensitiveScreen:
    def test_returns_pause_action(self, gate):
        result = gate.handle_sensitive_screen("com.bank.app", "en")
        assert result["action"] == "pause"
        assert result["appPackage"] == "com.bank.app"
        assert "message" in result

    def test_multilingual_messages(self, gate):
        for lang in ("zh", "en", "ms"):
            result = gate.handle_sensitive_screen("com.bank.app", lang)
            assert result["action"] == "pause"
            assert len(result[f"message_{lang}"]) > 0
