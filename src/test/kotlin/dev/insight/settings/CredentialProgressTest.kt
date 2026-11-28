package dev.insight.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CredentialProgressTest : BasePlatformTestCase() {
    fun testCredentialOperationRunsOffEdt() {
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        var operationWasOnEdt = true
        withCredentialProgress("Testing credential operation") {
            operationWasOnEdt = ApplicationManager.getApplication().isDispatchThread
        }
        assertFalse(operationWasOnEdt)
    }
}
