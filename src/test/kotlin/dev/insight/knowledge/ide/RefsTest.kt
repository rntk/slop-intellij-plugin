package dev.insight.knowledge.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefsTest {
    @Test fun `dotted names split into qualifiers and name`() {
        assertEquals(SymbolRef(null, listOf("dev", "insight", "diff", "walk"), "WalkModel"), SymbolRef.parse("dev.insight.diff.walk.WalkModel"))
        assertEquals(SymbolRef(null, listOf("WalkthroughWorkflow"), "run"), SymbolRef.parse("WalkthroughWorkflow.run"))
        assertEquals(SymbolRef(null, emptyList(), "run"), SymbolRef.parse("run"))
    }

    @Test fun `file pinned refs keep the path`() {
        assertEquals(SymbolRef("WalkModel.kt", emptyList(), "WalkModel"), SymbolRef.parse("WalkModel.kt#WalkModel"))
        assertEquals(SymbolRef("src/a/Foo.java", listOf("Foo"), "bar"), SymbolRef.parse("src/a/Foo.java#Foo.bar"))
    }

    @Test fun `java and rust separators and call parentheses are accepted`() {
        assertEquals(SymbolRef(null, listOf("Foo"), "bar"), SymbolRef.parse("Foo#bar"))
        assertEquals(SymbolRef(null, listOf("Foo"), "bar"), SymbolRef.parse("Foo::bar"))
        assertEquals(SymbolRef(null, listOf("Foo"), "bar"), SymbolRef.parse("Foo.bar(a, b)"))
    }

    @Test fun `malformed refs are rejected`() {
        assertNull(SymbolRef.parse(""))
        assertNull(SymbolRef.parse("a b.c"))
        assertNull(SymbolRef.parse("a..b".replace("..", "-")))
    }

    @Test fun `render round trips`() {
        listOf("a.b.C.d", "Foo.kt#Foo.bar").forEach { assertEquals(it, SymbolRef.parse(it)!!.render()) }
    }

    @Test fun `call edges split on the arrow`() {
        assertEquals(CallEdgeRef("A.run", "B.go"), CallEdgeRef.parse("A.run->B.go"))
        assertEquals(CallEdgeRef("A.run", "B.go"), CallEdgeRef.parse(" A.run -> B.go "))
        assertNull(CallEdgeRef.parse("A.run"))
        assertNull(CallEdgeRef.parse("->B"))
    }

    @Test fun `qualifiers decide between exact, moved and unrelated`() {
        val ref = SymbolRef.parse("a.Worker.runJob")!!
        assertEquals(RefMatch.EXACT, matchRef(ref, "a/Worker.java", listOf("a"), listOf("Worker"), "Worker"))
        assertEquals(RefMatch.DRIFT, matchRef(ref, "b/Worker.java", listOf("b"), listOf("Worker"), "Worker"))
        assertEquals(RefMatch.NONE, matchRef(ref, "a/Runner.java", listOf("a"), listOf("Runner"), "Runner"))
    }

    @Test fun `unqualified refs match any owner and a pinned path must agree`() {
        assertEquals(RefMatch.EXACT, matchRef(SymbolRef.parse("runJob")!!, "x/Y.kt", emptyList(), listOf("Y"), "Y"))
        val pinned = SymbolRef.parse("x/Y.kt#Y")!!
        assertEquals(RefMatch.EXACT, matchRef(pinned, "x/Y.kt", emptyList(), emptyList(), "Y"))
        assertEquals(RefMatch.DRIFT, matchRef(pinned, "z/Y.kt", emptyList(), emptyList(), "Y"))
    }

    @Test fun `top level members may be qualified by their file name`() {
        assertEquals(RefMatch.EXACT, matchRef(SymbolRef.parse("Utils.helper")!!, "a/Utils.kt", listOf("a"), emptyList(), "Utils"))
    }

    @Test fun `project paths are normalized`() {
        assertEquals("a/b", normalizeProjectPath("./a\\b/"))
        assertEquals("", normalizeProjectPath("."))
        assertEquals("a", normalizeProjectPath("/a"))
    }
}
