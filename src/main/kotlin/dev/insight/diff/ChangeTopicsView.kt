package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.MatteBorder
import kotlin.math.roundToInt

/** What the canvas shows for a tab's topics: the labels so far, the summaries beside each file, and whether a turn can run. */
internal data class TopicsCard(
    val topics: ChangeTopics,
    val summaries: ChangeSummaries,
    val canRun: Boolean,
    val topicSummaries: ChangeSummaries = ChangeSummaries(buildTopicSummaryTree(topics)),
) {
    /** What the hierarchy shows and traces; built once per card. */
    val reading: ChangeSummaries by lazy { topicReadingSummaries(topicSummaries, summaries) }
}

/** The document reads every file in topic order; the hierarchy reads the topics left to right with each file's summary. */
internal enum class ChangeTopicsMode { DOCUMENT, HIERARCHY }

/** What the shown outline was filtered for: the card and both trimmed queries. */
private data class OutlineFilter(val card: TopicsCard, val topicQuery: String, val summaryQuery: String)

/**
 * The selected topic survives re-rendering and snapshot changes while a node with the same level and path exists, and is
 * shared by both modes. Folded topics and the chosen level belong to the hierarchy; topics that appear after a level was
 * chosen fold to that level too. Word tracing reads the hierarchy's summaries, whose root is not shown. The two search
 * fields hide the files whose topic, or whose name, path and summary, do not match, with the topics left without files.
 */
internal class ChangeTopicsViewState(
    /** A large outline starts at its first level, as thousands of cards are slow to build and hard to read. */
    private val foldLargeOutline: Boolean = false,
    var mode: ChangeTopicsMode = ChangeTopicsMode.DOCUMENT,
) : SummaryTraceState(traceRoot = false, traceLeaves = true) {
    var selected: String? = null
        private set
    /** The level picked in the hierarchy's switcher; null shows every level until the reader picks one. */
    var level: Int? = null
        private set
    private val collapsed = hashSetOf<String>()
    private val seen = hashSetOf<String>()
    private var folded = false

    /** The text of the topic search field, matched against each file's whole topic. */
    var topicFilter = ""
    /** The text of the summary search field. Topics above its matches open while it is typed, unless folded by hand meanwhile. */
    var summaryFilter = ""
        set(value) { if (field.trim() != value.trim()) handFolded.clear(); field = value }
    private val handFolded = hashSetOf<String>()
    /** While the summary field opens the topics above its matches, only topics folded by hand since then fold. */
    private val folds get() = if (summaryFilter.isBlank()) collapsed else handFolded
    val filtering get() = topicFilter.isNotBlank() || summaryFilter.isNotBlank()

    private var filterFor: OutlineFilter? = null
    private var full: TopicOutline? = null
    private var shown: TopicOutline? = null
    /** The paths and topic identities left by the search fields; null hides nothing. */
    private var shownFiles: Set<String>? = null
    private var shownTopics: Set<String>? = null

    /** Every file of [card] in topic order. Like [outline], it updates this state. */
    fun fullOutline(card: TopicsCard): TopicOutline = outlines(card).first

    /** The files of [card] the search fields leave, in topic order, with only the topics above them. */
    fun outline(card: TopicsCard): TopicOutline = outlines(card).second

    private fun outlines(card: TopicsCard): Pair<TopicOutline, TopicOutline> {
        val topicQuery = topicFilter.trim()
        val summaryQuery = summaryFilter.trim()
        val previous = filterFor
        if (previous == null || previous.card !== card || previous.topicQuery != topicQuery || previous.summaryQuery != summaryQuery) {
            filterFor = OutlineFilter(card, topicQuery, summaryQuery)
            val outline = full.takeIf { previous?.card?.topics === card.topics } ?: topicOutline(card.topics)
            full = outline
            shown = if (topicQuery.isEmpty() && summaryQuery.isEmpty()) outline else {
                val summaries = card.summaries
                val texts = summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to summaries.text(it.id) }
                val kept = card.topics.files.filter { file ->
                    (topicQuery.isEmpty() || card.topics.labels[file.path]?.let { fuzzyMatches(topicQuery, it) } == true) &&
                        (summaryQuery.isEmpty() || fuzzyMatches(summaryQuery, listOfNotNull(file.path, texts[file.path]).joinToString(" ")))
                }
                val paths = kept.mapTo(hashSetOf()) { it.path }
                topicOutline(ChangeTopics(kept, card.topics.labels.filterKeys { it in paths }))
            }
            shownFiles = shown?.takeIf { it !== outline }?.files?.mapTo(hashSetOf()) { it.path }
            shownTopics = shown?.takeIf { it !== outline }?.nodes?.mapTo(hashSetOf()) { it.identity }
        }
        update(full!!)
        return full!! to shown!!
    }

    fun update(outline: TopicOutline) {
        val keys = outline.nodes.mapTo(hashSetOf(), ::key)
        if (selected !in keys) selected = null
        collapsed.retainAll(keys)
        handFolded.retainAll(keys)
        seen.retainAll(keys)
        // Nothing was chosen yet and the outline is huge: start at level 1, the topics beneath fold.
        if (this.level == null && foldLargeOutline && !folded && outline.files.size > LARGE_OUTLINE && outline.nodes.isNotEmpty()) {
            folded = true
            this.level = 1
        }
        val level = level
        for (node in outline.nodes) if (seen.add(key(node)) && level != null && node.level >= level) collapsed += key(node)
    }

    fun select(node: TopicNode) { selected = key(node).takeUnless { it == selected } }
    fun isSelected(node: TopicNode) = key(node) == selected
    fun toggle(node: TopicNode) { if (!folds.remove(key(node))) folds += key(node) }
    fun isCollapsed(node: TopicNode) = key(node) in folds

    /** Level n keeps levels 0..n visible and folds the topics at n and below; the outline's depth shows every file. */
    fun selectLevel(level: Int, outline: TopicOutline) {
        this.level = level
        collapsed.clear()
        outline.nodes.filter { it.level >= level }.mapTo(collapsed, ::key)
        handFolded.clear()
        handFolded += collapsed
    }

    fun effectiveLevel(outline: TopicOutline) = level?.coerceAtMost(outline.depth) ?: outline.depth

    /** Document mode keeps every file visible and limits the rail to the picked levels. */
    fun railDepth(outline: TopicOutline) = minOf(outline.depth, effectiveLevel(outline) + 1)

    /** Documents keep every file visible; hierarchies hide nodes under folded topics. [tree] is the reading tree. */
    override fun visibility(tree: ChangeSummaryTree): (SummaryNode) -> Boolean = { node ->
        filtered(node) && if (mode == ChangeTopicsMode.DOCUMENT) node.kind == SummaryKind.FILE || tree.depth(node.id) - 1 <= (level ?: Int.MAX_VALUE)
        else generateSequence(node.parent) { tree[it].parent }.map(tree::get)
            .none { it.identity.startsWith(TOPIC) && "${tree.depth(it.id) - 1}:${it.identity.removePrefix(TOPIC)}" in folds }
    }

    /** Whether the search fields leave [node] of the reading tree on screen. */
    private fun filtered(node: SummaryNode): Boolean = when {
        node.identity.startsWith(TOPIC) -> shownTopics?.contains(node.identity.removePrefix(TOPIC)) ?: true
        node.kind == SummaryKind.FILE -> shownFiles?.contains(node.path) ?: true
        else -> true
    }

    /**
     * Opens the topics down to the matches and folds related topics with no related entry beneath, so their summary shows.
     * [tree] is the reading tree: its topics are `topic:` identities one level below the hidden root.
     */
    override fun showTrace(tree: ChangeSummaryTree, trace: SummaryTrace) {
        fun keys(identities: Set<String>) = tree.nodes.filter { it.identity in identities && it.identity.startsWith(TOPIC) }
            .map { "${tree.depth(it.id) - 1}:${it.identity.removePrefix(TOPIC)}" }
        tracePath(tree, trace)?.let { path ->
            folds -= keys(path.open).toSet()
            folds += keys(path.fold)
        }
        val best = trace.best ?: return
        selected = keys(setOf(best.identity)).firstOrNull()
        reveal = best.identity
    }

    private fun key(node: TopicNode) = "${node.level}:${node.identity}"

    companion object {
        /** The reading tree's identity prefix of a topic, see [buildTopicSummaryTree]. */
        const val TOPIC = "topic:"
        /** Files in an outline beyond which [foldLargeOutline] starts at level 1. */
        const val LARGE_OUTLINE = 200
        fun identity(node: TopicNode) = TOPIC + node.identity
    }
}

