package dev.insight.diff

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.PsiTestUtil
import dev.insight.chat.ToolInputException
import dev.insight.chat.ToolChatRunner
import kotlinx.coroutines.*

class DiffSymbolsTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir(""))
    }

    private fun snapshot(): ReviewSnapshot {
        val file = myFixture.addFileToProject("Sample.java", """
            class Sample {
                int value;
                void entry() { save(); }
                void save() { helper(); helper(); value = 1; }
                void save(int x) { helper(); }
                void helper() {}
                // imaginary() is not a declaration.
            }
        """.trimIndent())
        assertTrue("Expected Java PSI, got ${file.javaClass}", file is com.intellij.psi.PsiJavaFile)
        assertTrue("Expected project content at ${file.virtualFile}", dev.insight.callgraph.CallGraphAnalyzer.isInProject(project, file))
        assertTrue("Expected supported declarations", dev.insight.callgraph.ProjectGraphAnalyzer.declarations(file).size > 1)
        return ReviewSnapshot(listOf(ReviewFile("Sample.java", file.virtualFile.path, null, "modified", "+change", "1", "", file.text)), emptyList())
    }

    fun testSelectorsDisambiguateOverloadsAndLinksAreStaticAndDeduplicated() {
        val snapshot = snapshot()
        ApplicationManager.getApplication().runReadAction {
            val symbols = DiffSymbols(project, snapshot)
            val result = symbols.find(listOf("Sample.java"), "save", 0)
            val found = JsonParser.parseString(result).asJsonObject["symbols"].asJsonArray.filter { it.asJsonObject["kind"].asString == "function" }
            assertEquals(result, 2, found.size)
            assertTrue(result.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
            rejected { symbols.resolve(listOf(DiffNote(listOf("Sample.java"), "ambiguous", "save"))) }
            val selector = found.first().asJsonObject["symbol"].asString
            val note = symbols.resolve(listOf(DiffNote(listOf("Sample.java"), "Saves", selector))).single()
            val evidence = note.evidence!!
            assertEquals(selector, note.symbol)
            assertEquals("save", evidence.symbol.name)
            assertEquals(listOf("helper"), evidence.links.single { it.label == "Calls" }.symbols.map { it.name })
            assertEquals(listOf("entry"), evidence.links.single { it.incoming }.symbols.map { it.name })
            assertTrue(evidence.links.single { it.label == "References" }.symbols.any { it.name == "value" })
            assertFalse(evidence.links.single { it.label == "References" }.symbols.any { it.name == "helper" })
            val grouped = snapshot.copy(files = snapshot.files + ReviewFile("old.java", "/deleted/old.java", null, "deleted", "", "old", "old", ""))
            val noteWithDeletion = DiffSymbols(project, grouped).resolve(listOf(DiffNote(listOf("Sample.java", "old.java"), "Moves helper", "helper"))).single()
            assertEquals("helper", noteWithDeletion.evidence!!.symbol.name)
            rejected { symbols.resolve(listOf(DiffNote(listOf("Sample.java"), "Invented", "imaginary"))) }
        }
    }

    fun testChangeFlowJoinsSymbolNotesOnlyThroughResolvedCalls() {
        val snapshot = snapshot()
        ApplicationManager.getApplication().runReadAction {
            val symbols = DiffSymbols(project, snapshot)
            val save = JsonParser.parseString(symbols.find(listOf("Sample.java"), "save", 0)).asJsonObject["symbols"].asJsonArray
                .first { it.asJsonObject["kind"].asString == "function" }.asJsonObject["symbol"].asString
            val paths = listOf("Sample.java")
            // The model lists steps out of order; the drawn order comes from the calls.
            val notes = symbols.resolve(listOf(DiffNote(paths, "Helps", "helper"), DiffNote(paths, "Starts", "entry"),
                DiffNote(paths, "Saves", save), DiffNote(paths, "Stores", "Sample.value")))
            val flow = changeFlow(notes)
            assertEquals(listOf("entry" to 0, "save" to 1, "helper" to 2), flow.rows.map { it.note.evidence!!.symbol.name to it.depth })
            assertEquals(listOf(null, 0, 1), flow.rows.map { it.parent })
            assertEquals(2, flow.edges.size)
            assertEquals(1, flow.unlinked) // A field write is a reference, not a call.
            assertEquals("Change flow (verified calls between symbol notes): Sample.entry → Sample.save; Sample.save → Sample.helper.", flow.summary())
        }
    }

    fun testClassesFieldsAndSameLineOverloadsHaveDistinctSelectors() {
        val file = myFixture.addFileToProject("Same.java", "class Same { int field; void go() {} void go(int x) {} }")
        assertTrue("Expected Java PSI, got ${file.javaClass}", file is com.intellij.psi.PsiJavaFile)
        assertTrue("Expected project content at ${file.virtualFile}", dev.insight.callgraph.CallGraphAnalyzer.isInProject(project, file))
        assertTrue("Expected supported declarations", dev.insight.callgraph.ProjectGraphAnalyzer.declarations(file).size > 1)
        val snapshot = ReviewSnapshot(listOf(ReviewFile("Same.java", file.virtualFile.path, null, "added", "", "1", "", file.text)), emptyList())
        ApplicationManager.getApplication().runReadAction {
            val index = DiffSymbols(project, snapshot)
            val result = index.find(listOf("Same.java"), "go", 0)
            val found = JsonParser.parseString(result).asJsonObject["symbols"].asJsonArray.filter { it.asJsonObject["kind"].asString == "function" }
            val selectors = found.map { it.asJsonObject["symbol"].asString }
            assertEquals(result, 2, selectors.toSet().size)
            selectors.forEach { index.resolve(listOf(DiffNote(listOf("Same.java"), "Go", it))) }
            val notes = index.resolve(listOf(DiffNote(listOf("Same.java"), "Class", "Same"), DiffNote(listOf("Same.java"), "Field", "Same.field")))
            assertTrue(notes[0].evidence!!.links.single { it.label == "Contains" }.symbols.any { it.name == "field" })
            assertEquals("field", notes[1].evidence!!.symbol.name)
        }
    }

    fun testMissingDeletedUnsupportedAndEditedSourceCannotProduceEvidence() {
        val snapshot = snapshot()
        val file = myFixture.findFileInTempDir("Sample.java")
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(psi)!!
            document.insertString(0, "// edited\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        ApplicationManager.getApplication().runReadAction {
            rejected { DiffSymbols(project, snapshot).resolve(listOf(DiffNote(listOf("Sample.java"), "Old", "helper"))) }
            val deleted = snapshot.copy(files = snapshot.files.map { it.copy(status = "deleted", after = "") })
            rejected { DiffSymbols(project, deleted).resolve(listOf(DiffNote(listOf("Sample.java"), "Deleted", "helper"))) }
            assertNull(DiffSymbols(project, deleted).resolve(listOf(DiffNote(listOf("Sample.java"), "File note"))).single().evidence)
            val unavailable = JsonParser.parseString(DiffSymbols(project, deleted).find(listOf("Sample.java"), "", 0)).asJsonObject
            assertTrue(unavailable["symbols"].asJsonArray.isEmpty)
            assertTrue(unavailable["unavailable"].asJsonObject.has("Sample.java"))
            rejected { DiffSymbols(project, snapshot).resolve(listOf(DiffNote(listOf("missing.java"), "Missing", "helper"))) }
        }
    }

    fun testHistoricalSourceMismatchExplainsSymbolsRequireMatchingCurrentSource() {
        val snapshot = snapshot()
        ApplicationManager.getApplication().runReadAction {
            for (mode in listOf(ReviewDiffMode.REVISIONS, ReviewDiffMode.MERGE_BASE)) {
                val historical = snapshot.copy(
                    files = snapshot.files.map { it.copy(after = "// historical source\n" + it.after) },
                    comparison = ReviewComparison(ReviewDiffRequest(mode, "/repo", "main", "topic"), "base", "target")
                )
                val symbols = DiffSymbols(project, historical)
                val found = JsonParser.parseString(symbols.find(listOf("Sample.java"), "", 0)).asJsonObject
                assertTrue(found["symbols"].asJsonArray.isEmpty)
                val expected = "Current source for Sample.java does not match the reviewed target revision. Use a file note without symbol."
                assertEquals(expected, found["unavailable"].asJsonObject["Sample.java"].asString)
                try {
                    symbols.resolve(listOf(DiffNote(listOf("Sample.java"), "Old", "helper")))
                    fail("Historical source mismatches must reject symbol notes")
                } catch (e: ToolInputException) { assertEquals(expected, e.message) }
                assertNull(symbols.resolve(listOf(DiffNote(listOf("Sample.java"), "File note"))).single().evidence)
            }
        }
    }

    fun testTabAppliesValidatedBatchesAtomicallyAndNormalizesSymbolAliases() {
        val snapshot = snapshot()
        val tab = DiffReviewTab(project, setOf("Sample.java"), { emptyList() }, {})
        try {
            tab.update(snapshot)
            val context = tab.captureContext()
            runWithEvents {
                val found = JsonParser.parseString(context.symbols(listOf("Sample.java"), "helper", 0)).asJsonObject
                val selector = found["symbols"].asJsonArray.first().asJsonObject["symbol"].asString
                try {
                    context.addNotes(listOf(DiffNote(listOf("Sample.java"), "Valid", "entry"), DiffNote(listOf("Sample.java"), "Missing", "invented")))
                    fail("A missing symbol must reject the whole batch")
                } catch (_: ToolInputException) { }
                assertTrue(tab.state.notes.isEmpty())
                context.addNotes(listOf(DiffNote(listOf("Sample.java"), "First", "helper")))
                context.addNotes(listOf(DiffNote(listOf("Sample.java"), "Updated", selector)))
                context.addNotes(listOf(DiffNote(listOf("Sample.java"), "File note")))
                assertEquals(2, tab.state.notes.size)
                assertEquals("Updated", tab.state.notes.first().text)
                assertEquals(selector, tab.state.notes.first().symbol)
                assertNotNull(tab.state.notes.first().evidence)
                assertTrue(context.uncovered().isEmpty())
                tab.update(snapshot.copy(files = snapshot.files.map { it.copy(fingerprint = "changed") }))
                assertTrue(tab.state.notes.isEmpty())
                try { context.symbols(listOf("Sample.java"), "helper", 0); fail("Stale turn") } catch (_: ToolInputException) { }
            }
        } finally { Disposer.dispose(tab) }
    }

    fun testDiscoveryPagesAndUnsupportedFilesRemainExplicit() {
        val file = myFixture.addFileToProject("Many.java", "class Many { " + (1..40).joinToString(" ") { "void f$it() {}" } + " }")
        val text = myFixture.addFileToProject("notes.txt", "function invented() {}")
        val snapshot = ReviewSnapshot(listOf(file, text).map {
            ReviewFile(it.name, it.virtualFile.path, null, "added", "", "1", "", it.text)
        }, emptyList())
        ApplicationManager.getApplication().runReadAction {
            val index = DiffSymbols(project, snapshot)
            val first = JsonParser.parseString(index.find(listOf("Many.java"), "", 0)).asJsonObject
            val next = first["next_offset"].asInt
            val second = JsonParser.parseString(index.find(listOf("Many.java"), "", next)).asJsonObject
            val all = first["symbols"].asJsonArray.toList() + second["symbols"].asJsonArray.toList()
            assertEquals(41, all.size)
            assertEquals(41, all.map { it.asJsonObject["symbol"].asString }.toSet().size)
            assertFalse(second.has("next_offset"))
            rejected { index.resolve(listOf(DiffNote(listOf("notes.txt"), "Invented", "invented"))) }
        }
    }

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Symbol tools should finish", { job.isCompleted }, 10000)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected invalid symbol evidence to be rejected") } catch (_: ToolInputException) { }
    }
}
