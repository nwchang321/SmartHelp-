# SmartHelp+ 重建总览

> 基于当前代码仓库整理  
> 核对日期：2026-04-23  
> 目的：给后续“重新做这个 APP”时作为统一参考文档使用  
> 原则：这份文档优先以当前代码为准，不完全沿用旧的 `README.md` / `SMARTHELP_OVERVIEW.md` / `UI_DESIGN_SPEC.md`

---

## 1. 这个项目是什么

`SmartHelp+` 是一个给长者使用的 Android 智能手机辅助应用。

它的核心能力不是“聊天”，而是：

- 用户说出想做的事
- APP 获取当前手机画面
- AI 理解当前界面
- 用很短的语音告诉用户下一步
- 同时在正确位置画出高亮/箭头
- 用户点一下后，再截图确认是否完成
- 一步一步循环，直到任务完成

一句话定义：

**SmartHelp+ = 一个面向长者、语音优先、带视觉高亮的一步一步手机操作引导助手。**

---

## 2. 产品目标

### 2.1 主要目标

- 降低长者在使用智能手机时的紧张感、迷失感和操作压力
- 让用户每次只关注“下一步”，而不是整个流程
- 用“语音 + 屏幕高亮”代替复杂说明文字
- 在用户做完动作后自动验证，避免误导

### 2.2 目标用户

- 主要用户：年长 Android 用户
- 次要场景：不熟悉某些 App 操作的新手用户

### 2.3 适合的任务

- 打开 App
- 找按钮
- 打电话
- 发 WhatsApp / 发信息
- 打开相机
- 找设置项
- 查看照片
- 一般性的手机导航任务
- 帮用户判断可疑讯息 / 链接 / QR code 是否像诈骗

### 2.4 明确不适合或高风险任务

- 银行转账
- 输入 OTP / TAC / PIN / 密码
- 付款确认
- 远程控制 App 安装
- 陌生链接 / 陌生 QR code 的直接操作
- 任何必须高度谨慎的金钱交易流程

---

## 3. 当前技术栈

| 层 | 当前实现 |
|---|---|
| Android App | Java, Android SDK 26-34 |
| Android UI | Material Components + XML Layout |
| Android 网络 | OkHttp WebSocket |
| Android 设备能力 | MediaProjection, AccessibilityService, SpeechRecognizer, Overlay Window |
| Backend | Node.js 18+, Express, `ws` |
| 实时语音/截图引导 | Gemini Live |
| 任务规划 | Gemini Planner |
| Prompt 测试 | Promptfoo |
| 单元测试 | `node:test` |
| 数据存储 | 无数据库，主要是 SharedPreferences + 内存状态 |

### 3.1 代码里实际使用的 AI 模型

- 实时引导模型默认值：
  - `models/gemini-2.5-flash-native-audio-preview-12-2025`
- 任务规划模型：
  - 首选 `gemini-3-flash-preview`
  - 回退 `gemini-2.5-flash-lite`（与 IntentAgent / NavigationReactAgent 同款）

注意：旧文档里还有 `gemini-2.5-flash` 的描述，但**以当前代码为准**。

---

## 4. 项目目录总览

```text
SmartHelp/
├─ android/                         # Android 原生 App
│  └─ app/src/main/
│     ├─ java/com/smarthelp/app/    # Activity / Service / UI 控制逻辑
│     ├─ res/layout/                # 页面与 overlay 布局
│     ├─ res/drawable/              # 背景、按钮、气泡、渐变、图标
│     ├─ res/values/                # colors / strings / themes
│     └─ AndroidManifest.xml
├─ server/                          # Node.js backend
│  ├─ src/
│  │  ├─ core/                      # 新架构核心：controller / state machine / executor / safety
│  │  ├─ agents/                    # Gemini Live、ReAct navigation、兼容层
│  │  └─ prompts/                   # 模块化 prompts
│  ├─ promptfoo/                    # Prompt 测试
│  ├─ test/                         # 单元测试 + integration replay
│  └─ docs/
├─ README.md
├─ design.md
├─ PROJECT_OVERVIEW.md
├─ SMARTHELP_OVERVIEW.md
└─ UI_DESIGN_SPEC.md
```

---

## 5. 当前真实架构

## 5.1 Android 端整体流程

```text
SplashActivity
  -> MainActivity
  -> Onboarding / Privacy Notice
  -> 权限申请
  -> 启动 OverlayService + ScreenCaptureService
  -> 用户切到别的 App 操作
  -> OverlayService 负责语音、截图请求、高亮、重试、完成弹层
  -> 与 server WebSocket 通讯
```

## 5.2 Server 端整体流程

