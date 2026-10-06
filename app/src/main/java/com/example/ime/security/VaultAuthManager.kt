package com.example.ime.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import com.example.logger.LogKeeper

/**
 * VaultAuthManager orchestrates authentication routing between:
 * 1. 9-Dot Canvas Pattern with Phone PIN fallback (Default).
 * 2. Biometric Only (Fingerprint/Face unlock via BiometricPrompt/Keyguard).
 */
object VaultAuthManager {

    private const val PREF_AUTH_MODE = "security_vault_auth_mode"
    const val AUTH_MODE_PATTERN_PIN = "pattern_pin"
    const val AUTH_MODE_BIOMETRIC_ONLY = "biometric_only"

    private fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    fun getAuthMode(context: Context): String {
        return getPrefs(context).getString(PREF_AUTH_MODE, AUTH_MODE_PATTERN_PIN) ?: AUTH_MODE_PATTERN_PIN
    }

    fun setAuthMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(PREF_AUTH_MODE, mode).apply()
        LogKeeper.logEvent("VaultAuthManager", "Auth mode updated to: $mode")
    }

    fun isBiometricOnly(context: Context): Boolean {
        return getAuthMode(context) == AUTH_MODE_BIOMETRIC_ONLY
    }

    /**
     * Checks if the device has a secure screen lock set (PIN, pattern, or password).
     */
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /**
     * Creates an Intent to prompt the user for their Android phone lock PIN/pattern.
     */
    fun createPhonePinConfirmIntent(
        context: Context,
        title: CharSequence = "Unlock Security Vault",
        description: CharSequence = "Enter your phone lock PIN or pattern"
    ): Intent? {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
        return km.createConfirmDeviceCredentialIntent(title, description)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
