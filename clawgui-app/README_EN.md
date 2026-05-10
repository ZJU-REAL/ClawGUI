# ClawGUI-APP

[Main README](README.md) | [中文安装指南](SETUP.md) | [English Setup Guide](SETUP_EN.md)

*ClawGUI-APP is the on-device Android deployment module of ClawGUI, corresponding to the “deploy ClawGUI-Agent on a real phone” track in the main roadmap.*

<p>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License">
  <img src="https://img.shields.io/badge/Shizuku-required-orange" alt="Shizuku required">
</p>

**ClawGUI-APP** runs the full ClawGUI “brain + GUI agent” stack directly on one Android phone. It removes the old split architecture where a desktop host orchestrates tasks and the phone only executes them. The app is built on top of [Shizuku](https://github.com/RikkaApps/Shizuku) for high-privilege, non-root device control. Two agents cooperate:

- **brain**: a function-calling LLM that understands intent, selects tools, and manages sessions and memory
- **phone agent**: a VLM-driven executor that inspects the screen and performs tap / swipe / type actions

On the product side, execution status is exposed through a floating overlay, text input is stabilized with a built-in IME, traces are persisted for replay and debugging, and external channels such as Feishu can send tasks into the app.

## ✨ Highlights

- **Phone-only workflow**: a rooted device or a device with Shizuku is enough; no desktop coordinator is required
- **Two-agent design**: the brain handles planning and tool orchestration, while the phone agent handles screen understanding and action execution
- **Conversation + automation in one app**: this is not just a script runner; it includes sessions, long-term memory, external channels, and traces
- **Built for real usage**: overlay status, IME, session persistence, external messages, and diagnostics are all part of the main product flow

## 🚀 Quick Start

ClawGUI-APP requires several permissions and system integrations, including Shizuku, overlay permission, and IME setup. Use the setup guide for the full process:

- [English Setup Guide](SETUP_EN.md)
- [中文安装指南](SETUP.md)

Shortest path:

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku) and the ClawGUI-APP APK
2. Start Shizuku using the method that matches your Android version
3. Open ClawGUI-APP and complete Shizuku authorization, overlay permission, and optional IME setup
4. Configure your brain / VLM provider and API key in Settings
5. Validate the setup with simple commands such as `Open WeChat` or `Go back to home screen`

Developer environment requirements, APK build steps, Shizuku startup methods for different Android versions, and troubleshooting are documented in [SETUP_EN.md](SETUP_EN.md).

## 🏗️ Architecture

ClawGUI-APP uses two agents:

- **nanobot / brain** (`core/nano/`)
  - A function-calling LLM
  - Understands user intent
  - Decides whether GUI manipulation is required
  - Orchestrates tools such as `gui_execute`, `read_memory`, and `write_memory`
  - Manages sessions, memory, traces, and external channels

- **phone agent / VLM executor** (`core/phone/`)
  - Takes over after being delegated through `gui_execute`
  - Captures screenshots
  - Calls the configured VLM
  - Parses actions
  - Executes `tap / swipe / type / launch / scroll` through Shizuku

Runtime flow:

```text
User / Feishu message
        ↓
   nanobot (brain)
        ↓
  gui_execute tool call
        ↓
 phone agent (VLM)
        ↓
 Screenshot → plan action → execute via Shizuku
        ↓
 Return result to brain → write session / reply to channel / persist trace
```

## 📦 Current Capabilities

### In-app conversation

- Main chat screen for direct instructions
- Drawer-based session management
- Floating overlay during execution
- Manual stop support

### GUI automation

- Launch apps
- Tap, long-press, double-tap, swipe, back, and home
- Text input through a built-in IME path
- Multi-step planning on the current screen

### Sessions and memory

- Local multi-session persistence
- Long-term memory tools: `read_memory` / `write_memory`
- Recent history and trace injection to reduce repeated actions

### External channels

- Feishu bot integration
- External messages written into the app inbox
- Read-only inspection of external sessions inside the app

### Observability

- Per-turn traces persisted to `workspace/traces/<date>/`
- Optional diagnostic log export
- Foreground service + overlay feedback during execution

## 📖 Documentation

- [SETUP_EN.md](SETUP_EN.md): installation, Shizuku startup, first-run configuration, troubleshooting
- [docs/architecture-notes.md](docs/architecture-notes.md): architecture notes for modifying the core nanobot layer

## 🙏 Acknowledgements

ClawGUI-APP builds on top of the following open-source projects:

- [**Shizuku**](https://github.com/RikkaApps/Shizuku): high-privilege device control without root
- [**roubao**](https://github.com/Turbo1123/roubao): partial reference for Android / Shizuku integration under the MIT license

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
