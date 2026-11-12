package dev.insight.canvas

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** One entry of a card's ⋯ menu; [separated] starts a new group. */
internal data class CanvasCardAction(
    val name: String,
    val label: String,
    val tooltip: String? = null,
    val enabled: Boolean = true,
    val separated: Boolean = false,
    val run: () -> Unit,
)

/** The ⋯ menu of [actions], also shown on right-click. */
internal fun canvasActionMenu(actions: List<CanvasCardAction>): JPopupMenu = JPopupMenu().apply {
    actions.forEachIndexed { index, action ->
        if (action.separated && index > 0) addSeparator()
        add(JMenuItem(action.label).apply {
            name = action.name
            toolTipText = action.tooltip
            isEnabled = action.enabled
            addActionListener { action.run() }
        })
    }
}

/** A text glyph that acts as a small button and scales with the zoom like the text around it. */
internal fun canvasGlyphButton(host: CanvasCardHost, glyph: String, tooltip: String, name: String, action: (JComponent) -> Unit): JLabel =
    host.zoomFont(JLabel(glyph).apply {
        this.name = name
        toolTipText = tooltip
        foreground = UIUtil.getContextHelpForeground()
    }, UIUtil.getLabelFont().deriveFont(Font.BOLD)).let { label -> label.onLeftClick { action(label) } }

/**
 * A card's header: the meta fold toggle, the [title] (with an optional [badge]) taking all the room the card has, then a
 * ⋯ menu of [actions] and, given [onClose], a × that closes the card. Commands live in the menu so the title keeps its
 * width at any zoom.
 */
internal class CanvasCardHeader(
    host: CanvasCardHost,
    cardId: String?,
    title: CanvasTitleLabel,
    badge: JComponent?,
    private val actions: () -> List<CanvasCardAction>,
    closeTooltip: String? = null,
    onClose: (() -> Unit)? = null,
) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
    val metaToggle = canvasFoldToggle(host, cardId, CardSection.META)
    val menuButton = canvasGlyphButton(host, "⋯", "More actions", "canvas-card-menu") { showMenu(it) }
    val closeButton = onClose?.let { close -> canvasGlyphButton(host, "×", closeTooltip ?: "Close", "canvas-close-card") { close() } }

    init {
        isOpaque = false
        add(metaToggle, BorderLayout.WEST)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(title, BorderLayout.CENTER)
            badge?.let { add(it, BorderLayout.EAST) }
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(menuButton)
            closeButton?.let(::add)
        }, BorderLayout.EAST)
    }

    /** The menu's current entries. */
    fun menuActions(): List<CanvasCardAction> = actions()

    fun showMenu(invoker: JComponent = menuButton, x: Int = 0, y: Int = invoker.height) =
        canvasActionMenu(actions()).show(invoker, x, y)
}
