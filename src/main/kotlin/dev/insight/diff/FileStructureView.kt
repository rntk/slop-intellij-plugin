package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.MatteBorder

/**
 * A run of lines of the viewed file: an outermost function or property, or the lines between them. [reaches] lists the
 * neighboring project files reached by the block's calls; paths in the review's scope are scope paths, others project-relative.
 * [lineReaches] keeps those neighbors per call line. [title] names the declaration, null for the lines between.
 */
internal data class FileBlock(
    val lines: IntRange,
    val title: String?,
    val reaches: List<String>,
    val lineReaches: Map<Int, List<String>> = emptyMap(),
)

/** The structure of one file at [fingerprint]: its blocks top to bottom and neighboring calls per line. */
internal data class FileStructure(
    val path: String,
    val fingerprint: String,
    val blocks: List<FileBlock>,
    /** True when local helper traversal was cut at the depth or visit limit. */
    val truncated: Boolean = false,
)

/**
 * What the canvas shows in File view: [file] block by block, each with the topics and summaries of the code it reaches.
 * [structure] is null while it is analyzed, or with [error] when it could not be. [canRun] allows topic and summary runs.
 */
internal data class FileViewCard(
    val file: ReviewSource,
    val structure: FileStructure?,
    val error: String?,
    val topics: ReviewTopics,
    val summaries: ReviewSummaries,
    val canRun: Boolean,
) {
    /** The scope's files reached by any block, without a topic or a summary yet. */
    val missingTopics: Int get() = reached().count { it in scopePaths && it !in topics.labels }
    val missingSummaries: Int get() = reached().count { it in scopePaths && fileSummaries[it] == null }

    val scopePaths: Set<String> by lazy { topics.files.mapTo(hashSetOf()) { it.path } + fileSummaries.keys }
    val fileSummaries: Map<String, String?> by lazy {
        summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to summaries.text(it.id) }
    }

    fun reached(): Set<String> = structure?.blocks.orEmpty().flatMapTo(linkedSetOf()) { it.reaches }
}

internal class FileStructureActions(
    val openFile: (path: String, line: Int?) -> Unit,
    val showLines: (path: String, lines: IntRange) -> Unit,
)

/**
 * One file read as a document of its blocks, with neighboring files' topics on the right rail. Summaries appear in the left
 * margin only while hovering a topic card or the corresponding source line.
 */
