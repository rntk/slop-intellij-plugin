package dev.insight.diff.walk.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.canvas.CanvasMotion
import dev.insight.canvas.CanvasPlacement
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffsWallHighlighter
import dev.insight.diff.walk.HunkStatus
import dev.insight.diff.walk.WalkHunk
import dev.insight.diff.walk.WalkLink
import dev.insight.diff.walk.WalkRelation
import dev.insight.diff.walk.WalkStep
import dev.insight.diff.walk.WalkStepKind
import dev.insight.diff.walk.Walkthrough
import dev.insight.review.wall.WallBlock
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallSpan
import dev.insight.review.wall.wallSegments
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private val LOG = logger<WalkCanvas>()

/** What the walkthrough canvas asks its page to do. */
internal class WalkCanvasActions(
    /** Opens the native diff of [hunk]'s file, at [line] when a row was double-clicked. */
    val openDiff: (hunk: WalkHunk, line: WallLine?) -> Unit = { _, _ -> },
    /** The focused card changed, so the trail and the list follow it. */
    val focusChanged: (cardId: String?) -> Unit = {},
    /** Cards were added or removed, so "not on the canvas" lists change. */
    val boardChanged: () -> Unit = {},
    val zoomChanged: (Double) -> Unit = {},
    val showContext: ((WalkHunk, WallLine?, RelativePoint) -> Unit)? = null,
    /** Opens or folds the chat about a hunk card; null paints no chat chip. */
    val toggleChat: ((hunkId: String) -> Unit)? = null,
    /** How a hunk card's chat chip shows its chats. */
    val chatMark: (hunkId: String) -> WalkChatMark = { WalkChatMark() },
)

/** A hunk card's chats as its chip shows them: [open] beside the canvas, [started] with a question, or [busy] answering. */
internal data class WalkChatMark(val open: Boolean = false, val started: Boolean = false, val busy: Boolean = false)

/**
 * The walkthrough's canvas: diff hunks as cards with their link groups as chips, a step's note above its root, and arrows
 * labelled with the relation between cards opened from one another. It has no edges: dragging empty space, or Alt+dragging
 * anywhere, pans it; Ctrl/Cmd+wheel zooms. Moves it makes for the reader glide. Cards are painted, not components, so
 * hundreds stay cheap; their rows reuse the Diffs wall's parsing and syntax colours.
 */
