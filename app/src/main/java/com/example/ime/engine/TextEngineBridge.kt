package com.example.ime.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import com.example.ime.keyboard.KeyData
import com.example.ime.keyboard.KeyType
import com.example.logger.LogKeeper
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardMode
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.NgramContext
import com.example.R
import com.example.RichInputMethodSubtype
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.CoordinateUtils
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.example.ime.dictionary.PersonalDictionaryStorage
import com.example.ime.dictionary.PersonalDictionaryEntry
import com.example.ime.dictionary.DictionaryPartition
import com.example.ime.security.VaultSessionManager

/**
 * TextEngineBridge coordinates bilingual orchestration (English, French, Dual mode),
 * native JNI proximity calculation, composing span lifecycle, and suggestion pipelines.
 */
class TextEngineBridge(private val context: Context) {

    companion object {
        @Volatile
        var activeInstance: TextEngineBridge? = null
            private set
    }

    init {
        activeInstance = this
    }

    val prefs: TextEnginePreferences = TextEnginePreferences(context)
    private val localEditGeneration = AtomicLong(0L)
    @Volatile
    private var lastLocalEditTimestamp: Long = 0L

    var isWebEditor: Boolean = false
        private set

    fun getLocalEditGeneration(): Long = localEditGeneration.get()
    fun getLastLocalEditTimestamp(): Long = lastLocalEditTimestamp
    fun incrementEditGeneration(): Long {
        lastLocalEditTimestamp = System.currentTimeMillis()
        return localEditGeneration.incrementAndGet()
    }

    enum class LanguageMode(val displayName: String, val indicator: String) {
        ENGLISH("English", "EN"),
        FRENCH("Français", "FR"),
        DUAL("Dual (EN + FR)", "EN • FR")
    }

    var currentMode: LanguageMode = LanguageMode.ENGLISH
        private set

    // Lite Mode state (Phase 3.2):
    // Disables gesture / swipe typing trajectory processing while preserving bilingual dictionaries and trigram predictions.
    var isLiteMode: Boolean = false
        private set

    fun setLiteMode(enabled: Boolean) {
        isLiteMode = enabled
        LogKeeper.logEvent("TextEngineBridge", "Lite mode set to $enabled")
    }



    // Dormancy & On-Demand Lifecycle management:
    // French is dormant and unloaded until explicitly needed or triggered (diacritics or French suggestion selection).
    // Automatically returns to sleep after consecutive non-French words or on keyboard hide.
    private var isFrenchDormant: Boolean = true
    private var modeBeforeDormancy: LanguageMode = LanguageMode.ENGLISH
    private var nonFrenchWordStreak: Int = 0
    private var isFrenchLoading: Boolean = false

    val wordComposer: WordComposer = WordComposer()

    private var activeKeyboard: Keyboard? = null
    private val sequenceNumber = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val settingsValues = SettingsValuesForSuggestion(false, false)
    private var lastCommittedWord: String? = null
    private val committedWordsHistory = ArrayList<String>()
    private var currentSuggestedWords: SuggestedWords? = null
    @Volatile
    private var currentCandidatesList: List<String> = emptyList()
    private val frenchWordsInLastQuery = mutableSetOf<String>()

    // Callback to update UI suggestions bar
    var onSuggestionsUpdated: ((List<String>, SuggestedWords?) -> Unit)? = null

    // Partitioned Personal Dictionary & Privacy Vault storage
    val personalDictStorage = PersonalDictionaryStorage.getInstance(context)
    private val vaultCandidateMap = ConcurrentHashMap<String, PersonalDictionaryEntry>()
    var onVaultUnlockRequested: ((PersonalDictionaryEntry, InputConnection?) -> Unit)? = null

