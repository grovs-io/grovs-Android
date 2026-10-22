package io.grovs.example.controls

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.grovs.example.data.KeyValue
import io.grovs.example.ui.controls.ChipInput
import io.grovs.example.ui.controls.KeyValueRows
import io.grovs.example.ui.controls.LocalSheetHost
import io.grovs.example.ui.controls.SheetHost
import io.grovs.example.ui.theme.GrovsTestAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun chipInputAddsOnCommaAndRemovesOnTap() {
        var tags: List<String> = emptyList()
        rule.setContent {
            GrovsTestAppTheme {
                var state by remember { mutableStateOf(emptyList<String>()) }
                ChipInput(state, { state = it; tags = it }, placeholder = "Add tag")
            }
        }
        rule.onNodeWithTag("chipInputField").performTextInput("qa,")
        rule.onNodeWithTag("chipInputField").performTextInput("android,")
        rule.runOnIdle { assertEquals(listOf("qa", "android"), tags) }
        rule.onNodeWithText("qa").performClick()
        rule.runOnIdle { assertEquals(listOf("android"), tags) }
    }

    @Test
    fun keyValueRowsDetectNumbers() {
        rule.setContent {
            GrovsTestAppTheme {
                CompositionLocalProvider(LocalSheetHost provides SheetHost()) {
                    var rows by remember { mutableStateOf(listOf(KeyValue("level", ""))) }
                    KeyValueRows(rows, { rows = it })
                }
            }
        }
        rule.onNodeWithTag("kvValue0").performTextInput("12")
        rule.onNodeWithText("123").assertExists()
    }
}
