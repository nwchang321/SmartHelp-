"""
SafetyGate — Translated from SafetyGate.js
Detects scam patterns, sensitive actions, and safety check requests in user goals.
Multi-language support: English, Chinese (Mandarin), Malay.
"""

from __future__ import annotations

import hashlib
import logging
import re
import time
from typing import Any, Optional

logger = logging.getLogger("smarthelp.safety")


# ── Pattern tables ─────────────────────────────────────────────────────────

BLOCKED_PATTERNS: dict[str, list[dict]] = {
    "zh": [
        {"pattern": re.compile(r"警察|公安|银行(工作人员|客服)|政府.*(转账|汇款|安全账户)"), "id": "impersonation"},
        {"pattern": re.compile(r"(安全账户|保护账户|冻结.*转)"), "id": "safe_account_scam"},
        {"pattern": re.compile(r"(anydesk|teamviewer|远程控制|远程协助|对方.*控制)", re.I), "id": "remote_control"},
        {"pattern": re.compile(r"(发|给|告诉|分享).*(验证码|otp|tac|pin|密码)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(验证码|otp|tac|pin|密码).*(发|给|告诉|分享)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(陌生人|不认识的人|陌生账号|陌生账户).*(转账|汇款|转钱)"), "id": "unknown_transfer"},
        {"pattern": re.compile(r"(打开|点击).*(链接|网址).*(转账|付款)|https?://\S+.*(转账|付款)", re.I), "id": "suspicious_link"},
    ],
    "en": [
        {"pattern": re.compile(r"police|pdrm|bank (staff|officer)|government.*(transfer|send money|safe account)", re.I), "id": "impersonation"},
        {"pattern": re.compile(r"safe account|protection account|freeze.*transfer", re.I), "id": "safe_account_scam"},
        {"pattern": re.compile(r"(anydesk|teamviewer|remote control|remote access)", re.I), "id": "remote_control"},
        {"pattern": re.compile(r"(send|share|give|tell).*(otp|tac|pin|password|verification code)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(otp|tac|pin|password|verification code).*(send|share|give|tell)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(transfer|send money).*(stranger|unknown person|unknown account)|stranger.*(transfer|send money)", re.I), "id": "unknown_transfer"},
        {"pattern": re.compile(r"(open|click).*(link|url).*(transfer|payment|pay)|https?://\S+.*(transfer|payment|pay)", re.I), "id": "suspicious_link"},
    ],
    "ms": [
        {"pattern": re.compile(r"polis|pdrm|pegawai bank|kerajaan.*(pindah wang|hantar duit|akaun selamat)", re.I), "id": "impersonation"},
        {"pattern": re.compile(r"(akaun selamat|akaun perlindungan)", re.I), "id": "safe_account_scam"},
        {"pattern": re.compile(r"(anydesk|teamviewer|kawalan jauh|akses jauh)", re.I), "id": "remote_control"},
        {"pattern": re.compile(r"(hantar|bagi|kongsi).*(otp|tac|pin|kata laluan|kod pengesahan)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(otp|tac|pin|kata laluan|kod pengesahan).*(hantar|bagi|kongsi)", re.I), "id": "share_credentials"},
        {"pattern": re.compile(r"(orang tidak dikenali|akaun tidak dikenali).*(pindah wang|hantar duit)|pindah wang.*(orang tidak dikenali|akaun tidak dikenali)", re.I), "id": "unknown_transfer"},
        {"pattern": re.compile(r"(buka|klik).*(pautan|link).*(pindah wang|bayaran)|https?://\S+.*(pindah wang|bayaran)", re.I), "id": "suspicious_link"},
    ],
}

