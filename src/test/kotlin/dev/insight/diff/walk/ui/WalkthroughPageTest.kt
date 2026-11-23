package dev.insight.diff.walk.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.DiffCanvasCards
import dev.insight.diff.DiffCanvasContent
import dev.insight.diff.DiffReviewState
import dev.insight.diff.ReviewSnapshot
import dev.insight.diff.showNotes
import dev.insight.diff.walk.LinkDirection
import dev.insight.diff.walk.RenameFixture
import dev.insight.diff.walk.WalkCanvasView
import dev.insight.diff.walk.WalkRelation
import dev.insight.diff.walk.Walkthrough
import dev.insight.diff.walk.reviewFile
import dev.insight.review.CanvasTabKey
import dev.insight.review.ui.NoteCanvas
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.agent.ToolCallEvent
import dev.insight.review.wall.WALL_CANVAS_CHAT
import dev.insight.review.wall.WallChats
import dev.insight.review.wall.WallLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.KeyStroke

class WalkthroughPageTest : BasePlatformTestCase() {
    private val files = RenameFixture.files

    private fun layout(component: Container) {
        component.doLayout()
        component.components.forEach { if (it is Container) layout(it) }
    }

    private fun paint(canvas: WalkCanvas) {
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { canvas.paint(g) } finally { g.dispose() }
    }

    fun testThePageListsEveryHunkBeforeARunAndFollowsARunInPlace() {
        val runs = mutableListOf<String?>()
        val page = WalkthroughPage(null, null, WalkthroughActions(run = { runs += it }))
        try {
            page.card.setSize(1400, 900)
            page.show(WalkthroughContent(Walkthrough.pending(WalkedFixture.hunks), files))
            layout(page.card)
            assertEquals(1 + WalkedFixture.hunks.size, page.listRows().size)
            assertTrue(page.messageLabel.isVisible)
            assertTrue(page.messageLabel.text.contains("Not built yet"))

            page.show(WalkthroughContent(WalkedFixture.running, files, running = true, progress = "Walking · 5 / 7 handled", built = true))
            assertEquals("Walking · 5 / 7 handled", page.progressLabel.text)
            assertEquals(listOf("S1", "S2", WALK_PENDING_GROUP), page.listRows().filterIsInstance<WalkListRow.Group>().map { it.id })

            page.activate(page.listRows().first())
            val board = page.canvas.board
            assertEquals(listOf(walkNoteId("S1"), WalkedFixture.DECL), board.cards.map { it.id })
            assertEquals(WalkedFixture.DECL, board.focused)
            assertTrue("The step's hunks unfold under it", page.listRows().filterIsInstance<WalkListRow.Hunk>().any { it.hunk.id == WalkedFixture.A })
            assertTrue(page.trail.components.isNotEmpty())

            page.canvas.openGroup(WalkedFixture.DECL, WalkGroupKey(WalkRelation.CAUSES, LinkDirection.OUTGOING))
            assertTrue(WalkedFixture.A in board && WalkedFixture.B in board)
            page.canvas.openGroup(WalkedFixture.A, WalkGroupKey(WalkRelation.SAME_EDIT, LinkDirection.SYMMETRIC))
            assertEquals("B is revealed, not opened twice", 4, board.cards.size)
            assertEquals(WalkedFixture.B, board.focused)

            // A later snapshot keeps the cards; one whose hunk changed is dropped.
            page.show(WalkthroughContent(WalkedFixture.finished, files, built = true))
            assertEquals(4, board.cards.size)
            assertTrue(page.listRows().filterIsInstance<WalkListRow.Group>().last().flagged)
            assertTrue(page.messageLabel.text.contains("budget"))
            val edited = files.map { if (it.path == "src/B.kt") reviewFile("src/B.kt", "@@ -7,3 +7,3 @@ fun b\n fun b() {\n-    x()\n+    y()\n }\n") else it }
            page.show(WalkthroughContent(Walkthrough.pending(dev.insight.diff.walk.walkHunks(edited)), edited))
            assertFalse(WalkedFixture.B in board)
            assertTrue(WalkedFixture.A in board)
        } finally {
            Disposer.dispose(page)
        }
    }

