package dev.insight.callgraph

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.enrichment.liftCalls
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphResult
import dev.insight.graph.RelationKind
import dev.insight.graph.hierarchy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class ProgressiveSchematicTest : LightJavaCodeInsightFixtureTestCase() {
    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Scope analysis should finish", { task.isCompleted }, 20)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun merge(previous: CallGraphResult, branch: CallGraphResult) =
        mergeSchematicResults(previous, branch) { result, node ->
            Triple(node.filePath, result.pointers[node.id]?.element?.textOffset, node.kind) to node.unresolved
        }

    private fun CallGraphResult.symbol(name: String) = graph.nodes.singleOrNull {
        (pointers[it.id]?.element as? PsiNamedElement)?.name == name
    } ?: error("No unique symbol '$name' among ${graph.nodes.map { it.name }}")
    private fun CallGraphResult.element(name: String): PsiElement = pointers[symbol(name).id]!!.element!!

    fun testOpeningReadsOnlySelectedFileWithoutResolvingFoldedBodies() {
        myFixture.addFileToProject(".stryker-tmp/sandbox/Noise.java", "class Noise { void noise() { missing(); } }")
        myFixture.addFileToProject("Helper.java", "class Helper { static void work() { missing(); } }")
        val file = myFixture.configureByText("Selected.java", """
            class Selected {
                void entry() {
                    Helper.work();
                }
                void unused() { missing(); }
            }
        """.trimIndent())
        val initial = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        assertTrue(initial.occurrences.isEmpty())
        assertTrue(initial.graph.edges.all { it.kind == RelationKind.CONTAINS })
        assertTrue(initial.graph.nodes.all { it.filePath == file.virtualFile.path })

        val opened = ProjectGraphAnalyzer.buildScope(project, file)
        assertTrue(opened.occurrences.isEmpty())
        assertFalse(opened.graph.nodes.any { it.name in listOf("Noise", "Helper") })
        val reading = SchematicBuilder.build(opened, opened.graph.hierarchy(), emptyMap(), opened.symbol("Selected.java").id)!!
        assertEquals(listOf("entry", "unused"), reading.targets.map { opened.graph.nodes[it].name.substringBefore('(') })
    }

    fun testIndexedUsagesDiscoverOtherFilesWithoutResolvingTheirBodies() {
        val file = myFixture.configureByText("Api.java", "class Api { static void work() {} static void unused() {} }")
        myFixture.addFileToProject("Client.java", """
            class Client {
                Runnable callback = Api::work;
                void run() { Api.work(); Api.work(); hidden(); }
                void hidden() { missing(); }
            }
        """.trimIndent())
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val initial = exploration.result
        val work = initial.symbol("work").id
        val found = background { exploration.findUsages(work) }
        assertEquals(initial.graph.nodes, found.result.graph.nodes.take(initial.graph.nodes.size))
        assertSame(found.result, exploration.result)
        assertFalse(found.truncated)
        assertEquals(3, found.usages.size)
        assertEquals(2, found.usages.count { it.kind == RelationKind.CALL })
        assertEquals(1, found.usages.count { it.kind == RelationKind.REFERENCE })
        assertEquals(setOf("callback", "run"), found.usages.map {
            (found.result.pointers[it.owner]!!.element as PsiNamedElement).name
        }.toSet())
        assertTrue("Search must leave bodies folded", found.result.occurrences.isEmpty())
        assertFalse(found.result.graph.nodes.any { it.unresolved })
        val run = found.result.symbol("run").id
        assertTrue(CallGraphEdge(run, work) in found.result.graph.edges)
        background { exploration.expand(run) }
        assertEquals(found.result.graph.nodes, exploration.result.graph.nodes.take(found.result.graph.nodes.size))
        assertTrue(exploration.result.occurrences.any { it.expression == "Api.work" })
        assertFalse(exploration.result.occurrences.any { it.expression == "missing" })
        val repeated = background { exploration.findUsages(work) }
        assertEquals(found.usages, repeated.usages)
    }

    fun testUsageSearchIncludesTypeReferencesAndReportsTruncation() {
        val file = myFixture.configureByText("Api.java", "class Api {}")
        myFixture.addFileToProject("Client.java", "class Client { Api first; Api second; Api third; }")
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val api = exploration.result.symbol("Api").id
        val found = background { exploration.findUsages(api, limit = 2) }
        assertEquals(2, found.usages.size)
        assertTrue(found.truncated)
        assertTrue(found.usages.all { it.kind == RelationKind.REFERENCE && it.line == 1 })
        val complete = background { exploration.findUsages(api) }
        assertEquals(3, complete.usages.size)
        assertFalse(complete.truncated)
        assertEquals(found.result.graph.nodes, complete.result.graph.nodes)
    }

    fun testUsageSearchTracksDiscoveredFilesAndRejectsStaleSource() {
        val file = myFixture.configureByText("Api.java", "class Api { static void work() {} }")
        val client = myFixture.addFileToProject("Client.java", "class Client { void run() { Api.work(); } }")
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val work = exploration.result.symbol("work").id
        background { exploration.findUsages(work) }
        val beforeEdit = exploration.result
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(client)!!
            document.insertString(document.textLength, "\n// edited")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val failure = background { runCatching { exploration.findUsages(work) }.exceptionOrNull() }
        assertTrue("Expected stale source rejection, got $failure", failure is IllegalStateException)
        assertSame(beforeEdit, exploration.result)
    }

    fun testKotlinUsagesCanLeaveTheInitialFile() {
        val file = myFixture.configureByText("api.kt", "package sample\nfun work() {}")
        myFixture.addFileToProject("client.kt", "package sample\nfun run() { work() }")
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val work = exploration.result.symbol("work").id
        val found = background { exploration.findUsages(work) }
        assertEquals(1, found.usages.size)
        assertEquals("run", (found.result.pointers[found.usages.single().owner]!!.element as PsiNamedElement).name)
        assertEquals(RelationKind.CALL, found.usages.single().kind)
        assertTrue(found.usages.single().filePath.endsWith("client.kt"))
    }

    fun testFollowingOneBranchKeepsIdsAndDoesNotResolveTargetOrSiblingBodies() {
        myFixture.addFileToProject("Helper.java", """
            class Helper {
                static void work() { deep(); }
                static void deep() {}
                static void unused() { missing(); }
            }
        """.trimIndent())
        val file = myFixture.configureByText("Selected.java", """
            class Selected {
                void entry() { Helper.work(); }
                void unused() { missing(); }
            }
        """.trimIndent())
        val initial = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        val branch = ProjectGraphAnalyzer.buildScope(project, initial.element("entry"))
        val followed = merge(initial, branch)
        assertEquals(initial.graph.nodes, followed.graph.nodes.take(initial.graph.nodes.size))
        assertEquals(listOf("Helper.work"), followed.occurrences.map { it.expression })
        assertFalse(followed.graph.nodes.any { it.name.substringBefore('(') == "deep" })
        val entry = followed.symbol("entry").id
        val work = followed.symbol("work").id
        assertTrue(CallGraphEdge(entry, work) in followed.graph.edges)
        assertEquals(work, followed.occurrences.single().targets.single())

        val next = merge(followed, ProjectGraphAnalyzer.buildScope(project, followed.element("work")))
        assertEquals(followed.graph.nodes, next.graph.nodes.take(followed.graph.nodes.size))
        val deep = next.symbol("deep").id
        assertTrue(CallGraphEdge(work, deep) in next.graph.edges)
        assertEquals(2, next.occurrences.size)
        assertFalse(next.graph.nodes.any { it.unresolved })
        val repeated = merge(next, ProjectGraphAnalyzer.buildScope(project, next.element("work")))
        assertEquals(next.graph, repeated.graph)
        assertEquals(next.occurrences, repeated.occurrences)

        val calls = liftCalls(next.graph).edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
        val reading = SchematicBuilder.build(next, next.graph.hierarchy(), calls, work)!!
        assertEquals(listOf(deep), reading.targets)
    }

    fun testJavaScriptArrowFunctionsAndCallbacksResolveOnlyInTheOpenedBranch() {
        val file = myFixture.configureByText("controller.jsx", """
            const leaf = () => {};
            const entry = () => {
                [1].forEach(() => leaf());
                function nested() { missingNested(); }
            };
            const unused = () => { missingSibling(); };
        """.trimIndent())
        val initial = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        val root = ProjectGraphAnalyzer.buildScope(project, file)
        assertTrue(root.occurrences.isEmpty())
        val opened = merge(initial, ProjectGraphAnalyzer.buildScope(project, initial.element("entry")))
        assertTrue(opened.occurrences.any { it.expression == "leaf" })
        assertFalse(opened.occurrences.any { it.expression.startsWith("missing") })
        val leaf = opened.symbol("leaf").id
        val entry = opened.symbol("entry").id
        assertTrue(liftCalls(opened.graph).edges.contains(CallGraphEdge(entry, leaf)))
    }

    fun testChatReadsNewDiscoveriesAndOnlyLoadsExplicitlyInspectedScopes() {
        val file = myFixture.configureByText("Selected.java", "class Selected { void entry() { next(); } void next() {} }")
        var result = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        val chat = SchematicGraph(result.graph, result.occurrences, { null },
            snapshot = { liftCalls(result.graph) to result.occurrences },
            expand = { id -> result = merge(result, ProjectGraphAnalyzer.buildScope(project, result.pointers[id]!!.element!!)) })
        val entry = result.symbol("entry").id
        assertTrue(chat.callees(entry).isEmpty())
        runBlocking { chat.load(entry) }
        assertEquals(listOf(result.symbol("next").id), chat.callees(entry))
        assertTrue(chat.inspect(entry).contains("Call sites in its body"))
    }

    fun testVisibleMethodHeadlinesLinkTypesWithoutOpeningBodies() {
        myFixture.addFileToProject("Request.java", "class Request {}")
        myFixture.addFileToProject("Response.java", "class Response {}")
        myFixture.addFileToProject("Hidden.java", "class Hidden { static void work() {} }")
        val file = myFixture.configureByText("Api.java", """
            class Api {
                Response run(Request request) {
                    Hidden.work();
                    return null;
                }
            }
        """.trimIndent())
        val initial = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        for (scope in listOf(file, initial.element("Api"))) {
            val result = ProjectGraphAnalyzer.buildScope(project, scope)
            val id = result.graph.nodes.single { result.pointers[it.id]?.element == scope }.id
            val reading = SchematicBuilder.build(result, result.graph.hierarchy(), emptyMap(), id)!!
            val row = reading.rows.filterIsInstance<BlockRow>().single { it.declared == result.symbol("run").id }
            val references = row.links.filter { it.reference }.flatMap { it.targets }.toSet()
            assertEquals(setOf(result.symbol("Request").id, result.symbol("Response").id), references)
            assertTrue(result.occurrences.isEmpty())
            assertFalse(result.graph.nodes.any { it.name == "Hidden" })
        }
    }

    fun testWholeReadingLinksEveryNameInFoldedBodies() {
        myFixture.addFileToProject("Request.java", "class Request {}")
        myFixture.addFileToProject("Response.java", "class Response {}")
        myFixture.addFileToProject("Store.java", "class Store { static void save(Request request) {} }")
        val file = myFixture.configureByText("Api.java", """
            class Api<T> {
                Response run(
                        Request request,
                        T extra) {
                    Request copy = request;
                    Store.save(copy);
                    return null;
                }
            }
        """.trimIndent())
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val root = exploration.result.graph.nodes.single { it.kind == dev.insight.graph.SymbolKind.FILE }.id
        assertTrue("A folded reading has no mentions", background { exploration.schematic(root) }!!.mentions.isEmpty())

        val reading = background { exploration.schematic(root, whole = true) }!!
        val result = exploration.result
        fun name(id: Int) = (result.pointers[id]?.element as? PsiNamedElement)?.name
        val byLine = reading.mentions.groupBy({ it.line }, { name(it.target) }).mapValues { it.value.toSet() }
        assertEquals(setOf("Request", "request"), byLine[2])
        assertEquals("Type parameters and parameters on later signature lines", setOf("T", "extra"), byLine[3])
        assertEquals(setOf("Request", "copy", "request"), byLine[4])
        assertEquals("A call into another file, found in a folded body", setOf("Store", "save", "copy"), byLine[5])
        assertFalse(reading.mentions.single { name(it.target) == "save" }.reference)
        assertTrue(reading.mentions.none { it.target == root })
    }

    fun testNewlyDiscoveredBoundaryFilesAreTrackedAfterExpansion() {
        val helper = myFixture.addFileToProject("Helper.java", "class Helper { static void work() {} }")
        val file = myFixture.configureByText("Selected.java", "class Selected { void entry() { Helper.work(); } }")
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val document = PsiDocumentManager.getInstance(project).getDocument(helper)!!
        fun editHelper() {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "\n// edited")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
        // This file has not contributed any declarations yet, so its edits must not invalidate the exploration.
        editHelper()
        val entry = exploration.result.symbol("entry").id
        background { exploration.expand(entry) }
        val opened = exploration.result
        val work = opened.symbol("work").id
        editHelper()
        val failure = background { runCatching { exploration.expand(work) }.exceptionOrNull() }
        assertTrue("Expected stale boundary rejection, got $failure", failure is IllegalStateException)
        assertSame(opened, exploration.result)
    }

    fun testExplorationReusesOpenedScopesAndAllowsUnrelatedEdits() {
        val file = myFixture.configureByText("Selected.java", "class Selected { void entry() { next(); } void next() {} void last() {} }")
        val exploration = background { ProgressiveSchematic.open(project, file.virtualFile) }!!
        val entry = exploration.result.symbol("entry").id
        background { exploration.expand(entry) }
        val opened = exploration.result
        background { exploration.expand(entry) }
        assertSame(opened, exploration.result)

        myFixture.addFileToProject("Changed.java", "class Changed {}")
        val next = opened.symbol("next").id
        background { exploration.expand(next) }
        val beforeEdit = exploration.result
        val last = beforeEdit.symbol("last").id
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n// changed") }
        val failure = background { runCatching { exploration.expand(last) }.exceptionOrNull() }
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("Reopen the schematic"))
        assertSame(beforeEdit, exploration.result)
    }
}
