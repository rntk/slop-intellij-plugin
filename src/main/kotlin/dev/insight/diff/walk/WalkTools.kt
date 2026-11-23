package dev.insight.diff.walk

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException
import dev.insight.agent.ToolRegistry
import dev.insight.agent.booleanArg
import dev.insight.agent.diffSideArg
import dev.insight.agent.intArg
import dev.insight.agent.objectTool
import dev.insight.agent.objectsArg
import dev.insight.agent.optionalStringArg
import dev.insight.agent.pathsArg
import dev.insight.agent.stringArg
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffSourceResult
import dev.insight.diff.wallLines
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmTool
import dev.insight.llm.clipLine
import dev.insight.prompts.PromptTemplates
import dev.insight.review.wall.WallLine
import java.util.regex.PatternSyntaxException

/** Names of the walkthrough tools. */
object WalkToolNames {
    const val WALK_STATUS = "walk_status"
    const val LIST_HUNKS = "list_hunks"
    const val READ_HUNKS = "read_hunks"
    const val SEARCH_DIFF = "search_diff"
    const val READ_SNAPSHOT_SOURCE = "read_snapshot_source"
    const val RECORD_STEP = "record_step"
    const val EXTEND_STEP = "extend_step"
    const val ADD_LINKS = "add_links"
    const val MARK_STANDALONE = "mark_standalone"
    const val ASK_SUBAGENT = "ask_subagent"

    /** Tools that change the walkthrough; a read-only run never has them. */
    val RECORDING = setOf(RECORD_STEP, EXTEND_STEP, ADD_LINKS, MARK_STANDALONE)
}

/** Which patch rows a diff search looks at. */
enum class WalkSearchSide(val wire: String) {
    ANY("any"), CHANGED("changed"), ADDED("added"), REMOVED("removed"), CONTEXT("context");

    internal fun accepts(kind: WallLine.Kind): Boolean = when (this) {
        ANY -> kind == WallLine.Kind.ADDED || kind == WallLine.Kind.REMOVED || kind == WallLine.Kind.CONTEXT
        CHANGED -> kind == WallLine.Kind.ADDED || kind == WallLine.Kind.REMOVED
        ADDED -> kind == WallLine.Kind.ADDED
        REMOVED -> kind == WallLine.Kind.REMOVED
        CONTEXT -> kind == WallLine.Kind.CONTEXT
    }
}

/**
 * The walkthrough's tools over one snapshot's [files] and a run's [ledger]: read tools ([readOnly]) and the tools that
 * record steps and links ([recording]). [priority] orders pending hunks in walk_status, best seeds first. Outputs are plain
 * text cut to about [resultChars]; tools are called in sequence, like the ledger.
 */
