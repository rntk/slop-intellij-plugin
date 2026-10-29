package dev.insight

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import dev.insight.graph.*

/** `callee: KIND label ?subject / …` for each call site of [caller], outermost frame first; exits are `!keyword`. */
private fun sites(project: Project, caller: String): List<String> {
    val result = ProjectGraphAnalyzer.build(project)
    val functions = result.graph.nodes.filter { it.inProject && it.kind == SymbolKind.FUNCTION }
    val id = functions.single { it.name.substringBefore('(') == caller }.id
    return CallSiteAnalyzer.collect(result, listOf(id), functions.map { it.id }).getValue(id).map { site ->
        val name = when (site) {
            is CallSite -> result.graph.nodes[site.callee].name.substringBefore('(')
            is ExitSite -> "!" + site.keyword
        }
        name + ":" + site.frames.joinToString(" /") { frame ->
            " ${frame.kind} ${if (frame.otherwise) "else" else frame.label}" + (frame.subject?.let { " ?$it" } ?: "")
        }
    }
}

/** Constructs of the first frame of `callees`' sites, in order. */
private fun constructs(project: Project): List<Int?> {
    val result = ProjectGraphAnalyzer.build(project)
    val functions = result.graph.nodes.filter { it.inProject && it.kind == SymbolKind.FUNCTION }
    val id = functions.single { it.name.substringBefore('(') == "run" }.id
    return CallSiteAnalyzer.collect(result, listOf(id), functions.map { it.id }).getValue(id).map { it.frames.firstOrNull()?.construct }
}

class CallSitesTest : LightJavaCodeInsightFixtureTestCase() {
    fun `test Java conditions, else-if chains, switch rules, loops, catch and ternaries`() {
        myFixture.configureByText("Flow.java", """
            class Flow {
                void a() {} void b() {} void c() {} void d() {} void e() {} void f() {} void g() {} void h() {}
                int k() { return 1; }
                void run(int kind, java.util.List<String> xs, boolean ok) {
                    a();
                    if (kind == 1) { b(); } else if (kind == 2) { c(); } else { d(); }
                    switch (kind) { case 3 -> e(); default -> f(); }
                    for (String x : xs) { g(); }
                    try { h(); } catch (RuntimeException ex) { a(); }
                    int v = ok ? k() : 0;
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: BRANCH kind == 1 ?kind == 1",
            "c: BRANCH kind == 2 ?kind == 1",
            "d: BRANCH else ?kind == 1",
            "e: CASE 3 ?kind",
            "f: CASE else ?kind",
            "g: LOOP for (String x : xs)",
            "h:",
            "a: ERROR RuntimeException ex",
            "k: BRANCH ok ?ok",
        ), sites(project, "run"))
        val chain = constructs(project).subList(1, 4)
        assertEquals("else-if branches share one construct", 1, chain.toSet().size)
    }

    fun `test nested and chained calls come in evaluation order`() {
        myFixture.configureByText("Chain.java", """
            class Chain {
                Chain make() { return this; }
                void use(int value) {}
                int count() { return 0; }
                void run() { make().use(count()); }
            }
        """.trimIndent())
        assertEquals(listOf("make:", "count:", "use:"), sites(project, "run"))
    }

    fun `test old-style Java switch labels guard the statements after them`() {
        myFixture.configureByText("Old.java", """
            class Old {
                void a() {} void b() {}
                void run(int kind) {
                    switch (kind) {
                        case 1:
                            a();
                            break;
                        default:
                            b();
                    }
                }
            }
        """.trimIndent())
        assertEquals(listOf("a: CASE 1 ?kind", "b: CASE else ?kind"), sites(project, "run"))
    }

    fun `test Kotlin when, if expressions, loops and catch`() {
        myFixture.configureByText("Flow.kt", """
            class Flow {
                fun a() {} fun b() {} fun c() {} fun d() {} fun e() {} fun f() {} fun g() {} fun h() {}
                fun run(kind: Int, xs: List<String>, ok: Boolean) {
                    a()
                    when (kind) {
                        1 -> b()
                        2, 3 -> c()
                        else -> d()
                    }
                    if (ok) e() else f()
                    for (x in xs) g()
                    try { h() } catch (ex: Exception) { a() }
                    fun local() { b() }
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: CASE 1 ?kind",
            "c: CASE 2, 3 ?kind",
            "d: CASE else ?kind",
            "e: BRANCH ok ?ok",
            "f: BRANCH else ?ok",
            "g: LOOP for (x in xs)",
            "h:",
            "a: ERROR ex: Exception",
        ), sites(project, "run"))
    }

    fun `test Java exits end branches, cases and catches but not lambdas, loops or try blocks`() {
        myFixture.configureByText("Exits.java", """
            class Exits {
                void a() {} void b() {} void c() {}
                int run(int kind, java.util.List<String> xs) {
                    if (kind == 0) return -1;
                    if (kind == 1) { a(); return 1; } else if (kind == 2) { throw new IllegalStateException(); }
                    if (kind == 3) { return 3; /* done */ }
                    if (kind == 4) { if (xs.isEmpty()) return 4; b(); }
                    switch (kind) { case 5 -> { return 5; } default -> c(); }
                    for (String x : xs) { return 6; }
                    try { return 7; } catch (RuntimeException ex) { a(); throw ex; }
                }
                void lambdas(java.util.List<String> xs) {
                    xs.forEach(x -> { if (x.isEmpty()) return; a(); });
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "!return: BRANCH kind == 0 ?kind == 0",
            "a: BRANCH kind == 1 ?kind == 1",
            "!return: BRANCH kind == 1 ?kind == 1",
            "!throw: BRANCH kind == 2 ?kind == 1",
            "!return: BRANCH kind == 3 ?kind == 3",
            // Only the inner `if` ends: `b()` still runs after it.
            "!return: BRANCH kind == 4 ?kind == 4 / BRANCH xs.isEmpty() ?xs.isEmpty()",
            "b: BRANCH kind == 4 ?kind == 4",
            "!return: CASE 5 ?kind",
            "c: CASE else ?kind",
            "a: ERROR RuntimeException ex",
            "!throw: ERROR RuntimeException ex",
        ), sites(project, "run"))
        assertEquals(listOf("a:"), sites(project, "lambdas"))
    }

    fun `test Kotlin exits end branches but not expressions or labelled returns`() {
        myFixture.configureByText("Exits.kt", """
            class Exits {
                fun a() {} fun b() {}
                fun run(kind: Int, name: String?, xs: List<String>): Int {
                    val n = name ?: return 0
                    if (kind == 1) return 1
                    when (kind) {
                        2 -> { a(); throw IllegalStateException() }
                        else -> b()
                    }
                    xs.forEach { if (it.isEmpty()) return@forEach }
                    return n.length
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "!return: BRANCH kind == 1 ?kind == 1",
            "a: CASE 2 ?kind",
            "!throw: CASE 2 ?kind",
            "b: CASE else ?kind",
        ), sites(project, "run"))
    }
}

class PythonCallSitesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        assertEquals("PythonCore is not loaded in the test IDE", "Python", FileTypeManager.getInstance().getFileTypeByFileName("a.py").name)
    }

