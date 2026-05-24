# SmartHelp+ 项目总览

> 核对日期：2026-05-25
> 范围：当前 `SmartHelp` 代码仓库
> 状态：已切换到 Android 原生客户端 + Python LiveKit Agent + Gemini 多阶段 AI 管线
> 说明：旧版 Node.js / WebSocket server 已被 `server-python/` 替代，旧资料只保留在 `docs/archive/` 作历史参考

---

## 1. 项目定位

SmartHelp+ 是一个面向长者的 Android 智能手机操作引导助手。

它不是聊天机器人，也不会替用户自动点击。它的核心目标是让用户每次只处理一个简单步骤：

1. 用户用语音或文字说出想完成的任务。
2. App 捕获当前手机画面和可访问性控件信息。
3. Python AI Agent 判断下一步应该做什么。
4. App 用短语音提示用户，并在屏幕上高亮正确按钮或区域。
5. 用户自己点击或输入。
6. 系统再次截图和验证，继续下一步，直到任务完成或被安全规则拦截。

一句话定义：

**SmartHelp+ = 面向长者、语音优先、带屏幕高亮的一步一步手机操作引导助手。**

---

## 2. 当前系统架构

当前架构由三层组成：

```text
Android App
  - OverlayService
  - ScreenCaptureService
  - SmartHelpAccessibilityService
  - LiveKit client
        |
        | LiveKit WebRTC
        | audio track + video/screenshot + data channel JSON
        v
LiveKit Cloud
        |
        v
Python LiveKit Agent
  - STT
  - Intent Agent
  - Safety Gate
  - ReAct Navigation Agent
  - Task Executor
  - Vision Grounding
  - Accessibility Fast Path
  - Gemini TTS
```

旧架构中的 `server/` Node.js backend、Express、`ws` WebSocket route 和 Node Gemini service 已经删除。现在的后端运行时在 `server-python/`。

---

## 3. 技术栈

| 层级 | 当前技术 |
|---|---|
| Android 客户端 | Java, Android SDK, Material Components |
| Android 能力 | MediaProjection, AccessibilityService, Overlay Window, SpeechRecognizer fallback, TextToSpeech fallback |
| 传输 | LiveKit WebRTC audio track + data channel JSON |
| Token 服务 | Python HTTP server, 默认端口 `8765` |
| AI Runtime | Python LiveKit Agent |
| STT | Google Cloud Speech-to-Text 为主，Gemini STT 作为 fallback |
| 意图识别 | Gemini `gemini-2.5-flash-lite`，可通过环境变量覆盖 |
| ReAct 导航 | Gemini `gemini-2.5-flash-lite`，逐步决策 |
| 视觉定位 | Gemini Vision，默认 `gemini-3-flash-preview` |
| 语音输出 | Gemini TTS 通过 LiveKit audio track，Android local TTS 作为 fallback |
| 本地状态 | Android SharedPreferences + 内存 session state |

常用模型环境变量：

- `GEMINI_INTENT_MODEL`
- `GEMINI_REACT_MODEL`
- `GEMINI_VISION_MODEL`
- `GEMINI_VISION_VERIFY_MODEL`
- `GEMINI_TTS_MODEL`
- `GEMINI_TTS_FALLBACK_MODEL`

---

## 4. 代码目录

```text
SmartHelp/
├── android/                         Android 原生 App
│   └── app/src/main/java/com/smarthelp/app/
│       ├── MainActivity.java
│       ├── SplashActivity.java
│       ├── SettingsActivity.java
│       ├── OverlayService.java
│       ├── ScreenCaptureService.java
│       ├── SmartHelpAccessibilityService.java
│       ├── HighlightOverlayView.java
│       ├── AppPrefs.java
│       ├── PrivacySafety.java
│       ├── network/
│       │   ├── ServerConnection.java
│       │   └── MessageProtocol.java
│       ├── overlay/
│       │   └── HighlightRenderer.java
│       └── session/
│           └── TaskSessionLocal.java
│
├── server-python/                   Python LiveKit Agent
│   ├── agent.py
│   ├── token_server.py
│   ├── generate_token.py
│   ├── intent_agent.py
│   ├── intent_safety_agent.py
│   ├── navigation_react_agent.py
│   ├── task_executor.py
│   ├── task_state_machine.py
│   ├── vision_grounding_tool.py
│   ├── accessibility_fast_path.py
│   ├── safety_gate.py
│   ├── voice_synthesizer.py
│   ├── prompt_registry.py
│   ├── prompts/
│   └── tests/
│
├── docs/
│   ├── architecture/
│   ├── testing/
│   ├── design.md
│   ├── UI_DESIGN_SPEC.md
│   └── archive/
│
├── branding/
├── README.md
├── overview.md
├── start-all.bat
├── start-all.ps1
├── stop-all.bat
└── _run-agent.bat
```