class WalkTools(
    files: List<ReviewFile>,
    private val ledger: WalkLedger,
    priority: List<String> = ledger.hunks.map { it.id },
    private val resultChars: Int = 12_000,
) {
    private val filesByPath = files.associateBy { it.path }
    private val hunks = ledger.hunks
    private val rank = priority.withIndex().associate { it.value to it.index }
    private val rows by lazy { hunks.associate { it.id to hunkRows(it) } }

    init { require(resultChars >= 1_000) { "Tool results need at least 1000 characters" } }

    /** Tools that never change the walkthrough. */
    fun readOnly(): List<ChatTool> = listOf(walkStatus, listHunks, readHunks, searchDiff, readSnapshotSource)

    /** Tools that record steps, links and standalone hunks in the ledger. */
    fun recording(): List<ChatTool> = listOf(recordStep, extendStep, addLinks, markStandalone)

    val walkStatus: ChatTool = objectTool(WalkToolNames.WALK_STATUS,
        "Show walkthrough progress: hunk counts per status, the steps recorded so far, and a page of pending hunks, best " +
            "seeds first, with path, kind, +added/-removed and header. Pass offset (next_offset) for the next page.",
        JsonSchemas.obj("offset" to JsonSchemas.integer(minimum = 0, description = "First pending hunk to list; 0 by default."),
            required = emptyList(), additionalProperties = false)) { args -> status(args.intArg("offset", 0)) }

    val listHunks: ChatTool = objectTool(WalkToolNames.LIST_HUNKS,
        "List hunks with their id, status, steps, +added/-removed and header, filtered by exact path, path glob or status. " +
            "Ids look like path#index (one-based per file). Pass offset (next_offset) for the next page.",
        JsonSchemas.obj(
            "path" to JsonSchemas.string(minLength = 1, description = "Only hunks of this changed file, exactly as listed."),
            "path_glob" to JsonSchemas.string(minLength = 1, description = "Only paths matching this glob, such as src/**/*.kt or *Test.kt."),
            "status" to JsonSchemas.enum(HunkStatus.entries.map { it.name.lowercase() }, description = "Only hunks with this status."),
            "offset" to JsonSchemas.integer(minimum = 0, description = "First hunk to list; 0 by default."),
            required = emptyList(), additionalProperties = false)) { args ->
        val path = args.optionalStringArg("path")
        if (path != null && path !in filesByPath) throw ToolInputException("$path has no diff in this snapshot.")
        val glob = args.optionalStringArg("path_glob")?.let(::globRegex)
        val status = args.optionalStringArg("status")?.let(::statusArg)
        val walk = ledger.snapshot()
        val matching = hunks.filter { (path == null || it.path == path) && (glob == null || glob.matches(it.path)) &&
            (status == null || walk.statusOf(it.id) == status) }
        page("Hunks", matching, args.intArg("offset", 0), LIST_PAGE) { hunkLine(it, walk) }
    }

    val readHunks: ChatTool = objectTool(WalkToolNames.READ_HUNKS,
        "Read hunks' patch rows. Each row shows its old line, new line, then the patch marker (+ added, - removed, space " +
            "context) and text; the @@ header comes first. Long hunks are paged: when the result names a next call, make it " +
            "to read on. offset skips rows of the first listed hunk.",
        JsonSchemas.obj(
            "ids" to JsonSchemas.array(JsonSchemas.string(minLength = 1), minItems = 1, maxItems = MAX_READ_IDS, description = "Hunk ids."),
            "offset" to JsonSchemas.integer(minimum = 0, description = "Rows of the first hunk to skip; 0 by default."),
            required = listOf("ids"), additionalProperties = false)) { args ->
        val ids = idsArg(args["ids"], "ids", 1, MAX_READ_IDS)
        renderHunks(ids, args.intArg("offset", 0), resultChars)
    }

    val searchDiff: ChatTool = objectTool(WalkToolNames.SEARCH_DIFF,
        "Search every hunk's rows, like grep over the whole diff, and list the hunks that match with their status and up to " +
            "$SEARCH_SAMPLES matching rows each. Plain patterns match case-insensitively; with regex true the pattern is a Java " +
            "regular expression (use \\b for word bounds). To find every site of a rename, search the old name in removed rows " +
            "and the new name in added rows. Headers and markers are not searched. Pass offset (next_offset) for the next page.",
        JsonSchemas.obj(
            "pattern" to JsonSchemas.string(minLength = 1, description = "Text, or a Java regular expression when regex is true."),
            "regex" to JsonSchemas.boolean(description = "Treat pattern as a regular expression; false by default."),
            "side" to JsonSchemas.enum(WalkSearchSide.entries.map { it.wire },
                description = "Rows to search: any (default), changed (added and removed), added, removed or context."),
            "path_glob" to JsonSchemas.string(minLength = 1, description = "Only paths matching this glob, such as src/**/*.kt."),
            "status" to JsonSchemas.enum(HunkStatus.entries.map { it.name.lowercase() }, description = "Only hunks with this status, such as pending."),
            "offset" to JsonSchemas.integer(minimum = 0, description = "First matching hunk to list; 0 by default."),
            required = listOf("pattern"), additionalProperties = false)) { args ->
        val pattern = args.stringArg("pattern")
        val regex = if (args.booleanArg("regex")) try { Regex(pattern) } catch (e: PatternSyntaxException) {
            throw ToolInputException("Invalid regular expression: ${e.description}")
        } else null
        val side = args.optionalStringArg("side")?.let { wire ->
            WalkSearchSide.entries.firstOrNull { it.wire == wire } ?: throw ToolInputException("side must be any, changed, added, removed or context")
        } ?: WalkSearchSide.ANY
        val glob = args.optionalStringArg("path_glob")?.let(::globRegex)
        val status = args.optionalStringArg("status")?.let(::statusArg)
        search(pattern, regex, side, glob, status, args.intArg("offset", 0))
    }

    val readSnapshotSource: ChatTool = objectTool(WalkToolNames.READ_SNAPSHOT_SOURCE,
        "Read numbered lines of a changed file's snapshot source beyond its hunks: side after (default) is the new version, " +
            "before the old one. Lines are one-based and inclusive. If next_start_line is present, call again from it.",
        JsonSchemas.obj(
            "path" to JsonSchemas.string(minLength = 1, description = "A changed file, exactly as listed."),
            "side" to JsonSchemas.enum(listOf("before", "after"), description = "before or after (default)."),
            "start_line" to JsonSchemas.integer(minimum = 1, description = "First line; 1 by default."),
            "end_line" to JsonSchemas.integer(minimum = 1, description = "Last line, inclusive."),
            required = listOf("path"), additionalProperties = false)) { args ->
        val path = args.stringArg("path")
        val file = filesByPath[path] ?: throw ToolInputException("$path has no diff in this snapshot.")
        diffSourceResult(file, args.diffSideArg(), args.intArg("start_line", 1).coerceAtLeast(1),
            if (args.has("end_line")) args.intArg("end_line", 1) else null)
    }

    val recordStep: ChatTool = objectTool(WalkToolNames.RECORD_STEP,
        "Record one step of the walkthrough: a chain from a root change to what it leads to. root and hunks become linked " +
            "(analyzed); hunks also listed in covered become covered: shown in this step through the root's analysis, such as " +
            "the call sites of a rename, without separate analysis. hunks are in reading order; the root comes first. links are " +
            "directed with short concrete reasons and must touch a hunk of the step. Returns the step id and what is still pending.",
        JsonSchemas.obj(
            "title" to JsonSchemas.string(minLength = 1, maxLength = WalkLedger.MAX_TITLE_CHARS, description = "A short title naming the change, such as \"Rename loadUser to fetchUser\"."),
            "explanation" to JsonSchemas.string(minLength = 1, description = "Markdown: what changed, why, and how the chain follows; cite path:line (after or before)."),
            "root" to JsonSchemas.string(minLength = 1, description = "The hunk id the chain starts from: the cause."),
            "hunks" to idsSchema(WalkLedger.MAX_STEP_HUNKS, "Other hunk ids of the step, in reading order."),
            "links" to linksSchema(MAX_LINKS),
            "covered" to idsSchema(WalkLedger.MAX_STEP_HUNKS, "Hunks of the step covered by this analysis rather than analyzed one by one."),
            required = listOf("title", "explanation", "root"), additionalProperties = false)) { args ->
        val change = ledger.recordStep(args.stringArg("title"), args.stringArg("explanation"), args.stringArg("root"),
            optionalIds(args, "hunks", WalkLedger.MAX_STEP_HUNKS), optionalLinks(args), optionalIds(args, "covered", WalkLedger.MAX_STEP_HUNKS))
        changeResult("Recorded", change)
    }

    val extendStep: ChatTool = objectTool(WalkToolNames.EXTEND_STEP,
        "Add hunks, covered hunks, links or more explanation to a recorded step, such as call sites found later. Added hunks " +
            "become linked unless also listed in covered; a covered hunk must be in the step or added now.",
        JsonSchemas.obj(
            "step_id" to JsonSchemas.string(minLength = 1, description = "A step id from record_step, such as S3."),
            "hunks" to idsSchema(WalkLedger.MAX_STEP_HUNKS, "Hunk ids to append, in reading order."),
            "links" to linksSchema(MAX_LINKS),
            "covered" to idsSchema(WalkLedger.MAX_STEP_HUNKS, "Hunks of the step covered by its analysis."),
            "explanation" to JsonSchemas.string(minLength = 1, description = "Markdown appended to the step's explanation."),
            required = listOf("step_id"), additionalProperties = false)) { args ->
        val change = ledger.extendStep(args.stringArg("step_id"), optionalIds(args, "hunks", WalkLedger.MAX_STEP_HUNKS), optionalLinks(args),
            optionalIds(args, "covered", WalkLedger.MAX_STEP_HUNKS), args.optionalStringArg("explanation"))
        changeResult("Extended", change)
    }

    val addLinks: ChatTool = objectTool(WalkToolNames.ADD_LINKS,
        "Add directed links between any hunks, such as between steps. Links do not change a hunk's status.",
        JsonSchemas.obj("links" to linksSchema(MAX_LINKS), required = listOf("links"), additionalProperties = false)) { args ->
        val (added, known) = ledger.addLinks(linksArg(args["links"]), emit = true)
        "Added $added link${plural(added)}${if (known > 0) "; $known already known" else ""}. Links: ${ledger.linkCount}."
    }

    val markStandalone: ChatTool = objectTool(WalkToolNames.MARK_STANDALONE,
        "Mark a pending hunk as self-contained: it neither causes nor follows from other changes. It gets its own one-hunk " +
            "step with your note. Use it only after checking with search_diff that nothing else depends on it.",
        JsonSchemas.obj(
            "id" to JsonSchemas.string(minLength = 1, description = "A pending hunk id."),
            "note" to JsonSchemas.string(minLength = 1, description = "One or two sentences on what the change does."),
            "title" to JsonSchemas.string(minLength = 1, maxLength = WalkLedger.MAX_TITLE_CHARS, description = "Optional short title."),
            required = listOf("id", "note"), additionalProperties = false)) { args ->
        changeResult("Marked standalone", ledger.markStandalone(args.stringArg("id"), args.stringArg("note"), args.optionalStringArg("title")))
    }

    /**
     * [ids]' numbered rows within [chars], skipping [offset] rows of the first; the text names the read_hunks call that
     * continues when cut.
     */
    fun renderHunks(ids: List<String>, offset: Int = 0, chars: Int = resultChars): String {
        val unknown = ids.filter { ledger.hunk(it) == null }
        if (unknown.isNotEmpty()) throw ToolInputException("Unknown hunk id${plural(unknown.size)}: ${unknown.joinToString()}")
        val walk = ledger.snapshot()
        val first = rows.getValue(ids.first())
        if (offset > 0 && offset >= first.size) throw ToolInputException("offset must be below ${first.size}, the rows of ${ids.first()}")
        return buildString {
            for ((position, id) in ids.withIndex()) {
                val hunk = ledger.hunk(id)!!
                val all = rows.getValue(id)
                val start = if (position == 0) offset else 0
                append(hunkLine(hunk, walk)).append(", rows ${start + 1}-")
                val headerAt = length
                var shown = start
                append('\n')
                if (all.isEmpty()) append("(no textual changes${if (hunk.previousPath != null) "; renamed from ${hunk.previousPath}" else ""})\n")
                while (shown < all.size && length + all[shown].length + 1 <= chars) append(all[shown++]).append('\n')
                if (shown == start && all.isNotEmpty()) append(all[shown++].take(chars / 2)).append('\n') // a page always advances
                insert(headerAt, "$shown of ${all.size}")
                if (shown < all.size) {
                    append("[More rows: call read_hunks with ids ${(listOf(id) + ids.drop(position + 1)).joinToString(prefix = "[", postfix = "]")} and offset $shown.]")
                    return@buildString
                }
                if (position < ids.lastIndex && length >= chars) {
                    append("[Not shown: ${ids.drop(position + 1).joinToString()}; read them with read_hunks.]")
                    return@buildString
                }
                append('\n')
            }
        }.trimEnd()
    }

    /** A compact text status: counts, recent steps within [stepChars], and a page of pending hunks from [offset]. */
    fun status(offset: Int, stepChars: Int = resultChars / 3): String {
        val walk = ledger.snapshot()
        val coverage = walk.coverage()
        return buildString {
            append(coverageLine(coverage)).append('\n')
            append("Steps: ${walk.steps.size}; links: ${walk.links.size}.\n")
            val recent = StringBuilder()
            for (step in walk.steps.asReversed()) {
                val line = "- ${step.id} \"${step.title}\" root ${step.rootHunk}, ${step.hunks.size} hunk${plural(step.hunks.size)}\n"
                if (recent.length + line.length > stepChars) { recent.insert(0, "- (earlier steps omitted)\n"); break }
                recent.insert(0, line)
            }
            append(recent)
            val pending = hunks.filter { walk.statusOf(it.id) == HunkStatus.PENDING }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            append(page("Pending hunks, best seeds first", pending, offset, STATUS_PAGE) { hunkLine(it, walk) })
        }
    }

    private fun search(pattern: String, regex: Regex?, side: WalkSearchSide, glob: Regex?, status: HunkStatus?, offset: Int): String {
        val needle = pattern.lowercase()
        val walk = ledger.snapshot()
        data class Hit(val hunk: WalkHunk, val rows: List<String>, val count: Int)
        val hits = mutableListOf<Hit>()
        var total = 0
        for (hunk in hunks) {
            if (glob != null && !glob.matches(hunk.path)) continue
            if (status != null && walk.statusOf(hunk.id) != status) continue
            val lines = wallLines(hunk.patch)
            val shown = rows.getValue(hunk.id)
            val matched = lines.indices.filter { index ->
                val line = lines[index]
                side.accepts(line.kind) && line.text.drop(1).let { regex?.containsMatchIn(it) ?: it.lowercase().contains(needle) }
            }
            if (matched.isEmpty()) continue
            total += matched.size
            hits += Hit(hunk, matched.take(SEARCH_SAMPLES).map { shown[it] }, matched.size)
        }
        if (offset > hits.size) throw ToolInputException("offset is beyond the ${hits.size} matching hunks")
        val pending = hits.count { walk.statusOf(it.hunk.id) == HunkStatus.PENDING }
        return buildString {
            append("'$pattern' in ${side.wire} rows: $total match${if (total == 1) "" else "es"} in ${hits.size} hunk${plural(hits.size)} ($pending pending).")
            var index = offset
            while (index < hits.size && index - offset < SEARCH_PAGE) {
                val hit = hits[index]
                val block = buildString {
                    append("\n").append(hunkLine(hit.hunk, walk))
                    hit.rows.forEach { append("\n    ").append(if (it.length <= SEARCH_ROW_CHARS) it else it.take(SEARCH_ROW_CHARS - 1) + "…") }
                    if (hit.count > hit.rows.size) append("\n    (+${hit.count - hit.rows.size} more matches)")
                }
                if (length + block.length > resultChars - 80 && index > offset) break
                append(block)
                index++
            }
            if (index < hits.size) append("\nnext_offset: $index")
        }
    }

    private fun <T> page(title: String, items: List<T>, offset: Int, size: Int, line: (T) -> String): String {
        if (offset > items.size) throw ToolInputException("offset is beyond the ${items.size} items")
        return buildString {
            if (items.isEmpty()) { append("$title: none."); return@buildString }
            append("$title (${items.size}):")
            var index = offset
            while (index < items.size && index - offset < size) {
                val text = "\n- " + line(items[index])
                if (length + text.length > resultChars - 40 && index > offset) break
                append(text)
                index++
            }
            if (index < items.size) append("\nnext_offset: $index")
        }
    }

    private fun hunkLine(hunk: WalkHunk, walk: Walkthrough): String = walkHunkLine(hunk, walk)

    private fun changeResult(verb: String, change: StepChange): String {
        val walk = ledger.snapshot()
        val coverage = walk.coverage()
        val linked = change.step.hunks.count { walk.statusOf(it) == HunkStatus.LINKED }
        val covered = change.step.hunks.count { walk.statusOf(it) == HunkStatus.COVERED }
        return buildString {
            append("$verb step ${change.step.id} with ${change.step.hunks.size} hunk${plural(change.step.hunks.size)} ")
            append("($linked linked, $covered covered); ${change.newlyHandled} newly handled.")
            if (change.linksAdded + change.linksKnown > 0)
                append(" Links added: ${change.linksAdded}${if (change.linksKnown > 0) ", already known: ${change.linksKnown}" else ""}.")
            append("\n").append(coverageLine(coverage))
            val next = ledger.pendingIds.sortedBy { rank[it] ?: Int.MAX_VALUE }.take(5)
            if (next.isNotEmpty()) append("\nNext pending: ").append(next.joinToString())
        }
    }

    private fun idsSchema(max: Int, description: String) =
        JsonSchemas.array(JsonSchemas.string(minLength = 1), maxItems = max, description = description)

    private fun linksSchema(max: Int) = JsonSchemas.array(JsonSchemas.obj(
        "from" to JsonSchemas.string(minLength = 1, description = "Hunk id the link starts from (the cause, or the tested code)."),
        "to" to JsonSchemas.string(minLength = 1, description = "Hunk id the link points to."),
        "relation" to JsonSchemas.enum(WalkRelation.entries.map { it.wire }, description =
            "causes: from leads to or requires to; same_edit: the same mechanical edit in both; tests: to tests from; " +
                "documents: to documents or configures from; related: same change without a clear direction."),
        "reason" to JsonSchemas.string(minLength = 1, maxLength = WalkLedger.MAX_REASON_CHARS, description = "Short and concrete, such as \"calls the renamed fetchUser\"."),
        additionalProperties = false), maxItems = max, description = "Directed links between hunks.")

    private fun optionalIds(args: JsonObject, name: String, max: Int): List<String> =
        if (args.has(name) && !args[name].isJsonNull) idsArg(args[name], name, 0, max) else emptyList()

    private fun optionalLinks(args: JsonObject): List<WalkLink> =
        if (args.has("links") && !args["links"].isJsonNull && !(args["links"].isJsonArray && args["links"].asJsonArray.isEmpty)) linksArg(args["links"]) else emptyList()

    private fun linksArg(value: JsonElement?): List<WalkLink> = objectsArg(value, "links", "link", MAX_LINKS) { link, prefix ->
        val relation = link.stringArg("relation", prefix).let { wire ->
            WalkRelation.fromWire(wire) ?: throw ToolInputException("${prefix}relation must be one of ${WalkRelation.entries.joinToString { it.wire }}")
        }
        WalkLink(link.stringArg("from", prefix), link.stringArg("to", prefix), relation, link.stringArg("reason", prefix))
    }

    private fun statusArg(value: String): HunkStatus =
        HunkStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ToolInputException("status must be one of ${HunkStatus.entries.joinToString { it.name.lowercase() }}")

    companion object {
        const val MAX_READ_IDS = 8
        const val MAX_LINKS = 80
        const val LIST_PAGE = 60
        const val STATUS_PAGE = 30
        const val SEARCH_PAGE = 25
        const val SEARCH_SAMPLES = 3
        private const val SEARCH_ROW_CHARS = 240
    }
}