```text
Android WebSocket
  -> server/src/index.js
  -> SessionController（默认主控制器）
  -> TaskStateMachine（状态机）
  -> TaskExecutor（执行器）
  -> SafetyGate（安全判断）
  -> NavigationReactAgent（决定下一步动作）
  -> GeminiLiveAgent（发送截图给 Gemini Live）
  -> 返回 audio / text / highlight / complete
```

## 5.3 非常重要的事实

- 当前默认主流程已经不是老式 `OrchestratorAgent`
- 当前默认运行的是：
  - `SessionController`
  - `TaskStateMachine`
  - `TaskExecutor`
- `server/src/agents/orchestratorAgent.js` 现在只剩兼容 shim 意义
- 如果环境变量 `USE_NEW_ARCH=false` 才会走兼容模式

这意味着你重做时，**要以新架构为主，不要再把旧的 orchestrator 当作核心设计。**

---

## 6. Android 端模块说明

## 6.1 启动与首页

### `SplashActivity.java`

作用：

- 启动页
- 播放 logo / 标题淡入动画
- 约 `900ms` 后跳到 `MainActivity`

对应布局：

- `android/app/src/main/res/layout/activity_splash.xml`

当前视觉方向：

- 浅蓝渐变背景
- 中央 logo
- 同心圆装饰
- 底部细 loading bar

### `MainActivity.java`

作用：

- 首页
- onboarding 入口
- server 连接状态检测
- 权限申请总入口
- 启动 / 停止服务
- 最近任务入口
- 设置页入口

首页核心元素：

- 顶部设置按钮
- 连接状态 pill
- 中央大圆形 mic orb
- 下方主 CTA 文案
- 最近任务列表

当前交互逻辑：

- 如果服务未运行：点击 orb -> 申请权限 -> 启动服务
- 如果服务已运行：点击 orb -> 停止服务
- 最近任务可以直接再次触发
- 如果 home 上先点了某个任务，query 会存到 `AppPrefs`，等 overlay ready 后自动发出

---

## 6.2 偏好与本地状态

### `AppPrefs.java`

负责存储：

- 语言
- server 地址
- text size
- voice speed
- privacy consent
- sensitive protection 开关
- pending query
- recent tasks
- recent server addresses
- onboarding shown
- quick actions
- guidance voice 开关
- action sounds 开关

当前默认值里最值得记住的内容：

- 默认 server：`192.168.0.154:3000`
- 最近任务最多保留：`3`
- 最近 server 地址最多保留：`4`

默认 quick actions：

| 显示文案 | 实际命令 |
|---|---|
| Make a call | `Use the phone dialer to call my son, not WhatsApp` |
| Send WhatsApp | `Send a WhatsApp message to my daughter` |
| Open Camera | `Open the camera` |
| Check scam | `Help me check whether this message, link, or QR code is a scam` |

---

## 6.3 设置页

### `SettingsActivity.java`

作用：

- 配置 server 地址
- 改语言
- 调整文字大小
- 控制语音与提示音
- 查看 privacy & safety
- 开关 sensitive protection
- 编辑 quick actions
- 查看帮助
- 清除最近任务
- 重置 quick actions

页面结构上分成以下 section：

1. Language
2. Notifications & Sound
3. Text Size
4. Quick Actions
5. Permissions
6. Privacy & Safety
7. Advanced / Help & Tutorial
8. Maintenance
9. Server Connection
10. About

注意：

- 设置页视觉上还是沿用很多 `bg_info_card_purple` 命名
- 但这些资源实际已经是蓝色系，不是真的紫色
- 设置页功能很多，是当前功能 inventory 最完整的地方
- 但视觉上还有旧设计残留，后续重做时建议简化

---

## 6.4 Overlay 运行核心

### `OverlayService.java`

这是 Android 端最重要、也最重的类。

它当前负责：

- 创建底部 guidance overlay
- 创建 highlight overlay
- 创建 floating ball overlay
- 创建 close target overlay
- 创建 chat overlay
- 连接 server
- 语音输入
- 处理 server 返回的 text / audio / highlight / taskComplete / requestScreenshot
- 任务中的 step progress
- 重播语音
- ball mode 与 input mode 切换
- 错误状态
- reconnect 状态
- 敏感页面暂停
- task complete 弹层
- chat history
- quick actions
- 点击检测后的 verify 逻辑

### 当前 UI 状态枚举

`OverlayUiState` 大致包括：

- `IDLE`
- `LISTENING`
- `ANALYZING`
- `COMPLETED`
- `ERROR`

### Overlay 目前包含的几个子界面

1. `overlay_guidance.xml`
   - 主 bottom panel
   - guidance 卡片
   - error 卡片
   - quick actions
   - mic button
   - listening / thinking 状态