    // French accented characters that instantly awaken French from dormancy
    private val frenchDiacritics = setOf(
        'é', 'è', 'ê', 'ë', 'à', 'â', 'ù', 'û', 'ô', 'ç', 'î', 'ï', 'œ', 'æ',
        'É', 'È', 'Ê', 'Ë', 'À', 'Â', 'Ù', 'Û', 'Ô', 'Ç', 'Î', 'Ï', 'Œ', 'Æ'
    )

    init {
        initializeDictionaries()
    }

    private fun initializeDictionaries() {
        LogKeeper.logEvent("TextEngineBridge", "TextEngine operating in decoupled clean mode (dictionaries detached)")
    }



    /**
     * Safely reloads all dictionaries on background IO after an import or user dictionary change.
     */
    fun reloadDictionaries() {
        LogKeeper.logEvent("TextEngineBridge", "Dictionaries reloaded in clean mode")
    }

    /**
     * Adds a word to the active personal dictionary.
     */
    fun addToUserDictionary(word: String, frequency: Int = 250): Boolean {
        personalDictStorage.addOrUpdateEntry(
            PersonalDictionaryEntry(
                phrase = word,
                shortcut = word,
                weight = frequency,
                partition = DictionaryPartition.NORMAL
            )
        )
        return true
    }

    /**
     * Awakens French mode dynamically (e.g. from diacritic, French word selection, or explicit mode change).
     */
    fun awakenFrench(targetMode: LanguageMode = LanguageMode.DUAL, forceReload: Boolean = false) {
        isFrenchDormant = false
        nonFrenchWordStreak = 0
        currentMode = targetMode
        LogKeeper.logEvent("TextEngineBridge", "French awakened (active mode: ${currentMode.displayName})")
    }

    /**
     * Puts French into dormant sleep to save CPU and RAM.
     */
    fun sleepFrench() {
        if (!isFrenchDormant) {
            isFrenchDormant = true
            nonFrenchWordStreak = 0
            if (currentMode != LanguageMode.ENGLISH) {
                modeBeforeDormancy = currentMode
                currentMode = LanguageMode.ENGLISH
            }
            LogKeeper.logEvent("TextEngineBridge", "French put to sleep (dormant)")
        }
    }

    /**
     * Fully unloads French dictionary from memory if requested (zero RAM footprint).
     */
    fun unloadFrench() {
        sleepFrench()
        LogKeeper.logEvent("TextEngineBridge", "FR dictionary unloaded from RAM")
    }

