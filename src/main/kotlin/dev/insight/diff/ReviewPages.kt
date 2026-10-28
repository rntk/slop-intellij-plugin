package dev.insight.diff

import java.awt.CardLayout
import java.awt.Component
import javax.swing.JPanel

/** Page registry and standalone preview host. The IDE supplies the visible editor tabs. */
internal class ReviewPages : JPanel(CardLayout()) {
    private data class Page(var title: String, val component: Component, var tooltip: String? = null)
    private val pages = mutableListOf<Page>()
    val tabCount: Int get() = pages.size
    var selectedIndex: Int = -1
        set(value) {
            field = value
            if (value in pages.indices) (layout as CardLayout).show(this, value.toString())
        }
    var selectedComponent: Component?
        get() = pages.getOrNull(selectedIndex)?.component
        set(value) { selectedIndex = pages.indexOfFirst { it.component === value } }

    fun addTab(title: String, component: Component, host: Component = component) {
        pages += Page(title, component)
        if (host.parent == null) add(host, pages.lastIndex.toString())
        if (selectedIndex < 0) selectedIndex = 0
    }
    fun getTitleAt(index: Int) = pages[index].title
    fun getComponentAt(index: Int) = pages[index].component
    fun indexOfComponent(component: Component) = pages.indexOfFirst { it.component === component }
    fun setTitleAt(index: Int, title: String) { pages[index].title = title }
    fun setToolTipTextAt(index: Int, tooltip: String?) { pages[index].tooltip = tooltip }
    override fun removeAll() {
        super.removeAll()
        pages.clear()
        selectedIndex = -1
    }
}
