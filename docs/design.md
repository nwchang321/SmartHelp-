# SmartHelp+ Design Guide

## 1. Design Direction

SmartHelp+ currently uses a calm, clean, senior-friendly visual language built around:

- light blue gradients instead of dark or high-saturation themes
- white rounded surfaces over soft blue backgrounds
- large touch targets and bold headings
- low visual noise in the live guidance overlay
- a voice-first interaction model

The app does **not** currently look like a chatbot product. It is closer to:

- a guided assistant
- a simplified accessibility utility
- a gentle, voice-led mobile helper

The visual tone is:

- trustworthy
- soft
- spacious
- modern
- non-threatening for older users

## 2. Core Visual Identity

### Primary Color System

The real brand color is currently **blue**, even though some legacy token names still say `purple`.

Primary palette from [`colors.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/values/colors.xml):

- Primary: `#1565C0`
- Primary dark: `#0D47A1`
- Primary light: `#42A5F5`
- Accent: `#00ACC1`
- Background: `#E3F2FD`
- Surface: `#FFFFFF`
- Surface variant: `#E3F4FF`
- Text primary: `#1C1B1F`
- Text secondary: `#6B7280`
- Success: `#10B981`
- Warning: `#F59E0B`
- Error: `#EF4444`

### Gradient Language

The product relies on soft top-to-bottom blue gradients rather than flat fills.

Examples:

- Hero background: [`bg_hero_gradient.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/drawable/bg_hero_gradient.xml)
- Mic button gradient: [`mic_button_background.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/drawable/mic_button_background.xml)

This creates a gentle "AI assistant" feel without looking overly technical.

## 3. Layout Style

### Overall Structure

The main screens follow a two-zone composition:

1. `Hero area`
   A blue gradient area with centered branding, greeting, and the primary action.

2. `Body surface`
   A white rounded sheet with content sections, settings, history, or detail items.

This pattern is visible in:

- [`activity_main.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/layout/activity_main.xml)
- [`activity_settings.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/layout/activity_settings.xml)

The white body surface style is defined in:

- [`bg_body_surface.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/drawable/bg_body_surface.xml)

### Shape Language

The app strongly favors:

- large corner radii
- pill controls
- circles for important actions
- rounded cards instead of sharp rectangles

This makes the UI feel softer and easier to approach.

## 4. Typography

Theme tokens are defined in [`themes.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/values/themes.xml).

Current typography direction:

- Headlines are bold and large
- Body text is readable and airy
- Guidance text is slightly larger and stronger than normal body text
- Captions are small but still soft grey, not low-contrast ultra-light text

Key text styles:

- Display: `34sp`
- Headline: `28sp`
- Title: `22sp`
- Body: `17sp`
- Guidance: `18sp`
- Caption: `13sp`

The font stack is currently default Android `sans-serif`, which is acceptable for clarity.

## 5. Component Language

### Buttons

Buttons are mostly iOS-like capsules:

- large height
- round corners
- bold labels
- low elevation

Primary buttons are blue filled pills. Secondary actions are outlined or soft filled pills.

Relevant styles:

- `Widget.SmartHelp.Button.Primary`
- `Widget.SmartHelp.Button.Secondary`
- `Widget.SmartHelp.Button.OverlayAction`
- `Widget.SmartHelp.Button.Microphone`

All defined in [`themes.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/values/themes.xml).

### Cards

Cards are clean and mostly white, with rounded corners and minimal borders.

The app should continue to prefer:

- flat or lightly elevated cards
- subtle section grouping
- one strong focal item per area

### Pills and Glassy Utility Controls

Translucent pills are used for utility actions like settings, connection state, and compact labels.

Example:

- [`bg_translucent_pill.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/drawable/bg_translucent_pill.xml)

This gives secondary controls a lightweight, non-threatening feel.

## 6. Overlay Design

### Current Overlay Direction

The overlay has now moved toward an intentionally minimal voice-first design:

- one bottom-centered mic
- one large listening/status label when needed
- no permanent text input field
- no quick-action chips
- no dense floating panel

See:

- [`overlay_guidance.xml`](/C:/Users/HP/Downloads/FYP%201/SmartHelp/android/app/src/main/res/layout/overlay_guidance.xml)

### Overlay Principles

The overlay should remain:

- minimal
- voice-first
- low-distraction
- readable from a distance
- usable with uncertain hands and slower reactions

The overlay should **not** return to:

- chat-style input bars
- multiple competing buttons
- horizontally scrolling quick actions
- decorative animation that distracts from the next step

### Current Mic Interaction Language

The mic interaction now communicates state through:

- glow/pulse on hold
- large `正在听... / Listening...`
- `已收到 / Received`
- `处理中... / Processing...`

This is the correct direction for elderly users because it gives status clarity without requiring reading a transcript.

## 7. Senior-Friendly Rules

These rules match the current best direction of the app and should be preserved:

- one primary action per screen
- large touch targets
- short action-first text
- high contrast between text and background
- stable control positions
- limited color variety within a single surface
- low cognitive load before task start

For SmartHelp+, "elder-friendly" means:

- fewer choices
- fewer words
- stronger state feedback
- less decoration
- more confidence

## 8. Design Strengths

The current app already has several strong visual decisions:

- soft blue palette feels trustworthy and medical/helpful rather than playful
- hero + white sheet layout feels clear and modern
- large centered mic is easy to understand
- rounded shapes reduce harshness
- overlay simplification is moving in the right direction

## 9. Current Inconsistencies

These are the main design inconsistencies in the codebase right now:

1. Some token names still say `purple` while the actual brand is blue.
2. Some older resources still reflect a busier, more card-heavy UI language than the new minimalist overlay.
3. Main screens are polished, but certain legacy overlay resources still exist and can confuse future design work.
4. Some comments in theme files describe the app as "Premium Purple iOS-Inspired", which no longer matches the actual palette.

These should be treated as cleanup issues, not new design direction.

## 10. Recommended Design Standard Going Forward

Use this as the default visual standard for SmartHelp+:

- Primary brand = calm blue
- Backgrounds = soft blue gradient or plain white
- Surfaces = white, rounded, breathable
- Primary action = one strong circular or pill control
- Text = bold headlines, short readable instructions
- Motion = meaningful status feedback only
- Overlay = minimal voice tool, not a chat window

## 11. Design Guardrails

Do:

- keep the interface quiet
- prioritize voice interaction over text entry
- use bold, direct verbs
- use status feedback to reassure the user
- keep important actions centered and predictable

Do not:

- add more chips, quick links, or multi-choice menus to the overlay
- turn the assistant into a chat interface
- introduce dark-heavy or neon styling
- mix too many accent colors on one screen
- use decorative motion that does not help comprehension

## 12. One-Sentence Summary

SmartHelp+ should look like a calm blue voice-guidance assistant for older adults: soft, clear, spacious, and focused on one action at a time.
