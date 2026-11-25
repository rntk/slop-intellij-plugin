package dev.insight.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimClassifierTest {
    @Test fun heuristicFlagsNumbersUnitsConstantsAndVersions() = runBlocking {
        val claims = listOf(
            docClaim("a", "Requests time out after 30 seconds."),
            docClaim("b", "Retries are capped by MAX_RETRY_COUNT."),
            docClaim("c", "Built against platform 2025.2.4."),
            docClaim("d", "Reviewers can explore changed files."),
        )
        val result = ClaimClassifier(null).classify(claims)
        assertTrue(result.getValue("a").volatile)
        assertTrue(result.getValue("b").volatile)
        assertTrue(result.getValue("c").volatile)
        assertFalse(result.getValue("d").volatile)
        assertFalse(result.getValue("d").detail)
        assertTrue(result.getValue("d").stability > result.getValue("a").stability)
    }

    @Test fun heuristicUsesTagsLevelAndBacktickDensity() = runBlocking {
        val result = ClaimClassifier(null).classify(listOf(
            docClaim("t", "A plain statement.", tags = setOf("detail")),
            docClaim("v", "A plain statement.", tags = setOf("volatile")),
            docClaim("dense", "`Foo` calls `bar` via `Baz`.", level = ClaimLevel.FILE),
            docClaim("sym", "Wraps `foo` for callers.", level = ClaimLevel.SYMBOL),
            docClaim("symPlain", "Helps callers.", level = ClaimLevel.SYMBOL),
            docClaim("flow", "Shows how `Foo` fits in.", level = ClaimLevel.FLOW),
        ))
        assertTrue(result.getValue("t").detail)
        assertTrue(result.getValue("v").volatile)
        assertTrue(result.getValue("dense").detail)
        assertTrue(result.getValue("sym").detail)
        assertFalse(result.getValue("symPlain").detail)
        assertFalse(result.getValue("flow").detail)
        assertTrue(result.getValue("t").confidence > result.getValue("flow").confidence)
    }

    @Test fun heuristicKeepsClaimStabilityWhenSet() = runBlocking {
        val result = ClaimClassifier(null).classify(listOf(docClaim("a", stability = 0.42)))
        assertEquals(0.42, result.getValue("a").stability, 1e-9)
    }

    @Test fun decisionsAnswersAreUsedInBatches() = runBlocking {
        val fake = FakeClassifierDecisions(detail = setOf("b"), volatile = setOf("c"), stability = mapOf("a" to 2.0))
        val classifier = ClaimClassifier(fake::decide, batchSize = 2)
        val result = classifier.classify(listOf(docClaim("a"), docClaim("b"), docClaim("c")))
        assertEquals(2, fake.requests.size)
        assertEquals(6, fake.requests.first().questions.size)
        assertEquals(0.5, result.getValue("a").stability, 1e-9)
        assertTrue(result.getValue("b").detail)
        assertFalse(result.getValue("b").volatile)
        assertTrue(result.getValue("c").volatile)
        assertTrue(result.values.all { it.decided && it.confidence > 0.5 })
    }

    @Test fun failedOrMissingAnswersFallBackToHeuristicAndAreNotCached() = runBlocking {
        val cache = InMemoryClaimClassCache()
        val failing = FakeClassifierDecisions(failWith = IllegalStateException("down"))
        val first = ClaimClassifier(failing::decide, cache).classify(listOf(docClaim("a", "Waits 5 seconds.")))
        assertTrue(first.getValue("a").volatile)
        assertFalse(first.getValue("a").decided)
        assertEquals(0, cache.size)

        val partial = FakeClassifierDecisions(unanswered = setOf("b"))
        val second = ClaimClassifier(partial::decide, cache).classify(listOf(docClaim("a"), docClaim("b")))
        assertTrue(second.getValue("a").decided)
        assertFalse(second.getValue("b").decided)
        assertEquals(1, cache.size)
    }

    @Test fun resultsAreCachedByIdAndNormalizedText() = runBlocking {
        val fake = FakeClassifierDecisions(detail = setOf("a"))
        val classifier = ClaimClassifier(fake::decide)
        val first = classifier.classify(listOf(docClaim("a", "Does a thing.")))
        val again = classifier.classify(listOf(docClaim("a", "  does   a THING ")))
        assertEquals(1, fake.requests.size)
        assertSame(first.getValue("a"), again.getValue("a"))
        classifier.classify(listOf(docClaim("a", "Does something else.")))
        assertEquals(2, fake.requests.size)
        classifier.classify(listOf(docClaim("other", "Does a thing.")))
        assertEquals(3, fake.requests.size)
    }

    @Test fun injectedCacheIsUsed() = runBlocking {
        val claim = docClaim("a")
        val cache = InMemoryClaimClassCache()
        val preset = ClaimClass(detail = true, volatile = false, stability = 0.9, confidence = 1.0, decided = true)
        cache.put(ClaimClassifier.cacheKey(claim), preset)
        val fake = FakeClassifierDecisions()
        val result = ClaimClassifier(fake::decide, cache).classify(listOf(claim))
        assertSame(preset, result["a"])
        assertTrue(fake.requests.isEmpty())
        assertNotNull(result["a"])
    }
}
