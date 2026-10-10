package com.verdroid.app.data.termux

import org.junit.Assert.*
import org.junit.Test

class TermuxSetupFlowTest {
    @Test fun stepsInOrder() {
        assertEquals(TermuxSetupStep.INSTALL, TermuxSetupFlow.next(false, true, true, true))
        assertEquals(TermuxSetupStep.GRANT, TermuxSetupFlow.next(true, false, null, null))
        assertEquals(TermuxSetupStep.ALLOW_EXTERNAL, TermuxSetupFlow.next(true, true, null, null))
        assertEquals(TermuxSetupStep.ALLOW_EXTERNAL, TermuxSetupFlow.next(true, true, false, true))
        assertEquals(TermuxSetupStep.STORAGE, TermuxSetupFlow.next(true, true, true, false))
        assertEquals(TermuxSetupStep.STORAGE, TermuxSetupFlow.next(true, true, true, null))
        assertEquals(TermuxSetupStep.DONE, TermuxSetupFlow.next(true, true, true, true))
    }

    @Test fun messagesGuideAndRetry() {
        assertTrue(TermuxSetupFlow.message(TermuxSetupStep.INSTALL, false).contains("F-Droid"))
        assertTrue(TermuxSetupFlow.message(TermuxSetupStep.ALLOW_EXTERNAL, false).contains("Paste"))
        assertTrue(TermuxSetupFlow.message(TermuxSetupStep.ALLOW_EXTERNAL, true).startsWith("Termux still doesn't answer"))
        assertTrue(TermuxSetupFlow.message(TermuxSetupStep.STORAGE, true).contains("termux-setup-storage"))
        assertTrue(TermuxSetupFlow.message(TermuxSetupStep.DONE, false).contains("set up"))
    }
}
