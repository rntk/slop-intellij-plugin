package dev.insight.context.actions

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.ToolWindowManager
import dev.insight.InsightBundle
import dev.insight.chat.ChatPanel
import dev.insight.context.ContextAction
import dev.insight.context.ContextRequest
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

    override fun isAvailable(request: ContextRequest) = true

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