internal class WalkCanvas(
    private val project: Project?,
    private val actions: WalkCanvasActions = WalkCanvasActions(),
    private val highlight: Boolean = project != null,
) : JComponent(), Disposable {
    var walkthrough: Walkthrough = Walkthrough(emptyList())
        private set
    private var files: Map<String, ReviewFile> = emptyMap()
    private val rows = HashMap<String, Pair<WalkHunk, WalkHunkRows>>()
    private val spans = HashMap<String, Pair<WallBlock, List<List<WallSpan>?>?>>()
    private val highlighting = HashSet<String>()
    private var highlightGeneration = 0
    internal val board = WalkBoard(::measureHunk, ::measureNote,
        WalkSpacing(JBUI.scale(140), JBUI.scale(20), JBUI.scale(14), JBUI.scale(72), JBUI.scale(40), JBUI.scale(40)))
    internal var zoom = 1.0
        private set
    /** The canvas point at the viewport's top left. */
    internal var cameraX = 0.0
        private set
    internal var cameraY = 0.0
        private set
    private val cameraMotion = CanvasMotion(260_000_000L)
    private val cardMotion = CanvasMotion()
    private val animatedCards = HashSet<String>()
    private var gliding = false
    private val frames = Timer(1000 / 60) { frame() }.apply { isCoalesce = true }
    private val hits = mutableListOf<Hit>()
    private var hover: Hit? = null
    private var pan: Pan? = null
    private var pendingReveal: String? = null
    private var disposed = false
    private var metricsCache: Metrics? = null
    private var codeFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
    private var uiFont: Font = UIUtil.getLabelFont()
    private var smallFont: Font = uiFont

    private class Pan(val start: Point, val cameraX: Double, val cameraY: Double) { var moved = false }

    /** A painted region the pointer can act on, in canvas pixels. */
    private sealed interface Hit {
        val rect: Rectangle
        data class Card(val card: String, override val rect: Rectangle) : Hit
        data class Chip(val card: String, val group: WalkGroupKey, override val rect: Rectangle) : Hit
        data class More(val card: String, val group: WalkGroupKey, override val rect: Rectangle) : Hit
        data class OpenAll(val card: String, override val rect: Rectangle) : Hit
        data class StepMore(val step: String, override val rect: Rectangle) : Hit
        /** Leads from a card back to a step showing it. */
        data class Step(val card: String, val step: String, override val rect: Rectangle) : Hit
        data class Status(val card: String, override val rect: Rectangle) : Hit
        /** Opens or folds the chat about a hunk card. */
        data class Chat(val card: String, override val rect: Rectangle) : Hit
        data class Row(val card: String, val line: WallLine, override val rect: Rectangle) : Hit
        data class Edge(val edge: WalkEdge, val points: List<Point2D.Double>, override val rect: Rectangle) : Hit
    }

    private inner class Metrics {
        val code: FontMetrics = getFontMetrics(codeFont)
        val ui: FontMetrics = getFontMetrics(uiFont)
        val bold: FontMetrics = getFontMetrics(uiFont.deriveFont(Font.BOLD))
        val small: FontMetrics = getFontMetrics(smallFont)
        val pad = JBUI.scale(8)
        val line = code.height
        val char = maxOf(1, code.charWidth('m'))
        val gutter = char * 10 + JBUI.scale(6)
        val title = ui.height + JBUI.scale(4)
        val place = small.height + JBUI.scale(4)
        val chips = small.height + JBUI.scale(10)
        val width = (pad + gutter + char * 84 + pad) * 6 / 5
        val noteLines = 12
    }

    private fun metrics(): Metrics = metricsCache ?: Metrics().also { metricsCache = it }

    init {
        isOpaque = true
        isFocusable = true
        focusTraversalKeysEnabled = false
        name = "walkthrough-canvas"
        loadFonts()
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = pressed(e)
            override fun mouseDragged(e: MouseEvent) = dragged(e)
            override fun mouseReleased(e: MouseEvent) = released(e)
            override fun mouseClicked(e: MouseEvent) = clicked(e)
            override fun mouseMoved(e: MouseEvent) = moved(e)
            override fun mouseExited(e: MouseEvent) { if (hover != null) { hover = null; repaint() } }
            override fun mouseWheelMoved(e: MouseWheelEvent) = wheel(e)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { pendingReveal?.let { pendingReveal = null; reveal(it, glide = false) } }
        })
        installKeys()
    }

    override fun updateUI() {
        super.updateUI()
        loadFonts()
        metricsCache = null
        highlightGeneration++
        spans.clear(); highlighting.clear()
    }

    private fun loadFonts() {
        codeFont = try {
            EditorColorsManager.getInstance().globalScheme.let { it.getFont(EditorFontType.PLAIN).deriveFont(it.editorFontSize2D) }
        } catch (e: Exception) {
            Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
        }
        uiFont = UIUtil.getLabelFont()
        smallFont = uiFont.deriveFont(uiFont.size2D * 0.9f)
    }

    /**
     * Shows [walkthrough] of [files] in place: cards, pan and zoom stay; chips and arrows follow its links. A card whose hunk
     * left the walkthrough, or whose patch changed, is removed.
     */
    fun show(walkthrough: Walkthrough, files: List<ReviewFile>) {
        this.walkthrough = walkthrough
        this.files = files.associateBy { it.path }
        val hunks = walkthrough.hunks.associateBy { it.id }
        val stale = rows.filter { (id, cached) -> hunks[id]?.patch != cached.first.patch }.keys
        stale.forEach { rows.remove(it); spans.remove(it) }
        val before = board.cards.size
        board.retain(hunks.keys - stale, walkthrough.steps.mapTo(HashSet()) { it.id })
        animatedCards.retainAll(board.cards.mapTo(HashSet()) { it.id })
        if (board.cards.size != before) actions.boardChanged()
        repaint()
    }

    /** Removes every card. */
    fun clear() {
        board.clear()
        animatedCards.clear()
        cardMotion.clear()
        actions.boardChanged()
        actions.focusChanged(null)
        repaint()
    }

    fun openStep(stepId: String) {
        val step = walkthrough.step(stepId) ?: return
        apply(board.openStep(step))
    }

    fun openHunk(hunkId: String) {
        if (walkthrough.hunk(hunkId) == null) return
        apply(board.openHunk(hunkId))
    }

    fun openGroup(cardId: String, key: WalkGroupKey) {
        val group = walkthrough.linksOf(cardId).firstOrNull { it.key == key } ?: return
        apply(board.openGroup(cardId, group))
    }

    /** Opens a batch of every link group of [cardId]. */
    fun openAllLinked(cardId: String) {
        val opens = walkthrough.linksOf(cardId).map { board.openGroup(cardId, it) }
        apply(WalkOpen(opens.firstNotNullOfOrNull { it.added.firstOrNull() } ?: opens.firstNotNullOfOrNull { it.reveal }, opens.flatMap { it.added }))
    }

    fun openStepHunks(stepId: String) {
        val step = walkthrough.step(stepId) ?: return
        apply(board.openStepHunks(step, linked = { id -> walkthrough.linksOf(step.rootHunk).firstOrNull { id in it.hunkIds }?.key }))
    }

    /** Leads from card [cardId] back to step [stepId]: its root beside the card, joined by an arrow, with its note. */
    fun openStepOf(cardId: String, stepId: String) {
        val step = walkthrough.step(stepId) ?: return
        apply(board.openStepOf(cardId, step, linked = walkthrough.linksOf(step.rootHunk).firstOrNull { cardId in it.hunkIds }?.key))
    }

    /** The steps a hunk card leads back to: those showing it, the unlinked list left out. */
    private fun backSteps(hunkId: String): List<WalkStep> = walkthrough.stepsOf(hunkId).filter { it.kind != WalkStepKind.UNLINKED }

    private fun apply(open: WalkOpen) {
        val now = System.nanoTime()
        open.added.forEach { id ->
            val card = board.card(id) ?: return@forEach
            animatedCards += id
            cardMotion.target(id, CanvasPlacement(card.rect.x, card.rect.y), now, animate = isShowing)
        }
        if (open.added.isNotEmpty()) {
            if (isShowing) frames.start()
            actions.boardChanged()
        }
        open.reveal?.let { reveal(it) }
        repaint()
    }

    /** Focuses [cardId] and glides the view to it: centred when it fits, else its top left in view. */
    fun reveal(cardId: String, glide: Boolean = true) {
        val card = board.card(cardId) ?: return
        focus(cardId)
        if (width <= 0 || height <= 0) { pendingReveal = cardId; return }
        val margin = JBUI.scale(24) / zoom
        val viewWidth = width / zoom
        val viewHeight = height / zoom
        val r = card.rect
        val x = if (r.width + 2 * margin <= viewWidth) r.centerX - viewWidth / 2 else r.x - margin
        val y = if (r.height + 2 * margin <= viewHeight) r.centerY - viewHeight / 2 else r.y - margin
        if (glide) glideTo(x, y) else { stopGlide(); cameraX = x; cameraY = y; repaint() }
    }

    fun focus(cardId: String?) {
        if (board.focused == cardId) return
        board.focused = cardId
        actions.focusChanged(board.focused)
        repaint()
    }

    private fun glideTo(x: Double, y: Double) {
        if (!isShowing) { cameraX = x; cameraY = y; repaint(); return }
        val now = System.nanoTime()
        cameraMotion.target(CAMERA, CanvasPlacement(cameraX.roundToInt(), cameraY.roundToInt()), now, animate = false)
        cameraMotion.target(CAMERA, CanvasPlacement(x.roundToInt(), y.roundToInt()), now, animate = true)
        gliding = true
        frames.start()
    }

    private fun stopGlide() { gliding = false }

    private fun frame() {
        val now = System.nanoTime()
        if (gliding) {
            val at = cameraMotion.position(CAMERA, now)
            cameraX = at.x.toDouble(); cameraY = at.y.toDouble()
            if (!cameraMotion.active(now)) gliding = false
        }
        if (!gliding && !cardMotion.active(now)) frames.stop()
        repaint()
    }

    fun zoomTo(target: Double, anchor: Point = Point(width / 2, height / 2)) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        stopGlide()
        val x = cameraX + anchor.x / zoom
        val y = cameraY + anchor.y / zoom
        zoom = next
        cameraX = x - anchor.x / zoom
        cameraY = y - anchor.y / zoom
        actions.zoomChanged(zoom)
        repaint()
    }

    /** Zooms out, never past 100%, so every card fits, and centres them. */
    fun fit() {
        val bounds = board.bounds() ?: return
        if (width <= 0 || height <= 0) return
        val margin = JBUI.scale(24)
        zoom = minOf((width - 2.0 * margin) / bounds.width, (height - 2.0 * margin) / bounds.height, 1.0).coerceIn(MIN_ZOOM, MAX_ZOOM)
        cameraX = bounds.centerX - width / zoom / 2
        cameraY = bounds.centerY - height / zoom / 2
        stopGlide()
        actions.zoomChanged(zoom)
        repaint()
    }

    private fun installKeys() {
        fun bind(name: String, vararg strokes: String, run: () -> Unit) {
            strokes.forEach { getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(it), name) }
            actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = run() })
        }
        bind("walk-escape", "ESCAPE") { focus(null) }
        bind("walk-zoom-reset", "typed 0") { zoomTo(1.0) }
        bind("walk-zoom-in", "typed +", "typed =") { zoomTo(zoom * KEY_ZOOM) }
        bind("walk-zoom-out", "typed -") { zoomTo(zoom / KEY_ZOOM) }
        bind("walk-fit", "typed f") { fit() }
        bind("walk-next", "TAB") { step(forward = true) }
        bind("walk-previous", "shift TAB") { step(forward = false) }
        bind("walk-open-linked", "ENTER") { board.focused?.takeIf { board.card(it)?.kind == WalkCard.Kind.HUNK }?.let(::openAllLinked) }
        val step = { JBUI.scale(48) / zoom }
        fun page(vertical: Boolean): Double = maxOf((if (vertical) height else width) / zoom - step(), step())
        fun move(dx: Double, dy: Double) {
            stopGlide()
            cameraX += dx
            cameraY += dy
            repaint()
        }
        fun edge(end: Boolean, horizontal: Boolean = false) {
            val bounds = board.bounds() ?: return
            stopGlide()
            val margin = JBUI.scale(24) / zoom
            cameraY = if (end) bounds.bottom + margin - height / zoom else bounds.y - margin
            if (horizontal) cameraX = if (end) bounds.right + margin - width / zoom else bounds.x - margin
            repaint()
        }
        bind("walk-up", "UP", "KP_UP") { move(0.0, -step()) }
        bind("walk-down", "DOWN", "KP_DOWN") { move(0.0, step()) }
        bind("walk-left", "LEFT", "KP_LEFT", "shift LEFT", "shift KP_LEFT", "shift UP", "shift KP_UP") { move(-step(), 0.0) }
        bind("walk-right", "RIGHT", "KP_RIGHT", "shift RIGHT", "shift KP_RIGHT", "shift DOWN", "shift KP_DOWN") { move(step(), 0.0) }
        bind("walk-page-up", "PAGE_UP", "shift SPACE") { move(0.0, -page(true)) }
        bind("walk-page-down", "PAGE_DOWN", "SPACE") { move(0.0, page(true)) }
        bind("walk-page-left", "shift PAGE_UP") { move(-page(false), 0.0) }
        bind("walk-page-right", "shift PAGE_DOWN") { move(page(false), 0.0) }
        bind("walk-top", "HOME") { edge(end = false) }
        bind("walk-bottom", "END") { edge(end = true) }
        bind("walk-start", "ctrl HOME", "meta HOME") { edge(end = false, horizontal = true) }
        bind("walk-end", "ctrl END", "meta END") { edge(end = true, horizontal = true) }
    }

    private fun step(forward: Boolean) {
        val cards = board.cards
        if (cards.isEmpty()) return
        val index = cards.indexOfFirst { it.id == board.focused }
        val next = when {
            index < 0 -> if (forward) 0 else cards.lastIndex
            forward -> (index + 1) % cards.size
            else -> (index - 1 + cards.size) % cards.size
        }
        reveal(cards[next].id)
    }

    // Pointer handling. Screen points become canvas points through the camera and zoom.

    private fun canvasPoint(e: MouseEvent) = Point2D.Double(cameraX + e.x / zoom, cameraY + e.y / zoom)

    private fun hitAt(e: MouseEvent): Hit? {
        val p = canvasPoint(e)
        val px = p.x.roundToInt()
        val py = p.y.roundToInt()
        hits.asReversed().firstOrNull { it !is Hit.Edge && it.rect.contains(px, py) }?.let { return it }
        val tolerance = JBUI.scale(5) / zoom
        return hits.asReversed().filterIsInstance<Hit.Edge>().firstOrNull { hit ->
            Rectangle(hit.rect).apply { grow(tolerance.toInt() + 1, tolerance.toInt() + 1) }.contains(px, py) && distance(hit.points, p) <= tolerance
        }
    }

    private fun distance(points: List<Point2D.Double>, p: Point2D.Double): Double =
        points.zipWithNext().minOfOrNull { (a, b) -> java.awt.geom.Line2D.ptSegDist(a.x, a.y, b.x, b.y, p.x, p.y) } ?: Double.MAX_VALUE

    private fun pressed(e: MouseEvent) {
        requestFocusInWindow()
        stopGlide()
        if (showContext(e)) return
        val hit = hitAt(e)
        val panAnywhere = e.isAltDown || javax.swing.SwingUtilities.isMiddleMouseButton(e)
        if (panAnywhere || (javax.swing.SwingUtilities.isLeftMouseButton(e) && (hit == null || hit is Hit.Edge))) {
            pan = Pan(e.point, cameraX, cameraY)
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }
    }

    private fun dragged(e: MouseEvent) {
        val pan = pan ?: return
        val dx = e.x - pan.start.x
        val dy = e.y - pan.start.y
        if (abs(dx) + abs(dy) > JBUI.scale(3)) pan.moved = true
        cameraX = pan.cameraX - dx / zoom
        cameraY = pan.cameraY - dy / zoom
        repaint()
    }

    private fun released(e: MouseEvent) {
        val moved = pan?.moved == true
        pan = null
        cursor = Cursor.getDefaultCursor()
        if (moved) e.consume() else showContext(e)
    }

    private fun showContext(e: MouseEvent): Boolean {
        val show = actions.showContext ?: return false
        if (!e.isPopupTrigger || e.isAltDown || e.isConsumed) return false
        val hit = hitAt(e)
        val id = when (hit) {
            is Hit.Card -> hit.card
            is Hit.Row -> hit.card
            is Hit.Status -> hit.card
            is Hit.Chat -> hit.card
            is Hit.Step -> hit.card
            is Hit.Chip -> hit.card
            is Hit.More -> hit.card
            is Hit.OpenAll -> hit.card
            else -> return false
        }
        val hunk = walkthrough.hunk(id) ?: return false
        focus(id)
        val line = (hit as? Hit.Row)?.line?.takeIf { it.old != null || it.new != null } ?: firstChangedRow(hunk)
        show(hunk, line, RelativePoint(e))
        e.consume()
        return true
    }

    private fun firstChangedRow(hunk: WalkHunk): WallLine? =
        rows[hunk.id]?.second?.lines?.firstOrNull { it.kind == WallLine.Kind.ADDED || it.kind == WallLine.Kind.REMOVED }

    private fun clicked(e: MouseEvent) {
        if (e.isConsumed || !javax.swing.SwingUtilities.isLeftMouseButton(e) || e.isAltDown) return
        val hit = hitAt(e)
        if (e.clickCount >= 2) {
            when (hit) {
                is Hit.Row -> walkthrough.hunk(hit.card)?.let { actions.openDiff(it, hit.line) }
                is Hit.Card -> walkthrough.hunk(hit.card)?.let { hunk ->
                    actions.openDiff(hunk, firstChangedRow(hunk))
                }
                else -> {}
            }
            return
        }
        when (hit) {
            null -> focus(null)
            is Hit.Chip -> { focus(hit.card); openGroup(hit.card, hit.group) }
            is Hit.More -> { focus(hit.card); openGroup(hit.card, hit.group) }
            is Hit.OpenAll -> { focus(hit.card); openAllLinked(hit.card) }
            is Hit.StepMore -> openStepHunks(hit.step)
            is Hit.Step -> { focus(hit.card); openStepOf(hit.card, hit.step) }
            is Hit.Card -> focus(hit.card)
            is Hit.Status -> focus(hit.card)
            is Hit.Chat -> { focus(hit.card); actions.toggleChat?.invoke(hit.card) }
            is Hit.Row -> focus(hit.card)
            is Hit.Edge -> reveal(hit.edge.to)
        }
    }

    private fun moved(e: MouseEvent) {
        val hit = hitAt(e)
        if (hit != hover) {
            hover = hit
            cursor = when (hit) {
                is Hit.Chip, is Hit.More, is Hit.OpenAll, is Hit.StepMore, is Hit.Step, is Hit.Chat, is Hit.Edge -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else -> Cursor.getDefaultCursor()
            }
            repaint()
        }
    }

    private fun wheel(e: MouseWheelEvent) {
        val rotation = e.preciseWheelRotation
        if (e.isControlDown || e.isMetaDown) {
            zoomTo(zoom * Math.pow(WHEEL_ZOOM, -rotation), e.point)
            return
        }
        stopGlide()
        val step = rotation * JBUI.scale(48) / zoom
        if (e.isShiftDown) cameraX += step else cameraY += step
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val hit = hitAt(event) ?: return null
        fun name(id: String) = walkthrough.hunk(id)?.let(::walkHunkName) ?: id
        fun reasons(from: String, links: List<WalkLink>): String? {
            if (links.isEmpty()) return null
            val lines = walkLinkReasons(from, links, ::name)
            val shown = lines.take(MAX_TOOLTIP_REASONS).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
            val more = (lines.size - MAX_TOOLTIP_REASONS).takeIf { it > 0 }?.let { "<br><i>and $it more</i>" }.orEmpty()
            return "<html><body style='width: 420px'>$shown$more</body></html>"
        }
        return when (hit) {
            is Hit.Chip -> walkthrough.linksOf(hit.card).firstOrNull { it.key == hit.group }?.let { group ->
                reasons(hit.card, group.links)?.let { it.replace("<html><body style='width: 420px'>",
                    "<html><body style='width: 420px'><b>Click to open ${if (board.opened(hit.card, hit.group)) "the rest" else "them"} beside this card</b><br>") }
            }
            is Hit.More -> "Open the next ${WALK_OPEN_BATCH} linked changes"
            is Hit.OpenAll -> "Open the linked changes of every chip (Enter)"
            is Hit.StepMore -> "Open the step's next ${WALK_OPEN_BATCH} changes beside its root"
            is Hit.Step -> walkthrough.step(hit.step)?.let { step ->
                val what = if (step.rootHunk == hit.card) "Show this step's note" else "Open this step's root and note beside this card"
                "<html><b>$what</b><br>${StringUtil.escapeXmlEntities(step.title)}</html>"
            }
            is Hit.Status -> walkStatusTooltip(walkthrough.statusOf(hit.card))
            is Hit.Chat -> actions.chatMark(hit.card).let { mark ->
                when {
                    mark.open -> "Fold this change's chat"
                    mark.busy -> "Answering · open the chat about this change and what it is linked to"
                    mark.started -> "Open the chat about this change and what it is linked to"
                    else -> "Chat about this change and what it is linked to"
                }
            }
            is Hit.Row -> "Double-click to open the diff at this line"
            is Hit.Edge -> {
                val from = board.card(hit.edge.from)?.ref ?: return null
                val to = board.card(hit.edge.to)?.ref ?: return null
                reasons(from, walkthrough.linksTouching(from).filter { it.other(from) == to })
                    ?: "In the same step as ${StringUtil.escapeXmlEntities(name(from))}"
            }
            is Hit.Card -> walkthrough.hunk(hit.card)?.let { "${walkHunkTitle(it)} · ${walkHunkPlace(it)} · double-click to open the diff" }
                ?: walkthrough.step(hit.card.removePrefix("note:"))?.title
        }
    }

    // Sizes. Cards keep the size they were placed with.

    private fun rowsOf(hunk: WalkHunk): WalkHunkRows {
        val cached = rows[hunk.id]
        if (cached != null && cached.first.patch == hunk.patch) return cached.second
        return walkHunkRows(hunk, files[hunk.path]).also { rows[hunk.id] = hunk to it }
    }

    private fun measureHunk(hunkId: String): WalkSize {
        val m = metrics()
        val hunk = walkthrough.hunk(hunkId) ?: return WalkSize(m.width, m.pad * 2 + m.title + m.chips)
        val rows = rowsOf(hunk)
        val body = (rows.lines.size + if (rows.hidden > 0) 1 else 0) * m.line
        return WalkSize(m.width, m.pad + m.title + m.place + body + JBUI.scale(6) + m.chips + m.pad)
    }

    private fun noteLines(stepId: String, maxLines: Int): List<String> {
        val m = metrics()
        val step = walkthrough.step(stepId) ?: return emptyList()
        return walkWrap(walkPlainText(step.explanation), m.width - 2 * m.pad, m.ui::stringWidth, maxLines)
    }

    private fun measureNote(stepId: String): WalkSize {
        val m = metrics()
        val lines = noteLines(stepId, m.noteLines).size.coerceAtLeast(1)
        return WalkSize(m.width, m.pad + m.title + lines * m.ui.height + JBUI.scale(4) + m.chips + m.pad)
    }

    // Painting.

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = CANVAS_BACKGROUND
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            hits.clear()
            if (board.isEmpty) {
                paintHint(g2)
                return
            }
            g2.scale(zoom, zoom)
            g2.translate(-cameraX, -cameraY)
            val visible = Rectangle(cameraX.toInt() - 1, cameraY.toInt() - 1, (width / zoom).toInt() + 2, (height / zoom).toInt() + 2)
            val now = System.nanoTime()
            val placed = board.cards.associate { it.id to placed(it, now) }
            paintNoteConnectors(g2, placed)
            board.edges.forEach { paintEdge(g2, it, placed) }
            val toHighlight = mutableListOf<String>()
            board.cards.forEach { card ->
                val rect = placed.getValue(card.id)
                if (!rect.intersects(visible)) return@forEach
                val opacity = if (card.id in animatedCards) cardMotion.opacity(card.id, now) else 1f
                val cg = g2.create() as Graphics2D
                try {
                    if (opacity < 1f) cg.composite = AlphaComposite.SrcOver.derive(opacity.coerceIn(0f, 1f))
                    when (card.kind) {
                        WalkCard.Kind.HUNK -> walkthrough.hunk(card.ref)?.let { paintHunk(cg, card, rect, it); toHighlight += it.id }
                        WalkCard.Kind.NOTE -> paintNote(cg, card, rect)
                    }
                } finally {
                    cg.dispose()
                }
            }
            requestHighlights(toHighlight)
        } finally {
            g2.dispose()
        }
    }

    private fun placed(card: WalkCard, now: Long): Rectangle {
        if (card.id !in animatedCards) return Rectangle(card.rect.x, card.rect.y, card.rect.width, card.rect.height)
        val at = cardMotion.position(card.id, now)
        return Rectangle(at.x, at.y, card.rect.width, card.rect.height)
    }

    private fun paintHint(g2: Graphics2D) {
        g2.font = uiFont
        g2.color = UIUtil.getContextHelpForeground()
        val text = if (walkthrough.hunks.isEmpty()) "No changes to walk through."
            else "Pick a step or a change on the left to open it here; its chips open what it is linked to."
        val fm = g2.fontMetrics
        g2.drawString(text, ((width - fm.stringWidth(text)) / 2).coerceAtLeast(JBUI.scale(12)), height / 2)
    }

    private fun paintNoteConnectors(g2: Graphics2D, placed: Map<String, Rectangle>) {
        g2.color = NOTE_BORDER
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f)
        board.cards.filter { it.kind == WalkCard.Kind.HUNK && it.parent?.startsWith("note:") == true }.forEach { card ->
            val note = placed[card.parent] ?: return@forEach
            val root = placed[card.id] ?: return@forEach
            g2.drawLine(note.centerX.toInt(), note.y + note.height, root.centerX.toInt(), root.y)
        }
    }

    private fun anchorY(rect: Rectangle): Int = rect.y + metrics().pad + metrics().title / 2

    private fun paintEdge(g2: Graphics2D, edge: WalkEdge, placed: Map<String, Rectangle>) {
        val a = placed[edge.from] ?: return
        val b = placed[edge.to] ?: return
        val fromRef = board.card(edge.from)?.ref ?: return
        val toRef = board.card(edge.to)?.ref ?: return
        val links = walkthrough.linksTouching(fromRef).filter { it.other(fromRef) == toRef }
        val relation = edge.group?.relation ?: links.firstOrNull()?.relation
        val color = relation?.let(::relationColor) ?: STEP_EDGE
        val weak = links.isNotEmpty() && links.all { it.weak }
        val (start, end) = when {
            b.x >= a.x + a.width -> Point2D.Double((a.x + a.width).toDouble(), anchorY(a).toDouble()) to Point2D.Double(b.x.toDouble(), anchorY(b).toDouble())
            b.x + b.width <= a.x -> Point2D.Double(a.x.toDouble(), anchorY(a).toDouble()) to Point2D.Double((b.x + b.width).toDouble(), anchorY(b).toDouble())
            b.y >= a.y -> Point2D.Double(a.centerX, (a.y + a.height).toDouble()) to Point2D.Double(b.centerX, b.y.toDouble())
            else -> Point2D.Double(a.centerX, a.y.toDouble()) to Point2D.Double(b.centerX, (b.y + b.height).toDouble())
        }
        val horizontal = abs(end.x - start.x) >= abs(end.y - start.y) / 4
        val bend = if (horizontal) (end.x - start.x) / 2 else 0.0
        val vbend = if (horizontal) 0.0 else (end.y - start.y) / 2
        val c1 = Point2D.Double(start.x + bend, start.y + vbend)
        val c2 = Point2D.Double(end.x - bend, end.y - vbend)
        val points = (0..SEGMENTS).map { i -> bezier(start, c1, c2, end, i.toDouble() / SEGMENTS) }
        val hovered = (hover as? Hit.Edge)?.edge == edge
        val width = JBUI.scale(if (hovered) 3 else if (board.focused == edge.from || board.focused == edge.to) 2 else 1).toFloat() + 0.5f
        g2.color = color
        g2.stroke = if (weak || edge.group == null && links.isEmpty())
            BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 5f), 0f)
            else BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val path = Path2D.Double().apply {
            moveTo(start.x, start.y)
            curveTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }
        g2.draw(path)
        g2.stroke = BasicStroke(width)
        val arrowAtTo = links.any { !it.relation.symmetric && it.from == fromRef }
        val arrowAtFrom = links.any { !it.relation.symmetric && it.from == toRef }
        if (arrowAtTo) arrow(g2, points[SEGMENTS - 1], end)
        if (arrowAtFrom) arrow(g2, points[1], start)
        val label = edge.group?.label ?: links.firstOrNull()?.let { link ->
            WalkGroupKey(link.relation, if (link.relation.symmetric) dev.insight.diff.walk.LinkDirection.SYMMETRIC
                else if (link.from == fromRef) dev.insight.diff.walk.LinkDirection.OUTGOING else dev.insight.diff.walk.LinkDirection.INCOMING).label
        } ?: "in step"
        if (smallFont.size2D * zoom >= MIN_TEXT_SIZE) {
            val mid = points[SEGMENTS / 2]
            g2.font = smallFont
            val fm = g2.fontMetrics
            val w = fm.stringWidth(label) + JBUI.scale(8)
            val h = fm.height
            val box = Rectangle((mid.x - w / 2).toInt(), (mid.y - h / 2).toInt(), w, h)
            g2.color = CANVAS_BACKGROUND
            g2.fillRoundRect(box.x, box.y, box.width, box.height, h, h)
            g2.color = color
            g2.drawString(label, box.x + JBUI.scale(4), box.y + fm.ascent)
        }
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        val bounds = Rectangle(xs.min().toInt() - 6, ys.min().toInt() - 6, (xs.max() - xs.min()).toInt() + 12, (ys.max() - ys.min()).toInt() + 12)
        hits += Hit.Edge(edge, points, bounds)
    }

    private fun bezier(p0: Point2D.Double, p1: Point2D.Double, p2: Point2D.Double, p3: Point2D.Double, t: Double): Point2D.Double {
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Point2D.Double(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }

    private fun arrow(g2: Graphics2D, from: Point2D.Double, to: Point2D.Double) {
        val angle = atan2(to.y - from.y, to.x - from.x)
        val size = JBUI.scale(8).toDouble()
        val head = Path2D.Double().apply {
            moveTo(to.x, to.y)
            lineTo(to.x - size * Math.cos(angle - 0.45), to.y - size * Math.sin(angle - 0.45))
            lineTo(to.x - size * Math.cos(angle + 0.45), to.y - size * Math.sin(angle + 0.45))
            closePath()
        }
        g2.fill(head)
    }

    private fun cardFrame(g2: Graphics2D, rect: Rectangle, fill: Color, border: Color, focused: Boolean) {
        val arc = JBUI.scale(10).toDouble()
        val shape = RoundRectangle2D.Double(rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(), arc, arc)
        g2.color = fill
        g2.fill(shape)
        g2.color = if (focused) FOCUS_BORDER else border
        g2.stroke = BasicStroke(JBUI.scale(if (focused) 2 else 1).toFloat())
        g2.draw(shape)
    }

    private fun paintHunk(g2: Graphics2D, card: WalkCard, rect: Rectangle, hunk: WalkHunk) {
        val m = metrics()
        val status = walkthrough.statusOf(hunk.id)
        val focused = board.focused == card.id
        hits += Hit.Card(card.id, rect)
        cardFrame(g2, rect, codeBackground(), JBColor.border(), focused)
        g2.color = statusColor(status)
        g2.fillRect(rect.x + 1, rect.y + JBUI.scale(6), JBUI.scale(4), rect.height - JBUI.scale(12))
        val text = uiFont.size2D * zoom >= MIN_TEXT_SIZE
        val left = rect.x + m.pad
        val right = rect.x + rect.width - m.pad
        var y = rect.y + m.pad
        // Title row: the status chip and counts on the right, the path in what is left.
        g2.font = smallFont
        val statusText = walkStatusLabel(status)
        val chipWidth = m.small.stringWidth(statusText) + JBUI.scale(12)
        val chip = Rectangle(right - chipWidth, y + (m.title - m.small.height - JBUI.scale(4)) / 2, chipWidth, m.small.height + JBUI.scale(4))
        g2.color = statusColor(status)
        g2.fillRoundRect(chip.x, chip.y, chip.width, chip.height, chip.height, chip.height)
        hits += Hit.Status(card.id, chip)
        val stats = "+${hunk.added} −${hunk.removed}"
        val statsWidth = m.small.stringWidth(stats)
        val chatWidth = if (actions.toggleChat != null) paintChatChip(g2, card.id, chip.x - JBUI.scale(8) - statsWidth, chip, text) else 0
        if (text) {
            g2.font = smallFont
            g2.color = Color.WHITE
            g2.drawString(statusText, chip.x + JBUI.scale(6), chip.y + JBUI.scale(2) + m.small.ascent)
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(stats, chip.x - JBUI.scale(8) - statsWidth, y + (m.title + m.small.ascent) / 2)
            val titleRoom = chip.x - JBUI.scale(16) - statsWidth - chatWidth - left
            paintPath(g2, walkHunkTitle(hunk) + " #${hunk.index}", left + JBUI.scale(4), y + (m.title + m.ui.ascent) / 2 - JBUI.scale(1), titleRoom)
        }
        y += m.title
        if (text) {
            g2.font = smallFont
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString(fit(walkHunkPlace(hunk), m.small, right - left - JBUI.scale(4)), left + JBUI.scale(4), y + m.small.ascent)
        }
        y += m.place
        paintRows(g2, card.id, hunk, rect, y)
        val rowsOf = rowsOf(hunk)
        y += (rowsOf.lines.size + if (rowsOf.hidden > 0) 1 else 0) * m.line + JBUI.scale(6)
        paintChips(g2, card.id, hunk, status, left + JBUI.scale(4), right, y)
    }

    /**
     * Paints a hunk card's chat chip ending at [right] (before the counts), as tall as the status [chip]: outlined until a chat
     * has a question, marked while it answers and filled while it is open. Returns the room it takes, gap included.
     */
    private fun paintChatChip(g2: Graphics2D, cardId: String, right: Int, chip: Rectangle, text: Boolean): Int {
        val m = metrics()
        val mark = actions.chatMark(cardId)
        val label = when {
            mark.busy -> "chat …"
            mark.started -> "chat ●"
            else -> "chat"
        }
        val width = m.small.stringWidth(label) + JBUI.scale(12)
        val rect = Rectangle(right - JBUI.scale(8) - width, chip.y, width, chip.height)
        val color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val hovered = hover?.rect == rect
        if (mark.open || hovered) {
            g2.color = withAlpha(color, if (mark.open) 60 else 35)
            g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, rect.height, rect.height)
        }
        g2.color = color
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, rect.height, rect.height)
        if (text) {
            g2.font = smallFont
            g2.drawString(label, rect.x + JBUI.scale(6), rect.y + JBUI.scale(2) + m.small.ascent)
        }
        hits += Hit.Chat(cardId, rect)
        return width + JBUI.scale(8)
    }

    private fun paintPath(g2: Graphics2D, path: String, x: Int, baseline: Int, room: Int) {
        val m = metrics()
        val name = path.substringAfterLast('/')
        val folder = path.dropLast(name.length)
        val nameWidth = m.bold.stringWidth(name)
        g2.font = uiFont.deriveFont(Font.BOLD)
        g2.color = UIUtil.getLabelForeground()
        if (nameWidth >= room) {
            g2.drawString(fit(name, m.bold, room), x, baseline)
            return
        }
        g2.font = uiFont
        g2.color = UIUtil.getContextHelpForeground()
        val folderText = fitStart(folder, m.ui, room - nameWidth)
        g2.drawString(folderText, x, baseline)
        g2.font = uiFont.deriveFont(Font.BOLD)
        g2.color = UIUtil.getLabelForeground()
        g2.drawString(name, x + m.ui.stringWidth(folderText), baseline)
    }

    private fun paintRows(g2: Graphics2D, cardId: String, hunk: WalkHunk, rect: Rectangle, top: Int) {
        val m = metrics()
        val rows = rowsOf(hunk)
        val rowSpans = spans[hunk.id]?.takeIf { it.first == rows.block }?.second
        val sketch = codeFont.size2D * zoom < MIN_TEXT_SIZE
        val clip = g2.create() as Graphics2D
        try {
            clip.clipRect(rect.x + 1, top, rect.width - 2, (rows.lines.size + 1) * m.line)
            clip.font = codeFont
            val textX = rect.x + m.pad + m.gutter
            rows.lines.forEachIndexed { index, line ->
                val y = top + index * m.line
                val row = Rectangle(rect.x + 1, y, rect.width - 2, m.line)
                hits += Hit.Row(cardId, line, row)
                rowTint(line.kind)?.let { clip.color = it; clip.fillRect(row.x, row.y, row.width, row.height) }
                if (sketch) {
                    clip.color = SKETCH_TEXT
                    val length = minOf(line.text.trimEnd().length, 84) * m.char
                    clip.fillRect(textX, y + m.line / 3, length, maxOf(1, m.line / 3))
                    return@forEachIndexed
                }
                clip.color = UIUtil.getContextHelpForeground()
                line.old?.let { clip.drawString(it.toString().padStart(4), rect.x + m.pad, y + m.code.ascent) }
                line.new?.let { clip.drawString(it.toString().padStart(4), rect.x + m.pad + m.char * 5, y + m.code.ascent) }
                var x = textX
                val plain = codeForeground(line.kind)
                wallSegments(line.text, rowSpans?.getOrNull(index)).forEach { (piece, style) ->
                    clip.color = style?.color ?: plain
                    clip.font = if (style == null || style.fontStyle == Font.PLAIN) codeFont else codeFont.deriveFont(style.fontStyle)
                    clip.drawString(piece, x, y + m.code.ascent)
                    x += clip.fontMetrics.stringWidth(piece)
                }
                clip.font = codeFont
            }
            if (rows.hidden > 0 && !sketch) {
                clip.font = smallFont
                clip.color = UIUtil.getContextHelpForeground()
                clip.drawString("… ${rows.hidden} more rows · double-click to open the diff", textX, top + rows.lines.size * m.line + m.code.ascent)
            }
        } finally {
            clip.dispose()
        }
    }

    private fun paintChips(g2: Graphics2D, cardId: String, hunk: WalkHunk, status: HunkStatus, left: Int, right: Int, top: Int) {
        val m = metrics()
        val text = smallFont.size2D * zoom >= MIN_TEXT_SIZE
        g2.font = smallFont
        val height = m.small.height + JBUI.scale(4)
        val groups = walkthrough.linksOf(hunk.id)
        val steps = backSteps(hunk.id)
        if (groups.isEmpty() && steps.isEmpty()) {
            if (text) {
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(if (status == HunkStatus.PENDING) "Not walked yet" else "No links", left, top + JBUI.scale(2) + m.small.ascent)
            }
            return
        }
        val unopened = groups.sumOf { board.remaining(it) }
        val openAllWidth = if (groups.size > 1 && unopened > 0) m.small.stringWidth(OPEN_ALL) + JBUI.scale(14) else 0
        var x = left
        val limit = right - openAllWidth - JBUI.scale(6)
        fun chip(label: String, color: Color, filled: Boolean, dashed: Boolean, hit: (Rectangle) -> Hit): Boolean {
            val width = m.small.stringWidth(label) + JBUI.scale(14)
            if (x + width > limit) return false
            val rect = Rectangle(x, top, width, height)
            val hovered = hover?.rect == rect
            if (filled || hovered) {
                g2.color = withAlpha(color, if (hovered) 70 else 40)
                g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, height, height)
            }
            g2.color = color
            g2.stroke = if (dashed) BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f) else BasicStroke(1f)
            g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, height, height)
            if (text) g2.drawString(label, rect.x + JBUI.scale(7), rect.y + JBUI.scale(2) + m.small.ascent)
            hits += hit(rect)
            x += width + JBUI.scale(6)
            return true
        }
        // Steps first, so a card can always lead back to the story it is part of.
        for (step in steps) {
            val label = if (step.rootHunk == hunk.id) "step ${step.id} note" else "in step ${step.id}"
            if (step.rootHunk == hunk.id && board.stepShownFor(cardId, step)) continue
            if (!chip(label, STEP_CHIP, board.stepShownFor(cardId, step), false) { Hit.Step(cardId, step.id, it) }) break
        }
        for (group in groups) {
            val key = group.key
            val opened = board.opened(cardId, key)
            val color = relationColor(group.relation)
            if (!chip(walkChipLabel(group), color, opened, group.links.all { it.weak }) { Hit.Chip(cardId, key, it) }) break
            val remaining = board.remaining(group)
            if (opened && remaining > 0 && !chip(walkMoreLabel(remaining), color, false, false) { Hit.More(cardId, key, it) }) break
        }
        if (openAllWidth > 0) {
            x = right - openAllWidth
            chip(OPEN_ALL, UIUtil.getLabelForeground(), false, false) { Hit.OpenAll(cardId, it) }
        }
    }

    private fun paintNote(g2: Graphics2D, card: WalkCard, rect: Rectangle) {
        val m = metrics()
        val step = walkthrough.step(card.ref) ?: return
        hits += Hit.Card(card.id, rect)
        cardFrame(g2, rect, NOTE_BACKGROUND, NOTE_BORDER, board.focused == card.id)
        if (uiFont.size2D * zoom < MIN_TEXT_SIZE) return
        val left = rect.x + m.pad
        val right = rect.x + rect.width - m.pad
        var y = rect.y + m.pad
        val badge = when (step.kind) {
            WalkStepKind.CHAIN -> "Step ${step.id}"
            WalkStepKind.STANDALONE -> "Standalone ${step.id}"
            WalkStepKind.UNLINKED -> "Not linked"
        }
        g2.font = smallFont
        g2.color = UIUtil.getContextHelpForeground()
        val badgeWidth = m.small.stringWidth(badge)
        g2.drawString(badge, right - badgeWidth, y + (m.title + m.small.ascent) / 2)
        g2.font = uiFont.deriveFont(Font.BOLD)
        g2.color = if (step.kind == WalkStepKind.UNLINKED) statusColor(HunkStatus.UNLINKED) else UIUtil.getLabelForeground()
        g2.drawString(fit(step.title, m.bold, right - left - badgeWidth - JBUI.scale(12)), left, y + (m.title + m.ui.ascent) / 2)
        y += m.title
        g2.font = uiFont
        g2.color = UIUtil.getLabelForeground()
        val room = (rect.height - m.pad * 2 - m.title - m.chips - JBUI.scale(4)) / m.ui.height
        noteLines(step.id, room.coerceAtLeast(1)).forEach { line ->
            g2.drawString(line, left, y + m.ui.ascent)
            y += m.ui.height
        }
        val top = rect.y + rect.height - m.pad - m.chips + JBUI.scale(4)
        g2.font = smallFont
        val others = step.hunks.size - 1
        val remaining = board.remaining(step)
        if (others <= 0) return
        if (remaining > 0) {
            val label = "open ${if (board.stepOpened(step.id)) walkMoreLabel(remaining).removePrefix("show ") else "$remaining"} " +
                "change${if (remaining == 1) "" else "s"} of this step"
            val width = m.small.stringWidth(label) + JBUI.scale(14)
            val chip = Rectangle(left, top, width, m.small.height + JBUI.scale(4))
            g2.color = if (hover?.rect == chip) withAlpha(NOTE_BORDER, 90) else withAlpha(NOTE_BORDER, 40)
            g2.fillRoundRect(chip.x, chip.y, chip.width, chip.height, chip.height, chip.height)
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(label, chip.x + JBUI.scale(7), chip.y + JBUI.scale(2) + m.small.ascent)
            hits += Hit.StepMore(step.id, chip)
        } else {
            g2.color = UIUtil.getContextHelpForeground()
            g2.drawString("All ${step.hunks.size} changes of this step are on the canvas", left, top + JBUI.scale(2) + m.small.ascent)
        }
    }

    private fun fit(text: String, fm: FontMetrics, room: Int): String {
        if (fm.stringWidth(text) <= room) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.take(end) + "…") > room) end--
        return text.take(end) + "…"
    }

    private fun fitStart(text: String, fm: FontMetrics, room: Int): String {
        if (fm.stringWidth(text) <= room) return text
        var start = 0
        while (start < text.length && fm.stringWidth("…" + text.drop(start)) > room) start++
        return "…" + text.drop(start)
    }

    private fun codeBackground(): Color = try {
        EditorColorsManager.getInstance().globalScheme.defaultBackground
    } catch (e: Exception) {
        UIUtil.getPanelBackground()
    }

    private fun codeForeground(kind: WallLine.Kind): Color = when (kind) {
        WallLine.Kind.HUNK, WallLine.Kind.NOTE -> UIUtil.getContextHelpForeground()
        else -> try { EditorColorsManager.getInstance().globalScheme.defaultForeground } catch (e: Exception) { UIUtil.getLabelForeground() }
    }

    private fun rowTint(kind: WallLine.Kind): Color? = when (kind) {
        WallLine.Kind.ADDED -> ADDED
        WallLine.Kind.REMOVED -> REMOVED
        else -> null
    }

    /** Works out the syntax colours of [hunkIds]' rows in the background, as the Diffs wall does; each card repaints when done. */
    private fun requestHighlights(hunkIds: List<String>) {
        if (!highlight || disposed) return
        val app = ApplicationManager.getApplication() ?: return
        val highlighter = diffsWallHighlighter(project) { path -> files[path] }
        val work = hunkIds.mapNotNull { id ->
            val block = rows[id]?.second?.block ?: return@mapNotNull null
            if (id in highlighting || spans[id]?.first == block) return@mapNotNull null
            val job = highlighter.prepare(block)
            if (job == null) { spans[id] = block to null; return@mapNotNull null }
            highlighting += id
            Triple(id, block, job)
        }
        if (work.isEmpty()) return
        val generation = highlightGeneration
        app.executeOnPooledThread {
            for ((id, block, job) in work) {
                if (disposed || generation != highlightGeneration) return@executeOnPooledThread
                val result = try {
                    runReadAction { job() }
                } catch (e: ProcessCanceledException) {
                    null
                } catch (e: Exception) {
                    LOG.warn("Could not highlight $id on the walkthrough", e)
                    null
                }
                app.invokeLater({
                    if (disposed || generation != highlightGeneration) return@invokeLater
                    highlighting.remove(id)
                    spans[id] = block to result
                    repaint()
                }, ModalityState.any())
            }
        }
    }

    override fun dispose() {
        disposed = true
        frames.stop()
        highlightGeneration++
        ToolTipManager.sharedInstance().unregisterComponent(this)
    }

    internal companion object {
        const val CAMERA = "camera"
        const val MIN_ZOOM = 0.15
        const val MAX_ZOOM = 2.5
        const val KEY_ZOOM = 1.25
        const val WHEEL_ZOOM = 1.1
        const val SEGMENTS = 24
        const val MIN_TEXT_SIZE = 4.5f
        const val MAX_TOOLTIP_REASONS = 12
        const val OPEN_ALL = "open all"
        val CANVAS_BACKGROUND = JBColor(0xF2F3F5, 0x1E1F22)
        val NOTE_BACKGROUND = JBColor(0xFFF8E1, 0x3A3524)
        val NOTE_BORDER = JBColor(0xE0C46C, 0x8C7A3C)
        /** Step chips on hunk cards: the note colour, dark enough for text. */
        val STEP_CHIP = JBColor(0xA0822A, 0xC8AE5C)
        val FOCUS_BORDER = JBColor(0x3574F0, 0x548AF7)
        val STEP_EDGE = JBColor(0x9A9A9A, 0x7A7A7A)
        val SKETCH_TEXT = JBColor(0xC0C0C0, 0x606060)
        val ADDED = JBColor(0xDDF5E3, 0x2D4535)
        val REMOVED = JBColor(0xF9DEDE, 0x4D3134)

        fun statusColor(status: HunkStatus): Color = when (status) {
            HunkStatus.LINKED -> JBColor(0x3F9E5A, 0x5FB878)
            HunkStatus.COVERED -> JBColor(0x5A9BD5, 0x4F86B0)
            HunkStatus.STANDALONE -> JBColor(0x9575CD, 0x8E7CC3)
            HunkStatus.UNLINKED -> JBColor(0xE0902A, 0xC08A2A)
            HunkStatus.PENDING -> JBColor(0xA8A8A8, 0x5E5E5E)
        }

        fun relationColor(relation: WalkRelation): Color = when (relation) {
            WalkRelation.CAUSES -> JBColor(0x3574F0, 0x548AF7)
            WalkRelation.SAME_EDIT -> JBColor(0x8A6FC9, 0x9D86D9)
            WalkRelation.TESTS -> JBColor(0x2E9E59, 0x57B579)
            WalkRelation.DOCUMENTS -> JBColor(0xC77D1A, 0xD69A45)
            WalkRelation.RELATED -> JBColor(0x8C8C8C, 0x9A9A9A)
        }
    }
}

private fun withAlpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