/** One line naming [hunk] for the model: its id, status, steps and triage kind, then its size and header. */
internal fun walkHunkLine(hunk: WalkHunk, walk: Walkthrough): String = buildString {
    val status = walk.statusOf(hunk.id)
    append(hunk.id).append(" [").append(status.name.lowercase())
    walk.stepsOf(hunk.id).takeIf { it.isNotEmpty() }?.let { steps -> append(" in ").append(steps.joinToString(",") { it.id }) }
    walk.triage?.get(hunk.id)?.let { append("; ").append(it.kind.wire) }
    append("] ")
    if (hunk.fileStatus != "modified") append(hunk.fileStatus).append(' ')
    append("+${hunk.added} -${hunk.removed}")
    if (hunk.header.isNotEmpty()) append(' ').append(hunk.header.clipLine(HUNK_HEADER_CHARS))
    else append(" (whole file")
        .append(hunk.previousPath?.let { ", from $it" } ?: "").append(')')
}

private const val HUNK_HEADER_CHARS = 120

/** 1-based display rows of [hunk]: old and new line numbers, then the patch row with tabs expanded and cut when overlong. */
internal fun hunkRows(hunk: WalkHunk): List<String> = if (hunk.patch.isEmpty()) emptyList() else wallLines(hunk.patch).map(::hunkRow)

