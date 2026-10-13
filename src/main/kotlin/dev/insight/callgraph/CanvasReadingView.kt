package dev.insight.callgraph

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.insight.enrichment.CanvasData
import dev.insight.enrichment.CanvasLine
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.math.roundToInt

/** A single scrolling surface keeps topic spans aligned with the code or summaries they describe. */
internal class CanvasReadingView(private val openSource: (CanvasLine) -> Unit) : JComponent() {
    var data: CanvasData? = null
        set(value) { field = value; selectedTopic = 0; rebuild() }
    var summaryMode = false
        set(value) { field = value; rebuild(); scrollToTopic() }
    var depth = 3
        set(value) { field = value; rebuild() }
    var zoom = 1.0
        set(value) { val old = field; field = value.coerceIn(0.25, 2.0); rebuild(); firePropertyChange("zoom", old, field) }
    internal var selectedTopic = 0
        private set
    private data class Card(val bounds: Rectangle, val label: String, val topic: Int, val level: Int)
    private val cards = mutableListOf<Card>()
    private var unitY = IntArray(0)
    private var topicY = IntArray(0)
    private var lineHeight = 18
    private var centerX = 0
    private var centerWidth = 600
    private var sideX = 0
    private var contentHeight = 200
    private var drag: Point? = null
    private var dragView: Point? = null
    private val accent = JBColor(Color(45, 114, 190), Color(100, 165, 230))

