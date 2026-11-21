package dev.insight.review.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

internal const val REPORT_VIEWER_PANE_NAME = "report-viewer-pane"
internal const val REPORT_VIEWER_SCROLL_NAME = "report-viewer-scroll"
internal const val REPORT_VIEWER_FILTER_NAME = "report-viewer-file-filter"
internal const val REPORT_VIEWER_FILE_LIST_NAME = "report-viewer-file-list"
internal const val REPORT_VIEWER_FILE_LIST_SCROLL_NAME = "report-viewer-file-list-scroll"

internal inline fun <reified T : Component> findDescendant(root: Container, predicate: (T) -> Boolean = { true }): T? {
    val queue = ArrayDeque<Container>()
    queue += root
    while (queue.isNotEmpty()) {
        for (child in queue.removeFirst().components) {
            if (child is T && predicate(child)) return child
            if (child is Container) queue += child
        }
    }
    return null
}

/** Returns the active report viewer input or reader pane to receive focus when the Report viewer tab is selected. */
internal fun findReportViewerFocusTarget(root: Container): JComponent? =
    findDescendant<JEditorPane>(root) { it.name == REPORT_VIEWER_PANE_NAME }
        ?: findDescendant<JBTextField>(root) { it.name == REPORT_VIEWER_FILTER_NAME }

/**
 * Keyboard navigation (arrows, Shift+arrows, Page Up/Down, Space/Shift+Space, Home/End, Ctrl/Cmd+Home/End)
 * for the Report viewer body, falling back to enclosing canvas scroll panes when an axis cannot scroll locally.
 */
