package dev.insight.board.ui

import dev.insight.board.Annotation
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardRect
import dev.insight.board.ChainBlock
import dev.insight.board.ChainStep
import dev.insight.board.CodeBlock
import dev.insight.board.StepsBlock
import dev.insight.board.TableBlock
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.max

/**
 * A custom-painted block body whose height follows its width; a left click on a linked spot (a code line, chip, step or
 * cell) navigates to its anchor, and the cursor and tooltip show the link.
 */
internal abstract class PaintedView(protected val style: BoardStyle, private val navigate: (BoardAnchor) -> Unit) :
    JComponent(), HeightForWidth {
    init {
        isOpaque = false
        toolTipText = ""
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) clickAt(e.x, e.y)
            }

            override fun mouseMoved(e: MouseEvent) {
                cursor = Cursor.getPredefinedCursor(if (anchorAt(e.x, e.y) != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /** The anchor linked at [x], [y] in this view, if any. */
    abstract fun anchorAt(x: Int, y: Int): BoardAnchor?

    protected open fun tipAt(x: Int, y: Int): String? = anchorAt(x, y)?.let { "Open $it" }

    /** Clicks at [x], [y] as the reader would; returns whether it navigated. */
    fun clickAt(x: Int, y: Int): Boolean = anchorAt(x, y)?.also(navigate) != null

    override fun getToolTipText(event: MouseEvent): String? = tipAt(event.x, event.y)

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val width = if (width > 0) width else style.px(300)
        return Dimension(width, heightFor(width))
    }

    /** Remembers one computation per width, since layout measures and painting reads the same width repeatedly. */
    protected class PerWidth<T>(private val compute: (Int) -> T) {
        private var width = Int.MIN_VALUE
        private var value: T? = null

        operator fun invoke(width: Int): T {
            if (width != this.width || value == null) {
                value = compute(width)
                this.width = width
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    protected fun Graphics2D.arrow(x1: Int, x2: Int, y: Int, head: Int) {
        drawLine(x1, y, x2 - head / 2, y)
        val path = Path2D.Float()
        path.moveTo(x2.toFloat(), y.toFloat())
        path.lineTo((x2 - head).toFloat(), y - head / 2f)
        path.lineTo((x2 - head).toFloat(), y + head / 2f)
        path.closePath()
        fill(path)
    }
}

/** Host-resolved code: numbered lines in monospace, patch colours, and margin notes under their lines. */
internal class CodeView(private val block: CodeBlock, style: BoardStyle, navigate: (BoardAnchor) -> Unit) : PaintedView(style, navigate) {
    private class Piece(val y: Int, val height: Int, val row: CodeRow?, val note: Annotation?, val lines: List<String>)

    internal val rows: List<CodeRow> = codeRows(block)
    private val pad = style.px(6)
    private val bar = style.px(3)
    private val gutter: Int by lazy {
        val digits = (rows.mapNotNull { it.number }.maxOrNull() ?: 0).toString().length
        getFontMetrics(style.mono).charWidth('0') * max(digits, 1) + 2 * pad
    }

    private val pieces = PerWidth { width ->
        val lineHeight = getFontMetrics(style.mono).height
        val small = getFontMetrics(style.small)
        val notePad = style.px(3)
        var y = style.px(4)
        buildList {
            for (row in rows) {
                add(Piece(y, lineHeight, row, null, emptyList()))
                y += lineHeight
                for (note in row.annotations) {
                    val lines = wrapLines(note.text, width - gutter - 2 * pad - bar, small::stringWidth)
                    val height = lines.size * small.height + 2 * notePad
                    y += style.px(2)
                    add(Piece(y, height, null, note, lines))
                    y += height + style.px(2)
                }
            }
        }
    }

    override fun heightFor(width: Int): Int = (pieces(width).lastOrNull()?.let { it.y + it.height } ?: 0) + style.px(4)

    override fun anchorAt(x: Int, y: Int): BoardAnchor? {
        val number = pieces(width).firstOrNull { y >= it.y && y < it.y + it.height }?.row?.number ?: return null
        return BoardAnchor(block.anchor.path, number, number, block.anchor.side)
    }

    override fun tipAt(x: Int, y: Int): String? {
        val piece = pieces(width).firstOrNull { y >= it.y && y < it.y + it.height } ?: return null
        return piece.note?.text ?: piece.row?.number?.let { "Open line $it" }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            val arc = style.px(6).toFloat()
            g2.color = BoardColors.code
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = BoardColors.border
            g2.drawLine(gutter - pad / 2, 0, gutter - pad / 2, height)
            val mono = getFontMetrics(style.mono)
            val small = getFontMetrics(style.small)
            for (piece in pieces(width)) {
                val row = piece.row
                if (row != null) {
                    val tint = when (row.kind) {
                        CodeRowKind.ADDED -> BoardColors.added
                        CodeRowKind.REMOVED -> BoardColors.removed
                        CodeRowKind.HUNK -> BoardColors.hunk
                        CodeRowKind.CONTEXT -> null
                    }
                    if (tint != null) {
                        g2.color = tint
                        g2.fillRect(0, piece.y, width, piece.height)
                    }
                    g2.font = style.mono
                    row.number?.let {
                        val label = it.toString()
                        g2.color = BoardColors.muted
                        g2.drawString(label, gutter - pad - mono.stringWidth(label), piece.y + mono.ascent)
                    }
                    g2.color = if (row.kind == CodeRowKind.HUNK) BoardColors.muted else BoardColors.text
                    val clip = g2.create(gutter, piece.y, max(0, width - gutter - pad / 2), piece.height) as Graphics2D
                    try {
                        clip.drawString(row.text, 0, mono.ascent)
                    } finally {
                        clip.dispose()
                    }
                }
                val note = piece.note ?: continue
                val noteWidth = max(0, width - gutter - pad)
                g2.color = BoardColors.severityTint(note.severity)
                g2.fillRect(gutter, piece.y, noteWidth, piece.height)
                g2.color = BoardColors.severity(note.severity)
                g2.fillRect(gutter, piece.y, bar, piece.height)
                g2.font = style.small
                g2.color = BoardColors.text
                var y = piece.y + style.px(3) + small.ascent
                for (line in piece.lines) {
                    g2.drawString(line, gutter + bar + pad, y)
                    y += small.height
                }
            }
        } finally {
            g2.dispose()
        }
    }
}

/** A host-computed call path: one chip per step with arrows between, wrapping; "…" ends a path cut at a budget. */
internal class ChainView(private val block: ChainBlock, style: BoardStyle, navigate: (BoardAnchor) -> Unit) : PaintedView(style, navigate) {
    internal class ChainUnit(val arrow: BoardRect?, val chip: BoardRect, val step: ChainStep?, val label: String)

    private val padX = style.px(8)
    private val arrowWidth = style.px(22)

    private val units = PerWidth { width ->
        val metrics = getFontMetrics(style.body)
        val labels = block.steps.map { it.title } + if (block.complete) emptyList() else listOf("…")
        val widths = labels.mapIndexed { i, label -> (if (i > 0) arrowWidth else 0) + metrics.stringWidth(label) + 2 * padX }
        val height = metrics.height + style.px(8)
        flowLayout(widths, height, width, style.px(2), style.px(6)).mapIndexed { i, r ->
            val lead = if (i > 0) arrowWidth else 0
            ChainUnit(if (i > 0) BoardRect(r.x, r.y, lead, r.height) else null,
                BoardRect(r.x + lead, r.y, max(1, r.width - lead), r.height), block.steps.getOrNull(i), labels[i])
        }
    }

    override fun heightFor(width: Int): Int =
        units(width).maxOfOrNull { it.chip.bottom } ?: (getFontMetrics(style.body).height + style.px(8))

    private fun unitAt(x: Int, y: Int) = units(width).firstOrNull { x >= it.chip.x && x < it.chip.right && y >= it.chip.y && y < it.chip.bottom }

    override fun anchorAt(x: Int, y: Int): BoardAnchor? = unitAt(x, y)?.step?.anchor

    override fun tipAt(x: Int, y: Int): String? {
        val step = unitAt(x, y)?.step ?: return null
        return listOfNotNull(step.title, step.summary, step.anchor?.toString()).joinToString(" — ")
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            g2.font = style.body
            val metrics = g2.fontMetrics
            val arc = style.px(8).toFloat()
            for (unit in units(width)) {
                unit.arrow?.let { a ->
                    g2.color = BoardColors.edge
                    g2.stroke = BasicStroke(style.px(1).toFloat())
                    g2.arrow(a.x + style.px(3), a.right - style.px(3), a.y + a.height / 2, style.px(7))
                }
                val c = unit.chip
                val shape = RoundRectangle2D.Float(c.x + 0.5f, c.y + 0.5f, c.width - 1f, c.height - 1f, arc, arc)
                val linked = unit.step?.anchor != null
                if (unit.step != null) {
                    g2.color = BoardColors.chip
                    g2.fill(shape)
                    g2.color = if (linked) BoardColors.link else BoardColors.border
                    g2.draw(shape)
                }
                g2.color = when {
                    unit.step == null -> BoardColors.muted
                    linked -> BoardColors.link
                    else -> BoardColors.text
                }
                val text = fitText(unit.label, c.width - 2 * padX, metrics)
                g2.drawString(text, c.x + padX, c.y + (c.height - metrics.height) / 2 + metrics.ascent)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** A numbered walkthrough; anchored steps are links and show their anchor under the text. */
internal class StepsView(private val block: StepsBlock, style: BoardStyle, navigate: (BoardAnchor) -> Unit) : PaintedView(style, navigate) {
    private class Item(val y: Int, val height: Int, val number: String, val lines: List<String>, val anchor: BoardAnchor?)

    private fun numberWidth() = getFontMetrics(style.bold).stringWidth("${block.steps.size}.") + style.px(8)

    private val items = PerWidth { width ->
        val body = getFontMetrics(style.body)
        val small = getFontMetrics(style.small)
        var y = 0
        block.steps.mapIndexed { i, step ->
            val lines = wrapLines(step.text, width - numberWidth(), body::stringWidth)
            val height = lines.size * body.height + if (step.anchor != null) small.height else 0
            Item(y, height, "${i + 1}.", lines, step.anchor).also { y += height + style.px(6) }
        }
    }

    override fun heightFor(width: Int): Int = items(width).lastOrNull()?.let { it.y + it.height } ?: 0

    override fun anchorAt(x: Int, y: Int): BoardAnchor? = items(width).firstOrNull { y >= it.y && y < it.y + it.height }?.anchor

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            val body = getFontMetrics(style.body)
            val small = getFontMetrics(style.small)
            val indent = numberWidth()
            for (item in items(width)) {
                g2.font = style.bold
                g2.color = BoardColors.muted
                g2.drawString(item.number, 0, item.y + body.ascent)
                g2.font = style.body
                g2.color = if (item.anchor != null) BoardColors.link else BoardColors.text
                var y = item.y + body.ascent
                for (line in item.lines) {
                    g2.drawString(line, indent, y)
                    y += body.height
                }
                item.anchor?.let {
                    g2.font = style.small
                    g2.color = BoardColors.muted
                    g2.drawString(fitText(it.toString(), width - indent, small), indent, y - body.ascent + small.ascent)
                }
            }
        } finally {
            g2.dispose()
        }
    }
}

/** A table of equal-width columns with wrapped cells; anchored cells are links. */
internal class TableView(private val block: TableBlock, style: BoardStyle, navigate: (BoardAnchor) -> Unit) : PaintedView(style, navigate) {
    private class Row(val y: Int, val height: Int, val cells: List<List<String>>, val anchors: List<BoardAnchor?>, val header: Boolean)

    private val pad = style.px(5)

    private val rows = PerWidth { width ->
        val columnWidth = width / block.columns.size.coerceAtLeast(1)
        val head = getFontMetrics(style.smallBold)
        val cell = getFontMetrics(style.small)
        var y = 0
        fun row(texts: List<String>, anchors: List<BoardAnchor?>, header: Boolean): Row {
            val metrics = if (header) head else cell
            val lines = texts.map { wrapLines(it, columnWidth - 2 * pad, metrics::stringWidth) }
            val height = (lines.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1) * metrics.height + 2 * pad
            return Row(y, height, lines, anchors, header).also { y += height }
        }
        listOf(row(block.columns, block.columns.map { null }, true)) +
            block.rows.map { cells -> row(cells.map { it.text }, cells.map { it.anchor }, false) }
    }

    override fun heightFor(width: Int): Int = rows(width).lastOrNull()?.let { it.y + it.height + 1 } ?: 0

    override fun anchorAt(x: Int, y: Int): BoardAnchor? {
        val row = rows(width).firstOrNull { y >= it.y && y < it.y + it.height } ?: return null
        val column = x / (width / block.columns.size.coerceAtLeast(1)).coerceAtLeast(1)
        return row.anchors.getOrNull(column)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            val columnWidth = width / block.columns.size.coerceAtLeast(1)
            val all = rows(width)
            val bottom = all.lastOrNull()?.let { it.y + it.height } ?: 0
            for (row in all) {
                if (row.header) {
                    g2.color = BoardColors.tableHeader
                    g2.fillRect(0, row.y, columnWidth * block.columns.size, row.height)
                }
                g2.font = if (row.header) style.smallBold else style.small
                val metrics = g2.fontMetrics
                row.cells.forEachIndexed { column, lines ->
                    g2.color = if (row.anchors.getOrNull(column) != null) BoardColors.link else BoardColors.text
                    var y = row.y + pad + metrics.ascent
                    for (line in lines) {
                        g2.drawString(line, column * columnWidth + pad, y)
                        y += metrics.height
                    }
                }
                g2.color = BoardColors.border
                g2.drawLine(0, row.y, columnWidth * block.columns.size, row.y)
            }
            g2.color = BoardColors.border
            g2.drawLine(0, bottom, columnWidth * block.columns.size, bottom)
            for (column in 0..block.columns.size) g2.drawLine(column * columnWidth, 0, column * columnWidth, bottom)
        } finally {
            g2.dispose()
        }
    }
}

/** Model-written text inside an evidence block (a symbol's summary), marked as interpretation. */
internal class InterpretationNote(text: String, style: BoardStyle) : JComponent() {
    private val arc = style.px(6).toFloat()

    init {
        layout = StackLayout(style.px(2))
        isOpaque = false
        border = javax.swing.border.EmptyBorder(style.px(4), style.px(6), style.px(5), style.px(6))
        add(javax.swing.JLabel("interpretation").apply {
            font = style.smallItalic
            foreground = BoardColors.muted
        })
        add(WrapText(text, style.italic, BoardColors.text))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            g2.color = BoardColors.interpretationNote
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
        } finally {
            g2.dispose()
        }
    }
}
