package dev.phoneagent.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedRecord(
    context: Context,
    fileName: String,
    private val keyAlias: String,
    private val maxRecordBytes: Int,
) {
    val storageFile = File(context.noBackupFilesDir, fileName)
    private val file = AtomicFile(storageFile)

    fun read(): String? = synchronized(lock) {
        val record = try {
            file.openRead().use { input ->
                val bytes = ByteArray(maxRecordBytes + 1)
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read == -1) break
                    size += read
                }
                check(size in (HEADER_BYTES + TAG_BYTES)..maxRecordBytes) { "Invalid encrypted record size." }
                bytes.copyOf(size)
            }
        } catch (_: FileNotFoundException) {
            if (file.baseFile.exists()) error("Cannot read secure storage.")
            return@synchronized null
        }
        check(record[0] == VERSION) { "Unsupported encrypted record version." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val nonce = record.copyOfRange(1, HEADER_BYTES)
        val key = keyStore().getKey(keyAlias, null) as? SecretKey
            ?: error("The encryption key is unavailable.")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(byteArrayOf(VERSION))
        cipher.doFinal(record, HEADER_BYTES, record.size - HEADER_BYTES).toString(Charsets.UTF_8)
    }

    fun write(text: String): Unit = synchronized(lock) {
        val input = text.toByteArray(Charsets.UTF_8)
        check(input.size + HEADER_BYTES + TAG_BYTES <= maxRecordBytes) { "The encrypted record is too large." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, readOrCreateKey())
        check(cipher.iv.size == NONCE_BYTES) { "Unsupported encryption nonce." }
        cipher.updateAAD(byteArrayOf(VERSION))
        val ciphertext = cipher.doFinal(input)
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(VERSION))
            output.write(cipher.iv)
            output.write(ciphertext)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    fun clear(): Unit = synchronized(lock) {
        file.delete()
        check(!file.baseFile.exists()) { "Cannot clear secure storage." }
        keyStore().deleteEntry(keyAlias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun readOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + NONCE_BYTES
        val lock = Any()
    }
}