/**
 * [refresh] rebuilds the card after the hierarchy changed the view state, as folding changes the layout. [trace] scores word
 * [Int] of a summary; with `true`, Search from a scored word's context menu searches and opens the path to the matches.
 */
internal class ChangeTopicActions(
    val openFile: (String) -> Unit,
    val showDiffCard: (String) -> Unit,
    val refresh: () -> Unit = {},
    val trace: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    /** Open or show a file node of the summary tree; by default by its path. A null [showNode] hides the canvas button. */
    val openNode: (SummaryNode) -> Unit = { openFile(it.path) },
    val showNode: ((SummaryNode) -> Unit)? = { showDiffCard(it.path) },
    val review: SummaryReviewPresentation? = null,
    /** Summary actions for the entry with this reading identity, such as filling or regenerating its summaries; null hides them. */
    val entryMenu: ((String) -> JPopupMenu)? = null,
) {
    fun installEntryMenu(component: JComponent, identity: String) {
        val menu = entryMenu ?: return
        installSummaryEntryMenu(component) { menu(identity) }
    }
}

/**
 * A review read as one document: every file's diff or source down the middle, its summary in the left margin and the
 * hierarchy on the right, broadest level outermost. Each rail card spans its files, and a section is as tall as the taller
 * of its contents and summary, so the three columns stay aligned.
 */
