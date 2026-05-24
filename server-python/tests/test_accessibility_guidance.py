"""Tests for accessibility-assisted guidance routing."""

from prompt_registry import PromptRegistry
from task_executor import TaskExecutor
from task_state_machine import TaskStateMachine


def test_prompt_includes_accessibility_controls():
    registry = PromptRegistry()
    ctx = {
        "rawGoal": "Send WhatsApp message",
        "outputLanguage": "en",
        "plan": {"steps": []},
        "stepIndex": 0,
    }
    step = {
        "instruction": "Tap WhatsApp",
        "action": "tap",
        "target": "WhatsApp",
        "matchHints": [],
        "avoidHints": [],
        "expectedResult": "WhatsApp chat list is visible",
    }
    prompt = registry.build_guide_prompt(
        "VERIFY",
        ctx,
        step,
        {
            "accessibility": [
                {
                    "label": "Agree and continue",
                    "className": "android.widget.Button",
                    "clickable": True,
                    "enabled": True,
                    "x": 50,
                    "y": 88,
                    "x1": 20,
                    "y1": 84,
                    "x2": 80,
                    "y2": 92,
                }
            ]
        },
    )

    assert "ACCESSIBILITY_CONTROLS:" in prompt
    assert "Agree and continue" in prompt
    assert "center=50,88" in prompt


def test_whatsapp_onboarding_uses_accessibility_button_before_model():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    step = {
        "instruction": "Tap WhatsApp",
        "action": "tap",
        "target": "WhatsApp",
        "matchHints": ["green icon"],
        "avoidHints": [],
        "expectedResult": "WhatsApp chat list is visible",
    }
    packet = {
        "meta": {
            "accessibility": [
                {
                    "label": "同意并继续",
                    "className": "android.widget.Button",
                    "clickable": True,
                    "enabled": True,
                    "x": 50,
                    "y": 87,
                    "x1": 18,
                    "y1": 83,
                    "x2": 82,
                    "y2": 91,
                }
            ]
        }
    }

    known = executor.fast_path.resolve_pre_vision("VERIFY", {"outputLanguage": "zh"}, step, packet, None)

    assert known is not None
    assert known["key"] == "whatsapp_onboarding"
    assert known["x"] == 50
    assert known["y"] == 87
    assert known["blockerDetected"] is True
    assert known["target"] == "同意并继续"
    assert known["text"] == "点同意并继续"


def test_phone_text_size_settings_does_not_use_smarthelp_settings_shortcut():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    ctx = {
        "rawGoal": "Help me increase phone text size",
        "outputLanguage": "en",
    }
    step = {
        "instruction": "Tap Settings.",
        "action": "tap",
        "target": "Settings",
        "matchHints": ["Settings app", "gear icon"],
        "avoidHints": ["SmartHelp settings"],
        "expectedResult": "Android Settings opens.",
    }

    known = executor.fast_path.resolve_pre_vision("NAVIGATE", ctx, step, {"meta": {}}, None)

    assert known is None


def test_smarthelp_settings_shortcut_still_handles_explicit_app_settings():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    ctx = {
        "rawGoal": "Open SmartHelp settings",
        "outputLanguage": "en",
    }
    step = {
        "instruction": "Tap Settings.",
        "action": "tap",
        "target": "Settings",
        "matchHints": ["Settings", "gear"],
        "avoidHints": [],
        "expectedResult": "SmartHelp settings opens.",
    }

    known = executor.fast_path.resolve_pre_vision("NAVIGATE", ctx, step, {"meta": {}}, None)

    assert known is not None
    assert known["key"] == "smarthelp_home_settings"
    assert known["x"] == 11
    assert known["y"] == 9


def test_visible_whatsapp_contact_skips_search_step():
    tsm = TaskStateMachine("test-session")
    tsm.ctx["rawGoal"] = "I want to send a photo to my son using WhatsApp. recipient: cy."
    tsm.ctx["plan"] = {
        "task": "Send a photo to cy using WhatsApp",
        "steps": [
            {"instruction": "Tap WhatsApp", "action": "tap", "target": "WhatsApp", "expectedResult": "WhatsApp chat list is visible"},
            {"instruction": "Tap the search icon", "action": "tap", "target": "Search icon", "expectedResult": "WhatsApp search bar is active"},
            {"instruction": "Type cy", "action": "type", "target": "Search field", "expectedResult": "Search results show cy"},
            {"instruction": "Tap cy", "action": "tap", "target": "cy contact row", "expectedResult": "Chat with cy is open"},
        ],
    }
    tsm.ctx["stepIndex"] = 1
    executor = TaskExecutor(tsm, config={"intentAgent": object(), "reactMode": False})
    packet = {
        "meta": {
            "accessibility": [
                {
                    "label": "问问 Meta AI 或搜索",
                    "className": "android.widget.EditText",
                    "clickable": True,
                    "enabled": True,
                    "x": 50,
                    "y": 10,
                    "x1": 0,
                    "y1": 8,
                    "x2": 100,
                    "y2": 12,
                },
                {
                    "label": "Cy.",
                    "className": "android.view.ViewGroup",
                    "clickable": True,
                    "enabled": True,
                    "x": 49,
                    "y": 26,
                    "x1": 0,
                    "y1": 20,
                    "x2": 100,
                    "y2": 32,
                },
            ]
        }
    }

    known = executor.fast_path.resolve_pre_vision(
        "NAVIGATE", tsm.get_context(), tsm.get_current_step(), packet, tsm.ctx.get("plan")
    )
    # Step-index mutation now lives in the executor, not the fast-path.
    executor._apply_step_index_advance(known)

    assert known is not None
    assert known["key"] == "whatsapp_visible_contact"
    assert known["x"] == 49
    assert known["y"] == 26
    assert known["target"] == "cy contact row"
    assert known["text"] == "Tap cy"
    assert tsm.ctx["stepIndex"] == 3


