# Suggestion Bar Stabilization, Dynamic Slots & Privacy Vault Pill Redesign

Stabilize VianBoard by pruning orphaned background dictionary routines, overhauling the suggestion bar with dynamic adaptive slot widths and strict Canvas boundary clipping, and styling Privacy Vault entries as distinct dynamically-masked pill containers with refined typography.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following user preferences were confirmed during clarification and govern this implementation plan:
> - **Suggestion Bar Boundaries**: Dynamic slot allocation (1 slot gets 100% middle width, 2 slots get 50/50, 3 slots get proportional widths) with strict Canvas clipping (`canvas.clipRect()`) to permanently eliminate text bleeding into adjacent controls.
> - **Background Cleanup**: Remove all orphaned background dictionary routines, memory trim debounce jobs, and C++ dormancy routines from `TextEngineBridge.kt`.
> - **Privacy Vault Presentation**: Privacy Vault entries will be treated as secondary priority, appearing in the suggestion bar inside subtle, stylish rounded pill containers with dynamic masking (`🔒 sch••••@gmail.com`) and elevated typography.

---

### 1. Overview & Core Concept

* **Core Goal**: Eliminate visual stutter, text overflow, and lingering background threads after the removal of the legacy LatinIME C++ binary dictionary engine, ensuring rock-solid typing stability and crisp typography.
* **Target Audience**: Users typing in English, French, and Dual modes who want clean, fast text input with seamless personal dictionary expansion and occasional, secure access to private credentials and addresses.
* **Key Value**:
  * **Zero Text Bleed**: Long words, personal phrases, and email shortcuts never overlap dividers or toolbar buttons.
  * **Lean Runtime**: No background coroutines or memory timers running for removed native engines.
  * **Visual Polish**: Privacy Vault suggestions stand out as secure, interactive pill chips rather than plain raw text.

---

### 2. User Experience & Visual Design

#### Dynamic Suggestion Bar Slots
```
[ ‹ ]  [       Single Suggestion (100% Available Middle Area)       ]  [ ⚙ ] [ 📋 ]
───────────────────────────────────────────────────────────────────────────────────
[ ‹ ]  [ Slot 1 (50% Width) ]  │  [ Slot 2 (50% Width) ]             ]  [ ⚙ ] [ 📋 ]
───────────────────────────────────────────────────────────────────────────────────
[ ‹ ]  [ Left Alt (32%) ] │ [ Center Candidate (36% Bold) ] │ [ Right (32%) ] [ ⚙ ]
```

* **Adaptive Slot Widths**:
  * **1 Candidate** (e.g. typing `omw` -> `"On my way!"` or `myaddr` -> `"🔒 123••••NY"`): The candidate receives 100% of the available middle area. No awkward cramping into a narrow 36% box.
  * **2 Candidates** (e.g. typed literal `"brb"` + match `"Be right back!"`): Split 50% / 50% with a single clean center hairline divider.
  * **3 Candidates**: Standard 32% / 36% / 32% division matching HeliBoard ergonomics.
* **Strict Canvas Clipping**:
  * Prior to drawing any candidate text, the canvas executes:
    ```kotlin
    canvas.save()
    canvas.clipRect(slotBounds)
    // Draw text with TextUtils.ellipsize
    canvas.restore()
    ```
  * Even under extreme font scaling or long words, text is mathematically confined to its slot bounds.

#### Privacy Vault Pill Container Styling
* **Visual Appearance**:
  * Pill container: Drawn with a subtle rounded rectangle (`cornerRadius = 14dp`), light semi-transparent background fill (`ColorType.KEY_BACKGROUND` with 20% alpha or primary accent tint), and 1dp stroke outline.
  * Dynamic Masking:
    * Emails: First 3 characters + `••••` + `@domain.com` (e.g., `sch••••@gmail.com`).
    * Street Addresses / Multi-word: First 3 chars + `••••` + Last 2 chars (e.g., `123••••NY`).
    * Phone numbers: `+1••••4567`.
  * Lock Icon: Renders a clean vector lock badge (`🔒`) inside the pill.
