package dev.insight.ui

import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Observes mouse gestures within one component tree, including children added later.
 * Passive descendants stay passive: installing listeners on them would steal events that Swing
 * normally targets at an interactive ancestor (for example, a label inside a clickable panel).
 * Descendants must install their own interaction listeners before joining the tree.
 * Swing keeps delivering a drag and its release to the pressed component when the pointer leaves it.
 * Create and dispose on the EDT, alongside the owning UI.
 */
internal class ComponentMouseEvents(private val root: Component, private val observe: (MouseEvent) -> Unit) {
    private val attached = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())
    private val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = observe(e)
        override fun mouseReleased(e: MouseEvent) = observe(e)
        override fun mouseEntered(e: MouseEvent) = observe(e)
        override fun mouseExited(e: MouseEvent) = observe(e)
        override fun mouseMoved(e: MouseEvent) = observe(e)
        override fun mouseDragged(e: MouseEvent) = observe(e)
    }
    private val children = object : ContainerAdapter() {
        override fun componentAdded(e: ContainerEvent) = attach(e.child)
        override fun componentRemoved(e: ContainerEvent) = detach(e.child)
    }

    init { attach(root) }

    private fun attach(component: Component) {
        if (!attached.add(component)) return
        if (component === root || component.mouseListeners.isNotEmpty() || component.mouseMotionListeners.isNotEmpty()) {
            component.addMouseListener(mouse)
            component.addMouseMotionListener(mouse)
        }
        if (component is Container) {
            component.addContainerListener(children)
            component.components.forEach(::attach)
        }
    }

    private fun detach(component: Component) {
        if (!attached.remove(component)) return
        component.removeMouseListener(mouse)
        component.removeMouseMotionListener(mouse)
        if (component is Container) {
            component.removeContainerListener(children)
            component.components.forEach(::detach)
        }
    }

    fun dispose() {
        attached.toList().forEach(::detach)
    }
}
