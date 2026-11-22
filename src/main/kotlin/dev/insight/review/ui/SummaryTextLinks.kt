package dev.insight.review.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.util.ui.JBUI
import dev.insight.chat.openReportSelectionChat
import dev.insight.context.ContextPick
import dev.insight.context.ReportSelectionActions
import dev.insight.context.ReviewDestination
import dev.insight.context.showContextPicker
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolMention
import dev.insight.review.LineLinkMatch
import dev.insight.review.ReportLink
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReviewSource
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.Shape
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.text.LayeredHighlighter
import javax.swing.text.Position
import javax.swing.text.View

/** Where the links of a summary lead, and what its selection's Context picker can open them on. */
internal class SummaryTextLinkContext(
    val navigator: ContextualLinkedTextNavigator,
    val reviewDestinations: () -> Map<ReviewDestination, () -> Unit> = { emptyMap() },
    val reviewFiles: List<ReviewSource> = emptyList(),
    val openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    /** Review actions for a file link resolved to project-relative path, such as opening its diff; listed after the link's own. */
    val fileItems: (path: String, lines: IntRange?) -> List<JMenuItem> = { _, _ -> emptyList() },
)

/** What a summary's own pointer handling asks of its links: whether a point is on one, and their menu items. */
internal class SummaryTextLinkHook(
    val linkAt: (Point) -> Boolean,
    /** Adds the items for the link at the point (null: at the caret) and for the selection; returns whether it added any. */
    val addItems: (JPopupMenu, Point?) -> Boolean,
    /** The links shown, as spans of the text. */
    val links: () -> List<LineLinkMatch> = { emptyList() },
    /** Follows a link as a click does, or in the editor. */
    val follow: (LineLinkMatch, Boolean) -> Unit = { _, _ -> },
)

private val SUMMARY_TEXT_LINKS = Key.create<SummaryTextLinkHook>("insight.summary.textLinks")

internal fun summaryTextLinks(area: JComponent): SummaryTextLinkHook? = ClientProperty.get(area, SUMMARY_TEXT_LINKS)

/**
 * Links the file paths, line references, URLs and code symbols of the plain [text] shown in [area], the way the report viewer
 * links a report, without changing the text or its character offsets, so word tracing, word sizes and review marks keep
 * working. Links are underlined; a click follows one through [context]'s navigator (Ctrl/Cmd+click opens the editor),
 * Shift+click offers its Context picker, and the context menu adds the link's items and, for selected text, the report
 * viewer's selection picker with a temporary chat and every file the selection links to. Links are found in the background
 * and again when the project's files or names change while [area] is in a window.
 */