2. `overlay_ball.xml`
   - 悬浮球模式
   - 语音气泡
   - 详情卡片
   - Done / Show Again / Stop / Expand 按钮

3. `overlay_chat.xml`
   - 聊天记录面板
   - user bubble / AI bubble

4. `dialog_task_complete.xml`
   - 完成后底部弹层
   - Ask Again / Go Home

### 设计层面最重要的结论

这个项目真正的“产品体验核心”其实就是 `OverlayService`。

后面你重做 APP 时，最需要保留的不是某个旧布局文件，而是下面这些能力：

- bottom guidance panel
- 一步一步的高亮引导
- 必要时切换成 ball mode
- step 完成后自动 verify
- 失败后 retry / help
- 敏感页面自动暂停
- 任务完成后给用户明确结束反馈

---

## 6.5 截图与验证

### `ScreenCaptureService.java`

作用：

- 使用 `MediaProjection` 截图
- 只在需要时 capture
- 截图前广播隐藏 overlay
- 截图后恢复 overlay
- 把截图编码成 base64 JPEG
- 计算轻量 hash 用于页面是否变化判断

关键信息：

- 通过广播 `com.smarthelp.ANALYZE_REQUEST` 触发截图
- 截图结果存在静态共享变量：
  - `latestScreenshotBase64`
  - `latestScreenshotHash`

这是一种实用但不算优雅的实现，优点是快，缺点是服务间耦合较强。

---

## 6.6 点击检测与敏感页面检测

### `SmartHelpAccessibilityService.java`

负责监听：

- `TYPE_VIEW_CLICKED`
- `TYPE_WINDOW_STATE_CHANGED`

然后广播给 APP 内部其他模块。

### `InputDetector.java`

负责把 Accessibility 广播包装成统一事件：

- `USER_INTERACTION`
- `WINDOW_CHANGED`

### `SafetyMonitor.java`

负责根据前台 package name 判断当前是不是敏感页面。

敏感页面关键词包含：

- bank
- payment
- wallet
- tng
- authenticator
- token
- maybank / cimb / rhb / publicbank 等

---

## 6.7 高亮绘制

### `HighlightRenderer.java`

负责真正把高亮 view 挂到系统 overlay 上。

### `HighlightOverlayView.java`

负责绘制：

- 高亮圆圈
- 外圈 pulse
- 指向箭头

### 当前真实高亮颜色

虽然 APP 品牌色已经是蓝色，但高亮绘制目前仍然是**橙色**：

- 边框：`#FF6B00`
- 填充：`#55FF6B00`

这是一个非常关键的设计事实：

- **应用 chrome 是蓝色**
- **目标高亮是橙色**

这未必是坏事，因为对长者来说橙色比蓝色更容易一眼看见。  
如果你重做，建议你明确决定：

1. 要么继续保留橙色高亮，作为“动作提示色”
2. 要么统一成蓝色，但要确保可视性不下降

我的建议：**保留橙色高亮更合理。**

---

## 6.8 语音输入现状

当前语音链路并不完全统一，这是后续重做时必须注意的地方。

### 现有路径

1. `OverlayService` 里直接使用本地 `SpeechRecognizer`
2. 另外又存在 `OverlayVoiceCaptureActivity.java`
3. 没有麦克风权限时会拉起 `MicPermissionActivity.java`
4. `GeminiLiveClient` 还支持把 PCM 音频 chunk 通过 WebSocket 发给 server

这说明：

- 当前系统里同时存在“本地语音识别”和“流式音频发送”的设计
- 实现上偏复杂
- 是一个明显的 design debt

### 重做建议

后续重做时，应统一语音输入方案，只保留一种主路径：

- 要么完全本地识别后发文字
- 要么完全流式语音上传 server
- 不建议长期同时保留两套交互入口

---

## 7. Server 端模块说明

## 7.1 入口

### `server/src/index.js`

提供两个主要入口：

- `GET /health`
- `WS /live`

每个 Android 连接会创建一个 session controller。

---

## 7.2 默认控制器

### `server/src/core/SessionController.js`

这是当前 server 端真正的主控制器。

它负责：

- 接受 Android 消息
- 把 legacy 消息协议正规化
- 持有当前 session state
- 连接 Gemini Live
- 调度状态机与执行器
- reconnect 处理
- 心跳检测
- 和 Android 保持兼容协议翻译

### 关键点

- 内部仍兼容老协议
- 会把新输出翻译成 Android 现有能读懂的 legacy message
- 这是为什么 Android 端还是在收 `ready` / `highlight` / `taskComplete` 这类消息

---

## 7.3 状态机

### `server/src/core/TaskStateMachine.js`

