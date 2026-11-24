package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.LineChartMetric
import dev.insight.review.OutlineDeclaration
import dev.insight.review.RadarSymbolKind
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSource
import dev.insight.review.dotLeader
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.AnimatedIcon
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

internal enum class LineChartPreviewKind { SOURCE, SUMMARY, CIRCLES, RADAR }

internal data class LineChartPreview(val path: String, val kind: LineChartPreviewKind)

/**
 * A number ladder for one directory, with foldable subdirectories, and parsed files that unfold into their declarations.
 * One row is current: hovering or focusing a row
 * moves it, and the arrow keys move it, fold and unfold folders, while Enter explores or opens the current row.
 */
internal class LineChartPanel(
    private val chart: ReviewLineChart,
    path: String?,
    rootLabel: String,
    private val navigate: (String) -> Unit,
    private val open: (String) -> Unit,
    private val contextMenu: ((ReviewLineChart.Entry) -> JPopupMenu)? = null,
    private val preview: LineChartPreview? = null,
    private val togglePreview: (LineChartPreview) -> Unit = {},
    expanded: Set<String> = emptySet(),
    private val expansionChanged: (Set<String>) -> Unit = {},
    cursorPath: String? = null,
    private val cursorChanged: (String?) -> Unit = {},
    /** Opens a declaration's file at its first line. */
    private val openAt: (String, Int) -> Unit = { path, _ -> open(path) },
    /** Parses a file's outline in the background, then calls back; null keeps files as leaves. */
    private val loadOutline: ((ReviewSource, () -> Unit) -> Unit)? = null,
    private val canSummarizeDeclaration: (OutlineDeclaration) -> Boolean = { false },
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private val metric = chart.metric
    private val outlineHint = loadOutline != null && metric.outlines
    private val numberColor get() = metric.diffSeries?.let { RadarChartPanel.color(it) } ?: UIUtil.getLabelForeground()
    val directory = chart.directory(path)
    private var expandedPaths = expanded
    private var rows: List<ReviewLineChart.Row> = emptyList()
    private var rowComponents: List<JComponent> = emptyList()
    /** Files whose outline this panel asked for, so each is parsed once. */
    private val requested = mutableSetOf<String>()
    private var current = -1

    /** The path of the current row, or null when the directory is empty. */
    val cursorPath: String? get() = rows.getOrNull(current)?.entry?.path

    private val list = JPanel(VerticalLayout(JBUI.scale(2))).apply {
        name = "charts-lines-rows"
        isOpaque = false
    }

    /** The previewed declaration, with its captured file. */
    fun previewDeclaration(): Pair<ReviewSource, OutlineDeclaration>? =
        preview?.let { chart.declaration(it.path) }?.let { entry -> chart.source(entry.filePath)?.let { it to entry.declaration!! } }

    fun previewFiles(): List<ReviewSource> = preview?.takeIf { it.kind == LineChartPreviewKind.SOURCE && chart.declaration(it.path) == null }
        ?.let { chart.files(it.path) }.orEmpty()

    init {
        name = "charts-lines"
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "${metric.label} chart"
        add(JPanel(VerticalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                name = "charts-lines-breadcrumbs"
                isOpaque = false
                chart.breadcrumbs(directory).forEachIndexed { index, entry ->
                    if (index > 0) add(JLabel(" / ").apply { font = chartFont(font); foreground = UIUtil.getContextHelpForeground() })
                    val title = if (index == 0) rootLabel + entry.path.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() else entry.label
                    val activeCrumb = entry.path == preview?.path
                    add((if (entry.path == directory.path) JLabel(title) else ActionLink(title) { explore(entry.path) }).apply {
                        name = "charts-lines-crumb:${entry.path}"
                        toolTipText = entry.path.ifEmpty { rootLabel } + if (activeCrumb) " · Showing in preview" else ""
                        font = chartFont(font).let { if (activeCrumb) it.deriveFont(it.style or Font.BOLD) else it }
                        if (activeCrumb) {
                            border = JBUI.Borders.compound(
                                JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 1),
                                JBUI.Borders.empty(1, 4),
                            )
                        }
                        componentPopupMenu = contextMenu?.invoke(entry)
                    })
                }
            })
            add(JLabel(valueText(directory.value) + if (metric == LineChartMetric.ITEMS) "" else " · " + fileCount(directory)).apply {
                name = "charts-lines-total"
                font = chartFont(JBFont.small())
                foreground = numberColor
                toolTipText = metric.description
            })
            add(JLabel(if (outlineHint) "Click a directory to explore or unfold a file for its declarations · ↑↓ move, ←→ fold, Enter opens"
                else "Click a directory to explore · ↑↓ move, ←→ fold, Enter opens").apply {
                font = chartFont(JBFont.small())
                foreground = UIUtil.getContextHelpForeground()
            })
        }, BorderLayout.NORTH)
        add(list, BorderLayout.CENTER)
        rebuildRows(cursorPath)
        installKeys()
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaintRow(current)
            override fun focusLost(e: FocusEvent) = repaintRow(current)
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { requestFocusInWindow() }
        })
    }

    /** Folds or unfolds the directory, file or declaration at [path], keeping the current row. */
    fun toggleExpanded(path: String) {
        expandedPaths = if (path in expandedPaths) expandedPaths - path else expandedPaths + path
        rebuildRows(cursorPath?.takeUnless { ReviewLineChart.isBeneath(it, path) } ?: path)
        expansionChanged(expandedPaths)
    }

    /** Moves the current row to [index], clamped to the visible rows. */
    fun moveCursor(index: Int, reveal: Boolean = true) {
        if (rows.isEmpty()) return
        val next = index.coerceIn(0, rows.lastIndex)
        if (next == current) return
        val previous = current
        current = next
        repaintRow(previous)
        repaintRow(next)
        if (reveal) rowComponents[next].let { it.scrollRectToVisible(java.awt.Rectangle(0, 0, it.width, it.height)) }
        cursorChanged(cursorPath)
    }

    private fun rebuildRows(cursor: String?) {
        rows = chart.rows(directory, expandedPaths)
        list.removeAll()
        if (rows.isEmpty()) list.add(JLabel(if (metric.hidesEmpty) "No ${metric.plural} here" else "No files in scope").apply { font = chartFont(font) })
        rowComponents = rows.mapIndexed { index, row -> row(row, index).also(list::add) }
        current = rows.indexOfFirst { it.entry.path == cursor }.takeIf { it >= 0 } ?: if (rows.isEmpty()) -1 else 0
        list.revalidate()
        list.repaint()
        requestOutlines()
    }

    /** Parses the unfolded files still shown as "Parsing…", then lists their declarations in place. */
    private fun requestOutlines() {
        val load = loadOutline ?: return
        for (row in rows) {
            if (row.entry.kind != ReviewLineChart.Kind.PLACEHOLDER || !requested.add(row.entry.filePath)) continue
            val source = chart.source(row.entry.filePath) ?: continue
            load(source) {
                // The current placeholder hands the cursor back to its file.
                val cursor = rows.getOrNull(current)?.entry?.let { if (it.kind == ReviewLineChart.Kind.PLACEHOLDER) it.filePath else it.path }
                rebuildRows(cursor)
            }
        }
    }

    private fun repaintRow(index: Int) { rowComponents.getOrNull(index)?.repaint() }

    private fun focusedWithin(): Boolean {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(owner, this)
    }

    /** Explores [path], keeping the child that leads back toward the shown directory current. */
    private fun explore(path: String) {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val back = directory.path.takeIf { it != path && it.startsWith(prefix) }
            ?.let { prefix + it.removePrefix(prefix).substringBefore('/') }
        cursorChanged(back)
        navigate(path)
    }

    private fun activate(entry: ReviewLineChart.Entry) = when (entry.kind) {
        ReviewLineChart.Kind.DIRECTORY -> explore(entry.path)
        ReviewLineChart.Kind.FILE -> open(entry.path)
        ReviewLineChart.Kind.DECLARATION -> openAt(entry.filePath, entry.declaration!!.lines.first)
        ReviewLineChart.Kind.PLACEHOLDER -> Unit
    }

    private fun installKeys() {
        fun bind(key: Int, id: String, action: () -> Unit) {
            val stroke = KeyStroke.getKeyStroke(key, 0)
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, id)
            actionMap.put(id, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
        }
        bind(KeyEvent.VK_UP, "charts-lines-up") { moveCursor(current - 1) }
        bind(KeyEvent.VK_DOWN, "charts-lines-down") { moveCursor(current + 1) }
        bind(KeyEvent.VK_HOME, "charts-lines-first") { moveCursor(0) }
        bind(KeyEvent.VK_END, "charts-lines-last") { moveCursor(rows.lastIndex) }
        bind(KeyEvent.VK_PAGE_UP, "charts-lines-page-up") { moveCursor(current - PAGE_ROWS) }
        bind(KeyEvent.VK_PAGE_DOWN, "charts-lines-page-down") { moveCursor(current + PAGE_ROWS) }
        bind(KeyEvent.VK_RIGHT, "charts-lines-unfold") {
            val row = rows.getOrNull(current) ?: return@bind
            if (row.entry.expandable && !row.expanded) toggleExpanded(row.entry.path)
            else if (row.expanded) moveCursor(current + 1)
        }
        bind(KeyEvent.VK_LEFT, "charts-lines-fold") {
            val row = rows.getOrNull(current) ?: return@bind
            when {
                row.expanded -> toggleExpanded(row.entry.path)
                row.depth > 0 -> moveCursor(rows.parentIndex(current) { it.depth == row.depth - 1 })
                directory.path != chart.root.path -> explore(directory.path.substringBeforeLast('/', ""))
            }
        }
        bind(KeyEvent.VK_BACK_SPACE, "charts-lines-parent") {
            if (directory.path != chart.root.path) explore(directory.path.substringBeforeLast('/', ""))
        }
        // Enter and Space on the panel itself; a focused link or toggle keeps its own activation keys.
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "charts-lines-activate")
        actionMap.put("charts-lines-activate", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { rows.getOrNull(current)?.let { activate(it.entry) } }
        })
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "charts-lines-preview")
        actionMap.put("charts-lines-preview", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                rows.getOrNull(current)?.let { togglePreview(LineChartPreview(it.entry.path, LineChartPreviewKind.SOURCE)) }
            }
        })
    }

    private fun List<ReviewLineChart.Row>.parentIndex(before: Int, predicate: (ReviewLineChart.Row) -> Boolean): Int =
        (before - 1 downTo 0).firstOrNull { predicate(this[it]) } ?: before

    private fun row(row: ReviewLineChart.Row, index: Int): JComponent = object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            // The current row spans the whole line, so the reader can follow it from the number to the toggles.
            if (index == current && !isOpaque) {
                g.color = if (focusedWithin()) JBUI.CurrentTheme.List.Selection.background(false)
                    else JBUI.CurrentTheme.List.Hover.background(true)
                g.fillRect(0, 0, width, height)
            }
        }
    }.apply {
        val entry = row.entry
        val active = preview?.path == entry.path
        name = "charts-lines-row:${entry.path}"
        isOpaque = active
        if (active) background = UIUtil.getListSelectionBackground(false)
        border = if (active) {
            JBUI.Borders.compound(
                JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 1),
                JBUI.Borders.empty(3, 6),
            )
        } else JBUI.Borders.empty(4, 7)
        componentPopupMenu = contextMenu?.invoke(entry)
        val placeholder = entry.kind == ReviewLineChart.Kind.PLACEHOLDER
        val declaration = entry.declaration
        val where = declaration?.let { "${entry.filePath}:${it.lines.first}" } ?: entry.path
        val tooltip = if (placeholder) "Parsing ${entry.filePath}" else "$where · ${valueText(entry.value)}" + when {
            declaration != null -> " · ${declaration.kind.unit}, lines ${declaration.lines.first}–${declaration.lines.last} · Open at its line"
            entry.directory && metric != LineChartMetric.ITEMS -> " · ${fileCount(entry)} · Click to explore"
            entry.directory -> " · Click to explore"
            metric.diffSeries == null -> " · Open file"
            else -> " · View diff"
        }
        add(object : JComponent() {
            init {
                inheritsPopupMenu = true
                name = "charts-lines-count:${entry.path}"
                foreground = numberColor
                preferredSize = Dimension(JBUI.scale(288), JBUI.scale(29))
                toolTipText = tooltip
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (placeholder) return
                // A shared monospace grid anchored at the right edge keeps digits, separators, and dots aligned across rows.
                g.font = numberFont()
                val metrics = g.fontMetrics
                val cell = metrics.charWidth('0').coerceAtLeast(1)
                val text = number(entry.value)
                val line = dotLeader(text, width / cell)
                val leader = line.length - text.length
                val x = width - line.length * cell
                val y = (height - metrics.height) / 2 + metrics.ascent
                UISettings.setupAntialiasing(g)
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(line.substring(0, leader), x, y)
                g.color = foreground
                g.drawString(text, x + leader * cell, y)
            }
        }, BorderLayout.WEST)
        add(JPanel(BorderLayout(JBUI.scale(2), 0)).apply {
            isOpaque = false
            inheritsPopupMenu = true
            // Nested rows indent their labels only, so every number stays in one column.
            border = JBUI.Borders.emptyLeft(row.depth * INDENT)
            add(foldToggle(row), BorderLayout.WEST)
            val label: JComponent = if (placeholder) JLabel(entry.label, AnimatedIcon.Default.INSTANCE, JLabel.LEADING).apply {
                foreground = UIUtil.getContextHelpForeground()
            } else ActionLink(entry.label + if (entry.directory) "/  ›" else "") { activate(entry) }.apply {
                icon = when (entry.kind) {
                    ReviewLineChart.Kind.DIRECTORY -> AllIcons.Nodes.Folder
                    ReviewLineChart.Kind.DECLARATION -> declarationIcon(declaration!!.kind)
                    else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.label).icon ?: AllIcons.FileTypes.Any_type
                }
                val kind = declaration?.kind?.unit ?: if (entry.directory) "directory" else "file"
                accessibleContext.accessibleName = "${entry.label}, ${valueText(entry.value)}, $kind"
            }
            label.apply {
                font = chartFont(font).let { if (active) it.deriveFont(it.style or Font.BOLD) else it }
                inheritsPopupMenu = true
                name = "charts-lines-entry:${entry.path}"
                toolTipText = tooltip
            }
            add(label, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            inheritsPopupMenu = true
            val location = declaration?.let { "${it.name} (${entry.filePath}:${it.lines.first})" } ?: entry.path
            if (!placeholder && (declaration == null || canSummarizeDeclaration(declaration))) {
                add(previewToggle(entry.path, LineChartPreviewKind.SUMMARY, location))
            }
            if (!placeholder) add(previewToggle(entry.path, LineChartPreviewKind.SOURCE, location))
            if (entry.kind == ReviewLineChart.Kind.FILE || entry.kind == ReviewLineChart.Kind.DIRECTORY) {
                add(previewToggle(entry.path, LineChartPreviewKind.CIRCLES, location))
                add(previewToggle(entry.path, LineChartPreviewKind.RADAR, location))
            }
        }, BorderLayout.EAST)
        trackCursor(this, index)
    }

    private fun declarationIcon(kind: RadarSymbolKind): Icon = when (kind) {
        RadarSymbolKind.CLASS -> AllIcons.Nodes.Class
        RadarSymbolKind.INTERFACE -> AllIcons.Nodes.Interface
        RadarSymbolKind.ENUM -> AllIcons.Nodes.Enum
        RadarSymbolKind.STRUCT -> AllIcons.Nodes.Record
        RadarSymbolKind.OBJECT -> AllIcons.Nodes.Static
        RadarSymbolKind.TYPE_ALIAS -> AllIcons.Nodes.Type
        RadarSymbolKind.CONSTRUCTOR, RadarSymbolKind.FUNCTION, RadarSymbolKind.ASYNC -> AllIcons.Nodes.Method
        RadarSymbolKind.TEST -> AllIcons.Nodes.Test
        RadarSymbolKind.LAMBDA -> AllIcons.Nodes.Lambda
        RadarSymbolKind.PROPERTY -> AllIcons.Nodes.Property
        RadarSymbolKind.CONSTANT -> AllIcons.Nodes.Constant
        RadarSymbolKind.VARIABLE, RadarSymbolKind.PARAMETER -> AllIcons.Nodes.Variable
        else -> AllIcons.Nodes.Tag
    }

    /** A chevron that folds or unfolds an entry with children; other entries keep an empty cell of the same width. */
    private fun foldToggle(row: ReviewLineChart.Row): JComponent {
        val size = Dimension(JBUI.scale(18), JBUI.scale(18))
        if (!row.entry.expandable) return JPanel().apply { isOpaque = false; preferredSize = size; inheritsPopupMenu = true }
        val label = if (row.expanded) "Fold ${row.entry.path}" else "Unfold ${row.entry.path}"
        return JLabel(if (row.expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight).apply {
            name = "charts-lines-fold:${row.entry.path}"
            preferredSize = size
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = label
            accessibleContext.accessibleName = label
            inheritsPopupMenu = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    toggleExpanded(row.entry.path)
                    requestChartFocus()
                }
            })
        }
    }

    private fun requestChartFocus() { if (!focusedWithin()) requestFocusInWindow() }

    /** Hovering, pressing, or focusing any part of a row makes it the current row. */
    private fun trackCursor(component: Component, index: Int) {
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = moveCursor(index, reveal = false)
            override fun mouseMoved(e: MouseEvent) = moveCursor(index, reveal = false)
            override fun mousePressed(e: MouseEvent) {
                moveCursor(index, reveal = false)
                if (e.component is JPanel) requestChartFocus()
            }
        }
        fun attach(child: Component) {
            child.addMouseListener(mouse)
            child.addMouseMotionListener(mouse)
            if (child.isFocusable && child !is JPanel) child.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) { moveCursor(index); repaintRow(current) }
            })
            if (child is Container) child.components.forEach(::attach)
        }
        attach(component)
    }

    private fun previewToggle(path: String, mode: LineChartPreviewKind, location: String): JToggleButton {
        val selection = LineChartPreview(path, mode)
        val active = preview == selection
        val summary = mode == LineChartPreviewKind.SUMMARY
        val kind = when (mode) {
            LineChartPreviewKind.SUMMARY -> "summary"
            LineChartPreviewKind.SOURCE -> if (metric.diffSeries == null) "source" else "diff"
            LineChartPreviewKind.CIRCLES -> "circles chart"
            LineChartPreviewKind.RADAR -> "radar chart"
        }
        val label = if (active) "Hide $kind" else "Show $kind"
        return JToggleButton().apply {
            if (summary) {
                text = "S"
                font = JBFont.small().asBold()
                margin = JBUI.emptyInsets()
            } else icon = when (mode) {
                LineChartPreviewKind.CIRCLES, LineChartPreviewKind.RADAR -> chartPreviewIcon(mode)
                else -> AllIcons.Actions.Preview
            }
            isSelected = active
            name = when (mode) {
                LineChartPreviewKind.SUMMARY -> "charts-lines-summary-toggle:$path"
                LineChartPreviewKind.SOURCE -> "charts-lines-toggle:$path"
                LineChartPreviewKind.CIRCLES -> "charts-lines-circles-toggle:$path"
                LineChartPreviewKind.RADAR -> "charts-lines-radar-toggle:$path"
            }
            val size = Dimension(JBUI.scale(28), JBUI.scale(24))
            preferredSize = size; minimumSize = size; maximumSize = size
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = active
            isRolloverEnabled = true
            if (active) background = UIUtil.getListSelectionBackground(true)
            addChangeListener { if (!isSelected) isContentAreaFilled = model.isRollover }
            inheritsPopupMenu = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "$label of $location"
            accessibleContext.accessibleName = "$label of $location"
            addActionListener { togglePreview(selection) }
        }
    }

    private fun chartPreviewIcon(mode: LineChartPreviewKind): Icon = object : Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val graphics = g.create(x, y, iconWidth, iconHeight)
            try {
                graphics.color = c.foreground
                if (mode == LineChartPreviewKind.CIRCLES) {
                    graphics.drawOval(1, 1, iconWidth - 2, iconHeight - 2)
                    graphics.drawOval(iconWidth / 4, iconHeight / 4, iconWidth / 2, iconHeight / 2)
                } else {
                    val xs = intArrayOf(iconWidth / 2, iconWidth - 2, iconWidth - 4, 3, 1)
                    val ys = intArrayOf(1, iconHeight / 3, iconHeight - 2, iconHeight - 2, iconHeight / 3)
                    graphics.drawPolygon(xs, ys, xs.size)
                    graphics.drawLine(iconWidth / 2, 1, iconWidth / 2, iconHeight - 3)
                    graphics.drawLine(1, iconHeight / 3, iconWidth - 4, iconHeight - 2)
                    graphics.drawLine(iconWidth - 2, iconHeight / 3, 3, iconHeight - 2)
                }
            } finally { graphics.dispose() }
        }
    }

    private fun number(value: Long) = String.format(Locale.US, "%,d", value)

    private fun valueText(value: Long) = "${number(value)} ${metric.unitText(value)}"

    private fun fileCount(entry: ReviewLineChart.Entry) = "${number(entry.files.toLong())} ${if (entry.files == 1) "file" else "files"}"

    private fun chartFont(font: Font): Font = font.deriveFont(font.size2D * FONT_SCALE)

    /** Keep the number column 20% larger than the chart's labels. */
    private fun numberFont(): Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        .deriveFont(chartFont(JBFont.label()).size2D * 1.2f)

    private companion object {
        /** The chart's labels are 32% larger than the IDE's (a 10% base, then 20% more). */
        const val FONT_SCALE = 1.32f
        const val PAGE_ROWS = 10
        val INDENT get() = JBUI.scale(20)
    }
}