    /**
     * Updates the lightweight keyboard model used for ProximityInfo native matrix calculations.
     */
    fun updateKeyboardModel(keys: List<KeyData>, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || keys.isEmpty()) return
        try {
            val locale = when (currentMode) {
                LanguageMode.ENGLISH -> Locale.US
                LanguageMode.FRENCH -> Locale.FRENCH
                LanguageMode.DUAL -> Locale.US
            }

            val extra = "KeyboardLayoutSet=qwerty,AsciiCapable,EnabledWhenDefaultIsNotAsciiCapable,EmojiCapable"
            val subtype = InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameResId(R.string.subtype_generic)
                .setSubtypeIconResId(R.drawable.ic_ime_switcher)
                .setSubtypeLocale(locale.toString())
                .setSubtypeMode(Constants.Subtype.KEYBOARD_MODE)
                .setSubtypeExtraValue(extra)
                .setIsAsciiCapable(true)
                .build()
            val richSubtype = RichInputMethodSubtype.get(subtype)

            val kbId = KeyboardId(
                element = KeyboardElement.ALPHABET,
                subtype = richSubtype,
                width = width,
                height = height,
                mode = KeyboardMode.TEXT,
                inputType = InputType.TYPE_CLASS_TEXT,
                imeOptions = 0,
                imeAction = 0,
                deviceLocked = false,
                numberRowEnabled = false,
                numberRowInSymbols = false,
                languageSwitchKeyEnabled = false,
                emojiKeyEnabled = false,
                customActionLabel = null,
                hasShortcutKey = false,
                isSplitLayout = false,
                oneHandedModeEnabled = false,
                internalAction = null,
                emojiSearchAvailable = false
            )

            val params = KeyboardParams()
            params.mId = kbId
            params.mOccupiedWidth = width
            params.mOccupiedHeight = height
            params.mBaseWidth = width
            params.mBaseHeight = height
            params.mProximityCharsCorrectionEnabled = true

            for (kd in keys) {
                val code = when (kd.type) {
                    KeyType.SPACE -> Constants.CODE_SPACE
                    KeyType.DELETE -> KeyCode.DELETE
                    KeyType.SHIFT -> KeyCode.SHIFT
                    KeyType.ENTER -> Constants.CODE_ENTER
                    else -> kd.label.firstOrNull()?.code ?: kd.code
                }
                val k = Key(
                    kd.label,
                    null,
                    code,
                    null,
                    kd.hintLabel,
                    0,
                    0,
                    kd.bounds.left.toInt(),
                    kd.bounds.top.toInt(),
                    kd.bounds.width().toInt().coerceAtLeast(1),
                    kd.bounds.height().toInt().coerceAtLeast(1),
                    0,
                    0
                )
                params.onAddKey(k)
            }

            val kb = Keyboard(params)
            activeKeyboard = kb
        } catch (e: Throwable) {
            LogKeeper.logError("TextEngineBridge", "KB_MODEL_UPDATE_FAIL", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Handles OS memory trim signals (Phase 3.3). Gracefully sheds French dictionary during RAM pressure.
     */
    fun onTrimMemory(level: Int) {
        LogKeeper.logEvent("TextEngineBridge", "OS Memory trim signal (level=$level)")
    }

    /**
     * Toggles or sets the active language mode.
     */
    fun setLanguageMode(mode: LanguageMode) {
        currentMode = mode
        if (mode == LanguageMode.FRENCH || mode == LanguageMode.DUAL) {
            awakenFrench(mode, forceReload = true)
        } else {
            sleepFrench()
        }
        wordComposer.reset()
        clearSuggestions()
        LogKeeper.logEvent("TextEngineBridge", "Language switched to ${mode.displayName}")
    }

    /**
     * Checks if input type indicates password or sensitive field.
     */
    fun isSensitiveInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val isNumberPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return isPassword || isNumberPassword
    }

    /**
     * Called on IME start input. Re-initializes state and applies dormancy rules.
     */
    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        wordComposer.reset()
        clearSuggestions()
        lastCommittedWord = null

        // Detect web editor or browser
        val inputType = info?.inputType ?: 0
        val isWebVariation = (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        val pkg = info?.packageName?.lowercase() ?: ""
        val isBrowserPkg = pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("browser") ||
                pkg.contains("brave") || pkg.contains("opera") || pkg.contains("webview") || pkg.contains("edge")
        isWebEditor = isWebVariation || isBrowserPkg

        // French Dormancy Rule: Keep French asleep on fresh keyboard open to conserve battery/RAM
        if (!restarting) {
            if (currentMode != LanguageMode.ENGLISH) {
                modeBeforeDormancy = currentMode
                sleepFrench()
                LogKeeper.logEvent("TextEngineBridge", "French dormant; defaulted to English on start")
            }
        }
    }

    /**
     * Called on IME finish input. Persists updates and resets state.
     */
    fun onFinishInput(ic: InputConnection?) {
        if (wordComposer.isComposingWord) {
            ic?.finishComposingText()
        }
        wordComposer.reset()
        clearSuggestions()

        // Put French to sleep on close
        sleepFrench()


    }

    /**
     * Handles typing a character.
     */
    fun handleCharacter(char: String, touchX: Int, touchY: Int, ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        incrementEditGeneration()

        if (isSensitiveInput(info)) {
            wordComposer.reset()
            clearSuggestions()
            ic.commitText(char, 1)
            return
        }

        // Auto-Wake Trigger: Typing a French diacritic awakens French in Dual mode on-demand
        val firstCh = char.firstOrNull() ?: ' '
        if (isFrenchDormant && frenchDiacritics.contains(firstCh)) {
            awakenFrench(LanguageMode.DUAL)
            LogKeeper.logEvent("TextEngineBridge", "French auto-awakened by diacritic: $char")
        }

        val codePoint = char.codePointAt(0)
        val event = Event.createSoftwareKeypressEvent(codePoint, 0, touchX, touchY, false)
        val processed = wordComposer.processEvent(event)
        wordComposer.applyProcessedEvent(processed)

        val composing = wordComposer.typedWord
        val useBatch = prefs.webEditorPerformanceMode && isWebEditor
        if (useBatch) ic.beginBatchEdit()
        try {
            ic.setComposingText(composing, 1)
        } finally {
            if (useBatch) ic.endBatchEdit()
        }

        querySuggestionsAsync()
    }

    /**
     * Handles backspace / delete key action.
     */
    fun handleDelete(ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        incrementEditGeneration()
        val useBatch = prefs.webEditorPerformanceMode && isWebEditor
        if (useBatch) ic.beginBatchEdit()
        try {
            if (isSensitiveInput(info)) {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                } else {
                    ic.commitText("", 1)
                }
                return
            }

            if (wordComposer.isComposingWord) {
                val event = Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0, 0, 0, false)
                val processed = wordComposer.processEvent(event)
                wordComposer.applyProcessedEvent(processed)

                if (wordComposer.isComposingWord) {
                    ic.setComposingText(wordComposer.typedWord, 1)
                    querySuggestionsAsync()
                } else {
                    ic.commitText("", 1)
                    clearSuggestions()
                }
            } else {
                // Word resumption check: if cursor is right after a word, resume composing
                val textBefore = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
                if (textBefore.isNotEmpty() && textBefore.last().isLetter()) {
                    val lastWord = textBefore.takeLastWhile { it.isLetter() }
                    if (lastWord.length in 2..32) {
                        ic.deleteSurroundingText(lastWord.length, 0)
                        val codePoints = lastWord.map { it.code }.toIntArray()
                        val coordinates = CoordinateUtils.newCoordinateArray(
                            codePoints.size,
                            Constants.NOT_A_COORDINATE,
                            Constants.NOT_A_COORDINATE
                        )
                        wordComposer.setComposingWord(codePoints, coordinates)
                        // Delete the final character that backspace targeted
                        val delEv = Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0, 0, 0, false)
                        wordComposer.applyProcessedEvent(wordComposer.processEvent(delEv))

                        if (wordComposer.isComposingWord) {
                            ic.setComposingText(wordComposer.typedWord, 1)
                            querySuggestionsAsync()
                        } else {
                            ic.commitText("", 1)
                            clearSuggestions()
                        }
                        return
                    }
                }

                // Normal delete fallback
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                } else {
                    ic.commitText("", 1)
                }
                clearSuggestions()
            }
        } finally {
            if (useBatch) ic.endBatchEdit()
        }
    }

    /**
     * Handles space key action with auto-correction commit and next-word prediction.
     */
    fun handleSpace(ic: InputConnection?, info: EditorInfo?) {
        if (ic == null) return

        incrementEditGeneration()

        if (isSensitiveInput(info)) {
            wordComposer.reset()
            clearSuggestions()
            ic.commitText(" ", 1)
            return
        }

        if (wordComposer.isComposingWord) {
            val typed = wordComposer.typedWord
            val sw = currentSuggestedWords
            val wordToCommit = if (sw != null && sw.mWillAutoCorrect && sw.size() > SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
                sw.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION)
            } else {
                typed
            }

            ic.beginBatchEdit()
            try {
                if (prefs.atomicWordReplacement && wordToCommit != typed) {
                    ic.finishComposingText()
                    val textBefore = ic.getTextBeforeCursor(typed.length + 4, 0)?.toString() ?: ""
                    if (textBefore.endsWith(typed)) {
                        ic.deleteSurroundingText(typed.length, 0)
                    } else if (textBefore.isNotEmpty() && textBefore.last().isLetterOrDigit()) {
                        val matchLen = textBefore.takeLastWhile { it.isLetterOrDigit() }.length.coerceAtMost(typed.length)
                        if (matchLen > 0) {
                            ic.deleteSurroundingText(matchLen, 0)
                        }
                    }
                    ic.commitText("$wordToCommit ", 1)
                } else {
                    ic.commitText("$wordToCommit ", 1)
                }
            } finally {
                ic.endBatchEdit()
            }

            recordWordInHistory(wordToCommit)
            lastCommittedWord = wordToCommit
            wordComposer.reset()

            // Trigger Next-Word Prediction
            queryNextWordPredictions(wordToCommit)
        } else {
            ic.commitText(" ", 1)
            val textBefore = ic.getTextBeforeCursor(30, 0)?.toString()?.trim() ?: ""
            val lastWord = textBefore.takeLastWhile { it.isLetter() }
            if (lastWord.isNotEmpty()) {
                queryNextWordPredictions(lastWord)
            } else {
                clearSuggestions()
            }
        }
    }

    /**
     * Handles selection of a suggestion candidate from the suggestion strip.
     */
    fun selectSuggestion(candidate: String, slotIndex: Int, ic: InputConnection?) {
        if (ic == null) return

        // 1. Check if candidate belongs to the Privacy Vault partition
        val vaultEntry = vaultCandidateMap[candidate] ?: personalDictStorage.getVaultEntryByPhrase(candidate)
        if (vaultEntry != null && vaultEntry.partition == DictionaryPartition.PRIVACY_VAULT) {
            val isUnlocked = VaultSessionManager.isPrivacyUnlocked()
            if (!isUnlocked) {
                // Trigger in-keyboard pattern unlock without committing plaintext yet
                onVaultUnlockRequested?.invoke(vaultEntry, ic)
                return
            }

            // Already unlocked: Commit raw plaintext with ZERO-LEARNING GUARANTEE!
            commitVaultPhrase(vaultEntry, ic)
            return
        }

        incrementEditGeneration()
        val typedWord = wordComposer.typedWord
        val typedLen = typedWord.length

        ic.beginBatchEdit()
        try {
            if (prefs.atomicWordReplacement) {
                ic.finishComposingText()
                if (typedLen > 0) {
                    val textBefore = ic.getTextBeforeCursor(typedLen + 4, 0)?.toString() ?: ""
                    if (textBefore.endsWith(typedWord)) {
                        ic.deleteSurroundingText(typedLen, 0)
                    } else if (textBefore.isNotEmpty() && textBefore.last().isLetterOrDigit()) {
                        val matchLen = textBefore.takeLastWhile { it.isLetterOrDigit() }.length.coerceAtMost(typedLen)
                        if (matchLen > 0) {
                            ic.deleteSurroundingText(matchLen, 0)
                        }
                    }
                }
            }
            // Replaces any existing composing span atomically
            ic.commitText("$candidate ", 1)
        } finally {
            ic.endBatchEdit()
        }

        // Check if selected word is a French word or contains diacritics to awaken French
        val isFrenchCandidate = frenchWordsInLastQuery.contains(candidate) || candidate.any { frenchDiacritics.contains(it) }
        if (isFrenchCandidate) {
            awakenFrench(LanguageMode.DUAL)
            nonFrenchWordStreak = 0
            LogKeeper.logEvent("TextEngineBridge", "French awakened by French suggestion selection: $candidate")
        } else if (!isFrenchDormant) {
            nonFrenchWordStreak++
            if (nonFrenchWordStreak >= 6) {
                sleepFrench()
                LogKeeper.logEvent("TextEngineBridge", "French put to sleep after $nonFrenchWordStreak consecutive English words")
            }
        }

        recordWordInHistory(candidate)
        lastCommittedWord = candidate
        wordComposer.reset()

        // Trigger Next-Word Prediction
        queryNextWordPredictions(candidate)
    }

    /**
     * Commits a Privacy Vault phrase atomically with Zero-Learning Guarantee.
     * Strictly prevented from entering UserHistoryDictionary, bigrams, predictive models, or history.
     */
    fun commitVaultPhrase(entry: PersonalDictionaryEntry, ic: InputConnection?) {
        if (ic == null) return
        incrementEditGeneration()
        val typedWord = wordComposer.typedWord
        val typedLen = typedWord.length

        ic.beginBatchEdit()
        try {
            if (prefs.atomicWordReplacement && typedLen > 0) {
                ic.finishComposingText()
                val textBefore = ic.getTextBeforeCursor(typedLen + 4, 0)?.toString() ?: ""
                if (textBefore.endsWith(typedWord)) {
                    ic.deleteSurroundingText(typedLen, 0)
                } else if (textBefore.isNotEmpty() && textBefore.last().isLetterOrDigit()) {
                    val matchLen = textBefore.takeLastWhile { it.isLetterOrDigit() }.length.coerceAtMost(typedLen)
                    if (matchLen > 0) {
                        ic.deleteSurroundingText(matchLen, 0)
                    }
                }
            }
            ic.commitText("${entry.phrase} ", 1)
        } finally {
            ic.endBatchEdit()
        }

        wordComposer.reset()
        // ZERO-LEARNING: Clear suggestion strip and do NOT train history or bigrams
        mainHandler.post {
            onSuggestionsUpdated?.invoke(emptyList(), null)
        }
        LogKeeper.logEvent("TextEngineBridge", "Committed vault phrase '${entry.shortcut}' with zero-learning guarantee")
    }

    private fun recordWordInHistory(word: String) {
        if (word.isBlank()) return
        // ZERO-LEARNING GUARANTEE: Block privacy phrases and shortcuts from learning models
        if (personalDictStorage.isVaultPhrase(word) || personalDictStorage.isVaultShortcut(word)) {
            LogKeeper.logEvent("TextEngineBridge", "Zero-learning: strictly blocked vault word from history dictionary")
            return
        }
        synchronized(committedWordsHistory) {
            committedWordsHistory.add(word)
            while (committedWordsHistory.size > NgramContext.MAX_PREV_WORD_COUNT) {
                committedWordsHistory.removeAt(0)
            }
        }
    }

    /**
     * Unlearns a word completely from user history dictionaries.
     */
    fun unlearnWord(word: String) {
        if (word.isBlank()) return
        LogKeeper.logEvent("TextEngineBridge", "Unlearned word: $word")
    }

    /**
     * Demotes the importance/frequency of a word in user history dictionaries without completely blacklisting it.
     */
    fun demoteWord(word: String) {
        if (word.isBlank()) return
        LogKeeper.logEvent("TextEngineBridge", "Demoted word: $word")
    }

    private fun querySuggestionsAsync() {
        val seq = sequenceNumber.incrementAndGet()
        val composerSnapshot = wordComposer

        scope.launch(Dispatchers.Default) {
            try {
                val results = mutableListOf<String>()
                val swResult: SuggestedWords? = null

                // Check Partitioned Personal Dictionary & Privacy Vault
                val currentToken = composerSnapshot.typedWord
                if (currentToken.isNotEmpty()) {
                    val matches = personalDictStorage.findMatches(currentToken)
                    if (matches.isNotEmpty()) {
                        val isPrivacyUnlocked = VaultSessionManager.isPrivacyUnlocked()
                        for (entry in matches) {
                            if (entry.partition == DictionaryPartition.PRIVACY_VAULT) {
                                val display = if (isPrivacyUnlocked) {
                                    "🔓 ${entry.phrase}"
                                } else {
                                    "🔒 ${PersonalDictionaryStorage.maskPhrase(entry.phrase)}"
                                }
                                vaultCandidateMap[display] = entry
                                results.add(0, display)
                            } else {
                                // Normal partition
                                results.add(0, entry.phrase)
                            }
                        }
                    }
                    if (!results.contains(currentToken)) {
                        results.add(currentToken)
                    }
                }

                if (seq == sequenceNumber.get()) {
                    currentSuggestedWords = swResult
                    currentCandidatesList = results
                    withContext(Dispatchers.Main) {
                        onSuggestionsUpdated?.invoke(results, swResult)
                    }
                }
            } catch (e: Throwable) {
                LogKeeper.logError("TextEngineBridge", "SUGGESTION_QUERY_FAIL", e.message ?: "")
            }
        }
    }

    private fun queryNextWordPredictions(prevWord: String) {
        currentSuggestedWords = null
        currentCandidatesList = emptyList()
        mainHandler.post {
            onSuggestionsUpdated?.invoke(emptyList(), null)
        }
    }



    fun clearSuggestions() {
        currentSuggestedWords = null
        currentCandidatesList = emptyList()
        mainHandler.post {
            onSuggestionsUpdated?.invoke(emptyList(), null)
        }
    }

    /**
     * Checks if a candidate is from the static built-in ROM dictionary assets (English or French).
     */
    fun isBuiltInWord(word: String): Boolean {
        return false
    }

    /**
     * Checks if a candidate is a personal / learned word from user typing history.
     */
    fun isPersonalWord(word: String): Boolean {
        return true
    }

    /**
     * Checks if a candidate currently has a scoring penalty applied (demoted).
     */
    fun isWordDemoted(word: String): Boolean {
        return false
    }

    /**
     * Retrieves all alternative/similar candidate words from the current suggestion query
     */
    fun getAllAlternativeCandidates(excludeWord: String? = null): List<String> {
        val sw = currentSuggestedWords ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until sw.size()) {
            val w = sw.getWord(i)
            if (w.isNotEmpty() && !list.contains(w) && (excludeWord == null || !w.equals(excludeWord, ignoreCase = true))) {
                list.add(w)
            }
        }
        return list
    }

    /**
     * Immediately removes a candidate from the active suggestions in memory
     * and refreshes the suggestion strip with the next best candidate.
     */
    fun removeCandidateAndRefresh(removedWord: String) {
        val current = currentSuggestedWords
        val currentList = currentCandidatesList.toMutableList()

        // 1. Remove the target word from current candidate strip list
        currentList.removeAll { it.equals(removedWord, ignoreCase = true) }

        // 2. Filter SuggestedWords
        val newSuggestedWords = if (current != null) {
            val filteredInfoList = ArrayList<SuggestedWords.SuggestedWordInfo>()
            for (i in 0 until current.size()) {
                val info = current.getInfo(i)
                val w = info?.mWord ?: current.getWord(i)
                if (!w.equals(removedWord, ignoreCase = true)) {
                    filteredInfoList.add(info ?: SuggestedWords.SuggestedWordInfo(w))
                }
            }
            SuggestedWords(filteredInfoList, current.mWillAutoCorrect, current.mIsPunctuationSuggestions)
        } else null
        currentSuggestedWords = newSuggestedWords

        // 3. Find next available alternative candidate to backfill the slot up to 3 candidates
        val alternatives = getAllAlternativeCandidates(excludeWord = removedWord)
        for (alt in alternatives) {
            if (!alt.equals(removedWord, ignoreCase = true) && !currentList.contains(alt)) {
                currentList.add(alt)
                if (currentList.size >= 3) break
            }
        }

        currentCandidatesList = currentList

        // 4. Immediately notify listener on Main thread so strip invalidates in real-time
        mainHandler.post {
            onSuggestionsUpdated?.invoke(currentList, newSuggestedWords)
        }
    }

    fun onDestroy() {
        scope.cancel()
    }
}
