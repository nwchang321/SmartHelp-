# SmartHelp+ Multi-Agent Runtime Architecture Diagrams

This file contains split architecture/runtime diagrams for the current SmartHelp+ implementation.
It reflects the Python + LiveKit + multi-agent ReAct version, not the older Node/WebSocket prototype.

## Figure 1. Overall System Deployment

```mermaid
flowchart LR
    User["Elderly user"]

    subgraph Android["Android device"]
        Main["MainActivity\nonboarding, consent, start/stop"]
        Overlay["OverlayService\nfloating assistant, mic, guidance UI"]
        Capture["ScreenCaptureService\nMediaProjection screenshot"]
        Access["SmartHelpAccessibilityService\nvisible controls + bounds"]
        Highlight["HighlightRenderer + HighlightOverlayView\norange ring, arrow, scroll cue"]
        Conn["ServerConnection\nLiveKit room + DataChannel"]

        Main --> Overlay
        Overlay --> Capture
        Capture --> Access
        Overlay --> Highlight
        Overlay --> Conn
    end

    subgraph LocalBackend["Backend runtime on developer machine"]
        Token["token_server.py\n/token, /health, /tts"]
        Agent["agent.py\nSmartHelpAgent per LiveKit room"]
        Executor["TaskExecutor\ncontrolled orchestrator"]
        FSM["TaskStateMachine\nexplicit task lifecycle"]
        Voice["GeminiVoiceSynthesizer\nserver-side TTS"]

        Agent --> Executor
        Executor --> FSM
        Agent --> Voice
    end

    subgraph LiveKit["LiveKit Cloud"]
        Room["LiveKit room\nWebRTC audio tracks + reliable data channel"]
    end

    subgraph AI["External AI services"]
        STT["Google STT / Gemini fallback\nspeech to text"]
        Models["Gemini models\nintent, ReAct navigation, vision grounding"]
        TTS["Gemini TTS\nnatural speech"]
    end

    User --> Main
    Conn -- "GET /token" --> Token
    Conn <-- "room join + data + mic track" --> Room
    Room <-- "data + audio tracks" --> Agent
    Agent --> STT
    Executor --> Models
    Voice --> TTS
    Executor -- "highlight / text / taskComplete" --> Agent
    Agent -- "DataChannel + TTS track" --> Room
    Room --> Conn
    Conn --> Overlay
```

## Figure 2. Android Runtime Architecture

```mermaid
flowchart TD
    User["User"]

    subgraph App["SmartHelp+ Android app"]
        Main["MainActivity\npermission entry, privacy notice, token pre-warm"]
        Settings["SettingsActivity + AppPrefs\nLiveKit URL, token endpoint, language, consent"]
        Overlay["OverlayService\nruntime controller"]
        Session["TaskSessionLocal\ncurrent goal, pending verify, last highlight"]
        Conn["ServerConnection\nLiveKit connect, data publish, mic track"]
        Protocol["MessageProtocol\nencode/decode JSON messages"]
        Capture["ScreenCaptureService\nhide overlay, capture JPEG, collect metadata"]
        Access["SmartHelpAccessibilityService\nvisible node labels and bounds"]
        Privacy["PrivacySafety\nsensitive app/query checks"]
        Renderer["HighlightRenderer"]
        View["HighlightOverlayView\norange target, bounding box, scroll direction"]
        LocalTTS["Android TextToSpeech fallback"]
    end

    User --> Main
    Main --> Settings
    Main --> Overlay
    Overlay --> Session
    Overlay --> Conn
    Conn --> Protocol
    Overlay --> Capture
    Capture --> Access
    Overlay --> Privacy
    Overlay --> Renderer
    Renderer --> View
    Overlay --> LocalTTS

    Conn -- "ready / text / highlight / requestScreenshot / taskComplete" --> Overlay
    Overlay -- "text, screenshot, user_action, help_request, cancel" --> Conn
```

## Figure 3. Python Multi-Agent Backend Architecture

