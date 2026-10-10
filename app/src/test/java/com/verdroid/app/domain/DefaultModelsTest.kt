package com.verdroid.app.domain

import com.verdroid.app.domain.model.DefaultModels
import org.junit.Assert.*
import org.junit.Test

class DefaultModelsTest {
    @Test fun `new default order`() {
        assertEquals(listOf(
            "kilo:kilo-auto/free",
            "inclusionai/ling-3.0-flash-sante:free",
            "openrouter/free",
            "openai/gpt-oss-120b:free",
            "qwen/qwen3-coder:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "mistralai/mistral-small-3.1-24b-instruct:free",
            "google/gemma-3-27b-it:free",
        ), DefaultModels.list)
        assertNotEquals("openrouter/free", DefaultModels.list.last())
    }

    @Test fun `only an uncustomised legacy list is migrated`() {
        assertTrue(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.joinToString("\n")))
        assertTrue(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.joinToString("\n") { " $it " } + "\n"))
        assertFalse(DefaultModels.isUncustomisedLegacy(null))
        assertFalse(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.reversed().joinToString("\n")))
        assertFalse(DefaultModels.isUncustomisedLegacy((DefaultModels.legacyV1 + "x/y:free").joinToString("\n")))
        assertFalse(DefaultModels.isUncustomisedLegacy(DefaultModels.list.joinToString("\n")))
    }
}
