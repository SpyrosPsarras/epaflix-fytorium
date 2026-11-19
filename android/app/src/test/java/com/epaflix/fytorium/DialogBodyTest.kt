package com.epaflix.fytorium

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class DialogBodyTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun datePickerShrinksWhenSwitchingToInputAndGrowsBackToCalendar() {
        compose.setContent { FytoriumTheme {
            val picker = rememberDatePickerState(initialSelectedDateMillis = 1_893_542_400_000L)
            DatePickerDialog(onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("Set date") } }) { DialogBody { DatePicker(picker) } }
        } }
        val calendarHeight = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height
        compose.checkDialogBody("date-picker-calendar")
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        val inputHeight = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height
        compose.checkDialogBody("date-picker-input")
        assertTrue("Input mode must be compact: calendar=$calendarHeight input=$inputHeight", inputHeight < 400f)
        compose.onNodeWithContentDescription("Switch to calendar input mode").performClick()
        assertEquals(calendarHeight, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun shrinkingContentLeavesNoEmptyBand() {
        val height = mutableStateOf(300.dp)
        compose.setContent { FytoriumTheme {
            AlertDialog(onDismissRequest = {}, text = { DialogBody { Box(Modifier.height(height.value).testTag("content")) } },
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } })
        } }
        val before = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { height.value = 60.dp }
        assertEquals("The dialog follows shrinking content", before - 240f,
            compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot.height, 1f)
    }
}