---

## 5. Android 端职责

Android 端主要负责用户体验、权限、屏幕捕获和显示引导结果。

### 5.1 `MainActivity.java`

主界面负责：

- 展示 SmartHelp+ 入口和当前状态。
- 引导用户完成麦克风、悬浮窗、无障碍服务、屏幕捕获权限。
- 启动 `OverlayService` 和 `ScreenCaptureService`。
- 提供设置入口和基本状态显示。

### 5.2 `OverlayService.java`

这是 Android 端最核心的运行时控制器。

它负责：

- 创建悬浮球、侧边栏、聊天面板、任务完成面板。
- 与 `ServerConnection` 建立 LiveKit session。
- 控制麦克风开始和停止。
- 发送用户文本、语音状态、截图请求和 action event。
- 接收 Python Agent 的 guidance / highlight / completion / safety message。
- 调用 `HighlightOverlayView` 在屏幕上画高亮框、箭头和提示。
- 使用本地 TTS 作为快速提示或 Gemini TTS fallback。
- 在用户操作后安排下一次验证。

设计原则：

- App 不替用户点击。
- 每次只给一个短步骤。
- 高亮和语音必须同步表达同一个目标。
- 如果进入敏感场景，优先暂停并显示安全提醒。

### 5.3 `ScreenCaptureService.java`

负责 MediaProjection 屏幕捕获：

- 接收系统 screen capture permission。
- 抓取当前屏幕图像。
- 附带屏幕尺寸、方向、截图时间等上下文。
- 读取最新 Accessibility snapshot。
- 把截图和上下文交给 LiveKit data channel / agent pipeline。

### 5.4 `SmartHelpAccessibilityService.java`

负责收集可访问性树和用户动作事件：

- 提取当前窗口里的可见控件文本、bounds、clickable 状态。
- 根据目标文本快速寻找候选控件。
- 向 `OverlayService` 通知用户点击、输入、滚动、窗口切换等动作。
- 检测敏感 package 或敏感界面，配合 `PrivacySafety` 暂停风险流程。

Accessibility 不用于自动操作，只用于：

- 更准确地理解屏幕。
- 更快地定位按钮。
- 判断用户是否已经执行了动作。

### 5.5 `ServerConnection.java`

Android 端 LiveKit 连接层：

- 从 `token_server.py` 获取 LiveKit token。
- 连接 LiveKit Cloud。
- 发布麦克风音频和屏幕相关消息。
- 接收 Python Agent 通过 data channel 返回的结构化 JSON。
- 处理连接失败、重连、emulator token endpoint fallback。

### 5.6 `MessageProtocol.java`

定义 Android 与 Python Agent 之间的 JSON message 格式。

典型消息包括：

- 用户文本或语音结束事件。
- 截图请求和截图结果。
- guidance 文本。
- highlight 坐标。
- task completed。
- ask user / retry / help request。
- safety warning。

---

## 6. Python Agent 职责

Python 端是当前系统的 AI 大脑。

### 6.1 `agent.py`

LiveKit Agent 入口，负责：

- 连接 LiveKit Room。
- 接收 Android 的音频、截图和 data channel message。
- 管理 `TaskExecutor`。
- 调用 STT、vision、TTS 等模块。
- 将字幕、语音、highlight 和状态消息发回 Android。

它替代旧版 Node.js server。

### 6.2 `token_server.py` / `generate_token.py`

负责发放短期 LiveKit token：

- Android 默认请求 `http://127.0.0.1:8765/token`。
- Emulator 会 fallback 到 `http://10.0.2.2:8765/token`。
- token 使用 `LIVEKIT_API_KEY` 和 `LIVEKIT_API_SECRET` 生成。

### 6.3 `intent_agent.py`

