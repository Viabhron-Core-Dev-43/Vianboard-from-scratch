# HeliBoard Native Brain (NDK) & Suggestion Bar Redesign Plan

## 1. Overview & Objectives

This implementation plan achieves two core goals:
1. **The Authentic HeliBoard Brain**: Compile the native C++ engine (`libjni_latinime.so`) directly via Android NDK inside the Gradle build using `src/main/jni/Android.mk`. This enables the native binary dictionary scoring, spatial touch proximity matrices, and Damerau-Levenshtein edit-distance heuristics that power HeliBoard.
2. **HeliBoard Suggestion Bar UI Match**: Re-architect the 2D Canvas suggestion strip in `VianKeyboardView.kt` to match the user's uploaded screenshot (`Screenshot_20260925-031136.png`):
   - **Slot 1 (Center / Auto-Correct Candidate)**: High-contrast pure black bold text (`16sp`, `Typeface.BOLD`, `sans-serif`) with HeliBoard's three-dot indicator (`…`) drawn cleanly beneath the text.
   - **Slot 0 (Left / Typed Word)** & **Slot 2 (Right / Alternate Candidate)**: Regular weight (`14.5sp`, `Typeface.NORMAL`, `sans-serif`).
   - **Hairline Vertical Dividers**: Subtle vertical separator strokes between suggestion slots.
   - **Toolbar Integration**: Clean alignment with the left expand chevron `[ > ]` and right docked pinned action tools (`[ :::: ]`, `[ 📋 ]`).

---

## 2. Technical Architecture & Phased Work

### Phase 1: NDK Toolchain & Gradle Native Build Configuration
1. **Toolchain Installation**:
   - Install Android NDK (`ndk;25.2.9519653` or `ndk;26.1.10909125`) via `sdkmanager` in the environment so Gradle can execute native C++ compilation.
2. **Gradle Configuration (`app/build.gradle.kts`)**:
   - Configure `externalNativeBuild`:
     ```kotlin
     externalNativeBuild {
         ndkBuild {
             path = file("src/main/jni/Android.mk")
         }
     }
     ```
   - Expand `abiFilters` in `defaultConfig.ndk` to include `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` (supporting both physical devices and Android emulators).
   - Verify that `libjni_latinime.so` is packaged directly into the APK under `lib/<abi>/libjni_latinime.so`.

### Phase 2: Binary Dictionary & Native JNI Linking
1. **Native Loader Verification (`BinaryDictionary.kt`)**:
   - When `BinaryDictionary.loadNativeLibraryIfNeeded()` runs, it detects the APK-bundled `libjni_latinime.so` in `nativeLibraryDir` and loads it via `System.loadLibrary("jni_latinime")`.
   - `sNativeLoaded` transitions to `true`.
2. **Dictionary Facilitator Integration (`DictionaryFacilitatorImpl.kt`)**:
   - `main_en-US.dict` and `main_fr.dict` are extracted to app-private disk and opened via `openNative(...)`.
   - Native unigram, bigram, and trigram queries execute through `Suggest.getSuggestedWords()` with Euclidean touch proximity.
3. **Privacy Vault & Personal Dictionary Passthrough**:
   - `TextEngineBridge.kt` preserves the sub-millisecond in-memory lookup for `PersonalDictionaryStorage` (normal shortcuts and masked privacy pills) and merges them at top priority with native suggestions.

### Phase 3: Suggestion Bar Canvas Redesign (Screenshot Match)
1. **Typography & Paints (`VianKeyboardView.kt`)**:
   - `suggestionCenterPaint`: `Typeface.create("sans-serif", Typeface.BOLD)`, text size `16sp`, color `theme.textColor` (or `#000000`).
   - `suggestionSidePaint`: `Typeface.create("sans-serif", Typeface.NORMAL)`, text size `14.5sp`, color `theme.textColor`.
   - `suggestionDotsPaint`: 3 small circular dots or centered glyph (`…`) positioned 2.5dp below the baseline of the center candidate word.
2. **Dividers & Geometry**:
   - Calculate exact slot boundaries across the available middle toolbar width.
   - Render vertical hairline dividers (`1dp` stroke, `#E2E8F0` / `0x26000000`) between Slot 0 and Slot 1, and between Slot 1 and Slot 2.
3. **Visual Truncation**:
   - Truncate long candidates gracefully with ellipsis (`...`) if the text exceeds the slot width, preserving the visual balance of the 3-slot bar.

---

## 3. Verification & Quality Assurance Plan

1. **Compilation & Packaging**:
   - Run `compile_applet` to verify that `ndk-build` compiles `libjni_latinime.so` and packages it into the APK.
   - Inspect APK contents to confirm `libjni_latinime.so` is present in `lib/arm64-v8a` and `lib/x86_64`.
2. **Automated Unit Testing**:
   - Run `gradle :app:testDebugUnitTest` to guarantee all existing 30+ unit tests continue to pass with zero regressions.
3. **On-Device Manual Testing**:
   - Open keyboard and type `"Al"` $\to$ verify the suggestion bar displays `"Alps"` in the center (bold with `…` below it), `"ALS...NS"` on the left, and alternate suggestions on the right, exactly matching the screenshot.
   - Verify tapping suggestions commits text with automatic spacing.
   - Verify Privacy Vault shortcuts (e.g. `myemail`) continue to surface masked pills with pattern authentication.
