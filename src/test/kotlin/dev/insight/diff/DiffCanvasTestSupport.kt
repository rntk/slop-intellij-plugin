package dev.insight.diff

import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.NoteCanvas

/** Shows [state] with the diff review's own pages [content], as the diff review renders its canvas. */
internal fun NoteCanvas.showNotes(cards: DiffCanvasCards, content: DiffCanvasContent, state: CanvasState = CanvasState()) {
    cards.content = content
    showNotes(state)
}
