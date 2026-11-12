package dev.insight.codereview

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.insight.agent.ChatRole
import dev.insight.review.ReviewPipeline

class ScheduledPipelinesPlatformTest : FileEditorManagerTestCase() {
    fun `test opening views generates nothing`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { error("no model") })
        try {
            tab.update(codeSnapshot(codeFile("open/a.kt", "fun a() = 1\n")))
            tab.openSummaries()
            tab.openTopics()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(tab.session.busy)
            assertTrue(tab.session.messages.none { it.role == ChatRole.USER })
        } finally { Disposer.dispose(tab) }
    }

    fun `test scheduled pipelines run one after another and a failed one is not retried`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { error("no model") })
        try {
            tab.update(codeSnapshot(codeFile("scheduled/a.kt", "fun a() = 1\n"), codeFile("scheduled/b.kt", "fun b() = 2\n")))
            tab.schedule(ReviewPipeline.code)
            PlatformTestUtil.waitWithEventsDispatching("The scheduled pipelines should finish",
                { tab.scheduledPipelines.isEmpty() && !tab.session.busy }, 20)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            // Topic summaries need topics, which the failing model never assigned, so that pipeline is skipped.
            assertEquals(listOf("/summaries", "/topics"), tab.session.messages.filter { it.role == ChatRole.USER }.map { it.text })
        } finally { Disposer.dispose(tab) }
    }
}