internal abstract class DocumentColumnsPanel(
    protected val zoom: Double,
    protected val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) : JPanel(null) {
    protected class RailItem(
        val key: String,
        val name: String,
        val title: String,
        val family: String?,
        val level: Int,
        val first: Int,
        val last: Int,
        val tooltip: String,
        val icon: Icon? = null,
        val placeholder: Boolean = false,
        /** The card's own summary, shown in the margin beside its files while the card is hovered or selected. */
        val summary: String? = null,
        val missingSummary: String = "No summary yet",
        val summaryLabel: String = "Summary",
        val levelLabel: String? = null,
    )

    private val sections = mutableListOf<JComponent>()
    private val notes = mutableListOf<JComponent>()
    private val noteColumns = mutableListOf<JComponent>()
    private var tops = IntArray(0)
    private var bottoms = IntArray(0)
    private var summaryWidth = 0
    private var documentHeight = 0
    /** The active rail card with its summary laid over the margin of its files. */
    private var cardSummary: Pair<RailItem, JComponent>? = null
    private val rail = mutableListOf<Pair<RailItem, JComponent>>()
    private val stickyHeaders = StickyCardHeaders()
    fun updateStickyHeaders(top: Int) = stickyHeaders.update(top)
    private var hovered: RailItem? = null
    private var sourceSummary: RailItem? = null
    private var sourceCards: Set<String> = emptySet()
    /** File view reveals a summary only for the hovered call line or rail card. */
    protected open val hoverSummariesOnly: Boolean = false
    protected open fun activeChanged(item: RailItem?) {}
    protected fun hoverSource(item: RailItem?, cards: Set<String> = emptySet()) {
        if (sourceSummary == item && sourceCards == cards) return
        sourceSummary = item
        sourceCards = cards
        applyActive()
    }
    protected fun scaled(n: Int) = zoomScaled(n, zoom)
    protected fun cardScaled(n: Int) = cardScaledBy(zoom, n)
    protected fun font(base: Font) = base.zoomed(zoom)
    /** Match the example canvas's inverse scale: card text stays legible and grows gently on zoom out. */
    protected fun cardFont(base: Font) = base.zoomed(cardScale(zoom))
    protected val help: Color get() = UIUtil.getContextHelpForeground()

    init { isOpaque = false }

    protected abstract fun openFile(file: ReviewSource)
    protected abstract fun showFile(file: ReviewSource)
    protected open fun wholeFileAction(file: ReviewSource): (() -> Unit)? = null
    protected open fun directoryDiffAction(item: RailItem): (() -> Unit)? = null
    protected open fun explainFileAction(file: ReviewSource): (() -> Unit)? = null
    protected open fun explainItemAction(item: RailItem): (() -> Unit)? = null
    protected open val explainEnabled: Boolean get() = true
    protected abstract fun isSelected(item: RailItem): Boolean
    protected abstract fun select(item: RailItem)
    protected open fun selectedFiles(): IntRange? = null
    /** Reading views can mark semantic search results beside the source and on the folder rail. */
    protected open fun resultTrace(): SummaryTrace? = null

    private fun resultColor(identity: String): Color? {
        val trace = resultTrace() ?: return null
        if (trace.related.none { it.identity == identity }) return null
        return if (trace.best?.identity == identity) TraceStyle.BEST_MATCH else TraceStyle.RELATED
    }

    private fun markResult(component: JComponent, identity: String) {
        val color = resultColor(identity) ?: return
        val opaque = component.isOpaque
        val background = component.background
        component.isOpaque = true
        component.background = color
        installTraceDecorationReset(component) {
            component.isOpaque = opaque
            component.background = background
            component.repaint()
        }
    }

    private fun resultBadge(identity: String): JLabel? {
        val trace = resultTrace() ?: return null
        val match = trace.related.firstOrNull { it.identity == identity } ?: return null
        val title = if (trace.best?.identity == identity) "● Best match" else "● Related"
        return JLabel("$title · ${(match.probability * 100).roundToInt()}%").apply {
            name = "match:$identity"
            font = cardFont(JBFont.small())
            foreground = TraceStyle.MATCH_TEXT
            toolTipText = matchLabel(match, trace)
            installTraceDecorationReset(this) {
                preferredSize = preferredSize
                text = ""
                name = null
                toolTipText = null
            }
        }
    }
    /** "1 file", "3 files": what a rail card or its summary spans. */
    protected open fun countLabel(count: Int): String = if (count == 1) "1 file" else "$count files"
    /** The sticky heading of a section; a file's path and facts by default. */
    protected open fun sectionHeader(file: ReviewSource): JComponent = fileHeader(file)
    /** What a section shows under its heading; null shows the file's diff or complete source. */
    protected open fun sectionBody(file: ReviewSource, width: Int): JComponent? = null
    /** The margin beside a section, inside its rule; the file's summary by default. */
    protected open fun noteBody(file: ReviewSource, summary: String?, missingSummary: String, width: Int): JComponent =
        summaryBody("file:${file.path}", summary, missingSummary, width)

    protected open fun summaryFont(): Font = cardFont(JBFont.label())

    protected open fun summaryWordWidth(identity: String, summary: String): Int =
        summary.split(Regex("\\s+")).maxOfOrNull(getFontMetrics(summaryFont())::stringWidth) ?: 0

    /** A file or rail card's summary; reading views attach cached word tracing here. */
    protected open fun summaryBody(identity: String, summary: String?, missingSummary: String, width: Int): JComponent =
        if (summary != null) text(summary, width, summaryFont(), null)
        else text(missingSummary, width, cardFont(JBFont.small()).deriveFont(Font.ITALIC), help)

    protected fun layoutDocument(
        files: List<ReviewSource>,
        summaries: Map<String, String?>,
        items: List<RailItem>,
        depth: Int,
        width: Int,
        missingSummary: String = "No summary yet",
    ) {
        if (files.isEmpty()) {
            val empty = text(vocabulary.noFiles, scaled(320), font(JBFont.label()), help)
            add(empty.apply { setBounds(0, 0, scaled(320), preferredSize.height) })
            preferredSize = Dimension(scaled(320), empty.height)
            applyActive()
            return
        }
        val railTitleMetrics = getFontMetrics(cardFont(JBFont.label().asBold()).forReviewName())
        val wordBoundary = Regex("\\s+")
        fun longestWord(value: String, metrics: java.awt.FontMetrics): Int =
            value.split(wordBoundary).maxOfOrNull(metrics::stringWidth) ?: 0
        fun titleWidth(item: RailItem) = longestWord(item.title, railTitleMetrics) +
            (item.icon?.let { it.iconWidth + cardScaled(4) } ?: 0)
        val railInsets = 2 + cardScaled(RAIL_ACCENT) + cardScaled(8) + cardScaled(6)
        val cardWidth = maxOf(cardScaled(RAIL_CARD_WIDTH),
            (items.maxOfOrNull(::titleWidth) ?: 0) + railInsets + JBUI.scale(2))
        val summaryWordWidth = (summaries.mapNotNull { (path, summary) -> summary?.let { summaryWordWidth("file:$path", it) } } +
            items.mapNotNull { item -> item.summary?.let { summaryWordWidth(item.key, it) } }).maxOrNull() ?: 0
        val summaryTitleWidth = items.maxOfOrNull(::titleWidth) ?: 0
        val summaryWidth = maxOf(cardScaled(SUMMARY_WIDTH),
            maxOf(summaryWordWidth, summaryTitleWidth) + cardScaled(NOTE_INSET) + cardScaled(2) + JBUI.scale(2))
        val railWidth = if (depth <= 0) 0 else depth * cardWidth + (depth - 1) * scaled(RAIL_GAP)
        this.summaryWidth = summaryWidth
        val gap = scaled(COLUMN_GAP)
        val docWidth = (width - summaryWidth - railWidth - 2 * gap).coerceAtLeast(scaled(MIN_DOC_WIDTH))
        val docX = summaryWidth + gap
        val railX = docX + docWidth + gap
        val preparedRail = items.map { it to railCard(it, cardWidth) }
        val rowMinimums = IntArray(files.size)
        preparedRail.forEach { (item, component) ->
            rowMinimums[item.first] = maxOf(rowMinimums[item.first], component.preferredSize.height)
        }
        tops = IntArray(files.size)
        bottoms = IntArray(files.size)
        var y = 0
        files.forEachIndexed { index, file ->
            val section = section(file, docWidth)
            val note = note(file, summaries[file.path], missingSummary, summaryWidth)
            val height = maxOf(section.preferredSize.height, note.preferredSize.height, rowMinimums[index])
            section.setBounds(docX, y, docWidth, height)
            val column = noteColumn(file, note).apply {
                setBounds(0, y, summaryWidth, height)
                isVisible = !hoverSummariesOnly
            }
            add(section); add(column)
            sections += section; notes += note; noteColumns += column
            tops[index] = y; bottoms[index] = y + height
            y += height + scaled(SECTION_GAP)
        }
        val step = cardWidth + scaled(RAIL_GAP)
        // The leaf level sits next to the diffs; the broadest level is outermost, as in the article canvas.
        for ((item, component) in preparedRail) {
            component.setBounds(railX + (depth - 1 - item.level) * step, tops[item.first], cardWidth, bottoms[item.last] - tops[item.first])
            add(component)
            rail += item to component
        }
        documentHeight = (y - scaled(SECTION_GAP)).coerceAtLeast(0)
        preferredSize = Dimension(railX + railWidth, documentHeight)
        applyActive()
    }

    /**
     * The hovered rail card, else the selected one, outlines its card and the diffs and summaries of its files, and shows its
     * own summary in the margin in place of theirs.
     */
    protected fun applyActive() {
        val active = hovered ?: sourceSummary ?: rail.firstOrNull { (item, _) -> !item.placeholder && isSelected(item) }?.first
        val range = active?.let { it.first..it.last } ?: selectedFiles()
        sections.forEachIndexed { index, section -> section.border = frame(range != null && index in range) }
        notes.forEachIndexed { index, note -> note.border = noteBorder(range != null && index in range) }
        showCardSummary(active?.takeUnless { it.placeholder })
        rail.forEach { (item, component) ->
            component.border = railBorder(item, item == active || (hovered == null && item.key in sourceCards))
            component.background = resultColor(item.key)
                ?: if (!item.placeholder && isSelected(item)) UIUtil.getListSelectionBackground(false) else UIUtil.getListBackground()
        }
        activeChanged(active)
        repaint()
    }

    /** Swaps the margin of [item]'s files for its summary, sticky like the file notes, and restores them when it goes. */
    private fun showCardSummary(item: RailItem?) {
        if (cardSummary?.first == item) return
        cardSummary?.second?.let { remove(it); stickyHeaders.remove(it) }
        cardSummary = null
        noteColumns.forEach { it.isVisible = !hoverSummariesOnly }
        if (hoverSummariesOnly) {
            preferredSize = Dimension(preferredSize.width, documentHeight)
            revalidate()
        }
        if (item == null || noteColumns.isEmpty()) return
        for (index in item.first..item.last) noteColumns[index].isVisible = false
        val note = cardNote(item, summaryWidth)
        // Opaque and on top, so a summary longer than its files' margin covers the notes below rather than mixing with them.
        val column = JPanel(stickyHeaders.layout()).apply {
            name = "card-summary:${item.key}"
            background = UIUtil.getListBackground()
            add(note, BorderLayout.NORTH)
            setBounds(0, tops[item.first], summaryWidth, maxOf(bottoms[item.last] - tops[item.first], note.preferredSize.height))
        }
        stickyHeaders.add(column, note)
        add(column, 0)
        column.validate()
        cardSummary = item to column
        if (hoverSummariesOnly) {
            preferredSize = Dimension(preferredSize.width, maxOf(documentHeight, column.y + column.height))
            revalidate()
        }
    }

    protected open fun cardNote(item: RailItem, width: Int): JComponent = JPanel(VerticalLayout(cardScaled(4))).apply {
        background = UIUtil.getListBackground()
        border = noteBorder(true)
        val inner = width - cardScaled(NOTE_INSET) - cardScaled(2)
        val icon = item.icon
        val title = text(item.title, inner - if (icon != null) icon.iconWidth + cardScaled(4) else 0, cardFont(JBFont.label().asBold()).forReviewName(), null)
        add(if (icon == null) title else JPanel(BorderLayout(cardScaled(4), 0)).apply {
            isOpaque = false
            add(JLabel(icon).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
        })
        add(JLabel("${item.summaryLabel} · ${countLabel(item.last - item.first + 1)}").apply { font = cardFont(JBFont.small()); foreground = help })
        val summary = item.summary
        add(summaryBody(item.key, summary, item.missingSummary, inner))
        markResult(this, item.key)
        toolTipText = "Summary of ${item.tooltip} · LLM interpretation"
        preferredSize = Dimension(width, preferredSize.height)
    }

    private fun section(file: ReviewSource, width: Int): JComponent = JPanel(stickyHeaders.layout()).apply {
        name = "file:${file.path}"
        background = UIUtil.getListBackground()
        border = frame(false)
        val inner = width - scaled(4)
        val heading = sectionHeader(file)
        add(heading, BorderLayout.NORTH)
        stickyHeaders.add(this, heading)
        val body = sectionBody(file, inner)
        if (body != null) add(body, BorderLayout.CENTER)
        else if (file is ReviewFile) {
            val patch = PatchView(file.patch, inner, zoom)
            add(patch, BorderLayout.CENTER)
            if (patch.hiddenLines > 0) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(8), scaled(4))).apply {
                isOpaque = false
                add(JLabel("… ${patch.hiddenLines} more lines").apply { font = font(JBFont.small()); foreground = help })
                add(ActionLink("View full diff") { openFile(file) }.apply { font = font(JBFont.small()) })
            }, BorderLayout.SOUTH)
        } else add(SourceTextView(file.text, inner, zoom), BorderLayout.CENTER)
        preferredSize = Dimension(width, preferredSize.height)
    }

    /** A source file's full path, or a diff's file name, followed by its snapshot facts. */
    private fun fileHeader(file: ReviewSource): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), pixelBorder(scaled(4), scaled(8), scaled(4), scaled(4)))
        toolTipText = file.path
        val name = file.path.substringAfterLast('/')
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
            add(Box.createHorizontalStrut(scaled(6)))
            add(ActionLink(if (file is ReviewFile) name else file.path) { openFile(file) }.apply {
                font = cardFont(JBFont.label().asBold()).forReviewName(); toolTipText = vocabulary.openTooltip(file.path)
                minimumSize = Dimension(0, preferredSize.height)
            })
            val details = listOfNotNull(file.path.substringBeforeLast('/', "").takeIf { file is ReviewFile && it.isNotEmpty() }) + file.facts
            add(Box.createHorizontalStrut(scaled(6)))
            add(JLabel(details.joinToString(" · ")).apply {
                font = font(JBFont.small()); foreground = help; toolTipText = file.path
                minimumSize = Dimension(0, preferredSize.height)
            })
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(8), 0)).apply {
            isOpaque = false
            resultBadge("file:${file.path}")?.let(::add)
            wholeFileAction(file)?.let { open ->
                add(ActionLink("Open file") { open() }.apply { font = cardFont(JBFont.small()) })
            }
            explainFileAction(file)?.let { add(explainLink(it, explainEnabled, cardFont(JBFont.small()))) }
            vocabulary.showOnCanvas?.let { label ->
                add(InplaceButton(IconButton(label, vocabulary.showOnCanvasIcon, vocabulary.showOnCanvasIcon)) { showFile(file) }
                    .apply { cursor = Cursor.getDefaultCursor(); toolTipText = label })
            }
        }, BorderLayout.EAST)
        markResult(this, "file:${file.path}")
    }

    /** The summary's margin beside its whole section, so the note stays in view while the diff beside it scrolls. */
    private fun noteColumn(file: ReviewSource, note: JComponent): JComponent = JPanel(stickyHeaders.layout()).apply {
        name = "summary:${file.path}"
        isOpaque = false
        add(note, BorderLayout.NORTH)
        stickyHeaders.add(this, note)
    }

    private fun note(file: ReviewSource, summary: String?, missingSummary: String, width: Int): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = noteBorder(false)
        toolTipText = "Summary of ${file.path} · LLM interpretation"
        add(noteBody(file, summary, missingSummary, width - cardScaled(NOTE_INSET) - cardScaled(2)), BorderLayout.CENTER)
        markResult(this, "file:${file.path}")
        preferredSize = Dimension(width, preferredSize.height)
    }

    private fun railCard(item: RailItem, width: Int): JComponent = JPanel(stickyHeaders.layout()).apply {
        name = item.name
        isOpaque = true
        background = UIUtil.getListBackground()
        border = railBorder(item, false)
        val inner = width - cardScaled(RAIL_ACCENT) - cardScaled(8) - cardScaled(6) - 2
        val icon = item.icon
        val title = text(item.title, inner - if (icon != null) icon.iconWidth + cardScaled(4) else 0,
            (if (item.placeholder) cardFont(JBFont.label()).deriveFont(Font.ITALIC) else cardFont(JBFont.label().asBold())).forReviewName(), if (item.placeholder) help else null)
        val heading = JPanel(VerticalLayout(cardScaled(2))).apply {
            isOpaque = false
            add(if (icon == null) title else JPanel(BorderLayout(cardScaled(4), 0)).apply {
                isOpaque = false
                add(JLabel(icon).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
                add(title, BorderLayout.CENTER)
            })
            add(JLabel(countLabel(item.last - item.first + 1)).apply { font = cardFont(JBFont.small()); foreground = help })
            item.levelLabel?.let { level ->
                add(JLabel(level).apply { font = cardFont(JBFont.small()); foreground = help })
            }
            resultBadge(item.key)?.let(::add)
            directoryDiffAction(item)?.let { open ->
                add(ActionLink("View diff") { open() }.apply { font = cardFont(JBFont.small()) })
            }
            explainItemAction(item)?.let { add(explainLink(it, explainEnabled, cardFont(JBFont.small()))) }
        }
        add(heading, BorderLayout.NORTH)
        stickyHeaders.add(this, heading)
        markResult(this, item.key)
        toolTipText = item.tooltip
        if (item.placeholder) return@apply
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val card = this
        // Children cover most of the card, so each forwards hover and clicks; leaving into a child is not leaving the card.
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { if (hovered != item) { hovered = item; applyActive() } }
            override fun mouseExited(e: MouseEvent) {
                if (hovered == item && !card.contains(SwingUtilities.convertPoint(e.component, e.point, card))) { hovered = null; applyActive() }
            }
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) { select(item); applyActive() } }
        }
        fun attach(component: Component) {
            if (component is ActionLink) return
            component.addMouseListener(listener)
            (component as? JComponent)?.toolTipText = item.tooltip
            if (component is Container) component.components.forEach(::attach)
        }
        attach(this)
    }

    private fun frame(active: Boolean): Border? =
        if (active) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    /** A left rule beside the summary; both states keep the same insets so the text never shifts. */
    private fun noteBorder(active: Boolean): Border? = JBUI.Borders.compound(
        MatteBorder(0, cardScaled(2), 0, 0, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
        pixelBorder(cardScaled(2), cardScaled(NOTE_INSET), cardScaled(2), 0),
    )

    private fun railBorder(item: RailItem, active: Boolean): Border? = JBUI.Borders.compound(
        MatteBorder(1, 1, 1, 1, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
        MatteBorder(0, cardScaled(RAIL_ACCENT), 0, 0, item.family?.let(::accent) ?: JBColor.border()),
        pixelBorder(cardScaled(6), cardScaled(8), cardScaled(6), cardScaled(6)),
    )


    protected fun text(value: String, width: Int, font: Font, color: Color?): JComponent = WrappedText(value, width).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
        this.font = font
        color?.let { foreground = it }
    }

    companion object {
        const val SUMMARY_WIDTH = 418
        const val MIN_DOC_WIDTH = 360
        const val DOC_WIDTH = 620
        const val COLUMN_GAP = 16
        const val SECTION_GAP = 12
        const val RAIL_CARD_WIDTH = 240
        const val RAIL_GAP = 6
        const val RAIL_ACCENT = 4
        const val NOTE_INSET = 10

        private fun scaledBy(zoom: Double, n: Int) = zoomScaled(n, zoom)
        /** Card geometry and typography must grow together, including during zoom out. */
        private fun cardScale(zoom: Double) = maxOf(zoom, 1.25 - 0.25 * zoom)
        private fun cardScaledBy(zoom: Double, n: Int) = scaledBy(cardScale(zoom), n)
        fun railWidth(depth: Int, zoom: Double) =
            if (depth <= 0) 0 else depth * cardScaledBy(zoom, RAIL_CARD_WIDTH) + (depth - 1) * scaledBy(zoom, RAIL_GAP)
        /** Unscaled width that fits the summaries, a readable diff column and every rail level. */
        fun defaultWidth(depth: Int) = SUMMARY_WIDTH + DOC_WIDTH + 2 * COLUMN_GAP +
            maxOf(depth, 1) * RAIL_CARD_WIDTH + (maxOf(depth, 1) - 1) * RAIL_GAP
        fun defaultWidth(depth: Int, zoom: Double): Int {
            val levels = maxOf(depth, 1)
            return cardScaledBy(zoom, SUMMARY_WIDTH) + scaledBy(zoom, DOC_WIDTH + 2 * COLUMN_GAP) +
                levels * cardScaledBy(zoom, RAIL_CARD_WIDTH) + (levels - 1) * scaledBy(zoom, RAIL_GAP)
        }

        /** One hue per top-level branch, so a subsystem reads as one family down the rail. */
        private val ACCENTS = listOf(
            JBColor(0x4C7DD9, 0x5F8FE6), JBColor(0x2E9E6B, 0x4DB383), JBColor(0xD9822B, 0xE09A50), JBColor(0xB04FC2, 0xC27AD1),
            JBColor(0xD04A5A, 0xDB6B78), JBColor(0x1F9AA8, 0x45B4C0), JBColor(0x8A7A2E, 0xB0A050), JBColor(0x6A62D4, 0x8A84E0),
        )
        internal fun accent(topLevel: String): Color = ACCENTS[Math.floorMod(topLevel.lowercase().hashCode(), ACCENTS.size)]
    }
}

