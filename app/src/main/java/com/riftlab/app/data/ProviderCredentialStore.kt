package com.riftlab.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.riftlab.app.RiftLabApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local provider credentials.
 *
 * Keys are encrypted with Android Keystore before being written to SharedPreferences. Plaintext
 * credentials are never committed to the repository and should never be written to logs/status UI.
 */
internal object ProviderCredentialStore {
    private const val PREFS = "riftlab_provider_credentials"
    private const val KEY_TACHIO = "tachio_api_key_v1"
    private const val KEYSTORE_ALIAS = "riftlab_provider_credentials_aes_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val prefs by lazy {
        RiftLabApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val _tachioConfigured = MutableStateFlow(readTachioApiKey() != null)
    val tachioConfigured: StateFlow<Boolean> = _tachioConfigured.asStateFlow()

    fun readTachioApiKey(): String? = decrypt(prefs.getString(KEY_TACHIO, null))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun saveTachioApiKey(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            clearTachioApiKey()
            return
        }
        prefs.edit().putString(KEY_TACHIO, encrypt(normalized)).apply()
        _tachioConfigured.value = true
    }

    fun clearTachioApiKey() {
        prefs.edit().remove(KEY_TACHIO).apply()
        _tachioConfigured.value = false
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            val separator = payload.indexOf(':')
            require(separator > 0 && separator < payload.lastIndex)
            val iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP)
            val encrypted = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
