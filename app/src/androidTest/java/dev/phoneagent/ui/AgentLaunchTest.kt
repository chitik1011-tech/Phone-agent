package dev.phoneagent.ui

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.MainActivity
import dev.phoneagent.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesIntoAgentChatWithModelControl() {
        fun label(id: Int) = compose.activity.getString(id)

        compose.onNodeWithText(label(R.string.agent_chat)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.chat_message)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.choose_model)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.provider_screen)).assertIsDisplayed()
    }

    @Test
    fun russianResourcesIncludeChatModelAndSettings() {
        val activity = compose.activity
        val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val russian = activity.createConfigurationContext(config)

        assertEquals("Чат с агентами", russian.getString(R.string.agent_chat))
        assertEquals("Выбрать модель", russian.getString(R.string.choose_model))
        assertEquals("Настройки", russian.getString(R.string.nav_settings))
    }
}
