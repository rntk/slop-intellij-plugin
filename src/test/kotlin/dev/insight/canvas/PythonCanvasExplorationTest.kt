package dev.insight.canvas

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/** Follows real source links through cards, including bindings whose PSI excludes their initializer. */
class PythonCanvasExplorationTest : BasePlatformTestCase() {
    fun testRestoringAnExternalMethodDoesNotResolveUnopenedFunctionBodies() {
        val other = myFixture.addFileToProject("worker.py", """
            class Worker:
                def selected(self):
                    return used_call()

                def unrelated(self):
                    return unopened_call()
        """.trimIndent())
        val file = myFixture.configureByText("entry.py", "def main():\n    pass\n")
        val opened = background { openCanvasSessionFile(project, file.virtualFile, store = null) }!!
        val path = relativePath(project, other.virtualFile.path)
        val saved = CanvasCard("method", null, CardKind.SYMBOL, CardAnchor(0, 3, 0),
            SymbolRef(path, "selected(self)", "FUNCTION"), path, 1, "old snapshot",
            summary = "Saved summary")
        val session = CanvasSession.new("entry.py").copy(cards = listOf(saved))
        val controller = CanvasSessionController(project, file.virtualFile, opened.exploration, opened.root,
            CanvasSessionModel(session))
        Disposer.register(testRootDisposable, controller)
        background { controller.loadRoot(); controller.restore() }
        val restored = controller.source(saved.id)!!
        assertNotNull("The saved method should resolve to live source", restored.node)
        assertTrue(restored.text, restored.text.contains("return used_call()"))
        assertEquals(saved.summary, controller.model.cards.single().summary)
        val names = opened.exploration.result.graph.nodes.map { it.name.substringBefore('(') }
        assertTrue(names.toString(), "used_call" in names)
        assertFalse("Restoring one card must not resolve unrelated bodies: $names", "unopened_call" in names)
    }

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas analysis should finish", { task.isCompleted }, 30)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun canvas(source: String): CanvasSessionController {
        val file = myFixture.configureByText("entry.py", source.trimIndent())
        val opened = background { openCanvasSessionFile(project, file.virtualFile, store = null) }!!
        return CanvasSessionController(project, file.virtualFile, opened.exploration, opened.root,
            CanvasSessionModel(opened.session)).also {
            Disposer.register(testRootDisposable, it)
            background { it.loadRoot() }
        }
    }

    private fun CanvasSessionController.open(parent: CanvasCard?, text: String, name: String = text): CanvasCard {
        val source = source(parent?.id)!!
        val at = source.text.indexOf(text)
        assertTrue("Missing '$text' in ${source.text}", at >= 0)
        val link = linkAt(source.links, at + text.length - 1)
        assertNotNull("No link at '$text' in ${source.text}", link)
        val target = link!!.targets.singleOrNull { node(it)?.name?.substringBefore('(') == name }
        assertNotNull("No target '$name' at '$text': ${link.targets.map { node(it)?.name }}", target)
        return background { openSymbol(parent?.id, link.range.first, link.range.last + 1, target!!) }
    }

    fun testAttributeAssignmentCardKeepsConstructorAndConfigurationClickable() {
        myFixture.addFileToProject("routes.py", """
            class RSSTagRoutes:
                def __init__(self, host, handlers):
                    self.host = host

                def dispatch(self):
                    return self.host
        """.trimIndent())
        myFixture.addFileToProject("service.py", """
            from routes import RSSTagRoutes

            class App:
                def __init__(self, config):
                    self.config = config
                    self.routes = RSSTagRoutes(self.config["settings"]["host_name"], handlers=self)

                def run(self):
                    return self.routes
        """.trimIndent())
        val canvas = canvas("""
            from service import App

            def run(app: App):
                return app.routes
        """)
        val run = canvas.open(null, "run")
        val routes = canvas.open(run, "app.routes", "routes")
        assertEquals("self.routes = RSSTagRoutes(self.config[\"settings\"][\"host_name\"], handlers=self)", routes.text.trim())
        val constructor = linkAt(canvas.source(routes.id)!!.links, routes.text.indexOf("RSSTagRoutes"))
        assertNotNull("The constructor stays clickable in the attribute card", constructor)
        val classOrConstructor = constructor!!.targets.first { canvas.node(it)?.name?.substringBefore('(') in setOf("RSSTagRoutes", "__init__") }
        val next = background { canvas.openSymbol(routes.id, constructor.range.first, constructor.range.last + 1, classOrConstructor) }
        assertEquals(routes.id, next.parentId)
        assertTrue(next.text, next.text.contains("self.host = host"))

        val config = canvas.open(routes, "self.config", "config")
        assertEquals("self.config = config", config.text.trim())
        val parameter = canvas.open(config, "= config", "config")
        assertTrue(parameter.text, parameter.text.contains("def __init__(self, config)"))
        assertEquals(listOf(run.id, routes.id, config.id, parameter.id), canvas.model.chain(parameter.id).map { it.id })
    }

    fun testMultilineLocalBindingKeepsTheEntireInitializerAndItsLinks() {
        myFixture.addFileToProject("routes.py", """
            def build_routes(host, handlers):
                return host
        """.trimIndent())
        val canvas = canvas("""
            from routes import build_routes

            def create(config, handler):
                routes = build_routes(
                    config["settings"]["host_name"],
                    handlers=handler,
                )
                return routes
        """)
        val binding = canvas.open(null, "return routes", "routes")
        assertTrue(binding.text, binding.text.contains("handlers=handler,"))
        assertTrue(binding.text, binding.text.trimEnd().endsWith(")"))
        val factory = canvas.open(binding, "build_routes")
        assertTrue(factory.text, factory.text.contains("return host"))
        canvas.open(binding, "config")
        canvas.open(binding, "handlers=handler", "handler")
        assertFalse("Opening the binding must not include the following statement", binding.text.contains("return routes"))
    }

    fun testInitializerReferencesUseIDEInferenceAcrossUnannotatedFactories() {
        myFixture.addFileToProject("settings.py", """
            class Settings:
                def __init__(self):
                    self.host = "example.test"

            def make_settings():
                return Settings()
        """.trimIndent())
        val canvas = canvas("""
            from settings import make_settings

            def create():
                settings = make_settings()
                host = settings.host
                return host
        """)
        val binding = canvas.open(null, "return host", "host")
        val attribute = canvas.open(binding, "settings.host", "host")
        assertTrue(attribute.text, attribute.text.contains("self.host = \"example.test\""))
        val settings = canvas.open(binding, "settings")
        assertTrue(settings.text, settings.text.contains("make_settings()"))
        val factory = canvas.open(settings, "make_settings")
        assertTrue(factory.text, factory.text.contains("return Settings()"))
    }

    fun testUnknownInitializerCallsKeepTheirOccurrenceLinkInTheBindingCard() {
        val canvas = canvas("""
            def create(factory, config):
                routes = factory.build(config)
                return routes
        """)
        val binding = canvas.open(null, "return routes", "routes")
        val source = canvas.source(binding.id)!!
        val call = linkAt(source.links, source.text.indexOf("build"))
        assertNotNull("Retain call evidence even when there is no resolved declaration", call)
        assertTrue(call!!.targets.any { canvas.node(it)?.unresolved == true })
        canvas.open(binding, "factory")
        canvas.open(binding, "config")
    }
}
