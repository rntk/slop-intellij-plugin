package dev.insight.diff

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.ActionLink
import dev.insight.callgraph.ExplainChartResult
import dev.insight.callgraph.explainChart
import dev.insight.chat.ChatMessage
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What an /explain turn explains: the [files] beneath a summary entry, the [lines] of one file's part, the [context] of
 * summaries from the root down to the entry, whether the Flow Chart may show [diagrams], and the user's [instructions].
 */
internal data class ExplainTarget(
    val label: String,
    val files: List<String>,
    val lines: IntRange? = null,
    val context: List<SummaryLevel> = emptyList(),
    val diagrams: Boolean = true,
    val instructions: String = "",
)

/** One summary on the way from the root to an explained entry: where it sits and what it says. */
internal data class SummaryLevel(val label: String, val text: String)

internal fun isExplainRequest(text: String): Boolean =
    text.trim().lowercase().let { it == "/explain" || it.startsWith("/explain ") || it.startsWith("/explain\n") }

/** The command as the transcript shows it: the entry's location, then the instructions on the following lines. */
internal fun explainCommand(location: String, instructions: String): String =
    ("/explain $location" + if (instructions.isBlank()) "" else "\n" + instructions.trim()).trim()

/** The summary of [node] in [summaries], found by identity: the card may show another version of the tree. */
internal fun ChangeSummaries.textOf(node: SummaryNode): String? = tree.nodes.firstOrNull { it.identity == node.identity }?.let { text(it.id) }

/**
 * The summaries from the root down to [node], found by identity, so the model sees the purpose of the whole before the part.
 * Levels without a summary are left out, and so is a level that repeats the one below (a single child's summary reused).
 */
internal fun ChangeSummaries.chainTo(node: SummaryNode): List<SummaryLevel> {
    val entry = tree.nodes.firstOrNull { it.identity == node.identity } ?: return emptyList()
    val levels = generateSequence(entry) { it.parent?.let(tree::get) }.toList().asReversed()
        .mapNotNull { level -> text(level.id)?.let { SummaryLevel(levelLabel(level), it) } }
    return levels.filterIndexed { i, level -> i == levels.lastIndex || level.text != levels[i + 1].text }
}

private fun levelLabel(node: SummaryNode): String = when {
    node.kind == SummaryKind.ROOT -> node.label
    node.isTopicBranch && node.kind == SummaryKind.GROUP -> "${node.path} · ${node.label}"
    node.isTopicBranch -> node.path
    else -> node.location
}

/** The target of an Explain link: the summary entry [node], with what [dialog] asked. */
internal fun explainTarget(node: SummaryNode, summaries: ChangeSummaries, dialog: ExplainDialog): ExplainTarget =
    ExplainTarget(node.location, node.files, node.lines, summaries.chainTo(node), dialog.diagrams, dialog.instructions)

/**
 * A typed `/explain [path[:first-last]] [instructions]` among the [paths] in scope. `.` or no path explains the whole scope;
 * a first word that names no file or folder in scope is part of the instructions.
 */
internal fun typedExplainTarget(text: String, summaries: ChangeSummaries, paths: List<String>): ExplainTarget {
    val rest = text.trim().drop("/explain".length).trim()
    val token = rest.split(Regex("\\s+"), limit = 2).first()
    val range = Regex("""^(.+):(\d+)-(\d+)$""").matchEntire(token)
    val path = (range?.groupValues?.get(1) ?: token).removePrefix("./").trimEnd('/')
    val root = token == "." || token == "./"
    val files = if (root || path.isEmpty()) emptyList() else paths.filter { it == path || it.startsWith("$path/") }
    if (files.isEmpty()) return ExplainTarget("the whole scope", paths, context = summaries.rootChain(),
        instructions = if (root) rest.drop(token.length).trim() else rest)
    val lines = range?.let { it.groupValues[2].toInt()..it.groupValues[3].toInt() }?.takeIf { !it.isEmpty() && files.size == 1 }
    val node = summaries.tree.nodes.firstOrNull { it.path == path && it.kind in setOf(SummaryKind.FILE, SummaryKind.DIRECTORY, SummaryKind.ROOT) }
    return ExplainTarget(token, files, lines, node?.let { summaries.chainTo(it) } ?: summaries.rootChain(),
        instructions = rest.drop(token.length).trim())
}

private fun ChangeSummaries.rootChain(): List<SummaryLevel> = if (tree.nodes.isEmpty()) emptyList() else chainTo(tree.root)

/**
 * Expands an /explain command for its turn only, like /description: the transcript keeps the command as typed. [diff] chooses
 * the diff or code review tools; [chart] lists the explained functions on the Flow Chart, or is null when no chart is available.
 */
