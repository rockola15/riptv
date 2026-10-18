package com.riptv.tv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra textos sensibles (las URL de las listas llevan usuario y contraseña) con una clave AES guardada en
 * el almacén de claves de Android: la clave no sale del dispositivo, así que lo guardado no se puede leer
 * copiándolo a otro sitio (copias de seguridad, extracción del almacenamiento…).
 */
object SecretBox {
    private const val ALIAS = "riptv_secret_v1"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Devuelve [plain] cifrado; si el dispositivo no puede cifrar, lo devuelve tal cual para no perder datos. */
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) {
        plain
    }

    /** Descifra lo guardado; null si no se puede (otra clave, dato dañado). El texto sin cifrar se devuelve igual. */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            }
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    fun isEncrypted(stored: String) = stored.startsWith(PREFIX)
}
