package com.example.ime.security.vault.context

import android.content.Context
import android.view.inputmethod.EditorInfo
import com.example.logger.LogKeeper
import com.example.logger.LogLevel
import com.example.ime.security.vault.data.SecurityVaultDao
import com.example.ime.security.vault.data.VaultEntryEntity

/**
 * SecurityVaultContextSniffer evaluates active application package names and input hints
 * to match against the Security Vault database for instant in-keyboard auto-fill suggestions.
 */
object SecurityVaultContextSniffer {
    private const val TAG = "VaultContextSniffer"

    /**
     * Evaluates active EditorInfo package name and hints against the Security Vault database.
     */
    fun findMatchingEntries(context: Context, editorInfo: EditorInfo?): List<VaultEntryEntity> {
        if (editorInfo == null) return emptyList()
        val pkg = editorInfo.packageName ?: return emptyList()

        val results = mutableListOf<VaultEntryEntity>()
        val dao = SecurityVaultDao.getInstance(context)

        // 1. Exact or partial package match
        val pkgMatches = dao.findByPackageOrUrl(pkg)
        results.addAll(pkgMatches)

        // 2. Extract clean app keyword from package name (e.g., com.github.android -> github)
        val keyword = extractKeywordFromPackage(pkg)
        if (keyword.length >= 3) {
            val keywordMatches = dao.findByPackageOrUrl(keyword)
            for (m in keywordMatches) {
                if (results.none { it.entryUuid == m.entryUuid }) {
                    results.add(m)
                }
            }
        }

        // 3. Match hint text if provided (e.g. web domains in browser address bars or fields)
        val hint = editorInfo.hintText?.toString()?.trim()
        if (!hint.isNullOrBlank() && hint.length >= 4) {
            val hintMatches = dao.findByPackageOrUrl(hint)
            for (m in hintMatches) {
                if (results.none { it.entryUuid == m.entryUuid }) {
                    results.add(m)
                }
            }
        }

        if (results.isNotEmpty()) {
            LogKeeper.logEvent(TAG, "Found ${results.size} context match(es) for pkg=$pkg", LogLevel.INFO)
        }
        return results
    }

    private fun extractKeywordFromPackage(pkg: String): String {
        val parts = pkg.split('.').filter { part ->
            part.lowercase() !in setOf("com", "org", "net", "io", "app", "android", "mobile", "client", "release", "beta")
        }
        return parts.firstOrNull { it.length >= 3 } ?: ""
    }
}
