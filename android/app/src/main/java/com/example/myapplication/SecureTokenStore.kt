package com.example.myapplication

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val TOKEN_KEY_ALIAS = "quantbridge_auth_token_v1"
private const val SECURE_PREFS_NAME = "quantbridge_secure"
private const val LEGACY_PREFS_NAME = "quantbridge"
private const val TOKEN_CIPHERTEXT_KEY = "token_ciphertext"
private const val TOKEN_IV_KEY = "token_iv"
private const val USER_CIPHERTEXT_KEY = "user_ciphertext"
private const val USER_IV_KEY = "user_iv"
private const val LEGACY_TOKEN_KEY = "token"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128

class SecureTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val securePrefs = appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun loadToken(): String? {
        val ciphertext = securePrefs.getString(TOKEN_CIPHERTEXT_KEY, null)
        val iv = securePrefs.getString(TOKEN_IV_KEY, null)

        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return runCatching { decryptString(ciphertext, iv) }
                .getOrElse {
                    clearToken()
                    null
                }
        }

        return migrateLegacyToken()
    }

    fun saveToken(token: String) {
        val encrypted = encryptString(token)
        securePrefs.edit().putEncrypted(TOKEN_CIPHERTEXT_KEY, TOKEN_IV_KEY, encrypted).apply()
        legacyPrefs.edit().remove(LEGACY_TOKEN_KEY).apply()
    }

    fun loadUser(): AuthUser? {
        val ciphertext = securePrefs.getString(USER_CIPHERTEXT_KEY, null)
        val iv = securePrefs.getString(USER_IV_KEY, null)
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return null
        return runCatching {
            val json = org.json.JSONObject(decryptString(ciphertext, iv))
            AuthUser(
                id = json.optString("id"),
                email = json.optString("email"),
                displayName = json.optString("display_name"),
                createdAt = json.optString("created_at")
            )
        }.getOrElse {
            clearUser()
            null
        }
    }

    fun saveUser(user: AuthUser) {
        val raw = org.json.JSONObject()
            .put("id", user.id)
            .put("email", user.email)
            .put("display_name", user.displayName)
            .put("created_at", user.createdAt)
            .toString()
        val encrypted = encryptString(raw)
        securePrefs.edit().putEncrypted(USER_CIPHERTEXT_KEY, USER_IV_KEY, encrypted).apply()
    }

    fun clearToken() {
        securePrefs.edit()
            .remove(TOKEN_CIPHERTEXT_KEY)
            .remove(TOKEN_IV_KEY)
            .apply()
        legacyPrefs.edit().remove(LEGACY_TOKEN_KEY).apply()
    }

    fun clearUser() {
        securePrefs.edit()
            .remove(USER_CIPHERTEXT_KEY)
            .remove(USER_IV_KEY)
            .apply()
    }

    fun clearSession() {
        clearToken()
        clearUser()
    }

    private fun migrateLegacyToken(): String? {
        val legacyToken = legacyPrefs.getString(LEGACY_TOKEN_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            saveToken(legacyToken)
            legacyToken
        }.getOrElse {
            legacyPrefs.edit().remove(LEGACY_TOKEN_KEY).apply()
            null
        }
    }

    private data class EncryptedPayload(val ciphertext: String, val iv: String)

    private fun android.content.SharedPreferences.Editor.putEncrypted(
        ciphertextKey: String,
        ivKey: String,
        payload: EncryptedPayload
    ): android.content.SharedPreferences.Editor {
        return putString(ciphertextKey, payload.ciphertext)
            .putString(ivKey, payload.iv)
    }

    private fun encryptString(value: String): EncryptedPayload {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decryptString(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
        )
        val tokenBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return tokenBytes.toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(TOKEN_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            TOKEN_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