当前 server 的任务流程已经被显式状态机化。

状态包括：

| 状态 | 含义 |
|---|---|
| `IDLE` | 没有任务 |
| `INTAKE` | 收到新目标，准备开始 |
| `SAFETY_CHECK` | 先做安全检查 |
| `PLANNING` | 生成步骤 |
| `CLARIFYING` | 缺信息，需要追问用户 |
| `GUIDING` | 准备引导当前步骤 |
| `AWAITING_ACTION` | 已告诉用户下一步，等待用户动作 |
| `VERIFYING` | 用户完成动作后，验证画面 |
| `RETRYING` | 验证失败后的重引导 |
| `HELPING` | 超时或找不到时进入帮助模式 |
| `STUCK` | 多次失败后卡住 |
| `COMPLETED` | 完成 |
| `FAILED` | 失败 |

这是当前项目里非常重要的一部分。  
你后续重做时，**建议完整保留“显式状态机”这条路线**。

---

## 7.4 执行器

### `server/src/core/TaskExecutor.js`

负责把状态机的当前状态转成实际动作：

- safety check
- 生成 plan
- 请求 screenshot
- 调用 Gemini Live 看图
- 处理 verify pass / fail
- 输出 guidance / chat / completed / stuck

它相当于“工作流执行层”。

---

## 7.5 安全层

### `server/src/core/SafetyGate.js`

这是 server 端的硬性安全判断层。

它会识别：

- 冒充警察 / 银行 / 政府
- 所谓 “safe account”
- 远程控制 App
- 分享 OTP / PIN / password
- 给陌生人转账
- 可疑链接

同时也会对下面这些动作做二次确认：

- 转账
- 扫 QR
- 安装 App
- 删除
- 改密码 / 新设备绑定

支持语言：

- `zh`
- `en`
- `ms`

这说明 server 的安全层对 Malay 是有支持的。

---

## 7.6 任务规划器

### `server/src/agents/taskAgent.js`

负责把用户目标拆成结构化步骤。

每一步通常包含：

- `instruction`
- `action`
- `target`
- `matchHints`
- `avoidHints`
- `expectedResult`

### 当前规划策略不是纯模型

它现在实际上是三层：

1. heuristic plan
   - 对某些典型 WhatsApp / call 场景直接写死逻辑
2. model-generated plan
   - 用 Gemini 生成 JSON 步骤
3. fallback plan
   - 如果失败，就退回 `ask_user`

### 重要结论

当前导航已经不是旧 planner 预生成完整步骤，而是：

**ReAct next-action decision + vision grounding + bounded retry**

旧 TaskAgent planner 已删除；失败时进入 STUCK/询问用户，不再切回旧 planner。

---

## 7.7 Gemini Live 适配层

### `server/src/agents/geminiLiveAgent.js`

作用：

- 连到 Gemini Live WebSocket
- 发 setup message
- 发图片 + context
- 发音频 chunk
- 收到 audio / transcription / tool call

### `server/src/agents/liveGuidanceConfig.js`

定义了：

- Live session setup
- tool schema
- voice 配置
- 当前 voice name：`Aoede`

唯一核心工具是：

- `highlightElement(x, y, completed, blockerDetected, blockerReason)`

这也是整个视觉引导闭环的核心协议。

---

## 7.8 Prompt 体系

当前 prompt 已经模块化，不再是单一大 prompt。

目录：

```text
server/src/prompts/
├─ chat/
├─ guide/
├─ navigation/
├─ contextBuilder.js
└─ index.js
```

### 当前 prompt 设计要点

- `navigation` 负责基于当前屏幕决定一个下一步动作
- `guide` 负责看截图并决定说什么、指哪里
- `chat` 负责追问缺失信息
- `contextBuilder` 负责把 GOAL / STEP / TARGET / EXPECTED_RESULT 拼成上下文块

### 当前 guide prompt 的核心原则

- 语气温和、像家人一样
- 一次只说一句话
- 一次只引导一个动作
- 高置信度才给准确坐标
- 低置信度则用 `x=50, y=50` 并让用户滚动 / 滑动
- verify 时如果确认完成，`completed=true`

---

## 7.9 Metrics / 测试 / 验证

### Metrics

`server/src/core/TaskMetricsLogger.js`

会记录：

- 状态切换
- task summary
- retry 次数
- help 次数
- duration

默认日志文件：

- `logs/task-metrics.jsonl`

### 自动化测试

当前已存在：

- unit tests
- prompt registry tests
- context builder tests
- safety gate tests
- state machine tests
- task executor tests
- session controller tests
- integration replay tests

### Promptfoo

当前 `server/promptfoo/` 已经支持：

