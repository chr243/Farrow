package com.verdroid.app.ui.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression (v1.0.19 crash on opening Settings > Tools): init { refresh() } runs check() synchronously on
 * Main.immediate, so every property it touches must be declared above the init block (Kotlin initializes in order).
 */
class ToolsViewModelInitOrderTest {
    @Test fun propertiesUsedByRefreshAreDeclaredBeforeInit() {
        val src = File("src/main/java/com/verdroid/app/ui/tools/ToolsViewModel.kt").readText()
        val init = src.indexOf("    init {")
        assertTrue(init > 0)
        for (p in listOf("private val _state", "private val _events", "private val checkLock", "private var lastActed")) {
            val i = src.indexOf(p)
            assertTrue("$p must be declared before init", i in 0 until init)
        }
    }
}
