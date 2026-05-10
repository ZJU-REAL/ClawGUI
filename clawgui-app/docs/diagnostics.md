# ClawGUI 诊断日志

开发/调试阶段用来定位"怎么复现都难说,但在真机一跑就坏"的 bug。本机我不好跑测试,同事在手机上复现 → 导出日志 → 发给我,我拿日志 grep 定位。

**上线前这套入口可以从 UI 隐藏或整个撤掉**(见文末)。

## 同事使用(复现一个 bug)

1. 打开 App,**设置 → 关于 → 诊断 → 打开"诊断模式"**(打开后会多打一点细节日志,不影响功能)
2. **复现 bug**(正常用就好)
3. 回到 **设置 → 关于 → 诊断 → 点"导出诊断日志"**
4. 弹出系统分享窗口,选微信/邮件/任何能传文件的应用,发给开发者
5. bug 报告里附带:**哪一步开始不对**、**预期是什么**、**实际看到什么**(这段文字比 log 关键)

旧日志会堆在手机里但只占几 MB,搞累了可以点"清空旧日志"。

## 日志落在哪

- 手机路径:App 私有目录 `files/workspace/diagnostics/log_<时间戳>.txt`
- 分享通过 `FileProvider`,系统会自动临时授权
- 每条 log 带 `sessionKey`,跨线程/跨 channel 能对上同一笔 turn

## 开发者视角

### 抓日志的两种方式

| 方式 | 前提 | 怎么做 |
|---|---|---|
| App 内置导出 | 只需手机 | 上述步骤,拿到 txt |
| ADB 直连 | 电脑连手机、USB 调试 | `adb logcat -d -v time \| grep ClawGUI > clawgui.log` |

ADB 的优势是能拿**实时**日志(`adb logcat` 去掉 `-d`),不用等同事操作。App 内置的优势是**同事零门槛**。

### 日志 tag 速查

所有 tag 都带 `ClawGUI/` 前缀,`adb logcat -d | grep ClawGUI` 一把梭。按功能块:

| Tag | 作用 |
|---|---|
| `ClawGUI/AppCore` | App.onCreate、acquireSlot/releaseSlot/stopSession、turn 生命周期 |
| `ClawGUI/AgentLoop` | dispatch 开始/结束/异常、cancelSession |
| `ClawGUI/Dispatcher` | outbound 消费循环、每条 msg 到哪个 channel、耗时 |
| `ClawGUI/InAppChannel` | 应用内对话 channel 的 send 进出 |
| `ClawGUI/LogExporter` | 日志导出本身(用来排查"连日志都导不出")|
| `ClawGUI/Log` | 诊断模式开关事件 |

### 读 log 的套路

1. **按 sessionKey grep**。比如 `grep "\[ui:abc\]" log.txt`,就是那一笔 turn 的全部踪迹。
2. **看时间差**。`AgentLoop dispatch done (Nms)` 这条里的 `Nms` 是 turn 耗时;`Dispatcher ✓ channel.send done (Nms)` 是送 UI 的耗时。
3. **找缺口**。如果 `AgentLoop dispatch done` 后没看到 `Dispatcher → in_app.send`,那说明消息没进 bus;如果有 `→` 没有 `✓`,说明卡在 channel.send 里(当前那个 Done 不切的 bug 就是靠这个定位)。

### 诊断模式 vs 常开

- 常开(关机默认):关键节点(acquireSlot、dispatch 进出、send 进出、错误)—— 够复原一次 turn 的骨架
- 诊断模式(用户手动开):预留了 `Log.diag` 标志,以后需要"每步 VLM 输入输出都打"之类细节时,用 `if (Log.diag) Log.i(...)` 守一下

当前版本还没有"诊断模式专属"的日志点。等下次定位某个具体 bug 需要更细日志时再补。

## 上线前怎么撤

一个最小回滚:

1. `SettingsScreen.kt::AboutSubPage` 里那张"诊断"Card 整块删掉(或者加个 BuildConfig.DEBUG 守卫只在 debug 包显示)
2. `LogExporter.kt` / `Log.kt` / `SettingsStore.diagnosticMode` 可以留,无副作用
3. AndroidManifest 的 `FileProvider` 声明也保留,`file_paths.xml` 里 `workspace_diagnostics` path 保留(FileProvider 没被调用就不触发)

不建议直接删掉 `Log.kt` —— 代码里一堆地方已经用它,删了要大改。隐藏 UI 入口最干净。