    init {
        isOpaque = true
        isFocusable = true
        toolTipText = "Click a topic to focus it. Double-click source to open it. Drag to pan; Ctrl/Cmd + wheel to zoom."
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                val point = Point((e.x / zoom).toInt(), (e.y / zoom).toInt())
                cards.lastOrNull { it.bounds.contains(point) }?.let { selectTopic(it.topic); return }
                if (summaryMode && point.x in centerX until centerX + centerWidth) {
                    topicY.indexOfLast { it <= point.y }.takeIf { it >= 0 }?.let { selectTopic(it); return }
                }
                if (e.clickCount == 2) sourceAt(point)?.let(openSource)
                drag = e.locationOnScreen
                dragView = (parent as? JViewport)?.viewPosition
            }
            override fun mouseReleased(e: MouseEvent) { drag = null; dragView = null; cursor = Cursor.getDefaultCursor() }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val from = drag ?: return
                val start = dragView ?: return
                val viewport = parent as? JViewport ?: return
                val now = e.locationOnScreen
                viewport.viewPosition = Point((start.x + from.x - now.x).coerceIn(0, maxOf(0, width - viewport.width)),
                    (start.y + from.y - now.y).coerceIn(0, maxOf(0, height - viewport.height)))
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }
        })
        addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) { zoom *= if (e.wheelRotation < 0) 1.1 else 1 / 1.1; e.consume() }
            else (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane)?.let {
                it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it))
            }
        }
        for ((key, delta) in listOf("UP" to -1, "DOWN" to 1)) {
            inputMap.put(KeyStroke.getKeyStroke(key), key)
            actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) { selectTopic(selectedTopic + delta) } })
        }
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { selectedLines().firstOrNull { !it.header }?.let(openSource) }
        })
    }

    fun selectTopic(index: Int) {
        val topics = data?.topics.orEmpty()
        if (topics.isEmpty()) return
        selectedTopic = index.coerceIn(topics.indices)
        rebuild()
        scrollToTopic()
    }

    private fun scrollToTopic() {
        if (selectedTopic !in topicY.indices) return
        scrollRectToVisible(Rectangle((centerX * zoom).toInt(), (topicY[selectedTopic] * zoom).toInt(),
            (minOf(centerWidth, 400) * zoom).toInt(), (100 * zoom).toInt()))
    }

    private fun selectedLines(): List<CanvasLine> {
        val value = data ?: return emptyList()
        val topic = value.topics.getOrNull(selectedTopic) ?: return emptyList()
        return topic.ranges.flatMap { range -> (range.start..range.end).mapNotNull { value.document.lines.getOrNull(it) } }
    }

    private fun sourceAt(point: Point): CanvasLine? {
        val value = data ?: return null
        if (!summaryMode && point.x in centerX until centerX + centerWidth) {
            val index = unitY.indexOfLast { it <= point.y }
            return value.document.lines.getOrNull(index)?.takeUnless { it.header }
        }
        if (summaryMode && point.x in sideX until sideX + 520 && point.y >= topicY[selectedTopic] + 48) {
            return selectedLines().getOrNull((point.y - topicY[selectedTopic] - 48) / lineHeight)?.takeUnless { it.header }
        }
        return null
    }

    private fun rebuild() {
        cards.clear()
        val value = data
        font = UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)
        lineHeight = getFontMetrics(codeFont()).height + 3
        val levels = value?.topics?.maxOfOrNull { it.path.split('>').size }?.coerceAtMost(depth) ?: 1
        centerX = 16 + levels * 180
        centerWidth = if (summaryMode) 520 else maxOf(600, (value?.document?.lines?.maxOfOrNull { getFontMetrics(codeFont()).stringWidth(it.text) } ?: 500) + 100)
        sideX = centerX + centerWidth + 20
        unitY = IntArray(value?.document?.lines?.size ?: 0) { 40 + it * lineHeight }
        var y = 40
        topicY = IntArray(value?.topics?.size ?: 0)
        value?.topics?.forEachIndexed { index, topic ->
            topicY[index] = if (summaryMode) y else unitY[topic.ranges.minOf { it.start }]
            if (summaryMode) y += maxOf(100, 52 + wrap(topic.summary.ifBlank { "No summary for this topic." }, 480).size * lineHeight) + 12
        }
        val groups = linkedMapOf<String, MutableList<Int>>()
        value?.topics?.forEachIndexed { index, topic ->
            val parts = topic.path.split('>')
            for (level in 1..minOf(levels, parts.size)) groups.getOrPut(parts.take(level).joinToString(">")) { mutableListOf() }.add(index)
        }
        groups.forEach { (path, indices) ->
            val level = path.split('>').size
            val spans = indices.flatMap { index ->
                if (summaryMode) listOf(Triple(topicY[index], topicY.getOrNull(index + 1) ?: y, index))
                else value!!.topics[index].ranges.map { Triple(unitY[it.start], unitY[it.end] + lineHeight, index) }
            }.sortedBy { it.first }
            val merged = mutableListOf<Triple<Int, Int, Int>>()
            spans.forEach { span ->
                val last = merged.lastOrNull()
                if (last != null && span.first <= last.second) merged[merged.lastIndex] = Triple(last.first, maxOf(last.second, span.second), last.third)
                else merged += span
            }
            merged.forEach { (top, bottom, index) ->
                cards += Card(Rectangle(16 + (level - 1) * 180, top, 168, maxOf(lineHeight, bottom - top - if (summaryMode) 12 else 0)), path.substringAfterLast('>'), index, level)
            }
        }
        contentHeight = maxOf(200, if (summaryMode) y else 40 + unitY.size * lineHeight,
            (topicY.getOrNull(selectedTopic) ?: 40) + 60 + selectedLines().size * lineHeight)
        preferredSize = Dimension(((sideX + 540) * zoom).roundToInt(), ((contentHeight + 30) * zoom).roundToInt())
        revalidate(); repaint()
    }

    private fun codeFont() = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
    private fun wrap(text: String, width: Int): List<String> {
        val fm = getFontMetrics(font)
        return text.lines().flatMap { paragraph ->
            val result = mutableListOf<String>(); var line = ""
            paragraph.split(' ').forEach { word ->
                if (line.isNotEmpty() && fm.stringWidth("$line $word") > width) { result += line; line = word }
                else line = if (line.isEmpty()) word else "$line $word"
            }
            result + line
        }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.color = background; g.fillRect(0, 0, width, height)
            g.scale(zoom, zoom)
            val visible = g.clipBounds ?: Rectangle(0, 0, preferredSize.width, preferredSize.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font
            val value = data
            if (value == null) {
                g.color = foreground
                g.drawString("Choose a flow or open a function from the graph, then Generate canvas.", 24, 40)
                return
            }
            fun box(rect: Rectangle, selected: Boolean = false) {
                g.color = if (selected) JBColor(Color(229, 240, 255), Color(43, 58, 78)) else JBColor(Color(246, 247, 249), Color(43, 45, 49))
                g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 8, 8)
                g.color = if (selected) accent else JBColor.border()
                g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 8, 8)
            }
            g.color = foreground
            for (level in 1..depth.coerceAtMost(value.topics.maxOfOrNull { it.path.split('>').size } ?: 1))
                g.drawString("Topic level $level", 16 + (level - 1) * 180, 24)
            g.drawString(if (summaryMode) "Topic summaries" else "Flow source", centerX, 24)
            g.drawString(if (summaryMode) "Selected topic source" else "Selected topic summary", sideX, 24)
            cards.forEach { card ->
                if (!card.bounds.intersects(visible)) return@forEach
                val activePath = value.topics.getOrNull(selectedTopic)?.path.orEmpty()
                val path = value.topics[card.topic].path.split('>').take(card.level).joinToString(">")
                box(card.bounds, activePath == path || activePath.startsWith("$path>"))
                g.color = foreground
                val titleLines = wrap(card.label, 145).take(maxOf(1, (card.bounds.height - 12) / lineHeight))
                val titleY = maxOf(card.bounds.y + 16, visible.y + 16).coerceAtMost(maxOf(card.bounds.y + 16, card.bounds.y + card.bounds.height - titleLines.size * lineHeight))
                val oldClip = g.clip
                g.clip(card.bounds)
                titleLines.forEachIndexed { i, line -> g.drawString(line, card.bounds.x + 10, titleY + i * lineHeight) }
                g.clip = oldClip
            }
            if (summaryMode) value.topics.forEachIndexed { index, topic ->
                val height = (topicY.getOrNull(index + 1) ?: (contentHeight - 20)) - topicY[index] - 12
                if (topicY[index] > visible.y + visible.height || topicY[index] + height < visible.y) return@forEachIndexed
                val lines = wrap(topic.summary.ifBlank { "No summary for this topic." }, 480)
                box(Rectangle(centerX, topicY[index], centerWidth, minOf(height, maxOf(100, 52 + lines.size * lineHeight))), index == selectedTopic)
                g.color = accent; g.drawString(topic.path.substringAfterLast('>'), centerX + 14, topicY[index] + 22)
                g.color = foreground
                lines.forEachIndexed { i, line -> g.drawString(line, centerX + 14, topicY[index] + 46 + i * lineHeight) }
            } else {
                g.font = codeFont()
                value.document.lines.forEachIndexed { index, line ->
                    if (unitY[index] > visible.y + visible.height || unitY[index] + lineHeight < visible.y) return@forEachIndexed
                    val selected = value.topics.getOrNull(selectedTopic)?.ranges?.any { index in it.start..it.end } == true
                    if (selected) { g.color = JBColor(Color(230, 241, 255), Color(35, 53, 72)); g.fillRect(centerX, unitY[index], centerWidth, lineHeight) }
                    g.color = if (line.header) accent else foreground
                    g.drawString(if (line.header) line.text else "${line.fileLine.toString().padStart(5)}  ${line.text}", centerX + 8, unitY[index] + lineHeight - 4)
                }
            }
            val topic = value.topics.getOrNull(selectedTopic) ?: return
            val top = topicY[selectedTopic]
            val sideLines = if (summaryMode) selectedLines().map { if (it.header) it.text else "${it.fileLine}: ${it.text}" }
                else wrap(topic.summary.ifBlank { "No summary for this topic." }, 490)
            box(Rectangle(sideX, top, 520, 58 + sideLines.size * lineHeight), true)
            g.font = font; g.color = accent
            wrap(topic.path.replace(">", " / "), 490).take(2).forEachIndexed { i, line -> g.drawString(line, sideX + 12, top + 20 + i * 15) }
            g.font = if (summaryMode) codeFont() else font; g.color = foreground
            sideLines.forEachIndexed { index, line -> g.drawString(line, sideX + 12, top + 48 + index * lineHeight) }
        } finally { g.dispose() }
    }
}
