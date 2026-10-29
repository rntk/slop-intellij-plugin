package dev.insight.review.ui

import com.intellij.openapi.Disposable
import dev.insight.review.CanvasTabKey
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * A review's own canvas pages beside the shared notes, sources, summaries, topics and File view, and the summaries Diff mode.
 * The extension keeps their content; [NoteCanvas] places, titles, refreshes and disposes them. Diff supplies its metric
 * cards and diff snapshots; code review has none.
 */
internal interface CanvasExtension : Disposable {
    /** Lets the extension rebuild one of its pages in place; called once by the canvas that hosts it. */
    fun attach(refresh: (CanvasTabKey) -> Unit)

    /** Applies the current content at the start of every full rebuild, before any card is built. */
    fun update()

    /** Pages placed after Topics and before the summaries, in order. */
    fun leadingCards(): Map<CanvasTabKey, JPanel>

    /** Pages placed after the source cards, in order; [snapshots] draws their card frame. */
    fun trailingCards(snapshots: SnapshotCanvasCards): Map<CanvasTabKey, JPanel>

    /** A fresh card for one of this extension's pages; null when [key] is not one of them. */
    fun card(key: CanvasTabKey, snapshots: SnapshotCanvasCards): JPanel?

    /** The tab title of one of this extension's pages; null when [key] is not one of them. */
    fun title(key: CanvasTabKey): String?

    /** The summaries Diff mode; null shows the summaries as an outline instead. */
    fun summaryDiffView(card: SummariesCard, state: ChangeSummariesViewState, zoom: Double, actions: ChangeSummaryActions): SummaryDiffView?
}

/** The content of the summaries Diff mode beside its file tree. */
internal interface SummaryDiffView : Disposable {
    fun withTree(width: Int, height: Int, treeWidth: Int?, rememberTreeWidth: (Int) -> Unit): JSplitPane
}
