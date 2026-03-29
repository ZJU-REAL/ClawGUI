---
name: gui-mobile
description: Control a connected mobile device (Android/HarmonyOS) via natural language using the gui_execute tool.
metadata: {"nanobot":{"emoji":"📱","always":true,"requires":{"bins":["adb"]}}}
---

# GUI Mobile Use

Use the `gui_execute` tool to control a connected Android or HarmonyOS phone through natural language instructions. The tool captures the phone screen, reasons about it with a vision-language model, and performs actions (tap, swipe, type, home, back, etc.) in a loop until the task completes.

## Prerequisites

1. **ADB installed** and on PATH (`adb` for Android, `hdc` for HarmonyOS)
2. **Phone connected** via USB with USB debugging enabled
3. **ADB Keyboard** installed and enabled on the device
   - Download: https://github.com/senzhk/ADBKeyBoard
   - Install: `adb install ADBKeyboard.apk`
   - Enable: Settings → Languages & Input → Virtual Keyboard → ADB Keyboard

## Usage

### Basic

```
gui_execute(task="Open WeChat and send 'I will be late' to Zhang San")
```

### With step limit

```
gui_execute(task="Search for Bluetooth headphones on Taobao and add to cart", max_steps=80)
```

## Examples

| User says | Tool call |
|-----------|-----------|
| 帮我打开微信给张三发消息说我晚点到 | `gui_execute(task="打开微信给张三发一条消息说我晚点到")` |
| Turn on Bluetooth on my phone | `gui_execute(task="Open Settings and turn on Bluetooth")` |
| 帮我看看手机上有什么新通知 | `gui_execute(task="下拉通知栏查看最新通知并汇报内容")` |
| Open YouTube and search for cooking videos | `gui_execute(task="Open YouTube app and search for cooking videos")` |
| 截个屏看看手机现在显示什么 | `gui_execute(task="截取当前屏幕截图并描述屏幕内容")` |
| 帮我在淘宝搜蓝牙耳机加入购物车 | `gui_execute(task="打开淘宝搜索蓝牙耳机，选择第一个商品加入购物车", max_steps=80)` |

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `task` | string | ✅ | — | Natural language description of what to do on the phone |
| `max_steps` | integer | ❌ | 50 | Max number of GUI action steps (1–200) |

## Tips

- **Be specific** in task descriptions — include app names, contact names, exact text to type.
- **Set higher max_steps** for multi-step flows (e.g. navigating through several screens).
- The tool will automatically check that ADB is installed, the device is connected, and ADB Keyboard is available before attempting any action.
- If an error is returned about missing ADB Keyboard, guide the user to install it.

## Configuration

The GUI tool is configured in `~/.nanobot/config.json` under `tools.gui`:

```json
{
  "tools": {
    "gui": {
      "enable": true,
      "deviceType": "adb",
      "deviceId": null,
      "maxSteps": 50,
      "useExternalModel": true,
      "guiBaseUrl": "https://open.bigmodel.cn/api/paas/v4/",
      "guiApiKey": "YOUR_API_KEY",
      "guiModelName": "autoglm-phone",
      "promptTemplateLang": "cn",
      "promptTemplateStyle": "autoglm",
      "traceEnabled": false,
      "traceDir": "gui_trace"
    }
  }
}
```

| Config Key | Description |
|-----------|-------------|
| `enable` | Enable/disable the GUI tool |
| `deviceType` | `adb` (Android) or `hdc` (HarmonyOS) |
| `deviceId` | Specific device serial (auto-detect if null) |
| `maxSteps` | Default max steps per task |
| `useExternalModel` | Use external GUI VLM (`true`) or nanobot model (`false`) |
| `guiBaseUrl` | API base URL for the external GUI model |
| `guiApiKey` | API key for the external GUI model |
| `guiModelName` | Model name (e.g. `autoglm-phone`) |
| `promptTemplateLang` | Prompt language: `cn` or `en` |
| `promptTemplateStyle` | Prompt template: `autoglm` / `uitars` / `qwenvl` / `maiui` / `guiowl` |
| `traceEnabled` | Save execution trace to `traceDir` |
| `traceDir` | Directory for episode folders and screenshots |

## Troubleshooting

| Error | Solution |
|-------|----------|
| ADB not found | Install: `brew install android-platform-tools` (macOS) or `apt install android-tools-adb` (Linux) |
| No devices connected | Connect phone via USB, enable USB debugging in Developer Options |
| ADB Keyboard not installed | Install APK from https://github.com/senzhk/ADBKeyBoard |
| External model API key missing | Set `guiApiKey` in config.json |
| Task timeout / too many steps | Increase `max_steps` or simplify the task description |
