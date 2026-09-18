package com.example.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage interface for sensitive credentials such as the YouTube stream key.
 */
interface SecureStreamKeyStorage {
    fun saveStreamKey(key: String)
    fun getStreamKey(): String
    fun clearStreamKey()
    fun hasStreamKey(): Boolean
}

/**
 * Hardware-backed Android KeyStore AES-GCM secure storage implementation.
 *
 * Encrypts the stream key using an AES-256 key securely maintained in the
 * Android KeyStore provider, with unique initialization vectors (IV) per encryption.
 * Encrypted ciphertext is safely stored in a private preferences container.
 *
 * Includes graceful in-process fallback support for local unit test / JVM environments.
 */
class AndroidKeyStoreSecureStorage(
    private val context: Context,
    private val keyAlias: String = KEY_ALIAS_DEFAULT
) : SecureStreamKeyStorage {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SECURE_VAULT_PREFS,
        Context.MODE_PRIVATE
    )

    // In-memory fallback if Android KeyStore provider is absent (e.g. standard JVM test runner)
    private var jvmFallbackKey: String? = null

    private val isKeyStoreSupported: Boolean by lazy {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            ks.load(null)
            true
        } catch (e: Throwable) {
            false
        }
    }

    init {
        if (isKeyStoreSupported) {
            ensureKeyExists()
        }
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Throwable) {
            // Handled gracefully via fallback
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    override fun saveStreamKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearStreamKey()
            return
        }

        if (!isKeyStoreSupported) {
            jvmFallbackKey = trimmed
            // Obfuscate for basic private storage in fallback mode
            val obfuscated = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            prefs.edit()
                .putString(KEY_FALLBACK_DATA, obfuscated)
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .apply()
            return
        }

        try {
            val secretKey = getSecretKey() ?: run {
                ensureKeyExists()
                getSecretKey()
            }

            if (secretKey == null) {
                jvmFallbackKey = trimmed
                return
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(cipherText, Base64.NO_WRAP))
                .remove(KEY_FALLBACK_DATA)
                .apply()
        } catch (_: Throwable) {
            jvmFallbackKey = trimmed
        }
    }

    @Synchronized
    override fun getStreamKey(): String {
        if (!isKeyStoreSupported) {
            if (jvmFallbackKey != null) return jvmFallbackKey!!
            val stored = prefs.getString(KEY_FALLBACK_DATA, null) ?: return ""
            return try {
                val decoded = Base64.decode(stored, Base64.NO_WRAP)
                String(decoded, Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }

        val ivBase64 = prefs.getString(KEY_IV, null)
        val cipherTextBase64 = prefs.getString(KEY_CIPHERTEXT, null)

        if (ivBase64.isNullOrEmpty() || cipherTextBase64.isNullOrEmpty()) {
            return jvmFallbackKey ?: ""
        }

        return try {
            val secretKey = getSecretKey() ?: return jvmFallbackKey ?: ""
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherText = Base64.decode(cipherTextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            jvmFallbackKey ?: ""
        }
    }

    @Synchronized
    override fun clearStreamKey() {
        jvmFallbackKey = null
        prefs.edit()
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_FALLBACK_DATA)
            .apply()
    }

    @Synchronized
    override fun hasStreamKey(): Boolean {
        return getStreamKey().isNotBlank()
    }

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS_DEFAULT = "vjstream_stream_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val SECURE_VAULT_PREFS = "vjstream_secure_vault_prefs"
        private const val KEY_IV = "enc_iv"
        private const val KEY_CIPHERTEXT = "enc_payload"
        private const val KEY_FALLBACK_DATA = "fb_payload"
    }
}
