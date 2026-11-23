package dev.insight.diff.walk.ui

import dev.insight.diff.walk.LinkDirection
import dev.insight.diff.walk.WalkLinkGroup
import dev.insight.diff.walk.WalkRelation
import dev.insight.diff.walk.WalkStep

/** A card's size, in canvas pixels at 100%. */
internal data class WalkSize(val width: Int, val height: Int)

/** A placed card, in canvas pixels at 100%; the canvas has no edges, so coordinates may be negative. */
internal data class WalkRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    /** Whether this and [other], each grown by [margin] on every side, overlap. */
    fun overlaps(other: WalkRect, margin: Int = 0): Boolean =
        x - margin < other.right && other.x - margin < right && y - margin < other.bottom && other.y - margin < bottom

    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom

    fun union(other: WalkRect): WalkRect {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        return WalkRect(left, top, maxOf(right, other.right) - left, maxOf(bottom, other.bottom) - top)
    }
}

/** Which side of a card its linked cards open on: what led to it on the left, what it leads to on the right. */
internal enum class WalkSide { LEFT, RIGHT }

/** The side a link group opens on: the far end of an incoming directed link came first, so it goes left; others right. */
internal fun walkSide(direction: LinkDirection): WalkSide = if (direction == LinkDirection.INCOMING) WalkSide.LEFT else WalkSide.RIGHT

/** Identifies one of a hunk's link groups. */
internal data class WalkGroupKey(val relation: WalkRelation, val direction: LinkDirection) {
    val label: String get() = relation.label(direction)
}

internal val WalkLinkGroup.key: WalkGroupKey get() = WalkGroupKey(relation, direction)

/** A card on the walkthrough canvas: a diff hunk, or the note explaining a step. */
internal data class WalkCard(
    /** The hunk id for hunk cards, `note:<step id>` for notes. */
    val id: String,
    val kind: Kind,
    /** The hunk id or the step id. */
    val ref: String,
    val rect: WalkRect,
    /** The card this one was opened from; the trail follows it back to a root. */
    val parent: String? = null,
    /** The step a root card was opened for. */
    val step: String? = null,
) {
    enum class Kind { HUNK, NOTE }
}

internal fun walkNoteId(stepId: String) = "note:$stepId"

/**
 * An arrow between two cards, drawn from [from], the card it was opened from, to [to]. [group] is the link group, seen from
 * [from], that opened it; null for a hunk opened as part of its step without a direct link.
 */
internal data class WalkEdge(val from: String, val to: String, val group: WalkGroupKey?)

/** What opening something did: the card to bring into view and the cards added. */
internal data class WalkOpen(val reveal: String?, val added: List<String> = emptyList())

/** Spacing of the walkthrough canvas, in canvas pixels at 100%. */
internal data class WalkSpacing(
    /** Between a card and the column of cards opened beside it, leaving room for the arrows' labels. */
    val columnGap: Int = 140,
    /** Between cards of one column. */
    val rowGap: Int = 20,
    /** Between a step's note and its root card below it. */
    val noteGap: Int = 14,
    /** Above a new root opened below everything on the canvas. */
    val sectionGap: Int = 72,
    val left: Int = 40,
    val top: Int = 40,
)

/** How many linked cards one click opens; the rest wait behind a "show N more" chip. */
internal const val WALK_OPEN_BATCH = 6

/**
 * The cards a reader opened on the walkthrough canvas and the arrows between them, without Swing. A hunk is on the canvas at
 * most once: opening it again reveals the card already there. [measure] and [noteSize] give the cards' sizes; they stay
 * fixed for a card's life, so streaming updates of a run never move cards.
 */
