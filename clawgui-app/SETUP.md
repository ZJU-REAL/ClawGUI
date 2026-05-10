# ClawGUI Android 安装与配置

[中文 README](README.md) | [English README](README_EN.md) | [English Setup Guide](SETUP_EN.md)

本文是 ClawGUI Android 的完整上手指南：从环境要求到 APK 编译/安装、Shizuku 启动、ClawGUI 首次配置、推荐使用流程，以及常见问题。

如果你只想了解 ClawGUI 是什么、能做什么、技术架构是怎样的，请先看 [README.md](README.md)。

---

## 安装与配置

这一节按“普通用户真正上手”的顺序来写。

### 1. 环境要求

如果你是开发者，需要：

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 36
- 一台 Android 8.0+ 设备

如果你只是安装使用 APK，需要：

- 一台 Android 8.0+ 设备
- 能联网
- 能安装 [Shizuku](https://github.com/RikkaApps/Shizuku)

### 2. 编译 APK（开发者）

在项目根目录准备 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

然后编译：

```bash
./gradlew assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 安装用户侧 App

用户侧至少需要安装两个 App：

1. **ClawGUI**
2. **Shizuku**

Shizuku 是 ClawGUI 执行 GUI 自动化的前提。没有它，App 可以聊天，但无法稳定执行点击、滑动、输入等设备操作。

### 4. 开启开发者选项

不同品牌入口不一样，但通常流程是：

1. 打开系统“设置”
2. 进入“关于手机”
3. 连续点击“版本号 / Build number”多次
4. 系统提示“您已处于开发者模式”
5. 回到设置，进入“开发者选项”

后面至少要开启：

- `USB 调试`
- `开发者选项`

如果你的设备是 Android 11 及以上，还需要开启：

- `无线调试`

## Shizuku 启动方式

Shizuku 支持三种方式：

1. **root 启动**
2. **Android 11+：无线调试启动**
3. **Android 10 及以下：连接电脑通过 ADB 启动**

### 方式 A：root 设备

如果设备已经 root：

1. 打开 Shizuku
2. 选择 root 启动
3. 启动成功后再打开 ClawGUI

这是最省事的一种方式。

### 方式 B：Android 11 及以上，通过无线调试启动

这是推荐给大多数用户的方式。

#### 第一次配对

1. 打开系统 `开发者选项`
2. 打开 `USB 调试`
3. 打开 `无线调试`
4. 打开 Shizuku，选择通过无线调试启动
5. 在 Shizuku 中点击开始配对
6. 回到系统 `无线调试`
7. 点击 `使用配对码配对设备`
8. 把系统显示的配对码填进 Shizuku

配对通常只需要做一次。

#### 每次开机后的启动

配对完成后，后面每次手机重启，一般只需要：

1. 打开 Shizuku
2. 确认 `无线调试` 仍然开启
3. 点击启动

如果没有成功：

1. 先关闭一次 `无线调试`
2. 再重新开启
3. 回到 Shizuku 再试

#### 需要注意

- 由于 Android 系统限制，**非 root 方式下，Shizuku 重启后通常需要重新启动一次**
- 一些厂商系统会限制后台运行，导致 Shizuku 搜不到配对服务或自动掉线
- 如果是 MIUI / HyperOS / ColorOS / Flyme 这类系统，通常还要额外放开后台运行或关闭某些开发者选项限制

### 方式 C：Android 10 及以下，通过电脑 ADB 启动

Android 10 及以下没有系统级“无线调试”能力，因此**不能按 Android 11+ 的方法直接在手机上完成无线配对**。这类设备最稳妥的方法是通过电脑执行一次 ADB 命令启动 Shizuku。

#### 准备电脑 ADB

1. 在电脑上下载 Google 的 Android SDK Platform Tools
2. 解压到任意目录
3. 打开这个目录对应的终端
4. 先执行：

```bash
adb
```

如果终端能输出帮助信息，说明 ADB 可用。

#### 连接手机

1. 在手机里开启 `开发者选项`
2. 开启 `USB 调试`
3. 用数据线把手机连接到电脑
4. 在电脑执行：

```bash
adb devices
```

5. 手机会弹出“是否允许 USB 调试”
6. 勾选“始终允许”，点击确认
7. 再执行一次：

```bash
adb devices
```

如果看到：

```text
List of devices attached
XXXX	device
```

说明连接成功。

#### 启动 Shizuku

在电脑终端执行：

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

执行完成后：

1. 回到手机打开 Shizuku
2. 确认 Shizuku 显示为已启动
3. 再打开 ClawGUI

#### 需要注意

- 这种方法 **每次手机重启后都需要重新执行一次**
- Android 10 及以下设备没有 Android 11 的原生无线调试能力，所以不能完全替代电脑
- 如果你的设备厂商对 ADB 做了限制，可能还需要在开发者选项里额外放开安全调试相关开关

## ClawGUI 首次配置

当 Shizuku 正常运行后，再配置 ClawGUI。

### 1. 首次授权

打开 ClawGUI 后，按照引导完成：

1. Shizuku 授权
2. 悬浮窗权限
3. 如果要提升输入稳定性，可启用 `ClawGUI 输入法`

### 2. 模型与 Provider 配置

进入设置页，至少配置以下内容：

- Brain provider
- Brain model
- VLM provider
- VLM model
- API Key
- 如有需要，配置 API Base URL

如果你不确定怎么配，最简单的思路是：

- Brain：一个常规对话模型
- VLM：一个已经适配过的 GUI 模型

### 3. 输入法配置

为了让 Agent 更稳定地在输入框中输入文本，建议启用内置输入法：

1. 进入 `设置 -> ClawGUI 输入法`
2. 按页面提示启用输入法
3. 尽量设为默认输入法

如果不启用，某些输入动作会退回到兼容路径，稳定性可能下降。

### 4. 第一次测试

建议先用简单指令测试：

- `打开微信`
- `打开设置`
- `返回桌面`

确认以下链路都正常：

- 模型能返回
- 悬浮球会显示运行状态
- Agent 能执行点击 / 返回 / 打开应用

## 推荐使用流程

第一次使用推荐按这个顺序操作：

1. 安装 Shizuku 和 ClawGUI
2. 启用开发者选项
3. 按你的系统版本启动 Shizuku
4. 打开 ClawGUI 完成首次授权
5. 去设置页填 API Key、provider、model
6. 测试一个简单的 GUI 指令
7. 再尝试更复杂的多步任务

## 常见问题

### 1. 为什么重启手机后又不能用了？

因为非 root 模式下，Shizuku 不是永久后台服务。手机重启后，通常要重新启动一次 Shizuku。

### 2. Android 10 及以下能不能像 Android 11 一样无线调试？

不能。Android 11 的“无线调试”是系统层新能力。Android 10 及以下通常还是要借助电脑 ADB。

### 3. 为什么 Shizuku 一直在搜索配对服务？

通常是因为系统限制了后台运行、局域网访问或无线调试状态异常。优先尝试：

1. 给 Shizuku 允许后台运行
2. 关闭再重新打开无线调试
3. 保持开发者选项和 USB 调试不要关闭

### 4. 为什么 Agent 能回复但不会操作手机？

一般说明：

- Shizuku 没启动
- Shizuku 没授权给 ClawGUI
- VLM 没配好
- 当前模型不适合做 GUI 动作规划

---

## 相关文档

- [README.md](README.md)：项目概览、能力、架构、技术栈
- [docs/feishu-setup.md](docs/feishu-setup.md)：飞书机器人接入步骤
- [docs/diagnostics.md](docs/diagnostics.md)：诊断日志开关与导出方式
- [docs/architecture-notes.md](docs/architecture-notes.md)：核心模块设计要点
