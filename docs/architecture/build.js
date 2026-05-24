// SmartHelp+ Architecture Diagrams — 9 slides
// Run: node build.js  →  produces SmartHelp_Architecture.pptx
const pptxgen = require("pptxgenjs");

// ── Palette: Ocean Gradient ──────────────────────────────────────────────
const C = {
  primary: "065A82",    // deep blue
  secondary: "1C7293",  // teal
  tertiary: "21295C",   // midnight
  accent: "F59E0B",     // orange highlight
  success: "10B981",    // green
  danger: "EF4444",     // red
  warn: "EAB308",       // yellow
  bgLight: "F8FAFC",
  bgWhite: "FFFFFF",
  textDark: "0F172A",
  textBody: "1E293B",
  textMuted: "64748B",
  border: "CBD5E1",
  borderLight: "E2E8F0",
};

const F = { header: "Calibri", body: "Calibri" };

// Reusable shadow factory (pptxgenjs mutates shadow objects, must return fresh each call)
const shadow = () => ({ type: "outer", blur: 6, offset: 2, angle: 90, color: "000000", opacity: 0.10 });

let pres = new pptxgen();
pres.layout = "LAYOUT_16x9"; // 10" × 5.625"
pres.author = "SmartHelp+ FYP";
pres.title = "SmartHelp+ System Architecture";

// ─── HELPERS ─────────────────────────────────────────────────────────────
function addTitle(slide, text, subtitle) {
  slide.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0, w: 10, h: 0.65, fill: { color: C.tertiary }, line: { color: C.tertiary }
  });
  slide.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0.65, w: 10, h: 0.06, fill: { color: C.accent }, line: { color: C.accent }
  });
  slide.addText(text, {
    x: 0.4, y: 0.05, w: 7.5, h: 0.55, fontSize: 22, bold: true, color: C.bgWhite,
    fontFace: F.header, valign: "middle", margin: 0
  });
  if (subtitle) {
    slide.addText(subtitle, {
      x: 7.7, y: 0.1, w: 2.2, h: 0.45, fontSize: 10, color: "CADCFC",
      fontFace: F.body, align: "right", valign: "middle", margin: 0
    });
  }
}

function addFooter(slide, page, total) {
  slide.addText(`SmartHelp+ Architecture · ${page}/${total}`, {
    x: 0, y: 5.35, w: 10, h: 0.25, fontSize: 9, color: C.textMuted,
    align: "center", fontFace: F.body, margin: 0
  });
}

function box(slide, opts) {
  // opts: {x, y, w, h, title, subtitle, color, bgColor, accentLeft, fontSize}
  const fontSize = opts.fontSize || 11;
  const titleSize = opts.titleSize || 12;
  const bg = opts.bgColor || C.bgWhite;
  slide.addShape(pres.shapes.RECTANGLE, {
    x: opts.x, y: opts.y, w: opts.w, h: opts.h,
    fill: { color: bg }, line: { color: opts.borderColor || C.border, width: 1 },
    shadow: shadow()
  });
  if (opts.accentLeft) {
    slide.addShape(pres.shapes.RECTANGLE, {
      x: opts.x, y: opts.y, w: 0.08, h: opts.h,
      fill: { color: opts.accentLeft }, line: { color: opts.accentLeft }
    });
  }
  let cy = opts.y + 0.08;
  if (opts.title) {
    slide.addText(opts.title, {
      x: opts.x + 0.15, y: cy, w: opts.w - 0.2, h: 0.32,
      fontSize: titleSize, bold: true, color: opts.color || C.textDark,
      fontFace: F.header, valign: "middle", margin: 0
    });
    cy += 0.32;
  }
  if (opts.subtitle) {
    slide.addText(opts.subtitle, {
      x: opts.x + 0.15, y: cy, w: opts.w - 0.2, h: opts.h - (cy - opts.y) - 0.08,
      fontSize: fontSize, color: opts.subColor || C.textBody,
      fontFace: F.body, valign: "top", margin: 0,
      paraSpaceAfter: 2
    });
  }
}

