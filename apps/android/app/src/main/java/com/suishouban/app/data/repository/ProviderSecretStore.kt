package com.suishouban.app.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProviderSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(CIPHERTEXT) && prefs.contains(IV)

    fun saveApiKey(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun apiKeyOrNull(): String? = runCatching {
        val encrypted = prefs.getString(CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }.getOrNull()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "provider_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "suishouban.provider.api-key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT = "api_key_ciphertext"
        const val IV = "api_key_iv"
    }
}