```mermaid
flowchart TD
    LK["LiveKit room\nDataChannel + audio tracks"]
    Agent["SmartHelpAgent\nagent.py"]

    subgraph Runtime["Session runtime"]
        FSM["TaskStateMachine\nIDLE -> INTAKE -> SAFETY_CHECK -> PLANNING -> GUIDING -> VERIFYING"]
        Executor["TaskExecutor\norchestrates safety, ReAct, grounding, verify, retry"]
        Prompt["PromptRegistry\nloads prompt templates and builds context"]
        Voice["GeminiVoiceSynthesizer\nstreams PCM into LiveKit audio track"]
    end

    subgraph Agents["Model-facing agents"]
        IntentSafety["IntentSafetyAgent\nunified intent + safety result"]
        Intent["IntentAgent\nclassify goal, app, language, missing info"]
        Safety["SafetyGate\ndeterministic scam / OTP / payment rules"]
        React["NavigationReactAgent\nsingle next action, no full plan"]
    end

    subgraph Grounding["Grounding and verification"]
        FastPath["AccessibilityFastPath\nknown UI / bounds match before vision"]
        Vision["VisionGroundingTool\nGemini vision + highlightElement tool"]
    end

    LK <--> Agent
    Agent --> FSM
    Agent --> Executor
    Agent --> Voice
    Executor --> FSM
    Executor --> Prompt
    Executor --> IntentSafety
    IntentSafety --> Safety
    IntentSafety --> Intent
    Executor --> React
    Executor --> FastPath
    Executor --> Vision
    Vision --> Executor
    Executor --> Agent
```

## Figure 4. Startup And LiveKit Connection Runtime

```mermaid
sequenceDiagram
    actor User
    participant Main as MainActivity
    participant Overlay as OverlayService
    participant Token as token_server.py
    participant LK as LiveKit Cloud Room
    participant Agent as Python SmartHelpAgent

    User->>Main: Open SmartHelp+
    Main->>Main: Check consent and required permissions
    Main->>Token: Pre-warm GET /token
    Token-->>Main: LiveKit URL + short-lived token
    User->>Main: Start assistant
    Main->>Overlay: Start foreground overlay service
    Overlay->>Token: GET /token if cached token is missing/expired
    Token-->>Overlay: LiveKit URL + token
    Overlay->>LK: Join room through ServerConnection
    Agent->>LK: Join same room as backend participant
    LK-->>Overlay: Room connected
    Agent-->>Overlay: ready message over DataChannel
    Overlay-->>User: Show floating assistant and mic control
```

## Figure 5. Voice Or Text Goal Intake Runtime

```mermaid
flowchart TD
    Start["User speaks or types a goal"]

    subgraph Android["Android"]
        Mic["OverlayService starts LiveKit mic track"]
        Text["Text input / recognized local text"]
        Send["ServerConnection sends\nlistening_start, audio_end, text"]
    end

    subgraph Agent["Python SmartHelpAgent"]
        AudioBuffer["Buffer incoming audio frames"]
        Transcribe["Transcribe audio\nGoogle STT primary, Gemini fallback"]
        Dispatch["Dispatch user goal to FSM"]
    end

    subgraph Executor["TaskExecutor + FSM"]
        Intake["INTAKE\nnormalize goal/language"]
        SafetyState["SAFETY_CHECK"]
        IntentSafety["IntentSafetyAgent\nSafetyGate + IntentAgent"]
        Planning["PLANNING\nNavigationReactAgent decides one next action"]
        Ask["CLARIFYING\nask one missing-info question"]
        Guide["GUIDING\nrequest screenshot"]
        Done["COMPLETED\nonly when final outcome is visible"]
    end

    Start --> Mic
    Start --> Text
    Mic --> Send
    Text --> Send
    Send --> AudioBuffer
    AudioBuffer --> Transcribe
    Transcribe --> Dispatch
    Send --> Dispatch
    Dispatch --> Intake --> SafetyState --> IntentSafety --> Planning
    Planning -->|needs_info| Ask
    Planning -->|guide or recover| Guide
    Planning -->|complete| Done
```

## Figure 6. Screenshot Grounding And Guidance Runtime

