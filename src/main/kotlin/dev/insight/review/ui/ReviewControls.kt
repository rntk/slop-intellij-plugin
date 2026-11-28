package dev.insight.review.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ui.JBColor
import com.intellij.ui.ScreenUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.Vector
import javax.swing.AbstractButton
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.plaf.basic.ComboPopup
import kotlin.math.max

/*
 * Shared pill-style controls for review headers and toolbars. They match [PillToggleButton] from the Tree board:
 * [TREE_CONTROL_HEIGHT_DP] tall (or taller when a caller sets a larger, zoomed font), 10dp arc, accent fill when active,
 * hover fill, non-focusable, hand cursor, and dimmed when disabled.
 */

private fun pillAlpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private fun pillGraphics(g: Graphics): Graphics2D = (g.create() as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

private fun defaultAccent(): Color = JBUI.CurrentTheme.Focus.focusColor()

/** Pill height for [c]'s font: [TREE_CONTROL_HEIGHT_DP] at the default small font, growing with zoomed fonts. */
internal fun pillHeight(c: JComponent): Int {
    val fm = c.getFontMetrics(c.font ?: JBFont.small())
    return max(JBUI.scale(TREE_CONTROL_HEIGHT_DP), fm.height + JBUI.scale(6))
}

internal enum class PillState { IDLE, HOVER, PRESSED, ACTIVE, PRIMARY, PRIMARY_HOVER }

/** Paints the rounded pill track shared by every control in this file. */
internal fun paintPillBackground(g: Graphics, c: JComponent, state: PillState, accent: Color, partial: Boolean = false) {
    val g2 = pillGraphics(g)
    try {
        val arc = JBUI.scale(10).toDouble()
        val rect = RoundRectangle2D.Double(0.5, 0.5, c.width - 1.0, c.height - 1.0, arc, arc)
        val enabled = c.isEnabled
        val bright = JBColor.isBright()
        g2.stroke = BasicStroke(1f)
        val (fill, line) = when {
            !enabled -> when (state) {
                PillState.ACTIVE, PillState.PRIMARY, PillState.PRIMARY_HOVER ->
                    pillAlpha(accent, if (bright) 16 else 24) to pillAlpha(accent, 60)
                else -> null to pillAlpha(JBColor.border(), 55)
            }
            state == PillState.ACTIVE || state == PillState.PRIMARY ->
                pillAlpha(accent, if (bright) 34 else 52) to pillAlpha(accent, 130)
            state == PillState.PRIMARY_HOVER -> pillAlpha(accent, if (bright) 58 else 80) to pillAlpha(accent, 170)
            state == PillState.PRESSED -> JBUI.CurrentTheme.ActionButton.pressedBackground() to pillAlpha(JBColor.border(), 170)
            state == PillState.HOVER -> JBUI.CurrentTheme.ActionButton.hoverBackground() to pillAlpha(JBColor.border(), 160)
            partial -> null to pillAlpha(accent, 110)
            else -> null to pillAlpha(JBColor.border(), 95)
        }
        fill?.let { g2.color = it; g2.fill(rect) }
        g2.color = line
        g2.draw(rect)
        if (enabled && partial && state != PillState.ACTIVE) {
            val dot = JBUI.scale(5).toDouble()
            g2.color = accent
            g2.fill(Ellipse2D.Double(c.width - dot - JBUI.scale(4), JBUI.scale(3).toDouble(), dot, dot))
        }
    } finally {
        g2.dispose()
    }
}

/** Foreground for pill text: dimmed when disabled, context-help grey when [muted]. */
internal fun pillForeground(c: JComponent, muted: Boolean): Color = when {
    !c.isEnabled -> UIUtil.getLabelDisabledForeground()
    muted -> UIUtil.getContextHelpForeground()
    else -> UIUtil.getLabelForeground()
}

private fun AbstractButton.pillIcon(): Icon? = if (isEnabled) icon else (disabledIcon ?: icon)

/** Width and height of an icon-plus-text pill button, including its border insets. */
private fun AbstractButton.pillSize(): Dimension {
    val fm = getFontMetrics(font)
    val icon = pillIcon()
    val textWidth = text?.takeIf { it.isNotEmpty() }?.let(fm::stringWidth) ?: 0
    val gap = if (icon != null && textWidth > 0) iconTextGap else 0
    val insets = insets
    return Dimension(insets.left + (icon?.iconWidth ?: 0) + gap + textWidth + insets.right, pillHeight(this))
}

/** Paints the icon and text of a pill button, centered horizontally and vertically. */
private fun AbstractButton.paintPillContent(g: Graphics, foreground: Color) {
    val g2 = g.create() as Graphics2D
    try {
        UISettings.setupAntialiasing(g2)
        g2.font = font
        val fm = g2.fontMetrics
        val icon = pillIcon()
        val label = text.orEmpty()
        val textWidth = if (label.isEmpty()) 0 else fm.stringWidth(label)
        val gap = if (icon != null && textWidth > 0) iconTextGap else 0
        val content = (icon?.iconWidth ?: 0) + gap + textWidth
        var x = max(insets.left, (width - content) / 2)
        val trailingIcon = horizontalTextPosition == SwingConstants.LEADING || horizontalTextPosition == SwingConstants.LEFT
        if (trailingIcon) {
            if (label.isNotEmpty()) {
                g2.color = foreground
                g2.drawString(label, x, (height - fm.height) / 2 + fm.ascent)
                x += textWidth + gap
            }
            if (icon != null) icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
        } else {
            if (icon != null) {
                icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
                x += icon.iconWidth + gap
            }
            if (label.isNotEmpty()) {
                g2.color = foreground
                g2.drawString(label, x, (height - fm.height) / 2 + fm.ascent)
            }
        }
    } finally {
        g2.dispose()
    }
}

private fun AbstractButton.configurePill(name: String?, tooltip: String?) {
    if (name != null) this.name = name
    toolTipText = tooltip?.takeIf { it.isNotEmpty() }
    font = JBFont.small()
    isFocusable = false
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusPainted = false
    isRolloverEnabled = true
    iconTextGap = JBUI.scale(4)
    margin = JBUI.emptyInsets()
    border = JBUI.Borders.empty(0, 9)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
}

/**
 * A [JCheckBox] drawn as a pill toggle (no check glyph): selected shows the accent pill, like [PillToggleButton].
 * Selection, [doClick], and Action/Item listeners keep their [JCheckBox] semantics, so callers and tests can keep
 * finding it as a `JCheckBox` by `name`. [partial] shows an accent dot when some, but not all, items are on.
 */
internal class PillCheckBox(
    text: String,
    name: String? = null,
    tooltip: String? = null,
    selected: Boolean = false,
    icon: Icon? = null,
    private val accentProvider: () -> Color = ::defaultAccent,
) : JCheckBox(text, icon, selected) {
    var partial: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        configurePill(name, tooltip)
    }

    override fun getPreferredSize(): Dimension = if (isPreferredSizeSet) super.getPreferredSize() else pillSize()
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val hovered = isEnabled && model.isRollover
        val state = when {
            isSelected -> PillState.ACTIVE
            isEnabled && model.isPressed && model.isArmed -> PillState.PRESSED
            hovered -> PillState.HOVER
            else -> PillState.IDLE
        }
        paintPillBackground(g, this, state, accentProvider(), partial)
        paintPillContent(g, pillForeground(this, muted = !(isSelected || hovered || partial)))
    }
}

