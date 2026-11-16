package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import dev.insight.diff.ChangeMapFixture.map
import dev.insight.diff.atoms.ChangeAtom
import dev.insight.diff.atoms.buildChangeMapTree
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewSummaries
import dev.insight.review.summarize
import dev.insight.review.ui.NoteCanvas
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel

class ChangeMapCardTest : BasePlatformTestCase() {
    private val files = ChangeMapFixture.files()
    private val tree = buildChangeMapTree(map, files)

    fun testTheCardListsTheChangesAndReadsTheSelectedOneAsAChain() {
        var generated = 0
        var removed = 0
        val opened = mutableListOf<ChangeAtom>()
        val cards = DiffCanvasCards({}, project, changeMapActions = ChangeMapActions({ generated++ }, { removed++ }, { opened += it }))
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            canvas.showNotes(cards, DiffCanvasContent(changeMap = ChangeMapCard(null, files, ReviewSummaries(tree), running = false, canRun = true)))
            assertEquals("Change map", canvas.tabs.getTitleAt(0))
            assertEquals("Generate", button(canvas).text)
            assertTrue(named(canvas, "change-map-empty").single().let { it as JLabel }.text.contains("Click Generate"))
            button(canvas).doClick()
            assertEquals(1, generated)

            val summaries = ReviewSummaries(tree).summarize(tree.nodes.filter { it.children.isEmpty() }.associate { it.id to "Leaf ${it.label}." })
            canvas.showNotes(cards, DiffCanvasContent(changeMap = ChangeMapCard(map, files, summaries, running = false, canRun = true)))
            assertEquals("Fill summaries", button(canvas).text)
            assertEquals("Change map · 2 logical changes · 2 standalone · 7 declarations in 7 files · 7 / ${tree.nodes.size} summaries written",
                label(canvas, "change-map-header"))
            val list = list(canvas)
            assertEquals(listOf("Make things", "Util.helper and 1 related", "Standalone changes (2)"), (0 until list.model.size).map { list.model.getElementAt(it).title })
            assertEquals("k1", cards.changeMapSelection)
            assertEquals("Make things", label(canvas, "change-map-title"))
            assertTrue(named(canvas, "change-map-summary-missing").isNotEmpty())
            // Steps start collapsed to their name and the first sentence of their summary, within sections by role.
            assertEquals(listOf("Origin (1)", "Follows (1)", "Tests (1)"), labels(canvas, "change-map-section-title"))
            assertEquals(listOf("Foo.make", "Bar.use", "BarTest.uses"), labels(canvas, "change-map-symbol"))
            assertEquals(listOf("Leaf Foo.make.", "Leaf Bar.use.", "Leaf BarTest.uses."), labels(canvas, "change-map-step-gist").map { it.removeHtml() })
            assertTrue(named(canvas, "change-map-relation").isEmpty())
            (named(canvas, "change-map-expand-all").single() as ActionLink).doClick()
            assertEquals(listOf("Origin", "Follows", "Test"), labels(canvas, "change-map-role").map { it.substringBefore(" ·") })
            assertEquals(listOf("↳ uses the new Foo.make", "↳ tests Bar.use", "↳ changes its use of Foo.make"), labels(canvas, "change-map-relation"))
            assertTrue("A one-sentence summary has nothing more to show", named(canvas, "change-map-step-summary").isEmpty())
            (named(canvas, "change-map-collapse-all").single() as ActionLink).doClick()
            assertTrue(named(canvas, "change-map-symbol").isEmpty())
            (named(canvas, "change-map-expand-all").single() as ActionLink).doClick()

            (named(canvas, "change-map-open").first() as ActionLink).doClick()
            assertEquals(listOf(ChangeMapFixture.origin), opened)

            // A link to another change selects it in place.
            (named(canvas, "change-map-link").single() as ActionLink).doClick()
            assertEquals(STANDALONE_ENTRY, cards.changeMapSelection)
            assertEquals("Standalone changes", label(canvas, "change-map-title"))
            assertEquals(listOf("Changes (1)", "Docs (1)"), labels(canvas, "change-map-section-title"))
            assertTrue(named(canvas, "change-map-relation").isEmpty())

            list(canvas).selectedIndex = 1
            assertEquals("k2", cards.changeMapSelection)
            assertEquals("Util.helper and 1 related", label(canvas, "change-map-title"))

            // A rebuild with every summary written keeps the selection and offers to regenerate.
            val complete = summaries.summarize(summaries.missing().filter { tree[it].identity != "change-map-root" }.associateWith<Int, String> { "Branch ${tree[it].label}." })
                .let { it.summarize(mapOf(tree.root.id to "The whole change.")) }
            assertTrue(cards.updateChangeMap(ChangeMapCard(map, files, complete, running = false, canRun = true)))
            assertEquals("k2", cards.changeMapSelection)
            assertEquals("Util.helper and 1 related", label(canvas, "change-map-title"))
            assertEquals("Regenerate", button(canvas).text)
            assertEquals("Branch Util.helper and 1 related.", label(canvas, "change-map-summary").removeHtml())
            assertEquals("The whole change.", label(canvas, "change-map-root-summary").removeHtml())

            cards.updateChangeMap(ChangeMapCard(map, files, complete, running = true, canRun = false))
            assertFalse(button(canvas).isEnabled)
            assertTrue(label(canvas, "change-map-header").endsWith("· working…"))

            descendants(canvas).filterIsInstance<InplaceButton>().single { it.toolTipText == "Remove change map" }.doClick()
            assertEquals(1, removed)
            assertFalse("A hidden card is added or removed by a rebuild", cards.updateChangeMap(null))

            canvas.showNotes(cards, DiffCanvasContent(changeMap = ChangeMapCard(EMPTY_CHANGE_MAP, files, ReviewSummaries(buildChangeMapTree(EMPTY_CHANGE_MAP, files)),
                running = false, canRun = true)))
            assertEquals("No changed declarations found.", (named(canvas, "change-map-empty").single() as JLabel).text.removeHtml())
            assertEquals(CanvasTabKey.ChangeMap, canvas.pageKeys.single())
        } finally {
            cards.dispose()
            canvas.dispose()
        }
    }

    private fun String.removeHtml() = replace(Regex("<[^>]+>"), "")
    private fun button(canvas: Container) = named(canvas, "change-map-action").single() as JButton
    @Suppress("UNCHECKED_CAST")
    private fun list(canvas: Container) = named(canvas, "change-map-list").single() as JBList<ChangeEntry>
    private fun label(canvas: Container, name: String) = (named(canvas, name).single() as JLabel).text
    private fun labels(canvas: Container, name: String) = named(canvas, name).map { (it as JLabel).text }
    private fun named(canvas: Container, name: String) = descendants(canvas).filter { it.name == name }

    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
