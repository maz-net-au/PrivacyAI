package au.net.maz.privacyai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the app's data key (DEK): 32 random bytes that encrypt the database and every
 * media file. The DEK itself is never stored in the clear. It is stored in `vault.json`:
 *
 *  - No PIN:   keystore( DEK )
 *  - With PIN: keystore( AES-GCM( PBKDF2(PIN), DEK ) )
 *
 * "keystore(...)" is an AES key held in the Android Keystore, so a copied file is useless
 * off this device. With a PIN, the PIN is part of the key: the database can't be opened
 * without it. Adding or changing the PIN only re-wraps the DEK; no data is re-encrypted.
 *
 * The PIN only stops accidental access (unlimited retries, by design).
 */
class Vault(private val file: File) {

    @Serializable
    private data class VaultFile(
        val version: Int = 1,
        val hasPin: Boolean,
        /** Base64 PBKDF2 salt; empty without a PIN. */
        val salt: String = "",
        val iterations: Int = 0,
        /** Base64 of iv + ciphertext from the keystore key. */
        val wrapped: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun exists(): Boolean = file.exists()

    fun hasPin(): Boolean = read()?.hasPin ?: false

    /** Create a fresh vault with a new random DEK. Returns the DEK. */
    fun create(pin: String?): ByteArray {
        val dek = ByteArray(32).also { random.nextBytes(it) }
        write(dek, pin)
        return dek
    }

    /** The DEK, or null if the PIN is wrong. [pin] is ignored when no PIN is set. */
    fun unlock(pin: String?): ByteArray? {
        val v = read() ?: return null
        return runCatching {
            val inner = keystoreDecrypt(b64(v.wrapped))
            if (!v.hasPin) inner
            else aesDecrypt(pinKey(pin.orEmpty(), b64(v.salt), v.iterations), inner)
        }.getOrNull()
    }

    /** Re-wrap [dek] under a new PIN (adding a PIN, or changing it). */
    fun setPin(dek: ByteArray, pin: String) = write(dek, pin)

    private fun write(dek: ByteArray, pin: String?) {
        val vf = if (pin.isNullOrEmpty()) {
            VaultFile(hasPin = false, wrapped = b64(keystoreEncrypt(dek)))
        } else {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val inner = aesEncrypt(pinKey(pin, salt, ITERATIONS), dek)
            VaultFile(
                hasPin = true,
                salt = b64(salt),
                iterations = ITERATIONS,
                wrapped = b64(keystoreEncrypt(inner)),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(vf))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun read(): VaultFile? =
        runCatching { json.decodeFromString<VaultFile>(file.readText()) }.getOrNull()

    // ---- primitives ------------------------------------------------------------

    private fun pinKey(pin: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun aesEncrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun aesDecrypt(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun keystoreEncrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return c.iv + c.doFinal(plain)
    }

    private fun keystoreDecrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "privacyai_vault"
        const val ITERATIONS = 150_000
    }
}
