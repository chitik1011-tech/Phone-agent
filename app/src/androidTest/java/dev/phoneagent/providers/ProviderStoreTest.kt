package dev.phoneagent.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderStoreTest {
    @Test
    fun roundTripUsesAuthenticatedRandomizedEncryptionAndCanDeleteConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = UUID.randomUUID().toString()
        val store = ProviderStore(context, slot)
        val settings = ProviderSettings(model = "provider-test-model", apiKey = "provider-test-key")
        try {
            store.save(settings)
            val saved = store.storageFile.readBytes()
            assertEquals(settings, ProviderStore(context, slot).load())
            assertFalse(saved.toString(Charsets.UTF_8).contains(settings.apiKey))
            assertFalse(saved.toString(Charsets.UTF_8).contains(settings.model))

            store.save(settings)
            val rewritten = store.storageFile.readBytes()
            assertNotEquals(saved.toList(), rewritten.toList())
            assertEquals(saved.first(), rewritten.first())

            rewritten[rewritten.lastIndex] = (rewritten.last().toInt() xor 1).toByte()
            store.storageFile.writeBytes(rewritten)
            assertTrue(runCatching { store.load() }.isFailure)

            store.clear()
            assertFalse(store.storageFile.exists())
            assertEquals(ProviderSettings(), ProviderStore(context, slot).load())
        } finally {
            store.clear()
        }
    }
}