CONFIRM_PATTERNS: dict[str, list[dict]] = {
    "zh": [
        {"pattern": re.compile(r"(转账|汇款|转钱|付款|发红包)"), "id": "money_transfer",
         "question_zh": "你确定要转账吗？转给谁呢？", "question_en": "Are you sure you want to transfer money? Who is it for?", "question_ms": "Adakah anda pasti mahu pindah wang? Untuk siapa?"},
        {"pattern": re.compile(r"(扫码|扫一下|二维码|qr)", re.I), "id": "qr_scan",
         "question_zh": "这个二维码是谁给你的？确定要扫吗？", "question_en": "Who gave you this QR code? Are you sure you want to scan it?", "question_ms": "Siapa yang memberi kod QR ini? Adakah anda pasti mahu mengimbasnya?"},
        {"pattern": re.compile(r"(安装|下载).*(app|应用|软件)", re.I), "id": "app_install",
         "question_zh": "你确定要安装这个 app 吗？是谁建议的？", "question_en": "Are you sure you want to install this app? Who suggested it?", "question_ms": "Adakah anda pasti mahu memasang aplikasi ini? Siapa yang mencadangkannya?"},
        {"pattern": re.compile(r"(删除|删掉|移除)"), "id": "delete_action",
         "question_zh": "确定要删除吗？删除后可能无法恢复。", "question_en": "Are you sure you want to delete this? It may not be recoverable.", "question_ms": "Adakah anda pasti mahu memadam ini? Ia mungkin tidak boleh dipulihkan."},
        {"pattern": re.compile(r"(修改密码|换密码|改密码|绑定.*设备|新设备)"), "id": "account_change",
         "question_zh": "你确定要修改这个设置吗？", "question_en": "Are you sure you want to change this setting?", "question_ms": "Adakah anda pasti mahu menukar tetapan ini?"},
    ],
    "en": [
        {"pattern": re.compile(r"(transfer|send money|pay|payment)", re.I), "id": "money_transfer",
         "question_zh": "你确定要转账吗？转给谁呢？", "question_en": "Are you sure you want to transfer money? Who is it for?", "question_ms": "Adakah anda pasti mahu pindah wang? Untuk siapa?"},
        {"pattern": re.compile(r"(scan|qr code)", re.I), "id": "qr_scan",
         "question_zh": "这个二维码是谁给你的？确定要扫吗？", "question_en": "Who gave you this QR code? Are you sure you want to scan it?", "question_ms": "Siapa yang memberi kod QR ini? Adakah anda pasti mahu mengimbasnya?"},
        {"pattern": re.compile(r"(install|download).*(app)", re.I), "id": "app_install",
         "question_zh": "你确定要安装这个 app 吗？是谁建议的？", "question_en": "Are you sure you want to install this app? Who suggested it?", "question_ms": "Adakah anda pasti mahu memasang aplikasi ini? Siapa yang mencadangkannya?"},
        {"pattern": re.compile(r"(delete|remove|erase)", re.I), "id": "delete_action",
         "question_zh": "确定要删除吗？删除后可能无法恢复。", "question_en": "Are you sure you want to delete this? It may not be recoverable.", "question_ms": "Adakah anda pasti mahu memadam ini? Ia mungkin tidak boleh dipulihkan."},
        {"pattern": re.compile(r"\b(change|reset|update)(\s+\w+){0,2}\s+password\b|new device|bind device|change.*pin", re.I), "id": "account_change",
         "question_zh": "你确定要修改这个设置吗？", "question_en": "Are you sure you want to change this setting?", "question_ms": "Adakah anda pasti mahu menukar tetapan ini?"},
    ],
    "ms": [
        {"pattern": re.compile(r"(pindah wang|hantar duit|bayar|bayaran)", re.I), "id": "money_transfer",
         "question_zh": "你确定要转账吗？转给谁呢？", "question_en": "Are you sure you want to transfer money? Who is it for?", "question_ms": "Adakah anda pasti mahu pindah wang? Untuk siapa?"},
        {"pattern": re.compile(r"(imbas|kod qr)", re.I), "id": "qr_scan",
         "question_zh": "这个二维码是谁给你的？确定要扫吗？", "question_en": "Who gave you this QR code? Are you sure you want to scan it?", "question_ms": "Siapa yang memberi kod QR ini? Adakah anda pasti mahu mengimbasnya?"},
        {"pattern": re.compile(r"(pasang|muat turun).*(app|aplikasi)", re.I), "id": "app_install",
         "question_zh": "你确定要安装这个 app 吗？是谁建议的？", "question_en": "Are you sure you want to install this app? Who suggested it?", "question_ms": "Adakah anda pasti mahu memasang aplikasi ini? Siapa yang mencadangkannya?"},
        {"pattern": re.compile(r"(padam|buang|hapus)", re.I), "id": "delete_action",
         "question_zh": "确定要删除吗？删除后可能无法恢复。", "question_en": "Are you sure you want to delete this? It may not be recoverable.", "question_ms": "Adakah anda pasti mahu memadam ini? Ia mungkin tidak boleh dipulihkan."},
        {"pattern": re.compile(r"(tukar kata laluan|peranti baharu)", re.I), "id": "account_change",
         "question_zh": "你确定要修改这个设置吗？", "question_en": "Are you sure you want to change this setting?", "question_ms": "Adakah anda pasti mahu menukar tetapan ini?"},
    ],
}