/** One row of [hunkRows], so a caller showing only a prefix formats only that. */
internal fun hunkRow(line: WallLine): String {
    val text = if (line.text.length <= 400) line.text else line.text.take(399) + "…"
    return "%5s %5s %s".format(line.old?.toString() ?: "", line.new?.toString() ?: "", text)
}

internal fun coverageLine(coverage: WalkCoverage): String =
    "Hunks: ${coverage.total}; pending ${coverage.pending}, linked ${coverage[HunkStatus.LINKED]}, covered ${coverage[HunkStatus.COVERED]}, " +
        "standalone ${coverage[HunkStatus.STANDALONE]}" + (coverage[HunkStatus.UNLINKED].takeIf { it > 0 }?.let { ", unlinked $it" } ?: "") + "."

/** 0..[max] (at least [min]) nonempty id strings, without duplicates. */
internal fun idsArg(value: JsonElement?, name: String, min: Int, max: Int): List<String> {
    if (value == null || !value.isJsonArray || value.asJsonArray.size() !in min..max)
        throw ToolInputException("$name must be an array of $min to $max hunk ids")
    return value.asJsonArray.map {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.isBlank()) throw ToolInputException("$name must contain hunk id strings")
        it.asString.trim()
    }.distinct()
}

/** A glob over project-relative paths: `**` spans folders, `*` and `?` stay within one; a glob without `/` matches file names too. */
internal fun globRegex(glob: String): Regex {
    val pattern = buildString {
        var index = 0
        while (index < glob.length) {
            val char = glob[index]
            when {
                glob.startsWith("**/", index) -> { append("(?:.*/)?"); index += 3; continue }
                glob.startsWith("**", index) -> { append(".*"); index += 2; continue }
                char == '*' -> append("[^/]*")
                char == '?' -> append("[^/]")
                else -> append(Regex.escape(char.toString()))
            }
            index++
        }
    }
    return Regex(if ('/' in glob) pattern else "(?:.*/)?$pattern")
}

