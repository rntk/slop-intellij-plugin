package dev.insight.board.ui

import com.intellij.util.ui.JBUI
import dev.insight.board.BoardAnchor
import dev.insight.board.Block
import dev.insight.board.ChainBlock
import dev.insight.board.CodeBlock
import dev.insight.board.CodeKind
import dev.insight.board.GroupBlock
import dev.insight.board.HeadingBlock
import dev.insight.board.StepsBlock
import dev.insight.board.SymbolBlock
import dev.insight.board.TableBlock
import dev.insight.board.TextBlock
import dev.insight.board.Tone
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlin.math.max

/** What a card asks of its board. */
internal interface BoardCardHost {
    fun navigate(anchor: BoardAnchor)
    fun ask(blockId: String)
    fun remove(blockId: String)
    fun select(blockId: String)
}

/** A card header: the title on the left, then tags (tone, "evidence"/"interpretation"), and hover actions on the right. */
internal class CardHeader(private val title: JComponent?, private val tags: List<JComponent>, val actions: List<JComponent>,
    private val gap: Int) : JPanel(null), HeightForWidth {
    init {
        isOpaque = false
        title?.let(::add)
        tags.forEach(::add)
        actions.forEach(::add)
    }

    override fun heightFor(width: Int): Int = (listOfNotNull(title) + tags + actions).maxOfOrNull { it.preferredSize.height } ?: 0

    override fun getPreferredSize(): Dimension = Dimension(width, heightFor(width))

    override fun doLayout() {
        // Hidden actions keep their place, so the title does not shift when the pointer enters the card.
        var right = width
        for (c in (tags + actions).asReversed()) {
            val size = c.preferredSize
            right -= size.width
            c.setBounds(right, (height - size.height) / 2, size.width, size.height)
            right -= gap
        }
        title?.let {
            val size = it.preferredSize
            it.setBounds(0, (height - size.height) / 2, max(0, minOf(size.width, right)), size.height)
        }
    }
}

/**
 * One block on the board. Evidence cards (host-resolved code, symbols, call paths) sit on a neutral background, model
 * prose on a faintly tinted one, and toned prose as a callout with a coloured stripe; every header says which it is.
 * Group cards are only a frame and title: their children are separate cards placed inside by the board.
 */
