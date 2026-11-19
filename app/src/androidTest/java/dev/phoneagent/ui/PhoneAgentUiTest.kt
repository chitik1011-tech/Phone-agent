package dev.phoneagent.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAgentUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingOpensRealDestinationsAndFinishes() {
        val opened = mutableListOf<String>()
        compose.setContent {
            PhoneAgentTheme(AppTheme.OLED) {
                PhoneAgentOnboarding(
                    onFinish = { opened.add("finish") },
                    onOpenProviders = { opened.add("providers") },
                    onOpenTermux = { opened.add("termux") },
                    onOpenProject = { opened.add("project") },
                    onOpenPermissions = { opened.add("permissions") },
                    onOpenAgentPreferences = { opened.add("agents") },
                )
            }
        }

        compose.onNodeWithText("Phone Agent").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open model settings").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open terminal setup").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open permissions").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Open agent settings").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Choose folder").performClick()
        compose.onNodeWithText("Open IDE").performClick()

        compose.runOnIdle {
            assertEquals(listOf("providers", "termux", "permissions", "agents", "project", "finish"), opened)
        }
    }

    @Test
    fun editorFindMovesBetweenRealMatches() {
        compose.setContent {
            PhoneAgentTheme(AppTheme.OLED) {
                CodeEditor(
                    value = "fun run() {\n    println(1)\n    println(2)\n}",
                    onValueChange = {},
                    fileName = "Example.kt",
                    readOnly = true,
                )
            }
        }

        compose.onNodeWithText("Find").performClick()
        compose.onNodeWithText("Find in file").performTextInput("println")
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("Prev").performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
    }

    @Test
    fun shellRendersSelectedWorkspaceDestination() {
        compose.setContent {
            var destination by remember { mutableStateOf(Destination.HOME) }
            PhoneAgentTheme(AppTheme.LIGHT) {
                PhoneAgentShell(destination, onNavigate = { destination = it }) {
                    Text("Active destination is ${it.label}")
                }
            }
        }

        compose.onNodeWithText("Active destination is Home").assertIsDisplayed()
        compose.onNodeWithText("Workspace").performClick()
        compose.onNodeWithText("Active destination is Workspace").assertIsDisplayed()
        compose.onNodeWithText("Terminal").performClick()
        compose.onNodeWithText("Active destination is Terminal").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Active destination is Settings").assertIsDisplayed()
    }
}