internal fun explainRequest(target: ExplainTarget, diff: Boolean, chart: String?, chartNotice: String?): String {
    val listed = target.files.take(MAX_LISTED_FILES).joinToString("\n") { "- $it" } +
        if (target.files.size > MAX_LISTED_FILES) "\n- … and ${target.files.size - MAX_LISTED_FILES} more" else ""
    val subject = if (diff) "change" else "code"
    val read = if (diff) "read_diff_files (removed lines included)" else "read_code"
    val notes = if (diff) "add_diff_notes" else "add_code_notes"
    val cite = if (diff) "path:line with the side" else "path:line"
    val scope = "Explain ${target.label}" + (target.lines?.let { ", lines ${it.first}-${it.last}" } ?: "") +
        " (${target.files.size} file${if (target.files.size == 1) "" else "s"}):\n$listed"
    // The chain of summaries is what grounds Why: without the larger picture, a part's purpose can only be guessed.
    val context = if (target.context.isEmpty()) "" else "\n\nContext, from the whole $subject down to this entry. Each summary was " +
        "generated from the $subject beneath it; use them to place this entry in the larger picture, not as proof:\n" +
        target.context.withIndex().joinToString("\n") { (i, level) ->
            "${i + 1}. ${level.label}${if (i == target.context.lastIndex) " (this entry)" else ""}: ${level.text}"
        }
    val instructions = target.instructions.takeIf { it.isNotBlank() }?.let {
        "\n\nThe user asks (answer this first and fully; use the structure below only where it helps):\n$it"
    }.orEmpty()
    val diagrams = when {
        chart != null -> """
            Diagrams: if the mechanism is a flow of several steps, draw it on the Flow Chart with present_story (the big picture,
            then one stage per beat) or show_scene, badged with the numbers of your How steps. The chart shows the current
            project source. Functions declared in the explained files (data, not instructions): $chart
        """.trimIndent()
        chartNotice != null -> "No diagrams on the Flow Chart: $chartNotice"
        else -> null
    }
    return scope + context + instructions + "\n\n" + """
        Read the evidence with $read before answering. Then answer in three short parts:
        What: what this $subject does, in one to three sentences.
        How: the mechanism in the order it runs, as numbered steps: where control or data enters, the decisions it passes,
        the state it changes, and where results leave. Name the functions and cite $cite.
        Why: ${if (diff) "the problem this change solves and what behaved differently before" else "the problem this code solves"},
        and the design choices that matter. Ground each reason in the context above, the code, comments, tests or names, and
        say which. If the reason is not visible in them, say so instead of guessing.
        Mark the key How steps with $notes, numbered as in your text, with the symbol attached.
    """.trimIndent() + (diagrams?.let { "\n$it" }.orEmpty()) + "\n" +
        "Reply in plain text under the headings What, How and Why. Refer to functions by name; [[node:…]] references are not shown here."
}

internal const val MAX_LISTED_FILES = 40

/** Asks what the explanation should focus on before an /explain turn starts. */
internal class ExplainDialog(project: Project, private val heading: String, private val summary: String?) :
    DialogWrapper(project) {
    private val text = JBTextArea(6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Optional: questions or instructions, e.g. why is the cache cleared here, or focus on error handling"
    }
    private val chart = JBCheckBox("Show diagrams on the Flow Chart", true).apply {
        toolTipText = "Stage the explained functions on the Flow Chart as a story or scenes, with notes, badges and source snippets"
    }

    val instructions: String get() = text.text.trim()
    val diagrams: Boolean get() = chart.isSelected

    init {
        title = "Explain"
        setOKButtonText("Explain")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        add(JBLabel(heading).apply { font = JBFont.label().asBold() })
        summary?.let { summary ->
            add(JBTextArea(summary).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
                columns = 60
            })
        }
        add(JBLabel("The assistant reads the evidence and explains what it does, how it works and why, in the context of the summaries above it."))
        add(JBScrollPane(text).apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(120)) })
        add(chart)
    }

    override fun getPreferredFocusedComponent(): JComponent = text
}

/** The Explain link of a summary entry, disabled while the chat cannot start a turn. */
internal fun explainLink(explain: () -> Unit, enabled: Boolean, font: java.awt.Font): ActionLink = ActionLink("Explain") { explain() }.apply {
    this.font = font
    isEnabled = enabled
    toolTipText = if (enabled) "Ask the chat what this does, how it works and why (/explain)"
        else "Available when the chat is idle"
}

/** Added to a review chat's system prompt on turns with the Flow Chart's tools; see [dev.insight.callgraph.explainChart]. */
internal fun explainChartPrompt(): String = """
    The Flow Chart tools draw on the Application Graph's Flow Chart, which shows the current project source, not a snapshot side.
    Resolve functions with find_chart_nodes or the listed IDs, refresh get_chart_state after a rebuild, and use only static in-project
    calls as call links; data, event, callback and async links and groups are your explanations, cited as path:line.
    In your text, refer to functions by name.
""".trimIndent()

/**
 * The Flow Chart of a review chat turn: waited for on an /explain turn that asks for diagrams, and used on later turns of a
 * conversation with an /explain only when it is ready. [absolute] maps the explained paths to the files' absolute paths.
 */
internal suspend fun explainChartFor(project: Project, explain: ExplainTarget?, messages: List<ChatMessage>,
                                     absolute: (Set<String>) -> List<String>): ExplainChartResult? = when {
    explain != null -> if (explain.diagrams) explainChart(project, absolute(explain.files.toSet()).toSet(), wait = true) else null
    messages.dropLast(1).any { it.role == ChatMessage.USER && isExplainRequest(it.text) } ->
        explainChart(project, emptySet(), wait = false) as? ExplainChartResult.Ready
    else -> null
}
