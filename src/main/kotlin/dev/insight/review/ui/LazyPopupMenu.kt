package dev.insight.review.ui

import java.awt.Component
import javax.swing.JPopupMenu
import javax.swing.MenuElement

/**
 * A context menu whose items are built by [build] the first time it is shown or its items are read. Large cards give
 * every entry a menu, and building each one up front dominated rebuilding the card while a run saved results.
 */
internal class LazyPopupMenu(build: () -> JPopupMenu) : JPopupMenu() {
    // Set after the superclass constructor, which may already query the components.
    private var pending: (() -> JPopupMenu)? = null

    init { pending = build }

    private fun fill() {
        val build = pending ?: return
        pending = null
        val built = build()
        name = name ?: built.name
        built.popupMenuListeners.forEach(::addPopupMenuListener)
        built.components.forEach(::add)
    }

    override fun getComponents(): Array<Component> { fill(); return super.getComponents() }
    override fun getComponentCount(): Int { fill(); return super.getComponentCount() }
    override fun getComponent(n: Int): Component { fill(); return super.getComponent(n) }
    override fun getSubElements(): Array<MenuElement> { fill(); return super.getSubElements() }
    override fun show(invoker: Component?, x: Int, y: Int) { fill(); super.show(invoker, x, y) }
    override fun setVisible(visible: Boolean) { if (visible) fill(); super.setVisible(visible) }
}
