package dev.insight.diff

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.LayoutManager
import javax.swing.JComponent

/** Keeps a card's heading visible while its tall body crosses the viewport, without moving it outside the card. */
internal class StickyCardHeaders {
    private val headings = mutableMapOf<JComponent, Component>()
    /** The last viewport top edge, so a card laid out again between scrolls keeps its heading in view. */
    private var top: Int? = null

    /** A card's layout: a [BorderLayout] that re-applies the sticky offset, which a plain layout pass would reset. */
    fun layout(): LayoutManager = object : BorderLayout() {
        override fun layoutContainer(parent: Container) {
            super.layoutContainer(parent)
            (parent as? JComponent)?.let(::place)
        }
    }

    /** The [card] must use [layout]. */
    fun add(card: JComponent, heading: Component) { headings[card] = heading }

    fun remove(card: JComponent) { headings -= card }

    /** [top] is the viewport's top edge in the coordinate system of the cards' parent. */
    fun update(top: Int) {
        this.top = top
        headings.keys.forEach(::place)
    }

    private fun place(card: JComponent) {
        val heading = headings[card] ?: return
        val top = top ?: return
        val start = card.insets.top
        val end = (card.height - card.insets.bottom - heading.height).coerceAtLeast(start)
        val y = (top - card.y).coerceIn(start, end)
        if (heading.y != y) {
            heading.setLocation(heading.x, y)
            card.repaint()
        }
    }
}
