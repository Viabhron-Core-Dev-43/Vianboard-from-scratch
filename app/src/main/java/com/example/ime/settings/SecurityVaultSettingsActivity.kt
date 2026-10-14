package com.example.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.ime.dictionary.DictionaryPartition
import com.example.ime.dictionary.PersonalDictionaryEntry
import com.example.ime.dictionary.PersonalDictionaryStorage
import com.example.ime.security.VaultSessionManager
import com.example.ime.security.VaultType
import com.example.ime.security.VianPatternUnlockView
import com.example.ime.security.vault.crypto.VaultCryptoManager
import com.example.ime.security.vault.data.SecurityVaultDao
import com.example.ime.security.vault.data.VaultEntryEntity
import com.example.ime.security.vault.data.VaultGroupEntity
import com.example.ime.security.vault.engine.KdbxRepository
import com.example.ime.security.vault.engine.KdbxSyncManager
import com.example.ime.security.vault.generator.PasswordGenerator
import com.example.ime.security.vault.totp.TotpGenerator
import com.example.ime.security.vault.ui.KdbxEntriesAdapter
import com.example.ime.security.vault.ui.KdbxListItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SecurityVaultSettingsActivity : Activity() {

    companion object {
        private const val REQ_PHONE_PIN = 2001
        private const val REQ_IMPORT_KDBX = 3001
        private const val REQ_EXPORT_KDBX = 3002
        private const val REQ_KEY_FILE = 3003
        private const val PREFS_NAME = "vian_security_vault_prefs"
    }

    private lateinit var storage: PersonalDictionaryStorage
    private lateinit var kdbxDao: SecurityVaultDao

    // Privacy Vault Views
    private lateinit var privacyAdapter: PrivacyVaultAdapter
    private var allVaultEntries = listOf<PersonalDictionaryEntry>()
    private var privacyFilter = ""
    private var isPlaintextRevealed = false

    // KDBX Views
    private lateinit var kdbxAdapter: KdbxEntriesAdapter
    private var allKdbxGroups = listOf<VaultGroupEntity>()
    private var allKdbxEntries = listOf<VaultEntryEntity>()
    private val expandedGroupUuids = mutableSetOf<String>()
    private var kdbxFilter = ""
    private var pendingImportUri: Uri? = null
    private var pendingKeyFileUri: Uri? = null

    // Layout Containers
    private lateinit var layoutVaultContent: LinearLayout
    private lateinit var layoutLockGate: LinearLayout
    private lateinit var containerPatternUnlock: FrameLayout
    private lateinit var layoutPrivacyTab: LinearLayout
    private lateinit var layoutKdbxTab: LinearLayout
    private lateinit var btnTabPrivacy: Button
    private lateinit var btnTabKdbx: Button
    private lateinit var btnToggleReveal: Button

    // KDBX Status Views
    private lateinit var tvKdbxDbTitle: TextView
    private lateinit var tvKdbxDbStats: TextView

    // Live TOTP Tick Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val totpTickRunnable = object : Runnable {
        override fun run() {
            if (layoutKdbxTab.visibility == View.VISIBLE) {
                kdbxAdapter.notifyDataSetChanged()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security_vault_settings)

        storage = PersonalDictionaryStorage.getInstance(this)
        kdbxDao = SecurityVaultDao.getInstance(this)

        initViews()
        initTabs()
        initPrivacyVault()
        initKdbxVault()
        checkAuthentication()
    }

    override fun onResume() {
        super.onResume()
        checkAuthentication()
        mainHandler.post(totpTickRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(totpTickRunnable)
    }

    private fun initViews() {
        layoutVaultContent = findViewById(R.id.layoutVaultContent)
        layoutLockGate = findViewById(R.id.layoutLockGate)
        containerPatternUnlock = findViewById(R.id.containerPatternUnlock)
        layoutPrivacyTab = findViewById(R.id.layoutPrivacyTab)
        layoutKdbxTab = findViewById(R.id.layoutKdbxTab)
        btnTabPrivacy = findViewById(R.id.btnTabPrivacy)
        btnTabKdbx = findViewById(R.id.btnTabKdbx)

        findViewById<Button>(R.id.btnVaultBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGateBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnLockNow).setOnClickListener {
            VaultSessionManager.lockAll()
            showLockGate()
            Toast.makeText(this, "Vault Locked", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGatePhonePin).setOnClickListener {
            launchPhonePinUnlock()
        }

        findViewById<Button>(R.id.btnQuickUnlockDefault).setOnClickListener {
            VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
            VaultSessionManager.unlockSecurity(VaultSessionManager.SECURITY_SESSION_DEFAULT_MS)
            showVaultDashboard()
            Toast.makeText(this, "Vault Unlocked (Default Pattern)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initTabs() {
        btnTabPrivacy.setOnClickListener { switchTab(isPrivacy = true) }
        btnTabKdbx.setOnClickListener { switchTab(isPrivacy = false) }
    }

    private fun switchTab(isPrivacy: Boolean) {
        if (isPrivacy) {
            layoutPrivacyTab.visibility = View.VISIBLE
            layoutKdbxTab.visibility = View.GONE
            btnTabPrivacy.setBackgroundColor(Color.parseColor("#7C3AED"))
            btnTabPrivacy.setTextColor(Color.WHITE)
            btnTabKdbx.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnTabKdbx.setTextColor(Color.parseColor("#475569"))
            loadPrivacyData()
        } else {
            layoutPrivacyTab.visibility = View.GONE
            layoutKdbxTab.visibility = View.VISIBLE
            btnTabKdbx.setBackgroundColor(Color.parseColor("#0284C7"))
            btnTabKdbx.setTextColor(Color.WHITE)
            btnTabPrivacy.setBackgroundColor(Color.parseColor("#E2E8F0"))
            btnTabPrivacy.setTextColor(Color.parseColor("#475569"))
            loadKdbxData()
        }
    }

    private fun initPrivacyVault() {
        findViewById<Button>(R.id.btnAddVaultEntry).setOnClickListener { showAddEditPrivacyDialog(null) }

        val switchAutoRelock = findViewById<Switch>(R.id.switchAutoRelock)
        switchAutoRelock.isChecked = storage.isAutoRelockOnCloseEnabled()
        switchAutoRelock.setOnCheckedChangeListener { _, isChecked ->
            storage.setAutoRelockOnClose(isChecked)
        }

        val switchBiometricOnly = findViewById<Switch>(R.id.switchBiometricOnly)
        switchBiometricOnly.isChecked = com.example.ime.security.VaultAuthManager.isBiometricOnly(this)
        switchBiometricOnly.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) {
                com.example.ime.security.VaultAuthManager.AUTH_MODE_BIOMETRIC_ONLY
            } else {
                com.example.ime.security.VaultAuthManager.AUTH_MODE_PATTERN_PIN
            }
            com.example.ime.security.VaultAuthManager.setAuthMode(this, mode)
        }

        val switchStealth = findViewById<Switch>(R.id.switchStealthPattern)
        switchStealth.isChecked = com.example.ime.security.MasterPatternStore.isStealthPatternEnabled(this)
        switchStealth.setOnCheckedChangeListener { _, isChecked ->
            com.example.ime.security.MasterPatternStore.setStealthPatternEnabled(this, isChecked)
        }

        btnToggleReveal = findViewById(R.id.btnToggleReveal)
        btnToggleReveal.setOnClickListener {
            isPlaintextRevealed = !isPlaintextRevealed
            btnToggleReveal.text = if (isPlaintextRevealed) "🔒 Hide" else "👁️ Reveal"
            privacyAdapter.setPlaintextRevealed(isPlaintextRevealed)
        }

        val rv = findViewById<RecyclerView>(R.id.rvVaultEntries)
        rv.layoutManager = LinearLayoutManager(this)
        privacyAdapter = PrivacyVaultAdapter(
            onEdit = { entry -> showAddEditPrivacyDialog(entry) },
            onDelete = { entry -> confirmDeletePrivacy(entry) },
            onMoveToNormal = { entry -> moveToNormalPrivacy(entry) }
        )
        rv.adapter = privacyAdapter

        val etSearch = findViewById<EditText>(R.id.etVaultSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                privacyFilter = s?.toString()?.trim() ?: ""
                filterPrivacyData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun initKdbxVault() {
        tvKdbxDbTitle = findViewById(R.id.tvKdbxDbTitle)
        tvKdbxDbStats = findViewById(R.id.tvKdbxDbStats)

        findViewById<Button>(R.id.btnKdbxImport).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_KDBX)
        }

        findViewById<Button>(R.id.btnKdbxExport).setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "vianboard_vault.kdbx")
            }
            startActivityForResult(intent, REQ_EXPORT_KDBX)
        }

        findViewById<Button>(R.id.btnKdbxSync).setOnClickListener {
            handleKdbxSync()
        }

        findViewById<Button>(R.id.btnKdbxAddEntry).setOnClickListener {
            showKdbxEntryEditorDialog(null)
        }

        findViewById<Button>(R.id.btnKdbxAddFolder).setOnClickListener {
            showAddFolderDialog()
        }

        findViewById<Button>(R.id.btnKdbxGenerator).setOnClickListener {
            showPasswordGeneratorDialog()
        }

        val rvKdbx = findViewById<RecyclerView>(R.id.rvKdbxEntries)
        rvKdbx.layoutManager = LinearLayoutManager(this)
        kdbxAdapter = KdbxEntriesAdapter(
            onGroupClicked = { group ->
                if (expandedGroupUuids.contains(group.groupUuid)) {
                    expandedGroupUuids.remove(group.groupUuid)
                } else {
                    expandedGroupUuids.add(group.groupUuid)
                }
                filterKdbxData()
            },
            onEntryClicked = { entry -> showKdbxEntryActionsDialog(entry) },
            onEntryMoreClicked = { entry, _ -> showKdbxEntryActionsDialog(entry) }
        )
        rvKdbx.adapter = kdbxAdapter

        val etKdbxSearch = findViewById<EditText>(R.id.etKdbxSearch)
        etKdbxSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                kdbxFilter = s?.toString()?.trim() ?: ""
                filterKdbxData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun checkAuthentication() {
        if (VaultSessionManager.isPrivacyUnlocked() || VaultSessionManager.isSecurityUnlocked()) {
            showVaultDashboard()
        } else {
            showLockGate()
        }
    }

    private fun showLockGate() {
        layoutVaultContent.visibility = View.GONE
        layoutLockGate.visibility = View.VISIBLE

        containerPatternUnlock.removeAllViews()
        val patternView = VianPatternUnlockView(this, VaultType.PRIVACY).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            onUnlockSuccess = {
                VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
                VaultSessionManager.unlockSecurity(VaultSessionManager.SECURITY_SESSION_DEFAULT_MS)
                showVaultDashboard()
                Toast.makeText(this@SecurityVaultSettingsActivity, "Vault Unlocked", Toast.LENGTH_SHORT).show()
            }
            onDismissToAlpha = { finish() }
            onUsePhonePinClicked = { launchPhonePinUnlock() }
        }
        containerPatternUnlock.addView(patternView)
    }

    private fun launchPhonePinUnlock() {
        val intent = com.example.ime.security.VaultAuthManager.createPhonePinConfirmIntent(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_PHONE_PIN)
        } else {
            Toast.makeText(this, "No device screen lock PIN set", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVaultDashboard() {
        layoutLockGate.visibility = View.GONE
        layoutVaultContent.visibility = View.VISIBLE
        containerPatternUnlock.removeAllViews()
        loadPrivacyData()
        loadKdbxData()
    }

    // --- Privacy Vault Data Operations ---

    private fun loadPrivacyData() {
        allVaultEntries = storage.getVaultEntries()
        filterPrivacyData()
    }

    private fun filterPrivacyData() {
        val filtered = if (privacyFilter.isEmpty()) {
            allVaultEntries
        } else {
            allVaultEntries.filter {
                it.phrase.contains(privacyFilter, ignoreCase = true) ||
                it.shortcut.contains(privacyFilter, ignoreCase = true) ||
                (it.category?.contains(privacyFilter, ignoreCase = true) == true)
            }
        }
        privacyAdapter.submitList(filtered)
        val tvEmpty = findViewById<TextView>(R.id.tvVaultEmptyState)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddEditPrivacyDialog(existing: PersonalDictionaryEntry?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_vault_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.etDialogVaultPhrase)
        val etShortcut = dialogView.findViewById<EditText>(R.id.etDialogVaultShortcut)
        val etCategory = dialogView.findViewById<EditText>(R.id.etDialogVaultCategory)
        val tvMaskPreview = dialogView.findViewById<TextView>(R.id.tvDialogMaskPreview)

        etPhrase.setText(existing?.phrase ?: "")
        etShortcut.setText(existing?.shortcut ?: "")
        etCategory.setText(existing?.category ?: "")

        val updatePreview = {
            val text = etPhrase.text.toString().trim()
            val masked = if (text.isNotEmpty()) PersonalDictionaryStorage.maskPhrase(text) else "••••"
            tvMaskPreview.text = "Preview: [ 🔒 $masked ]"
        }
        updatePreview()

        etPhrase.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add Secret Phrase" else "Edit Secret Phrase")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val phrase = etPhrase.text.toString().trim()
                val shortcut = etShortcut.text.toString().trim().lowercase()
                val category = etCategory.text.toString().trim()

                if (phrase.isEmpty()) {
                    Toast.makeText(this, "Phrase cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updated = (existing ?: PersonalDictionaryEntry(
                    phrase = phrase,
                    shortcut = shortcut,
                    category = if (category.isEmpty()) "General" else category,
                    partition = DictionaryPartition.PRIVACY_VAULT
                )).copy(
                    phrase = phrase,
                    shortcut = shortcut,
                    category = if (category.isEmpty()) null else category,
                    partition = DictionaryPartition.PRIVACY_VAULT,
                    updatedAt = System.currentTimeMillis()
                )

                storage.addOrUpdateEntry(updated)
                Toast.makeText(this, "Saved to Privacy Vault", Toast.LENGTH_SHORT).show()
                loadPrivacyData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveToNormalPrivacy(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Move to Normal Dictionary?")
            .setMessage("This word will be unmasked and visible across the system.")
            .setPositiveButton("Move") { _, _ ->
                storage.togglePartition(entry.id)
                loadPrivacyData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePrivacy(entry: PersonalDictionaryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Secret Entry")
            .setMessage("Permanently remove this secret?")
            .setPositiveButton("Delete") { _, _ ->
                storage.deleteEntry(entry.id)
                loadPrivacyData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- KDBX Data Operations ---

    private fun loadKdbxData() {
        allKdbxGroups = kdbxDao.getAllGroups()
        allKdbxEntries = kdbxDao.getAllEntries()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val filename = prefs.getString(KdbxSyncManager.PREF_KDBX_FILENAME, null)
        val lastSync = prefs.getLong(KdbxSyncManager.PREF_LAST_SYNC_TIME, 0L)

        if (filename != null) {
            tvKdbxDbTitle.text = filename
            val syncText = if (lastSync > 0) {
                "Last sync: " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(lastSync))
            } else "Not synced"
            tvKdbxDbStats.text = "${allKdbxEntries.size} entries · ${allKdbxGroups.size} folders · $syncText"
        } else {
            tvKdbxDbTitle.text = "Local KeePass Sandbox"
            tvKdbxDbStats.text = "${allKdbxEntries.size} entries · ${allKdbxGroups.size} folders · Local storage only"
        }

        // Expand root by default
        if (expandedGroupUuids.isEmpty() && allKdbxGroups.isNotEmpty()) {
            expandedGroupUuids.add(allKdbxGroups.first().groupUuid)
        }

        filterKdbxData()
    }

    private fun filterKdbxData() {
        val listItems = mutableListOf<KdbxListItem>()
        val tvEmpty = findViewById<TextView>(R.id.tvKdbxEmptyState)

        if (kdbxFilter.isNotEmpty()) {
            // Flat search results
            val matchedEntries = allKdbxEntries.filter {
                it.title.contains(kdbxFilter, ignoreCase = true) ||
                (it.username?.contains(kdbxFilter, ignoreCase = true) == true) ||
                (it.urlOrPackage?.contains(kdbxFilter, ignoreCase = true) == true)
            }
            for (e in matchedEntries) {
                listItems.add(KdbxListItem.EntryItem(e))
            }
        } else {
            // Group accordion hierarchy
            for (group in allKdbxGroups) {
                val groupEntries = allKdbxEntries.filter { it.groupUuid == group.groupUuid }
                val isExpanded = expandedGroupUuids.contains(group.groupUuid)
                listItems.add(KdbxListItem.GroupItem(group, groupEntries.size, isExpanded))
                if (isExpanded) {
                    for (entry in groupEntries) {
                        listItems.add(KdbxListItem.EntryItem(entry))
                    }
                }
            }
            // Add orphan/direct entries without matching groups
            val groupedUuids = allKdbxGroups.map { it.groupUuid }.toSet()
            val ungrouped = allKdbxEntries.filter { it.groupUuid !in groupedUuids }
            for (u in ungrouped) {
                listItems.add(KdbxListItem.EntryItem(u))
            }
        }

        kdbxAdapter.submitList(listItems)
        tvEmpty.visibility = if (listItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showKdbxAuthDialog(
        prompt: String,
        onConfirmed: (CharArray, Uri?) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_kdbx_auth, null)
        val tvPrompt = dialogView.findViewById<TextView>(R.id.tvAuthPromptMessage)
        val etPass = dialogView.findViewById<EditText>(R.id.etMasterPassword)
        val tvKeyName = dialogView.findViewById<TextView>(R.id.tvKeyFileName)
        val btnSelectKey = dialogView.findViewById<Button>(R.id.btnSelectKeyFile)

        tvPrompt.text = prompt
        pendingKeyFileUri = null

        btnSelectKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_KEY_FILE)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("KeePass Credentials")
            .setView(dialogView)
            .setPositiveButton("Unlock / Proceed") { _, _ ->
                val pass = etPass.text.toString().toCharArray()
                onConfirmed(pass, pendingKeyFileUri)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQ_PHONE_PIN -> {
                VaultSessionManager.unlockPrivacy(VaultSessionManager.PRIVACY_SESSION_DEFAULT_MS)
                VaultSessionManager.unlockSecurity(VaultSessionManager.SECURITY_SESSION_DEFAULT_MS)
                showVaultDashboard()
                Toast.makeText(this, "Vault Unlocked via Device PIN", Toast.LENGTH_SHORT).show()
            }
            REQ_IMPORT_KDBX -> {
                val uri = data?.data ?: return
                pendingImportUri = uri
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.kdbx"
                showKdbxAuthDialog("Enter password to import $filename:") { password, keyFileUri ->
                    Thread {
                        val result = KdbxRepository.importFromUri(this, uri, password, keyFileUri)
                        VaultCryptoManager.zeroize(password)
                        runOnUiThread {
                            if (result.success) {
                                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString(KdbxSyncManager.PREF_KDBX_URI, uri.toString())
                                    .putString(KdbxSyncManager.PREF_KDBX_FILENAME, filename)
                                    .putLong(KdbxSyncManager.PREF_LAST_SYNC_TIME, System.currentTimeMillis())
                                    .apply()
                                Toast.makeText(this, "Imported ${result.entryCount} entries across ${result.groupCount} folders!", Toast.LENGTH_LONG).show()
                                loadKdbxData()
                            } else {
                                Toast.makeText(this, "Import failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }
            REQ_EXPORT_KDBX -> {
                val uri = data?.data ?: return
                showKdbxAuthDialog("Enter master password to protect exported file:") { password, keyFileUri ->
                    Thread {
                        val success = KdbxRepository.exportToUri(this, uri, password, keyFileUri)
                        VaultCryptoManager.zeroize(password)
                        runOnUiThread {
                            if (success) {
                                Toast.makeText(this, "KDBX database exported successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            }
            REQ_KEY_FILE -> {
                val uri = data?.data ?: return
                pendingKeyFileUri = uri
                Toast.makeText(this, "Key file attached: ${uri.lastPathSegment ?: "keyfile"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleKdbxSync() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KdbxSyncManager.PREF_KDBX_URI, null)
        if (uriStr == null) {
            Toast.makeText(this, "No external KDBX file linked. Tap '📥 Import' first.", Toast.LENGTH_LONG).show()
            return
        }

        val uri = Uri.parse(uriStr)
        showKdbxAuthDialog("Enter master password to sync with linked KDBX file:") { password, keyFileUri ->
            Thread {
                val diffResult = KdbxSyncManager.computeDiff(this, uri, password, keyFileUri)
                runOnUiThread {
                    if (diffResult.errorMessage != null) {
                        Toast.makeText(this, "Sync diff error: ${diffResult.errorMessage}", Toast.LENGTH_LONG).show()
                    } else if (!diffResult.hasChanges) {
                        Toast.makeText(this, "Database is already 100% in sync!", Toast.LENGTH_SHORT).show()
                    } else {
                        showKdbxSyncDiffDialog(uri, password, keyFileUri, diffResult)
                    }
                }
            }.start()
        }
    }

    private fun showKdbxSyncDiffDialog(
        uri: Uri,
        password: CharArray,
        keyFileUri: Uri?,
        diffResult: KdbxSyncManager.SyncDiffResult
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_kdbx_sync_diff, null)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvDiffSummary)
        val tvContent = dialogView.findViewById<TextView>(R.id.tvDiffContent)

        tvSummary.text = "Visual Diff: +${diffResult.addedCount} Added, ~${diffResult.modifiedCount} Modified, -${diffResult.deletedCount} Deleted"

        val sb = java.lang.StringBuilder()
        for (diff in diffResult.diffs) {
            val prefix = when (diff.type) {
                KdbxSyncManager.DiffType.ADDED -> "[+] ADDED: "
                KdbxSyncManager.DiffType.MODIFIED -> "[~] MODIFIED: "
                KdbxSyncManager.DiffType.DELETED -> "[-] DELETED: "
                KdbxSyncManager.DiffType.CONFLICT -> "[!] CONFLICT: "
            }
            sb.append("$prefix${diff.title}\n")
            for (f in diff.fieldDiffs) {
                sb.append("    ${f.fieldName}: ${f.oldValue ?: "-"} ➔ ${f.newValue ?: "-"}\n")
            }
            sb.append("\n")
        }
        tvContent.text = sb.toString()

        AlertDialog.Builder(this)
            .setTitle("Confirm 2-Way Sync")
            .setView(dialogView)
            .setPositiveButton("Commit Sync") { _, _ ->
                Thread {
                    val success = KdbxSyncManager.executeSync(this, uri, password, keyFileUri)
                    VaultCryptoManager.zeroize(password)
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "2-Way sync committed successfully!", Toast.LENGTH_SHORT).show()
                            loadKdbxData()
                        } else {
                            Toast.makeText(this, "Sync failed during commit", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel") { _, _ ->
                VaultCryptoManager.zeroize(password)
            }
            .show()
    }

    private fun showAddFolderDialog() {
        val input = EditText(this).apply {
            hint = "Folder Name (e.g. Work, Finance, Social)"
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Create New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val group = VaultGroupEntity(
                        groupUuid = UUID.randomUUID().toString(),
                        name = name
                    )
                    kdbxDao.insertOrUpdateGroup(group)
                    Toast.makeText(this, "Folder '$name' created", Toast.LENGTH_SHORT).show()
                    loadKdbxData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKdbxEntryEditorDialog(existing: VaultEntryEntity?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_kdbx_entry_editor, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etKdbxTitle)
        val spinnerGroup = dialogView.findViewById<Spinner>(R.id.spinnerKdbxGroup)
        val etUsername = dialogView.findViewById<EditText>(R.id.etKdbxUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etKdbxPassword)
        val btnTogglePass = dialogView.findViewById<Button>(R.id.btnToggleKdbxPassword)
        val btnGenPass = dialogView.findViewById<Button>(R.id.btnGenerateKdbxPassword)
        val etUrl = dialogView.findViewById<EditText>(R.id.etKdbxUrl)
        val etTotp = dialogView.findViewById<EditText>(R.id.etKdbxTotp)
        val tvTotpPreview = dialogView.findViewById<TextView>(R.id.tvKdbxTotpPreview)
        val etNotes = dialogView.findViewById<EditText>(R.id.etKdbxNotes)
        val etCustom = dialogView.findViewById<EditText>(R.id.etKdbxCustomFields)

        // Populate groups into spinner
        val groups = if (allKdbxGroups.isEmpty()) {
            val defGroup = VaultGroupEntity(UUID.randomUUID().toString(), name = "General")
            kdbxDao.insertOrUpdateGroup(defGroup)
            listOf(defGroup)
        } else allKdbxGroups

        val groupNames = groups.map { it.name }
        spinnerGroup.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupNames)

        if (existing != null) {
            etTitle.setText(existing.title)
            val gIndex = groups.indexOfFirst { it.groupUuid == existing.groupUuid }
            if (gIndex >= 0) spinnerGroup.setSelection(gIndex)
            etUsername.setText(existing.username ?: "")
            etPassword.setText(VaultCryptoManager.decryptToString(existing.passwordEncrypted) ?: "")
            etUrl.setText(existing.urlOrPackage ?: "")
            etTotp.setText(VaultCryptoManager.decryptToString(existing.totpSecretEncrypted) ?: "")
            etNotes.setText(VaultCryptoManager.decryptToString(existing.notesEncrypted) ?: "")
            etCustom.setText(existing.customFieldsJson ?: "")
        }

        var isPassShown = false
        btnTogglePass.setOnClickListener {
            isPassShown = !isPassShown
            etPassword.inputType = if (isPassShown) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etPassword.setSelection(etPassword.text.length)
        }

        btnGenPass.setOnClickListener {
            val pass = PasswordGenerator.generate(PasswordGenerator.GeneratorConfig(length = 16))
            etPassword.setText(pass)
            Toast.makeText(this, "Generated high-entropy password", Toast.LENGTH_SHORT).show()
        }

        val updateTotpPreview = {
            val raw = etTotp.text.toString().trim()
            if (raw.isNotEmpty()) {
                val config = TotpGenerator.parseConfig(raw)
                if (config != null) {
                    val res = TotpGenerator.generate(config)
                    tvTotpPreview.text = "Live TOTP Preview: ${res?.code ?: "------"} (${res?.remainingSeconds ?: 0}s left)"
                } else {
                    tvTotpPreview.text = "⚠️ Invalid Base32 seed or otpauth URI"
                }
            } else {
                tvTotpPreview.text = "No TOTP configured"
            }
        }
        updateTotpPreview()

        etTotp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { updateTotpPreview() }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New KeePass Entry" else "Edit KeePass Entry")
            .setView(dialogView)
            .setPositiveButton("Save Entry") { _, _ ->
                val title = etTitle.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val selectedGroup = groups[spinnerGroup.selectedItemPosition.coerceIn(0, groups.size - 1)]
                val passStr = etPassword.text.toString()
                val notesStr = etNotes.text.toString()
                val totpStr = etTotp.text.toString().trim()

                val entry = VaultEntryEntity(
                    entryUuid = existing?.entryUuid ?: UUID.randomUUID().toString(),
                    groupUuid = selectedGroup.groupUuid,
                    title = title,
                    username = etUsername.text.toString().trim().ifEmpty { null },
                    passwordEncrypted = if (passStr.isNotEmpty()) VaultCryptoManager.encryptString(passStr) else null,
                    urlOrPackage = etUrl.text.toString().trim().ifEmpty { null },
                    totpSecretEncrypted = if (totpStr.isNotEmpty()) VaultCryptoManager.encryptString(totpStr) else null,
                    notesEncrypted = if (notesStr.isNotEmpty()) VaultCryptoManager.encryptString(notesStr) else null,
                    customFieldsJson = etCustom.text.toString().trim().ifEmpty { null },
                    updatedAt = System.currentTimeMillis()
                )

                kdbxDao.insertOrUpdateEntry(entry)
                Toast.makeText(this, "KDBX entry '$title' saved securely!", Toast.LENGTH_SHORT).show()
                loadKdbxData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKdbxEntryActionsDialog(entry: VaultEntryEntity) {
        val options = mutableListOf<String>()
        options.add("📋 Copy Username")
        options.add("🔑 Copy Password")
        if (entry.totpSecretEncrypted != null && entry.totpSecretEncrypted.isNotEmpty()) {
            options.add("⏱️ View Live TOTP Token")
        }
        options.add("✏️ Edit Entry")
        options.add("🗑️ Delete Entry")

        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "📋 Copy Username" -> {
                        copyToClipboard("Username", entry.username ?: "")
                        Toast.makeText(this, "Username copied", Toast.LENGTH_SHORT).show()
                    }
                    "🔑 Copy Password" -> {
                        val pass = VaultCryptoManager.decryptToString(entry.passwordEncrypted) ?: ""
                        copyToClipboard("Password", pass)
                        Toast.makeText(this, "Password copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    "⏱️ View Live TOTP Token" -> {
                        val seed = VaultCryptoManager.decryptToString(entry.totpSecretEncrypted) ?: ""
                        val res = TotpGenerator.generateFromSecret(seed)
                        if (res != null) {
                            AlertDialog.Builder(this)
                                .setTitle("TOTP Code")
                                .setMessage("Current 6-Digit Code:\n\n${res.code}\n\nExpires in ${res.remainingSeconds}s")
                                .setPositiveButton("Copy Code") { _, _ ->
                                    copyToClipboard("TOTP", res.code)
                                    Toast.makeText(this, "TOTP copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                                .show()
                        } else {
                            Toast.makeText(this, "Invalid TOTP seed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "✏️ Edit Entry" -> showKdbxEntryEditorDialog(entry)
                    "🗑️ Delete Entry" -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete '${entry.title}'?")
                            .setMessage("Permanently remove this entry from KDBX?")
                            .setPositiveButton("Delete") { _, _ ->
                                kdbxDao.deleteEntry(entry.entryUuid)
                                loadKdbxData()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPasswordGeneratorDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_password_generator, null)
        val tvResult = dialogView.findViewById<TextView>(R.id.tvGeneratedPassword)
        val tvEntropy = dialogView.findViewById<TextView>(R.id.tvEntropyRating)
        val btnRegen = dialogView.findViewById<Button>(R.id.btnRegenerate)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopyGenerated)
        val switchPassphrase = dialogView.findViewById<Switch>(R.id.switchPassphraseMode)
        val layoutOptions = dialogView.findViewById<LinearLayout>(R.id.layoutPasswordOptions)
        val tvLength = dialogView.findViewById<TextView>(R.id.tvLengthLabel)
        val sbLength = dialogView.findViewById<SeekBar>(R.id.seekBarLength)
        val cbUpper = dialogView.findViewById<CheckBox>(R.id.cbUppercase)
        val cbLower = dialogView.findViewById<CheckBox>(R.id.cbLowercase)
        val cbDigits = dialogView.findViewById<CheckBox>(R.id.cbDigits)
        val cbSymbols = dialogView.findViewById<CheckBox>(R.id.cbSymbols)

        var currentConfig = PasswordGenerator.GeneratorConfig(length = 16)

        val refreshGen = {
            val pass = PasswordGenerator.generate(currentConfig)
            tvResult.text = pass
            val strength = PasswordGenerator.estimateStrength(pass)
            tvEntropy.text = "Strength: ${strength.rating.label} (~${strength.entropyBits.toInt()} bits entropy)"
            tvEntropy.setTextColor(when (strength.rating) {
                PasswordGenerator.StrengthAssessment.Rating.WEAK -> Color.parseColor("#EF4444")
                PasswordGenerator.StrengthAssessment.Rating.FAIR -> Color.parseColor("#F59E0B")
                PasswordGenerator.StrengthAssessment.Rating.STRONG -> Color.parseColor("#10B981")
                PasswordGenerator.StrengthAssessment.Rating.UNBREAKABLE -> Color.parseColor("#0284C7")
            })
        }

        refreshGen()

        btnRegen.setOnClickListener { refreshGen() }
        btnCopy.setOnClickListener {
            copyToClipboard("Password", tvResult.text.toString())
            Toast.makeText(this, "Password copied!", Toast.LENGTH_SHORT).show()
        }

        switchPassphrase.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(isPassphraseMode = isChecked)
            layoutOptions.visibility = if (isChecked) View.GONE else View.VISIBLE
            refreshGen()
        }

        sbLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val len = progress + 8
                tvLength.text = "Length: $len characters"
                currentConfig = currentConfig.copy(length = len)
                refreshGen()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbUpper.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(includeUppercase = isChecked)
            refreshGen()
        }
        cbLower.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(includeLowercase = isChecked)
            refreshGen()
        }
        cbDigits.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(includeDigits = isChecked)
            refreshGen()
        }
        cbSymbols.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(includeSymbols = isChecked)
            refreshGen()
        }

        AlertDialog.Builder(this)
            .setTitle("Cryptographic Key Generator")
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    // --- Privacy Vault Adapter ---

    private class PrivacyVaultAdapter(
        private val onEdit: (PersonalDictionaryEntry) -> Unit,
        private val onDelete: (PersonalDictionaryEntry) -> Unit,
        private val onMoveToNormal: (PersonalDictionaryEntry) -> Unit
    ) : RecyclerView.Adapter<PrivacyVaultAdapter.ViewHolder>() {

        private var items = listOf<PersonalDictionaryEntry>()
        private var revealPlaintext = false

        fun submitList(newItems: List<PersonalDictionaryEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setPlaintextRevealed(revealed: Boolean) {
            revealPlaintext = revealed
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_privacy_vault_entry, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvShortcut.text = if (item.shortcut.isNotEmpty()) item.shortcut else "no shortcut"
            holder.tvCategory.text = item.category ?: "Confidential"

            if (revealPlaintext) {
                holder.tvPhrase.text = item.phrase
            } else {
                holder.tvPhrase.text = "[ 🔒 ${PersonalDictionaryStorage.maskPhrase(item.phrase)} ]"
            }

            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
            holder.btnMoveToNormal.setOnClickListener { onMoveToNormal(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvShortcut: TextView = view.findViewById(R.id.tvVaultShortcut)
            val tvCategory: TextView = view.findViewById(R.id.tvVaultCategory)
            val tvPhrase: TextView = view.findViewById(R.id.tvVaultPhrase)
            val btnEdit: Button = view.findViewById(R.id.btnVaultEdit)
            val btnDelete: Button = view.findViewById(R.id.btnVaultDelete)
            val btnMoveToNormal: Button = view.findViewById(R.id.btnMoveToNormal)
        }
    }
}