* **Refined Typography**:
  * Crisp, modern font rendering (`sans-serif-medium` for suggestions, 15sp base, with balanced baseline vertical centering).

---

### 3. Key Product Decisions & Trade-Offs

* **Decision 1: Dynamic Slot Geometry vs. Fixed 3-Slot Grid**
  * *Chosen Approach*: Dynamic slot geometry based on active candidate count (`1`, `2`, or `3` slots).
  * *Why*: With the native dictionary detached, personal shortcuts and vault matches frequently return 1 or 2 high-confidence phrases. Constraining a single phrase to 36% width caused severe truncation and visual awkwardness. Giving 1 match the entire middle area creates a spacious, readable experience.
  * *Alternatives Considered*: Fixed 3-slot grid was rejected because it leaves 64% of the bar empty and squishes long personal phrases.

* **Decision 2: Complete Removal of Memory Trim Debounce Jobs**
  * *Chosen Approach*: Strip `frenchTrimDebounceJob`, `isFrenchTrimmedByMemory`, and `FRENCH_TRIM_DEBOUNCE_MS` from `TextEngineBridge.kt`.
  * *Why*: These coroutine jobs were exclusively designed to unload the C++ native `.so` memory pages during OS RAM pressure. Since the C++ engine has been decoupled, these jobs were running useless background timers.
  * *Alternatives Considered*: Retaining the jobs for future use was rejected to adhere to the zero-overhead, leak-free standard.

* **Decision 3: Privacy Vault as Secondary Priority**
  * *Chosen Approach*: Vault entries appear only when an exact shortcut match or deliberate keyword is typed, styled in a distinctive pill to differentiate them from standard typing.
  * *Why*: Users use the vault occasionally, not constantly. It should not crowd everyday alphabet typing.

---

### 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                       VianBoardService                          │
└───────────────┬─────────────────────────────────┬───────────────┘
                │                                 │
                ▼                                 ▼
┌───────────────────────────────┐ ┌───────────────────────────────┐
│       TextEngineBridge        │ │        VianKeyboardView       │
│  - Lightweight Typing Bridge  │ │  - Cached KeyboardGeometry    │
│  - Zero hanging jobs/timers   │ │  - Dynamic Slot Math (1/2/3)  │
│  - Personal Dictionary Lookup │ │  - Strict Canvas clipRect()   │
│  - Privacy Vault Matcher      │ │  - Vault Pill Container Draw  │
└───────────────┬───────────────┘ └───────────────┬───────────────┘
                │                                 │
                ▼                                 ▼
┌───────────────────────────────┐ ┌───────────────────────────────┐
│    PersonalDictionaryStorage  │ │       Suggestion Slots        │
│  - Normal Partition           │ │  [100% Single Match Pill]     │
│  - Privacy Vault Partition    │ │  [50% / 50% Dual Slots]       │
│  - In-Memory Fast Index       │ │  [32% / 36% / 32% Triple]     │
└───────────────────────────────┘ └───────────────────────────────┘
```

#### Files to Synchronize:
1. `app/src/main/java/com/example/ime/keyboard/KeyboardGeometry.kt`:
   * Update suggestion slot computation: compute dynamic bounds for `1`, `2`, and `3` candidate configurations.
2. `app/src/main/java/com/example/ime/keyboard/KeyboardLayout.kt`:
   * Update `syncSuggestionKeys()` to assign the dynamic bounds based on `suggestions.size`.
3. `app/src/main/java/com/example/ime/keyboard/VianKeyboardView.kt`:
   * Enforce `canvas.save()` / `canvas.clipRect(slotBounds)` / `canvas.restore()` around all suggestion label drawings.
   * Render Privacy Vault candidates with rounded pill containers, lock badges, and refined typography.
4. `app/src/main/java/com/example/ime/engine/TextEngineBridge.kt`:
   * Remove orphaned `frenchTrimDebounceJob`, `isFrenchTrimmedByMemory`, and trim timers.
   * Streamline language mode updates to pure state updates.
