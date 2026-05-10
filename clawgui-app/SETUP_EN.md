# ClawGUI Android Setup Guide

[中文 README](README.md) | [English README](README_EN.md) | [中文安装指南](SETUP.md)

This guide covers the complete onboarding path for ClawGUI Android: environment requirements, APK build and installation, Shizuku startup, first-run configuration, recommended usage flow, and common issues.

If you only want the project overview and architecture, start with [README_EN.md](README_EN.md).

---

## 1. Requirements

### For developers

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 36
- An Android 8.0+ device

### For end users installing the APK

- An Android 8.0+ device
- Network access
- Ability to install [Shizuku](https://github.com/RikkaApps/Shizuku)

## 2. Build the APK

Create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build:

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 3. Install the Required Apps

Users need at least:

1. **ClawGUI**
2. **Shizuku**

Shizuku is required for reliable GUI automation. Without it, the app can still chat, but device actions such as tap, swipe, and type will not work reliably.

## 4. Enable Developer Options

The exact menu path depends on the OEM, but the common flow is:

1. Open system **Settings**
2. Open **About phone**
3. Tap **Build number / Version number** multiple times
4. Confirm the system message that developer mode is enabled
5. Go back and open **Developer options**

At minimum, enable:

- `USB debugging`
- `Developer options`

If the device runs Android 11 or newer, also enable:

- `Wireless debugging`

## Shizuku Startup Methods

Shizuku can be started in three ways:

1. **Root**
2. **Android 11+ via wireless debugging**
3. **Android 10 and below via ADB from a computer**

### Method A: Rooted Device

If the device is already rooted:

1. Open Shizuku
2. Choose the root startup option
3. Start the service
4. Open ClawGUI after Shizuku is active

This is the simplest path.

### Method B: Android 11+ via Wireless Debugging

This is the recommended path for most non-root users.

#### First-time pairing

1. Open **Developer options**
2. Enable `USB debugging`
3. Enable `Wireless debugging`
4. Open Shizuku and choose the wireless debugging startup mode
5. In Shizuku, start the pairing process
6. Return to system `Wireless debugging`
7. Tap `Pair device with pairing code`
8. Enter the code shown by the system into Shizuku

Pairing usually only needs to be done once.

#### Starting after reboot

After the initial pairing, each reboot usually only requires:

1. Open Shizuku
2. Confirm `Wireless debugging` is still enabled
3. Start the service

If startup fails:

1. Disable `Wireless debugging`
2. Re-enable it
3. Return to Shizuku and try again

#### Notes

- On non-root devices, **Shizuku usually needs to be started again after a reboot**
- Some OEM systems aggressively restrict background processes, which can break pairing discovery or cause disconnections
- On MIUI / HyperOS / ColorOS / Flyme class systems, you may also need to allow background activity for Shizuku

### Method C: Android 10 and Below via ADB from a Computer

Android 10 and below do **not** provide the Android 11 system-level wireless debugging flow. In practice, the stable approach is to connect the phone to a computer and run one ADB command to start Shizuku.

#### Prepare ADB on the computer

1. Download Google Android SDK Platform Tools
2. Extract it to any directory
3. Open a terminal in that directory
4. Run:

```bash
adb
```

If you see the help output, ADB is available.

#### Connect the phone

1. Enable `Developer options` on the phone
2. Enable `USB debugging`
3. Connect the phone to the computer with a USB cable
4. On the computer, run:

```bash
adb devices
```

5. Accept the `Allow USB debugging` prompt on the phone
6. Optionally check `Always allow`
7. Run again:

```bash
adb devices
```

If you see:

```text
List of devices attached
XXXX	device
```

the connection is working.

#### Start Shizuku

Run:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Then:

1. Return to the phone
2. Open Shizuku
3. Confirm the service is active
4. Open ClawGUI

#### Notes

- This method usually needs to be repeated after every reboot
- Android 10 and below cannot use the Android 11 wireless debugging pairing flow
- Some OEM builds may require additional debugging-related switches in Developer options

## First-Time ClawGUI Configuration

Once Shizuku is active, finish the app setup.

### 1. Initial authorization

Open ClawGUI and complete:

1. Shizuku authorization
2. Overlay permission
3. Optional `ClawGUI IME` enablement for more reliable text input

### 2. Model and provider configuration

In Settings, configure at least:

- Brain provider
- Brain model
- VLM provider
- VLM model
- API key
- API base URL if your provider requires it

If you are unsure where to start:

- Brain: a normal chat / reasoning model
- VLM: a GUI-oriented vision-language model that the project already supports well

### 3. IME configuration

For more reliable text entry, enable the built-in IME:

1. Open `Settings -> ClawGUI IME`
2. Enable the IME as instructed
3. Prefer setting it as the default IME

If you skip this, some input actions will fall back to a compatibility path with lower reliability.

### 4. First validation

Test with simple commands first:

- `Open WeChat`
- `Open Settings`
- `Go back to home screen`

Verify:

- The model returns normally
- The floating overlay shows the running state
- The agent can open apps and perform basic navigation

## Recommended Onboarding Sequence

1. Install Shizuku and ClawGUI
2. Enable Developer options
3. Start Shizuku using the method for your Android version
4. Open ClawGUI and finish the first-run permissions
5. Configure the API key, providers, and models
6. Validate with a simple GUI instruction
7. Only then move on to more complex multi-step tasks

## Common Issues

### Why does it stop working after reboot?

Because on non-root devices Shizuku is not a permanent system service. After a reboot, it usually has to be started again.

### Can Android 10 and below use the same wireless debugging flow as Android 11?

No. Android 11 introduced the system-level wireless debugging pairing flow. Android 10 and below generally still require a computer with ADB.

### Why does Shizuku keep searching for the pairing service?

Common causes:

1. The OEM system restricts background activity
2. Wireless debugging is in a bad state
3. Developer options or USB debugging was disabled

Try:

1. Allow background activity for Shizuku
2. Disable and re-enable wireless debugging
3. Keep Developer options and USB debugging enabled

### Why can the agent reply but not operate the phone?

Usually one of the following:

- Shizuku is not running
- ClawGUI was not granted Shizuku access
- The VLM is not configured correctly
- The selected model is not suitable for GUI action planning
