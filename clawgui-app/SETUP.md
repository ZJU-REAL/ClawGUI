# SETUP

How to build `clawgui-app` from a fresh Linux machine. Verified on Ubuntu
22.04 (kernel 6.8). Mac/Windows steps are equivalent after step 1.

## 1. JDK 17

The Android Gradle Plugin requires JDK 17.

```bash
mkdir -p ~/sdk_install && cd ~/sdk_install
curl -sSL -o jdk17.tar.gz \
  https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz
mkdir -p ~/jdk-17
tar -xzf jdk17.tar.gz -C ~/jdk-17 --strip-components=1
export JAVA_HOME=$HOME/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
java -version  # → "17.0.13"
```

Persist by appending the two `export` lines to `~/.bashrc` / `~/.zshrc`.

## 2. Android SDK

The build needs `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`.

```bash
cd ~/sdk_install
curl -sSL -o cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools && unzip -q ~/sdk_install/cmdline-tools.zip
mv cmdline-tools latest
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## 3. Gradle distribution (optional — wrapper handles this)

If the wrapper cannot reach `services.gradle.org` from your network, prefetch
the distribution into the wrapper cache:

```bash
mkdir -p ~/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta
cd ~/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta
curl -sSL -o gradle-8.2-bin.zip \
  https://mirrors.cloud.tencent.com/gradle/gradle-8.2-bin.zip
unzip -q gradle-8.2-bin.zip
touch gradle-8.2-bin.zip.ok
```

(Tencent and Aliyun both mirror Gradle distributions, helpful from inside
mainland China.)

## 4. Point the project at the SDK

The repo doesn't check in `local.properties`. Create one:

```bash
echo "sdk.dir=$ANDROID_HOME" > clawgui-app/local.properties
```

## 5. Build

```bash
cd clawgui-app
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

First build downloads ~400MB of Android/Compose/OkHttp artifacts and runs
about **6–8 minutes**. Incremental builds are seconds.

## 6. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on device: grant `SYSTEM_ALERT_WINDOW` (for the dynamic-island overlay),
install Shizuku separately and start its service, and enable the ClawGUI IME
in system input-method settings.

## Common errors

- **`SDK location not found`** — `local.properties` missing or path wrong.
- **`Could not resolve com.larksuite.oapi:oapi-sdk`** — `jitpack.io` blocked.
  The dependency lives on Maven Central; check `~/.gradle/init.d/*.gradle`
  for forced mirrors that override it.
- **Out-of-memory during Kotlin compile** — bump `org.gradle.jvmargs` in
  `gradle.properties` to `-Xmx4096m`.