- navigation-react
- guide-navigate
- guide-verify
- guide-retry
- guide-help
- chat

### GitHub Actions

`.github/workflows/test.yml` 当前会跑：

- server unit tests
- prompt tests（有 `GEMINI_API_KEY` 时）
- integration replay（schedule / manual）

这说明这个项目虽然是 FYP，但 backend 这部分已经开始有比较像正式工程的验证体系。

---

## 8. 当前消息协议

## 8.1 Android -> Server

当前 Android 发送的主要 legacy message：

| type | 说明 |
|---|---|
| `text` | 用户文字请求 |
| `image` | base64 screenshot |
| `audio` | base64 PCM 音频 |

额外字段：

- `query`
- `verify`
- `width`
- `height`

## 8.2 Server 内部正规化后的消息

`SessionController` 会把上面的 legacy message 规范成：

- `user_input`
- `screenshot`
- `audio_chunk`
- `user_action`
- `help_request`
- `cancel`
- `sensitive_screen`
- `ping`

## 8.3 Server -> Android

Android 当前实际在收的 legacy 协议：

| type | 说明 |
|---|---|
| `connecting` | Gemini / server 正在连接 |
| `ready` | session ready |
| `text` | AI 文本提示 |
| `audio` | 语音音频 |
| `highlight` | 高亮坐标 |
| `transcription` | 语音识别结果 |
| `taskComplete` | 任务完成 |
| `requestScreenshot` | 请求重新截图 |
| `error` | 错误消息 |

其中 `highlight` 结构尤其重要：

```json
{
  "type": "highlight",
  "x": 42,
  "y": 63,
  "completed": false,
  "blockerDetected": false,
  "blockerReason": null
}
```

---

## 9. 真实的产品流程

```text
1. 用户打开 SmartHelp+
2. 进入 Splash
3. 进入 MainActivity
4. 首次进入会看到 onboarding
5. 点击首页大 orb
6. 依次申请：
   - Notification
   - Microphone
   - Overlay
   - Screen Capture
   - Accessibility（可选但推荐）
7. 启动 OverlayService + ScreenCaptureService
8. 用户切到目标 App
9. 通过 mic 说出目标
10. Android 把 query / screenshot 发到 server
11. Server 先做 safety check
12. Server 生成 plan
13. Gemini Live 根据 screenshot + context 给出一句话 + highlight
14. Android 播放语音并显示高亮
15. 用户点击后再次截图 verify
16. 成功则下一步，失败则 retry/help
17. 最终 task complete
18. 弹出完成卡片，可继续问或回首页
```

---

## 10. UI / UX 设计总则

这一部分是你后续重做时最应该当成“设计基线”的内容。

## 10.1 设计关键词

- calm
- soft
- senior-friendly
- voice-first
- spacious
- rounded
- trustworthy
- low cognitive load

### 不应该做成什么样

- 不要做成聊天机器人首页
- 不要把 overlay 做成复杂控制台
- 不要让用户同一时间面对很多按钮
- 不要让页面充满强对比、霓虹、暗黑、赛博风
- 不要把引导写成长段文字

---

## 10.2 推荐的“标准品牌方向”

当前最值得保留的视觉方向来自：

- `colors.xml`
- `activity_main.xml`
- `activity_splash.xml`
- `design.md`

可以总结成一句：

**主品牌是温和蓝色，信息层是白色圆角卡片，重点动作是大圆形或 pill，overlay 尽量简洁。**

---

## 10.3 颜色系统

## 10.3.1 建议作为“主规范”的颜色

| Token | Hex | 用途 |
|---|---|---|
| Primary | `#1565C0` | 主按钮、强调、品牌蓝 |
| Primary Dark | `#0D47A1` | 深蓝、按下态、品牌渐变 |
| Primary Light | `#42A5F5` | 浅蓝装饰、ring、次强调 |
| Accent | `#00ACC1` | 次强调、辅助信息 |
| Navy Primary | `#0A2342` | 大标题、深色文案、hero 内深色内容 |
| Background | `#E3F2FD` | 主背景底色 |
| Surface | `#FFFFFF` | 白色内容面板 |
| Surface Variant | `#E3F4FF` | 轻量卡片背景 |
| Text Primary | `#1C1B1F` | 主正文 |
| Text Secondary | `#6B7280` | 次正文 |
| Text Hint | `#9CA3AF` | hint |
| Success | `#10B981` | 完成 / 成功 |
| Warning | `#F59E0B` | 警告 |
| Error | `#EF4444` | 错误 |

## 10.3.2 重要提醒：命名和真实颜色不一致

当前代码里有很多旧命名，比如：

- `brand_purple`
- `brand_purple_dark`
- `bg_info_card_purple`