    fun testTheCanvasPaintsCardsAndOffersDiffAndContextNavigation() {
        val opened = mutableListOf<Pair<String, WallLine?>>()
        val contexts = mutableListOf<Pair<String, WallLine?>>()
        val canvas = WalkCanvas(null, WalkCanvasActions(
            openDiff = { hunk, line -> opened += hunk.id to line },
            showContext = { hunk, line, _ -> contexts += hunk.id to line },
        ))
        try {
            canvas.setSize(1200, 900)
            canvas.show(WalkedFixture.finished, files)
            paint(canvas)
            canvas.openStep("S1")
            canvas.openAllLinked(WalkedFixture.DECL)
            assertEquals(setOf(walkNoteId("S1"), WalkedFixture.DECL, WalkedFixture.A, WalkedFixture.B, WalkedFixture.TEST),
                canvas.board.cards.map { it.id }.toSet())
            canvas.openStepHunks("S1")
            canvas.openStep("unlinked")
            canvas.reveal(WalkedFixture.DECL, glide = false)
            paint(canvas)
            canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 0, 0, canvas.width / 2, canvas.height / 2, 2, false, MouseEvent.BUTTON1))
            assertEquals(WalkedFixture.DECL, opened.single().first)
            canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, canvas.width / 2, canvas.height / 2, 1, true, MouseEvent.BUTTON3))
            assertEquals(opened.single(), contexts.single())
            canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, canvas.width / 2, canvas.height / 2, 1, false, MouseEvent.BUTTON3))
            assertEquals("Only a platform popup trigger opens the picker", 1, contexts.size)
            canvas.zoomTo(0.2)
            paint(canvas)
            canvas.fit()
            assertTrue(canvas.zoom <= 1.0)
            paint(canvas)
            canvas.clear()
            assertTrue(canvas.board.isEmpty)
            paint(canvas)
        } finally {
            Disposer.dispose(canvas)
        }
    }

    fun testCanvasNavigationKeysMoveTheCamera() {
        val canvas = WalkCanvas(null)
        try {
            canvas.setSize(800, 600)
            fun press(stroke: String) {
                val key = canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke))
                assertNotNull("Missing binding for $stroke", key)
                canvas.actionMap.get(key).actionPerformed(null)
            }
            press("DOWN")
            val afterDown = canvas.cameraY
            assertTrue(afterDown > 0)
            press("PAGE_DOWN")
            assertTrue(canvas.cameraY > afterDown)
            press("shift PAGE_UP")
            assertTrue(canvas.cameraX < 0)
            canvas.show(WalkedFixture.finished, files)
            canvas.openStep("S1")
            press("ctrl HOME")
            val bounds = canvas.board.bounds()!!
            assertTrue(canvas.cameraX < bounds.x)
            assertTrue(canvas.cameraY < bounds.y)
        } finally {
            Disposer.dispose(canvas)
        }
    }

    fun testStateKeepsTheWalkthroughOfItsRevisionOnly() {
        val state = DiffReviewState()
        state.update(ReviewSnapshot(files, emptyList()))
        assertNull(state.walkthrough)
        assertEquals(WalkedFixture.hunks.size, state.walkthroughOrPending.hunks.size)
        assertSame(state.walkthroughOrPending, state.walkthroughOrPending)
        val revision = state.revision
        assertTrue(state.setWalkthrough(revision, WalkedFixture.finished))
        assertSame(WalkedFixture.finished, state.walkthroughOrPending)
        assertEquals(walkTileStats(WalkedFixture.finished), walkTileStats(state.walkthrough))
        state.update(ReviewSnapshot(files.dropLast(1), emptyList()))
        assertNull("A changed diff hides the old walkthrough", state.walkthrough)
        assertFalse(state.setWalkthrough(revision, WalkedFixture.finished))
        state.showWalkthrough()
        assertTrue(state.walkthroughVisible)
        state.clearNotes()
        assertFalse(state.walkthroughVisible)
    }

    fun testCardsAndTheCanvasOpenTheirOwnChatsBesideTheCanvas() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        val asked = mutableListOf<String>()
        val chats = WallChats(project, scope, backend = { key ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    asked += key
                    return "ok"
                }
            }
        }, welcome = { "" })
        val page = WalkthroughPage(project, scope, WalkthroughActions(chats = chats))
        try {
            page.card.setSize(1400, 900)
            page.show(WalkthroughContent(WalkedFixture.finished, files, built = true))
            page.openStep("S1")
            layout(page.card)
            page.canvas.setSize(900, 700)
            paint(page.canvas)
            assertEquals(WalkCanvasView(listOf(WalkedFixture.DECL), listOf("S1"), WalkedFixture.DECL), page.canvasView())

            page.toggleChat(WalkedFixture.DECL)
            assertEquals(WalkedFixture.DECL, page.shownChat())
            val window = page.chatWindow(WalkedFixture.DECL)!!
            assertTrue("The dock shows the card's chat", generateSequence<java.awt.Component>(window) { it.parent }.any { it === page.card })
            paint(page.canvas)
            window.send("What does this change cause?")
            PlatformTestUtil.waitWithEventsDispatching("The card's chat answers", { asked.isNotEmpty() && !chats.busy(WalkedFixture.DECL) }, 10)
            assertEquals(listOf(WalkedFixture.DECL), asked)
            assertTrue(chats.hasConversation(WalkedFixture.DECL))

            page.toggleChat(WALL_CANVAS_CHAT)
            assertEquals("One chat shows at a time", WALL_CANVAS_CHAT, page.shownChat())
            assertNull(page.chatWindow(WalkedFixture.DECL)!!.parent)
            page.toggleChat(WALL_CANVAS_CHAT)
            assertNull(page.shownChat())
        } finally {
            Disposer.dispose(page)
            Disposer.dispose(chats)
            scope.cancel()
        }
    }

    fun testTheReviewCanvasHostsTheWalkthroughPage() {
        val cards = DiffCanvasCards({})
        val canvas = NoteCanvas({}, {}, extension = cards).apply { setSize(1200, 800) }
        try {
            val content = WalkthroughContent(Walkthrough.unlinked(files), files, message = "No assistant is configured.")
            canvas.showNotes(cards, DiffCanvasContent(walkthrough = content))
            assertTrue(CanvasTabKey.Walkthrough in canvas.pageKeys)
            assertEquals("Walkthrough", canvas.pageTitle(CanvasTabKey.Walkthrough))
            val page = cards.walkthroughPage
            assertEquals(listOf("unlinked"), page.listRows().filterIsInstance<WalkListRow.Group>().map { it.id })
            assertTrue(cards.updateWalkthrough(content.copy(walkthrough = WalkedFixture.finished, message = null, built = true)))
            assertEquals(3, page.listRows().filterIsInstance<WalkListRow.Group>().size)
            canvas.showNotes(cards, DiffCanvasContent(walkthrough = content))
            assertSame("One page lives across rebuilds", page, cards.walkthroughPage)
            canvas.showNotes(cards, DiffCanvasContent())
            assertFalse(CanvasTabKey.Walkthrough in canvas.pageKeys)
            assertFalse(cards.updateWalkthrough(content))
        } finally {
            cards.dispose()
            canvas.dispose()
        }
    }

}
