package dev.insight.context

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.agent.SourceSide

class ContextSymbolsTest : BasePlatformTestCase() {
    private val source = """
        class Outer {
            int value;

            void render(int x) {
                int y = x + 1;
                value = y;
            }
        }

        // trailing comment
    """.trimIndent()

    fun testEnclosingSymbolsListInnermostFirst() {
        val file = myFixture.configureByText("Outer.java", source)
        val symbols = enclosingSymbols(file, source.indexOf("x + 1"))
        assertEquals(listOf("render", "Outer"), symbols.map { it.symbol.name })
        assertEquals(4..7, symbols[0].lines)
        assertEquals("function", symbols[0].symbol.kind)
        assertEquals("Outer.render", symbols[0].symbol.qualifiedName)
        assertEquals(1..8, symbols[1].lines)
        assertEquals("class", symbols[1].symbol.kind)
    }

    fun testOffsetOutsideAnySymbolIsEmpty() {
        val file = myFixture.configureByText("Outer.java", source)
        assertEquals(emptyList<SymbolSpan>(), enclosingSymbols(file, source.indexOf("trailing")))
    }

    fun testSymbolsInTextParseABeforeSide() {
        val before = source.replace("value = y;", "value = y;\n        value++;")
        // Line 5 is the local's line; its indentation is skipped so the method, not the class body, encloses it.
        val symbols = symbolsInText(project, "src/Outer.java", before, 5)
        assertEquals(listOf("render", "Outer"), symbols.map { it.symbol.name })
        assertEquals(4..8, symbols[0].lines)
        assertEquals(emptyList<SymbolSpan>(), symbolsInText(project, "notes.unknownext", before, 5))
    }

    fun testRegistrySkipsFailingActionsAndForgetsDisposedOnes() {
        val request = ContextRequest(project, CodeContext(ContextScopeKind.FILE, "File", "a", null, null, SourceSide.CURRENT, ""),
            emptyList(), ContextOrigin.OTHER, ContextData(), null)
        val ok = object : ContextAction {
            override val id = "test.ok"; override val title = "Ok"; override val group = "Copy"
            override fun isAvailable(request: ContextRequest) = true
            override fun perform(request: ContextRequest) {}
        }
        val failing = object : ContextAction {
            override val id = "test.failing"; override val title = "Failing"; override val group = "Copy"
            override fun isAvailable(request: ContextRequest): Boolean = throw IllegalStateException("broken")
            override fun perform(request: ContextRequest) {}
        }
        val registry = ContextActionRegistry.getInstance()
        val scope = Disposer.newDisposable()
        try {
            registry.register(ok, scope)
            registry.register(failing, scope)
            val ids = registry.actions(request).map { it.id }
            assertTrue(ids.toString(), "test.ok" in ids && "test.failing" !in ids)
        } finally {
            Disposer.dispose(scope)
        }
        assertTrue(registry.all().none { it.id.startsWith("test.") })
    }
}