internal class FileStructurePanel(
    private val card: FileViewCard,
    private val structure: FileStructure,
    width: Int,
    zoom: Double,
    private val actions: FileStructureActions,
    vocabulary: ReviewVocabulary = ReviewVocabulary.CODE,
    private val project: Project? = null,
) : DocumentColumnsPanel(zoom, vocabulary) {
    /** A block as a document section; its path is unique within the document. */
    private class BlockSource(val block: FileBlock, val file: ReviewSource, override val text: String) : ReviewSource {
        override val path: String = "${file.path}#L${block.lines.first}-${block.lines.last}"
        override val absolutePath: String get() = file.absolutePath
        override val fingerprint: String get() = file.fingerprint
        override val facts: List<String> get() = listOf("Lines ${block.lines.first}–${block.lines.last}")
    }

    private val lines = card.file.text.orEmpty().split('\n')
    private val digits = lines.size.toString().length
    private val sourceViews = mutableMapOf<Int, SourceTextView>()
    private val summaryPaths = mutableMapOf<String, List<String>>()
    private val summaryLines = mutableMapOf<String, Set<Int>>()
    private val lineItems = mutableMapOf<Int, RailItem>()
    private val topicItems = mutableListOf<RailItem>()
    override val hoverSummariesOnly: Boolean = true

    init {
        val sources = structure.blocks.map { block ->
            BlockSource(block, card.file, lines.subList((block.lines.first - 1).coerceIn(0, lines.size), block.lines.last.coerceIn(0, lines.size)).joinToString("\n") + "\n")
        }
        val depth = railDepth(card)
        structure.blocks.forEachIndexed { index, block ->
            val groups = groups(card, block)
            groups.entries.forEachIndexed { lane, (topic, paths) ->
                val key = "$index:$topic"
                summaryPaths[key] = paths
                summaryLines[key] = block.lineReaches.filterValues { reached -> reached.any { it in paths } }.keys
                topicItems += RailItem(
                    key = key,
                    name = "reached-topic:$index:$topic",
                    title = topicTitle(topic),
                    family = topic.takeUnless { it == PENDING || it == OUTSIDE }?.substringBefore(" > "),
                    level = depth - 1 - lane,
                    first = index,
                    last = index,
                    tooltip = "${topicTitle(topic)} · ${paths.joinToString(", ")} · hover for summaries",
                    summary = paths.mapNotNull { card.fileSummaries[it] }.joinToString("\n"),
                )
            }
            for ((line, paths) in block.lineReaches) {
                val key = "line:$line"
                summaryPaths[key] = paths
                summaryLines[key] = setOf(line)
                lineItems[line] = RailItem(key, key, "Line $line", null, 0, index, index, "Line $line")
            }
        }
        // The margin is sized for the longest word of what it shows: the reached files' summaries.
        val margins = sources.associate { source ->
            source.path to source.block.reaches.mapNotNull { card.fileSummaries[it] }.joinToString(" ").ifEmpty { null }
        }
        layoutDocument(sources, margins, topicItems, depth, width, missingSummary = "")
    }

    override fun countLabel(count: Int) = if (count == 1) "1 block" else "$count blocks"

    override fun sectionHeader(file: ReviewSource): JComponent {
        val block = (file as BlockSource).block
        return JPanel(BorderLayout(scaled(6), 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), pixelBorder(scaled(4), scaled(8), scaled(4), scaled(4)))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(ActionLink(block.title ?: "Lines ${block.lines.first}–${block.lines.last}") { actions.openFile(card.file.path, block.lines.first) }.apply {
                    font = cardFont(JBFont.label().asBold()); toolTipText = "Open ${card.file.path} at line ${block.lines.first}"
                    minimumSize = Dimension(0, preferredSize.height)
                })
                add(Box.createHorizontalStrut(scaled(6)))
                val reach = when (block.reaches.size) { 0 -> "no neighboring calls"; 1 -> "calls 1 neighboring file"; else -> "calls ${block.reaches.size} neighboring files" }
                add(JLabel(listOfNotNull("Lines ${block.lines.first}–${block.lines.last}".takeIf { block.title != null }, reach).joinToString(" · ")).apply {
                    font = font(JBFont.small()); foreground = help
                    minimumSize = Dimension(0, preferredSize.height)
                })
            }, BorderLayout.CENTER)
        }
    }

    override fun sectionBody(file: ReviewSource, width: Int): JComponent {
        val source = file as BlockSource
        val index = structure.blocks.indexOf(source.block)
        return SourceTextView(source.text, width, zoom, source.block.lines.first, digits) { line ->
            val paths = line?.let { source.block.lineReaches[it] }.orEmpty()
            hoverSource(lineItems[line], topicItems.filter { item ->
                item.first == index && summaryPaths[item.key].orEmpty().any { it in paths }
            }.mapTo(hashSetOf()) { it.key })
        }.also { it.enableNavigation(project, card.file); sourceViews[index] = it }
    }

    /** No persistent notes or summary-driven row heights: a hover overlays only the relevant neighboring summaries. */
    override fun noteBody(file: ReviewSource, summary: String?, missingSummary: String, width: Int): JComponent = JPanel()

    override fun cardNote(item: RailItem, width: Int): JComponent = JPanel(VerticalLayout(cardScaled(6))).apply {
        background = UIUtil.getListBackground()
        border = pixelBorder(cardScaled(4), cardScaled(NOTE_INSET), cardScaled(4), cardScaled(2))
        val inner = width - cardScaled(NOTE_INSET) - cardScaled(2)
        for ((topic, paths) in summaryPaths[item.key].orEmpty().groupBy { topic(card, it) }) {
            add(topicSummary(topic, paths, inner))
        }
        preferredSize = Dimension(width, preferredSize.height)
    }

    override fun activeChanged(item: RailItem?) {
        val activeLines = item?.let { summaryLines[it.key] }.orEmpty()
        sourceViews.forEach { (index, view) ->
            view.highlightedLines = activeLines.filterTo(hashSetOf()) { it in structure.blocks[index].lines }
        }
    }

    private fun topicSummary(topic: String, paths: List<String>, width: Int): JComponent = JPanel(VerticalLayout(cardScaled(4))).apply {
        val labeled = topic != PENDING && topic != OUTSIDE
        name = "neighbor-summary:$topic"
        isOpaque = true
        background = UIUtil.getListBackground()
        border = JBUI.Borders.compound(
            MatteBorder(1, 1, 1, 1, JBColor.border()),
            MatteBorder(0, cardScaled(RAIL_ACCENT), 0, 0, if (labeled) accent(topic.substringBefore(" > ")) else JBColor.border()),
            pixelBorder(cardScaled(4), cardScaled(6), cardScaled(4), cardScaled(4)),
        )
        val inner = width - cardScaled(RAIL_ACCENT) - cardScaled(10) - 2
        add(text(topicTitle(topic), inner,
            if (labeled) cardFont(JBFont.label().asBold()) else cardFont(JBFont.label()).deriveFont(Font.ITALIC), if (labeled) null else help))
        for (path in paths) {
            val name = path.substringAfterLast('/')
            add(JPanel(BorderLayout(cardScaled(4), 0)).apply {
                isOpaque = false
                add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type), BorderLayout.WEST)
                add(ActionLink(name) { actions.openFile(path, null) }.apply {
                    font = cardFont(JBFont.small()); toolTipText = path
                    minimumSize = Dimension(0, preferredSize.height)
                }, BorderLayout.CENTER)
            })
            if (topic == OUTSIDE) continue
            val summary = card.fileSummaries[path]
            add(if (summary != null) text(summary, inner, cardFont(JBFont.label()), null)
                else text("No summary yet", inner, cardFont(JBFont.small()).deriveFont(Font.ITALIC), help))
        }
        toolTipText = if (labeled) "$topic · LLM interpretation of the neighboring files" else null
    }

    override fun openFile(file: ReviewSource) = actions.openFile(card.file.path, (file as? BlockSource)?.block?.lines?.first)
    override fun showFile(file: ReviewSource) { (file as? BlockSource)?.let { actions.showLines(card.file.path, it.block.lines) } }
    override fun isSelected(item: RailItem): Boolean = false
    override fun select(item: RailItem) {}

    companion object {
        private const val PENDING = "\u0000pending"
        private const val OUTSIDE = "\u0000outside"

        private fun topic(card: FileViewCard, path: String) =
            card.topics.labels[path] ?: if (path in card.scopePaths) PENDING else OUTSIDE

        private fun topicTitle(topic: String) = when (topic) {
            PENDING -> "Awaiting topic"
            OUTSIDE -> "Outside the review scope"
            else -> topic
        }

        private fun groups(card: FileViewCard, block: FileBlock) = block.reaches.groupBy { topic(card, it) }

        fun railDepth(card: FileViewCard) = card.structure?.blocks?.maxOfOrNull { groups(card, it).size } ?: 0
    }
}
