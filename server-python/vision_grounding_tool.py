"""
VisionGroundingTool — encapsulates Gemini Vision calls for screenshot grounding
and verification.

Extracted from agent.py (Phase 1 of the multi-agent ReAct migration). Behavior is
unchanged: same HIGHLIGHT_TOOL schema, same grid-coordinate hint, same debug
artifact writing, same text fallback. Accessibility fast-paths still live in
task_executor.py for now; they will be absorbed in Phase 3.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import time
from typing import Optional

from google.genai import types as genai_types

logger = logging.getLogger("smarthelp.vision")


def _clamp(value, lo: int = 0, hi: int = 100) -> int:
    try:
        n = round(float(value))
    except (TypeError, ValueError):
        return 50
    return max(lo, min(hi, n))


def _clamp_optional(value, lo: int = 0, hi: int = 100) -> Optional[int]:
    if value is None:
        return None
    try:
        n = round(float(value))
    except (TypeError, ValueError):
        return None
    return max(lo, min(hi, n))


HIGHLIGHT_TOOL = genai_types.Tool(
    function_declarations=[
        genai_types.FunctionDeclaration(
            name="highlightElement",
            description="Call this to highlight a UI element on the screenshot. You MUST call this function on every turn. Fill ALL required fields including the description fields — they force you to actually look at the image instead of guessing.",
            parameters=genai_types.Schema(
                type="OBJECT",
                properties={
                    "screen_summary": genai_types.Schema(type="STRING", description="REQUIRED. Describe what is actually visible on the screenshot. For an app launcher / home screen: list each row of icons top-to-bottom. For a WhatsApp / messaging chat list: enumerate EVERY visible chat row in order, including its full label and any kinship suffix like '(Son)' or '(Mom)'. Do NOT stop after the first 2-3 entries — list all of them so the Navigation ReAct Agent can detect a recipient that may be further down the list. Example A (home): 'Home screen with 3 rows of icons. Row 1: 文件管理, WPS, 日历, 时钟. Row 2: 小米创作, AI字幕, 设备加速. Row 3: WhatsApp (green icon), 微信, 抖音.' Example B (chat list): 'WhatsApp chats. Row 1: Cy.. Row 2: McDonald\\'s Malaysia. Row 3: SPX Express. Row 4: +60 11-6534 8153. Row 5: +60 16-879 2022. Row 6: Wen (Son). Row 7: UTS NETBALL CLUB.'"),
                    "target_found": genai_types.Schema(type="BOOLEAN", description="REQUIRED. True only if the TARGET element is actually visible somewhere on the screenshot."),
                    "target_location_text": genai_types.Schema(type="STRING", description="REQUIRED if target_found=true. Describe in words WHERE on screen the target is, e.g. 'third row, first column' or 'bottom dock, rightmost icon'. If target_found=false, say which direction to scroll."),
                    "x": genai_types.Schema(type="NUMBER", description="Clickable center x as percentage 0-100 of the full attached screenshot. Must align with target_location_text."),
                    "y": genai_types.Schema(type="NUMBER", description="Clickable center y as percentage 0-100 of the full attached screenshot. Must align with target_location_text. Verify: at the (x,y) percentage, is there actually an icon, or empty wallpaper? If empty, you guessed wrong — fix it."),
                    "x1": genai_types.Schema(type="NUMBER", description="Left edge of a tight bounding box around the tappable UI element, as percentage 0-100"),
                    "y1": genai_types.Schema(type="NUMBER", description="Top edge of a tight bounding box around the tappable UI element, as percentage 0-100"),
                    "x2": genai_types.Schema(type="NUMBER", description="Right edge of a tight bounding box around the tappable UI element, as percentage 0-100"),
                    "y2": genai_types.Schema(type="NUMBER", description="Bottom edge of a tight bounding box around the tappable UI element, as percentage 0-100"),
                    "completed": genai_types.Schema(type="BOOLEAN", description="True if the step at decided_step_index is done. When true, the executor advances past that step."),
                    "blockerDetected": genai_types.Schema(type="BOOLEAN", description="True if a popup blocks the step"),
                    "blockerReason": genai_types.Schema(type="STRING", description="What the blocker is"),
                    "direction": genai_types.Schema(type="STRING", description="up, down, left, or right. Only set when target_found=false."),
                    "decided_step_index": genai_types.Schema(
                        type="NUMBER",
                        description=(
                            "0-based index of the plan step the CURRENT SCREEN corresponds to. "
                            "Look at PLAN_STEPS and CURRENT_STEP_INDEX in the context. "
                            "If the screen shows that a LATER step's target is already reachable "
                            "(e.g., the contact you need is already visible in the chat list, or "
                            "the photo to send is already selected), set this to that later step's "
                            "index and highlight ITS target — do not waste the user's time on intermediate steps. "
                            "If the screen looks like the current step, set this to CURRENT_STEP_INDEX. "
                            "NEVER go backward: decided_step_index must be >= CURRENT_STEP_INDEX. "
                            "If unsure, set to CURRENT_STEP_INDEX."
                        ),
                    ),
                },
                required=["screen_summary", "target_found", "target_location_text", "x", "y", "completed", "decided_step_index"],
            ),
        )
    ]
)


GRID_HINT = (
    "\n\nCOORDINATE GROUNDING (think carefully before answering):\n"
    "- The screenshot is divided into a 0-100 grid for x (left to right) and y (top to bottom).\n"
    "- y=0 is the top status bar, y=50 is the vertical center, y=100 is the bottom navigation bar.\n"
    "- Most home-screen app grids occupy roughly y=8 to y=55. The dock bar (if visible) sits around y=88-96.\n"
    "- For a 4-column app grid, columns are at x≈18, 38, 58, 78.\n"
    "- For a 3-row app grid, rows are at y≈18, 32, 46. Add ~14 per extra row.\n"
    "- BEFORE finalizing x and y, mentally count: which row from the top is the target? (1, 2, 3...) Which column from the left? (1, 2, 3, 4...)\n"
    "- Cross-check: is your y value placed ON an icon in the screenshot, or in empty wallpaper / between rows? If empty, your y is wrong — re-estimate.\n"
)


class VisionGroundingTool:
    """Gemini Vision-backed screenshot grounding + verification.

    Single public coroutine: `ground(image_b64, prompt, mode)`. Returns a dict
    matching the highlightElement tool-call shape, or None on failure.
    """

    def __init__(
        self,
        gemini_client,
        *,
        primary_model: str,
        verify_model: str,
        session_id: str,
    ) -> None:
        self._gemini_client = gemini_client
        self._primary_model = primary_model
        self._verify_model = verify_model
        self.session_id = session_id

    async def ground(self, image_b64: str, prompt: str, mode: str | None = None) -> dict | None:
        try:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, self._call_sync, image_b64, prompt, mode)
        except Exception as e:
            logger.error("Gemini vision call failed: %s", e)
            return None

    def _call_sync(self, image_b64: str, prompt: str, mode: str | None) -> dict | None:
        try:
            raw = base64.b64decode(image_b64)
            image_part = genai_types.Part(inline_data=genai_types.Blob(
                mime_type="image/jpeg", data=raw))
            model = self._verify_model if (mode or "").upper() in ("VERIFY", "RETRY") else self._primary_model
            tool_config = genai_types.ToolConfig(
                function_calling_config=genai_types.FunctionCallingConfig(
                    mode="ANY",
                    allowed_function_names=["highlightElement"],
                )
            )
            # Gemini 3 models require temperature=1.0 to avoid looping/degraded
            # reasoning (see https://ai.google.dev/gemini-api/docs/prompting-strategies).
            # Gemini 2.5 and earlier benefit from low temperature for grounding.
            is_gemini3 = "gemini-3" in (model or "").lower()
            chosen_temperature = 1.0 if is_gemini3 else 0.1
            # Place GRID_HINT BEFORE the prompt: behavioural constraints belong at
            # the start of the user prompt per Gemini 3 best practices, and the
            # anchor sentence ("Based on the context above…") should remain the
            # last thing the model reads.
            user_text = GRID_HINT.strip() + "\n\n" + prompt
            logger.info(
                "Gemini vision request: model=%s mode=%s image_bytes=%d temperature=%.2f",
                model, mode or "GUIDE", len(raw), chosen_temperature,
            )
            response = self._gemini_client.models.generate_content(
                model=model,
                contents=[image_part, user_text],
                config=genai_types.GenerateContentConfig(
                    tools=[HIGHLIGHT_TOOL],
                    tool_config=tool_config,
                    temperature=chosen_temperature,
                    thinking_config=genai_types.ThinkingConfig(thinking_budget=2048),
                ),
            )
            candidates = getattr(response, "candidates", None) or []
            for cand in candidates:
                content = getattr(cand, "content", None)
                parts = getattr(content, "parts", None) or []
                for part in parts:
                    fc = getattr(part, "function_call", None)
                    if fc and getattr(fc, "name", "") == "highlightElement":
                        args = dict(fc.args) if fc.args else {}
                        result = {
                            "x": _clamp(args.get("x", 50)),
                            "y": _clamp(args.get("y", 50)),
                            "x1": _clamp_optional(args.get("x1")),
                            "y1": _clamp_optional(args.get("y1")),
                            "x2": _clamp_optional(args.get("x2")),
                            "y2": _clamp_optional(args.get("y2")),
                            "completed": bool(args.get("completed", False)),
                            "blockerDetected": bool(args.get("blockerDetected", False)),
                            "blockerReason": args.get("blockerReason"),
                            "direction": args.get("direction"),
                            "screen_summary": args.get("screen_summary"),
                            "target_found": args.get("target_found"),
                            "target_location_text": args.get("target_location_text"),
                        }
                        self._write_debug_artifact(raw, result)
                        logger.info("Gemini vision tool call: x=%s y=%s bbox=%s,%s,%s,%s completed=%s dir=%s",
                                    result["x"], result["y"], result.get("x1"), result.get("y1"),
                                    result.get("x2"), result.get("y2"), result["completed"], result.get("direction"))
                        missing_grounding = [k for k in ("screen_summary", "target_found", "target_location_text") if not args.get(k) and args.get(k) is not False]
                        if missing_grounding:
                            logger.warning("Vision tool call missing grounding fields: %s — coordinates may be unreliable", missing_grounding)
                        if args.get("screen_summary"):
                            logger.info("  Vision SAW: %s", str(args.get("screen_summary"))[:300])
                        if args.get("target_location_text"):
                            logger.info("  Vision SAYS target at: %s (found=%s)", args.get("target_location_text"), args.get("target_found"))
                        return result
            text = getattr(response, "text", "") or ""
            logger.warning(
                "Gemini vision returned NO tool call — falling back to text parse. "
                "screen_summary/target_found/bbox will be missing. Text head: %s",
                text[:200],
            )
            return _parse_text_fallback(text)
        except Exception as e:
            logger.error("Gemini vision sync error: %s", e)
            return None

    def _write_debug_artifact(self, image_bytes: bytes, result: dict) -> None:
        if os.environ.get("SMARTHELP_VISION_DEBUG", "1").lower() in ("0", "false", "no"):
            return
        try:
            debug_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "debug", "vision", self.session_id)
            os.makedirs(debug_dir, exist_ok=True)
            stamp = str(int(time.time() * 1000))
            image_path = os.path.join(debug_dir, f"{stamp}.jpg")
            json_path = os.path.join(debug_dir, f"{stamp}.json")
            with open(image_path, "wb") as f:
                f.write(image_bytes)
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            _write_debug_html(image_bytes, result, os.path.join(debug_dir, f"{stamp}.html"))
            _write_annotated_debug_image(image_bytes, result, os.path.join(debug_dir, f"{stamp}.annotated.jpg"))
        except Exception as e:
            logger.debug("Vision debug artifact write failed: %s", e)


def _write_debug_html(image_bytes: bytes, result: dict, output_path: str) -> None:
    image_data = base64.b64encode(image_bytes).decode("ascii")
    x = _clamp(result.get("x", 50))
    y = _clamp(result.get("y", 50))
    bbox = ""
    if all(result.get(k) is not None for k in ("x1", "y1", "x2", "y2")):
        x1 = _clamp(result.get("x1"))
        y1 = _clamp(result.get("y1"))
        x2 = _clamp(result.get("x2"))
        y2 = _clamp(result.get("y2"))
        left = min(x1, x2)
        top = min(y1, y2)
        width = abs(x2 - x1)
        height = abs(y2 - y1)
        bbox = (
            f'<div class="bbox" style="left:{left}%;top:{top}%;'
            f'width:{width}%;height:{height}%;"></div>'
        )
    html = f"""<!doctype html>