但这些资源的**真实颜色已经是蓝色系**。  
重做时不要被名字误导，应该看实际 hex。

---

## 10.4 关键渐变与组件背景

这些是当前代码里最有代表性的视觉材料。

### Hero 背景

- 文件：`bg_hero_gradient.xml`
- 颜色：
  - `#E3F4FF`
  - `#BBDEFB`
  - `#FFFFFF`

用途：

- Splash
- Main
- Settings

### 首页 idle orb

- 文件：`bg_welcome_cta_idle.xml`
- 颜色：
  - `#0A2342`
  - `#0D47A1`
  - `#1565C0`

用途：

- 首页主 mic orb

### 首页 active orb

- 文件：`bg_welcome_cta_active.xml`
- 颜色：
  - `#085B4A`
  - `#10997C`
  - `#30C79D`

说明：

- 当前 active 态改成绿色系，传达“正在服务中”
- 这个设计是合理的，可以保留

### 蓝色信息卡

- 文件：`bg_info_card_blue.xml`
- 渐变：
  - `#EBF3FF`
  - `#DBEAFE`

### 旧命名“purple”卡

- 文件：`bg_info_card_purple.xml`
- 实际颜色：
  - `#E3F2FD`
  - `#BBDEFB`

说明：

- 名字旧，但视觉仍然是蓝色
- 设置页目前大量使用它

### overlay 状态 pill

- 文件：`bg_overlay_status_pill.xml`
- 颜色：`#DD0A2342`

用途：

- step badge
- listening / thinking pill
- floating ball step badge

### overlay 输入壳层

- 文件：`bg_overlay_input_shell.xml`
- 背景：`#FFFCF8`
- 边框：`#E7DDD2`

说明：

- 它不是纯白，更偏暖白
- 这使 overlay 在深色底板上更柔和

### chat 气泡

- 用户气泡：`bg_chat_user_bubble.xml`
  - 主色 `#1565C0`
- AI 气泡：`bg_chat_ai_card.xml`
  - 白底 + 左侧蓝条

---

## 10.5 高亮颜色

### 当前实现

| 项目 | Hex |
|---|---|
| Highlight Border | `#FF6B00` |
| Highlight Fill | `#55FF6B00` |
| Arrow | `#FF6B00` |

### 建议

后续重做时，推荐明确把高亮颜色定义为单独一套 token，例如：

- `highlight_primary = #FF6B00`
- `highlight_fill = #55FF6B00`

这样品牌蓝和操作提示色不会混淆。

---

## 10.6 字体与大小

当前主题和页面大致采用以下层级：

| Style | 当前尺度 |
|---|---|
| Display | `34sp` 到 `38sp` |
| Headline | `26sp` 到 `28sp` |
| Title | `20sp` 到 `22sp` |
| Body | `15sp` 到 `17sp` |
| Guidance | `18sp` |
| Caption | `12sp` 到 `13sp` |

当前字体：

- 默认 `sans-serif`

建议：

- 对长者产品来说，默认无衬线字体是合理的
- 不需要为“设计感”强行上花哨字体
- 更重要的是字号、字重、留白和对比度

---

## 10.7 形状语言

当前 UI 形状规律非常清楚：

- 大量使用圆角卡片
- 主要按钮用 pill
- 首页主动作用圆形 orb
- 底部 panel 顶角大圆角
- 悬浮球也是圆形

建议重做时统一以下规则：

| 组件 | 建议圆角 |
|---|---|
| 主要卡片 | `16dp` 到 `22dp` |
| 大底部 panel | 顶角 `28dp` 到 `36dp` |
| 状态 pill | `16dp` 到 `20dp` |
| CTA 按钮 | 高度一半作为 radius |
| orb | 完整圆形 |

---

## 10.8 页面级设计结构

### 推荐保留的页面模板

#### 模板 A：Hero + White Sheet

适用页面：

- Splash
- Main
- Settings

结构：

1. 顶部/中上部是浅蓝 hero
2. 底部是白色圆角 content sheet

#### 模板 B：Bottom Guidance Panel

适用页面：

- Overlay 主引导

结构：

1. 深色/渐变底板
2. guidance 卡片
3. error 卡片（按需）
4. quick actions（按需）
5. mic 输入区

#### 模板 C：Floating Ball + Speech Bubble

适用页面：

- 最小化模式

结构：

1. 小球
2. 侧边气泡
3. 点击后展开详情卡

---

## 10.9 当前视觉不一致点

这一段非常重要，因为你要“重新做”，最好从这里开始清理。

### 1. 命名还是 purple，但品牌已经是 blue

- `brand_purple*`
- `bg_info_card_purple`
- theme 注释中还写着 “Premium Purple”