/**
 * The review read as one document, like an article with its topic rail: every file in topic order down the middle,
 * its summary in the left margin and the topic hierarchy on the right, broad topics outermost. Each rail card spans exactly
 * the diffs of its files, and a section is as tall as the taller of its diff and summary, so the three columns stay aligned.
 */
internal class ChangeTopicsPanel(
    card: TopicsCard,
    private val state: ChangeTopicsViewState,
    width: Int,
    zoom: Double,
    private val actions: ChangeTopicActions,
    vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) : DocumentColumnsPanel(zoom, vocabulary) {
    val outline = state.outline(card)
    private val nodesByKey = outline.nodes.associateBy { "${it.level}:${it.identity}" }

    init {
        // Files awaiting a topic still get a placeholder in the leaf column.
        val depth = maxOf(state.railDepth(outline), if (outline.pending != null) 1 else 0)
        // A topic reads its own summary, else the summary of exactly its files, as in the hierarchy.
        val reading = card.reading
        val topicTexts = reading.tree.nodes.mapNotNull { node -> reading.text(node.id)?.let { node.identity to it } }.toMap()
        val items = outline.nodes.filter { it.level < depth }.map { node ->
            RailItem(
                key = "${node.level}:${node.identity}",
                name = "topic:${node.level}:${node.identity}",
                title = node.name,
                family = node.path.first(),
                level = node.level,
                first = node.first,
                last = node.last,
                tooltip = node.fullPath,
                summary = topicTexts[ChangeTopicsViewState.identity(node)],
                summaryLabel = "Topic summary",
            )
        } + listOfNotNull(outline.pending?.let { pending ->
            RailItem(
                key = "pending",
                name = "topic:pending",
                title = "Awaiting topic",
                family = null,
                level = depth - 1,
                first = pending.first,
                last = pending.last,
                tooltip = "Run /topics to classify these files",
                placeholder = true,
            )
        })
        val summaries = card.summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to card.summaries.text(it.id) }
        layoutDocument(outline.files, summaries, items, depth, width)
    }

    override fun openFile(file: ReviewSource) = actions.openFile(file.path)
    override fun showFile(file: ReviewSource) = actions.showDiffCard(file.path)
    override fun summaryBody(identity: String, summary: String?, missingSummary: String, width: Int): JComponent =
        (if (summary != null) WrappedSummaryText(summary, width, summaryFont(), emptyMap())
            else super.summaryBody(identity, summary, missingSummary, width)).also { area ->
            val readingIdentity = nodesByKey[identity]?.let(ChangeTopicsViewState::identity) ?: identity
            if (summary != null) installWordTracing(area as javax.swing.text.JTextComponent, readingIdentity, summary, state, actions.trace)
        }.let { area ->
            val readingIdentity = nodesByKey[identity]?.let(ChangeTopicsViewState::identity) ?: identity
            actions.review?.body(readingIdentity, area) ?: area
        }
    override fun isSelected(item: RailItem): Boolean = nodesByKey[item.key]?.let(state::isSelected) == true
    override fun select(item: RailItem) { nodesByKey[item.key]?.let(state::select) }

    companion object {
        fun defaultWidth(depth: Int, zoom: Double) = DocumentColumnsPanel.defaultWidth(depth, zoom)
        internal fun accent(topLevel: String): Color = DocumentColumnsPanel.accent(topLevel)
    }
}

