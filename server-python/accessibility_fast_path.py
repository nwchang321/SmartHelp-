"""
AccessibilityFastPath — pure matching layer that resolves common UI targets
from the Android accessibility tree, so we can skip a Gemini Vision call when
the target is unambiguous.

Extracted from task_executor.py. The executor still owns FSM state mutation
(step-index advances, `last_known_target_key`) — this module only computes
matches and returns highlights.

Public API:
    fast_path = AccessibilityFastPath()
    highlight = fast_path.resolve_pre_vision(mode, ctx, step, packet, plan)
    override  = fast_path.resolve_post_vision_override(mode, request, result, step)
    done      = fast_path.should_complete_known_target(step, last_known_target_key)

When `resolve_pre_vision` returns a highlight with an `advance_to_step_index`
field, the caller must update its step pointer (and clear retry/blocker state)
to that index.
"""

from __future__ import annotations

import logging
import re
from typing import Any, Optional

logger = logging.getLogger("smarthelp.fast_path")


_WHATSAPP_FORWARD_ACTION_LABELS = (
    "同意并继续", "同意並繼續", "agree and continue", "agree & continue",
    "continue", "继续", "繼續", "allow", "允许", "允許", "next", "start",
)

_WHATSAPP_GALLERY_CONTROL_TERMS = (
    "gallery",
    "\u76f8\u518c",  # Simplified Chinese: album/gallery
    "\u76f8\u7c3f",  # Traditional Chinese: album/gallery
    "\u56fe\u5e93",  # Simplified Chinese: gallery
    "\u5716\u5eab",  # Traditional Chinese: gallery
)

_WHATSAPP_GENERIC_PHOTO_STEP_TERMS = (
    "photo",
    "photos",
    "picture",
    "pictures",
    "image",
    "images",
)

_WHATSAPP_ATTACHMENT_TRIGGER_TERMS = (
    "attachment",
    "attachments",
    "attach",
    "paperclip",
    "clip",
    "\u9644\u4ef6",
)

_WHATSAPP_ATTACHMENT_SHEET_MARKERS = (
    ("gallery", _WHATSAPP_GALLERY_CONTROL_TERMS),
    ("camera", ("camera", "\u76f8\u673a", "\u76f8\u6a5f")),
    ("location", ("location", "\u4f4d\u7f6e")),
    ("contact", ("contact", "contacts", "\u8054\u7cfb\u4eba", "\u806f\u7d61\u4eba")),
    ("document", ("document", "documents", "\u6587\u6863", "\u6587\u6a94", "\u6587\u4ef6")),
    ("poll", ("poll", "\u6295\u7968")),
    ("event", ("event", "\u6d3b\u52a8", "\u6d3b\u52d5")),
)


