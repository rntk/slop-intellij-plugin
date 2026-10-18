package dev.insight.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileEnrichmentCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private fun path(): Path = folder.root.toPath().resolve("nested/cache.json")

    private val full = NodeAnnotation(
        concerns = mapOf("storage" to 0.8, "network" to 0.1),
        phase = ChoiceValue("run", mapOf("run" to 0.7, "init" to 0.3), 0.9),
        layer = ChoiceValue("service", mapOf("service" to 1.0), 0.5),
        importance = ScoreValue(3.5, 5, 0.75),
        entryPoint = 0.2,
        utility = 0.0,
        domain = ChoiceValue("billing", mapOf("billing" to 0.6, "none" to 0.4), 0.8),
    )

    @Test fun `round trips nodes edges and texts`() {
        val cache = FileEnrichmentCache(path())
        cache.putNode("a", full)
        cache.putNode("b", NodeAnnotation())
        cache.putEdge("e", EdgeAnnotation(0.25, 0.75))
        cache.putText("t", "{\"label\":\"unicode ✓\"}")
        cache.flush()
        val reloaded = FileEnrichmentCache(path())
        assertEquals(full, reloaded.node("a"))
        assertEquals(NodeAnnotation(), reloaded.node("b"))
        assertEquals(EdgeAnnotation(0.25, 0.75), reloaded.edge("e"))
        assertEquals("{\"label\":\"unicode ✓\"}", reloaded.text("t"))
        assertNull(reloaded.node("missing"))
    }

    @Test fun `missing fields get Kotlin defaults`() {
        Files.createDirectories(path().parent)
        Files.writeString(path(), """{"version":1,"nodes":{"a":{"entryPoint":0.5,"phase":{"choice":"run"}},"bad":7},"edges":{"e":{}},"texts":{}}""")
        val cache = FileEnrichmentCache(path())
        assertEquals(NodeAnnotation(entryPoint = 0.5, phase = ChoiceValue("run", emptyMap(), 0.0)), cache.node("a"))
        assertNull(cache.node("bad"))
        assertEquals(EdgeAnnotation(0.0, 0.0), cache.edge("e"))
    }

    @Test fun `non finite numbers are stored as zero`() {
        val cache = FileEnrichmentCache(path())
        cache.putEdge("e", EdgeAnnotation(Double.NaN, Double.POSITIVE_INFINITY))
        cache.flush()
        assertEquals(EdgeAnnotation(0.0, 0.0), FileEnrichmentCache(path()).edge("e"))
    }

    @Test fun `corrupt file starts empty and can be overwritten`() {
        Files.createDirectories(path().parent)
        Files.writeString(path(), "{ this is not json")
        val cache = FileEnrichmentCache(path())
        assertNull(cache.node("a"))
        cache.putNode("a", full)
        cache.flush()
        assertEquals(full, FileEnrichmentCache(path()).node("a"))
    }

    @Test fun `version mismatch starts empty`() {
        Files.createDirectories(path().parent)
        Files.writeString(path(), """{"version":${FileEnrichmentCache.VERSION + 1},"nodes":{"a":{}},"edges":{},"texts":{"t":"x"}}""")
        val cache = FileEnrichmentCache(path())
        assertNull(cache.node("a"))
        assertNull(cache.text("t"))
    }

    @Test fun `flush without changes writes nothing and leaves no temp files`() {
        val cache = FileEnrichmentCache(path())
        cache.flush()
        assertEquals(false, Files.exists(path()))
        cache.putText("t", "x")
        cache.flush()
        assertNotNull(cache.text("t"))
        assertEquals(listOf("cache.json"), Files.list(path().parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test fun `concurrent writers are all persisted`() {
        val cache = FileEnrichmentCache(path())
        val executor = Executors.newFixedThreadPool(8) { task ->
            Thread(task, "cache-test-writer").apply { isDaemon = true }
        }
        try {
            val writers = (0 until 8).map { t -> Callable {
                repeat(50) {
                    cache.putEdge("$t-$it", EdgeAnnotation(it / 100.0, 0.5))
                    if (it % 10 == 0) cache.flush()
                }
            } }
            // Bound the whole batch and propagate failures from every writer.
            executor.invokeAll(writers, 10, TimeUnit.SECONDS).forEach { it.get() }
        } finally { executor.shutdownNow() }
        cache.flush()
        val reloaded = FileEnrichmentCache(path())
        assertEquals(EdgeAnnotation(0.49, 0.5), reloaded.edge("7-49"))
        assertEquals(400, (0 until 8).sumOf { t -> (0 until 50).count { reloaded.edge("$t-$it") != null } })
    }
}
