package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.callgraph.LineChange
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiffReviewTest {
    @Test fun `default repository follows active file then project and respects nested root boundaries`() {
        val repositories = listOf("/other", "/work/project", "/work/project/nested").map { ReviewRepository(it, listOf("HEAD")) }
        assertEquals("/work/project/nested", preferredReviewRepository(repositories, "/work/project/nested/src/a.kt", "/work/project"))
        assertEquals("/work/project", preferredReviewRepository(repositories, "/unrelated/a.kt", "/work/project/subdir"))
        assertEquals("/other", preferredReviewRepository(repositories, "/work/project-other/a.kt", null))
        assertEquals("/work/project", preferredReviewRepository(repositories, null, "/work/project"))
        assertEquals("/other", preferredReviewRepository(repositories, null, null))
        assertNull(preferredReviewRepository(emptyList(), "/work/project/a.kt", "/work/project"))
    }

    @Test fun `request changes invalidate captured revisions and all canvas evidence even for identical files`() {
        val state = state()
        val path = "src/a.kt"
        state.update(state.snapshot.copy(files = state.snapshot.files.map { it.copy(before = "old\n", after = "new\n") }))
        state.addNote(state.revision, listOf(path), "Local evidence")
        state.showSource(state.revision, DiffSourceRange(path, 1, 1))
        state.showDiff(path)
        val previous = state.revision
        val comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "main", "topic"), "a".repeat(40), "b".repeat(40))
        state.update(state.snapshot.copy(comparison = comparison))
        assertEquals(previous + 1, state.revision)
        assertTrue(state.notes.isEmpty())
        assertTrue(state.sources.isEmpty())
        assertTrue(state.diffs.isEmpty())
        rejected { state.check(previous) }
        state.addNote(state.revision, listOf(path), "Historical evidence")
        state.showSource(state.revision, DiffSourceRange(path, 1, 1))
        state.showDiff(path)
        val current = state.revision
        state.update(state.snapshot)
        assertEquals(current, state.revision)
        assertEquals(1, state.notes.size)
        // Explicitly choosing a different base clears evidence even when resolved IDs and files match.
        state.update(state.snapshot.copy(comparison = comparison.copy(request = comparison.request.copy(base = "release"))))
        assertTrue(state.notes.isEmpty())
        assertTrue(state.sources.isEmpty())
        assertTrue(state.diffs.isEmpty())
        rejected { state.check(current) }
    }

    @Test fun `moving refs preserve unchanged cards across all and scoped tabs while expiring changed and removed evidence`() {
        for (mode in listOf(ReviewDiffMode.WORKING_TREE, ReviewDiffMode.REVISIONS, ReviewDiffMode.MERGE_BASE)) {
            val comparison = ReviewComparison(ReviewDiffRequest(mode, "/repo", "HEAD", "topic"), "old-base", "old-target")
            val files = listOf("a.kt", "b.kt", "removed.kt").map { path ->
                file(path).copy(before = "old\n", after = "new\n", beforeLabel = comparison.beforeLabel, afterLabel = comparison.afterLabel)
            }
            val initial = ReviewSnapshot(files, emptyList(), comparison)
            val moved = comparison.copy(baseRevision = "new-base", targetRevision = "new-target")
            val unchanged = files[0].copy(beforeLabel = moved.beforeLabel, afterLabel = moved.afterLabel)
            val changed = files[1].copy(before = "committed\n", fingerprint = "changed")
            val next = initial.copy(files = listOf(unchanged, changed), comparison = moved)
            val states = listOf(DiffReviewState(), DiffReviewState(setOf("a.kt")))
            for (state in states) {
                state.update(initial)
                state.snapshot.files.forEach { file ->
                    state.addNote(state.revision, listOf(file.path), "Note for ${file.path}")
                    state.showSource(state.revision, DiffSourceRange(file.path, 1, 1))
                    state.showDiff(file.path)
                }
                if (state.scopePaths == null) state.addNote(state.revision, listOf("a.kt", "b.kt"), "Group note")
                val retainedNote = state.notes.first()
                val retainedSource = state.sources.first()
                val previous = state.revision
                state.update(next)
                assertEquals(listOf(retainedNote), state.notes)
                assertSame(retainedNote, state.notes.single())
                assertEquals(listOf(retainedSource), state.sources)
                assertEquals(listOf(unchanged), state.diffs)
                assertEquals(moved, state.snapshot.comparison)
                assertEquals(previous + 1, state.revision)
                rejected { state.check(previous) }
            }
        }
    }

    @Test fun `diff context identifies the selected repository refs and immutable commit ids`() {
        val comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.MERGE_BASE, "/repo", "main", "feature"), "base-id", "target-id")
        val snapshot = ReviewSnapshot(emptyList(), emptyList(), comparison)
        val json = JsonParser.parseString(diffStateResult(snapshot, setOf("a"), emptyList(), emptyList(), 0, 0)).asJsonObject
        val source = json["comparison"].asJsonObject
        assertEquals("base-id", source["baseRevision"].asString)
        assertEquals("target-id", source["targetRevision"].asString)
        val request = source["request"].asJsonObject
        assertEquals("MERGE_BASE", request["mode"].asString)
        assertEquals("/repo", request["repository"].asString)
        assertEquals("main", request["base"].asString)
        assertEquals("feature", request["target"].asString)
    }

    @Test fun `diff request validation preserves revision expressions and rejects incomplete inputs`() {
        assertEquals(ReviewDiffRequest(), ReviewDiffRequest(repository = "/ignored", base = "").validated())
        val request = ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", " HEAD~3 ", " v1.0 ").validated()
        assertEquals("HEAD~3", request.base)
        assertEquals("v1.0", request.target)
        for (invalid in listOf(request.copy(repository = null), request.copy(base = " "), request.copy(target = ""), request.copy(base = "a\nb"))) {
            try { invalid.validated(); fail("Expected invalid request") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("HEAD", request.copy(mode = ReviewDiffMode.WORKING_TREE, target = "").validated().target)
    }

    @Test fun `file list pages preserve long escaped rename paths and selection within the wire budget`() {
        val files = (1..55).map { index -> file("src/$index-" + "\"\\\t".repeat(180)).copy(
            previousPath = "old/$index-" + "\"\\\t".repeat(180), status = "renamed",
        ) }
        val snapshot = ReviewSnapshot(files, emptyList())
        val selected = files.map { it.path }
        val discovered = mutableListOf<String>()
        var offset = 0
        var calls = 0
        do {
            assertTrue("Pagination must make progress", ++calls <= files.size)
            val response = diffStateResult(snapshot, selected.toSet(), selected, emptyList(), offset, 0)
            assertTrue(response.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
            assertEquals(response, diffStateResult(snapshot, selected.toSet(), selected, emptyList(), offset, 0))
            val json = JsonParser.parseString(response).asJsonObject
            assertEquals("fixed file selection", json["scope"].asString)
            assertEquals(files.size, json["total_files"].asInt)
            assertEquals(selected.size, json["selected_count"].asInt)
            val page = json["files"].asJsonArray.map { it.asJsonObject }
            assertTrue(page.isNotEmpty() && page.size < DIFF_PAGE_SIZE)
            page.forEachIndexed { index, entry ->
                val original = files[offset + index]
                assertEquals(original.path, entry["path"].asString)
                assertEquals(original.previousPath, entry["previous_path"].asString)
                assertEquals("renamed", entry["status"].asString)
                assertTrue(entry["selected"].asBoolean)
                discovered += entry["path"].asString
            }
            val next = json["next_offset"]?.asInt
            if (next != null) assertEquals(offset + page.size, next)
            else assertEquals(files.size, offset + page.size)
            offset = next ?: files.size
        } while (offset < files.size)
        assertEquals(selected, discovered)
        val end = JsonParser.parseString(diffStateResult(snapshot, null, selected, emptyList(), files.size, 0)).asJsonObject
        assertTrue(end["files"].asJsonArray.isEmpty)
        assertFalse(end.has("next_offset"))
        rejected { diffStateResult(snapshot, null, selected, emptyList(), -1, 0) }
        rejected { diffStateResult(snapshot, null, selected, emptyList(), files.size + 1, 0) }
        rejected { diffStateResult(ReviewSnapshot(listOf(file("x".repeat(16_000))), emptyList()), null, emptyList(), emptyList(), 0, 0) }
    }

    @Test fun `inline patches account for JSON escaping and leave deferred patches readable`() {
        val files = listOf(file("escaped").copy(patch = "\u0001".repeat(3000)), file("small"), file("empty").copy(patch = ""))
        val snapshot = ReviewSnapshot(files, emptyList())
        val note = DiffNote(files.map { it.path }, "Quotes \" and tabs\t")
        val response = diffStateResult(snapshot, null, emptyList(), listOf(note), 0, 24_000)
        assertTrue(response.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
        val json = JsonParser.parseString(response).asJsonObject
        val excerpt = json["notes"].asJsonArray.single().asJsonObject
        assertEquals(1, json["notes_count"].asInt)
        assertEquals(note.paths, excerpt["paths"].asJsonArray.map { it.asString })
        assertFalse(excerpt.has("title"))
        assertEquals(note.text, excerpt["text_excerpt"].asString)
        val page = json["files"].asJsonArray.map { it.asJsonObject }
        assertTrue(page.all { it["has_note"].asBoolean })
        assertEquals(files[0].patch.length, page[0]["patch_characters"].asInt)
        assertFalse(page[0].has("patch"))
        assertEquals(files[1].patch, page[1]["patch"].asString)
        assertEquals("", page[2]["patch"].asString)
        assertFalse(page[1].has("patch_characters"))
        val read = JsonParser.parseString(diffReadResult(listOf(files[0]), 0)).asJsonObject["files"].asJsonArray.single().asJsonObject
        assertTrue(read["patch"].asString.isNotEmpty())
        assertTrue(read.has("next_offset"))
        val empty = JsonParser.parseString(diffStateResult(ReviewSnapshot(emptyList(), emptyList()), null, emptyList(), emptyList(), 0, 0)).asJsonObject
        assertTrue(empty["files"].asJsonArray.isEmpty)
        assertFalse(empty.has("next_offset"))
    }

    @Test fun `serialized read budget preserves all evidence and pagination with escaped content`() {
        val files = (1..10).map { index -> file("src/$index.csv").copy(
            patch = "\"quoted\",\\path\t\u0001\n".repeat(1000), previousPath = "old/\"$index.csv", status = "renamed",
        ) }
        val assembled = files.associate { it.path to StringBuilder() }
        val pending = ArrayDeque<Pair<List<ReviewFile>, Int>>()
        pending.add(files to 0)
        var calls = 0
        var deferred = false
        while (pending.isNotEmpty()) {
            assertTrue("Pagination must make progress", ++calls < 100)
            val (requested, offset) = pending.removeFirst()
            val response = diffReadResult(requested, offset)
            assertTrue(response.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
            val json = JsonParser.parseString(response).asJsonObject
            val slices = json["files"].asJsonArray.map { it.asJsonObject }
            val notRead = json["not_read"]?.asJsonArray?.map { it.asString }.orEmpty()
            assertEquals(requested.map { it.path }.toSet(), (slices.map { it["path"].asString } + notRead).toSet())
            for (slice in slices) {
                val path = slice["path"].asString
                val original = files.single { it.path == path }
                assertEquals(original.previousPath, slice["previous_path"].asString)
                assertEquals("renamed", slice["status"].asString)
                val received = assembled.getValue(path)
                assertEquals(received.length, slice["offset"]?.asInt ?: 0)
                received.append(slice["patch"].asString)
                slice["next_offset"]?.let {
                    assertTrue(it.asInt > offset)
                    assertEquals(received.length, it.asInt)
                    pending.add(listOf(original) to it.asInt)
                }
            }
            if (notRead.isNotEmpty()) {
                deferred = true
                pending.add(files.filter { it.path in notRead } to 0)
            }
        }
        assertTrue("Exercise not_read metadata as well as next_offset", deferred)
        files.forEach { assertEquals(it.patch, assembled.getValue(it.path).toString()) }
    }

    @Test fun `serialized read budget includes large rename metadata and handles empty patches`() {
        val renamed = file("long/" + "a".repeat(2000)).copy(previousPath = "old/" + "b".repeat(2000), patch = "\t".repeat(6000))
        val response = diffReadResult(listOf(renamed, file("empty").copy(patch = "")), 0)
        assertTrue(response.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
        val json = JsonParser.parseString(response).asJsonObject
        val first = json["files"].asJsonArray.first().asJsonObject
        assertEquals(renamed.previousPath, first["previous_path"].asString)
        assertEquals(first["patch"].asString.length, first["next_offset"].asInt)
        val empty = JsonParser.parseString(diffReadResult(listOf(file("empty").copy(patch = "")), 0)).asJsonObject
        assertEquals("", empty["files"].asJsonArray.single().asJsonObject["patch"].asString)
        rejected { diffReadResult(listOf(file("x".repeat(16_000))), 0) }
    }

    private fun file(path: String, version: String = "1", status: String = "modified") =
        ReviewFile(path, "/project/$path", null, status, "-old\n+new\n", version)

    private fun state() = DiffReviewState().apply {
        update(ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt")), listOf(UntrackedFile("new.kt", "/project/new.kt"))))
    }

    @Test fun `notes support files and groups and replace the same group regardless of order`() {
        val state = state()
        state.addNote(state.revision, listOf("src/a.kt"), "Adds input checks.")
        state.addNote(state.revision, listOf("src/a.kt", "src/b.kt"), "Adds the feature.")
        state.addNote(state.revision, listOf("src/b.kt", "src/a.kt", "src/a.kt"), "Also tests it.")
        assertEquals(2, state.notes.size)
        assertEquals("Also tests it.", state.notes.last().text)
        state.removeNote(listOf("src/b.kt", "src/a.kt"))
        assertEquals(1, state.notes.size)
    }

    @Test fun `refresh invalidates changed groups but preserves notes for unchanged files`() {
        val state = state()
        val revision = state.revision
        state.addNote(revision, listOf("src/a.kt"), "A note")
        state.addNote(revision, listOf("src/b.kt"), "B note")
        state.addNote(revision, listOf("src/a.kt", "src/b.kt"), "Group note")
        state.update(state.snapshot.copy(files = listOf(file("src/a.kt", "2"), file("src/b.kt"))))
        assertEquals(revision + 1, state.revision)
        assertEquals(listOf("B note"), state.notes.map { it.text })
        val refreshed = state.revision
        state.update(state.snapshot)
        assertEquals(refreshed, state.revision)
        state.update(state.snapshot.copy(files = emptyList()))
        assertTrue(state.notes.isEmpty())
    }

    @Test fun `untracked files cannot be annotated until included and exclusion removes their notes`() {
        val state = state()
        rejected { state.addNote(state.revision, listOf("src/a.kt", "new.kt"), "Not included") }
        assertTrue(state.notes.isEmpty())
        state.update(state.snapshot.copy(files = state.snapshot.files + file("new.kt", status = "untracked")))
        state.addNote(state.revision, listOf("new.kt"), "New functionality")
        state.update(state.snapshot.copy(files = state.snapshot.files.filter { it.path != "new.kt" }))
        assertTrue(state.notes.isEmpty())
    }

    @Test fun `stale and refreshing mutations fail atomically`() {
        val state = state()
        rejected { state.addNote(0, listOf("src/a.kt"), "A note") }
        rejected { state.addNote(state.revision, listOf("src/a.kt", "missing"), "A note") }
        state.refreshing = true
        rejected { state.addNote(state.revision, listOf("src/a.kt"), "A note") }
        assertTrue(state.notes.isEmpty())
    }

    @Test fun `changes to excluded untracked list do not invalidate included file evidence`() {
        val state = state()
        val revision = state.revision
        state.update(state.snapshot.copy(untracked = emptyList()))
        assertEquals(revision, state.revision)
        assertTrue(state.snapshot.untracked.isEmpty())
        state.addNote(revision, listOf("src/a.kt"), "Still current")
    }

    @Test fun `tab scopes filter evidence and reject notes for files outside the scope`() {
        val paths = mutableSetOf("src/a.kt")
        val scoped = DiffReviewState(paths)
        paths += "src/b.kt"
        scoped.update(state().snapshot)
        assertEquals(listOf("src/a.kt"), scoped.snapshot.files.map { it.path })
        scoped.addNote(scoped.revision, listOf("src/a.kt"), "Scoped note")
        rejected { scoped.addNote(scoped.revision, listOf("src/b.kt"), "Outside scope") }
        rejected { scoped.addNote(scoped.revision, listOf("src/a.kt", "src/b.kt"), "Outside scope") }
        assertEquals(1, scoped.notes.size)
    }

    @Test fun `tabs keep independent notes and only scoped evidence affects their revisions`() {
        val all = state()
        val scoped = DiffReviewState(setOf("src/a.kt")).apply { update(all.snapshot) }
        all.addNote(all.revision, listOf("src/a.kt"), "First topic")
        scoped.addNote(scoped.revision, listOf("src/a.kt"), "Second topic")
        val revision = scoped.revision
        val next = all.snapshot.copy(files = listOf(file("src/a.kt"), file("src/b.kt", "2"), file("new.kt")))
        all.update(next)
        scoped.update(next)
        assertEquals(revision, scoped.revision)
        assertEquals("Second topic", scoped.notes.single().text)
        assertEquals("First topic", all.notes.single().text)
        all.clearNotes()
        assertEquals(1, scoped.notes.size)
        scoped.update(next.copy(files = listOf(file("src/a.kt", "2"))))
        assertEquals(revision + 1, scoped.revision)
        assertTrue(scoped.notes.isEmpty())
    }

    @Test fun `a scope stays fixed when its files disappear and reappear`() {
        val scoped = DiffReviewState(setOf("new.kt"))
        val global = state().snapshot
        scoped.update(global.copy(files = global.files + file("new.kt", status = "untracked")))
        scoped.addNote(scoped.revision, listOf("new.kt"), "A new file")
        scoped.update(global)
        assertTrue(scoped.snapshot.files.isEmpty())
        assertTrue(scoped.notes.isEmpty())
        assertEquals(setOf("new.kt"), scoped.scopePaths)
        rejected { scoped.addNote(scoped.revision, listOf("src/a.kt"), "Outside scope") }
        scoped.update(global.copy(files = global.files + file("new.kt", "2", "untracked")))
        assertEquals(listOf("new.kt"), scoped.snapshot.files.map { it.path })
        assertTrue(scoped.notes.isEmpty())
    }

    @Test fun `unified evidence includes additions deletions and renames independent of the graph`() {
        assertEquals("@@ -1,3 +1,3 @@\n start\n-old\n+new\n end\n",
            reviewPatch("start\nold\nend\n", "start\nnew\nend\n", listOf(LineChange(1, 2, 1, 2))))
        assertEquals("@@ -0,0 +1,2 @@\n+one\n+two\n", reviewPatch("", "one\ntwo\n", listOf(LineChange(0, 0, 0, 2))))
        assertEquals("@@ -1,2 +0,0 @@\n-one\n-two\n", reviewPatch("one\ntwo\n", "", listOf(LineChange(0, 2, 0, 0))))
        assertTrue(reviewPatch("same\n", "same", listOf(LineChange(0, 1, 0, 1))).contains("After: no newline"))
    }

    @Test fun `nearby patch regions merge without dropping old or new lines`() {
        val before = (1..20).joinToString("\n", postfix = "\n") { "old$it" }
        val after = (1..20).joinToString("\n", postfix = "\n") { if (it in listOf(5, 8, 19)) "new$it" else "old$it" }
        val patch = reviewPatch(before, after, listOf(LineChange(4, 5, 4, 5), LineChange(7, 8, 7, 8), LineChange(18, 19, 18, 19)))
        assertEquals(2, Regex("@@ -").findAll(patch).count())
        for (line in listOf(5, 8, 19)) { assertTrue(patch.contains("-old$line\n")); assertTrue(patch.contains("+new$line\n")) }
        assertFalse(patch.contains("old13"))
    }

    @Test fun `note batches are validated before any note is added`() {
        val state = state()
        rejected { state.addNotes(state.revision, listOf(DiffNote(listOf("src/a.kt"), "Fine"), DiffNote(listOf("missing"), "Bad"))) }
        assertTrue(state.notes.isEmpty())
        assertEquals(listOf("src/a.kt", "src/b.kt"), state.uncovered())
        state.addNotes(state.revision, listOf(DiffNote(listOf("src/a.kt"), "Fine"), DiffNote(listOf("src/b.kt"), "Also fine")))
        assertTrue(state.uncovered().isEmpty())
    }

    @Test fun `file and symbol notes coexist replace and remove independently`() {
        val state = state()
        val paths = listOf("src/a.kt")
        state.addNotes(state.revision, listOf(DiffNote(paths, "File"), DiffNote(paths, "First", "first"), DiffNote(paths, "Second", "second")))
        assertEquals(3, state.notes.size)
        state.addNotes(state.revision, listOf(DiffNote(paths, "Updated", "first")))
        assertEquals(listOf("File", "Updated", "Second"), state.notes.map { it.text })
        state.removeNote(paths, "first")
        assertEquals(listOf(null, "second"), state.notes.map { it.symbol })
        state.removeNote(paths)
        assertEquals("second", state.notes.single().symbol)
        val response = JsonParser.parseString(diffStateResult(state.snapshot, null, emptyList(), state.notes, 0, 0)).asJsonObject
        assertEquals("second", response["notes"].asJsonArray.single().asJsonObject["symbol"].asString)
        state.update(state.snapshot.copy(files = state.snapshot.files.map { it.copy(fingerprint = "new") }))
        assertTrue(state.notes.isEmpty())
    }

    private class Context : DiffReviewContext {
        val state = DiffReviewState().apply {
            update(ReviewSnapshot(listOf(ReviewFile("a.kt", "/a.kt", null, "modified", "-old\n+new", "1"),
                ReviewFile("b.kt", "/b.kt", null, "modified", "-x = '<a>'\n+x = \"<b>\" && y", "1")), emptyList()))
        }
        val reads = mutableListOf<List<String>>()
        override suspend fun state(offset: Int, patchBudget: Int) = "included=a.kt,b.kt"
        override suspend fun read(paths: List<String>, offset: Int): String {
            reads += paths
            return reviewJson.toJson(mapOf("files" to paths.map { path -> mapOf("path" to path, "patch" to state.snapshot.files.single { it.path == path }.patch) }))
        }
        override suspend fun addNotes(notes: List<DiffNote>): String { state.addNotes(state.revision, notes); return "Added" }
        override suspend fun uncovered() = state.uncovered()
    }

    private fun call(name: String, args: String) = Completion("", toolCalls = listOf(LlmToolCall(name, name, JsonParser.parseString(args).asJsonObject)))

    @Test fun `description reads several files per call and publishes several notes per call`() = runBlocking {
        val context = Context()
        val completions = ArrayDeque(listOf(
            call("read_diff_files", """{"paths":["a.kt","b.kt"]}"""),
            call("add_diff_notes", """{"notes":[{"paths":["a.kt"],"text":"Replaces the old value."},
                {"paths":["b.kt"],"text":"Switches the literal."}]}"""),
            Completion("2 notes covering 2/2 files."),
        ))
        val result = ToolChatRunner({ completions.removeFirst() }, diffReviewTools(context))
            .run(listOf(LlmMessage.system(diffReviewPrompt("test")), LlmMessage.user("/description")))
        assertEquals(3, result.requestCount)
        assertEquals(listOf(listOf("a.kt", "b.kt")), context.reads)
        assertEquals(listOf("Replaces the old value.", "Switches the literal."), context.state.notes.map { it.text })
    }

    @Test fun `coverage check sends missing files back until every file has a note`() = runBlocking {
        val context = Context()
        val requests = mutableListOf<CompletionRequest>()
        val completions = ArrayDeque(listOf(
            call("add_diff_notes", """{"notes":[{"paths":["a.kt"],"text":"Changes a."}]}"""),
            Completion("Done."),
            call("add_diff_notes", """{"notes":[{"paths":["b.kt"],"text":"Changes b."}]}"""),
            Completion("2 notes covering 2/2 files."),
        ))
        val result = ToolChatRunner({ requests += it; completions.removeFirst() }, diffReviewTools(context),
            followUp = { context.uncovered().takeIf { it.isNotEmpty() }?.let { coverageNote(it, 2) } })
            .run(listOf(LlmMessage.system(diffReviewPrompt("test")), LlmMessage.user("/description")))
        assertEquals("2 notes covering 2/2 files.", result.reply)
        assertTrue(context.state.uncovered().isEmpty())
        val nudge = requests[2].messages.last()
        assertEquals(LlmRole.USER, nudge.role)
        assertTrue(nudge.content.contains("1 of 2 files still have no note: b.kt"))
        // The rejected answer stays in the request so the next prefix matches the previous request.
        assertEquals(requests[1].messages, requests[2].messages.dropLast(2))
    }

    @Test fun `tool inputs reject malformed paths batches and oversized notes before dispatch`() = runBlocking {
        val context = Context()
        val tools = diffReviewTools(context)
        val add = tools.single { it.definition.name == "add_diff_notes" }
        val noteSchema = add.definition.parameters["properties"].asJsonObject["notes"].asJsonObject["items"].asJsonObject
        assertEquals(setOf("paths", "text", "symbol"), noteSchema["properties"].asJsonObject.keySet())
        assertEquals(listOf("paths", "text"), noteSchema["required"].asJsonArray.map { it.asString })
        for (args in listOf(
            """{}""",
            """{"notes":[]}""",
            """{"notes":["a.kt"]}""",
            """{"notes":[{"paths":["a.kt"]}]}""",
            """{"notes":[{"paths":[3],"text":"A"}]}""",
            """{"notes":[{"paths":[],"text":"A"}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"A"},{"paths":["a.kt","missing"],"text":"A"}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"${"x".repeat(2001)}"}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"A","symbol":null}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"A","symbol":42}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"A","symbol":" "}]}""",
            """{"notes":[{"paths":["a.kt"],"text":"A","symbol":"${"x".repeat(501)}"}]}""",
        )) {
            try { add.call(JsonParser.parseString(args).asJsonObject); fail(args) } catch (_: ToolInputException) { }
        }
        assertTrue(context.state.notes.isEmpty())
        val read = tools.single { it.definition.name == "read_diff_files" }
        for (args in listOf("""{}""", """{"paths":[]}""", """{"paths":["a.kt","b.kt"],"offset":10}""", """{"paths":["a.kt"],"offset":-1}""")) {
            try { read.call(JsonParser.parseString(args).asJsonObject); fail(args) } catch (_: ToolInputException) { }
        }
        assertTrue(context.reads.isEmpty())
    }

    @Test fun `tool results keep code characters unescaped`() = runBlocking {
        val json = Context().read(listOf("b.kt"), 0)
        assertTrue(json, json.contains("x = '<a>'") && json.contains("&& y"))
        assertFalse(json.contains("\\u00"))
    }

    @Test fun `prompt and schemas name only the current tools`() {
        val prompt = diffReviewPrompt("test")
        val names = diffReviewTools(Context()).map { it.definition.name }
        assertEquals(listOf("point_to_code", "get_current_diff", "read_diff_files", "find_diff_symbols", "show_diff_source", "add_diff_notes"), names)
        for (name in Regex("[a-z]+(?:_[a-z]+)+").findAll(prompt).map { it.value }.filter { it.endsWith("_diff") || it.contains("_diff_") })
            assertTrue("Unknown tool $name in the prompt", name in names)
        assertFalse(diffReviewTools(Context()).any { it.definition.parameters.toString().contains("revision") })
        assertTrue(isDescriptionRequest(" /Description ") && isDescriptionRequest("description") && !isDescriptionRequest("describe a.kt"))
        assertTrue(descriptionRequest("/description tests").endsWith("Focus: tests") && !descriptionRequest("/description").contains("Focus"))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected a rejected mutation") } catch (_: ToolInputException) { }
    }
}
