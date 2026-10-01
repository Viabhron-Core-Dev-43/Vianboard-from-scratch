# VianBoard (Vb2)

A fast, lightweight, and privacy-focused Android keyboard (IME) featuring dual English/French text prediction, offline Whisper voice typing, customizable toolbar tools, quick notes, and an encrypted privacy vault.

---

## 🚀 Automated APK Builds (GitHub Actions)

This repository includes a continuous integration pipeline (`.github/workflows/build-apk.yml`) that compiles the native C++ LatinIME and Whisper engines, builds the Android APK, and publishes downloadable installable APKs as GitHub artifacts on every push or manual trigger.

### How to Download the Latest APK from GitHub:
1. Navigate to the **[Actions Tab](https://github.com/Viabhronlewis4373/Vb2/actions)** of this repository.
2. Click on the latest workflow run (e.g., **Build Android APK**).
3. Scroll down to the **Artifacts** section at the bottom of the page.
4. Click **`VianBoard-Debug-APK`** to download the installable `.apk` file directly to your phone.

---

## 🛠️ Key Features & Architecture

* **Text Prediction & Dual Language Engine**:
  * Offline JNI C++ LatinIME suggestion core (`libjni_latinime.so`) supporting English and French.
  * Smart on-demand French dictionary lifecycle (auto-awakens on diacritics/French candidates, auto-sleeps during consecutive English streaks).
  * 3-slot HeliBoard-style suggestion strip with bold auto-correct center slot and candidate demotion/deletion popup.
* **Whisper Voice Input**:
  * Offline on-device speech recognition via native Whisper C++ engine (`libwhisper.so`).
* **Customizable Floating Toolbar**:
  * Expand/collapse chevron with circular background matching HeliBoard design specifications.
  * Pinned quick tools: Voice, Clipboard, Quick Notes, Cards, Desktop Shortcuts, Emoji, and Security Vault.
* **Encrypted Security Vault**:
  * Sandboxed, decoupled storage for sensitive phrases and account credentials, protected by `VianPatternUnlockView` pattern lock authentication.
* **Zero-PII Diagnostics**:
  * Built-in `LogKeeper` with Master On/Off switch, capturing only component error codes and traces without recording user typing or PII.

---

## 🏗️ Building Locally

### Prerequisites
* **Android Studio**: Ladybug / Jellyfish (or newer)
* **JDK**: Version 17
* **Android SDK**: API 36 (target), API 26 (minimum)
* **Android NDK**: Version 25.2.9519653

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Viabhronlewis4373/Vb2.git
cd Vb2

# Build Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew :app:testDebugUnitTest
```
The resulting APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License & Attribution
* LatinIME engine & dictionaries derived from AOSP / HeliBoard.
* Whisper speech recognition engine powered by whisper.cpp.