```mermaid
flowchart TD
    Request["TaskExecutor emits requestScreenshot"]

    subgraph Android["Android screenshot side"]
        Hide["OverlayService hides highlight/overlay before capture"]
        Capture["ScreenCaptureService captures JPEG screenshot"]
        Bounds["SmartHelpAccessibilityService collects visible controls and bounds"]
        Chunk["ServerConnection sends screenshot\nimage or image_chunk + image_end"]
    end

    subgraph Backend["Python backend"]
        Reassemble["SmartHelpAgent reassembles screenshot chunks"]
        Prepare["TaskExecutor builds mode-specific guide prompt"]
        FastPath["AccessibilityFastPath checks known target and bounds"]
        Vision["VisionGroundingTool sends screenshot + prompt to Gemini Vision"]
        Tool["highlightElement tool result\nx/y, bbox, completed, blocker, direction"]
        Emit["Emit guidance/highlight to Android"]
    end

    subgraph AndroidRender["Android rendering"]
        MatchBounds["SmartHelpAccessibilityService.findTargetBounds\noptional final bounds refinement"]
        Highlight["HighlightOverlayView draws\norange ring, arrow, bbox, scroll direction"]
        TTS["Voice guidance\nserver TTS track or Android TTS fallback"]
    end

    Request --> Hide --> Capture --> Bounds --> Chunk
    Chunk --> Reassemble --> Prepare --> FastPath
    FastPath -->|confident hit| Emit
    FastPath -->|miss| Vision --> Tool --> Emit
    Emit --> MatchBounds --> Highlight
    Emit --> TTS
```

## Figure 7. Verification, Re-Planning And Completion Runtime

```mermaid
flowchart TD
    Highlight["Highlight shown to user"]
    UserAction["User performs the tap/type/scroll manually"]
    NewShot["Android sends new screenshot"]
    Verify["TaskExecutor enters VERIFYING"]
    VisionVerify["VisionGroundingTool checks EXPECTED_RESULT"]

    Completed{"Expected result visible?"}
    Blocker{"Blocker detected?"}
    Final{"Final task outcome?"}

    ReactHistory["Append reactHistory\nok / fail / blocker"]
    Replan["react_replan -> PLANNING"]
    React["NavigationReactAgent decides fresh next action"]
    Recover["recover action\nback, close popup, scroll, retry target"]
    NextGuide["guide next step"]
    TaskDone["taskComplete -> Android completion sheet"]
    Stuck["STUCK\niteration limit or repeated failure"]

    Highlight --> UserAction --> NewShot --> Verify --> VisionVerify --> Completed
    Completed -->|yes| ReactHistory --> Final
    Final -->|yes| TaskDone
    Final -->|no| Replan --> React --> NextGuide
    Completed -->|no| Blocker
    Blocker -->|yes| ReactHistory --> Replan --> React --> Recover
    Blocker -->|no| ReactHistory --> Replan --> React --> Recover
    React -->|max iterations exceeded| Stuck
```

## Figure 8. Safety And Privacy Runtime

```mermaid
flowchart TD
    Goal["User goal or current screen"]

    subgraph Android["Android-side privacy controls"]
        Consent["Privacy notice and user consent"]
        Permission["Android system permissions\nmicrophone, overlay, screen capture, notification, accessibility"]
        SensitivePackage["PrivacySafety detects sensitive app/package"]
        OnDemand["Screenshots captured only on request"]
        HideOverlay["Overlay hidden before screenshot"]
        NoAutoTap["No automatic tapping\nuser remains in control"]
    end

    subgraph Backend["Backend safety controls"]
        SafetyGate["SafetyGate\nOTP, TAC, PIN, banking, payment, scam, remote control, unknown QR/link"]
        IntentSafety["IntentSafetyAgent combines\nsafety + intent + missing info"]
        Block["Blocked warning\nstop unsafe flow"]
        Confirm["Confirmation required\nask user / family presence"]
        Allow["Allowed guidance flow"]
        Pause["Sensitive screen pause"]
    end

    Goal --> Consent --> Permission
    Goal --> SensitivePackage -->|sensitive| Pause
    Goal --> SafetyGate --> IntentSafety
    IntentSafety -->|blocked| Block
    IntentSafety -->|confirm_needed| Confirm
    IntentSafety -->|safe| Allow
    Allow --> OnDemand --> HideOverlay --> NoAutoTap
```