function arrow(slide, x1, y1, x2, y2, color, width, label, dashed) {
  const lineOpts = {
    x: x1, y: y1, w: x2 - x1, h: y2 - y1,
    line: {
      color: color || C.secondary,
      width: width || 1.5,
      endArrowType: "triangle",
      ...(dashed ? { dashType: "dash" } : {})
    }
  };
  slide.addShape(pres.shapes.LINE, lineOpts);
  if (label) {
    const midX = (x1 + x2) / 2;
    const midY = (y1 + y2) / 2;
    slide.addText(label, {
      x: midX - 0.6, y: midY - 0.18, w: 1.2, h: 0.3,
      fontSize: 8, color: color || C.textMuted, italic: true,
      align: "center", valign: "middle", fontFace: F.body, margin: 0,
      fill: { color: C.bgWhite }
    });
  }
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 1 — TITLE
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.tertiary };

  // Decorative diagonal accent bar
  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 0, w: 10, h: 0.15, fill: { color: C.accent }, line: { color: C.accent }
  });
  s.addShape(pres.shapes.RECTANGLE, {
    x: 0, y: 5.475, w: 10, h: 0.15, fill: { color: C.accent }, line: { color: C.accent }
  });

  s.addText("SmartHelp+", {
    x: 0.5, y: 1.3, w: 9, h: 0.9,
    fontSize: 56, bold: true, color: C.bgWhite,
    fontFace: F.header, align: "center", valign: "middle", margin: 0,
    charSpacing: 2
  });
  s.addText("System Architecture", {
    x: 0.5, y: 2.25, w: 9, h: 0.5,
    fontSize: 26, color: "CADCFC",
    fontFace: F.header, align: "center", valign: "middle", margin: 0
  });

  // Pillars under title
  const pillars = [
    { icon: "📱", label: "Android Java" },
    { icon: "🌐", label: "LiveKit WebRTC" },
    { icon: "🐍", label: "Python Agent" },
    { icon: "🤖", label: "Gemini APIs" },
  ];
  pillars.forEach((p, i) => {
    const px = 1.0 + i * 2.0;
    s.addText(p.icon, {
      x: px, y: 3.2, w: 1.8, h: 0.5,
      fontSize: 28, align: "center", valign: "middle", margin: 0
    });
    s.addText(p.label, {
      x: px, y: 3.7, w: 1.8, h: 0.3,
      fontSize: 12, color: "CADCFC", bold: true,
      fontFace: F.body, align: "center", valign: "middle", margin: 0
    });
  });

  s.addText("Voice-First AI Guidance for Elderly Users  ·  FYP 2026", {
    x: 0.5, y: 4.4, w: 9, h: 0.4,
    fontSize: 14, italic: true, color: "97BCEC",
    fontFace: F.body, align: "center", valign: "middle", margin: 0
  });
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 2 — SYSTEM TOPOLOGY (3-tier overview)
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "1.  System Topology", "3 tiers · 5 services");

  // Three tier headers across the top
  const tierY = 1.0;
  const tierH = 0.32;
  const tiers = [
    { x: 0.4, w: 2.3, label: "MOBILE TIER", color: C.primary },
    { x: 2.85, w: 2.0, label: "EDGE TIER", color: C.secondary },
    { x: 4.95, w: 4.65, label: "CLOUD TIER", color: C.tertiary },
  ];
  tiers.forEach(t => {
    s.addShape(pres.shapes.RECTANGLE, {
      x: t.x, y: tierY, w: t.w, h: tierH,
      fill: { color: t.color }, line: { color: t.color }
    });
    s.addText(t.label, {
      x: t.x, y: tierY, w: t.w, h: tierH,
      fontSize: 10, bold: true, color: C.bgWhite, fontFace: F.header,
      align: "center", valign: "middle", charSpacing: 2, margin: 0
    });
  });

  // Boxes per service
  // Android (mobile tier)
  box(s, {
    x: 0.4, y: 1.45, w: 2.3, h: 3.5, accentLeft: C.primary,
    title: "📱 Android Device",
    subtitle: "Java · API 26-34\n\n• OverlayService (FG)\n• ScreenCaptureService\n• HighlightOverlayView\n• AccessibilityService\n• ServerConnection\n  (LiveKit Room API)\n• MessageProtocol\n  (JSON envelope)",
    titleSize: 13, fontSize: 10
  });

  // Token Server (edge)
  box(s, {
    x: 2.85, y: 1.45, w: 2.0, h: 1.65, accentLeft: C.secondary,
    title: "💻 Token Server",
    subtitle: "Python · port 8765\n\n• /token (LiveKit JWT)\n• /tts (TTS fallback)\n• /health",
    titleSize: 12, fontSize: 9
  });

  // LiveKit Cloud (cloud tier — left)
  box(s, {
    x: 5.0, y: 1.45, w: 2.0, h: 1.65, accentLeft: C.tertiary,
    title: "☁️ LiveKit Cloud",
    subtitle: "WebRTC SFU\n\n• Room signaling\n• Audio tracks (16k↑/24k↓)\n• Data channel (JSON)\n• Video tracks (opt)",
    titleSize: 12, fontSize: 9
  });

  // Python Agent (edge — bottom)
  box(s, {
    x: 2.85, y: 3.25, w: 2.0, h: 1.7, accentLeft: C.secondary,
    title: "🐍 Python Agent",
    subtitle: "LiveKit Worker\n\n• agent.py\n• Task FSM\n• Vision pipeline\n• STT + TTS bridge",
    titleSize: 12, fontSize: 9
  });

  // Gemini APIs (cloud — right)
  box(s, {
    x: 7.15, y: 1.45, w: 2.45, h: 3.5, accentLeft: C.accent,
    title: "🤖 Gemini APIs",
    subtitle: "Google Cloud\n\n• gemini-2.5-flash-lite\n  → intent + ReAct navigation\n• gemini-2.5-flash\n  → vision grounding\n• gemini-3.1-flash-tts\n  → voice synthesis\n• Google Cloud STT\n  → speech recognition",
    titleSize: 13, fontSize: 10
  });

  // Connection arrows
  arrow(s, 2.7, 2.27, 2.85, 2.27, C.accent, 2, "① HTTP", false);
  arrow(s, 2.7, 4.1, 2.85, 4.1, C.primary, 2, "③ tracks", false);
  arrow(s, 4.85, 2.27, 5.0, 2.27, C.secondary, 1.5);
  arrow(s, 6.0, 3.1, 4.0, 3.25, C.secondary, 1.5, "② WebRTC", false);
  arrow(s, 4.85, 4.1, 6.95, 2.5, C.secondary, 1.5);
  arrow(s, 4.85, 4.1, 7.15, 4.0, C.accent, 1.5, "④ REST", false);

  addFooter(s, 2, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 3 — END-TO-END DATA FLOW (sequence diagram)
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "2.  End-to-End Data Flow", "User says \"Open WhatsApp\"");

  // Actor lanes
  const actors = [
    { x: 0.5,  w: 1.4, label: "USER",    color: C.tertiary },
    { x: 2.0,  w: 1.4, label: "ANDROID",  color: C.primary },
    { x: 3.5,  w: 1.4, label: "LIVEKIT",  color: C.secondary },
    { x: 5.0,  w: 1.7, label: "PYTHON",   color: C.secondary },
    { x: 6.85, w: 1.4, label: "GEMINI",   color: C.accent },
    { x: 8.35, w: 1.3, label: "RESULT",   color: C.success },
  ];
  const laneTop = 1.0, laneBottom = 5.0;
  actors.forEach(a => {
    // Header pill
    slideRoundedHeader(s, a);
    // Vertical lifeline
    s.addShape(pres.shapes.LINE, {
      x: a.x + a.w / 2, y: 1.4, w: 0, h: laneBottom - 1.4,
      line: { color: C.borderLight, width: 1, dashType: "dash" }
    });
  });

  function slideRoundedHeader(s, a) {
    s.addShape(pres.shapes.RECTANGLE, {
      x: a.x, y: laneTop, w: a.w, h: 0.4,
      fill: { color: a.color }, line: { color: a.color }
    });
    s.addText(a.label, {
      x: a.x, y: laneTop, w: a.w, h: 0.4,
      fontSize: 11, bold: true, color: C.bgWhite, fontFace: F.header,
      align: "center", valign: "middle", margin: 0, charSpacing: 1
    });
  }

  // Steps  (from / to indices into actors)
  const cx = i => actors[i].x + actors[i].w / 2;
  const steps = [
    { y: 1.55, from: 0, to: 1, label: "① 说话: Open WhatsApp", color: C.tertiary },
    { y: 1.85, from: 1, to: 2, label: "② publish mic + DataChannel", color: C.primary },
    { y: 2.15, from: 2, to: 3, label: "③ audio frames", color: C.secondary },
    { y: 2.45, from: 3, to: 4, label: "④ STT (Google Cloud)", color: C.accent },
    { y: 2.75, from: 4, to: 3, label: "↩ \"Open WhatsApp\"", color: C.accent, reverse: true },
    { y: 3.05, from: 3, to: 4, label: "⑤ Intent Agent (flash-lite)", color: C.accent },
    { y: 3.35, from: 3, to: 4, label: "⑥ ReAct decision", color: C.accent },
    { y: 3.65, from: 3, to: 1, label: "⑦ Speak: \"Tap WhatsApp\"", color: C.success, reverse: true },
    { y: 3.95, from: 1, to: 3, label: "⑧ Send screenshot (chunked)", color: C.primary },
    { y: 4.25, from: 3, to: 4, label: "⑨ Vision (flash + tool call)", color: C.accent },
    { y: 4.55, from: 3, to: 5, label: "⑩ Highlight + audio → user", color: C.success, reverse: false, toResult: true },
  ];

  steps.forEach(st => {
    const x1 = cx(st.from);
    const x2 = st.toResult ? cx(5) : cx(st.to);
    s.addShape(pres.shapes.LINE, {
      x: Math.min(x1, x2), y: st.y, w: Math.abs(x2 - x1), h: 0,
      line: {
        color: st.color, width: 1.5,
        ...(x2 > x1 ? { endArrowType: "triangle" } : { beginArrowType: "triangle" })
      }
    });
    // Label above the line
    const labelW = 4.0;
    const labelX = (x1 + x2) / 2 - labelW / 2;
    s.addText(st.label, {
      x: labelX, y: st.y - 0.18, w: labelW, h: 0.18,
      fontSize: 9, color: st.color, bold: true,
      align: "center", valign: "middle", fontFace: F.body, margin: 0,
      fill: { color: C.bgWhite }
    });
  });

  // Bottom annotation
  s.addText("Loop continues for VERIFY → next step → COMPLETED.   ~2-4s per turn end-to-end.", {
    x: 0.5, y: 5.05, w: 9.0, h: 0.25,
    fontSize: 10, italic: true, color: C.textMuted,
    fontFace: F.body, align: "center", valign: "middle", margin: 0
  });

  addFooter(s, 3, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 4 — PYTHON AGENT INTERNAL ARCHITECTURE
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "3.  Python Agent Internals", "server-python/  modules");

  // Central box: SmartHelpAgent
  box(s, {
    x: 3.5, y: 1.05, w: 3.0, h: 1.0, accentLeft: C.tertiary,
    title: "🎯 SmartHelpAgent",
    subtitle: "agent.py · entrypoint(ctx)\nRoom event handlers · STT · Vision call · TTS publish",
    titleSize: 13, fontSize: 9, bgColor: C.bgWhite
  });

  // Layer 2 — owns
  box(s, {
    x: 0.4, y: 2.3, w: 2.2, h: 1.0, accentLeft: C.primary,
    title: "🔁 TaskStateMachine",
    subtitle: "task_state_machine.py\n10 states · FSM transitions",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 2.8, y: 2.3, w: 2.4, h: 1.0, accentLeft: C.primary,
    title: "🎬 TaskExecutor",
    subtitle: "task_executor.py\nOrchestrator · per-state handlers",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 5.4, y: 2.3, w: 2.0, h: 1.0, accentLeft: C.primary,
    title: "🔊 VoiceSynth",
    subtitle: "voice_synthesizer.py\n24 kHz PCM stream",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 7.6, y: 2.3, w: 2.0, h: 1.0, accentLeft: C.primary,
    title: "📡 LiveKit Room",
    subtitle: "rtc.Room · audio + data\ntrack publish/subscribe",
    titleSize: 11, fontSize: 9
  });

  // Layer 3 — Executor uses
  box(s, {
    x: 0.4, y: 3.55, w: 2.0, h: 0.95, accentLeft: C.accent,
    title: "🧭 IntentAgent",
    subtitle: "intent_agent.py\nflash-lite · classify",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 2.6, y: 3.55, w: 2.0, h: 0.95, accentLeft: C.accent,
    title: "📋 NavigationReactAgent",
    subtitle: "navigation_react_agent.py\none next action",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 4.8, y: 3.55, w: 2.0, h: 0.95, accentLeft: C.danger,
    title: "🛡 SafetyGate",
    subtitle: "safety_gate.py\nregex pre-LLM filter",
    titleSize: 11, fontSize: 9
  });
  box(s, {
    x: 7.0, y: 3.55, w: 2.6, h: 0.95, accentLeft: C.success,
    title: "📚 PromptRegistry",
    subtitle: "prompt_registry.py\nLRU cache · 10 templates · context interpolation",
    titleSize: 11, fontSize: 9
  });

  // Bottom layer — Prompt files
  box(s, {
    x: 0.4, y: 4.7, w: 9.2, h: 0.55, accentLeft: C.success, bgColor: "ECFDF5",
    title: "prompts/",
    subtitle: "intent.system.txt   ·   navigation_react.{system, examples}.txt   ·   guide.{shared, navigate, verify, retry, help}.txt   ·   chat.txt",
    titleSize: 10, fontSize: 9
  });

  // Connection lines from central to layer 2
  [1.5, 4.0, 6.4, 8.6].forEach(x => {
    s.addShape(pres.shapes.LINE, {
      x: 5.0, y: 2.05, w: x - 5.0, h: 0.25,
      line: { color: C.tertiary, width: 1 }
    });
  });
  // Lines from TaskExecutor to row 3
  [1.4, 3.6, 5.8, 8.3].forEach(x => {
    s.addShape(pres.shapes.LINE, {
      x: 4.0, y: 3.3, w: x - 4.0, h: 0.25,
      line: { color: C.accent, width: 1, dashType: "dash" }
    });
  });

  addFooter(s, 4, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 5 — TASK STATE MACHINE
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "4.  Task State Machine", "10 states · FSM in task_state_machine.py");

  function stateBox(s, x, y, label, kind) {
    const colors = {
      start: { bg: C.bgWhite, border: C.tertiary, text: C.tertiary },
      mid:   { bg: C.bgWhite, border: C.primary, text: C.primary },
      llm:   { bg: "FEF3C7", border: C.accent, text: C.textDark },
      end:   { bg: "DCFCE7", border: C.success, text: "065F46" },
      fail:  { bg: "FEE2E2", border: C.danger, text: "991B1B" },
    };
    const cfg = colors[kind] || colors.mid;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x: x, y: y, w: 1.55, h: 0.5,
      fill: { color: cfg.bg }, line: { color: cfg.border, width: 1.5 },
      rectRadius: 0.08, shadow: shadow()
    });
    s.addText(label, {
      x: x, y: y, w: 1.55, h: 0.5,
      fontSize: 10, bold: true, color: cfg.text,
      fontFace: F.header, align: "center", valign: "middle", margin: 0
    });
  }

  // Layout (3 rows)
  // Row 1: IDLE → INTAKE → SAFETY_CHECK → PLANNING → CLARIFYING
  stateBox(s, 0.4, 1.1, "IDLE", "start");
  stateBox(s, 2.2, 1.1, "INTAKE", "mid");
  stateBox(s, 4.0, 1.1, "SAFETY_CHECK", "mid");
  stateBox(s, 5.8, 1.1, "PLANNING", "llm");
  stateBox(s, 7.6, 1.1, "CLARIFYING", "llm");

  // Row 2: GUIDING → AWAITING_ACTION → VERIFYING → RETRYING → HELPING
  stateBox(s, 0.4, 2.5, "GUIDING", "llm");
  stateBox(s, 2.2, 2.5, "AWAITING\nACTION", "mid");
  stateBox(s, 4.0, 2.5, "VERIFYING", "llm");
  stateBox(s, 5.8, 2.5, "RETRYING", "llm");
  stateBox(s, 7.6, 2.5, "HELPING", "llm");

  // Row 3 (terminals): COMPLETED, STUCK, FAILED
  stateBox(s, 1.5, 3.9, "COMPLETED", "end");
  stateBox(s, 4.2, 3.9, "STUCK", "fail");
  stateBox(s, 6.9, 3.9, "FAILED", "fail");

  // Arrows row 1 (→)
  const a = (x1, y1, x2, y2, color, label) => arrow(s, x1, y1, x2, y2, color || C.secondary, 1.2, label, false);
  a(1.95, 1.35, 2.2, 1.35);
  a(3.75, 1.35, 4.0, 1.35);
  a(5.55, 1.35, 5.8, 1.35);
  a(7.35, 1.35, 7.6, 1.35, C.warn, "needs info");

  // PLANNING → GUIDING (drop down)
  a(6.5, 1.6, 1.2, 2.5, C.success, "plan ready");
  // CLARIFYING → PLANNING (return)
  arrow(s, 8.4, 1.6, 6.5, 1.6, C.warn, 1.2, "user info", false);

  // Row 2 arrows
  a(1.95, 2.75, 2.2, 2.75);
  a(3.75, 2.75, 4.0, 2.75);
  a(5.55, 2.75, 5.8, 2.75, C.warn, "fail");
  // VERIFYING → GUIDING (loop back next step)
  arrow(s, 4.0, 2.5, 1.2, 2.5, C.success, 1.2, "next step", true);

  // RETRYING → HELPING
  a(7.35, 2.75, 7.6, 2.75, C.warn, "retry max");

  // To terminals
  a(4.5, 3.0, 2.2, 3.9, C.success, "done");
  a(7.0, 3.0, 5.0, 3.9, C.danger);
  a(4.6, 3.0, 7.5, 3.9, C.danger, "blocked");

  // Legend
  const lx = 9.0, ly = 1.1;
  s.addShape(pres.shapes.RECTANGLE, {
    x: lx, y: ly, w: 0.95, h: 1.6, fill: { color: C.bgWhite }, line: { color: C.borderLight }
  });
  s.addText("Legend", { x: lx, y: ly + 0.05, w: 0.95, h: 0.25, fontSize: 9, bold: true, color: C.textDark, align: "center", margin: 0, fontFace: F.header });
  const items = [
    { color: C.tertiary, label: "Start" },
    { color: C.primary, label: "Logic" },
    { color: C.accent, label: "LLM" },
    { color: C.success, label: "Success" },
    { color: C.danger, label: "Failure" },
  ];
  items.forEach((it, i) => {
    s.addShape(pres.shapes.RECTANGLE, {
      x: lx + 0.08, y: ly + 0.32 + i * 0.24, w: 0.18, h: 0.16,
      fill: { color: it.color }, line: { color: it.color }
    });
    s.addText(it.label, {
      x: lx + 0.3, y: ly + 0.3 + i * 0.24, w: 0.65, h: 0.2,
      fontSize: 8, color: C.textBody, fontFace: F.body, valign: "middle", margin: 0
    });
  });

  addFooter(s, 5, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 6 — PROMPT COMPOSITION
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "5.  Prompt Composition", "10 templates → 4 runtime calls");

  // 4 columns
  const cols = [
    { x: 0.4,  title: "INTENT", color: C.tertiary, model: "flash-lite", files: ["intent.system.txt"], size: "~3.5K" },
    { x: 2.85, title: "REACT", color: C.primary, model: "flash-lite", files: ["navigation_react.system.txt", "navigation_react.examples.txt"], size: "~9K" },
    { x: 5.3,  title: "GUIDE",  color: C.accent,   model: "flash + vision", files: ["guide.shared.txt", "+ guide.{MODE}.txt", "  · navigate.txt", "  · verify.txt", "  · retry.txt", "  · help.txt"], size: "~7K" },
    { x: 7.75, title: "CHAT",   color: C.secondary, model: "flash-lite", files: ["chat.txt"], size: "~1.5K" },
  ];

  cols.forEach(col => {
    // Header
    s.addShape(pres.shapes.RECTANGLE, {
      x: col.x, y: 1.0, w: 2.1, h: 0.5,
      fill: { color: col.color }, line: { color: col.color }
    });
    s.addText(col.title, {
      x: col.x, y: 1.0, w: 2.1, h: 0.5,
      fontSize: 14, bold: true, color: C.bgWhite, fontFace: F.header,
      align: "center", valign: "middle", margin: 0, charSpacing: 2
    });
    s.addText(col.model, {
      x: col.x, y: 1.5, w: 2.1, h: 0.3,
      fontSize: 9, italic: true, color: C.textMuted, fontFace: F.body,
      align: "center", valign: "middle", margin: 0
    });

    // File stack
    const fileBoxY = 1.85;
    const fileH = 0.32;
    col.files.forEach((file, i) => {
      const isSubItem = file.startsWith("  ·");
      const isPlus = file.startsWith("+");
      s.addShape(pres.shapes.RECTANGLE, {
        x: col.x + 0.05, y: fileBoxY + i * fileH, w: 2.0, h: fileH - 0.04,
        fill: { color: isSubItem ? "F1F5F9" : (isPlus ? "FEF3C7" : C.bgWhite) },
        line: { color: C.borderLight, width: 0.5 }
      });
      s.addText(file.trim().replace(/^[+·]\s*/, ""), {
        x: col.x + 0.1 + (isSubItem ? 0.2 : 0), y: fileBoxY + i * fileH,
        w: 2.0 - (isSubItem ? 0.25 : 0.05), h: fileH - 0.04,
        fontSize: 9, color: isSubItem ? C.textMuted : C.textDark,
        fontFace: F.body, valign: "middle", margin: 0
      });
    });

    // Size pill
    const pillY = fileBoxY + col.files.length * fileH + 0.15;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x: col.x + 0.5, y: pillY, w: 1.2, h: 0.35,
      fill: { color: col.color }, line: { color: col.color }, rectRadius: 0.05
    });
    s.addText(`${col.size} chars`, {
      x: col.x + 0.5, y: pillY, w: 1.2, h: 0.35,
      fontSize: 9, bold: true, color: C.bgWhite, fontFace: F.body,
      align: "center", valign: "middle", margin: 0
    });
  });

  // Bottom note
  s.addText("Each runtime call uses ONE composed prompt. Mode-specific guide files share guide.shared.txt → 60-70% token reuse via prompt cache.", {
    x: 0.4, y: 4.95, w: 9.2, h: 0.3,
    fontSize: 10, italic: true, color: C.textMuted, fontFace: F.body,
    align: "center", valign: "middle", margin: 0
  });

  addFooter(s, 6, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 7 — MESSAGE PROTOCOL
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "6.  Message Protocol", "LiveKit DataChannel · JSON envelope");

  // Two side-by-side tables
  const headerOpts = (color) => ({ fill: { color }, color: "FFFFFF", bold: true, align: "center", fontFace: F.header, fontSize: 11 });

  // Table 1: Android → Python
  s.addText("Android → Python", {
    x: 0.4, y: 1.0, w: 4.5, h: 0.3,
    fontSize: 13, bold: true, color: C.primary, fontFace: F.header, margin: 0
  });
  const t1 = [
    [{ text: "type", options: headerOpts(C.primary) }, { text: "Purpose", options: headerOpts(C.primary) }],
    ["text / user_input", "Text input or STT result"],
    ["audio / audio_chunk", "Mic PCM frames (b64)"],
    ["audio_end", "Audio stream end signal"],
    ["image / screenshot", "Screenshot (small, b64 jpg)"],
    ["image_chunk + image_end", "Large screenshot (chunked >14KB)"],
    ["listening_start", "User started speaking"],
    ["help_request", "User taps for help"],
    ["cancel", "Cancel current task"],
    ["user_action", "User performed UI action"],
    ["sensitive_screen", "Bank PIN / OTP screen detected"],
    ["ping", "Heartbeat"],
  ];
  s.addTable(t1, {
    x: 0.4, y: 1.35, w: 4.5, colW: [2.0, 2.5],
    fontSize: 9, fontFace: F.body, color: C.textBody,
    border: { type: "solid", pt: 0.5, color: C.borderLight },
    rowH: 0.27
  });

  // Table 2: Python → Android
  s.addText("Python → Android", {
    x: 5.1, y: 1.0, w: 4.5, h: 0.3,
    fontSize: 13, bold: true, color: C.accent, fontFace: F.header, margin: 0
  });
  const t2 = [
    [{ text: "type", options: headerOpts(C.accent) }, { text: "Purpose", options: headerOpts(C.accent) }],
    ["ready", "Agent ready for input"],
    ["text", "Subtitle + speak/display flags"],
    ["highlight", "x, y, bbox, completed, direction"],
    ["transcription", "Live STT result (partial?)"],
    ["taskComplete", "Task finished; goal echoed"],
    ["requestScreenshot", "Need fresh screenshot"],
    ["audio_chunk + audio_end", "Large TTS audio (chunked)"],
    ["error", "Server-side error notice"],
    ["pong", "Heartbeat reply"],
    ["—", "—"],
    ["—", "—"],
  ];
  s.addTable(t2, {
    x: 5.1, y: 1.35, w: 4.5, colW: [2.0, 2.5],
    fontSize: 9, fontFace: F.body, color: C.textBody,
    border: { type: "solid", pt: 0.5, color: C.borderLight },
    rowH: 0.27
  });

  // Note at bottom
  s.addText("All messages are JSON. Audio/video tracks travel separately on LiveKit native tracks (not over data channel) for low-latency streaming.", {
    x: 0.4, y: 5.0, w: 9.2, h: 0.3,
    fontSize: 10, italic: true, color: C.textMuted, fontFace: F.body,
    align: "center", valign: "middle", margin: 0
  });

  addFooter(s, 7, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 8 — TECH STACK
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "7.  Technology Stack", "End-to-end components");

  const cats = [
    { color: C.primary, title: "📱 Mobile", items: [
      "Java · Android SDK 26-34",
      "OkHttp3 · Material Design",
      "Gson (JSON encoding)",
      "LiveKit Android SDK (Kotlin)",
      "MediaProjection (screenshot)",
      "Foreground service (mic)",
    ]},
    { color: C.secondary, title: "🌐 Networking & Audio", items: [
      "LiveKit Cloud (WebRTC SFU)",
      "DataChannel (JSON, reliable)",
      "Mic: 16 kHz PCM mono",
      "TTS: 24 kHz PCM mono",
      "Local /token server (port 8765)",
      "Chunked transfer >14 KB",
    ]},
    { color: C.accent, title: "🤖 AI Models", items: [
      "Intent: gemini-2.5-flash-lite",
      "ReAct: gemini-2.5-flash-lite",
      "Vision: gemini-2.5-flash + tools",
      "TTS: gemini-3.1-flash-tts-preview",
      "STT: Google Cloud STT (primary)",
      "STT fallback: Gemini transcription",
    ]},
    { color: C.success, title: "🐍 Python Server", items: [
      "Python 3.11+ · LiveKit Agents",
      "Custom 10-state FSM",
      "Pre-LLM regex SafetyGate",
      "Schema-forced grounding",
      "10 prompt templates · LRU cache",
      "Bilingual (en / zh / ms)",
    ]},
  ];

  cats.forEach((cat, i) => {
    const x = 0.4 + (i % 2) * 4.7;
    const y = 1.05 + Math.floor(i / 2) * 2.15;
    s.addShape(pres.shapes.RECTANGLE, {
      x: x, y: y, w: 4.5, h: 1.95,
      fill: { color: C.bgWhite }, line: { color: C.borderLight, width: 1 },
      shadow: shadow()
    });
    s.addShape(pres.shapes.RECTANGLE, {
      x: x, y: y, w: 0.1, h: 1.95,
      fill: { color: cat.color }, line: { color: cat.color }
    });
    s.addText(cat.title, {
      x: x + 0.2, y: y + 0.05, w: 4.2, h: 0.4,
      fontSize: 14, bold: true, color: cat.color, fontFace: F.header,
      valign: "middle", margin: 0
    });
    const bulletItems = cat.items.map((t, j) => ({
      text: t,
      options: { bullet: { code: "25CF" }, breakLine: j < cat.items.length - 1, color: C.textBody, fontSize: 10 }
    }));
    s.addText(bulletItems, {
      x: x + 0.25, y: y + 0.5, w: 4.2, h: 1.4,
      fontFace: F.body, paraSpaceAfter: 2, margin: 0
    });
  });

  addFooter(s, 8, 9);
}

