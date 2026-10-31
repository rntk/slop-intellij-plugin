package dev.insight.apilog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.core.ApiExchange
import dev.insight.core.ApiTrafficLog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Application-wide raw traffic logs, stored under the IDE log directory so they survive restarts. */
object ApiLogs {
    val directory: Path get() = Path.of(PathManager.getLogPath(), "insight-api")
    // LLM bodies carry whole prompts and tool schemas, so keep a shorter history.
    val llm: ApiTrafficLog by lazy { ApiTrafficLog("LLM", directory.resolve("llm.jsonl"), maxEntries = 300) }
    val decisions: ApiTrafficLog by lazy { ApiTrafficLog("Decisions", directory.resolve("decisions.jsonl")) }
}

class ApiLogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val tabs = listOf("LLM" to { ApiLogs.llm }, "Decisions" to { ApiLogs.decisions })
        for ((title, log) in tabs) {
            val panel = ApiLogPanel(project, log)
            toolWindow.contentManager.addContent(factory.createContent(panel, title, false).apply { setDisposer(panel) })
        }
    }
}

/**
 * One API's traffic: a foldable list of exchanges, each unfolding into its request and response,
 * next to the raw text of the selected node. [log] is resolved off the EDT because it loads history from disk.
 */
class ApiLogPanel(private val project: Project, log: () -> ApiTrafficLog) : JPanel(BorderLayout()), Disposable {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = "Loading…"
    }
    private val detailTitle = JBLabel()
    private val detail = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, UIUtil.getLabelFont().size)
    }
    private val structured = ApiLogStructuredView()
    private var shownSelection: Pair<ApiExchange, Part>? = null
    private var selectionVersion = 0L
    private var refreshing = false
    private val count = JBLabel()
    private var traffic: ApiTrafficLog? = null
    private var unsubscribe: (() -> Unit)? = null
    @Volatile private var disposed = false

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4)
            add(button("Copy all", "Copy every exchange as JSON") { log ->
                runLogTask("Copying API log") {
                    val json = ApiTrafficLog.toPrettyJson(log.entries())
                    SwingUtilities.invokeLater { if (!disposed) copy(json) }
                }
            })
            add(button("Export…", "Save every exchange to a JSON file") { log -> export(log) })
            add(button("Open folder", "Show the JSON-lines files the log is stored in") { log -> log.file?.parent?.let(::reveal) })
            add(button("Expand all", null) { expandAll(true) })
            add(button("Collapse all", null) { expandAll(false) })
            add(button("Clear", "Delete this API's history from memory and disk") { log -> clear(log) })
            add(count)
        }, BorderLayout.NORTH)
        add(JBSplitter(false, 0.4f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(4)
                    add(detailTitle, BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                        add(JButton("Copy raw").apply {
                            toolTipText = "Copy the raw transcript for the selection"
                            addActionListener { copy(detail.text) }
                        })
                        add(JButton("Copy as JSON").apply {
                            toolTipText = "Copy the selected exchange (request and response) as JSON"
                            addActionListener { selected()?.let { copy(ApiTrafficLog.toPrettyJson(listOf(it))) } }
                        })
                    }, BorderLayout.EAST)
                }, BorderLayout.NORTH)
                add(JTabbedPane().apply {
                    addTab("Structured", structured)
                    addTab("Raw", JBScrollPane(detail))
                }, BorderLayout.CENTER)
            }
        }, BorderLayout.CENTER)
        tree.addTreeSelectionListener { if (!refreshing) showSelection() }
        runLogTask("Loading API log") {
            val loaded = log()
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                traffic = loaded
                tree.emptyText.text = "No ${loaded.api} requests yet"
                unsubscribe = loaded.subscribe { SwingUtilities.invokeLater { if (!disposed) refresh() } }
                refresh()
            }
        }
    }

    override fun dispose() {
        disposed = true
        unsubscribe?.invoke()
    }

    /** Rebuilds the tree, newest first, keeping which exchanges are unfolded and the selection. */
    private fun refresh() {
        val log = traffic ?: return
        val expanded = exchangeNodes().filter { tree.isExpanded(TreePath(it.path)) }.map { (it.userObject as Row).exchange.id }.toSet()
        val selection = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row
        val entries = log.entries()
        refreshing = true
        root.removeAllChildren()
        for (exchange in entries.asReversed()) {
            root.add(DefaultMutableTreeNode(Row(exchange, Part.EXCHANGE)).apply {
                add(DefaultMutableTreeNode(Row(exchange, Part.REQUEST), false))
                if (!exchange.pending) add(DefaultMutableTreeNode(Row(exchange, Part.RESPONSE), false))
            })
        }
        model.reload()
        for (node in exchangeNodes()) {
            val row = node.userObject as Row
            if (row.exchange.id in expanded) tree.expandPath(TreePath(node.path))
        }
        selection?.let { previous ->
            val node = exchangeNodes().firstOrNull { (it.userObject as Row).exchange.id == previous.exchange.id }
            val target = if (previous.part == Part.EXCHANGE) node
                else node?.children()?.toList()?.map { it as DefaultMutableTreeNode }?.firstOrNull { (it.userObject as Row).part == previous.part }
            target?.let { tree.selectionPath = TreePath(it.path) }
        }
        val failed = entries.count { it.failed }
        refreshing = false
        count.text = "${entries.size} requests" + if (failed > 0) ", $failed failed" else ""
        showSelection()
    }

    private fun showSelection() {
        val row = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row
        // The tree is rebuilt with fresh exchange instances, so look up the current one by ID.
        val exchange = row?.let { r -> traffic?.entries()?.firstOrNull { it.id == r.exchange.id } }
        if (row == null || exchange == null) {
            detailTitle.text = "Select a request"
            detail.text = ""
            shownSelection = null
            selectionVersion++
            structured.show(null)
            return
        }
        val current = exchange to row.part
        if (shownSelection == current) return
        shownSelection = current
        val version = ++selectionVersion
        structured.show(null)
        runLogTask("Preparing structured API log") {
            val json = ApiTrafficLog.toJson(exchange)
            val value = when (row.part) {
                Part.EXCHANGE -> json
                Part.REQUEST -> json.getAsJsonObject("request")
                Part.RESPONSE -> JsonObject().apply {
                    json.get("response")?.let { add("response", it) }
                    exchange.error?.let { addProperty("error", it) }
                }
            }
            SwingUtilities.invokeLater {
                if (!disposed && selectionVersion == version) structured.show(value)
            }
        }
        val text = when (row.part) {
            Part.EXCHANGE -> ApiTrafficLog.toText(exchange)
            Part.REQUEST -> buildString {
                appendLine("${exchange.method} ${exchange.url}")
                exchange.requestHeaders.forEach { (name, value) -> appendLine("$name: $value") }
                appendLine()
                append(ApiTrafficLog.pretty(exchange.requestBody))
            }
            Part.RESPONSE -> buildString {
                exchange.status?.let { appendLine("HTTP $it") }
                exchange.responseHeaders.forEach { (name, value) -> appendLine("$name: $value") }
                exchange.error?.let { appendLine("Error: $it") }
                appendLine()
                append(ApiTrafficLog.pretty(exchange.responseBody))
            }
        }
        if (detail.text != text) {
            detail.text = text
            detail.caretPosition = 0
        }
        detailTitle.text = "#${exchange.id} ${row.part.title}"
    }

    private fun selected(): ApiExchange? = ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row)
        ?.let { row -> traffic?.entries()?.firstOrNull { it.id == row.exchange.id } }

    private fun exchangeNodes() = root.children().toList().map { it as DefaultMutableTreeNode }

    private fun expandAll(expand: Boolean) {
        for (node in exchangeNodes()) TreePath(node.path).let { if (expand) tree.expandPath(it) else tree.collapsePath(it) }
    }

    private fun button(text: String, tooltip: String?, action: (ApiTrafficLog) -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        addActionListener { traffic?.let(action) }
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun export(log: ApiTrafficLog) {
        val descriptor = FileSaverDescriptor("Export ${log.api} API Log", "Save the raw requests and responses as JSON", "json")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("insight-${log.api.lowercase()}-api-log.json") ?: return
        runLogTask("Exporting ${log.api} API log") {
            Files.writeString(target.file.toPath(), ApiTrafficLog.toPrettyJson(log.entries()))
        }
    }

    /** Log parsing/serialization can involve large bodies; disk writes must finish once started. */
    private fun runLogTask(title: String, task: () -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                if (!disposed) task()
            }

            override fun onThrowable(error: Throwable) {
                if (!disposed) Messages.showErrorDialog(project, error.message ?: error.javaClass.simpleName, title)
            }
        })
    }

    private fun reveal(directory: Path) {
        Files.createDirectories(directory)
        RevealFileAction.openDirectory(directory)
    }

    private fun clear(log: ApiTrafficLog) {
        val answer = Messages.showYesNoDialog(project, "Delete all recorded ${log.api} requests and responses?", "Clear API Log", null)
        if (answer == Messages.YES) runLogTask("Clearing ${log.api} API log") { log.clear() }
    }

    private enum class Part(val title: String) { EXCHANGE("Request and response"), REQUEST("Request"), RESPONSE("Response") }

    private data class Row(val exchange: ApiExchange, val part: Part)

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean) {
            val (exchange, part) = (value as? DefaultMutableTreeNode)?.userObject as? Row ?: return
            when (part) {
                Part.EXCHANGE -> {
                    append("#${exchange.id} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(TIME.format(exchange.startedAt) + "  ")
                    val (outcome, attributes) = when {
                        exchange.pending -> "…" to SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                        exchange.failed -> (exchange.status?.toString() ?: "ERR") to SimpleTextAttributes.ERROR_ATTRIBUTES
                        else -> exchange.status.toString() to SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    }
                    append(outcome, attributes)
                    exchange.durationMs?.let { append("  ${formatDuration(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    if (exchange.attempt > 0) append("  retry ${exchange.attempt}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    summary(exchange).takeIf { it.isNotEmpty() }?.let { append("  $it") }
                }
                Part.REQUEST -> {
                    append("Request  ")
                    append("${exchange.method} ${size(exchange.requestBody)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                Part.RESPONSE -> {
                    append("Response  ", if (exchange.failed) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(listOfNotNull(exchange.status?.let { "HTTP $it" }, exchange.responseBody?.let(::size), exchange.error)
                        .joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            toolTipText = if (part == Part.EXCHANGE) exchange.url else null
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun formatDuration(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)

        fun size(body: String) = body.length.let { if (it < 1024) "$it B" else "%.1f KB".format(it / 1024.0) }

        /** A one-line hint of what was asked, so related requests are easy to spot in a long list. */
        fun summary(exchange: ApiExchange): String {
            val request = runCatching { JsonParser.parseString(exchange.requestBody).asJsonObject }.getOrNull() ?: return ""
            val messages = request.getAsJsonArray("messages")
            val questions = request.getAsJsonObject("questions")
            val text = when {
                messages != null -> {
                    val last = messages.lastOrNull { it.isJsonObject }?.asJsonObject
                    "${messages.size()} msgs" + (last?.let { " · ${it.string("role")}: ${it.string("content")}" } ?: "")
                }
                questions != null -> "questions: ${questions.keySet().joinToString(", ")}"
                else -> ""
            }
            return text.replace(Regex("\\s+"), " ").let { if (it.length > 120) it.take(119) + "…" else it }
        }

        fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}