<meta charset="utf-8">
<style>
body {{ margin: 0; background: #111; }}
.wrap {{ position: relative; display: inline-block; }}
img {{ display: block; max-width: 100vw; height: auto; }}
.dot {{ position: absolute; left: {x}%; top: {y}%; width: 18px; height: 18px;
  margin-left: -9px; margin-top: -9px; border-radius: 50%; background: #f00;
  outline: 4px solid #fff; box-shadow: 0 0 0 3px #f90; }}
.cross-x, .cross-y {{ position: absolute; background: #f00; pointer-events: none; }}
.cross-x {{ left: calc({x}% - 22px); top: calc({y}% - 1px); width: 44px; height: 2px; }}
.cross-y {{ left: calc({x}% - 1px); top: calc({y}% - 22px); width: 2px; height: 44px; }}
.bbox {{ position: absolute; border: 3px solid #ff9800; box-sizing: border-box; }}
</style>
<div class="wrap">
  <img src="data:image/jpeg;base64,{image_data}">
  {bbox}
  <div class="cross-x"></div><div class="cross-y"></div><div class="dot"></div>
</div>
"""
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html)


def _write_annotated_debug_image(image_bytes: bytes, result: dict, output_path: str) -> None:
    try:
        from io import BytesIO
        from PIL import Image, ImageDraw
    except Exception:
        return
    try:
        image = Image.open(BytesIO(image_bytes)).convert("RGB")
        draw = ImageDraw.Draw(image)
        w, h = image.size
        cx = int(w * _clamp(result.get("x", 50)) / 100)
        cy = int(h * _clamp(result.get("y", 50)) / 100)
        draw.ellipse((cx - 10, cy - 10, cx + 10, cy + 10), outline=(255, 0, 0), width=4)
        draw.line((cx - 18, cy, cx + 18, cy), fill=(255, 0, 0), width=3)
        draw.line((cx, cy - 18, cx, cy + 18), fill=(255, 0, 0), width=3)
        bbox_values = [result.get("x1"), result.get("y1"), result.get("x2"), result.get("y2")]
        if all(v is not None for v in bbox_values):
            x1 = int(w * _clamp(result.get("x1")) / 100)
            y1 = int(h * _clamp(result.get("y1")) / 100)
            x2 = int(w * _clamp(result.get("x2")) / 100)
            y2 = int(h * _clamp(result.get("y2")) / 100)
            draw.rectangle((min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)), outline=(255, 128, 0), width=4)
        image.save(output_path, quality=90)
    except Exception:
        return


def _parse_text_fallback(text: str) -> dict | None:
    """Try to extract x,y coordinates from a text response as a last resort."""
    x_match = re.search(r'x\s*[:=]\s*(\d+)', text, re.I)
    y_match = re.search(r'y\s*[:=]\s*(\d+)', text, re.I)
    if x_match and y_match:
        result = {
            "x": _clamp(x_match.group(1)),
            "y": _clamp(y_match.group(1)),
            "completed": bool(re.search(r'completed\s*[:=]\s*true', text, re.I)),
            "blockerDetected": False,
            "blockerReason": None,
            "direction": None,
        }
        logger.info("Parsed text fallback: x=%s y=%s", result["x"], result["y"])
        return result
    return None