// ═══════════════════════════════════════════════════════════════════════
// SLIDE 9 — DESIGN HIGHLIGHTS
// ═══════════════════════════════════════════════════════════════════════
{
  let s = pres.addSlide();
  s.background = { color: C.bgLight };
  addTitle(s, "8.  Key Design Decisions", "What makes SmartHelp+ distinctive");

  const highlights = [
    { num: "1", color: C.primary, title: "3-tier model cascade", desc: "lite → flash → tts. Intent and ReAct navigation stay lightweight; vision uses full flash." },
    { num: "2", color: C.danger, title: "Dual-layer safety", desc: "Regex SafetyGate (deterministic, zero latency) + LLM safety prompt (semantic scam detection)." },
    { num: "3", color: C.accent, title: "Schema-forced grounding", desc: "highlightElement tool requires screen_summary + target_found + target_location_text — vision must justify coords." },
    { num: "4", color: C.success, title: "Mode-shared prompt", desc: "4 guide modes share guide.shared.txt → 60-70% token reuse via Gemini prompt cache." },
    { num: "5", color: C.secondary, title: "STT failover", desc: "Google Cloud STT primary, Gemini transcription fallback. Single-point-of-failure eliminated." },
    { num: "6", color: C.tertiary, title: "Streaming TTS over track", desc: "PCM audio published directly to LiveKit AudioSource. No base64-over-data-channel overhead." },
    { num: "7", color: C.primary, title: "Chunked image transfer", desc: ">14KB screenshots split into image_chunk + image_end frames. Supports any resolution." },
    { num: "8", color: C.accent, title: "Explicit 10-state FSM", desc: "Each state maps to one prompt mode. Recovery from STUCK/FAILED is observable & testable." },
    { num: "9", color: C.success, title: "End-to-end bilingual", desc: "STT → intent → ReAct → guide → TTS all carry detected_language consistently." },
    { num: "10", color: C.warn, title: "Verbosity directive (LOW)", desc: "Gemini 3 best-practice. Explicit response-length contract prevents long-form drift." },
  ];

  highlights.forEach((h, i) => {
    const col = i % 2;
    const row = Math.floor(i / 2);
    const x = 0.4 + col * 4.7;
    const y = 1.0 + row * 0.82;

    // Number badge
    s.addShape(pres.shapes.OVAL, {
      x: x, y: y + 0.12, w: 0.5, h: 0.5,
      fill: { color: h.color }, line: { color: h.color }
    });
    s.addText(h.num, {
      x: x, y: y + 0.12, w: 0.5, h: 0.5,
      fontSize: 16, bold: true, color: C.bgWhite, fontFace: F.header,
      align: "center", valign: "middle", margin: 0
    });
    // Title
    s.addText(h.title, {
      x: x + 0.6, y: y + 0.05, w: 4.0, h: 0.32,
      fontSize: 12, bold: true, color: C.textDark, fontFace: F.header,
      valign: "middle", margin: 0
    });
    // Description
    s.addText(h.desc, {
      x: x + 0.6, y: y + 0.36, w: 4.0, h: 0.42,
      fontSize: 9, color: C.textBody, fontFace: F.body,
      valign: "top", margin: 0
    });
  });

  addFooter(s, 9, 9);
}

// ═══════════════════════════════════════════════════════════════════════
pres.writeFile({ fileName: "SmartHelp_Architecture.pptx" })
  .then(name => console.log(`✓ Generated: ${name}`));
