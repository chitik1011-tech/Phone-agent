package dev.phoneagent.providers

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phoneagent.R
import dev.phoneagent.ui.AppTheme
import dev.phoneagent.ui.PhoneAgentTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentModelPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choosesASavedModelAndSendsItsPreferencesToTheStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val slot = UUID.randomUUID().toString()
        val store = ProviderStore(context, slot)
        try {
            store.save(
                ProviderSettings(preset = ProviderPreset.OPENROUTER, model = "provider/first", apiKey = "fixture"),
                listOf(ProviderModel("provider/saved", "Saved fixture", true, true, 9_216)),
            )
            val library = store.library()
            compose.setContent {
                PhoneAgentTheme(AppTheme.OLED) {
                    Box {
                        AgentModelPicker(store, library, false, {}, {}, {})
                    }
                }
            }

            compose.onNodeWithText("Saved fixture").performClick()
            compose.onNodeWithText(context.getString(R.string.model_reasoning_high)).performClick()
            compose.onNodeWithText(context.getString(R.string.model_use)).performScrollTo().performClick()
            compose.waitUntil(5_000) { store.load().model == "provider/saved" }

            val selected = ProviderStore(context, slot).load()
            assertEquals(ReasoningEffort.HIGH, selected.reasoning)
            assertEquals(9_216, selected.maxOutputTokens)
        } finally {
            store.clear()
        }
    }
}
