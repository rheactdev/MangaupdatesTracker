package com.example.mangaupdatestracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "mangaupdates_tracker_credentials"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

class CredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mangaupdates_credentials", Context.MODE_PRIVATE)

    var username: String
        get() = readEncrypted("username_encrypted").ifBlank {
            prefs.getString("username", "").orEmpty()
        }
        set(value) {
            prefs.edit()
                .putString("username_encrypted", encrypt(value))
                .remove("username")
                .apply()
        }

    var token: String
        get() = readEncrypted("token_encrypted").ifBlank {
            prefs.getString("token", "").orEmpty()
        }
        set(value) {
            prefs.edit()
                .putString("token_encrypted", encrypt(value))
                .remove("token")
                .apply()
        }

    var keepSignedIn: Boolean
        get() = prefs.getBoolean("keep_signed_in", false)
        set(value) {
            prefs.edit().putBoolean("keep_signed_in", value).apply()
        }

    var savedPassword: String
        get() = readEncrypted("password_encrypted")
        set(value) {
            prefs.edit().putString("password_encrypted", encrypt(value)).apply()
        }

    fun saveSession(username: String, token: String, password: String?, keepPassword: Boolean) {
        val editor = prefs.edit()
            .putString("username_encrypted", encrypt(username))
            .putString("token_encrypted", encrypt(token))
            .putBoolean("keep_signed_in", keepPassword)
            .remove("username")
            .remove("token")

        if (keepPassword && !password.isNullOrBlank()) {
            editor.putString("password_encrypted", encrypt(password))
        } else {
            editor.remove("password_encrypted")
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun clearSavedPassword() {
        prefs.edit()
            .remove("password_encrypted")
            .putBoolean("keep_signed_in", false)
            .apply()
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun readEncrypted(key: String): String {
        val encoded = prefs.getString(key, "").orEmpty()
        if (encoded.isBlank()) return ""
        return runCatching {
            val combined = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
            val iv = ByteArray(combined.getInt())
            combined.get(iv)
            val encrypted = ByteArray(combined.remaining())
            combined.get(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }
}
