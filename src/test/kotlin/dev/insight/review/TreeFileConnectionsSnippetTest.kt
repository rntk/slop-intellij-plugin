package dev.insight.review

import com.intellij.ui.components.ActionLink
import dev.insight.graph.RelationKind
import dev.insight.review.ui.TreeConnectionPreviewPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

class TreeFileConnectionsSnippetTest {
    @Test
    fun `extractCodeSnippet extracts context, dedents common indent, expands tabs, and clamps bounds`() {
        val text = listOf(
            "package demo;",
            "class Foo {",
            "\tvoid bar() {",
            "\t\tval x = 1;",
            "\t\tcallTarget(x);",
            "\t}",
            "}",
        ).joinToString("\n")

        val middle = extractCodeSnippet(text, line = 5)
        assertNotNull(middle)
        assertEquals(4, middle!!.firstLine)
        assertEquals(5, middle.highlightLine)
        assertEquals(
            listOf(
                "    val x = 1;",
                "    callTarget(x);",
                "}",
            ),
            middle.lines,
        )

        val top = extractCodeSnippet(text, line = 1)
        assertNotNull(top)
        assertEquals(1, top!!.firstLine)
        assertEquals(1, top.highlightLine)
        assertEquals(listOf("package demo;", "class Foo {"), top.lines)

        val bottom = extractCodeSnippet(text, line = 7)
        assertNotNull(bottom)
        assertEquals(6, bottom!!.firstLine)
        assertEquals(7, bottom.highlightLine)
        assertEquals(listOf("    }", "}"), bottom.lines)

        assertNull(extractCodeSnippet(text, line = 0))
        assertNull(extractCodeSnippet(text, line = 8))
        assertNull(extractCodeSnippet("", line = 1))
    }

    @Test
    fun `extractCodeSnippet truncates long lines with ellipsis`() {
        val longLine = "    val result = " + "a".repeat(120)
        val snippet = extractCodeSnippet(longLine, line = 1, maxLineChars = 40)
        assertNotNull(snippet)
        assertEquals(1, snippet!!.lines.size)
        assertEquals(40, snippet.lines.single().length)
        assertTrue(snippet.lines.single().endsWith("…"))
    }

    @Test
    fun `preview panel renders separated occurrence cards, target placeholders, overflow, and callbacks`() {
        val occurrences = listOf(
            TreeConnectionOccurrence(
                kind = RelationKind.CALL,
                fromLine = 10,
                toLine = 4,
                fromSnippet = CodeSnippet(9, 10, listOf("fun call() {", "    Api.run()", "}")),
                toSnippet = CodeSnippet(3, 4, listOf("object Api {", "    fun run() {}", "}")),
            ),
            TreeConnectionOccurrence(
                kind = RelationKind.IMPORT,
                fromLine = 2,
                toLine = null,
                fromSnippet = CodeSnippet(1, 2, listOf("package client", "import api.*")),
                toSnippet = null,
            ),
        )
        val site = TreeConnectionSite(fromLine = 10, toLine = 4, occurrences = occurrences, totalOccurrences = 5)
        val openedAt = mutableListOf<Pair<String, Int>>()
        var openedContext = false
        var closedCount = 0

        val panel = TreeConnectionPreviewPanel(
            fromPath = "src/client/Client.kt",
            toPath = "src/api/Api.kt",
            site = site,
            pinned = true,
            openAt = { path, line -> openedAt += path to line },
            openContext = { openedContext = true },
            onClose = { closedCount++ },
        )

        val all = descendants(panel)
        val cards = all.filter { it.name == "charts-tree-connection-occurrence" }
        assertEquals(2, cards.size)

        val labels = all.filterIsInstance<JLabel>().mapNotNull { it.text }
        assertTrue(labels.contains("Client.kt → Api.kt"))
        assertTrue(labels.contains("CALL"))
        assertTrue(labels.contains("IMPORT"))
        assertTrue(labels.contains("File / package target"))
        assertTrue(labels.contains("+ 3 more occurrences"))

        val links = all.filterIsInstance<ActionLink>()
        val lineLink = links.first { it.text == "Client.kt:10" }
        lineLink.doClick()
        assertEquals(listOf("src/client/Client.kt" to 10), openedAt)
        assertEquals(1, closedCount)

        val contextLink = links.single { it.name == "charts-tree-connection-open-context" }
        contextLink.doClick()
        assertTrue(openedContext)
        assertEquals(2, closedCount)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