负责理解用户想做什么：

- 判断任务目标。
- 识别目标 App 或操作类型。
- 判断语言。
- 输出给后续安全和导航阶段使用的结构化 intent。

### 6.4 `intent_safety_agent.py` 和 `safety_gate.py`

负责风险拦截：

- OTP / TAC / PIN。
- 银行转账或付款确认。
- 可疑链接、二维码、未知安装包。
- 远程控制或高风险设置。
- 用户明显在要求安全检查时，优先回答安全判断。

高风险任务不会进入导航高亮流程。

### 6.5 `navigation_react_agent.py`

负责逐步导航决策。

它不是一次性生成完整长计划，而是每次根据当前状态做一次 ReAct 式判断：

- 当前用户目标是什么。
- 当前屏幕显示什么。
- Accessibility snapshot 里有哪些候选控件。
- 上一步是否成功。
- 是否需要重试、改用视觉定位、或向用户询问。

输出是下一步动作，例如：

- 让用户点击某个按钮。
- 让用户输入文字。
- 告诉用户当前找不到目标。
- 判断任务已经完成。

### 6.6 `task_state_machine.py`

定义任务生命周期：

```text
IDLE
  -> INTAKE
  -> GUIDING
  -> AWAITING_ACTION
  -> VERIFYING
  -> RETRYING
  -> HELPING
  -> COMPLETED / BLOCKED / ERROR
```

状态机让系统不会变成随意聊天，而是保持在明确的任务闭环里。

### 6.7 `task_executor.py`

任务执行核心：

- 调用 intent safety。
- 调用 ReAct navigation。
- 选择 Accessibility fast path 或 vision grounding。
- 决定发送给 Android 的文本、highlight、retry、completion。
- 控制去重，避免重复 TTS 和重复高亮。
- 在用户动作后要求下一次截图验证。

### 6.8 `vision_grounding_tool.py`

负责视觉定位和验证：

- 输入当前 screenshot、目标描述、上下文。
- 调用 Gemini Vision。
- 输出目标是否存在、目标区域、坐标、置信度、屏幕摘要。
- 当 Accessibility 无法可靠定位时提供 pixel-level grounding。

### 6.9 `accessibility_fast_path.py`

负责纯本地匹配：

- 根据控件文本、content description、bounds、clickable 信息寻找目标。
- 如果已经能确定目标按钮，就跳过 Gemini Vision。
- 降低延迟和 API 调用成本。

### 6.10 `voice_synthesizer.py`

负责 Gemini TTS：

- 将短提示合成为 PCM。
- 通过 LiveKit audio track 播放给 Android。
- 支持中文、英文、马来文 voice 配置。
- 失败时让 Android 使用 local TTS fallback。

---

## 7. 一次完整任务流程

以“帮我打开相机”为例：

1. 用户点击悬浮球并讲话。
2. Android 打开 LiveKit 麦克风 audio track。
3. Python Agent 接收音频并转文字。
4. `IntentAgent` 判断目标是打开 Camera。
5. `IntentSafetyAgent` 确认不是高风险任务。
6. Android 截取当前屏幕并发送截图 + Accessibility snapshot。
7. `NavigationReactAgent` 判断下一步。
8. `AccessibilityFastPath` 先尝试从控件树找 Camera。
9. 如果找不到，再调用 `VisionGroundingTool` 看图定位。
10. Python Agent 发送 guidance：例如“Tap the Camera icon.”
11. Android 显示高亮，并用语音说出同一句短提示。
12. 用户自己点击。
13. Accessibility event 触发，Android 延迟请求验证截图。
14. Agent 判断是否进入 Camera。
15. 如果成功，发送 completed；如果失败，发送 retry 或新的下一步。

---

## 8. 安全边界

SmartHelp+ 的安全原则：

- 不自动点击。
- 不替用户输入密码、OTP、TAC、PIN。
- 不引导银行转账、付款确认、未知安装包安装。
- 不鼓励点击可疑链接或扫描未知二维码。
- 进入敏感 App 或敏感界面时，优先暂停并显示安全提醒。

Android 端有 `PrivacySafety.java`，Python 端有 `SafetyGate` 和 `IntentSafetyAgent`。两边都做防护，不只依赖一个模型判断。

---

## 9. 运行方式