    fun `test Python if-elif-else, loops, try-except and conditional expressions`() {
        myFixture.configureByText("flow.py", """
            def a(): pass
            def b(): pass
            def c(): pass
            def d(): pass
            def e(): pass
            def f(): pass
            def g(): pass
            def h(): pass

            def run(kind, xs):
                a()
                if kind == 1:
                    b()
                elif kind == 2:
                    c()
                else:
                    d()
                for x in xs:
                    e()
                try:
                    f()
                except ValueError as err:
                    g()
                v = h() if kind else None
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: BRANCH kind == 1 ?kind == 1",
            "c: BRANCH kind == 2 ?kind == 1",
            "d: BRANCH else ?kind == 1",
            "e: LOOP for x in xs",
            "f:",
            "g: ERROR ValueError as err",
            "h: BRANCH kind ?kind",
        ), sites(project, "run"))
        assertEquals(1, constructs(project).subList(1, 4).toSet().size)
    }

    fun `test Python guard clauses of a request router end their branches`() {
        myFixture.configureByText("router.py", """
            import json

            class Handler:
                def send_error(self, status): pass
                def graph(self, body): pass
                def functions(self, body): pass

                def do_post(self, path, raw):
                    try:
                        body = json.loads(raw)
                    except ValueError:
                        self.send_error(400)
                        return
                    if not body:
                        raise ValueError("empty")
                    if path == "/api/graph":
                        self.graph(body)
                        return
                    if path == "/api/functions":
                        self.functions(body)
                        return  # handled
                    for item in body:
                        if item:
                            return
                    self.send_error(404)
        """.trimIndent())
        assertEquals(listOf(
            "send_error: ERROR ValueError",
            "!return: ERROR ValueError",
            "!raise: BRANCH not body ?not body",
            "graph: BRANCH path == \"/api/graph\" ?path == \"/api/graph\"",
            "!return: BRANCH path == \"/api/graph\" ?path == \"/api/graph\"",
            "functions: BRANCH path == \"/api/functions\" ?path == \"/api/functions\"",
            "!return: BRANCH path == \"/api/functions\" ?path == \"/api/functions\"",
            "!return: LOOP for item in body / BRANCH item ?item",
            "send_error:",
        ), sites(project, "do_post"))
    }
}