### 2. overlay 主底板颜色比首页更偏紫/靛蓝

`bg_bottom_sheet_soft_blue.xml` 实际颜色是：

- `#F0091C33`
- `#EA114A8B`
- `#D81E6DD0`

它和首页更干净的浅蓝风格不完全统一。

### 3. Splash 相关还有旧紫色资源残留

- `bg_splash_deep.xml`

但当前 `activity_splash.xml` 实际用的是 `bg_hero_gradient.xml`。

### 4. highlight 用橙色，品牌用蓝色

这不是 bug，但必须在设计系统中明确定位。

### 5. 语音输入路径重复

- overlay 内直接语音识别
- 另外又有 `OverlayVoiceCaptureActivity`

视觉和流程都可能因此出现重复设计。

---

## 11. 当前页面清单

| 页面 / 组件 | 主要文件 | 说明 |
|---|---|---|
| Splash | `activity_splash.xml`, `SplashActivity.java` | 启动页 |
| Home | `activity_main.xml`, `MainActivity.java` | 首页 |
| Settings | `activity_settings.xml`, `SettingsActivity.java` | 设置页 |
| Onboarding | `dialog_onboarding.xml` | 首次引导 |
| Overlay Main Panel | `overlay_guidance.xml` | 主引导面板 |
| Floating Ball | `overlay_ball.xml` | 最小化模式 |
| Chat Panel | `overlay_chat.xml` | 聊天记录 |
| Task Complete | `dialog_task_complete.xml` | 完成弹层 |
| Permission Helper | `MicPermissionActivity.java` | 麦克风权限中转 |
| Voice Capture Helper | `OverlayVoiceCaptureActivity.java` | 专门的语音捕获 activity |
| Highlight Overlay | `HighlightOverlayView.java`, `HighlightRenderer.java` | 箭头与高亮圈 |

---

## 12. 语言支持现状

这是一个容易误判的点，必须写清楚。

### 当前真实情况

- Android UI：**主要是 English + Chinese**
- Server safety/navigation/guide：**English + Chinese + Malay**
- 语音识别 language tag：
  - `en-US`
  - `zh-CN`
  - `ms-MY`

### 结论

这个项目目前是：

- **前端 UI 双语为主**
- **后端与安全层三语能力更强**

如果你重做时想完整支持 Malay，需要把：

- Android 页面文案
- Settings UI
- Onboarding
- Overlay 提示文案

一起补齐，而不是只改 server。

---

## 13. 隐私与安全原则

## 13.1 客户端层

`PrivacySafety.java` 会明确告诉用户：

- 只有在主动求助时才工作
- 会发送截图、语音、文字到你配置的 server 和 AI
- 不要在银行 / 支付 / OTP / 密码场景继续使用
- 遇到敏感页面时会自动暂停

## 13.2 服务端层

`SafetyGate.js` 会：

- 先挡下明显高风险任务
- 对金钱相关动作做确认
- 给出诈骗提示

## 13.3 设计原则

这个产品的安全设计不是“尽量帮用户完成所有任务”，而是：

**高风险时宁愿保守、宁愿暂停，也不要自信地误导。**

这条原则建议你在重做时继续保留。

---

## 14. 当前代码里哪些东西可以当“重做基准”

### 最值得保留的部分

1. 首页整体结构
   - `activity_main.xml`
   - 大圆 orb
   - hero + white sheet

2. Splash 视觉方向
   - `activity_splash.xml`

3. 显式状态机
   - `TaskStateMachine.js`

4. SafetyGate
   - `SafetyGate.js`

5. 模块化 prompt 结构
   - `server/src/prompts/`

6. overlay 的功能闭环
   - 语音
   - 截图
   - 高亮
   - verify
   - retry/help
   - complete

### 可以保留思路、但建议重构的部分

1. `OverlayService.java`
   - 过大，职责太多

2. 语音输入路径
   - 有重复

3. 旧命名资源
   - purple 命名、旧注释

4. 设置页
   - 功能全，但视觉和结构还可以更干净

### 明显属于兼容/旧时代残留的部分

1. `OrchestratorAgent` 作为主架构描述
2. monolithic prompt backup
3. 某些旧 purple 注释与旧 drawable

---

## 15. 重做时建议的功能优先级

## 15.1 第一优先级：必须先做

- Home
- Settings
- Onboarding
- 权限流
- Overlay 主面板
- 截图服务
- WebSocket 通讯
- highlight 渲染
- state machine
- basic safety

## 15.2 第二优先级：尽快补上

- floating ball mode
- chat history
- task complete sheet
- quick actions
- reconnect UX
- recent tasks

## 15.3 第三优先级：可以后做

