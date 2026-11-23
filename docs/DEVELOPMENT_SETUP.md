# GuardianGrid: Development Setup Guide

This guide walks team members through setting up their workstation, configuring the Android SDK and JDK, building the app, running unit tests, and configuring testing tools (e.g. Telegram Bot API).

---

## 1. System Prerequisites

1. **Operating System:** Windows 10/11, macOS (Apple Silicon or Intel), or Linux (Ubuntu 22.04+).
2. **Java Development Kit (JDK):**
   - **Required Version:** JDK 17 (LTS) or JDK 21 (LTS).
   - **Recommended Distribution:** Eclipse Temurin 17 / OpenJDK 17.
   - *Note on Java 25:* If your system has Java 25 installed as system default, ensure Gradle uses JDK 17 via `org.gradle.java.home` in `gradle.properties` or by pointing `JAVA_HOME` to JDK 17.
3. **Android Studio:**
   - Android Studio Ladybug (2024.2+) or Meerkat (2024.3+) is recommended.
   - Components required:
     - Android SDK Platform 35 (or 34)
     - Android SDK Build-Tools 34.0.0 / 35.0.0
     - Android SDK Platform-Tools
     - Android Emulator (API 34/35)

---

## 2. Repository Cloning & Project Import

```bash
# Clone the repository
git clone https://github.com/BALAJIx64/CudaCores.git
cd CudaCores
```

### Opening in Android Studio
1. Launch Android Studio.
2. Select **Open** and choose the root directory: `d:\Projects\CudaCores`.
3. Allow Gradle to perform its initial project sync and index the source trees.
4. Verify that Android Studio detects the module `:app` and lists `core/`, `modules/`, and `tests/`.

---

## 3. Local Configuration (`local.properties`)

Ensure `local.properties` exists in the repository root and contains the correct absolute path to your Android SDK:

```properties
## Windows Example
sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk

## macOS Example
sdk.dir=/Users/<YourUsername>/Library/Android/sdk

## Linux Example
sdk.dir=/home/<YourUsername>/Android/Sdk
```
*(This file is tracked by `.gitignore` so your personal SDK location remains private.)*

---

## 4. Building & Running from Command Line

```bash
# Windows PowerShell
$env:JAVA_HOME = "C:\Users\<YourUsername>\.jdks\temurin-17\jdk-17.0.21.1+2"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest

# Assemble Debug APK
.\gradlew.bat assembleDebug

# macOS / Linux
export JAVA_HOME="/path/to/jdk-17"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 5. Setting Up the Telegram Bot API Demo

Member 3's initial demonstration requires a Telegram Bot Token and Chat ID:
1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`, choose a name (e.g. `GuardianGridAlertBot`) and a username.
3. Copy the HTTP API token provided by BotFather (format: `<BOT_TOKEN>` e.g., `123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11`).
4. Create a dedicated emergency channel or group in Telegram, add your bot as an administrator, and retrieve the channel's Chat ID (e.g. `@your_emergency_channel` or numerical ID `-100xxxxxxxxx`).
5. Configure the token and chat ID via secure local configuration or app settings for Member 3's communication dispatcher. Never commit real tokens to Git.

---

## 6. Sensor Emulation in Android Emulator

When testing on an Android Emulator:
- **Audio Testing:** Open Emulator Extended Controls ($\dots$) $\rightarrow$ **Microphone** $\rightarrow$ enable "Virtual microphone uses host audio input".
- **Motion Testing:** Extended Controls $\rightarrow$ **Virtual Sensors** $\rightarrow$ adjust Accelerometer and Gyroscope sliders to simulate falls and violent shaking.
- **Location Testing:** Extended Controls $\rightarrow$ **Location** $\rightarrow$ send custom latitude/longitude points to verify emergency GPS tracking.
