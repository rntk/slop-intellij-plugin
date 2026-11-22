package dev.insight.context.actions

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.ToolWindowManager
import dev.insight.InsightBundle
import dev.insight.chat.ChatPanel
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.toMarkdown
import java.awt.datatransfer.StringSelection

/** The Insight Chat tool window's id. */
private const val CHAT_TOOL_WINDOW = "Insight Chat"

/** Adds the context as Markdown to the Insight Chat input and focuses it; the user sends it. */
internal class AskInChatAction : ContextAction {
    override val id = "ask.chat"
    override val title get() = InsightBundle.message("context.action.chat")
    override val group = "Ask"
    override val order = 10

    override fun isAvailable(request: ContextRequest) = !request.isReportSelectionText()

    override fun perform(request: ContextRequest) {
        val window = ToolWindowManager.getInstance(request.project).getToolWindow(CHAT_TOOL_WINDOW)
            ?: return report(request.project, InsightBundle.message("context.action.chat.unavailable"))
        val markdown = request.context.toMarkdown()
        window.activate {
            val panel = window.contentManager.contents.firstNotNullOfOrNull { it.component as? ChatPanel }
                ?: return@activate report(request.project, InsightBundle.message("context.action.chat.unavailable"))
            panel.input.text = appendToChatInput(panel.input.text, markdown)
            panel.input.caretPosition = panel.input.document.length
            panel.input.requestFocusInWindow()
        }
    }
}

internal class AskAboutReportSelectionAction : ContextAction {
    override val id = "ask.reportSelection"
    override val title get() = InsightBundle.message("context.action.report.chat")
    override val group = "Ask"

    override fun isAvailable(request: ContextRequest) = request.isReportSelectionText()

    override fun perform(request: ContextRequest) {
        request.data[ContextKeys.REPORT_SELECTION]?.openChat?.invoke(request.context.text)
    }
}

internal class DeleteReportSelectionAction : ContextAction {
    override val id = "edit.deleteReportSelection"
    override val title get() = InsightBundle.message("context.action.report.delete")
    override val group = "Edit"

    override fun isAvailable(request: ContextRequest) =
        request.isReportSelectionText() && request.data[ContextKeys.REPORT_SELECTION]?.delete != null

    override fun perform(request: ContextRequest) {
        request.data[ContextKeys.REPORT_SELECTION]?.delete?.invoke()
    }
}

/** The context as Markdown on the clipboard. */
internal class CopyAsMarkdownAction : ContextAction {
    override val id = "copy.markdown"
    override val title get() = InsightBundle.message("context.action.copy")
    override val group = "Copy"
    override val order = 10

    override fun isAvailable(request: ContextRequest) = true

    override fun perform(request: ContextRequest) {
        CopyPasteManager.getInstance().setContents(StringSelection(request.context.toMarkdown()))
    }
}

/** The selected report text itself, rather than another scope of a report selection picker such as its linked files. */
private fun ContextRequest.isReportSelectionText() =
    ContextKeys.REPORT_SELECTION in data && context.kind == ContextScopeKind.SELECTION