- 更高级的 prompt evaluation
- 更完整的 Malay UI
- analytics dashboard
- 更强的 metrics 展示

---

## 16. 推荐的重做顺序

如果你真的要从头再做，我建议按下面顺序重建，而不是一开始就把所有功能一次堆上去。

### Phase 1：设计系统和壳

- 先定颜色 token
- 先定 typography
- 先定 hero + white sheet 布局模板
- 重做 Splash / Home / Settings 外壳

### Phase 2：权限与基础运行

- 权限流
- Overlay 权限
- Screen capture 权限
- 基础 foreground service

### Phase 3：核心引导闭环

- Overlay panel
- WebSocket
- 发送文字 query
- 截图上传
- highlight 显示
- task complete

### Phase 4：智能流程

- state machine
- ReAct navigation
- verify / retry / help
- sensitive protection
- safety gate

### Phase 5：体验优化

- floating ball
- chat history
- quick actions
- reconnect UX
- recent tasks
- sounds / settings polish

---

## 17. 我对重做版本的具体建议

这是我结合当前代码后，给你的最实用建议。

### 17.1 产品定位不要变

不要把 SmartHelp+ 做成“聊天机器人”。  
应该继续做成：

- 老人友好
- 语音优先
- 一步一步
- 当前界面导向

### 17.2 设计系统要统一

建议你以后统一用下面这套规则：

- APP 主品牌：蓝色
- 高亮提示：橙色
- 白色卡片做信息层
- 大圆 orb 做主入口
- overlay 尽量少按钮

### 17.3 语音入口只能保留一套

重做时一定要统一：

- 是本地 STT 还是 server streaming
- 不要再留两套相似入口

### 17.4 把旧命名一次清掉

建议统一重命名：

- `brand_purple` -> `brand_blue`
- `bg_info_card_purple` -> `bg_info_card_primary`

至少新的设计系统不要继续继承错误命名。

### 17.5 继续保留 server 的状态机路线

不要退回“全靠 prompt 自己决定流程”的做法。  
当前 `TaskStateMachine` 是这个项目最正确的方向之一。

---

## 18. 重要参考文件

如果后续要继续研究原项目细节，优先看这些：

### 产品 / 设计类

- `README.md`
- `design.md`
- `UI_DESIGN_SPEC.md`
- `SMARTHELP_OVERVIEW.md`

### 架构 / prompt / 重构类

- `AGENT_REPLANNING_BRIEF.txt`
- `PROMPT_ANALYSIS.md`
- `server/docs/migration-status.md`
- `server/docs/manual-verification-checklist.md`

### Android 关键文件

- `android/app/src/main/java/com/smarthelp/app/MainActivity.java`
- `android/app/src/main/java/com/smarthelp/app/OverlayService.java`
- `android/app/src/main/java/com/smarthelp/app/ScreenCaptureService.java`
- `android/app/src/main/java/com/smarthelp/app/SettingsActivity.java`
- `android/app/src/main/java/com/smarthelp/app/AppPrefs.java`
- `android/app/src/main/res/layout/activity_main.xml`
- `android/app/src/main/res/layout/overlay_guidance.xml`
- `android/app/src/main/res/values/colors.xml`

### Server 关键文件

- `server/src/index.js`
- `server/src/core/SessionController.js`
- `server/src/core/TaskStateMachine.js`
- `server/src/core/TaskExecutor.js`
- `server/src/core/SafetyGate.js`
- `server/src/agents/taskAgent.js`
- `server/src/agents/geminiLiveAgent.js`
- `server/src/prompts/`

---

## 19. 最终结论

如果你接下来要“重新开始做这个 APP”，你最应该记住的不是某个旧页面细节，而是这 8 件事：

1. SmartHelp+ 的本质是“语音 + 高亮”的一步一步引导，不是聊天机器人。
2. 当前正确的架构方向是：显式状态机 + ReAct navigation + vision grounding，而不是旧式完整 planner。
3. Android 端的核心不是首页，而是 `OverlayService` 这条引导闭环。
4. Server 端真实核心已经是 `SessionController + TaskStateMachine + TaskExecutor`。
5. 品牌主色已经是蓝色，旧的 purple 命名只是历史残留。
6. 高亮用橙色是合理的，因为它比品牌蓝更适合作为“行动提示色”。
7. 安全策略必须保守，尤其是银行、OTP、诈骗场景。
8. 重做时应该先把设计系统、权限流、overlay 闭环和状态机做好，再做次要功能。

如果只用一句话概括重做方向：

**把 SmartHelp+ 重做成一个更干净、更统一、更可靠的蓝色语音导航助手，同时保留橙色高亮、显式状态机和保守安全策略。**
