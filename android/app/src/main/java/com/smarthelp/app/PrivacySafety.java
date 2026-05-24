package com.smarthelp.app;

import android.content.Context;

import java.util.Locale;

public final class PrivacySafety {
    private static final String[] SENSITIVE_PACKAGE_KEYWORDS = {
            "maybank", "cimb", "rhb", "publicbank", "bankislam", "bankrakyat",
            "hongleong", "uob", "ocbc", "affin", "ambank", "bsn",
            "touchngo", "tng", "boost", "grabpay", "bigpay", "duitnow",
            "wallet", "payment", "bank", "banking", "finance",
            "authenticator", "token"
    };

    private static final String[] SENSITIVE_QUERY_KEYWORDS = {
            "otp", "one-time password", "password", "passcode", "pin",
            "bank", "banking", "transfer money", "transfer funds", "send money",
            "payment", "pay bill", "wallet", "approve transaction", "secure code"
    };

    private PrivacySafety() {}

    public static String getPrivacyNoticeTitle(Context context) {
        return isChinese(context) ? "隐私与安全说明" : "Privacy & Safety Notice";
    }

    public static String getPrivacyNoticeMessage(Context context) {
        if (isChinese(context)) {
            return "SmartHelp+ 只会在您主动请求帮助时工作。\n\n"
                    + "它可能会：\n"
                    + "- 读取当前可见屏幕画面\n"
                    + "- 将截图、语音和文字发送到您配置的 SmartHelp 服务器和 Gemini AI，用来生成引导\n"
                    + "- 使用无障碍服务检测点击和页面切换，以便更快响应\n\n"
                    + "请不要在以下场景使用：\n"
                    + "- 银行转账、付款确认\n"
                    + "- 密码、PIN、OTP 验证码\n"
                    + "- 含有高度敏感个人信息的页面\n\n"
                    + "检测到银行、支付、密码或验证码相关页面时，SmartHelp+ 会自动暂停截图引导。\n"
                    + "您可以随时停止服务，并在 Android 设置中撤销权限。";
        }

        return "SmartHelp+ only works when you actively ask for help.\n\n"
                + "It may:\n"
                + "- capture the visible screen\n"
                + "- send screenshots, voice, and typed requests to your configured SmartHelp server and Gemini AI to generate guidance\n"
                + "- use accessibility events to detect taps and page changes for faster guidance\n\n"
                + "Do not use SmartHelp+ for:\n"
                + "- bank transfers or payment approval\n"
                + "- passwords, PINs, or OTP codes\n"
                + "- screens containing highly private personal information\n\n"
                + "SmartHelp+ automatically pauses screenshot guidance when banking, payment, password, or authenticator screens are detected.\n"
                + "You can stop the service anytime and remove permissions in Android Settings.";
    }

    public static String getSensitivePauseMessage(Context context) {
        return isChinese(context)
                ? "检测到敏感页面，已暂停截图引导。请先离开银行、支付、密码或验证码页面。"
                : "Sensitive screen detected. Screenshot guidance is paused on banking, payment, password, and OTP screens.";
    }

    public static String getSensitiveTaskMessage(Context context) {
        return isChinese(context)
                ? "为保护隐私，SmartHelp+ 不引导转账、密码或验证码操作。"
                : "For privacy, SmartHelp+ does not guide bank transfers, passwords, or OTP steps.";
    }

    public static String getSensitiveProtectionSummary(Context context) {
        return isChinese(context)
                ? "检测到银行、支付、密码和验证码页面时自动暂停截图分析"
                : "Automatically pauses screenshot analysis on banking, payment, password, and OTP screens";
    }

    public static String getSensitiveProtectionStatus(Context context) {
        return isChinese(context) ? "默认开启" : "On by default";
    }

    public static String getPrivacyPermissionMessage(Context context) {
        return isChinese(context)
                ? "SmartHelp+ 需要临时录屏权限，才能一步步引导您。只有在您主动请求帮助时，当前可见屏幕、语音和文字才可能发送到您配置的服务器和 AI 服务进行处理。\n\n请避免在银行、支付、密码或验证码页面继续使用。"
                : "SmartHelp+ needs temporary screen capture to guide you step by step. Only when you ask for help may the visible screen, voice, and text be sent to your configured server and AI service for processing.\n\nPlease avoid using it on banking, payment, password, or OTP screens.";
    }

    public static boolean isSensitivePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        if (normalized.contains("smarthelp")) return false;

        for (String keyword : SENSITIVE_PACKAGE_KEYWORDS) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }

    public static boolean isSensitiveQuery(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_QUERY_KEYWORDS) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }

    private static boolean isChinese(Context context) {
        return "zh".equals(AppPrefs.getLanguage(context));
    }
}
