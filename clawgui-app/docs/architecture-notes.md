# ClawGUI Android 架构笔记

这个项目从 Python 版 nanobot(`/pubdata/lihp/prj/JiuGui/ClawGUI/clawgui-agent/nanobot`)移植而来。本文先给一份项目地图(目录结构、技术栈、运行流程),再讲修改核心组件前的几条约定。

## 项目结构

```text
app/src/main/kotlin/com/clawgui/android/
├── App.kt                         # Application,组装全局状态、channel、trace、slot
├── core/
│   ├── ime/                       # ClawguiIME
│   ├── nano/                      # brain / nanobot
│   │   ├── agent/                 # AgentLoop、上下文构建、工具
│   │   ├── bus/                   # 消息总线
│   │   ├── channels/              # InApp / Feishu / Dispatcher
│   │   ├── providers/             # 多 provider LLM 接入
│   │   ├── session/               # 会话持久化
│   │   ├── trace/                 # TraceRecorder
│   │   ├── cron/ heartbeat/ ...   # 定时和基础能力
│   ├── phone/                     # phone agent / GUI executor
│   │   ├── actions/               # 动作定义与解析
│   │   ├── memory/                # phone agent 层记忆
│   │   ├── model/                 # 模型调用与 adapter
│   │   ├── platform/              # 截图、设备交互
│   │   └── tracer/                # phone 侧 trace 辅助
│   └── util/                      # 通用日志、导出等
├── platform/
│   ├── http/                      # HTTP / SSE
│   └── shizuku/                   # DeviceController、IME 控制等
├── service/                       # AgentService / ShellService
└── ui/
    ├── overlay/                   # 悬浮球
    ├── screens/                   # Chat / Drawer / Inbox / Settings / Onboarding
    └── theme/                     # Compose Theme
```

## 技术栈

- Kotlin 1.9.20
- Android Gradle Plugin 8.2.0
- Jetpack Compose + Material 3
- compileSdk 36 / targetSdk 36 / minSdk 26
- JDK 17
- OkHttp 4.12.0 + SSE
- kotlinx.serialization / kotlinx.datetime / coroutines
- Shizuku 13.1.5
- Feishu `oapi-sdk` 2.4.16
- `androidx.security-crypto` 用于敏感配置加密存储

完整依赖见 [app/build.gradle.kts](../app/build.gradle.kts)。

## 运行流程

```text
用户 / 飞书消息
        ↓
   nanobot(brain)
        ↓
  gui_execute 工具调用
        ↓
 phone agent(VLM)
        ↓
 截图 → 规划动作 → Shizuku 执行
        ↓
 结果返回 brain → 写入会话 / 回发 channel / 落 trace
```

## 修改前注意事项

动手改 nano 核心抽象之前花几分钟读一下下面的约定,能少绕弯、少反复。

### 先对照 Python 原版 nanobot

核心抽象都有 Python 对应实现:`AgentLoop`、`ContextBuilder`、`AgentMemory`、`MemoryConsolidator`、`SessionManager`、`ToolRegistry` 等。在加新抽象之前,先去 Python 原版对应目录 grep 一下:

- `clawgui-agent/nanobot/nanobot/agent/` — 主循环、上下文、记忆
- `clawgui-agent/nanobot/nanobot/providers/` — LLM provider
- `clawgui-agent/nanobot/nanobot/command/` — 斜杠命令

如果 Python 版没有对应抽象,说明这类问题在上游是用别的方式解决的。抄一份 Python 的解法通常比另起炉灶成本更低,后续跟进上游改动也更省力。

### 两层 agent 的契约,不要从外部打破

系统是两层 agent:

- **大脑层(nanobot,glm-4.5)**:function-calling LLM。接收用户原始输入,通过自主选择 `gui_execute` / `read_memory` / `write_memory` 等工具来决定"下一步做什么"。
- **执行层(phone_agent,AutoGLM VLM)**:被 `gui_execute` 工具启动后接管,只负责把"操作手机"这个子任务跑完。

