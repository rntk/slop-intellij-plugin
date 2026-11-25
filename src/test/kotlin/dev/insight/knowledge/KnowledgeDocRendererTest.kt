package dev.insight.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeDocRendererTest {
    private val target = DocTarget("Diff review", "src/app")

    private fun renderer(
        complete: FakeCompletion? = null,
        decisions: FakeClassifierDecisions? = null,
        limits: DocRenderLimits = DocRenderLimits(),
    ) = KnowledgeDocRenderer(complete?.let { it::complete }, ClaimClassifier(decisions?.let { it::decide }), limits)

    private val sharedAnchors = listOf(Anchor(AnchorKind.FILE, "src/app/Panel.kt"), Anchor(AnchorKind.DIRECTORY, "src/app"))

    private fun threeClaims() = stateOf(
        docClaim("c1", "Reviewers explore a comparison as a read-only workspace", anchors = sharedAnchors),
        docClaim("c2", "Notes and generated views sit alongside the source.", level = ClaimLevel.MODULE, anchors = sharedAnchors.take(1)),
        docClaim("c3", "The graph relates changes to surrounding calls", anchors = listOf(Anchor(AnchorKind.FILE, "src/graph/Service.kt"))),
    )

    @Test fun deterministicOutputMatchesFlowsStyle() = runBlocking {
        val doc = renderer().render(threeClaims(), target)
        assertEquals("flows/diff-review.md", doc.path)
        val text = doc.markdown.replace(Regex(" <!--.*?-->"), "")
        val lines = text.lines()
        assertEquals("# Diff review flow", lines.first())
        assertEquals("", lines[1])
        assertTrue(lines[2].startsWith("Reviewers explore a comparison as a read-only workspace."))
        assertTrue(text.contains("\nEntry points for further exploration:\n\n- [Panel.kt](../src/app/Panel.kt)"))
        assertTrue(doc.markdown.contains("<!-- c1 c3 -->") || doc.markdown.contains("<!-- c3 c1 -->") || doc.markdown.contains("<!-- c1 "))
        assertEquals(setOf("c1", "c2", "c3"), doc.usedClaimIds.toSet())
        assertTrue(doc.unplaced.isEmpty())
        assertFalse(doc.markdown.contains("Not placed"))
        assertTrue(doc.markdown.endsWith("\n"))
    }

    @Test fun citationsCanBeOmitted() = runBlocking {
        val doc = renderer(limits = DocRenderLimits(embedCitations = false)).render(threeClaims(), target)
        assertFalse(doc.markdown.contains("<!--"))
    }

    @Test fun titleAlreadyEndingInFlowIsNotDuplicated() = runBlocking {
        val doc = renderer().render(threeClaims(), DocTarget("Diff flow", "src/app", existingPath = "docs/guide/diff.md"))
        assertEquals("# Diff flow", doc.markdown.lines().first())
        assertEquals("docs/guide/diff.md", doc.path)
        assertTrue(doc.markdown.contains("](../../src/app/Panel.kt)"))
    }

    @Test fun groupsByAreaAndCapsParagraphs() = runBlocking {
        val state = stateOf(
            docClaim("a1", "Alpha part.", area = "src/a/x"),
            docClaim("b1", "Beta part.", area = "src/a/y"),
            docClaim("c1", "Gamma part.", area = "src/a/z"),
        )
        val paragraphs = { limits: DocRenderLimits ->
            runBlocking { renderer(limits = limits).render(state, DocTarget("T", "src/a")) }
                .markdown.lines().filter { it.endsWith("-->") }
        }
        assertEquals(3, paragraphs(DocRenderLimits()).size)
        val capped = paragraphs(DocRenderLimits(maxParagraphs = 2))
        assertEquals(2, capped.size)
        assertTrue(capped.last().contains("Beta part. Gamma part."))
    }

    @Test fun onlyClaimsInTheTargetAreasAreUsed() = runBlocking {
        val state = stateOf(docClaim("in", area = "src/app/sub"), docClaim("out", area = "src/other"), docClaim("near", area = "src/application"))
        val doc = renderer().render(state, DocTarget("T", "src/app"))
        assertEquals(listOf("in"), doc.usedClaimIds)
        val both = renderer().render(state, DocTarget("T", setOf("src/app", "src/other")))
        assertEquals(setOf("in", "out"), both.usedClaimIds.toSet())
        val root = renderer().render(state, DocTarget("T", ""))
        assertEquals(3, root.usedClaimIds.size)
    }

    @Test fun detailAndVolatileClaimsAreExcludedWithReason() = runBlocking {
        val state = stateOf(
            docClaim("ok", "Readers browse the results."),
            docClaim("det", "Uses a private helper.", tags = setOf("detail")),
            docClaim("vol", "Waits 30 seconds before retrying."),
        )
        val doc = renderer().render(state, target)
        assertEquals(listOf("ok"), doc.usedClaimIds)
        assertEquals(
            setOf(ExcludedClaim("det", ExclusionReason.DETAIL), ExcludedClaim("vol", ExclusionReason.VOLATILE)),
            doc.excluded.toSet())
        assertFalse(doc.markdown.contains("private helper"))
        assertFalse(doc.markdown.contains("30 seconds"))
    }

    @Test fun decisionsDriveExclusion() = runBlocking {
        val decisions = FakeClassifierDecisions(detail = setOf("c2"))
        val doc = renderer(decisions = decisions).render(threeClaims(), target)
        assertEquals(listOf(ExcludedClaim("c2", ExclusionReason.DETAIL)), doc.excluded)
        assertEquals(setOf("c1", "c3"), doc.usedClaimIds.toSet())
    }

    @Test fun prefersGeneralLevelsAndRecordsOverLimitClaims() = runBlocking {
        val state = stateOf(
            docClaim("sym", "Symbol note.", level = ClaimLevel.FILE),
            docClaim("mod", "Module note.", level = ClaimLevel.MODULE),
            docClaim("flow", "Flow note.", level = ClaimLevel.FLOW),
        )
        val doc = renderer(limits = DocRenderLimits(maxClaims = 2)).render(state, target)
        assertEquals(setOf("flow", "mod"), doc.usedClaimIds.toSet())
        assertEquals(listOf(ExcludedClaim("sym", ExclusionReason.OVER_LIMIT)), doc.excluded)
    }

    @Test fun entryPointsFollowAnchorCentralityAndLimit() = runBlocking {
        fun file(ref: String) = listOf(Anchor(AnchorKind.FILE, ref))
        val state = stateOf(
            docClaim("a", "A.", anchors = file("src/app/Hub.kt") + file("src/app/Rare.kt")),
            docClaim("b", "B.", anchors = file("src/app/Hub.kt") + Anchor(AnchorKind.DIRECTORY, "src/app/ui")),
            docClaim("c", "C.", anchors = file("src/app/Hub.kt") + Anchor(AnchorKind.DIRECTORY, "src/app/ui") + Anchor(AnchorKind.SYMBOL, "a.B")),
            docClaim("d", "D.", anchors = file("/etc/passwd") + file("../outside.kt")),
        )
        val doc = renderer(limits = DocRenderLimits(entryPoints = 2)).render(state, target)
        val links = doc.markdown.lines().filter { it.startsWith("- [") }
        assertEquals(listOf("- [Hub.kt](../src/app/Hub.kt)", "- [ui/](../src/app/ui)"), links)
    }

    @Test fun relativeLinksAreComputedFromTheDocLocation() {
        assertEquals("../src/a/B.kt", KnowledgeDocRenderer.relativeLink("flows/x.md", "src/a/B.kt"))
        assertEquals("B.kt", KnowledgeDocRenderer.relativeLink("src/a/x.md", "src/a/B.kt"))
        assertEquals("../c/D.kt", KnowledgeDocRenderer.relativeLink("src/a/x.md", "src/c/D.kt"))
        assertEquals("src/a/B.kt", KnowledgeDocRenderer.relativeLink("README.md", "src/a/B.kt"))
    }

    // ---- LLM path ----

    @Test fun modelParagraphsWithValidIdsAreUsed() = runBlocking {
        val completion = FakeCompletion {
            """```json
            {"paragraphs":[{"text":"Reviewers explore changes and add notes","claimIds":["c1","c2"]},
                           {"text":"A graph relates changes to calls.","claimIds":["c3"]}],
             "entryPoints":[{"path":"src/app/Panel.kt","description":"comparison workspace."},{"path":"invented/File.kt","description":"x"}]}
            ```"""
        }
        val doc = renderer(complete = completion).render(threeClaims(), target)
        assertEquals(1, completion.requests.size)
        assertTrue(completion.requests.single().messages.last().content.contains("[c3]"))
        assertTrue(doc.markdown.contains("Reviewers explore changes and add notes. <!-- c1 c2 -->"))
        assertTrue(doc.markdown.contains("A graph relates changes to calls. <!-- c3 -->"))
        assertTrue(doc.markdown.contains("- [Panel.kt](../src/app/Panel.kt) — comparison workspace"))
        assertFalse(doc.markdown.contains("invented"))
        assertTrue(doc.unplaced.isEmpty())
        assertTrue(doc.warnings.isEmpty())
    }

    @Test fun unknownIdsAreDroppedAndCoverageRechecked() = runBlocking {
        val completion = FakeCompletion {
            """{"paragraphs":[{"text":"Made up facts.","claimIds":["zzz"]},
                              {"text":"Real facts.","claimIds":["c1","nope"]}]}"""
        }
        val doc = renderer(complete = completion).render(threeClaims(), target)
        assertFalse(doc.markdown.contains("Made up facts"))
        assertTrue(doc.markdown.contains("Real facts. <!-- c1 -->"))
        assertEquals(listOf("c1"), doc.usedClaimIds)
        assertEquals(listOf("c2", "c3"), doc.unplaced.sorted())
        assertTrue(doc.warnings.any { "'zzz'" in it })
        assertTrue(doc.warnings.any { "'nope'" in it })
        assertTrue(doc.warnings.any { "without valid citations" in it })
    }

    @Test fun uncitedClaimLandsInNotPlacedSection() = runBlocking {
        val completion = FakeCompletion { """{"paragraphs":[{"text":"Only some.","claimIds":["c1","c2"]}]}""" }
        val doc = renderer(complete = completion).render(threeClaims(), target)
        assertEquals(listOf("c3"), doc.unplaced)
        val tail = doc.markdown.substringAfter("## Not placed\n")
        assertEquals("\n- The graph relates changes to surrounding calls\n", tail)
        assertTrue(doc.markdown.trimEnd().endsWith("surrounding calls"))
        assertFalse(tail.contains("<!--"))
    }

    @Test fun citedExcludedClaimsAreDropped() = runBlocking {
        val state = stateOf(docClaim("ok", "Fine."), docClaim("det", "Detail.", tags = setOf("detail")))
        val completion = FakeCompletion { """{"paragraphs":[{"text":"Both.","claimIds":["ok","det"]}]}""" }
        val doc = renderer(complete = completion).render(state, target)
        assertEquals(listOf("ok"), doc.usedClaimIds)
        assertTrue(doc.markdown.contains("<!-- ok -->"))
        assertTrue(doc.warnings.any { "'det'" in it })
    }

    @Test fun markdownReplyWithCommentCitationsIsUnderstood() = runBlocking {
        val completion = FakeCompletion {
            "# Whatever\n\nReviewers explore changes. <!-- c1 -->\n\nGraph links. <!-- c3, bogus -->\n\nEntry points for further exploration:\n\n- [x](y)"
        }
        val doc = renderer(complete = completion).render(threeClaims(), target)
        assertEquals(setOf("c1", "c3"), doc.usedClaimIds.toSet())
        assertEquals(listOf("c2"), doc.unplaced)
        assertEquals(1, doc.markdown.lines().count { it.startsWith("# ") })
        assertTrue(doc.warnings.any { "not JSON" in it })
        assertTrue(doc.warnings.any { "'bogus'" in it })
    }

    @Test fun modelFailureOrGarbageFallsBackToDeterministicRendering() = runBlocking {
        val failing = renderer(complete = FakeCompletion { error("boom") }).render(threeClaims(), target)
        assertEquals(3, failing.usedClaimIds.size)
        assertTrue(failing.warnings.any { "boom" in it })
        val garbage = renderer(complete = FakeCompletion { "{\"paragraphs\":[]}" }).render(threeClaims(), target)
        assertEquals(3, garbage.usedClaimIds.size)
        assertTrue(garbage.warnings.any { "deterministic" in it })
    }

    @Test fun noClaimsGivesTitleOnlyWithWarning() = runBlocking {
        val completion = FakeCompletion { error("must not be called") }
        val doc = renderer(complete = completion).render(KnowledgeState(), target)
        assertEquals("# Diff review flow\n", doc.markdown)
        assertTrue(doc.warnings.single().startsWith("No claims selected"))
        assertTrue(completion.requests.isEmpty())
    }
}
