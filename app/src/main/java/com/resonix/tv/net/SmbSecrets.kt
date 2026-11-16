package com.resonix.tv.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.resonix.tv.diag.AppLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts share passwords with an AES-256 key that lives inside the Android Keystore
 * (the key itself can not be read out of the TV, only used). Only the encrypted text is stored.
 * Nothing in here ever logs a password: failures log the exception class only.
 */
object SmbSecrets {
    private const val ALIAS = "resonix_smb_key_v1"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER)
        store.load(null)
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Base64 text (random start value + encrypted bytes), or null when this TV's key store fails. */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        AppLog.log("SMB_SECRET", "encrypt failed: ${e.javaClass.simpleName}")
        null
    }

    /** The original text, or null when it can not be decrypted (key lost, data damaged). */
    fun decrypt(sealedText: String): String? = try {
        val raw = Base64.decode(sealedText, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_BYTES)
        val sealed = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(sealed), Charsets.UTF_8)
    } catch (e: Exception) {
        AppLog.log("SMB_SECRET", "decrypt failed: ${e.javaClass.simpleName}")
        null
    }
}
