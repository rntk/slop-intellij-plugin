package dev.insight.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Shows a fuzzy search over a canvas's [cards] below [owner]: the typed text filters the candidate list, and a click
 * or Enter on a candidate closes the popup and calls [choose] with its key.
 */
internal fun <K> showCardSearch(owner: JComponent, cards: List<CardCandidate<K>>, choose: (K) -> Unit): JBPopup {
    val search = SearchTextField(false).apply {
        textEditor.emptyText.text = "Find a card by name, file or text"
        toolTipText = "Fuzzy search: camel humps (\"CSP\"), word starts and parts of names · ↑↓ select · Enter shows the card"
    }
    val listModel = DefaultListModel<CardCandidate<K>>()
    val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<CardCandidate<K>>() {
            override fun customizeCellRenderer(list: JList<out CardCandidate<K>>, card: CardCandidate<K>, index: Int,
                selected: Boolean, focused: Boolean) {
                append(card.name)
                if (card.detail.isNotEmpty()) append("  ${card.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        emptyText.text = if (cards.isEmpty()) "No cards on the canvas yet" else "No matching cards"
    }
    fun filter() {
        listModel.clear()
        searchCards(cards, search.text).forEach(listModel::addElement)
        if (!listModel.isEmpty) list.selectedIndex = 0
    }
    filter()
    val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        border = JBUI.Borders.empty(6)
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(520), JBUI.scale(320)) }, BorderLayout.CENTER)
    }
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, search.textEditor)
        .setTitle("Find Card")
        .setRequestFocus(true)
        .setFocusable(true)
        .setMovable(true)
        .setResizable(true)
        .setCancelOnClickOutside(true)
        .createPopup()
    fun chooseSelected() {
        val card = list.selectedValue ?: return
        popup.closeOk(null)
        choose(card.key)
    }
    search.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = filter()
    })
    search.textEditor.addActionListener { chooseSelected() }
    ScrollingUtil.installActions(list, search.textEditor)
    list.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e) &&
                list.locationToIndex(e.point).let { it >= 0 && list.getCellBounds(it, it).contains(e.point) }) chooseSelected()
        }
    })
    popup.showUnderneathOf(owner)
    return popup
}
