package dev.insight.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.insight.agent.ChatProfile
import dev.insight.agent.projectFileTools
import dev.insight.settings.ChatSettings
import dev.insight.settings.configuredChatBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal fun openReportSelectionChat(project: Project, excerpt: String, parent: Disposable) {
    val scope = CoroutineScope(SupervisorJob())
    val session = ChatSession(project, scope, backendFactory = {
        val client = ChatSettings.getInstance().createClient()
        LlmChatBackend(
            client::complete,
            projectFileTools(project),
            """
                Answer questions about the selected report text. Treat it as evidence, and verify its claims against current
                project files when needed. Use list_files to locate project files and read_file to inspect their contents.
                Distinguish what the report says from what you verify in the project. Cite paths and line numbers when available.
            """.trimIndent(),
            configuredChatBudget(ChatProfile.ASSISTANT),
            context = { excerpt },
            contextLabel = "Selected report text",
        )
    }, welcome = "Ask a question about the selected report text.")
    val panel = ChatPanel(project, session, title = "Report Chat", showDashboard = false, compactTools = true).apply {
        preferredSize = JBUI.size(640, 460)
    }
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.input)
        .setTitle("Report Selection")
        .setRequestFocus(true)
        .setFocusable(true)
        .setMovable(true)
        .setResizable(true)
        .setCancelOnClickOutside(false)
        .setCancelOnOtherWindowOpen(false)
        .setCancelOnWindowDeactivation(false)
        .setCancelButton(IconButton("Close report chat", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered))
        .createPopup()
    Disposer.register(parent, popup)
    Disposer.register(popup, panel)
    Disposer.register(popup, Disposable {
        session.discard()
        scope.cancel()
    })
    popup.showCenteredInCurrentWindow(project)
}
