"""Tests for VisionGroundingTool.

We stub the gemini_client.models.generate_content so the tool runs end-to-end
without hitting Gemini. We assert the schema mapping, the tool-config used,
and the text-fallback path.
"""

import asyncio
import base64

import pytest

from vision_grounding_tool import (
    HIGHLIGHT_TOOL,
    VisionGroundingTool,
    _parse_text_fallback,
)


class _FakePart:
    def __init__(self, *, function_call=None):
        self.function_call = function_call


class _FakeFunctionCall:
    def __init__(self, name, args):
        self.name = name
        self.args = args


class _FakeContent:
    def __init__(self, parts):
        self.parts = parts


class _FakeCandidate:
    def __init__(self, content):
        self.content = content


class _FakeResponse:
    def __init__(self, *, candidates=None, text=""):
        self.candidates = candidates or []
        self.text = text


class _FakeModels:
    def __init__(self, response):
        self.response = response
        self.last_args = None

    def generate_content(self, **kwargs):
        self.last_args = kwargs
        return self.response


class _FakeClient:
    def __init__(self, response):
        self.models = _FakeModels(response)


@pytest.fixture(autouse=True)
def _disable_debug_artifacts(monkeypatch):
    monkeypatch.setenv("SMARTHELP_VISION_DEBUG", "0")


@pytest.fixture
def tiny_jpeg_b64():
    # A 1x1 white JPEG, deterministic and tiny.
    raw = (
        b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
        b"\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r"
        b"\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c"
        b"\x1c(7),01444\x1f'9=82<.342\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00"
        b"\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00"
        b"\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xc4\x00\xb5\x10\x00\x02\x01"
        b"\x03\x03\x02\x04\x03\x05\x05\x04\x04\x00\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12"
        b"!1A\x06\x13Qa\x07\"q\x142\x81\x91\xa1\x08#B\xb1\xc1\x15R\xd1\xf0$3br\x82\t\n\x16"
        b"\x17\x18\x19\x1a%&'()*456789:CDEFGHIJSTUVWXYZcdefghijstuvwxyz\x83\x84\x85\x86\x87"
        b"\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9"
        b"\xaa\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd2"
        b"\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf1\xf2"
        b"\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xfb\xd0\xff\xd9"
    )
    return base64.b64encode(raw).decode("ascii")


def test_highlight_tool_has_required_fields():
    decl = HIGHLIGHT_TOOL.function_declarations[0]
    assert decl.name == "highlightElement"
    required = list(decl.parameters.required)
    for must in ("screen_summary", "target_found", "target_location_text", "x", "y", "completed"):
        assert must in required


def test_ground_returns_normalized_dict(tiny_jpeg_b64):
    args = {
        "x": 42, "y": 88,
        "x1": 30, "y1": 80, "x2": 55, "y2": 96,
        "completed": False,
        "blockerDetected": False,
        "blockerReason": None,
        "direction": None,
        "screen_summary": "Home screen",
        "target_found": True,
        "target_location_text": "bottom dock",
    }
    fake_response = _FakeResponse(candidates=[
        _FakeCandidate(_FakeContent([_FakePart(function_call=_FakeFunctionCall("highlightElement", args))]))
    ])
    client = _FakeClient(fake_response)
    tool = VisionGroundingTool(client, primary_model="m", verify_model="v", session_id="t")

    result = asyncio.run(tool.ground(tiny_jpeg_b64, "find the WhatsApp icon", mode="NAVIGATE"))
    assert result is not None
    assert result["x"] == 42 and result["y"] == 88
    assert result["x1"] == 30 and result["x2"] == 55
    assert result["target_found"] is True
    assert result["screen_summary"] == "Home screen"


def test_ground_clamps_out_of_range_coords(tiny_jpeg_b64):
    args = {"x": 150, "y": -10, "completed": False, "screen_summary": "s", "target_found": True, "target_location_text": "t"}
    fake = _FakeResponse(candidates=[
        _FakeCandidate(_FakeContent([_FakePart(function_call=_FakeFunctionCall("highlightElement", args))]))
    ])
    tool = VisionGroundingTool(_FakeClient(fake), primary_model="m", verify_model="v", session_id="t")
    result = asyncio.run(tool.ground(tiny_jpeg_b64, "p", mode=None))
    assert 0 <= result["x"] <= 100
    assert 0 <= result["y"] <= 100


def test_ground_uses_verify_model_for_verify_mode(tiny_jpeg_b64):
    args = {"x": 50, "y": 50, "completed": True, "screen_summary": "s", "target_found": True, "target_location_text": "t"}
    fake = _FakeResponse(candidates=[
        _FakeCandidate(_FakeContent([_FakePart(function_call=_FakeFunctionCall("highlightElement", args))]))
    ])
    client = _FakeClient(fake)
    tool = VisionGroundingTool(client, primary_model="primary-m", verify_model="verify-m", session_id="t")
    asyncio.run(tool.ground(tiny_jpeg_b64, "p", mode="VERIFY"))
    assert client.models.last_args["model"] == "verify-m"

    asyncio.run(tool.ground(tiny_jpeg_b64, "p", mode="NAVIGATE"))
    assert client.models.last_args["model"] == "primary-m"


def test_ground_falls_back_to_text_parse(tiny_jpeg_b64):
    fake = _FakeResponse(candidates=[], text="x: 30 y: 70 completed: true")
    tool = VisionGroundingTool(_FakeClient(fake), primary_model="m", verify_model="v", session_id="t")
    result = asyncio.run(tool.ground(tiny_jpeg_b64, "p", mode="NAVIGATE"))
    assert result is not None
    assert result["x"] == 30 and result["y"] == 70
    assert result["completed"] is True


def test_parse_text_fallback_returns_none_when_no_coords():
    assert _parse_text_fallback("hello world") is None