SAFETY_CHECK_PATTERNS: dict[str, list[re.Pattern]] = {
    "zh": [
        re.compile(r"(是不是|是否).*(诈骗|骗子|假的|骗人)"),
        re.compile(r"(帮我|帮忙).*(看看|检查|判断).*(安全|可靠|真假)"),
        re.compile(r"(这个|这条).*(链接|消息|二维码).*(安全吗|可靠)"),
        re.compile(r"(别帮我操作|不要操作).*(只|先).*(看|检查)"),
        re.compile(r"帮我看看.*(诈骗|安全吗|真假)"),
    ],
    "en": [
        # "is this/it/a/that a scam/fake/fraud/safe?", also catches "Is this message a scam"
        re.compile(r"\bis\b.*\b(scam|fake|fraud|phishing)\b", re.I),
        re.compile(r"\b(scam|fake|fraud|phishing|suspicious)\b\??\s*$", re.I),
        # "help me check / look at / verify this message"
        re.compile(r"(help me )?(check|verify|look at|examine).*(message|link|qr|code|email|sms|notification|scam|suspicious|safe|legit)", re.I),
        re.compile(r"(do not|don't).*(act|tap|transfer).*(just|only).*(check|verify)", re.I),
        # "is this safe?" / "is this legit?"
        re.compile(r"is (this|it|that|a|the).*\b(safe|legit|real|genuine|trustworthy)\b\??$", re.I),
    ],
    "ms": [
        re.compile(r"(betul ke|adakah).*(scam|penipuan|tipu)", re.I),
        re.compile(r"(semak|periksa).*(selamat|mencurigakan|sah)", re.I),
        re.compile(r"(jangan).*(tekan|buat).*(hanya|cuma).*(semak|periksa)", re.I),
    ],
}

BLOCKED_WARNINGS: dict[str, dict[str, str]] = {
    "impersonation": {
        "zh": "这可能是诈骗。真正的警察和银行不会要求你通过电话转账。建议先挂断，然后打给你的家人确认。",
        "en": "This may be a scam. Real police and banks never ask you to transfer money over the phone. Please hang up and check with your family first.",
        "ms": "Ini mungkin penipuan. Polis dan bank sebenar tidak akan meminta anda pindah wang melalui telefon. Sila letak telefon dan semak dengan keluarga anda dahulu.",
    },
    "safe_account_scam": {
        "zh": '没有所谓的"安全账户"，这是常见的诈骗手法。请不要转账，先联系你的家人。',
        "en": 'There is no such thing as a "safe account". This is a common scam. Please do not transfer money and contact your family first.',
        "ms": 'Tiada apa yang dipanggil "akaun selamat". Ini helah penipuan yang biasa. Jangan pindah wang dan hubungi keluarga anda dahulu.',
    },
    "remote_control": {
        "zh": "让别人远程控制你的手机是非常危险的。请不要安装这个 app。如果有疑问，请联系你的家人。",
        "en": "Letting someone control your phone remotely is very dangerous. Please do not install this app. Contact your family if you have questions.",
        "ms": "Membenarkan orang lain mengawal telefon anda dari jauh sangat berbahaya. Jangan pasang aplikasi ini. Hubungi keluarga anda jika anda ragu-ragu.",
    },
    "share_credentials": {
        "zh": "验证码和密码是你的私人信息，不能分享给任何人，包括银行工作人员和警察。",
        "en": "Verification codes and passwords are your private information. Never share them with anyone, including bank staff or police.",
        "ms": "Kod pengesahan dan kata laluan ialah maklumat peribadi anda. Jangan sesekali berkongsinya dengan sesiapa, termasuk pegawai bank atau polis.",
    },
    "unknown_transfer": {
        "zh": "把钱转给陌生人或不明账户风险很高。请先联系家人确认收款人身份。",
        "en": "Sending money to a stranger or unknown account is risky. Please confirm the recipient with your family first.",
        "ms": "Menghantar wang kepada orang atau akaun yang tidak dikenali adalah berisiko. Sila sahkan penerima dengan keluarga anda dahulu.",
    },
    "suspicious_link": {
        "zh": "来源不明的链接可能会把你带到诈骗页面。请不要继续操作，先找家人帮你确认。",
        "en": "Unknown links can lead to scam pages. Please stop here and ask your family to verify it first.",
        "ms": "Pautan yang tidak diketahui boleh membawa anda ke halaman penipuan. Sila berhenti di sini dan minta keluarga anda menyemaknya dahulu.",
    },
}

SENSITIVE_SCREEN_MESSAGES: dict[str, str] = {
    "zh": "检测到银行或敏感页面。为了保护你的安全，我先暂停引导。操作完成后请回来找我。",
    "en": "I noticed a banking or sensitive screen. For your safety, I will pause guidance. Come back to me when you are done.",
    "ms": "Saya mengesan skrin perbankan atau sensitif. Demi keselamatan anda, saya akan hentikan panduan buat sementara waktu. Datang semula selepas anda selesai.",
}