class AccessibilityFastPath:
    """Stateless matcher. Safe to share across sessions."""

    # ── Pre-vision matching ────────────────────────────────────────────────

    def resolve_pre_vision(
        self,
        mode: str,
        ctx: dict,
        step: dict | None,
        packet: dict | None = None,
        plan: dict | None = None,
    ) -> dict | None:
        if (
            mode in ("NAVIGATE", "RETRY")
            and self.is_smarthelp_settings_target(ctx, step)
            and not self.is_phone_settings_task(ctx, step)
        ):
            return {
                "key": "smarthelp_home_settings",
                "text": self.known_settings_instruction(ctx.get("outputLanguage")),
                "x": 11,
                "y": 9,
                "target": "Settings",
                "matchHints": ["Settings", "gear"],
            }

        if mode in ("NAVIGATE", "HELP", "RETRY"):
            visible_contact = self._resolve_visible_whatsapp_contact(ctx, step, packet, plan)
            if visible_contact:
                return visible_contact

            attachment_option = self._resolve_whatsapp_attachment_gallery(ctx, step, packet)
            if attachment_option:
                return attachment_option

        if mode in ("VERIFY", "RETRY") and self.is_whatsapp_open_step(step):
            controls = ((packet or {}).get("meta") or {}).get("accessibility") or []
            button = self.find_forward_action_control(controls)
            if button:
                highlight = self.control_to_highlight(button, "WhatsApp onboarding", step)
                highlight["key"] = "whatsapp_onboarding"
                highlight["text"] = self.known_button_instruction(ctx.get("outputLanguage"), button.get("label"))
                return highlight
        return None

    def resolve_post_vision_override(
        self,
        mode: str,
        request: dict | None,
        result: dict,
        step: dict | None,
    ) -> dict | None:
        if mode not in ("VERIFY", "RETRY"):
            return None
        if not self.is_whatsapp_open_step(step):
            return None

        controls = (((request or {}).get("screenshot") or {}).get("meta") or {}).get("accessibility") or []
        button = self.find_forward_action_control(controls)
        if button:
            return self.control_to_highlight(button, "WhatsApp onboarding", step)

        summary = " ".join(str(result.get(k) or "") for k in ("screen_summary", "target_location_text")).lower()
        if "welcome" in summary and "whatsapp" in summary and ("agree" in summary or "continue" in summary or "同意" in summary):
            logger.info("WhatsApp onboarding detected from vision summary but no accessibility button was available")
        return None

    def should_complete_known_target(self, step: dict | None, last_known_target_key: str | None) -> bool:
        return last_known_target_key == "smarthelp_home_settings" and self.is_smarthelp_settings_target({}, step)

    # ── WhatsApp visible-contact shortcut ──────────────────────────────────

    def _resolve_visible_whatsapp_contact(
        self,
        ctx: dict,
        step: dict | None,
        packet: dict | None,
        plan: dict | None,
    ) -> dict | None:
        if not self.is_whatsapp_search_step(ctx, step):
            return None

        controls = ((packet or {}).get("meta") or {}).get("accessibility") or []
        if not controls:
            return None

        plan = plan or {}
        steps = plan.get("steps") or []
        current_idx = int(ctx.get("stepIndex", 0) or 0)

        for idx in range(current_idx + 1, len(steps)):
            future_step = steps[idx]
            contact_name = self.extract_contact_name_from_step(future_step)
            if not contact_name:
                continue
            control = self.find_contact_control(controls, contact_name)
            if not control:
                continue

            highlight = self.control_to_highlight(control, f"visible WhatsApp contact {contact_name}", future_step)
            highlight["key"] = "whatsapp_visible_contact"
            highlight["target"] = future_step.get("target") or contact_name
            highlight["matchHints"] = future_step.get("matchHints") or [contact_name]
            highlight["blockerDetected"] = False
            highlight["blockerReason"] = None
            highlight["text"] = self.known_button_instruction(ctx.get("outputLanguage"), contact_name)
            highlight["advance_to_step_index"] = idx
            logger.info(
                "Skipping WhatsApp search; visible contact '%s' matched step %d -> %d via accessibility label=%r",
                contact_name,
                current_idx + 1,
                idx + 1,
                control.get("label"),
            )
            return highlight

        return None

    # ── WhatsApp attachment-sheet shortcut ─────────────────────────────────

    def _resolve_whatsapp_attachment_gallery(
        self,
        ctx: dict,
        step: dict | None,
        packet: dict | None,
    ) -> dict | None:
        if not self.is_whatsapp_attachment_gallery_step(ctx, step):
            return None

        controls = ((packet or {}).get("meta") or {}).get("accessibility") or []

        # PHOTO-GALLERY HAND-OFF: if the gallery picker is ALREADY open
        # (gallery_view_pager or photo grid markers visible), the user has
        # already advanced past "Tap Gallery". Do NOT fire fast_path again
        # — return None so the ReAct agent sees the picker, applies the
        # PHOTO-GALLERY HAND-OFF RULE, and emits status=completed with the
        # final "Pick a photo and tap Send" instruction.
        if self._whatsapp_gallery_picker_already_open(controls):
            logger.info(
                "Fast-path: WhatsApp gallery picker already open — yielding "
                "to ReAct for PHOTO-GALLERY HAND-OFF completion."
            )
            return None

        if not self.has_whatsapp_attachment_sheet_controls(controls):
            return None

        control = self.find_whatsapp_gallery_control(controls)
        if not control:
            return None

        highlight = self.control_to_highlight(control, "WhatsApp attachment sheet gallery", step)
        step_text = self.step_search_text(step)
        target = str((step or {}).get("target") or "Gallery").strip() or "Gallery"
        if not any(term in step_text for term in _WHATSAPP_GALLERY_CONTROL_TERMS):
            target = "Gallery"
        hints = list((step or {}).get("matchHints") or [])
        for hint in ("Gallery", "\u76f8\u518c", "\u56fe\u5e93"):
            if hint not in hints:
                hints.append(hint)

        highlight["key"] = "whatsapp_attachment_gallery"
        highlight["target"] = target
        highlight["matchHints"] = hints
        highlight["blockerDetected"] = False
        highlight["blockerReason"] = None
        highlight["text"] = self.known_button_instruction(ctx.get("outputLanguage"), target)
        logger.info(
            "Skipping vision for WhatsApp attachment gallery via accessibility label=%r",
            control.get("label"),
        )
        return highlight

    # ── Predicates ─────────────────────────────────────────────────────────

    def is_smarthelp_settings_target(self, ctx: dict, step: dict | None) -> bool:
        if not step:
            return False
        action = (step.get("action") or "").strip().lower()
        if action and action != "tap":
            return False
        target = (step.get("target") or "").strip().lower()
        parts = [ctx.get("rawGoal"), step.get("instruction"), step.get("target"), step.get("expectedResult")]
        parts += step.get("matchHints") or []
        text = " ".join(str(p) for p in parts if p).lower()
        return (
            target == "settings" or target == "设置"
            or "打开设置" in text or "开启设置" in text or "點擊設定" in text or "设置" in text
            or bool(re.search(r"\b(open|tap|press|select)\b.{0,24}\bsettings?\b", text))
        )

    def is_phone_settings_task(self, ctx: dict, step: dict | None) -> bool:
        parts = [
            ctx.get("rawGoal"),
            step.get("instruction") if step else None,
            step.get("target") if step else None,
            step.get("expectedResult") if step else None,
        ]
        if step:
            parts += step.get("matchHints") or []
        text = " ".join(str(p) for p in parts if p).lower()
        return bool(
            re.search(
                r"\b(phone|android|system)\s+settings?\b"
                r"|\b(text|font|display)\s+(size|settings?)\b"
                r"|\b(increase|make|change|adjust)\b.{0,24}\b(text|font|display)\b"
                r"|\b(text|font|display)\b.{0,24}\b(bigger|larger|large|small|size)\b",
                text,
            )
            or any(
                term in text
                for term in (
                    "\u5b57\u4f53",  # simplified Chinese: font
                    "\u5b57\u9ad4",  # traditional Chinese: font
                    "\u6587\u5b57",  # Chinese: text
                    "\u8c03\u5927",  # simplified Chinese: increase
                    "\u8abf\u5927",  # traditional Chinese: increase
                    "\u663e\u793a",  # simplified Chinese: display
                    "\u986f\u793a",  # traditional Chinese: display
                )
            )
        )

    def is_whatsapp_open_step(self, step: dict | None) -> bool:
        if not step:
            return False
        text = " ".join(str(p or "") for p in [
            step.get("action"),
            step.get("target"),
            step.get("instruction"),
            step.get("expectedResult"),
            *(step.get("matchHints") or []),
        ]).lower()
        return "whatsapp" in text and "chat list" in text

    def is_whatsapp_search_step(self, ctx: dict, step: dict | None) -> bool:
        if not step:
            return False
        goal_text = " ".join(str(p or "") for p in [
            ctx.get("rawGoal"),
            ((ctx.get("plan") or {}).get("task") if isinstance(ctx.get("plan"), dict) else None),
        ]).lower()
        if "whatsapp" not in goal_text:
            return False
        step_text = " ".join(str(p or "") for p in [
            step.get("action"),
            step.get("target"),
            step.get("instruction"),
            step.get("expectedResult"),
            *(step.get("matchHints") or []),
        ]).lower()
        return "search" in step_text or "搜索" in step_text

    def is_whatsapp_attachment_gallery_step(self, ctx: dict, step: dict | None) -> bool:
        if not step:
            return False
        action = (step.get("action") or "").strip().lower()
        if action and action != "tap":
            return False

        context_text = " ".join(str(p or "") for p in [
            ctx.get("rawGoal"),
            ((ctx.get("plan") or {}).get("task") if isinstance(ctx.get("plan"), dict) else None),
        ]).lower()
        step_text = self.step_search_text(step)
        combined = f"{context_text} {step_text}"
        has_explicit_gallery = any(term in step_text for term in _WHATSAPP_GALLERY_CONTROL_TERMS)
        has_generic_photo_option = (
            any(term in step_text for term in _WHATSAPP_GENERIC_PHOTO_STEP_TERMS)
            and not any(term in step_text for term in _WHATSAPP_ATTACHMENT_TRIGGER_TERMS)
        )
        has_attachment_sheet_trigger = (
            any(term in step_text for term in _WHATSAPP_ATTACHMENT_TRIGGER_TERMS)
            and any(term in context_text for term in _WHATSAPP_GENERIC_PHOTO_STEP_TERMS)
        )
        return (
            "whatsapp" in combined
            and (has_explicit_gallery or has_generic_photo_option or has_attachment_sheet_trigger)
        )

    def extract_contact_name_from_step(self, step: dict | None) -> str | None:
        if not step:
            return None
        action = (step.get("action") or "").strip().lower()
        text = " ".join(str(p or "") for p in [
            step.get("target"),
            step.get("instruction"),
            step.get("expectedResult"),
            *(step.get("matchHints") or []),
        ])
        lowered = text.lower()
        if action and action != "tap":
            return None
        if not re.search(r"\b(contact|chat|conversation|row)\b|联系人|聯絡人", lowered):
            return None

        raw = str(step.get("target") or step.get("instruction") or "").strip()
        raw = re.sub(
            r"\b(tap|open|select|choose|press|contact|contacts|row|chat|conversation|with|result|results)\b",
            " ",
            raw,
            flags=re.IGNORECASE,
        )
        raw = re.sub(r"\s+", " ", raw).strip(" .:-")
        return raw or None

    # ── Control matchers ───────────────────────────────────────────────────

    def find_contact_control(self, controls: list, contact_name: str) -> dict | None:
        target = self.normalize_contact_label(contact_name)
        if len(target) < 2:
            return None

        best = None
        best_score = 0
        for control in controls:
            if not isinstance(control, dict):
                continue
            label = str(control.get("label") or "")
            normalized = self.normalize_contact_label(label)
            if not normalized:
                continue

            tokens = normalized.split()
            score = 0
            if normalized == target:
                score = 140
            elif tokens and tokens[0] == target:
                score = 125
            elif target in tokens:
                score = 105
            elif target in normalized:
                score = 70

            if score <= 0:
                continue
            if self.looks_like_search_control(control):
                score -= 90
            if control.get("clickable"):
                score += 20
            if control.get("enabled", True):
                score += 5
            if score > best_score:
                best_score = score
                best = control

        return best if best_score >= 70 else None

    def _whatsapp_gallery_picker_already_open(self, controls: list) -> bool:
        """Detect that WhatsApp's gallery picker view is the foreground screen
        (the user already tapped 相册/Gallery). Markers we look for:
        - gallery_view_pager (Android view pager inside the picker)
        - "Recents" / "All photos" headers
        - gallery_spinner (album dropdown at top of picker)
        Any one is sufficient — these only appear AFTER the picker has opened.
        """
        if not isinstance(controls, list):
            return False
        markers = (
            "gallery_view_pager",
            "gallery_spinner",
            "recents",  # Picker title in English locale
            "最近",     # Picker title in Chinese locale
            "all photos",
            "所有照片",
        )
        for control in controls:
            if not isinstance(control, dict):
                continue
            text = self.control_search_text(control)
            if not text:
                continue
            if any(marker in text for marker in markers):
                return True
        return False

    def has_whatsapp_attachment_sheet_controls(self, controls: list) -> bool:
        if not isinstance(controls, list):
            return False

        found = set()
        for control in controls:
            if not isinstance(control, dict):
                continue
            text = self.control_search_text(control)
            if not text:
                continue
            for name, terms in _WHATSAPP_ATTACHMENT_SHEET_MARKERS:
                if any(term in text for term in terms):
                    found.add(name)
        return "gallery" in found and len(found) >= 2

    def find_whatsapp_gallery_control(self, controls: list) -> dict | None:
        if not isinstance(controls, list):
            return None

        best = None
        best_score = 0
        for control in controls:
            if not isinstance(control, dict):
                continue
            text = self.control_search_text(control)
            if not text or not any(term in text for term in _WHATSAPP_GALLERY_CONTROL_TERMS):
                continue
            if "gallery_spinner" in text:
                continue

            label = str(control.get("label") or "").strip().lower()
            score = 100
            if label in _WHATSAPP_GALLERY_CONTROL_TERMS:
                score += 45
            if control.get("clickable"):
                score += 20
            if control.get("enabled", True):
                score += 10

            y = self._percent_optional(control.get("y"))
            if y is not None:
                if 50 <= y <= 90:
                    score += 10
                elif y < 35:
                    score -= 50

            if "contact_photo" in text or "profile_photo" in text:
                score -= 80

            if score > best_score:
                best_score = score
                best = control

        return best if best_score >= 80 else None

    def find_forward_action_control(self, controls: list) -> dict | None:
        if not isinstance(controls, list):
            return None
        best = None
        best_score = 0
        for control in controls:
            if not isinstance(control, dict):
                continue
            label = str(control.get("label") or "").strip()
            normalized = label.lower()
            if not normalized:
                continue
            score = 0
            for term in _WHATSAPP_FORWARD_ACTION_LABELS:
                if term in normalized or term in label:
                    score = max(score, 100 if term in ("同意并继续", "agree and continue") else 70)
            if score <= 0:
                continue
            if control.get("clickable"):
                score += 20
            if control.get("enabled", True):
                score += 10
            if score > best_score:
                best_score = score
                best = control
        return best

    @staticmethod
    def normalize_contact_label(value: Any) -> str:
        text = str(value or "").strip().lower()
        text = re.sub(r"[^\w一-鿿]+", " ", text)
        return re.sub(r"\s+", " ", text).strip()

    @staticmethod
    def looks_like_search_control(control: dict) -> bool:
        label = str(control.get("label") or "").lower()
        class_name = str(control.get("className") or "").lower()
        return "search" in label or "搜索" in label or "edittext" in class_name

    @staticmethod
    def control_search_text(control: dict) -> str:
        return " ".join(
            str(control.get(key) or "")
            for key in ("label", "text", "contentDescription", "viewId", "resourceId", "resourceName")
        ).strip().lower()

    @staticmethod
    def step_search_text(step: dict | None) -> str:
        if not step:
            return ""
        return " ".join(str(p or "") for p in [
            step.get("target"),
            step.get("instruction"),
            step.get("expectedResult"),
            *(step.get("matchHints") or []),
        ]).lower()

    def control_to_highlight(self, control: dict, reason: str, step: dict | None) -> dict:
        logger.info("Known UI rule matched: %s -> %s", reason, control.get("label"))
        target = str(control.get("label") or (step or {}).get("target") or "Continue")
        return {
            "x": self._percent(control.get("x"), 50),
            "y": self._percent(control.get("y"), 82),
            "x1": self._percent_optional(control.get("x1")),
            "y1": self._percent_optional(control.get("y1")),
            "x2": self._percent_optional(control.get("x2")),
            "y2": self._percent_optional(control.get("y2")),
            "completed": False,
            "blockerDetected": True,
            "blockerReason": reason,
            "direction": None,
            "target": target,
            "matchHints": [target],
        }

    @staticmethod
    def _percent(value: Any, default: int) -> int:
        try:
            return max(0, min(100, int(round(float(value)))))
        except (TypeError, ValueError):
            return default

    @classmethod
    def _percent_optional(cls, value: Any) -> int | None:
        if value is None:
            return None
        return cls._percent(value, 0)

    # ── i18n helpers ───────────────────────────────────────────────────────

    @staticmethod
    def known_settings_instruction(language: str | None) -> str:
        if language == "zh":
            return "点左上角设置"
        if language == "ms":
            return "Tekan tetapan kiri atas"
        return "Tap top-left Settings"

    @staticmethod
    def known_button_instruction(language: str | None, label: Any) -> str:
        button = str(label or "").strip() or "Continue"
        if language == "zh":
            return f"点{button}"
        if language == "ms":
            return f"Tekan {button}"
        return f"Tap {button}"