/**
 * A unified patch painted as editor-styled lines with old and new line numbers. It paints only the visible rows, so a long
 * document of many files stays cheap; very long patches stop at [MAX_LINES] and link to the full diff.
 */
internal class PatchView(patch: String, viewWidth: Int, zoom: Double) : JComponent() {
    private enum class Kind { CONTEXT, ADDED, REMOVED, HUNK, NOTE }
    private class Line(val text: String, val kind: Kind, val old: Int?, val new: Int?)

    private val lines: List<Line>
    val hiddenLines: Int
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val lineFont: Font = scheme.getFont(EditorFontType.PLAIN).deriveFont((scheme.editorFontSize2D * zoom).toFloat())
    private val metrics = getFontMetrics(lineFont)
    private val lineHeight = metrics.height
    private val pad = (JBUI.scale(4) * zoom).roundToInt()
    private val numberWidth: Int

    init {
        val all = patch.split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        var old = 0
        var new = 0
        var inHunk = false
        val parsed = all.map { raw ->
            val text = raw.replace("\t", "    ")
            val hunk = HUNK.find(raw)
            when {
                hunk != null -> {
                    old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); inHunk = true
                    Line(text, Kind.HUNK, null, null)
                }
                !inHunk || raw.startsWith("[") -> Line(text, Kind.NOTE, null, null)
                raw.startsWith("+") -> Line(text, Kind.ADDED, null, new++)
                raw.startsWith("-") -> Line(text, Kind.REMOVED, old++, null)
                else -> Line(text, Kind.CONTEXT, old++, new++)
            }
        }
        lines = parsed.take(MAX_LINES)
        hiddenLines = parsed.size - lines.size
        val largest = lines.maxOfOrNull { maxOf(it.old ?: 0, it.new ?: 0) } ?: 0
        numberWidth = if (lines.none { it.old != null || it.new != null }) 0 else metrics.stringWidth("0".repeat(maxOf(3, largest.toString().length)))
        font = lineFont
        preferredSize = Dimension(viewWidth, maxOf(lines.size, 1) * lineHeight + 2 * pad)
    }

    val lineCount: Int get() = lines.size

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = lineFont
            g2.color = scheme.defaultBackground
            g2.fillRect(0, 0, width, height)
            val clip = g2.clipBounds ?: java.awt.Rectangle(0, 0, width, height)
            val gutter = if (numberWidth == 0) 0 else 2 * numberWidth + 3 * pad
            val from = ((clip.y - pad) / lineHeight).coerceAtLeast(0)
            val to = ((clip.y + clip.height - pad) / lineHeight + 1).coerceAtMost(lines.size)
            if (lines.isEmpty()) {
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString("No textual changes", pad, pad + metrics.ascent)
            }
            for (index in from until to) {
                val line = lines[index]
                val top = pad + index * lineHeight
                background(line.kind)?.let { g2.color = it; g2.fillRect(0, top, width, lineHeight) }
                val baseline = top + metrics.ascent
                if (gutter > 0) {
                    g2.color = UIUtil.getContextHelpForeground()
                    line.old?.let { g2.drawString(it.toString(), pad + numberWidth - metrics.stringWidth(it.toString()), baseline) }
                    line.new?.let { g2.drawString(it.toString(), 2 * pad + 2 * numberWidth - metrics.stringWidth(it.toString()), baseline) }
                }
                g2.color = when (line.kind) {
                    Kind.HUNK, Kind.NOTE -> UIUtil.getContextHelpForeground()
                    else -> scheme.defaultForeground
                }
                // Long lines are clipped at the column; resizing the card or the full diff shows the rest.
                g2.drawString(line.text, gutter + pad, baseline)
            }
        } finally { g2.dispose() }
    }

    private fun background(kind: Kind): Color? = when (kind) {
        Kind.ADDED -> scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor ?: JBColor(0xDDF5E3, 0x2D4535)
        Kind.REMOVED -> scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor ?: JBColor(0xF9DEDE, 0x4D3134)
        Kind.HUNK -> JBColor(0xEEF2F8, 0x2E3239)
        else -> null
    }

    /** Test hook: the painted rows as `kind old new text`. */
    internal fun describe(): List<String> = lines.map { "${it.kind.name.lowercase()} ${it.old ?: "-"} ${it.new ?: "-"} ${it.text}" }

    companion object {
        const val MAX_LINES = 400
        private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    }
}