def _norm_lang(language: str | None) -> str:
    """Normalise a language tag.

    SmartHelp+ is locked to English end-to-end; STT only emits en-US transcripts.
    The zh/ms pattern tables below are retained as dormant defensive code in case
    future versions re-enable multilingual input — they will not fire unless the
    caller explicitly passes language="zh" or "ms".
    """
    return language if language in ("zh", "en", "ms") else "en"


class SafetyGate:
    def check(self, goal: str, language: str | None = None) -> dict:
        lang = _norm_lang(language)
        normalized = (goal or "").strip().lower()

        if not normalized:
            r = {"safe": True, "needsConfirm": False, "ruleId": None, "isSafetyCheckRequest": False}
            self._log(goal, r)
            return r

        if self._is_safety_check_request(normalized, lang):
            r = {"safe": True, "needsConfirm": False, "ruleId": None, "isSafetyCheckRequest": True}
            self._log(goal, r)
            return r

        blocked = self._match_patterns(normalized, BLOCKED_PATTERNS, lang)
        if blocked:
            r = {
                "safe": False, "needsConfirm": False,
                "ruleId": blocked["id"],
                "warning": self._get_blocked_warning(blocked["id"], lang),
                "isSafetyCheckRequest": False,
            }
            self._log(goal, r)
            return r

        confirm = self._match_patterns(normalized, CONFIRM_PATTERNS, lang)
        if confirm:
            r = {
                "safe": False, "needsConfirm": True,
                "ruleId": confirm["id"],
                "question": self._get_confirm_question(confirm, lang),
                "isSafetyCheckRequest": False,
            }
            self._log(goal, r)
            return r

        r = {"safe": True, "needsConfirm": False, "ruleId": None, "isSafetyCheckRequest": False}
        self._log(goal, r)
        return r

    def handle_sensitive_screen(self, app_package: str | None, language: str | None) -> dict:
        lang = _norm_lang(language)
        return {
            "action": "pause",
            "appPackage": app_package,
            "message_zh": SENSITIVE_SCREEN_MESSAGES["zh"],
            "message_en": SENSITIVE_SCREEN_MESSAGES["en"],
            "message_ms": SENSITIVE_SCREEN_MESSAGES["ms"],
            "message": SENSITIVE_SCREEN_MESSAGES.get(lang, SENSITIVE_SCREEN_MESSAGES["en"]),
        }

    def _ordered_languages(self, language: str) -> list[str]:
        # Caller-requested language is checked first. Then "en" (default in the
        # English-only build). Other dormant tables (zh/ms) are still scanned
        # last as a defensive net so a Chinese/Malay scam phrase that somehow
        # reaches the gate (e.g. via OCR or a future re-enabled STT) still
        # gets caught.
        langs = [_norm_lang(language)]
        for l in ("en", "zh", "ms"):
            if l not in langs:
                langs.append(l)
        return langs

    def _match_patterns(self, text: str, pattern_sets: dict, language: str) -> dict | None:
        for lang in self._ordered_languages(language):
            patterns = pattern_sets.get(lang, [])
            for rule in patterns:
                if rule["pattern"].search(text):
                    return rule
        return None

    def _is_safety_check_request(self, text: str, language: str) -> bool:
        for lang in self._ordered_languages(language):
            for pattern in SAFETY_CHECK_PATTERNS.get(lang, []):
                if pattern.search(text):
                    return True
        return False

    def _get_blocked_warning(self, rule_id: str, language: str) -> str:
        messages = BLOCKED_WARNINGS.get(rule_id)
        if not messages:
            defaults = {
                "zh": "这个操作可能有风险，建议先问问家人的意见。",
                "en": "This action may be risky. Please check with your family first.",
                "ms": "Tindakan ini mungkin berisiko. Sila semak dengan keluarga anda dahulu.",
            }
            return defaults.get(language, defaults["en"])
        return messages.get(language, messages["en"])

    def _get_confirm_question(self, rule: dict, language: str) -> str:
        key = f"question_{language}"
        return rule.get(key, rule.get("question_en", ""))

    def _log(self, goal: str, result: dict) -> None:
        text = str(goal or "")
        goal_hash = hashlib.sha256(text.encode()).hexdigest()[:16] if text else None
        status = "SAFE" if result.get("safe") else ("CONFIRM" if result.get("needsConfirm") else "BLOCKED")
        logger.info("goalLen=%d hash=%s result=%s ruleId=%s", len(text), goal_hash, status, result.get('ruleId'))