internal class WalkBoard(
    private val measure: (hunkId: String) -> WalkSize,
    private val noteSize: (stepId: String) -> WalkSize,
    val spacing: WalkSpacing = WalkSpacing(),
) {
    private val byId = LinkedHashMap<String, WalkCard>()
    private val edgeList = mutableListOf<WalkEdge>()
    /** Link groups opened from a card, so its chips can offer the rest. */
    private val openedGroups = HashMap<String, MutableSet<WalkGroupKey>>()
    /** Steps whose hunks were opened from their note. */
    private val openedSteps = HashSet<String>()

    val cards: List<WalkCard> get() = byId.values.toList()
    val edges: List<WalkEdge> get() = edgeList.toList()
    val isEmpty: Boolean get() = byId.isEmpty()

    /** The focused card, which the trail leads to. */
    var focused: String? = null
        set(value) { field = value?.takeIf { it in byId } }

    fun card(id: String): WalkCard? = byId[id]
    operator fun contains(id: String): Boolean = id in byId
    fun opened(cardId: String, group: WalkGroupKey): Boolean = openedGroups[cardId]?.contains(group) == true
    fun stepOpened(stepId: String): Boolean = stepId in openedSteps

    /** The space every card covers, or null when the canvas is empty. */
    fun bounds(): WalkRect? = byId.values.map { it.rect }.reduceOrNull(WalkRect::union)

    /** The topmost card under ([x], [y]): the last placed paints on top. */
    fun cardAt(x: Int, y: Int): WalkCard? = byId.values.lastOrNull { it.rect.contains(x, y) }

    /** The cards from the root of [cardId]'s chain to it, following the cards each was opened from. */
    fun trail(cardId: String): List<WalkCard> {
        val chain = mutableListOf<WalkCard>()
        val seen = HashSet<String>()
        var at = byId[cardId]
        while (at != null && seen.add(at.id)) {
            chain += at
            at = at.parent?.let(byId::get)
        }
        return chain.asReversed()
    }

    /** Opens hunk [hunkId] on its own, below everything else, or reveals it when it is already on the canvas. */
    fun openHunk(hunkId: String): WalkOpen {
        if (hunkId in byId) return WalkOpen(hunkId)
        val size = measure(hunkId)
        add(WalkCard(hunkId, WalkCard.Kind.HUNK, hunkId, WalkRect(spacing.left, nextSectionTop(), size.width, size.height)))
        return WalkOpen(hunkId, listOf(hunkId))
    }

    /**
     * Opens [step]: its note with its root hunk below it, below everything else. A root already on the canvas is revealed
     * instead, with the note placed above it where there is room.
     */
    fun openStep(step: WalkStep): WalkOpen {
        val noteId = walkNoteId(step.id)
        val existing = byId[step.rootHunk]
        if (existing != null) {
            if (noteId !in byId) {
                val note = noteSize(step.id)
                val y = existing.rect.y - spacing.noteGap - note.height
                val rect = WalkRect(existing.rect.x, y, note.width, note.height)
                if (byId.values.none { it.rect.overlaps(rect, spacing.rowGap / 2) }) {
                    add(WalkCard(noteId, WalkCard.Kind.NOTE, step.id, rect, step = step.id))
                    if (existing.parent == null) add(existing.copy(parent = noteId, step = step.id))
                    return WalkOpen(step.rootHunk, listOf(noteId))
                }
            }
            return WalkOpen(step.rootHunk)
        }
        val note = noteSize(step.id)
        val top = nextSectionTop()
        val added = mutableListOf<String>()
        if (noteId !in byId) {
            add(WalkCard(noteId, WalkCard.Kind.NOTE, step.id, WalkRect(spacing.left, top, note.width, note.height), step = step.id))
            added += noteId
        }
        val size = measure(step.rootHunk)
        val rootTop = if (added.isEmpty()) top else top + note.height + spacing.noteGap
        add(WalkCard(step.rootHunk, WalkCard.Kind.HUNK, step.rootHunk, WalkRect(spacing.left, rootTop, size.width, size.height),
            parent = noteId.takeIf { it in byId }, step = step.id))
        added += step.rootHunk
        return WalkOpen(step.rootHunk, added)
    }

    /**
     * Leads back from card [cardId] to [step], a step showing it: the step's root, with its note above where there is room,
     * opens in a column left of the card, or is joined to it by an arrow when already on the canvas. [linked] is how the root
     * links to the card, if it does, which labels the arrow. On the step's own root this opens the step's note.
     */
    fun openStepOf(cardId: String, step: WalkStep, linked: WalkGroupKey? = null): WalkOpen {
        val card = byId[cardId] ?: return WalkOpen(null)
        if (card.ref == step.rootHunk) return openStep(step)
        val added = mutableListOf<String>()
        if (step.rootHunk !in byId) {
            val size = measure(step.rootHunk)
            val note = noteSize(step.id)
            // Room for the note above the root, so the step reads top down.
            val above = note.height + spacing.noteGap
            val spot = freeSpot(card.rect.x - spacing.columnGap - maxOf(size.width, note.width), card.rect.y - above,
                WalkSize(maxOf(size.width, note.width), above + size.height))
            add(WalkCard(step.rootHunk, WalkCard.Kind.HUNK, step.rootHunk,
                WalkRect(spot.x, spot.y + above, size.width, size.height), step = step.id))
            added += step.rootHunk
        }
        added += openStep(step).added
        connect(step.rootHunk, cardId, linked)
        return WalkOpen(step.rootHunk, added)
    }

    /** Whether [step] is shown for card [cardId]: its root joined to the card, or, on the root itself, its note on the canvas. */
    fun stepShownFor(cardId: String, step: WalkStep): Boolean =
        if (byId[cardId]?.ref == step.rootHunk) walkNoteId(step.id) in byId
        else step.rootHunk in byId && edgeList.any { (it.from == step.rootHunk && it.to == cardId) || (it.from == cardId && it.to == step.rootHunk) }

    /**
     * Opens the hunks of [group], seen from card [cardId], in a column beside it: up to [limit] not yet on the canvas, the
     * rest left for "show more". Hunks already on the canvas are joined by an arrow rather than opened again.
     */
    fun openGroup(cardId: String, group: WalkLinkGroup, limit: Int = WALK_OPEN_BATCH): WalkOpen {
        val parent = byId[cardId] ?: return WalkOpen(null)
        val key = group.key
        openedGroups.getOrPut(cardId) { HashSet() } += key
        val present = group.hunkIds.filter { it in byId && it != cardId }
        present.forEach { connect(cardId, it, key) }
        val fresh = group.hunkIds.filter { it !in byId }.take(limit.coerceAtLeast(0))
        val added = placeColumn(parent, fresh, walkSide(group.direction)) { connect(cardId, it, key) }
        return WalkOpen(added.firstOrNull() ?: present.firstOrNull(), added)
    }

    /** The hunks of [group] that opening it again would add: those not on the canvas. */
    fun remaining(group: WalkLinkGroup): Int = group.hunkIds.count { it !in byId }

    /**
     * Opens [step]'s other hunks in a column right of its root, [limit] at a time; [linked] says how a hunk is linked to the
     * root, if it is, which labels its arrow.
     */
    fun openStepHunks(step: WalkStep, linked: (hunkId: String) -> WalkGroupKey?, limit: Int = WALK_OPEN_BATCH): WalkOpen {
        val root = byId[step.rootHunk] ?: openStep(step).let { byId[step.rootHunk] } ?: return WalkOpen(null)
        openedSteps += step.id
        val fresh = step.hunks.filter { it !in byId }.take(limit.coerceAtLeast(0))
        val added = placeColumn(root, fresh, WalkSide.RIGHT) { connect(root.id, it, linked(it)) }
        return WalkOpen(added.firstOrNull() ?: step.rootHunk, added)
    }

    /** The hunks of [step] not on the canvas yet. */
    fun remaining(step: WalkStep): Int = step.hunks.count { it !in byId }

    /** Drops cards whose hunk or step is gone, with their arrows; cards opened from them become roots of their own. */
    fun retain(hunkIds: Set<String>, stepIds: Set<String>) {
        val gone = byId.values.filter { if (it.kind == WalkCard.Kind.HUNK) it.ref !in hunkIds else it.ref !in stepIds }.map { it.id }.toSet()
        if (gone.isEmpty()) return
        gone.forEach { byId.remove(it); openedGroups.remove(it) }
        byId.replaceAll { _, card -> if (card.parent in gone) card.copy(parent = null) else card }
        edgeList.removeAll { it.from in gone || it.to in gone }
        openedSteps.retainAll(stepIds)
        if (focused in gone) focused = null
    }

    fun clear() {
        byId.clear(); edgeList.clear(); openedGroups.clear(); openedSteps.clear(); focused = null
    }

    private fun add(card: WalkCard) { byId[card.id] = card }

    private fun connect(from: String, to: String, group: WalkGroupKey?) {
        if (from == to || edgeList.any { (it.from == from && it.to == to) || (it.from == to && it.to == from) }) return
        edgeList += WalkEdge(from, to, group)
    }

    /** Places [hunkIds] in a column on [side] of [parent], from its top down, each in the first free spot. */
    private fun placeColumn(parent: WalkCard, hunkIds: List<String>, side: WalkSide, joined: (String) -> Unit): List<String> {
        var y = parent.rect.y
        return hunkIds.map { id ->
            val size = measure(id)
            val x = if (side == WalkSide.LEFT) parent.rect.x - spacing.columnGap - size.width else parent.rect.right + spacing.columnGap
            val rect = freeSpot(x, y, size)
            add(WalkCard(id, WalkCard.Kind.HUNK, id, rect, parent = parent.id))
            joined(id)
            y = rect.bottom + spacing.rowGap
            id
        }
    }

    /** The first spot at ([x], [y]) or below it where a card of [size] overlaps none. */
    private fun freeSpot(x: Int, y: Int, size: WalkSize): WalkRect {
        var rect = WalkRect(x, y, size.width, size.height)
        while (true) {
            val blocking = byId.values.filter { it.rect.overlaps(rect, spacing.rowGap / 2) }.maxByOrNull { it.rect.bottom } ?: return rect
            rect = rect.copy(y = blocking.rect.bottom + spacing.rowGap)
        }
    }

    private fun nextSectionTop(): Int = bounds()?.let { it.bottom + spacing.sectionGap } ?: spacing.top
}