/**
 * A [JButton] drawn as a pill for text actions (`Summarize`, `Regenerate`, `Back`, `100%`).
 * [primary] tints it with the accent for a card's main call-to-action. [onClick], when given, is added as an action listener.
 */
internal class PillButton(
    text: String,
    name: String? = null,
    tooltip: String? = null,
    icon: Icon? = null,
    primary: Boolean = false,
    private val accentProvider: () -> Color = ::defaultAccent,
    onClick: (() -> Unit)? = null,
) : JButton(text, icon) {
    var primary: Boolean = primary
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        configurePill(name, tooltip)
        if (onClick != null) addActionListener { onClick() }
    }

    override fun getPreferredSize(): Dimension = if (isPreferredSizeSet) super.getPreferredSize() else pillSize()
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val hovered = isEnabled && model.isRollover
        val pressed = isEnabled && model.isPressed && model.isArmed
        val state = when {
            primary -> if (hovered || pressed) PillState.PRIMARY_HOVER else PillState.PRIMARY
            pressed -> PillState.PRESSED
            hovered -> PillState.HOVER
            else -> PillState.IDLE
        }
        paintPillBackground(g, this, state, accentProvider())
        paintPillContent(g, pillForeground(this, muted = false))
    }
}

/**
 * A [JComboBox] drawn as a compact pill drop-down with a small chevron. An optional [prefix] is painted in grey before
 * the value (`View  Document ▾`) so callers can drop a separate label. The popup list and custom renderers keep working.
 */