internal class ReportViewerNavigation(
    private val card: JPanel,
    private val body: JScrollPane?,
    private val pane: JEditorPane? = null,
) {
    private enum class Distance { STEP, PAGE, EDGE }
    private data class Command(val x: Int, val y: Int, val distance: Distance = Distance.STEP)

    internal var scopeRoot: JComponent = card
        private set
    private val shortcutHosts = LinkedHashSet<JComponent>()

    private val commands = buildMap<KeyStroke, Command> {
        fun bind(code: Int, x: Int, y: Int, distance: Distance = Distance.STEP, modifiers: Int = 0) {
            put(KeyStroke.getKeyStroke(code, modifiers), Command(x, y, distance))
        }
        val shift = InputEvent.SHIFT_DOWN_MASK
        bind(KeyEvent.VK_UP, 0, -1)
        bind(KeyEvent.VK_KP_UP, 0, -1)
        bind(KeyEvent.VK_DOWN, 0, 1)
        bind(KeyEvent.VK_KP_DOWN, 0, 1)
        bind(KeyEvent.VK_LEFT, -1, 0)
        bind(KeyEvent.VK_KP_LEFT, -1, 0)
        bind(KeyEvent.VK_LEFT, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_KP_LEFT, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_UP, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_KP_UP, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_RIGHT, 1, 0)
        bind(KeyEvent.VK_KP_RIGHT, 1, 0)
        bind(KeyEvent.VK_RIGHT, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_KP_RIGHT, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_DOWN, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_KP_DOWN, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_PAGE_UP, 0, -1, Distance.PAGE)
        bind(KeyEvent.VK_SPACE, 0, -1, Distance.PAGE, shift)
        bind(KeyEvent.VK_PAGE_DOWN, 0, 1, Distance.PAGE)
        bind(KeyEvent.VK_SPACE, 0, 1, Distance.PAGE)
        bind(KeyEvent.VK_PAGE_UP, -1, 0, Distance.PAGE, shift)
        bind(KeyEvent.VK_PAGE_DOWN, 1, 0, Distance.PAGE, shift)
        bind(KeyEvent.VK_HOME, 0, -1, Distance.EDGE)
        bind(KeyEvent.VK_END, 0, 1, Distance.EDGE)
        for (modifier in listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK)) {
            bind(KeyEvent.VK_HOME, -1, -1, Distance.EDGE, modifier)
            bind(KeyEvent.VK_END, 1, 1, Distance.EDGE, modifier)
        }
    }

    init {
        // Override JEditorPane's default WHEN_FOCUSED caret navigation bindings while keeping copy/select/Alt+Shift+K intact.
        pane?.let { bind(it, shortcuts = false, ancestorMap = false) }
        // Override JBScrollPane's standard bindings so it cannot steal keys before outer fallback runs.
        body?.let { bind(it, shortcuts = false, ancestorMap = true) }
        bind(card, shortcuts = true, ancestorMap = true)
    }

    /** Attaches navigation to the enclosing canvas [page] and persistent editor [host]. */
    fun install(page: JComponent, host: JComponent = page) {
        scopeRoot = host
        unregisterShortcuts(card)
        if (host !== page) {
            bind(page, shortcuts = false, ancestorMap = true)
            bind(host, shortcuts = true, ancestorMap = true)
        } else {
            bind(page, shortcuts = true, ancestorMap = true)
        }
    }

    /** Unregisters all custom shortcuts installed by this instance across host components. */
    fun dispose() {
        shortcutHosts.toList().forEach(::unregisterShortcuts)
        shortcutHosts.clear()
    }

    private fun unregisterShortcuts(component: JComponent) {
        (component.getClientProperty(ACTIONS_KEY) as? List<*>)
            ?.filterIsInstance<DumbAwareAction>()
            ?.forEach { it.unregisterCustomShortcutSet(component) }
        component.putClientProperty(ACTIONS_KEY, null)
        shortcutHosts.remove(component)
    }

    private fun bind(component: JComponent, shortcuts: Boolean, ancestorMap: Boolean) {
        unregisterShortcuts(component)
        commands.forEach { (stroke, command) ->
            val name = "$ACTION_PREFIX$stroke"
            component.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { navigate(focusOwner(), command) }
                override fun isEnabled(): Boolean = accepts(focusOwner())
            })
            component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name)
            if (ancestorMap) {
                component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name)
            }
        }
        if (!shortcuts) return
        val actions = mutableListOf<DumbAwareAction>()
        val byCommand = linkedMapOf<Command, MutableList<KeyboardShortcut>>()
        commands.forEach { (stroke, command) ->
            byCommand.getOrPut(command) { mutableListOf() }.add(KeyboardShortcut(stroke, null))
        }
        byCommand.forEach { (command, strokeList) ->
            val action = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) { navigate(focusOwner(), command) }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = accepts(focusOwner()) }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
            action.registerCustomShortcutSet(CustomShortcutSet(*strokeList.toTypedArray()), component)
            actions += action
        }
        component.putClientProperty(ACTIONS_KEY, actions)
        shortcutHosts += component
    }

    private fun focusOwner(): Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: scopeRoot

    internal fun accepts(owner: Component): Boolean {
        if (MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) return false
        if (owner !== scopeRoot && !SwingUtilities.isDescendingFrom(owner, scopeRoot)) return false
        // JEditorPane is a non-editable JTextComponent and should scroll via navigation keys.
        if (owner is JTextComponent && owner.isEditable) return false
        if (owner is AbstractButton || owner is JComboBox<*> || owner is JList<*>) return false
        if (SwingUtilities.getAncestorOfClass(JComboBox::class.java, owner) != null) return false
        return true
    }

    internal fun navigate(owner: Component, stroke: KeyStroke) {
        commands[stroke]?.let { navigate(owner, it) }
    }

    private fun navigate(owner: Component, command: Command) {
        if (!accepts(owner)) return
        val outer = generateSequence(card.parent) { it.parent }.filterIsInstance<JScrollPane>().toList()
        fun move(direction: Int, vertical: Boolean) {
            if (direction == 0) return
            fun bar(scroll: JScrollPane) = if (vertical) scroll.verticalScrollBar else scroll.horizontalScrollBar
            fun scrollable(scroll: JScrollPane) = bar(scroll).let { it.maximum - it.minimum > it.visibleAmount }
            val target = body?.takeIf(::scrollable) ?: outer.firstOrNull(::scrollable) ?: return
            val scrollbar = bar(target)
            val unit = scrollbar.getUnitIncrement(direction).coerceAtLeast(1)
            val step = maxOf(unit, JBUI.scale(48))
            val delta = when (command.distance) {
                Distance.STEP -> step
                Distance.PAGE -> (scrollbar.visibleAmount - step).coerceAtLeast(unit)
                Distance.EDGE -> scrollbar.maximum - scrollbar.minimum
            }
            scrollbar.value = (scrollbar.value.toLong() + direction.toLong() * delta)
                .coerceIn(scrollbar.minimum.toLong(), (scrollbar.maximum - scrollbar.visibleAmount).toLong())
                .toInt()
        }
        move(command.x, vertical = false)
        move(command.y, vertical = true)
    }

    private companion object {
        const val ACTIONS_KEY = "report.viewer.navigation.actions"
        const val ACTION_PREFIX = "report-viewer-navigation:"
    }
}

/** Wires Up/Down/PageUp/PageDown/Enter on the empty-state filter field to drive and open items in [list]. */
internal fun installFilterListNavigation(
    filterField: JBTextField,
    list: JBList<String>,
    openSelected: () -> Unit,
) {
    ScrollingUtil.installActions(list)
    ScrollingUtil.installActions(list, filterField)
    fun bindFilter(strokes: List<KeyStroke>, name: String, move: () -> Unit) {
        filterField.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (list.model.size > 0) move()
            }
        })
        val inputMap = filterField.getInputMap(JComponent.WHEN_FOCUSED)
        strokes.forEach { inputMap.put(it, name) }
    }
    bindFilter(
        listOf(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), KeyStroke.getKeyStroke(KeyEvent.VK_KP_UP, 0)),
        "report-filter-list-up",
    ) { ScrollingUtil.moveUp(list, 0) }
    bindFilter(
        listOf(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), KeyStroke.getKeyStroke(KeyEvent.VK_KP_DOWN, 0)),
        "report-filter-list-down",
    ) { ScrollingUtil.moveDown(list, 0) }
    bindFilter(
        listOf(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0)),
        "report-filter-list-page-up",
    ) { ScrollingUtil.movePageUp(list) }
    bindFilter(
        listOf(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0)),
        "report-filter-list-page-down",
    ) { ScrollingUtil.movePageDown(list) }
    filterField.addActionListener { openSelected() }
}