## Figure 9. Main System Flowchart

```mermaid
flowchart TD
    Start([User opens SmartHelp+])
    Consent{Consent and permissions ready?}
    Setup["Request / guide permissions\nOverlay, mic, screen capture, notification, accessibility"]
    Token["Fetch LiveKit token\nfrom token_server.py"]
    Join["Android joins LiveKit room"]
    AgentReady["Python SmartHelpAgent joins room\nand sends ready"]
    Input{User input type}
    Voice["Voice input\nLiveKit mic track"]
    Text["Typed text input"]
    STT["Speech-to-text\nGoogle STT or Gemini fallback"]
    Goal["User goal received"]
    Safety["SafetyGate + IntentSafetyAgent\ncheck scam, OTP, payment, missing info"]
    SafetyDecision{Safety result}
    Block["Block unsafe task\nshow warning"]
    Confirm["Ask confirmation or missing information"]
    React["NavigationReactAgent\nchoose one next action"]
    ActionDecision{Next action}
    CompleteByReact["Return completed\nif final outcome already visible"]
    RequestShot["Request screenshot"]
    Capture["Android hides overlay\ncaptures screenshot + accessibility metadata"]
    FastPath{Accessibility fast-path hit?}
    KnownHighlight["Use known UI bounds\nskip Gemini Vision"]
    Vision["VisionGroundingTool\nGemini screenshot grounding"]
    Highlight["Send highlight + short guidance\norange ring / arrow / scroll cue"]
    UserAction["User manually performs action"]
    VerifyShot["Android sends verification screenshot"]
    Verify["Vision verifies EXPECTED_RESULT"]
    VerifyDecision{Expected result visible?}
    TaskFinal{Final task step?}
    TaskComplete["Send taskComplete\nshow completion sheet + voice follow-up"]
    Replan["Append reactHistory\nreact_replan to PLANNING"]
    Recover["Recover or retry\nwrong page, popup, missing target"]
    Stuck{Iteration limit reached?}
    StuckEnd["STUCK\nask user to retry or stop"]
    End([Session ends or new goal starts])

    Start --> Consent
    Consent -->|No| Setup --> Consent
    Consent -->|Yes| Token --> Join --> AgentReady --> Input
    Input -->|Speak| Voice --> STT --> Goal
    Input -->|Type| Text --> Goal
    Goal --> Safety --> SafetyDecision
    SafetyDecision -->|blocked| Block --> End
    SafetyDecision -->|confirm / missing info| Confirm --> Goal
    SafetyDecision -->|allowed| React --> ActionDecision
    ActionDecision -->|ask_user| Confirm
    ActionDecision -->|complete| CompleteByReact --> TaskComplete --> End
    ActionDecision -->|guide / recover| RequestShot --> Capture --> FastPath
    FastPath -->|Yes| KnownHighlight --> Highlight
    FastPath -->|No| Vision --> Highlight
    Highlight --> UserAction --> VerifyShot --> Verify --> VerifyDecision
    VerifyDecision -->|Yes| TaskFinal
    TaskFinal -->|Yes| TaskComplete --> End
    TaskFinal -->|No| Replan --> React
    VerifyDecision -->|No / blocker| Replan --> Stuck
    Stuck -->|No| Recover --> React
    Stuck -->|Yes| StuckEnd --> End
```

## Figure Numbering Suggestion For The Report

| Report figure | Suggested title |
| --- | --- |
| Figure 3.1 | Overall System Deployment Architecture |
| Figure 3.2 | Android Runtime Architecture |
| Figure 3.3 | Python Multi-Agent Backend Architecture |
| Figure 3.4 | Startup and LiveKit Connection Runtime Flow |
| Figure 3.5 | Voice/Text Goal Intake Runtime Flow |
| Figure 3.6 | Screenshot Grounding and Guidance Runtime Flow |
| Figure 3.7 | Verification, Re-Planning and Completion Runtime Flow |
| Figure 3.8 | Safety and Privacy Runtime Flow |
| Figure 3.9 | Main System Flowchart |
