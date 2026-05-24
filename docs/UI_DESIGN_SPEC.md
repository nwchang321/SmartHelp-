# SmartHelp+ UI/UX Design Specification

**Version:** 1.0  
**Date:** 2026-04-09  
**Scope:** Complete user journey — from first launch to task completion

---

## Table of Contents

1. [User Journey Overview](#1-user-journey-overview)
2. [Design System Reference](#2-design-system-reference)
3. [S1 — Splash Screen](#3-s1--splash-screen)
4. [S2 — Onboarding (First Launch Only)](#4-s2--onboarding-first-launch-only)
5. [S3 — Home Screen](#5-s3--home-screen)
6. [F1 — Permission Flow](#6-f1--permission-flow)
7. [S4 — Overlay Panel](#7-s4--overlay-panel)
8. [S5 — Floating Ball Mode](#8-s5--floating-ball-mode)
9. [S6 — Chat History Panel](#9-s6--chat-history-panel)
10. [S7 — Task Complete](#10-s7--task-complete)
11. [Full Interaction Flow](#11-full-interaction-flow)
12. [File Change Summary](#12-file-change-summary)

---

## 1. User Journey Overview

```
FIRST LAUNCH                    SUBSEQUENT LAUNCHES
     │                                   │
 [S1 Splash]                       [S1 Splash]
     │                                   │
 [S2 Onboarding]               [S3 Home Screen]
     │                                   │
 [Privacy Notice]          [F1 Permission Check]
     │                          (skip if granted)
 [S3 Home Screen]                        │
     │                                   │
 [F1 Permission Flow]         [S4 Overlay Panel]
     │                                   │
     └──────────────────────────────────►│
                                         │
                          ┌──────────────┼──────────────┐
                          │              │              │
                    [S5 Ball Mode]  [S6 Chat]   [S7 Complete]
```

---

## 2. Design System Reference

### Color Palette

| Token | Hex | Usage |
|---|---|---|
| `primary` | `#1565C0` | Buttons, links, borders |
| `primary_dark` | `#0D47A1` | Pressed states |
| `primary_light` | `#42A5F5` | Rings, accents |
| `navy_deep` | `#0A2342` | Orb gradient start, text on light |
| `navy_mid` | `#0D47A1` | Orb gradient center |
| `accent` | `#00ACC1` | Secondary highlights |
| `success` | `#10B981` | Completion states |
| `background` | `#E3F2FD` | App background |
| `surface` | `#FFFFFF` | Cards, panels |
| `text_primary` | `#1C1B1F` | Body text |
| `text_secondary` | `#6B7280` | Subtitles, hints |
| `overlay_navy` | `#DD0A2342` | Overlay control bar (87% opacity) |

### Typography Scale

| Style | Size | Weight | Usage |
|---|---|---|---|
| Display | 38sp | Bold | Main greeting |
| Headline | 26–28sp | Bold | Dialog titles |
| Title | 20–22sp | Bold | Section headers |
| Body | 15–17sp | Regular | Content text |
| Guidance | 18sp | Medium | AI instructions |
| Caption | 13–14sp | Regular | Hints, labels |
| Label | 10–12sp | Bold | Status pills, tags |

### Shape Language

| Component | Shape |
|---|---|
| Primary orb | Perfect circle (oval drawable or MaterialCardView with radius = 50%) |
| Buttons | Pill (full cornerRadius) |
| Cards | 16dp corners |
| Overlay panels | 24dp top corners, flat bottom |
| Chat messages | 14dp corners |
| Status pills | 20dp corners |
| Control bar | 24dp corners |

### Elevation / Shadow

| Component | Elevation |
|---|---|
| Floating orb (main screen) | 24dp |
| Floating orb (overlay) | 8dp |
| Floating ball | 10dp |
| Cards | 2–4dp |
| Chat panel | 12dp (bottom sheet) |

---

## 3. S1 — Splash Screen

### Role in Flow
First thing the user sees. Establishes brand, loads app, transitions to Home or Onboarding.

### Current Issues
- Transition from Splash → MainActivity is abrupt (no animation).

### Layout

```
Background: bg_hero_gradient (light blue, #E3F2FD top → white bottom)

┌──────────────────────────────────┐
│                                  │
│         ◯  280dp ring            │  alpha: 0.08, circle_brand_ring
│       ◯  220dp ring              │  alpha: 0.12
│     ◯  140dp ring                │  alpha: 0.22
│       🌟  200dp glow             │  alpha: 0.75, bg_logo_glow
│       [ LOGO ] 108dp             │  app_logo, centerInside
│                                  │
│        SmartHelp+                │  36sp bold, #0A2342, letterSpacing 0.04
│  Your step-by-step guide         │  15sp, #1565C0, centered
│                                  │
│                                  │
│      ━━━━━━━━━━  120dp           │  indeterminate, 3dp, #1565C0
│   POWERED BY GEMINI AI           │  10sp bold, #801565C0, allCaps
└──────────────────────────────────┘
```

### Interaction
- Duration: 1800ms minimum, waits for connection check.
- On finish: transition to `MainActivity` with `fade_in / fade_out` animation.

### Changes Required
| File | Change |
|---|---|
| `SplashActivity.java` | Add `overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)` before `finish()` |

---

## 4. S2 — Onboarding (First Launch Only)

### Role in Flow
Shown once via BottomSheetDialog immediately after Splash on first launch. Explains what the app does before asking for any permissions.

### Current Issues
- Cards use `bg_info_card_purple` — old purple palette, clashes with current blue brand.
- Subtitle color `#7B74A8` is a purple tone, inconsistent with blue system.
- "Got it!" button corner radius (26dp) on 52dp height does not render as a true pill.

### Layout

```
╭────────────────────────────────────╮  bg: bg_body_surface (white, rounded top)
│  ────                              │  Drag handle: 40dp × 4dp, #CCCCDD, centered
│                                    │
│  How SmartHelp+ works      22sp bold  #1A1A2E
│  Your AI assistant that            │  14sp, #6B7280, marginBottom 24dp
│  guides you step by step.          │
│                                    │
│  ┌──────────────────────────────┐  │
│  │  🗣️  Say your goal           │  │  bg: #EEF4FF, left border 3dp #1565C0
│  │  Hold the mic and say what   │  │  16dp corners
│  │  you want to do.             │  │  title: 15sp bold #0A2342
│  └──────────────────────────────┘  │  body: 13sp #1565C0
│                                    │
│  ┌──────────────────────────────┐  │
│  │  👆  Follow the arrow        │  │  bg: #EDF9FB, left border 3dp #00ACC1
│  │  Tap exactly where the       │  │  title: 15sp bold #0A2342
│  │  arrow points.               │  │  body: 13sp #00838F
│  └──────────────────────────────┘  │
│                                    │
│  ┌──────────────────────────────┐  │
│  │  🔒  Your data stays private │  │  bg: #F0FDF4, left border 3dp #10B981
│  │  Nothing is stored or        │  │  title: 15sp bold #0A2342
│  │  shared. Ever.               │  │  body: 13sp #059669
│  └──────────────────────────────┘  │
│                                    │
│  ╭──────────────────────────────╮  │
│  │        Got it! / 明白了       │  │  56dp height, full pill
│  ╰──────────────────────────────╯  │  gradient: #0A2342 → #1565C0, 135°
╰────────────────────────────────────╯
```

### Interaction
- Not dismissable by back button or touching outside (`setCancelable(false)`).
- "Got it!" → dismiss → if no privacy consent: show Privacy Notice dialog.

### Changes Required
| File | Change |
|---|---|
| `dialog_onboarding.xml` | Replace `bg_info_card_purple` with colored left-border white cards. Update text colors to blue system. Fix button corner radius to full pill. |

---

## 5. S3 — Home Screen

### Role in Flow
Main screen. User taps the orb to start/stop the assistant. Shows recent task history.

### Current Issues
- `bg_welcome_cta_idle.xml` uses `android:shape="rectangle"` with `corners radius="30dp"` — clipped to circle by MaterialCardView, but causes slight anti-aliasing artifacts on some devices. Should be oval.
- History rows use `bg_info_card_purple` (old palette).
- No clear visual difference between "service idle" and "service running" states on the orb.
- Orb tint does not change when service is active.

### Layout

```
Background: bg_hero_gradient

┌──────────────────────────────────────┐
│  [⚙️ settings 42dp]     [● Status]  │  Top bar: translucent pills
│                                      │
│                                      │
│         Good morning! 👋             │  38sp bold, #0A2342
│   Try asking me to open apps...      │  15sp, #1565C0, centered
│                                      │
│              ╭──188dp──╮            │
│           ╭──┤  ring   ├──╮         │  circle_white_ring, pulse animation
│           │  ╭─140dp──╮  │          │
│           │  │  [MIC]  │  │         │  140dp orb (MaterialCardView, r=70dp)
│           │  │  52dp   │  │         │  idle bg:   bg_welcome_cta_idle
│           │  ╰─────────╯  │         │  active bg: bg_welcome_cta_active
│           ╰───────────────╯         │
│                                      │
│       Tap to start              20sp bold, #0A2342
│       Hold mic and speak         14sp, #1565C0
│                                      │
│   ╭────────────────────────────╮    │  Only shown when service is running
│   │  ● SmartHelp+ is active    │    │  bg: bg_translucent_pill, green dot
│   ╰────────────────────────────╯    │
│                                      │
├──────────────────────────────────────┤
│  bg_body_surface (white, rounded top)│
│                                      │
│  Recent tasks              18sp bold │
│  Tap to run again          13sp hint │
│                                      │
│  ╭───────────────────────────────╮   │  bg_info_card_blue (replaces purple)
│  │ 🕐  帮我发 WhatsApp 给妈妈  ▶ │   │  15sp bold, #0A2342
│  ╰───────────────────────────────╯   │
│                                      │
│  Your recent tasks will appear here  │  14sp, #C5C2DC, empty state
└──────────────────────────────────────┘
```

### Orb States

| State | Orb Gradient | Ring | Label |
|---|---|---|---|
| Idle | `#0A2342 → #0D47A1 → #1565C0` | Pulse white, 2400ms cycle | "Tap to start" |
| Running | `#085B4A → #10997C → #30C79D` | Pulse green, 1600ms cycle | "Tap to stop" |

### Animations (existing, keep)
- Orb ring: scale 1.0 → 1.08 → 1.0, alpha 0.95 → 0.55 → 0.95, 2400ms loop.
- Orb float: translateY 0 → -8dp → 0, 2500ms loop.
- Staggered fade-up on intro: top bar → greeting → subtitle → orb → labels.

### Changes Required
| File | Change |
|---|---|
| `bg_welcome_cta_idle.xml` | Change `android:shape="rectangle"` to `android:shape="oval"`, remove `<corners>` |
| `activity_main.xml` | Replace `bg_info_card_purple` with `bg_info_card_blue` on history rows |
| `MainActivity.java` | Switch orb gradient and ring animation speed/color when `isServiceRunning` changes |

---

## 6. F1 — Permission Flow

### Role in Flow
Triggered when the user taps the orb for the first time (or each time a permission is missing). Runs through up to 4 permissions sequentially before starting services.

### Current Issues
- All 4 permission requests use `AlertDialog` (system default — small font, grey background). Jarring for elderly users who expect the app's consistent design language.
- Overlay and screen capture dialogs are especially terse and clinical.

### Permission Sequence
```
1. Notification  (Android 13+)
2. Microphone
3. Display over other apps  (Overlay)
4. Screen Capture
```

### Unified Permission BottomSheet Design

Replace all 4 `AlertDialog` calls with a consistent `BottomSheetDialog` using a new layout `dialog_permission_request.xml`.

```
╭────────────────────────────────────╮  bg: surface (white), top corners 24dp
│  ────                              │  drag handle
│                                    │
│         [ icon  64dp ]             │  permission-specific icon, tint: primary
│                                    │
│  SmartHelp+ needs permission       │  20sp bold, text_primary, centered
│  to display over other apps        │  16sp, text_secondary, centered, lineSpacing 1.4
│                                    │
│  ┌──────────────────────────────┐  │
│  │  Why we need this:           │  │  MaterialCardView, 0dp elevation, 16dp corners
│  │  • Show step-by-step arrows  │  │  bg: surface, padding 20dp
│  │  • Only activates when you   │  │  16sp, text_primary, lineSpacing 1.4
│  │    ask for help              │  │
│  └──────────────────────────────┘  │
│                                    │
│  ╭──────────────────────────────╮  │  56dp, full pill, bg: btn_primary_bg
│  │       Open Settings          │  │  17sp bold, white
│  ╰──────────────────────────────╯  │
│  ╭──────────────────────────────╮  │  50dp, outlined pill, color: primary
│  │          Not now             │  │  16sp bold
│  ╰──────────────────────────────╯  │
│                                    │
╰────────────────────────────────────╯
```

### Per-Permission Content

| Permission | Icon | Title | Bullet 1 | Bullet 2 |
|---|---|---|---|---|
| Notification | `ic_notifications` | "Notification Access" | "Alert you when guidance arrives" | "Only for SmartHelp+ activity" |
| Microphone | `ic_mic` | "Microphone Access" | "Hear your voice commands" | "Audio is never stored" |
| Overlay | `ic_layers` | "Display Over Apps" | "Show step-by-step guidance arrows" | "Only activates when you ask" |
| Screen Capture | `ic_screenshot` | "Screen Access" | "See what's on your screen to guide you" | "Screenshots are never stored or uploaded" |

### Changes Required
| File | Change |
|---|---|
| `dialog_permission_request.xml` | **New file** |
| `MainActivity.java` | Replace 4× `AlertDialog` with `BottomSheetDialog` using new layout |

---

## 7. S4 — Overlay Panel

### Role in Flow
The primary interaction surface. Appears at the bottom of the screen on top of all other apps after services start. User holds the mic to speak, receives voice guidance and visual highlights.

### Current Issues
- No brand identity — looks like a random floating button.
- `mic_button_background.xml` uses `#1565C0 → #1976D2` — light blue, does not match main screen orb (`#0A2342 → #1565C0`).
- `txtListening` floats disconnected at `top|center_horizontal`, far from the mic.
- `btnMinimize` is invisible (1dp × 1dp) despite Java already binding it to `switchToBallMode()`.
- `bg_mic_glowing_circle.xml` still references `brand_purple` (stale color token).
- No way to open chat history from the panel.

### Layout

```
Window params: MATCH_PARENT width, WRAP_CONTENT height, gravity: BOTTOM | CENTER
paddingHorizontal: 16dp, paddingBottom: 24dp

┌───────────────────────────────────────────────┐
│                                               │
│  ┌─────────────────────────────────────────┐  │
│  │ [logo 20dp]  SmartHelp+   [💬]   [⌄]   │  │  Control bar pill
│  └─────────────────────────────────────────┘  │  bg: #DD0A2342, cornerRadius: 24dp
│                bg_overlay_control_bar          │  white text 12sp bold
│                                               │  [💬] = btnChat → toggleChatPanel()
│                         marginTop: 10dp        │  [⌄]  = btnMinimize → switchToBallMode()
│                                               │
│            ┌──────────────────────┐           │
│            │  正在听... / 处理中   │           │  txtListening pill
│            └──────────────────────┘           │  bg: bg_overlay_status_pill (#DD0A2342)
│                bg_overlay_status_pill          │  white text 15sp bold
│                 visibility: GONE               │  paddingH: 20dp, paddingV: 8dp
│                                               │
│                     marginTop: 8dp             │
│                                               │
│          ╭──────── 104dp ────────╮            │
│        ╭─╰────────────────────────╯─╮         │
│        │        viewMicGlow          │         │  bg: bg_overlay_mic_glow
│        │   ╭────────────────────╮   │         │  visibility: GONE (shown when listening)
│        │   │   [MIC ICON 32dp]  │   │         │  animate: scale 0.92 → 1.08, alpha 0.45 → 0.1
│        │   │      88dp          │   │         │
│        │   ╰────────────────────╯   │         │  btnMicrophone: 88dp ImageButton
│        ╰─────────────────────────────╯         │  bg: mic_button_background (new deep navy)
│                                               │  tint: white, elevation: 8dp
└───────────────────────────────────────────────┘

Hidden compatibility views (1dp × 1dp, gone):
  cardGuidance (+ txtWelcomeLabel, txtGuidance, txtWelcomeSubtitle, layoutThinking)
  cardUserQuery (+ txtUserQuery)
  layoutDoneButtons (+ btnShowAgain, btnDoneStep)
  txtHint, txtInputLabel
```

### Visual States

| State | Control Bar | Status Pill | Glow Ring | Mic Orb |
|---|---|---|---|---|
| Idle (connected) | Visible | Hidden | Hidden | Deep navy, elevation 8dp |
| Idle (connecting) | Visible | Hidden | Hidden | Deep navy, 70% opacity |
| Listening | Visible | "正在听..." | Pulsing blue, visible | Scale 0.96×, alpha 0.88 |
| Processing | Visible | "处理中..." | Hidden | Normal, 60% opacity (disabled) |
| Error | Visible | Error text (red pill) | Hidden | Normal |

### Status Pill Text by State

| Trigger | Chinese | English |
|---|---|---|
| Mic held | 正在听... | Listening... |
| Transcript shown | `<transcript text>` | `<transcript text>` |
| Mic released, transcript OK | 已收到 | Received |
| Gemini processing | 处理中... | Processing... |
| Didn't catch | 没听清 | Didn't catch that |

### Mic Interaction

- **Tap** → start/stop listening (toggles).
- **Hold (180ms delay)** → start listening (hold-to-talk mode).
- **Release** → stop listening, submit transcript.

### New Drawables Required

| File | Spec |
|---|---|
| `bg_overlay_control_bar.xml` | `<solid color="#DD0A2342" />`, `<corners radius="24dp" />` |
| `bg_overlay_status_pill.xml` | `<solid color="#DD0A2342" />`, `<corners radius="20dp" />` |

### Modified Drawables

| File | Change |
|---|---|
| `mic_button_background.xml` | Gradient: `startColor="#0A2342"`, `centerColor="#0D47A1"`, `endColor="#1565C0"`, `angle="135"` (oval shape, keep ripple wrapper) |
| `bg_overlay_mic_glow.xml` | Replace `brand_purple_10` / `brand_purple` tokens with `#1A1565C0` / `#661565C0` |
| `bg_mic_glowing_circle.xml` | Replace `@color/brand_purple` and `@color/brand_purple_10` with hex values `#1565C0` and `#1A1565C0` |

### Changes Required
| File | Change |
|---|---|
| `overlay_guidance.xml` | Full rewrite — add control bar, reposition txtListening above orb, make btnMinimize a real visible button, add btnChat |
| `OverlayService.java` | Wire `btnChat` to `toggleChatPanel()`; make btnMinimize 32dp visible icon |

---

## 8. S5 — Floating Ball Mode

### Role in Flow
Active when a task is underway and the user needs to interact with the underlying app. The ball stays out of the way (draggable, snaps to screen edge) while keeping guidance accessible.

### Current Issues
- No explicit way for the user to return to the full panel from ball mode.
- Popup card (Done / Show Again) has no "speak again" entry point.
- Ball has only a 1.5dp `#E3F2FD` stroke — nearly invisible.

### Ball Layout

```
╭──────────────────────────────────────────╮
│ [Detail Popup Card — visible on tap]     │
├──────────────────────────────────────────┤
│ [Speech Bubble]  ▷  [Ball 64dp]          │
╰──────────────────────────────────────────╯
```

### Ball Body (64dp circle)

```
╭──────────────────╮
│  [ LOGO 40dp ]   │  ImageView, scaleType: fitCenter
╰──────────────────╯

bg: bg_ball_white (white oval)
stroke: 2dp, #421565C0   ← increase from current 1.5dp #E3F2FD
elevation: 10dp           ← increase from current (default ~2dp)
```

### Speech Bubble (auto-shown on new instruction)

```
╭──────────────────────────────╮   ▷  ● ball
│ [logo 26dp]  [instruction]   │
╰──────────────────────────────╯
bg: bg_ball_speech_bubble (existing)
max width: 220dp, 3 lines max
```

### Detail Popup Card (tap ball → toggle)

```
╭──────────────────────────────────────╮
│ [logo 40dp]  SmartHelp+        [×]  │  Header
├──────────────────────────────────────┤
│  Step instruction text...            │  txtGuidanceBall, 15sp, #0A2342
│  lineSpacingMultiplier: 1.3          │
├──────────────────────────────────────┤
│ [🎤 新请求] [🔁 再显示] [✓ 完成]    │  3 buttons, horizontal, layoutDoneBallButtons
╰──────────────────────────────────────╯
bg: bg_card_white (white, 16dp corners)
elevation: 10dp
width: 260dp
```

### Popup Buttons

| Button | Label (ZH) | Label (EN) | Action |
|---|---|---|---|
| `btnExpandPanel` (new) | 🎤 新请求 | 🎤 New Request | `switchToInputMode()` + 300ms delay → `startListening()` |
| `btnShowAgainBall` | 🔁 再显示 | 🔁 Show Again | Re-show last highlight + replay voice |
| `btnDoneBall` | ✓ 完成 | ✓ Done | `analyzeCurrentScreen(true)` — verify step |

### Ball Tap Logic

```
Tap ball
  → Popup visible?     → Collapse popup
  → Bubble visible?    → Show popup (with instruction)
  → Nothing visible?   → Show bubble
```

### Changes Required
| File | Change |
|---|---|
| `overlay_ball.xml` | Add `btnExpandPanel` (🎤 新请求) as third button in `layoutDoneBallButtons` |
| `bg_ball_white.xml` | Increase stroke width to 2dp, change color to `#421565C0` |
| `OverlayService.java` | Wire `btnExpandPanel` → `switchToInputMode()` + `handler.postDelayed(() -> startListening(), 300)` |

---

## 9. S6 — Chat History Panel

### Role in Flow
Allows the user to review the full conversation — every request they made and every response the AI gave. Opened via `[💬]` on the overlay control bar or ball popup. Does not interrupt any active session.

### This is a fully new feature.

### Window Setup

- Separate overlay window (`windowManager.addView`), same approach as ball.
- Window params: `MATCH_PARENT` width, `MATCH_PARENT` height, `TYPE_APPLICATION_OVERLAY`.
- Panel itself is anchored to the bottom, occupying 62% of screen height.
- The upper 38% is a semi-transparent scrim — tapping it closes the panel.

### Layout (`overlay_chat.xml`)

```
FrameLayout — match_parent × match_parent
│
├── View (scrim, match_parent × match_parent)
│     bg: #99000000
│     clickable: true → close panel
│
└── LinearLayout (match_parent, wrap_content, gravity: bottom)
      orientation: vertical
      ╭────────────────────────────────────╮
      │  ────                              │  Handle: 36dp × 4dp, #D1D5DB, centered, marginTop 12dp
      │                                   │
      │  [logo 28dp]  对话记录    [×]     │  Header row
      │               Chat History         │  18sp bold, #0A2342
      │                                   │
      ├────────────────────────────────── ┤
      │                                   │
      │  (ScrollView, weight=1)           │
      │    LinearLayout chatMessageContainer│
      │      paddingH: 16dp, paddingV: 12dp│
      │                                   │
      │              [User bubble]   ──►  │  Right-aligned
      │                                   │
      │  ◄──  [AI card]                   │  Left-aligned
      │                                   │
      │              [User bubble]   ──►  │
      │                                   │
      ├────────────────────────────────── ┤
      │  paddingBottom: 16dp              │
      ╰────────────────────────────────────╯
      bg: bg_chat_panel (white, topLeftRadius 24dp, topRightRadius 24dp)
      elevation: 12dp
      maxHeight: 62% screen height
```

### Message Styles

**User message (right-aligned):**
```
                    ╭──────────────────────╮
                    │  帮我给妈妈发消息      │
                    ╰──────────────────────╯
layout_gravity: end
bg: bg_chat_user_bubble
  → solid: #1565C0
  → corners: 14dp, except bottom-right: 4dp
textColor: #FFFFFF
textSize: 15sp
padding: 12dp H, 10dp V
maxWidth: 75% screen width
marginBottom: 8dp
```

**AI message (left-aligned):**
```
╭──────────────────────────────────╮
│ ▌  好的，请先打开 WhatsApp...    │
╰──────────────────────────────────╯
layout_gravity: start
bg: bg_chat_ai_card
  → solid: #FFFFFF
  → corners: 14dp, except top-left: 4dp
  → left border: 3dp solid #1565C0
textColor: #0A2342
textSize: 15sp
lineSpacingMultiplier: 1.4
padding: 12dp H, 10dp V
maxWidth: 80% screen width
elevation: 2dp
marginBottom: 8dp
```

### When to Add Messages

| Trigger | Sender | Text |
|---|---|---|
| User releases mic, transcript ready | User | transcript text |
| AI guidance text received (`onInstructionText`) | AI | instruction text |
| Task complete | AI | "✓ 任务完成！ / Task complete!" |
| Error | AI | error message |

### Implementation Notes

- Max 50 messages in `chatHistory` list (drop oldest when exceeded).
- Auto-scroll to bottom on each new message.
- Messages persist for the duration of the service session (reset on `stopSelf()`).
- Panel opens/closes with a slide-up/slide-down animation (200ms, ease-out).

### New Files Required

| File | Spec |
|---|---|
| `overlay_chat.xml` | Full layout as described above |
| `bg_chat_user_bubble.xml` | `solid: #1565C0`, corners: 14dp all, bottom-right: 4dp |
| `bg_chat_ai_card.xml` | `solid: #FFFFFF`, corners: 14dp all, top-left: 4dp; left inset item with `solid: #1565C0`, width: 3dp |
| `bg_chat_panel.xml` | `solid: #FFFFFF`, topLeftRadius: 24dp, topRightRadius: 24dp |

### Java Changes Required (`OverlayService.java`)

```java
// New fields
private View chatView;
private WindowManager.LayoutParams chatParams;
private LinearLayout chatMessageContainer;
private ScrollView chatScrollView;
private boolean isChatOpen = false;
private static final int MAX_CHAT_MESSAGES = 50;

// New methods
private void createChatOverlay()
private void toggleChatPanel()
private void addChatMessage(boolean isUser, String text)
private View buildUserBubble(String text)
private View buildAiBubble(String text)
```

---

## 10. S7 — Task Complete

### Role in Flow
Shown when the AI confirms all task steps are done (`onTaskComplete(goal)`). Gives the user a clear success moment and options to continue or go home.

### Current Issue
`dialog_task_complete.xml` is fully designed and correct, but **it is never shown**. `OverlayService.onTaskComplete()` only updates a hidden text view with "✓ Task complete!" — the user sees nothing celebratory.

### Layout (existing `dialog_task_complete.xml`, minor update)

```
BottomSheetDialog — non-cancellable

╭────────────────────────────────────────╮
│  ────                                  │  Drag handle: 36dp × 4dp
│                                        │
│         ╭──────────────╮              │
│         │      ✓       │  80dp        │  circle_success_bg (green circle)
│         ╰──────────────╯              │  checkmark icon 44dp, white
│                                        │
│         任务完成！                      │  26sp bold, text_primary, centered
│         Task Complete!                 │
│                                        │
│  "帮我给妈妈发消息" — done              │  16sp, text_secondary, centered  ← show goal
│                                        │  (currently shows static subtitle string)
│                                        │
│  需要其他帮助吗？                       │  14sp, text_hint, centered
│  Need anything else?                   │
│                                        │
│  ╭──────────────────────────────────╮  │
│  │        🎤 再问一个 / Ask Again    │  │  56dp, full pill
│  ╰──────────────────────────────────╯  │  bg: gradient #0A2342 → #1565C0
│  ╭──────────────────────────────────╮  │
│  │          回主页 / Go Home         │  │  50dp, outlined pill
│  ╰──────────────────────────────────╯  │  color: primary
╰────────────────────────────────────────╯
```

### Button Behaviour

| Button | Action |
|---|---|
| "Ask Again" | Dismiss dialog → overlay panel stays visible → `startListening(stayInInputBar: true)` |
| "Go Home" | Dismiss dialog → `stopSelf()` → broadcast to MainActivity to update orb to idle state |

### Changes Required
| File | Change |
|---|---|
| `dialog_task_complete.xml` | Set `txtCompleteSubtitle` to display `goal` text dynamically (remove static string) |
| `OverlayService.java` | In `onTaskComplete()`: create and show `BottomSheetDialog` with `dialog_task_complete` layout; pass `goal` to `txtCompleteSubtitle`; wire `btnAskAnother` and `btnGoHome` |

---

## 11. Full Interaction Flow

```
[S1 Splash]
     │
     │  fade_in / fade_out transition (1800ms)
     ▼
     ├── First launch ──► [S2 Onboarding] ──► [Privacy Notice] ──► [S3 Home]
     │
     └── Return launch ──► [S3 Home]
                                │
                         Tap orb (idle)
                                │
                         [F1 Permission Check]
                          ├── All granted ──────────────────────┐
                          └── Missing permission                 │
                                │                                │
                         [Permission BottomSheet]                │
                                │                                │
                         Granted ──────────────────────────────►│
                                                                  │
                                                     [S4 Overlay Panel — Idle]
                                                                  │
                                                    User holds mic / taps mic
                                                                  │
                                                     [S4 Overlay Panel — Listening]
                                                                  │
                                                         User releases mic
                                                                  │
                                                     [S4 Overlay Panel — Processing]
                                                                  │
                                               ┌──────────────────┼──────────────────┐
                                               │                  │                  │
                                          AI responds         Tap [💬]          Tap [⌄]
                                               │                  │                  │
                                    Highlight arrow appears  [S6 Chat Panel]  [S5 Ball Mode]
                                    Voice plays                   │                  │
                                               │              Tap [×]           Tap ball
                                               │              or scrim               │
                                               │                  │            Show bubble
                                               │                  ▼            Tap bubble
                                               │           Back to S4          Show popup
                                               │                               │         │
                                               │                        [🎤 New Request] │
                                               │                               │    [✓ Done]
                                               │                        switchToInputMode   │
                                               │                        + startListening    │
                                               │                                     analyzeScreen
                                               │                                           │
                                               └───────────────────────────── onTaskComplete()
                                                                                           │
                                                                               [S7 Task Complete]
                                                                                           │
                                                                              ┌────────────┴──────────────┐
                                                                              │                           │
                                                                    [Ask Again]                    [Go Home]
                                                                    startListening()               stopSelf()
                                                                    Back to S4                     Back to S3
```

---

## 12. File Change Summary

### New Files

| File | Location | Purpose |
|---|---|---|
| `overlay_chat.xml` | `res/layout/` | Chat history panel layout |
| `dialog_permission_request.xml` | `res/layout/` | Unified permission bottom sheet |
| `bg_overlay_control_bar.xml` | `res/drawable/` | Control bar pill background |
| `bg_overlay_status_pill.xml` | `res/drawable/` | Status text pill background |
| `bg_chat_user_bubble.xml` | `res/drawable/` | User message bubble |
| `bg_chat_ai_card.xml` | `res/drawable/` | AI message card with left border |
| `bg_chat_panel.xml` | `res/drawable/` | Chat panel bottom surface |

### Modified Layout Files

| File | Changes |
|---|---|
| `overlay_guidance.xml` | Full rewrite: control bar + btnChat + visible btnMinimize + reposition txtListening + orb resize to 88dp |
| `overlay_ball.xml` | Add `btnExpandPanel` (🎤 新请求) to `layoutDoneBallButtons` |
| `dialog_onboarding.xml` | Replace `bg_info_card_purple` cards with left-border white cards; update text colors; fix button pill |
| `dialog_task_complete.xml` | Minor: `txtCompleteSubtitle` made dynamic (receives goal text) |
| `activity_main.xml` | Replace `bg_info_card_purple` on history rows with `bg_info_card_blue` |

### Modified Drawable Files

| File | Changes |
|---|---|
| `mic_button_background.xml` | Gradient: `#0A2342 → #0D47A1 → #1565C0`, angle 135°, oval shape |
| `bg_welcome_cta_idle.xml` | Change `android:shape` from `rectangle` to `oval`, remove `<corners>` |
| `bg_overlay_mic_glow.xml` | Verify color values match current blue palette |
| `bg_mic_glowing_circle.xml` | Replace `@color/brand_purple` → `#1565C0`, `@color/brand_purple_10` → `#1A1565C0` |
| `bg_ball_white.xml` | Increase stroke width to `2dp`, change color to `#421565C0` |

### Modified Java Files

| File | Changes |
|---|---|
| `SplashActivity.java` | Add `fade_in / fade_out` transition before `finish()` |
| `MainActivity.java` | (1) Replace 4× `AlertDialog` in permission flow with `BottomSheetDialog` using `dialog_permission_request.xml`. (2) Switch orb gradient + ring animation speed/color when `isServiceRunning` state changes |
| `OverlayService.java` | (1) Wire `btnChat` → `toggleChatPanel()`. (2) Wire `btnExpandPanel` → `switchToInputMode()` + delayed `startListening()`. (3) Add chat overlay: `createChatOverlay()`, `toggleChatPanel()`, `addChatMessage()`. (4) Fix `onTaskComplete()` to show `dialog_task_complete` BottomSheetDialog with goal text. |

### Implementation Order

```
Step 1 — Drawables (no logic, establishes visual foundation)
  → All 7 new drawables
  → 5 modified drawables

Step 2 — Overlay Panel
  → overlay_guidance.xml (rewrite)
  → OverlayService.java: wire btnChat + btnMinimize visibility

Step 3 — Ball Mode
  → overlay_ball.xml (add expand button)
  → bg_ball_white.xml
  → OverlayService.java: wire btnExpandPanel

Step 4 — Chat Panel
  → overlay_chat.xml (new)
  → OverlayService.java: createChatOverlay + toggleChatPanel + addChatMessage

Step 5 — Task Complete
  → dialog_task_complete.xml (minor update)
  → OverlayService.java: onTaskComplete BottomSheetDialog

Step 6 — Home Screen & Permission Flow
  → activity_main.xml (history card color)
  → bg_welcome_cta_idle.xml (oval fix)
  → MainActivity.java (orb state + permission BottomSheets)

Step 7 — Onboarding & Splash
  → dialog_onboarding.xml (card colors)
  → SplashActivity.java (transition animation)
```
