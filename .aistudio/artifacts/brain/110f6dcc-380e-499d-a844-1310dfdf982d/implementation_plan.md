# Implementation Plan: Lightweight Repository Archive & Delivery

## Objective
The repository currently contains intermediate C++ compilation artifacts (`.o`, `.d`, `.a` files in `app/src/main/jni/obj/`) inflating the project size beyond 100MB. On a mobile connection, this causes timeouts and high data usage. We will create an optimized, clean ZIP (~5–10 MB) containing all source code, assets, dictionaries, JNI sources, and docs, and provide direct, reliable delivery mechanisms.

---

## Proposed Changes

### 1. Repository Cleanliness & Size Optimization
- Exclude all intermediate generated files:
  - `app/src/main/jni/obj/` (intermediate object files: `*.o`, `*.d`, `*.a`)
  - Intermediate build caches (`.gradle`, `build/`)
- Preserve all vital assets:
  - Complete Kotlin & Java source files
  - C++ JNI source code (`.cpp`, `.h`, `Android.mk`, `CMakeLists.txt`)
  - Precompiled shared libraries in `app/src/main/jni/libs/` (`*.so`)
  - Binary dictionaries (`.dict`) in `assets/dicts/`
  - Emoji text databases and resources
  - Blueprints, plans, and receipts logs

### 2. Archive Generation
- Generate a clean archive `vianboard-source.zip` directly at the workspace root using standard zip compression with exclusions.
- Verify archive size to confirm reduction from ~100MB+ down to ~5–12MB.

### 3. Delivery Channels
- **Direct Download Link**: Generate an encrypted, temporary one-time download link or serve directly so you can download it with a single tap on your mobile browser.
- **Direct Git Push (Zero-Data Alternative)**: If you provide your target GitHub repository and a personal access token (or temporary token), we push directly from this server environment. Your phone downloads 0 MB.

---

## Verification Plan
1. Check exact size of `vianboard-source.zip` before distribution.
2. Verify zip integrity by inspecting table of contents (`unzip -l`) ensuring no critical code or binary dictionaries are missing.
3. Record full action in `/receipts/` ledger.
