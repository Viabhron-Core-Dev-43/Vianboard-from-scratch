# Receipts Ledger - Volume 008

This file is a permanent audit trail. Entries are appended sequentially after every action.
Each file in this series is capped at 500 lines. When this file approaches 500 lines, it is closed and the next file in the series is created.

---

### [2026-10-17 18:05:00 UTC] - Receipt Entry 035: Voice Input Engine Parity, Multi-Process Synchronization & Direct Model Import
- **One-line summary**: Synchronized isolated `:voice` process with main IME via `ACTION_RELOAD_MODEL` & `CMD_RELOAD_MODEL`, added `ACTION_VIEW` intent-filter for direct file opening, implemented 1-tap download manager for recommended FUTO models, and relaxed model validation to $\ge 1\,\text{MB}$.
- **Exact files touched**:
  * `app/src/main/AndroidManifest.xml`
  * `app/src/main/java/com/example/ime/voice/VoiceIpcProtocol.kt`
  * `app/src/main/java/com/example/ime/voice/VoiceModelManager.kt`
  * `app/src/main/java/com/example/ime/voice/VoiceInputService.kt`
  * `app/src/main/java/com/example/ime/voice/VoiceInputConnection.kt`
  * `app/src/main/res/layout/activity_voice_input_settings.xml`
  * `app/src/main/java/com/example/ime/settings/VoiceInputSettingsActivity.kt`
  * `app/src/test/java/com/example/ime/voice/VoiceEngineTest.kt`
  * `BLUEPRINT.md`
  * `receipts/RECEIPTS_007.md`
  * `receipts/RECEIPTS_008.md`
- **What was actually done**:
  1. In `AndroidManifest.xml`: Declared `android.permission.INTERNET` for downloading offline models; added `<intent-filter>` for `ACTION_VIEW` (`*/*`, `application/octet-stream`, `.*\.bin`) on `VoiceInputSettingsActivity` allowing external file managers, browsers, and notifications to open downloaded models directly into VianBoard.
  2. In `VoiceIpcProtocol.kt`: Added `CMD_RELOAD_MODEL = 10` for hot-swapping voice models across the process boundary.
  3. In `VoiceModelManager.kt`: Added `installDownloadedFile` for direct installation from HTTP downloads; added `notifyVoiceService` dispatching `ACTION_RELOAD_MODEL` to `:voice` service whenever a model is imported, downloaded, or deleted; lowered header fallback size threshold from 5 MB to 1 MB.
  4. In `VoiceInputService.kt`: Added `ACTION_RELOAD_MODEL` handling in `onStartCommand` and `CMD_RELOAD_MODEL` in IPC message handler; added transparent client error reporting when native library or model is missing in `triggerStreamingInference`.
  5. In `VoiceInputConnection.kt`: Added `reloadModel()` API.
  6. In `activity_voice_input_settings.xml`: Added "Recommended Models" card containing download progress bar and status text.
  7. In `VoiceInputSettingsActivity.kt`: Added `initRecommendedModelsViews()` rendering FUTO Whisper Tiny ACFT (~75MB), FUTO Whisper Base ACFT (~142MB), and Multilingual (~151MB); implemented `startModelDownload` streaming directly from Hugging Face with redirect following and progress reporting; implemented `handleIncomingModelIntent` to automatically import models opened via `ACTION_VIEW`.
  8. In `VoiceEngineTest.kt`: Added unit test suite covering Whisper clean output, n-gram repetition loop collapsing, phantom silence filtering, header magic validation, and size formatting.
  9. Executed silent security scan and purged build-generated keystores and test APK binaries per Mandates 2 and 3.
- **How it was verified**:
  - Local compilation verified via `compile_applet` (BUILD SUCCESSFUL).
  - Unit test suite running via `gradle :app:testDebugUnitTest`.
  - Security scan verified clean: 0 keystores, 0 base64 files, 0 apk binaries.
- **Any deviation from what was requested, and why**: None. Followed exact user command.
- **Known issue or follow-up needed**: Native inference requires building the APK via GitHub Actions to generate `libwhisper.so`. Offline models can now be imported via SAF, downloaded with 1-tap, or opened directly from files.
