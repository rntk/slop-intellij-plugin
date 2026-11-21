package dev.insight.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLinkParserTest {
    private val sampleFiles = listOf(
        "README.md",
        "Makefile",
        "LICENSE",
        "scripts/run",
        "src/core/llm/providers.js",
        "src/core/llm/clients.js",
        "src/core/llm/llm.js",
        "src/core/metrics/llm.js",
        "src/core/pipeline/topicTreeMerge.js",
        "src/core/pipeline/topicParser.js",
        "src/core/projects/projectTopicTree.js",
        "src/core/storage/chatStorage.js",
        "src/core/storage/storage.js",
        "src/content/selection/html.js",
        "src/canvas/html.js",
        "src/content/rails/in-page/controller.jsx",
        "src/content/rails/in-page/geometry.js",
        "src/content/rails/shared/helper.js",
        "src/content/rails/youtube/yt.js",
        "src/topics-tree/TopicsTreeCanvas.jsx",
        "src/options/ProjectsSection.jsx",
        "flows/code-review.md",
        "flows/summaries.md",
    )

    private fun index(
        files: List<String> = sampleFiles,
        projectRoot: String? = "/app",
        contentRoots: List<String> = listOf("/app"),
        reviewScopeFiles: Set<String> = emptySet(),
        reportFileDir: String? = null,
    ) = ReportProjectIndex(
        files = files,
        projectRoot = projectRoot,
        contentRoots = contentRoots,
        reviewScopeFiles = reviewScopeFiles,
        reportFileDir = reportFileDir,
    )

    @Test fun `parses exact project paths with colon, hash, and paren line specs`() {
        val collector = ReportLinkCollector(index())
        val line = "See src/core/pipeline/topicTreeMerge.js:L256-L337, " +
            "src/core/llm/clients.js:42, " +
            "src/core/llm/clients.js:42–58, " +
            "src/core/llm/providers.js:447:12, " +
            "src/core/llm/providers.js:10:2-15:8, " +
            "src/core/storage/storage.js#L145-L172, " +
            "src/core/storage/storage.js#L12C3-L18C9, " +
            "src/topics-tree/TopicsTreeCanvas.jsx(439) and " +
            "src/topics-tree/TopicsTreeCanvas.jsx(440, 15)."
        val matches = collector.scanSegment(line, inBackticks = false, allowContextualLines = true)
        assertEquals(9, matches.size)

        assertEquals("src/core/pipeline/topicTreeMerge.js", matches[0].link.primaryPath)
        assertEquals(256..337, matches[0].link.lines)

        assertEquals("src/core/llm/clients.js", matches[1].link.primaryPath)
        assertEquals(42..42, matches[1].link.lines)

        assertEquals(42..58, matches[2].link.lines)

        assertEquals("src/core/llm/providers.js", matches[3].link.primaryPath)
        assertEquals(447..447, matches[3].link.lines)
        assertEquals(12, matches[3].link.column)

        assertEquals(10..15, matches[4].link.lines)
        assertEquals(2, matches[4].link.column)

        assertEquals("src/core/storage/storage.js", matches[5].link.primaryPath)
        assertEquals(145..172, matches[5].link.lines)

        assertEquals(12..18, matches[6].link.lines)
        assertEquals(3, matches[6].link.column)

        assertEquals("src/topics-tree/TopicsTreeCanvas.jsx", matches[7].link.primaryPath)
        assertEquals(439..439, matches[7].link.lines)

        assertEquals(440..440, matches[8].link.lines)
        assertEquals(15, matches[8].link.column)
    }

    @Test fun `parses comma-separated continuation line ranges on one file path`() {
        val collector = ReportLinkCollector(index())
        val matches = collector.scanSegment(
            "src/core/metrics/llm.js:L178-L185, L202-L213, L217-L236, L449-L459",
            inBackticks = true,
            allowContextualLines = true,
        )
        assertEquals(4, matches.size)
        assertTrue(matches.all { it.link.primaryPath == "src/core/metrics/llm.js" })
        assertEquals(listOf(178..185, 202..213, 217..236, 449..459), matches.map { it.link.lines })
        assertEquals(
            listOf("src/core/metrics/llm.js:L178-L185", "L202-L213", "L217-L236", "L449-L459"),
            matches.map { it.link.displayText },
        )
    }

    @Test fun `resolves partial suffix paths and bare filenames`() {
        val collector = ReportLinkCollector(index())
        val matches = collector.scanSegment(
            "Check selection/html.js and topicTreeMerge.js plus TopicsTreeCanvas.jsx:L439-L441",
            inBackticks = false,
            allowContextualLines = true,
        )
        assertEquals(3, matches.size)
        assertEquals("src/content/selection/html.js", matches[0].link.primaryPath)
        assertEquals("src/core/pipeline/topicTreeMerge.js", matches[1].link.primaryPath)
        assertEquals("src/topics-tree/TopicsTreeCanvas.jsx", matches[2].link.primaryPath)
        assertEquals(439..441, matches[2].link.lines)
    }

    @Test fun `resolves local absolute, foreign absolute, file URI, git prefix, and relative paths`() {
        val collector = ReportLinkCollector(index(reportFileDir = "flows"))
        val text = "/app/src/core/llm/clients.js:10 " +
            "/home/other/checkout/src/core/pipeline/topicParser.js:20 " +
            "file:///app/src/core/llm/providers.js:L447 " +
            "b/src/core/storage/storage.js:L5 " +
            "summaries.md " +
            "../src/core/projects/projectTopicTree.js:L247 " +
            "src\\content\\rails\\in-page\\geometry.js:L65"
        val matches = collector.scanSegment(text, inBackticks = false, allowContextualLines = true)
        assertEquals(
            listOf(
                "src/core/llm/clients.js",
                "src/core/pipeline/topicParser.js",
                "src/core/llm/providers.js",
                "src/core/storage/storage.js",
                "flows/summaries.md",
                "src/core/projects/projectTopicTree.js",
                "src/content/rails/in-page/geometry.js",
            ),
            matches.map { it.link.primaryPath },
        )
        assertEquals(listOf(10..10, 20..20, 447..447, 5..5, null, 247..247, 65..65), matches.map { it.link.lines })
    }

    @Test fun `contextual line references bind to enclosing bullet, reset on sibling, and bind left-to-right`() {
        val report = """
            ### 1. `core/llm`, `core/metrics` (25 files)
            - [ ] **[CS-001]** `src/core/llm/providers.js:L447-L511` (`setTopicSplitter`)
              - **Issue:** `setTopicSplitter` (L447-L464, L483-L500) and line 471 (`L471-L475`).
              - **Suggestion:** See lines 470-476.
            - [ ] **[CS-002]** Check `L10-L20` here before file mention, then compare `clients.js` (L50) and `topicParser.js` (L99).
            ---
            After rule `L10` should not link.
        """.trimIndent()
        val rendered = renderMarkdownReport(report, index())
        val links = rendered.links

        // CS-001 main file + 4 contextual ranges in nested bullets
        val cs001Links = links.filter { it.primaryPath == "src/core/llm/providers.js" }
        assertEquals(listOf(447..511, 447..464, 483..500, 471..471, 471..475, 470..476), cs001Links.map { it.lines })
        assertTrue(cs001Links.drop(1).all { it.contextual })

        // Sibling CS-002 must NOT inherit providers.js for its leading L10-L20, and binds clients.js -> L50, topicParser.js -> L99
        val clientsIdx = links.indexOfFirst { it.primaryPath == "src/core/llm/clients.js" }
        assertEquals(50..50, links[clientsIdx + 1].lines)
        assertEquals("src/core/llm/clients.js", links[clientsIdx + 1].primaryPath)
        assertTrue(links[clientsIdx + 1].contextual)

        val parserIdx = links.indexOfFirst { it.primaryPath == "src/core/pipeline/topicParser.js" }
        assertEquals(99..99, links[parserIdx + 1].lines)
        assertEquals("src/core/pipeline/topicParser.js", links[parserIdx + 1].primaryPath)

        // After rule, L10 is NOT linked
        assertFalse(links.any { it.displayText == "L10" && it.lines == 10..10 })
    }

    @Test fun `table rows do not bleed contextual active file across rows`() {
        val md = """
            | Item | Detail |
            | --- | --- |
            | `src/core/llm/clients.js` | check L10 |
            | `L20` | unrelated row |
        """.trimIndent()
        val rendered = renderMarkdownReport(md, index())
        assertEquals(2, rendered.links.size)
        assertEquals("src/core/llm/clients.js", rendered.links[0].primaryPath)
        assertEquals("src/core/llm/clients.js", rendered.links[1].primaryPath)
        assertEquals(10..10, rendered.links[1].lines)
    }

    @Test fun `resolves directory paths and contextual subdirectories without linking bare prose words`() {
        val report = """
            ### 10. `content/rails`
            - Scope: `src/content/rails/` (`in-page/`, `shared/`, `youtube/`), plus `chat` and `state`.
        """.trimIndent()
        val rendered = renderMarkdownReport(report, index())
        val dirLinks = rendered.links.filter { it.kind == ReportTargetKind.DIRECTORY }
        assertEquals(
            listOf(
                "src/content/rails",
                "src/content/rails",
                "src/content/rails/in-page",
                "src/content/rails/shared",
                "src/content/rails/youtube",
            ),
            dirLinks.map { it.primaryPath },
        )
        // Bare words "chat" or "state" without slash must not be linked
        assertFalse(rendered.links.any { it.displayText == "chat" || it.displayText == "state" })
    }

    @Test fun `disambiguates duplicate filenames using section directory, prior mentions, and review scope`() {
        // html.js exists in src/content/selection/html.js and src/canvas/html.js
        val mdSection = """
            ### 8. `canvas`
            - Check `html.js:L34-L55`
        """.trimIndent()
        val resCanvas = renderMarkdownReport(mdSection, index())
        val htmlCanvas = resCanvas.links.first { it.displayText.startsWith("html.js") }
        assertFalse(htmlCanvas.isAmbiguous)
        assertEquals("src/canvas/html.js", htmlCanvas.primaryPath)

        // Disambiguation by review scope when no section dir is active
        val mdScope = "Check `html.js:12`."
        val resScope = renderMarkdownReport(mdScope, index(reviewScopeFiles = setOf("src/content/selection/html.js")))
        val htmlScope = resScope.links.single()
        assertFalse(htmlScope.isAmbiguous)
        assertEquals("src/content/selection/html.js", htmlScope.primaryPath)

        // When no signal breaks the tie, preserve ambiguity with ordered candidates
        val resAmbig = renderMarkdownReport("Check `html.js:12`.", index())
        val htmlAmbig = resAmbig.links.single()
        assertTrue(htmlAmbig.isAmbiguous)
        assertEquals(listOf("src/canvas/html.js", "src/content/selection/html.js"), htmlAmbig.candidates)

        // Duplicate basename llm.js (src/core/llm/llm.js and src/core/metrics/llm.js): section 1 mentions core/metrics
        val mdPrior = """
            - Full mention `src/core/metrics/llm.js:L350`
            - Later short ref `llm.js:L505`
        """.trimIndent()
        val resPrior = renderMarkdownReport(mdPrior, index())
        assertEquals("src/core/metrics/llm.js", resPrior.links[1].primaryPath)
        assertFalse(resPrior.links[1].isAmbiguous)
    }

    @Test fun `rejects code symbols, numbers, URLs, and fenced code contextual line refs`() {
        val md = """
            Numbers 1.5 and 6.4% and O(N * L) and Object.values and JSON.stringify should not link.
            URL https://example.com/src/core/llm/clients.js:10 links externally.
            ```js
            const L10 = values.filter();
            // explicit path still links: src/core/llm/clients.js:5
            ```
        """.trimIndent()
        val rendered = renderMarkdownReport(md, index())
        assertEquals(2, rendered.links.size)
        assertEquals(ReportTargetKind.EXTERNAL_URL, rendered.links[0].kind)
        assertEquals("https://example.com/src/core/llm/clients.js:10", rendered.links[0].externalUrl)
        assertEquals(ReportTargetKind.FILE, rendered.links[1].kind)
        assertEquals("src/core/llm/clients.js", rendered.links[1].primaryPath)
        assertEquals(5..5, rendered.links[1].lines)
    }

    @Test fun `extensionless files link only when exact backticked root or explicit markdown link`() {
        assertTrue(renderMarkdownReport("Check the license and run it", index()).links.isEmpty())
        assertTrue(renderMarkdownReport("Check `license` and `run` here.", index()).links.isEmpty())

        val backticked = renderMarkdownReport("Run target in `Makefile` and check `LICENSE`.", index())
        assertEquals(listOf("Makefile", "LICENSE"), backticked.links.map { it.primaryPath })

        val mdLink = renderMarkdownReport("Read [the license](LICENSE).", index())
        assertEquals(listOf("LICENSE"), mdLink.links.map { it.primaryPath })
    }

    @Test fun `realistic code-smells style audit report links paths, multi-ranges, dirs, and nested bullets accurately`() {
        val auditReport = """
            # Code-Smell Audit Log
            - **Scope:** Read-only audit of **338** files across `src/` (336 files) (`6.4%`, `188` findings).
            ---
            | # | Area | Files |
            | ---: | --- | ---: |
            | 1 | `core/llm`, `core/metrics` | 25 |
            | 10 | `content/rails` (`in-page/`, `shared/`, `youtube/`) | 21 |
            ---
            ## Top High-Impact Candidates
            1. **`[CS-016]`** `src/core/pipeline/topicTreeMerge.js:L256-L337` — Per-run `Set` rebuilds (`O(N^2 * depth)`).
            2. **`[CS-057]`** `src/core/storage/chatStorage.js:L465-L477, L592-L607` — Scans storage repeatedly.

            ### 1. `core/llm`, `core/metrics` (25 files)
            - [ ] **[CS-001]** `src/core/llm/providers.js:L447-L511` (`setTopicSplitter`)
              - **Issue:** `setTopicSplitter` (L447-L464, L483-L500) duplicates `getDecisionProvider` (L471-L475, L507-L511).
              - **Suggestion:** Refactor helpers.
            - [ ] **[CS-003]** `src/core/metrics/llm.js:L312-L325, L505-L518` (`normalizeLlmMetrics`)
              - **Issue:** Sanitization is copy-pasted.
        """.trimIndent()
        val rendered = renderMarkdownReport(auditReport, index())
        val dirs = rendered.links.filter { it.kind == ReportTargetKind.DIRECTORY }.map { it.primaryPath }
        assertTrue("src" in dirs)
        assertTrue("src/core/llm" in dirs)
        assertTrue("src/core/metrics" in dirs)
        assertTrue("src/content/rails" in dirs)
        assertTrue("src/content/rails/in-page" in dirs)
        assertTrue("src/content/rails/shared" in dirs)
        assertTrue("src/content/rails/youtube" in dirs)

        val storageLinks = rendered.links.filter { it.primaryPath == "src/core/storage/chatStorage.js" }
        assertEquals(listOf(465..477, 592..607), storageLinks.map { it.lines })

        val providerLinks = rendered.links.filter { it.primaryPath == "src/core/llm/providers.js" }
        assertEquals(listOf(447..511, 447..464, 483..500, 471..475, 507..511), providerLinks.map { it.lines })

        val metricsLinks = rendered.links.filter { it.primaryPath == "src/core/metrics/llm.js" }
        assertEquals(listOf(312..325, 505..518), metricsLinks.map { it.lines })
    }

    @Test fun `preserves root order and root identity for nested and separate content roots`() {
        val nestedIndex = ReportProjectIndex(
            files = listOf("src/Widget.kt", "src/OnlyTop.kt", "module/src/Widget.kt"),
            projectRoot = "/repo",
            contentRoots = listOf("/repo", "/repo/module"),
        )
        val nestedMd = """
            - Module: /repo/module/src/Widget.kt:L10
            - Top: /repo/src/Widget.kt:L5
            - Missing in module: /repo/module/src/OnlyTop.kt:L1
        """.trimIndent()
        val nestedRendered = renderMarkdownReport(nestedMd, nestedIndex)
        assertEquals(2, nestedRendered.links.size)
        assertEquals("module/src/Widget.kt", nestedRendered.links[0].primaryPath)
        assertEquals(10..10, nestedRendered.links[0].lines)
        assertEquals("src/Widget.kt", nestedRendered.links[1].primaryPath)
        assertEquals(5..5, nestedRendered.links[1].lines)

        val siblingIndex = ReportProjectIndex(
            projectRoot = null,
            contentRoots = listOf("/workspace/app", "/workspace/lib"),
            fileEntries = listOf(
                ReportIndexedEntry("src/Widget.kt", "/workspace/app/src/Widget.kt", inReviewScope = false),
                ReportIndexedEntry("src/Widget.kt", "/workspace/lib/src/Widget.kt", inReviewScope = false),
            ),
        )
        val siblingMd = """
            - App abs: /workspace/app/src/Widget.kt:L1
            - Lib abs: /workspace/lib/src/Widget.kt:L2
            - Shared rel: src/Widget.kt:L3
        """.trimIndent()
        val siblingRendered = renderMarkdownReport(siblingMd, siblingIndex)
        assertEquals(3, siblingRendered.links.size)
        assertFalse(siblingRendered.links[0].isAmbiguous)
        assertEquals("app/src/Widget.kt", siblingRendered.links[0].primaryPath)
        assertEquals("/workspace/app/src/Widget.kt", siblingIndex.absolutePathFor("app/src/Widget.kt"))

        assertFalse(siblingRendered.links[1].isAmbiguous)
        assertEquals("lib/src/Widget.kt", siblingRendered.links[1].primaryPath)
        assertEquals("/workspace/lib/src/Widget.kt", siblingIndex.absolutePathFor("lib/src/Widget.kt"))

        assertTrue(siblingRendered.links[2].isAmbiguous)
        assertEquals(listOf("app/src/Widget.kt", "lib/src/Widget.kt"), siblingRendered.links[2].candidates)

        // When one sibling root is in review scope, relative lookup prefers it without colliding with the other root.
        val scopedSiblingIndex = ReportProjectIndex(
            projectRoot = null,
            contentRoots = listOf("/workspace/app", "/workspace/lib"),
            fileEntries = listOf(
                ReportIndexedEntry("src/Widget.kt", "/workspace/app/src/Widget.kt", inReviewScope = false),
                ReportIndexedEntry("src/Widget.kt", "/workspace/lib/src/Widget.kt", inReviewScope = true),
            ),
        )
        val scopedRel = renderMarkdownReport("src/Widget.kt:L3", scopedSiblingIndex).links.single()
        assertFalse(scopedRel.isAmbiguous)
        assertEquals("lib/src/Widget.kt", scopedRel.primaryPath)

        val sameBasenameIndex = ReportProjectIndex(
            projectRoot = null,
            contentRoots = listOf("/a/core", "/b/core"),
            fileEntries = listOf(
                ReportIndexedEntry("src/Widget.kt", "/a/core/src/Widget.kt"),
                ReportIndexedEntry("src/Widget.kt", "/b/core/src/Widget.kt"),
            ),
        )
        val sameBaseRendered = renderMarkdownReport("/a/core/src/Widget.kt:L7 and /b/core/src/Widget.kt:L9", sameBasenameIndex)
        assertEquals(listOf("a/core/src/Widget.kt", "b/core/src/Widget.kt"), sameBaseRendered.links.map { it.primaryPath })
        assertEquals("/a/core/src/Widget.kt", sameBasenameIndex.absolutePathFor("a/core/src/Widget.kt"))
        assertEquals("/b/core/src/Widget.kt", sameBasenameIndex.absolutePathFor("b/core/src/Widget.kt"))
    }

    @Test fun `contextual line links carry ambiguous candidates instead of inheriting unrelated prior file`() {
        val md = """
            See `src/core/llm/providers.js:L10`, then compare `html.js` around `L42` and lines 50-52.
            - `providers.js:L5`
            - Ambiguous item `html.js`:
              - nested `L99`
            - Sibling item `L77`
        """.trimIndent()
        val rendered = renderMarkdownReport(md, index())
        val l42 = rendered.links.first { it.displayText == "L42" }
        assertTrue(l42.contextual)
        assertTrue(l42.isAmbiguous)
        assertEquals(listOf("src/canvas/html.js", "src/content/selection/html.js"), l42.candidates)

        val lines50 = rendered.links.first { it.displayText == "lines 50-52" }
        assertEquals(listOf("src/canvas/html.js", "src/content/selection/html.js"), lines50.candidates)

        val nestedL99 = rendered.links.first { it.displayText == "L99" }
        assertEquals(listOf("src/canvas/html.js", "src/content/selection/html.js"), nestedL99.candidates)

        // Sibling bullet resets active context so L77 is not linked.
        assertFalse(rendered.links.any { it.displayText == "L77" })
    }

    @Test fun `decodes percent-encoded relative and file URI markdown links`() {
        val encodedIndex = ReportProjectIndex(
            files = listOf(
                "src/My File.kt",
                "src/my dir/Nested.kt",
                "src/café.kt",
                "src/c++ notes.md",
                "src/a%20b.kt",
            ),
            projectRoot = "/app",
        )
        val md = """
            - [space](src/My%20File.kt#L12)
            - [dir](src/my%20dir/)
            - [utf8](src/caf%C3%A9.kt:L3)
            - [plus](src/c++%20notes.md)
            - [literal](file:///app/src/a%2520b.kt:4)
        """.trimIndent()
        val rendered = renderMarkdownReport(md, encodedIndex)
        assertEquals(5, rendered.links.size)

        assertEquals(ReportTargetKind.FILE, rendered.links[0].kind)
        assertEquals("src/My File.kt", rendered.links[0].primaryPath)
        assertEquals(12..12, rendered.links[0].lines)

        assertEquals(ReportTargetKind.DIRECTORY, rendered.links[1].kind)
        assertEquals("src/my dir", rendered.links[1].primaryPath)

        assertEquals("src/café.kt", rendered.links[2].primaryPath)
        assertEquals(3..3, rendered.links[2].lines)

        assertEquals("src/c++ notes.md", rendered.links[3].primaryPath)

        assertEquals("src/a%20b.kt", rendered.links[4].primaryPath)
        assertEquals(4..4, rendered.links[4].lines)
    }
}
