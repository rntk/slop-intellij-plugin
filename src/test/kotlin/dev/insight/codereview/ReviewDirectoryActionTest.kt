package dev.insight.codereview

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReviewDirectoryActionTest : BasePlatformTestCase() {
    fun `test update restores directory wording and reflects changed exclusions`() {
        val file = myFixture.addFileToProject("scope/Main.java", "class Main {}").virtualFile
        val directory = file.parent
        var selected: VirtualFile? = file
        val action = ReviewDirectoryAction()
        val context = DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> selected
                else -> null
            }
        }
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals("Review Code from This File", event.presentation.text)

        selected = directory
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals("Review Code in Directory", event.presentation.text)
        assertEquals("Open Code Review for the code in this folder", event.presentation.description)

        PsiTestUtil.addExcludedRoot(module, directory)
        try {
            action.update(event)
            assertTrue(event.presentation.isVisible)
            assertFalse(event.presentation.isEnabled)
        } finally {
            PsiTestUtil.removeExcludedRoot(module, directory)
        }
        action.update(event)
        assertTrue(event.presentation.isEnabled)

        selected = null
        action.update(event)
        assertFalse(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
    }
}