internal class BoardCard(val block: Block, private val style: BoardStyle, private val host: BoardCardHost) :
    JPanel(StackLayout(style.px(6))), HeightForWidth {
    internal enum class Kind { HEADING, CARD, GROUP }

    val kind = when (block) {
        is HeadingBlock -> Kind.HEADING
        is GroupBlock -> Kind.GROUP
        else -> Kind.CARD
    }
    private val tone: Tone? = (block as? TextBlock)?.tone ?: (block as? GroupBlock)?.tone
    private val stripe = if (tone != null && kind == Kind.CARD) style.px(4) else 0
    internal val header: CardHeader

    var selected = false
        set(value) {
            if (field == value) return
            field = value
            updateActions()
            repaint()
        }
    var flashing = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }
    private var hovered = false
        set(value) {
            if (field == value) return
            field = value
            updateActions()
        }

    /** Hover and selection follow the pointer over the whole card, whichever child it is over. Declared before init, which installs it. */
    private val pointer = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            hovered = true
        }

        override fun mouseExited(e: MouseEvent) {
            hovered = contains(SwingUtilities.convertPoint(e.component, e.point, this@BoardCard))
        }

        override fun mousePressed(e: MouseEvent) {
            host.select(block.id)
        }
    }

    init {
        name = "board-card-${block.id}"
        isOpaque = false
        border = when (kind) {
            Kind.HEADING -> EmptyBorder(style.px(2), style.px(4), style.px(2), style.px(4))
            Kind.GROUP -> EmptyBorder(style.px(5), style.px(10), 0, style.px(10))
            Kind.CARD -> EmptyBorder(style.px(8), style.px(10) + stripe, style.px(10), style.px(10))
        }
        header = CardHeader(title(), tags(), actions(), style.px(8))
        add(header)
        body().forEach(::add)
        track(this)
        updateActions()
    }

    override fun heightFor(width: Int): Int {
        setSize(width, max(height, 1))
        return preferredSize.height
    }

    private fun label(text: String, font: Font, color: Color = BoardColors.text) = JLabel(text).apply {
        this.font = font
        foreground = color
    }

    private fun link(text: String, font: Font, anchor: BoardAnchor, name: String) =
        LinkLabel(text, font) { host.navigate(anchor) }.apply {
            this.name = name
            toolTipText = "Open $anchor"
        }

    private fun title(): JComponent? = when (block) {
        is HeadingBlock -> label(block.text, style.heading(block.level))
        is TextBlock -> block.title?.let { label(it, style.bold) }
        is CodeBlock -> link(block.title ?: block.anchor.toString(), style.bold, block.anchor, "board-anchor-${block.id}")
        is SymbolBlock -> block.anchor?.let { link(block.title, style.bold, it, "board-anchor-${block.id}") } ?: label(block.title, style.bold)
        is ChainBlock -> label(block.title ?: "Call path", style.bold)
        is StepsBlock -> label(block.title ?: "Steps", style.bold)
        is TableBlock -> block.title?.let { label(it, style.bold) }
        is GroupBlock -> label(block.title.orEmpty(), style.bold, tone?.let(BoardColors::tone) ?: BoardColors.text)
    }?.also { title -> if (title.name == null) title.name = "board-title-${block.id}" }

    private fun tags(): List<JComponent> = buildList {
        if (block is TextBlock && block.tone != null)
            add(label(toneLabel(block.tone).uppercase(), style.smallBold, BoardColors.tone(block.tone)).apply { name = "board-tone-${block.id}" })
        if (block is CodeBlock && block.kind == CodeKind.DIFF) add(label("diff", style.small, BoardColors.muted))
        val caption = when {
            kind != Kind.CARD -> null
            block is TextBlock && block.tone == null -> "model interpretation"
            block.interpretation -> "interpretation"
            else -> "evidence"
        }
        if (caption != null) add(label(caption, if (block.interpretation) style.smallItalic else style.small, BoardColors.muted).apply {
            name = "board-caption-${block.id}"
            toolTipText = if (block.interpretation) "Written by the model" else "Resolved by the IDE from the project's code"
        })
    }

    private fun actions(): List<JComponent> = buildList {
        val id = block.id
        add(LinkLabel("Ask", style.small) { host.ask(id) }.apply {
            name = "board-ask-$id"
            toolTipText = "Ask about this block in chat"
        })
        primaryAnchor(block)?.let { anchor -> add(link("Open", style.small, anchor, "board-open-$id")) }
        add(LinkLabel("Remove", style.small) { host.remove(id) }.apply {
            name = "board-remove-$id"
            toolTipText = "Remove from the board"
        })
    }

    private fun body(): List<JComponent> = when (block) {
        is HeadingBlock, is GroupBlock -> emptyList()
        is TextBlock -> listOf(WrapText(block.text, style.body, BoardColors.text))
        is CodeBlock -> listOf(CodeView(block, style, host::navigate))
        is SymbolBlock -> listOfNotNull(
            block.meta?.let { WrapText(it, style.small, BoardColors.muted) },
            block.summary?.let { InterpretationNote(it, style) },
            block.anchor?.let { link(it.toString(), style.small, it, "board-link-${block.id}") },
        )
        is ChainBlock -> listOf(ChainView(block, style, host::navigate))
        is StepsBlock -> listOf(StepsView(block, style, host::navigate))
        is TableBlock -> listOf(TableView(block, style, host::navigate))
    }.onEachIndexed { i, c -> if (c.name == null) c.name = "board-body-${block.id}-$i" }

    private fun updateActions() {
        val shown = hovered || selected
        header.actions.forEach { it.isVisible = shown }
    }

    private fun track(component: Component) {
        component.addMouseListener(pointer)
        if (component is Container) component.components.forEach(::track)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.board()
        try {
            val arc = style.px(10).toFloat()
            val w = width.toFloat()
            val h = height.toFloat()
            val shape = RoundRectangle2D.Float(0f, 0f, w, h, arc, arc)
            when (kind) {
                Kind.CARD -> {
                    g2.color = when {
                        tone != null -> BoardColors.toneTint(tone)
                        block.interpretation -> BoardColors.interpretation
                        else -> BoardColors.evidence
                    }
                    g2.fill(shape)
                    if (tone != null) {
                        val clip = g2.create() as java.awt.Graphics2D
                        try {
                            clip.clip(shape)
                            clip.color = BoardColors.tone(tone)
                            clip.fillRect(0, 0, stripe, height)
                        } finally {
                            clip.dispose()
                        }
                    }
                }
                Kind.GROUP -> {
                    g2.color = tone?.let(BoardColors::toneTint) ?: BoardColors.group
                    g2.fill(shape)
                }
                Kind.HEADING -> {}
            }
            val (color, thickness) = when {
                flashing -> BoardColors.flash to 2f
                selected -> BoardColors.selected to 2f
                kind == Kind.HEADING -> return
                kind == Kind.GROUP -> (tone?.let(BoardColors::tone) ?: BoardColors.border) to 1.5f
                else -> BoardColors.border to 1f
            }
            val stroke = thickness * JBUI.scale(1)
            g2.color = color
            g2.stroke = BasicStroke(stroke)
            g2.draw(RoundRectangle2D.Float(stroke / 2, stroke / 2, w - stroke, h - stroke, arc, arc))
        } finally {
            g2.dispose()
        }
    }
}