internal fun installSummaryTextLinks(area: JTextComponent, text: String, project: Project, context: SummaryTextLinkContext) {
    if (project.isDisposed || text.isBlank()) return
    val links = project.service<LinkedTextLinks>()
    val navigator = context.navigator
    var shown: PlainTextLinks? = null
    val highlights = mutableListOf<Any>()
    val follower = LinkedTextFollower(project, links.coroutineScope, area, { navigator }, { shown?.index })
    var finding: Job? = null

    fun draw(next: PlainTextLinks) {
        if (next === shown) return
        // A rebuilt or edited text no longer matches the offsets the links were found at.
        if (area.document.length != text.length || area.document.getText(0, area.document.length) != text) return
        shown = next
        highlights.forEach(area.highlighter::removeHighlight)
        highlights.clear()
        for (match in next.matches) {
            if (match.end <= area.document.length) highlights += area.highlighter.addHighlight(match.start, match.end, LinkUnderline)
        }
        area.repaint()
    }

    fun load() {
        val defaultFile = navigator.defaultFile()
        val extras = navigator.extraFiles()
        links.cachedPlain(text, defaultFile, extras)?.let { draw(it); return }
        if (finding?.isActive == true) return
        finding = links.coroutineScope.launch(Dispatchers.EDT) {
            while (true) {
                val next = try {
                    links.findPlain(text, defaultFile, extras)
                } catch (e: CancellationException) {
                    // Links found the old way were dropped; look again unless the summary stopped showing.
                    currentCoroutineContext().ensureActive()
                    continue
                }
                draw(next)
                break
            }
        }
    }
    val changed: () -> Unit = { load() }
    var listening = false
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) links.addListener(changed) else links.removeListener(changed)
    }
    // Like a LinkedTextPane, the summary follows changes to the project for as long as it is in a window.
    area.addHierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L) return@addHierarchyListener
        if (area.isDisplayable) { listen(true); load() } else { listen(false); finding?.cancel(); finding = null }
    }
    if (area.isDisplayable) listen(true)
    load()

    fun matchAt(point: Point): LineLinkMatch? {
        val current = shown ?: return null
        val offset = area.viewToModel2D(point).takeIf { it >= 0 } ?: return null
        val match = current.at(offset) ?: current.at(offset - 1) ?: return null
        // The nearest offset is not enough; the pointer must be on the link's characters.
        val start = area.modelToView2D(match.start) ?: return null
        val end = area.modelToView2D(match.end) ?: return null
        val inside = if (start.y == end.y) point.x >= start.x && point.x <= end.x && point.y >= start.y && point.y <= start.maxY
            else point.y >= start.y && point.y <= end.maxY &&
                (point.y > start.maxY || point.x >= start.x) && (point.y < end.y || point.x <= end.x)
        return match.takeIf { inside }
    }

    fun showSelectionContext(point: Point) {
        val current = shown
        val start = area.selectionStart
        val end = area.selectionEnd
        val selected = area.selectedText?.takeIf { start != end } ?: return
        val inSelection = current?.matches.orEmpty().filter { it.start < end && it.end > start }.map { it.link }
        val pick = reportSelectionContextPick(
            ReportTextSelection(start, end, selected),
            ReportSelectionActions(openChat = { excerpt -> if (!project.isDisposed) openReportSelectionChat(project, excerpt, project) }),
            context.reviewDestinations(), inSelection,
            absolutePath = { current?.index?.absolutePathFor(it) },
            reviewFiles = context.reviewFiles, openOnWall = context.openOnWall, label = "Selected summary text",
        )
        showContextPicker(project, pick, area, RelativePoint(area, point))
    }

    fun addItems(menu: JPopupMenu, point: Point?): Boolean {
        val before = menu.componentCount
        val hasSelection = area.selectionStart != area.selectionEnd
        val at = point ?: area.modelToView2D(area.caretPosition)?.bounds?.location ?: Point(0, 0)
        if (hasSelection) menu.add(JMenuItem("Context…").apply {
            name = "summary-selection-action:Context"
            toolTipText = "Preview the selected text, chat about it, or open every file it links to"
            addActionListener { showSelectionContext(at) }
        })
        val link = (if (point != null) matchAt(point) else shown?.at(area.caretPosition))?.link
        if (link != null) {
            if (menu.componentCount > before) menu.addSeparator()
            follower.addMenuItems(menu, link, at, hasSelection)
            val path = link.primaryPath
            if (link.kind == ReportTargetKind.FILE && path != null && !link.isAmbiguous)
                context.fileItems(path, link.lines).forEach(menu::add)
        }
        return menu.componentCount > before
    }

    ClientProperty.put(area, SUMMARY_TEXT_LINKS, SummaryTextLinkHook({ matchAt(it) != null }, ::addItems, { shown?.matches.orEmpty() },
        { match, inEditor -> follower.activate(match.link, Point(0, 0), shift = false, inEditor = inEditor) }))

    val pointer = object : MouseAdapter() {
        private var hovered: LineLinkMatch? = null
        private var savedTooltip: String? = null

        override fun mouseMoved(e: MouseEvent) {
            val match = matchAt(e.point)
            if (match === hovered) return
            if (hovered == null) savedTooltip = area.toolTipText
            hovered = match
            if (match != null) {
                area.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                area.toolTipText = follower.tooltip(match.link)
            } else {
                area.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                area.toolTipText = savedTooltip
            }
        }

        override fun mouseExited(e: MouseEvent) {
            if (hovered == null) return
            hovered = null
            area.toolTipText = savedTooltip
        }

        override fun mouseClicked(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1 || area.selectionStart != area.selectionEnd) return
            val match = matchAt(e.point) ?: return
            // The entry and card listeners skip consumed clicks, so following a link never also selects the entry.
            e.consume()
            follower.activate(match.link, e.point, shift = e.isShiftDown, inEditor = e.isControlDown || e.isMetaDown)
        }

        override fun mousePressed(e: MouseEvent) = popup(e)
        override fun mouseReleased(e: MouseEvent) = popup(e)

        /** A traced summary shows its own word menu, which includes these items; any other shows them above the entry's. */
        private fun popup(e: MouseEvent) {
            if (!e.isPopupTrigger || e.isConsumed || !area.isShowing || hasSelectionMenu(area)) return
            val menu = JPopupMenu()
            val linked = addItems(menu, e.point)
            val entry = summaryEntryMenuItems(area)
            if (!linked && entry == null) return
            if (entry != null) {
                if (linked) menu.addSeparator()
                entry.components.toList().forEach(menu::add)
            }
            menu.show(area, e.x, e.y)
            e.consume()
        }
    }
    area.addMouseListener(pointer)
    area.addMouseMotionListener(pointer)
}

