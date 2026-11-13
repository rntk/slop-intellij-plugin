package dev.insight.review.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** Each scrollable axis of the focused evidence owns keys, including at its limits; other axes pan the document. */
internal class DocumentNavigation(private val panel: DocumentColumnsPanel) {
    private enum class Distance { STEP, PAGE, EDGE }
    private data class Command(val x: Int, val y: Int, val distance: Distance = Distance.STEP)
    private val commands = buildMap<KeyStroke, Command> {
        fun bind(code: Int, x: Int, y: Int, distance: Distance = Distance.STEP, modifiers: Int = 0) {
            put(KeyStroke.getKeyStroke(code, modifiers), Command(x, y, distance))
        }
        val shift = InputEvent.SHIFT_DOWN_MASK
        bind(KeyEvent.VK_UP, 0, -1)
        bind(KeyEvent.VK_DOWN, 0, 1)
        bind(KeyEvent.VK_LEFT, -1, 0)
        bind(KeyEvent.VK_RIGHT, 1, 0)
        bind(KeyEvent.VK_LEFT, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_RIGHT, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_UP, -1, 0, modifiers = shift)
        bind(KeyEvent.VK_DOWN, 1, 0, modifiers = shift)
        bind(KeyEvent.VK_PAGE_UP, 0, -1, Distance.PAGE)
        bind(KeyEvent.VK_PAGE_DOWN, 0, 1, Distance.PAGE)
        bind(KeyEvent.VK_SPACE, 0, 1, Distance.PAGE)
        bind(KeyEvent.VK_SPACE, 0, -1, Distance.PAGE, shift)
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
        install(panel)
        // A nested scroll pane's standard Swing bindings otherwise win over the document's bindings.
        fun visit(container: Container) {
            container.components.forEach {
                if (it is JScrollPane) bind(it, shortcuts = false)
                if (it is Container) visit(it)
            }
        }
        visit(panel)
    }

    /** The editor can initially focus the enclosing page rather than a document descendant. */
    fun install(component: JComponent) = bind(component, shortcuts = true)

    private fun bind(component: JComponent, shortcuts: Boolean) {
        // Editor pages survive document rebuilds; release the shortcuts that captured the previous panel.
        (component.getClientProperty("document.navigation.actions") as? List<*>)
            ?.filterIsInstance<DumbAwareAction>()?.forEach { it.unregisterCustomShortcutSet(component) }
        val actions = mutableListOf<DumbAwareAction>()
        commands.forEach { (stroke, command) ->
            val name = "document-navigation:$stroke"
            component.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { navigate(focusOwner(), command) }
            })
            component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name)
            component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name)
        }
        if (!shortcuts) return
        commands.forEach { (stroke, command) ->
            val action = object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) { navigate(focusOwner(), command) }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = accepts(focusOwner()) }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
            action.registerCustomShortcutSet(CustomShortcutSet(KeyboardShortcut(stroke, null)), component)
            actions += action
        }
        component.putClientProperty("document.navigation.actions", actions)
    }

    private fun focusOwner(): Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: panel

    private fun accepts(owner: Component) = (owner === panel || SwingUtilities.isDescendingFrom(owner, panel) ||
        SwingUtilities.isDescendingFrom(panel, owner)) &&
        owner !is JTextComponent && owner !is AbstractButton

    internal fun navigate(owner: Component, stroke: KeyStroke) {
        commands[stroke]?.let { navigate(owner, it) }
    }

    private fun navigate(owner: Component, command: Command) {
        if (!accepts(owner)) return
        val section = generateSequence(owner) { it.parent }.firstOrNull { it.parent === panel && it.name?.startsWith("file:") == true }
        fun source(container: Container): JScrollPane? {
            for (child in container.components) {
                if (child is JScrollPane && child.name == "document-source-scroll") return child
                if (child is Container) source(child)?.let { return it }
            }
            return null
        }
        val body = (section as? Container)?.let(::source)
        val canvas = generateSequence(panel.parent) { it.parent }.filterIsInstance<JScrollPane>().toList()
        fun move(direction: Int, vertical: Boolean) {
            if (direction == 0) return
            fun bar(pane: JScrollPane) = if (vertical) pane.verticalScrollBar else pane.horizontalScrollBar
            fun scrollable(pane: JScrollPane): Boolean = bar(pane).let { it.maximum - it.minimum > it.visibleAmount }
            val target = body?.takeIf(::scrollable) ?: canvas.firstOrNull(::scrollable) ?: return
            val scrollbar = bar(target)
            val delta = when (command.distance) {
                Distance.STEP -> if (target === body) scrollbar.getUnitIncrement(direction).coerceAtLeast(1) else JBUI.scale(48)
                Distance.PAGE -> (scrollbar.visibleAmount - scrollbar.getUnitIncrement(direction)).coerceAtLeast(1)
                Distance.EDGE -> scrollbar.maximum - scrollbar.minimum
            }
            scrollbar.value = (scrollbar.value.toLong() + direction.toLong() * delta)
                .coerceIn(scrollbar.minimum.toLong(), (scrollbar.maximum - scrollbar.visibleAmount).toLong()).toInt()
        }
        move(command.x, false)
        move(command.y, true)
    }
}