### 9.1 环境变量

`server-python/.env` 需要包含：

```env
LIVEKIT_URL=...
LIVEKIT_API_KEY=...
LIVEKIT_API_SECRET=...
GOOGLE_API_KEY=...
GOOGLE_APPLICATION_CREDENTIALS=...
```

`.env` 已被 `.gitignore` 忽略，不应该提交到 GitHub。

### 9.2 安装 Python 依赖

```powershell
cd server-python
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

### 9.3 启动

推荐使用：

```powershell
.\start-all.bat
```

它会启动：

- token server。
- Python LiveKit Agent。
- agent auto-restart wrapper。
- ADB reverse for token endpoint。

停止：

```powershell
.\stop-all.bat
```

---

## 10. 测试

Python 测试：

```powershell
cd server-python
.\venv\Scripts\python.exe -m pytest
```

Android 测试位于：

- `android/app/src/test/`
- `android/app/src/androidTest/`

项目测试计划和模板位于：

- `docs/testing/TESTING_PLAN.md`
- `docs/testing/TECHNICAL_EDGE_CASE_TEST_TEMPLATE.md`
- `docs/testing/ELDERLY_USER_TEST_CASES_TEMPLATE.md`
- `docs/testing/BETA_TESTING_SUMMARY_TEMPLATE.md`

运行时调试输出、截图证据和临时测试产物不应提交：

- `server-python/debug/`
- `android/test-artifacts/`
- `docs/testing/evidence/`

---

## 11. 当前重点文件

如果要继续开发，优先看这些文件：

| 文件 | 作用 |
|---|---|
| `android/app/src/main/java/com/smarthelp/app/OverlayService.java` | Android 引导体验主控制器 |
| `android/app/src/main/java/com/smarthelp/app/ScreenCaptureService.java` | 屏幕捕获 |
| `android/app/src/main/java/com/smarthelp/app/SmartHelpAccessibilityService.java` | Accessibility snapshot 和用户动作事件 |
| `android/app/src/main/java/com/smarthelp/app/network/ServerConnection.java` | LiveKit 连接 |
| `server-python/agent.py` | Python Agent 入口 |
| `server-python/task_executor.py` | 任务执行和消息编排 |
| `server-python/navigation_react_agent.py` | 下一步导航判断 |
| `server-python/vision_grounding_tool.py` | 截图视觉定位 |
| `server-python/accessibility_fast_path.py` | Accessibility 快速定位 |
| `server-python/safety_gate.py` | 安全规则 |
| `server-python/voice_synthesizer.py` | Gemini TTS |

---

## 12. 与旧版本的主要区别

旧版本：

- Android 通过 WebSocket 连接 Node.js server。
- Node.js server 调 Gemini Live / Gemini service。
- 任务规划更多依赖 prompt 和一次性计划。

当前版本：

- Android 通过 LiveKit 与 Python Agent 通讯。
- 音频、截图和 data channel 都走 LiveKit session。
- Python 端拆成 intent、safety、ReAct、executor、vision、TTS 多阶段模块。
- Accessibility fast path 能减少视觉模型调用。
- 任务状态机明确管理任务生命周期。
- TTS 可以由 server 端 Gemini TTS 通过 LiveKit audio track 播放，失败时 Android local TTS fallback。

---

## 13. 产品原则

SmartHelp+ 后续开发应保持这些原则：

1. 每次只给一个下一步。
2. 语音和视觉高亮必须一致。
3. 不替用户操作，只指导用户自己操作。
4. 遇到安全风险时宁可中断，也不要继续引导。
5. 优先使用 Accessibility 数据；不够可靠时才用 Vision。
6. 用户完成动作后必须验证，不要假设成功。
7. 面向长者的文案要短、直接、低压力。

---

## 14. 当前状态总结

当前 SmartHelp+ 已经从旧的 Node.js backend 重构为 Python LiveKit Agent 架构。

Android 端负责捕获、展示、语音交互和用户动作检测；Python 端负责 AI 决策、安全判断、视觉定位和 TTS。整个系统围绕“用户自己操作，系统一步一步指导”的闭环设计。

这份 `overview.md` 以当前代码为准；如果与 `docs/archive/` 或旧文档冲突，应以当前源码、`README.md` 和 `server-python/` 实现为准。
