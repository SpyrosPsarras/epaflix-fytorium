package com.epaflix.fytorium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposeRuleGuardTest {
    // The v1 rules resume screen code off the main thread after withContext, unlike the app.
    @Test fun composeTestsUseOnlyTheV2Rules() {
        val files = listOf("src/test", "src/androidTest").flatMap { root -> File(root).walk().filter { it.extension == "kt" }.toList() }
        assertTrue(files.any { it.name == "ComposeRuleGuardTest.kt" } && files.any { it.name == "LaunchTest.kt" })
        val v1 = files.flatMap { file -> file.readLines().filter { it.trim().startsWith("import androidx.compose.ui.test.junit4.create") }.map { "$file: $it" } }
        assertEquals(emptyList<String>(), v1)
    }
}
