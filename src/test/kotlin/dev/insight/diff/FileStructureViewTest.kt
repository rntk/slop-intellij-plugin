package dev.insight.diff

import dev.insight.review.summarize
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JTextArea

class FileStructureViewTest : BasePlatformTestCase() {
    fun testNeighborTopicsSitOnRightAndSummariesFollowHoveredLinesAndCards() {
        val entry = CodeFile("Entry.kt", "/repo/Entry.kt", "fun run() {\n  orders.place()\n  mail.send()\n\n  orders.place(); mail.send()\n}\n")
        val orders = CodeFile("Orders.kt", "/repo/Orders.kt", "fun place() {}\n")
        val mail = CodeFile("Mail.kt", "/repo/Mail.kt", "fun send() {}\n")
        val files = listOf(entry, orders, mail)
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }.associate {
            it.id to when (it.path) {
                entry.path -> "Coordinates the application."
                orders.path -> "Places an order."
                else -> "Sends a message."
            }
        })
        val topics = ReviewTopics(files).assign(entry.path, "App>Entry").assign(orders.path, "Sales>Orders").assign(mail.path, "Messaging>Mail")
        val structure = FileStructure(entry.path, entry.fingerprint, listOf(FileBlock(1..6, "run()", listOf(orders.path, mail.path), mapOf(
            2 to listOf(orders.path), 3 to listOf(mail.path), 5 to listOf(orders.path, mail.path),
        ))))
        val card = FileViewCard(entry, structure, null, topics, summaries, false)
        val opened = mutableListOf<String>()
        for (zoom in listOf(0.5, 1.0, 1.8)) {
            val panel = FileStructurePanel(card, structure, DocumentColumnsPanel.defaultWidth(FileStructurePanel.railDepth(card), zoom), zoom,
                FileStructureActions({ path, _ -> opened += path }, { _, _ -> }))
            panel.setSize(panel.preferredSize)
            layoutTree(panel)
            val section = panel.components.single { it.name?.startsWith("file:") == true }
            val source = descendants(panel).filterIsInstance<SourceTextView>().single()
            val rails = panel.components.filter { it.name?.startsWith("reached-topic:") == true }
            assertEquals(2, rails.size)
            assertTrue(rails.all { it.x >= section.x + section.width && it.y == section.y })
            assertFalse(texts(panel).contains("Coordinates the application."))
            assertFalse(texts(panel).contains("Places an order."))
            val idleBounds = section.bounds

            fun hoverLine(line: Int) {
                val y = (0 until source.height).first { source.lineAt(it) == line }
                source.dispatchEvent(MouseEvent(source, MouseEvent.MOUSE_MOVED, 0, 0, 5, y, 0, false))
                layoutTree(panel)
            }
            hoverLine(2)
            assertTrue(texts(panel).contains("Places an order."))
            assertFalse(texts(panel).contains("Sends a message."))
            assertEquals(setOf(2), source.highlightedLines)
            hoverLine(3)
            assertFalse(texts(panel).contains("Places an order."))
            assertTrue(texts(panel).contains("Sends a message."))
            hoverLine(5)
            assertTrue(texts(panel).containsAll(listOf("Places an order.", "Sends a message.")))
            assertEquals(idleBounds, section.bounds)
            hoverLine(4)
            assertFalse(panel.components.any { it.name?.startsWith("card-summary:") == true })
            assertEquals(emptySet<Int>(), source.highlightedLines)

            val orderCard = rails.single { it.name!!.endsWith("Sales > Orders") }
            orderCard.dispatchEvent(MouseEvent(orderCard, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false))
            layoutTree(panel)
            assertTrue(texts(panel).contains("Places an order."))
            assertFalse(texts(panel).contains("Sends a message."))
            assertEquals(setOf(2, 5), source.highlightedLines)
            descendants(panel).filterIsInstance<ActionLink>().single { it.text == orders.path }.doClick()
            assertEquals(orders.path, opened.last())
            // Clicks do not leave a summary pinned after the pointer leaves.
            orderCard.dispatchEvent(MouseEvent(orderCard, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
            orderCard.dispatchEvent(MouseEvent(orderCard, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false))
            assertFalse(panel.components.any { it.name?.startsWith("card-summary:") == true })
            assertFalse(texts(panel).contains("Places an order."))
            assertEquals(emptySet<Int>(), source.highlightedLines)
        }
    }

    fun testPendingAndOutsideScopeNeighborsHaveHoverCardsAndLongSummariesFit() {
        val entry = CodeFile("Entry.kt", "/repo/Entry.kt", "work()\n")
        val pending = CodeFile("Pending.kt", "/repo/Pending.kt", "fun work() {}\n")
        val files = listOf(entry, pending)
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val structure = FileStructure(entry.path, entry.fingerprint, listOf(FileBlock(1..1, null,
            listOf(pending.path, "Outside.kt"), mapOf(1 to listOf(pending.path, "Outside.kt")))))
        val card = FileViewCard(entry, structure, null, ReviewTopics(files), summaries, true)
        assertEquals(1, card.missingTopics)
        assertEquals(1, card.missingSummaries)
        val panel = FileStructurePanel(card, structure, 1500, 1.0, FileStructureActions({ _, _ -> }, { _, _ -> }))
        panel.setSize(panel.preferredSize)
        layoutTree(panel)
        val source = descendants(panel).filterIsInstance<SourceTextView>().single()
        val y = (0 until source.height).first { source.lineAt(it) == 1 }
        assertNull(source.lineAt(0))
        assertNull(source.lineAt(source.height))
        source.dispatchEvent(MouseEvent(source, MouseEvent.MOUSE_MOVED, 0, 0, 5, y, 0, false))
        layoutTree(panel)
        assertTrue(texts(panel).containsAll(listOf("Awaiting topic", "No summary yet", "Outside the review scope")))
        val summary = panel.components.single { it.name?.startsWith("card-summary:") == true }
        assertTrue(panel.preferredSize.height >= summary.y + summary.height)
        source.dispatchEvent(MouseEvent(source, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false))
        assertFalse(panel.components.any { it.name?.startsWith("card-summary:") == true })
    }

    private fun texts(panel: Container) = descendants(panel).filterIsInstance<JTextArea>().map { it.text }
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
