package dev.insight.review.wall

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The list of places a link led to in one card's file, laid over the card's top-right corner: each entry scrolls the card to
 * its line, and Hide drops the list.
 */
internal class WallLinkSitesPanel(
    private val idPrefix: String,
    sites: WallLinkSites,
    private val onSite: (WallLinkSite) -> Unit,
    onHide: () -> Unit,
    private val onRaise: () -> Unit = {},
) : JPanel(BorderLayout()) {
    var sites: WallLinkSites = sites
        private set
    private val title = JLabel().apply {
        font = JBFont.small().asBold()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val list = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val scroller = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        name = "$idPrefix-link-sites:${sites.path}"
        isOpaque = true
        background = JBColor.namedColor("ToolTip.background", UIUtil.getToolTipBackground())
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(4, 8))
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
            add(title, BorderLayout.CENTER)
            add(ActionLink("Hide") { onHide() }.apply {
                name = "$idPrefix-link-sites-hide"
                font = JBFont.small()
                toolTipText = "Hide this list"
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(scroller, BorderLayout.CENTER)
        // Consume presses on non-link areas so they do not activate or drag the card beneath.
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                onRaise()
                e.consume()
            }
        })
        show(sites)
    }

    /** Lists [next], marking the site at its current line. */
    fun show(next: WallLinkSites) {
        val sameEntries = sites.path == next.path &&
            sites.title == next.title &&
            sites.sites == next.sites &&
            list.componentCount == next.sites.size
        sites = next
        title.text = next.title
        if (sameEntries) {
            next.sites.forEachIndexed { index, site ->
                (list.getComponent(index) as? ActionLink)?.font =
                    if (site.line == next.current) JBFont.small().asBold() else JBFont.small()
            }
        } else {
            list.removeAll()
            next.sites.forEach { site ->
                list.add(ActionLink(site.label) { onSite(site) }.apply {
                    name = "$idPrefix-link-site"
                    font = if (site.line == next.current) JBFont.small().asBold() else JBFont.small()
                    toolTipText = "Scroll this card to line ${site.line}"
                    border = JBUI.Borders.empty(1, 0)
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            scroller.viewport.viewPosition = Point(0, 0)
        }
        list.revalidate()
        list.repaint()
        revalidate()
        repaint()
    }

    /** The size the list wants, at most [maxWidth] wide and [maxHeight] tall. */
    fun sizeWithin(maxWidth: Int, maxHeight: Int): Dimension {
        val wanted = preferredSize
        return Dimension(minOf(wanted.width, maxWidth), minOf(wanted.height, maxHeight))
    }
}