internal class PillComboBox<T>(
    model: ComboBoxModel<T>,
    name: String? = null,
    prefix: String? = null,
    tooltip: String? = null,
    accentProvider: () -> Color = ::defaultAccent,
) : JComboBox<T>(model) {
    constructor(
        items: List<T>,
        name: String? = null,
        prefix: String? = null,
        tooltip: String? = null,
        accentProvider: () -> Color = ::defaultAccent,
    ) : this(DefaultComboBoxModel(Vector(items)), name, prefix, tooltip, accentProvider)

    /** Grey leading text painted inside the pill. Read with a null fallback: the UI may ask during super construction. */
    var prefix: String? = prefix
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    internal val accent: (() -> Color)? = accentProvider

    init {
        if (name != null) this.name = name
        toolTipText = tooltip?.takeIf { it.isNotEmpty() }
        font = JBFont.small()
        configurePill()
    }

    override fun updateUI() {
        setUI(PillComboBoxUI())
        configurePill()
    }

    private fun configurePill() {
        isOpaque = false
        isFocusable = false
        border = PILL_COMBO_BORDER()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private companion object {
        @Suppress("FunctionName")
        fun PILL_COMBO_BORDER(): Border = JBUI.Borders.empty(0, 9, 0, 6)
    }
}

/** Paints [PillComboBox]: pill track, grey prefix, the renderer's current value without its background, and a chevron. */
internal class PillComboBoxUI : BasicComboBoxUI() {
    private var hovered = false
    private val hover = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) { hovered = true; comboBox?.repaint() }
        override fun mouseExited(e: MouseEvent) { hovered = false; comboBox?.repaint() }
    }

    private fun prefixText(): String? = (comboBox as? PillComboBox<*>)?.prefix?.takeIf { it.isNotEmpty() }

    private fun prefixWidth(): Int {
        val text = prefixText() ?: return 0
        return comboBox.getFontMetrics(comboBox.font).stringWidth(text) + JBUI.scale(5)
    }

    private fun accent(): Color = (comboBox as? PillComboBox<*>)?.accent?.invoke() ?: defaultAccent()

    override fun installDefaults() {
        super.installDefaults()
        squareButton = false
        padding = JBUI.emptyInsets()
    }

    override fun installListeners() {
        super.installListeners()
        comboBox.addMouseListener(hover)
    }

    override fun uninstallListeners() {
        comboBox.removeMouseListener(hover)
        super.uninstallListeners()
    }

    override fun createArrowButton(): JButton = ChevronButton().apply { addMouseListener(hover) }

    override fun createPopup(): ComboPopup = object : BasicComboPopup(comboBox) {
        override fun getPopupHeightForRowCount(maxRowCount: Int): Int {
            val listHeight = list.preferredSize.height.takeIf { it > 0 } ?: comboBox.height
            val scrollInsets = scroller.insets
            val viewportInsets = scroller.viewportBorder?.getBorderInsets(scroller)
            val contentHeight = listHeight + scrollInsets.top + scrollInsets.bottom +
                (viewportInsets?.let { it.top + it.bottom } ?: 0)
            val screenHeight = ScreenUtil.getScreenRectangle(comboBox).height
            val maxHeight = (screenHeight * 0.65).toInt() - insets.top - insets.bottom
            return contentHeight.coerceAtMost(maxHeight.coerceAtLeast(1))
        }

        override fun computePopupBounds(px: Int, py: Int, pw: Int, ph: Int): Rectangle {
            val listWidth = list.preferredSize.width + JBUI.scale(8)
            return super.computePopupBounds(px, py, max(pw, listWidth), ph)
        }
    }

    override fun rectangleForCurrentValue(): Rectangle {
        val insets = insets
        val arrow = arrowButton?.width ?: 0
        val lead = prefixWidth()
        return Rectangle(
            insets.left + lead, insets.top,
            (comboBox.width - insets.left - insets.right - arrow - lead).coerceAtLeast(0),
            comboBox.height - insets.top - insets.bottom,
        )
    }

    override fun getPreferredSize(c: JComponent): Dimension = getMinimumSize(c)

    override fun getMinimumSize(c: JComponent): Dimension {
        val insets = insets
        val display = displaySize
        val arrow = arrowButton?.preferredSize?.width ?: 0
        return Dimension(
            insets.left + prefixWidth() + display.width + arrow + insets.right,
            max(pillHeight(comboBox), display.height + insets.top + insets.bottom),
        )
    }

    override fun paint(g: Graphics, c: JComponent) {
        val active = comboBox.isPopupVisible
        val state = when {
            active -> PillState.ACTIVE
            hovered && comboBox.isEnabled -> PillState.HOVER
            else -> PillState.IDLE
        }
        paintPillBackground(g, comboBox, state, accent())
        prefixText()?.let { text ->
            val g2 = g.create() as Graphics2D
            try {
                UISettings.setupAntialiasing(g2)
                g2.font = comboBox.font
                val fm = g2.fontMetrics
                g2.color = if (comboBox.isEnabled) UIUtil.getContextHelpForeground() else UIUtil.getLabelDisabledForeground()
                g2.drawString(text, insets.left, (comboBox.height - fm.height) / 2 + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
        if (!comboBox.isEditable) paintCurrentValue(g, rectangleForCurrentValue(), false)
    }

    override fun paintCurrentValueBackground(g: Graphics, bounds: Rectangle, hasFocus: Boolean) = Unit

    override fun paintCurrentValue(g: Graphics, bounds: Rectangle, hasFocus: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val renderer = comboBox.renderer as javax.swing.ListCellRenderer<Any?>? ?: return
        @Suppress("UNCHECKED_CAST")
        val component: Component = renderer.getListCellRendererComponent(listBox as javax.swing.JList<Any?>, comboBox.selectedItem, -1, false, false)
        component.font = comboBox.font
        component.foreground = pillForeground(comboBox, muted = false)
        val opaque = (component as? JComponent)?.isOpaque
        (component as? JComponent)?.isOpaque = false
        try {
            currentValuePane.paintComponent(g, component, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, component is JPanel)
        } finally {
            if (opaque != null) (component as JComponent).isOpaque = opaque
        }
    }

    /** Small chevron at the right edge of the pill; clicks open the popup via the combo's own listener. */
    private inner class ChevronButton : JButton() {
        init {
            name = "pill-combo-arrow"
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isFocusable = false
            border = JBUI.Borders.empty()
            margin = JBUI.emptyInsets()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(12), JBUI.scale(TREE_CONTROL_HEIGHT_DP))
        override fun getMinimumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = pillGraphics(g)
            try {
                val w = JBUI.scale(7).toDouble()
                val h = JBUI.scale(4).toDouble()
                val x = (width - w) / 2.0
                val y = (height - h) / 2.0
                g2.color = pillForeground(comboBox, muted = !(hovered || comboBox.isPopupVisible))
                g2.stroke = BasicStroke(JBUIScale.scale(1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Path2D.Double().apply { moveTo(x, y); lineTo(x + w / 2, y + h); lineTo(x + w, y) })
            } finally {
                g2.dispose()
            }
        }
    }
}

/**
 * A transparent `FlowLayout(LEFT, 4dp, 0)` toolbar row, like the Tree board controls, with a [ToolbarDivider] between
 * each pair of non-empty [clusters].
 */
internal fun pillToolbarRow(vararg clusters: List<JComponent>): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
    isOpaque = false
    var first = true
    for (cluster in clusters) {
        if (cluster.isEmpty()) continue
        if (!first) add(ToolbarDivider())
        cluster.forEach(::add)
        first = false
    }
}

/** Header padding that vertically centers a row of 24dp pill controls in a card header. */
internal fun pillHeaderBorder(): Border = JBUI.Borders.empty(4, 8)
