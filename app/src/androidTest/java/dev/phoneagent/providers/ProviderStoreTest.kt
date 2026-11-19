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
    fun preservesSeveralProvidersAndPerModelPreferencesAcrossReloadAndImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = UUID.randomUUID().toString()
        val store = ProviderStore(context, slot)
        val openRouter = ProviderSettings(preset = ProviderPreset.OPENROUTER, model = "provider/a", apiKey = "router-key")
        val local = ProviderSettings(preset = ProviderPreset.OLLAMA, model = "local-model")
        val catalog = listOf(
            ProviderModel("provider/b", "B", true, true, 12_288),
            ProviderModel("provider/c", "C", null),
        )
        try {
            store.save(openRouter, catalog)
            store.save(local)
            val reopened = ProviderStore(context, slot)
            val first = checkNotNull(reopened.library().connection(ProviderPreset.OPENROUTER))
            assertEquals(listOf("provider/a", "provider/b", "provider/c"), first.models.map(SavedModel::id))
            val b = first.models[1]
            assertEquals(12_288, b.maxOutputTokens)
            reopened.select(openRouter, b.copy(maxOutputTokens = 8_192, reasoning = ReasoningEffort.HIGH))
            reopened.importModels(openRouter, catalog)
            val picked = ProviderStore(context, slot).load()
            assertEquals("router-key", picked.apiKey)
            assertEquals("provider/b", picked.model)
            assertEquals(8_192, picked.maxOutputTokens)
            assertEquals(ReasoningEffort.HIGH, picked.reasoning)
            reopened.remove(ProviderPreset.OPENROUTER)
            assertEquals(local, reopened.load())
        } finally {
            store.clear()
        }
    }

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
