package dev.insight.diff

import dev.insight.callgraph.SymbolKind
import org.junit.Assert.*
import org.junit.Test

class SideDeclarationsTest {
    private fun declaration(name: String, header: String = "fun $name()") =
        SideDeclaration("A.$name", SymbolKind.FUNCTION, header, "$header { }", 1..1)

    @Test fun `matching keeps overload partners and pairs a changed signature`() {
        val before = listOf(declaration("save", "fun save(a: Int)"), declaration("save", "fun save(b: String)"), declaration("gone"))
        val after = listOf(declaration("save", "fun save(b: String)"), declaration("save", "fun save(a: Long)"), declaration("fresh"))
        val pairs = matchDeclarations(before, after)
        assertTrue(pairs.contains(before[1] to after[0]))
        assertTrue(pairs.contains(before[0] to after[1]))
        assertTrue(pairs.contains(null to after[2]))
        assertTrue(pairs.contains(before[2] to null))
    }

    @Test fun `headers skip annotations and stop at bodies without splitting parameter defaults`() {
        assertEquals("fun f(a: Int = 1): String", declarationHeader("@Suppress(\"x\")\nfun f(a: Int = 1): String {\n  return \"\"\n}"))
        assertEquals("fun g()", declarationHeader("fun g() = 42"))
        assertEquals("def run(self, x):", declarationHeader("@decorator\ndef run(self, x):\n    pass"))
        assertEquals("public void go(List<String> xs)", declarationHeader("@Override\npublic void go(List<String> xs) { }"))
        assertEquals("fun h(a: Int)", declarationHeader("fun h(a: Int) { a == 1 }"))
        assertEquals("fun k(): Boolean", declarationHeader("fun k(): Boolean =\n    x >= 1"))
    }
}