大脑层的核心能力是 **自主决策**:闲聊就回文字,动手就调工具。这个判断由三样东西驱动:

1. 注册的工具列表(`ToolRegistry.getDefinitions()`)
2. 每个工具的 `description`
3. `ContextBuilder.getIdentity()` 里对工具用途和边界的描述

想调整大脑的行为,优先级应该是:**改 prompt → 改 tool description → 增减注册的工具**。这三者依次穷尽之前,尽量不在大脑之外再加一层决策(分类器、路由器、意图识别等)。原因:

- function-calling LLM 已经内置了"在文本回复和工具调用之间分配"的能力,外部再加一层做同样的判断容易和 LLM 自身意图冲突。
- 前置层能看到的上下文通常比主 agent 少(没 MEMORY、没 tool schema、没对话中注入的运行时信息),判断依据更弱。
- 一次用户请求会变成两次 LLM 调用,延迟和费用翻倍。

觉得必须加前置层的时候,先回到 prompt 里用具体例子把边界写清楚,跑一遍再评估是否真的需要外层。

### 改 system prompt 之前,先核对实际注册的工具

`ContextBuilder.getIdentity()` 里提到的工具名 **必须** 和 `App.sendInstruction` 里 `tools.register(...)` 的注册项严格对齐。提了但没注册,模型会尝试调用不存在的工具,或者误把别的工具往那个名字上靠,引发连锁怪行为(比如"你好"也被硬调 `gui_execute`)。

动手前查一眼:

```bash
grep -n 'tools\.register(' app/src/main/kotlin/com/clawgui/android/App.kt
```

对照 `ContextBuilder.getIdentity()` 里的工具清单,有一个算一个。删工具时两边一起改。

### Memory / History / Session 三层职责别混

- **MEMORY.md**(`AgentMemory.readLongTerm` / `writeLongTerm`)—— 长期事实。每次新会话启动会注入 system prompt。例:用户姓名、偏好。
- **HISTORY.md**(`appendHistory` / `readRecentHistoryEntries`)—— 带时间戳的流水。靠 `read_memory` 工具按需查,不注入 system。例:"2026-04-19 14:30 用户让我打开微信发了条消息"。
- **Session JSONL**(`SessionManager`)—— 单次会话的完整消息历史(含 `tool_calls`),作为 `history` 喂给 LLM。超过 `contextWindowTokens` 时会被 `MemoryConsolidator` 总结进 MEMORY/HISTORY 后清空。

新增工具类动 MEMORY 或 HISTORY 就够,**不要**去改 Session 的内容。Session 的写入由 `AgentLoop.saveTurn` / `recordCancellation` 统一负责。

### 异常路径也要写进 Session

`AgentLoop.processMessage` 里任何异常分支都要确保当轮对话被记入 Session,否则下一轮 LLM 会把上一轮当成"没发生",容易回溯到更早的动作重放。`CancellationException` 尤其容易漏,已经用 `recordCancellation` 兜底,新增异常类型时记得参考这个模式。

### Consolidation 阈值

`contextWindowTokens` 默认 65536(和 Python 版对齐)。Android 场景对话偏短,实际很少触发 consolidation,所以 MEMORY 当前主要靠 `write_memory` 工具主动写入。这是可以接受的现状;如果后续确有长对话需求(桌面端迁移、长跑任务),再下调阈值,不要一开始就调,避免过早优化。

### 提交前自检清单

- [ ] `ContextBuilder` 里提到的工具都在 `App.kt` 注册了
- [ ] 新增抽象在 Python 原版 nanobot 里有对应实现可参考
- [ ] 没有引入和 function-calling LLM 并行的"第二个决策者"(路由器、分类器、意图识别等);如果必须加,已经在 PR 描述里写清楚为什么 prompt/tool description 那条路走不通
- [ ] 写入 Session 的路径覆盖了所有异常分支

以上任何一条判断不准,就在 PR 描述里写出你的理由,让 review 一起对齐。