/** A thin underline in the link color, drawn under the text so its colors stay those of tracing and review. */
private object LinkUnderline : LayeredHighlighter.LayerPainter() {
    private val color: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED

    override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) = Unit

    override fun paintLayer(g: Graphics, offs0: Int, offs1: Int, bounds: Shape, c: JTextComponent, view: View): Shape? {
        val shape = try {
            view.modelToView(offs0, Position.Bias.Forward, offs1, Position.Bias.Backward, bounds)
        } catch (e: Exception) {
            return null
        }
        val r: Rectangle = shape.bounds
        g.color = color
        val y = r.y + r.height - 1
        g.drawLine(r.x, y, r.x + r.width - 1, y)
        return r
    }
}

/**
 * Where the links of a summary lead in the Hierarchy and Document views: to the editor, as in the report viewer, preferring
 * among same-named files the summary's own [entryFiles], then the review's [scopeFiles], and binding bare line references to
 * [sourcePath]. [pick] supplies the review's Context picker for a link resolved to a path.
 */
internal fun summaryTextNavigator(
    project: Project,
    sourcePath: String?,
    entryFiles: List<String>,
    scopeFiles: List<String>,
    isDiffReview: Boolean,
    pick: (ReportLink, String, String?) -> ContextPick?,
): ContextualLinkedTextNavigator = object : ContextualLinkedTextNavigator {
    private val entry = entryFiles.toSet()
    private val scope = scopeFiles.toSet()

    override fun defaultFile(): String? = sourcePath

    override fun extraFiles(): List<String> = scopeFiles

    override fun contextFiles(): List<String> = (listOfNotNull(sourcePath) + entryFiles + scopeFiles).distinct()

    override fun preferredFile(candidates: List<String>): String? = summaryPreferredFile(candidates, sourcePath, entry, scope)

    override fun rankFiles(candidates: List<String>): List<String> = summaryRankFiles(candidates, sourcePath, entry, scope)

    override fun linkTooltip(link: ReportLink): String =
        dev.insight.review.linkTooltip(link, inReviewScope = link.primaryPath in scope, isDiffReview = isDiffReview)

    override fun contextPick(link: ReportLink, path: String, absolutePath: String?): ContextPick? = pick(link, path, absolutePath)

    override fun openFile(path: String, absolutePath: String?, lines: IntRange?, column: Int?) {
        openReportFile(project, path, lines, column, absolutePath)
    }

    override fun openDeclaration(declaration: SymbolCandidate, mention: SymbolMention) {
        openReportFile(project, declaration.path, declaration.line..declaration.line, declaration.column, declaration.absolutePath)
    }
}

/**
 * The file among ambiguous [candidates] a summary means without asking: its [sourcePath], else the single one among the
 * entry's files, else the single one in the review's scope; null when a choice remains.
 */
internal fun summaryPreferredFile(candidates: List<String>, sourcePath: String?, entryFiles: Set<String>, scopeFiles: Set<String>): String? {
    if (sourcePath != null && sourcePath in candidates) return sourcePath
    for (tier in listOf(entryFiles, scopeFiles)) {
        val matching = candidates.filter { it in tier }
        if (matching.size == 1) return matching.single()
        if (matching.size > 1) return null
    }
    return null
}

/** Ambiguous [candidates] ordered for the chooser: the summary's own file, its entry's files, the review's scope, the rest. */
internal fun summaryRankFiles(candidates: List<String>, sourcePath: String?, entryFiles: Set<String>, scopeFiles: Set<String>): List<String> =
    candidates.sortedBy { path ->
        when {
            path == sourcePath -> 0
            path in entryFiles -> 1
            path in scopeFiles -> 2
            else -> 3
        }
    }