private fun plural(count: Int) = if (count == 1) "" else "s"

/**
 * Delegates a focused research question to a fresh read-only run: [readTools] (no recording tools, no ask_subagent) plus
 * [projectTools] loadable by search_tools, at most [maxRounds] tool rounds within [contextChars]. Returns the answer cut
 * to [answerChars] and the known hunk ids it cites. The sub-run's tool calls reach [onToolCall] named `ask_subagent/<tool>`.
 */
class AskSubagentTool(
    private val complete: CompletionFn,
    private val walkTools: WalkTools,
    private val readTools: List<ChatTool> = walkTools.readOnly(),
    private val projectTools: List<ChatTool> = emptyList(),
    private val maxRounds: Int = 12,
    private val contextChars: Int = 48_000,
    private val answerChars: Int = 3_000,
    private val toolResultChars: Int = 10_000,
    private val knownHunk: (String) -> Boolean,
    private val onToolCall: suspend (ToolCallEvent) -> Unit = {},
) : ChatTool {
    init {
        val forbidden = WalkToolNames.RECORDING + WalkToolNames.ASK_SUBAGENT
        val names = (readTools + projectTools).map { it.definition.name }
        require(names.none { it in forbidden }) { "A subagent gets read-only tools only" }
        require(maxRounds >= 1 && answerChars > 0 && contextChars > 0)
    }

    override val definition = LlmTool(WalkToolNames.ASK_SUBAGENT,
        "Delegate a focused research question to a helper with read-only tools (hunks, diff search, snapshot source and " +
            "project files), so many reads stay out of your context. Ask something concrete, such as \"which hunks call " +
            "fetchUser and do any still pass the old argument?\". It cannot record steps; check its answer before recording. " +
            "Returns a short answer and the hunk ids it cites. A turn allows only a few calls.",
        JsonSchemas.obj(
            "task" to JsonSchemas.string(minLength = 1, description = "The question to research, with the names and paths it involves."),
            "hunk_ids" to JsonSchemas.array(JsonSchemas.string(minLength = 1), maxItems = MAX_HUNKS, description = "Hunks to start from; their rows are given to the helper."),
            "paths" to JsonSchemas.array(JsonSchemas.string(minLength = 1), maxItems = MAX_PATHS, description = "Files the question involves."),
            required = listOf("task"), additionalProperties = false))

    override suspend fun call(arguments: JsonObject): String {
        val task = arguments.stringArg("task")
        val ids = if (arguments.has("hunk_ids")) idsArg(arguments["hunk_ids"], "hunk_ids", 0, MAX_HUNKS) else emptyList()
        val unknown = ids.filterNot(knownHunk)
        if (unknown.isNotEmpty()) throw ToolInputException("Unknown hunk id${plural(unknown.size)}: ${unknown.joinToString()}")
        val paths = if (arguments.has("paths")) pathsArg(arguments["paths"], MAX_PATHS) else emptyList()
        val user = buildString {
            append("Research task:\n").append(task.trim()).append('\n')
            if (paths.isNotEmpty()) append("\nFiles involved: ").append(paths.joinToString()).append('\n')
            if (ids.isNotEmpty()) append("\nHunks to start from:\n").append(walkTools.renderHunks(ids, 0, contextChars / 4)).append('\n')
        }
        val registry = projectTools.takeIf { it.isNotEmpty() }?.let { ToolRegistry().register(PROJECT_TOPIC, it) }
        val runner = ToolChatRunner(complete, readTools, maxToolRounds = maxRounds, maxToolResultChars = toolResultChars,
            maxContextChars = contextChars, maxHistoryChars = 0, evictionTarget = 0.85, registry = registry)
        val result = runner.run(listOf(LlmMessage.system(SUBAGENT_SYSTEM), LlmMessage.user(user))) { event ->
            onToolCall(event.copy(name = "${WalkToolNames.ASK_SUBAGENT}/${event.name}"))
        }
        val answer = result.reply.trim()
        val cited = HUNK_REF.findAll(answer).map { it.value }.filter(knownHunk).distinct().toList()
        return buildString {
            append("Subagent answer (research only; check before recording):\n")
            append(if (answer.length <= answerChars) answer else answer.take(answerChars) + "\n[Answer cut at $answerChars characters.]")
            append("\nCited hunks: ").append(cited.ifEmpty { listOf("none") }.joinToString())
        }
    }

    companion object {
        const val MAX_HUNKS = 10
        const val MAX_PATHS = 10
        /** The topic project tools are registered under, for main runs and subagents alike. */
        const val PROJECT_TOPIC = "searching, listing and reading current project files outside the diff"
        private val HUNK_REF = Regex("[^\\s`'\"(),;\\[\\]<>]+#\\d+")
        internal val SUBAGENT_SYSTEM: String by lazy {
            PromptTemplates.render("diff-walkthrough-subagent.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA))
        }
    }
}
