package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How often a word occurs in the removed and the added lines of the change, and the files it occurs in. */
internal data class WordChange(val word: String, val removed: Int, val added: Int, val paths: Set<String>) {
    val total get() = removed + added
}

/** The words of the changed lines; context lines are not counted. */
internal data class WordChanges(
    val words: List<WordChange>,
    val removedWords: Int,
    val addedWords: Int,
    /** Every changed line, including blank lines and lines without words. */
    val lines: ChangedLineCounts = ChangedLineCounts(),
) {
    /**
     * Below 0.5 when a word is used more in removed lines, above 0.5 when more in added lines, relative to how many words
     * each side has; a word used on one side only always lies on that side. Smoothing keeps rare words nearer the middle
     * than frequent ones.
     */
    fun side(word: WordChange): Double {
        val added = if (addedWords == 0) 0.0 else word.added.toDouble() / addedWords
        val removed = if (removedWords == 0) 0.0 else word.removed.toDouble() / removedWords
        val smoothing = 1.0 / (removedWords + addedWords + 1)
        return (added + smoothing) / (added + removed + 2 * smoothing)
    }
}

private val WORD = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

/** Counts the words of every `-` and `+` line of the patches, keeping the most frequent [limit]. */
internal fun wordChanges(files: List<ReviewFile>, limit: Int = 300): WordChanges {
    data class Count(var removed: Int = 0, var added: Int = 0, val paths: MutableSet<String> = linkedSetOf())
    val counts = hashMapOf<String, Count>()
    var removed = 0
    var added = 0
    var deletedLines = 0
    var addedLines = 0
    files.forEach { file ->
        var inHunk = false
        file.patch.lineSequence().forEach { line ->
            when {
                line.startsWith("diff --git ") -> inHunk = false
                line.startsWith("@@") -> inHunk = true
                inHunk && (line.startsWith('-') || line.startsWith('+')) -> {
                    if (line[0] == '-') deletedLines++ else addedLines++
                    WORD.findAll(line, 1).forEach { match ->
                        val word = match.value
                        if (word.length < 2) return@forEach
                        val count = counts.getOrPut(word) { Count() }
                        if (line[0] == '-') { count.removed++; removed++ } else { count.added++; added++ }
                        count.paths += file.path
                    }
                }
            }
        }
    }
    val words = counts.map { (word, count) -> WordChange(word, count.removed, count.added, count.paths) }
        .sortedWith(compareByDescending<WordChange> { it.total }.thenBy { it.word })
    return WordChanges(words.take(limit), removed, added, ChangedLineCounts(deletedLines, addedLines))
}

/** The changed files the word chart counts: one file, the files beneath a directory, or every file when [path] is empty. */
internal data class WordScope(val path: String, val directory: Boolean, val files: Int) {
    fun contains(file: String) = path.isEmpty() || if (directory) file.startsWith("$path/") else file == path

    override fun toString() = when {
        path.isEmpty() -> "All changed files · $files"
        directory -> "$path/ · $files"
        else -> path
    }
}

/** Every changed file and every directory above one, after the scope of all files; a directory comes before its contents. */
internal fun wordScopes(files: List<ReviewFile>): List<WordScope> {
    val directories = hashMapOf<String, Int>()
    files.forEach { file ->
        var end = file.path.lastIndexOf('/')
        while (end > 0) {
            val directory = file.path.substring(0, end)
            directories.merge(directory, 1, Int::plus)
            end = directory.lastIndexOf('/')
        }
    }
    val scopes = directories.map { (path, count) -> WordScope(path, true, count) } + files.map { WordScope(it.path, false, 1) }
    return listOf(WordScope("", true, files.size)) + scopes.sortedWith(compareBy({ it.path.replace('/', '\u0000') }, { !it.directory }))
}

/**
 * Words scattered across the width by [WordChanges.side]: removed on the left, added on the right, shared in the middle.
 * Larger words occur more often. Clicking a word opens the files it was removed or added in.
 */
