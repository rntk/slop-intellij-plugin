package dev.insight.diff

import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** Shared entry actions for the split summary and native diff readers. */
internal fun summaryEntryMenu(
    node: SummaryNode,
    card: SummariesCard,
    state: ChangeSummariesViewState,
    actions: ChangeSummaryActions,
    vocabulary: ReviewVocabulary,
    actionPrefix: String,
    canFold: Boolean = node.children.isNotEmpty(),
    toggle: (String) -> Unit = actions.toggle,
): JPopupMenu = JPopupMenu().apply {
    fun item(text: String, enabled: Boolean = true, action: () -> Unit) = add(JMenuItem(text).apply {
        name = "$actionPrefix:$text"
        isEnabled = enabled
        addActionListener { action() }
    })
    fun foldLabel() = if (state.isOpen(node, card.summaries)) "Fold" else "Unfold"
    val fold = if (canFold)
        item(foldLabel()) { toggle(node.identity) } else null
    if (node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK) {
        item(if (vocabulary == ReviewVocabulary.DIFF) "Open diff" else "Open file") { actions.openNode(node) }
        val show = actions.showNode
        val showText = vocabulary.showOnCanvas
        if (show != null && showText != null) item(showText) { show(node) }
    }
    actions.wholeFileAction(node)?.let { item("Open file", action = it) }
    actions.directoryAction(node)?.let { item("Open diff", action = it) }
    actions.explainAction(node)?.let { item("Explain", card.canRun, it) }
    actions.inspectAction(node)?.let { item("Inspect code", action = it) }
    actions.addEntryItems(this, node)
    val reviewed = actions.review?.menuItem(node)?.also {
        addSeparator()
        add(it)
    }
    // Entry menus survive tree folds and checkbox toggles, so their labels follow the current state on every opening.
    addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
            fold?.let { it.text = foldLabel(); it.name = "$actionPrefix:${it.text}" }
            if (reviewed != null) actions.review.refresh(reviewed, node)
        }
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
        override fun popupMenuCanceled(e: PopupMenuEvent) {}
    })
}