def test_whatsapp_attachment_gallery_uses_accessibility_before_model():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    ctx = {
        "rawGoal": "I want to send a photo to my son using WhatsApp.",
        "outputLanguage": "en",
    }
    step = {
        "instruction": "Tap Gallery.",
        "action": "tap",
        "target": "Gallery",
        "matchHints": ["Gallery", "\u76f8\u518c"],
        "avoidHints": [],
        "expectedResult": "The phone gallery opens for photo selection.",
    }
    packet = {
        "meta": {
            "accessibility": [
                {
                    "label": "\u76f8\u518c",
                    "className": "android.widget.TextView",
                    "clickable": True,
                    "enabled": True,
                    "x": 13,
                    "y": 68,
                    "x1": 0,
                    "y1": 65,
                    "x2": 25,
                    "y2": 71,
                },
                {"label": "\u76f8\u673a", "enabled": True, "x": 38, "y": 68},
                {"label": "\u4f4d\u7f6e", "enabled": True, "x": 63, "y": 68},
                {"label": "\u8054\u7cfb\u4eba", "enabled": True, "x": 88, "y": 68},
            ]
        }
    }

    known = executor.fast_path.resolve_pre_vision("NAVIGATE", ctx, step, packet, None)

    assert known is not None
    assert known["key"] == "whatsapp_attachment_gallery"
    assert known["x"] == 13
    assert known["y"] == 68
    assert known["x1"] == 0
    assert known["y1"] == 65
    assert known["x2"] == 25
    assert known["y2"] == 71
    assert known["target"] == "Gallery"
    assert known["text"] == "Tap Gallery"
    assert known["blockerDetected"] is False


def test_whatsapp_attachment_gallery_does_not_match_contact_photo_view():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    ctx = {
        "rawGoal": "I want to send a photo to my son using WhatsApp.",
        "outputLanguage": "en",
    }
    step = {
        "instruction": "Choose the photo option.",
        "action": "tap",
        "target": "Photo",
        "matchHints": ["photo"],
        "avoidHints": [],
        "expectedResult": "The phone gallery opens for photo selection.",
    }
    packet = {
        "meta": {
            "accessibility": [
                {
                    "label": "Wen photo com.whatsapp:id/contact_photo_view",
                    "className": "android.view.View",
                    "clickable": True,
                    "enabled": True,
                    "x": 13,
                    "y": 8,
                },
                {"label": "\u76f8\u673a", "enabled": True, "x": 38, "y": 68},
                {"label": "\u4f4d\u7f6e", "enabled": True, "x": 63, "y": 68},
                {"label": "\u8054\u7cfb\u4eba", "enabled": True, "x": 88, "y": 68},
            ]
        }
    }

    known = executor.fast_path.resolve_pre_vision("NAVIGATE", ctx, step, packet, None)

    assert known is None


def test_whatsapp_attachment_gallery_does_not_match_gallery_spinner():
    executor = TaskExecutor(
        TaskStateMachine("test-session"),
        config={"intentAgent": object(), "reactMode": False},
    )
    ctx = {
        "rawGoal": "I want to send a photo to my son using WhatsApp.",
        "outputLanguage": "en",
    }
    step = {
        "instruction": "Tap Gallery.",
        "action": "tap",
        "target": "Gallery",
        "matchHints": ["Gallery"],
        "avoidHints": [],
        "expectedResult": "The phone gallery opens for photo selection.",
    }
    packet = {
        "meta": {
            "accessibility": [
                {
                    "label": "Recents com.whatsapp:id/gallery_spinner",
                    "className": "android.widget.TextView",
                    "clickable": True,
                    "enabled": True,
                    "x": 50,
                    "y": 46,
                    "x1": 21,
                    "y1": 43,
                    "x2": 79,
                    "y2": 48,
                },
                {"label": "Camera", "enabled": True, "x": 38, "y": 68},
                {"label": "Location", "enabled": True, "x": 63, "y": 68},
                {"label": "Contact", "enabled": True, "x": 88, "y": 68},
            ]
        }
    }

    known = executor.fast_path.resolve_pre_vision("NAVIGATE", ctx, step, packet, None)

    assert known is None