internal class WordChangeChart(
    internal val changes: WordChanges,
    private val chartWidth: Int,
    private val chartHeight: Int,
    private val open: (WordChange) -> Unit,
) : JComponent() {
    private data class Placed(val change: WordChange, val bounds: Rectangle, val font: Font, val baseline: Int)
    private var placed: List<Placed>? = null

    init {
        preferredSize = Dimension(chartWidth, chartHeight)
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { at(e)?.let { open(it.change) } }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (at(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
    }

    internal fun placedWords(): Map<String, Rectangle> = placement().associate { it.change.word to it.bounds }

    private fun at(e: MouseEvent) = placement().firstOrNull { it.bounds.contains(e.point) }

    override fun getToolTipText(event: MouseEvent): String? = at(event)?.change?.let { change ->
        val files = change.paths.sorted()
        "<html><b>${escape(change.word)}</b> · −${change.removed} removed · +${change.added} added<br>" +
            files.take(10).joinToString("<br>") { escape(it) } + (if (files.size > 10) "<br>… ${files.size - 10} more" else "") + "</html>"
    }

    /**
     * Places the most frequent words first, each at its side's column at a free height; when the column is full, it drifts
     * sideways step by step. Words that find no room within the drift are left out.
     */
    private fun placement(): List<Placed> = placed ?: run {
        val base = JBFont.label()
        val margin = JBUI.scale(SIDE_MARGIN)
        val top = JBUI.scale(TOP_MARGIN)
        val usable = chartWidth - 2 * margin
        val max = changes.words.maxOfOrNull { it.total } ?: 1
        // Placed words by horizontal band, so a candidate is only checked against its neighbours.
        val band = JBUI.scale(BAND)
        val taken = hashMapOf<Int, MutableList<Rectangle>>()
        fun bands(r: Rectangle) = r.y / band..(r.y + r.height) / band
        val gap = JBUI.scale(2)
        val middle = margin + usable / 2
        changes.words.mapNotNull { change ->
            val size = JBUI.scale(MIN_FONT) + (JBUI.scale(MAX_FONT - MIN_FONT) * sqrt(change.total.toDouble() / max)).toFloat()
            val font = base.deriveFont(size)
            val metrics = getFontMetrics(font)
            val width = metrics.stringWidth(change.word)
            val height = metrics.ascent + metrics.descent
            val side = changes.side(change)
            val center = margin + (side * usable).roundToInt()
            // A word stays within its side's half, even when it drifts: removed left of the middle, added right of it.
            val minLeft = if (side > 0.5) middle else 0
            val maxLeft = maxOf(minLeft, if (side < 0.5) middle - width else chartWidth - width)
            val x = (center - width / 2).coerceIn(minLeft, maxLeft)
            // A stable, word-dependent start height scatters the words like the reference chart, and keeps them in place across refreshes.
            val span = chartHeight - top - height
            if (span <= 0) return@mapNotNull null
            val start = top + abs(change.word.hashCode() % span)
            val step = JBUI.scale(3)
            val drift = maxOf(width / 2, step)
            val shifts = (0..usable / MAX_DRIFT_PARTS + width step drift).flatMap { if (it == 0) listOf(0) else listOf(-it, it) }
            for (shift in shifts) for (offset in 0..span step step) for (sign in intArrayOf(1, -1)) {
                val y = top + Math.floorMod(start - top + sign * offset, span)
                val left = (x + shift).coerceIn(minLeft, maxLeft)
                val bounds = Rectangle(left, y, width, height)
                val padded = Rectangle(left - gap, y - gap, width + 2 * gap, height + 2 * gap)
                if (bands(padded).none { b -> taken[b]?.any { it.intersects(padded) } == true }) {
                    bands(bounds).forEach { taken.getOrPut(it) { mutableListOf() } += bounds }
                    return@mapNotNull Placed(change, bounds, font, y + metrics.ascent)
                }
            }
            null
        }.also { placed = it }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getListBackground()
            g2.fillRect(0, 0, width, height)
            val labels = JBFont.small()
            g2.font = labels
            g2.color = REMOVED
            g2.drawString("← Removed · ${changes.removedWords} words", JBUI.scale(4), labels.size + JBUI.scale(4))
            g2.color = ADDED
            val addedLabel = "Added · ${changes.addedWords} words →"
            g2.drawString(addedLabel, chartWidth - g2.getFontMetrics(labels).stringWidth(addedLabel) - JBUI.scale(4), labels.size + JBUI.scale(4))
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
            val middle = JBUI.scale(SIDE_MARGIN) + (chartWidth - 2 * JBUI.scale(SIDE_MARGIN)) / 2
            g2.drawLine(middle, JBUI.scale(TOP_MARGIN), middle, chartHeight)
            placement().forEach { word ->
                g2.font = word.font
                g2.color = when {
                    word.change.removed == 0 -> ADDED
                    word.change.added == 0 -> REMOVED
                    else -> color(changes.side(word.change))
                }
                g2.drawString(word.change.word, word.bounds.x, word.baseline)
            }
        } finally { g2.dispose() }
    }

    private companion object {
        const val SIDE_MARGIN = 60
        const val TOP_MARGIN = 24
        const val MIN_FONT = 10
        const val MAX_FONT = 36
        /** A word drifts at most this fraction of the width (plus its own width) away from its column. */
        const val MAX_DRIFT_PARTS = 8
        const val BAND = 16
        val REMOVED = JBColor(Color(0xC0392B), Color(0xE8836E))
        val ADDED = JBColor(Color(0x1E8449), Color(0x6FCF97))
        val SHARED = JBColor(Color(0x7F8C8D), Color(0x9AA0A6))

        /** For words on both sides: red when mostly removed, grey in the middle, green when mostly added. */
        fun color(side: Double): Color = if (side < 0.5) mix(REMOVED, SHARED, side * 2) else mix(SHARED, ADDED, side * 2 - 1)

        fun mix(from: Color, to: Color, t: Double): Color = Color(
            (from.red + (to.red - from.red) * t).roundToInt(),
            (from.green + (to.green - from.green) * t).roundToInt(),
            (from.blue + (to.blue - from.blue) * t).roundToInt(),
        )

        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
