package dev.insight.diff.walk

import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.ToolRegistry
import dev.insight.agent.intArg
import dev.insight.agent.objectTool
import dev.insight.agent.optionalStringArg
import dev.insight.diff.ReviewFile
import dev.insight.diff.wallLines
import dev.insight.llm.JsonSchemas
import dev.insight.llm.clipLine
import dev.insight.prompts.PromptTemplates
import java.util.Locale

/** What the Walkthrough canvas shows when its own chat is asked: hunk cards and step notes in board order, and the focused card's hunk. */
data class WalkCanvasView(val hunks: List<String>, val steps: List<String>, val focused: String? = null)

/** Names of the walkthrough chat's own tools, beside the read-only [WalkTools]. */
object WalkChatToolNames {
    const val READ_STEPS = "read_steps"
    const val READ_LINKS = "read_links"
}

/**
 * A Walkthrough chat's read-only tools: [tools] go with every turn, and [registry] holds the fallbacks the model loads with
 * search_tools (or by calling them by name) only when the pinned context is not enough.
 */
class WalkChatTools(val tools: List<ChatTool>, val registry: ToolRegistry) {
    /** Every tool, those sent each turn first. */
    val all: List<ChatTool> get() = tools + registry.tools
}

/**
 * The read-only tools of a Walkthrough chat over [walk] of [files]: read_hunks every turn, as the pinned context's continuation
 * hints lead there; its steps and links, the run's other hunk readers (list_hunks, search_diff, read_snapshot_source) and
 * [projectTools] by topic. Nothing here records.
 */
fun walkChatTools(
    files: List<ReviewFile>, walk: Walkthrough, projectTools: List<ChatTool> = emptyList(), resultChars: Int = WALK_CHAT_RESULT_CHARS,
): WalkChatTools {
    val tools = WalkTools(files, WalkLedger.of(walk), resultChars = resultChars)
    val registry = ToolRegistry()
        .register("reading the walkthrough's steps, their notes and how hunks are linked and why", readSteps(walk, resultChars), readLinks(walk, resultChars))
        .register("finding hunks across the whole change by status, path or text", tools.listHunks, tools.searchDiff)
        .register("reading the before or after source of changed files beyond the hunks", tools.readSnapshotSource)
    if (projectTools.isNotEmpty()) registry.register(AskSubagentTool.PROJECT_TOPIC, projectTools)
    return WalkChatTools(listOf(tools.readHunks), registry)
}

private fun readSteps(walk: Walkthrough, resultChars: Int): ChatTool = objectTool(WalkChatToolNames.READ_STEPS,
    "Read the walkthrough's steps. Without step_id: coverage and a page of every step with its id, kind, title, root and size. " +
        "With step_id: that step's explanation and its hunks in reading order with their status. Pass offset (next_offset) for the next page.",
    JsonSchemas.obj(
        "step_id" to JsonSchemas.string(minLength = 1, description = "A step id such as S3, or unlinked for the final step."),
        "offset" to JsonSchemas.integer(minimum = 0, description = "First step (or the step's first hunk) to list; 0 by default."),
        required = emptyList(), additionalProperties = false)) { args ->
    val offset = args.intArg("offset", 0)
    val id = args.optionalStringArg("step_id")?.trim()
    if (id == null) stepsPage(walk, offset, resultChars)
    else stepDetail(walk, walk.step(id) ?: throw ToolInputException("Unknown step $id; read_steps without step_id lists them."), offset, resultChars)
}

private fun readLinks(walk: Walkthrough, resultChars: Int): ChatTool = objectTool(WalkChatToolNames.READ_LINKS,
    "Read how hunks are linked in the walkthrough: for each hunk, its steps and every link with its relation as seen from that " +
        "hunk (leads to, caused by, same edit as, tested by, tests, documented by, documents, related to), the other hunk, the " +
        "reason and, when verified, the confidence. Weak links are kept but doubtful. A long result ends with the ids and offset " +
        "to read on with.",
    JsonSchemas.obj(
        "ids" to JsonSchemas.array(JsonSchemas.string(minLength = 1), minItems = 1, maxItems = WalkTools.MAX_READ_IDS, description = "Hunk ids."),
        "offset" to JsonSchemas.integer(minimum = 0, description = "First line of the first id's steps and links to show, from a " +
            "previous result; 0 by default."),
        required = listOf("ids"), additionalProperties = false)) { args ->
    val ids = idsArg(args["ids"], "ids", 1, WalkTools.MAX_READ_IDS)
    val unknown = ids.filter { walk.hunk(it) == null }
    if (unknown.isNotEmpty()) throw ToolInputException("Unknown hunk id${if (unknown.size == 1) "" else "s"}: ${unknown.joinToString()}")
    val offset = args.intArg("offset", 0)
    linksPage(walk, ids, offset, resultChars)
}

/** [ids]' steps and links from line [offset] of the first, while [resultChars] lasts; a cut page says how to read on. */
private fun linksPage(walk: Walkthrough, ids: List<String>, offset: Int, resultChars: Int): String = buildString {
    val limit = resultChars - HINT_ROOM
    for ((index, id) in ids.withIndex()) {
        val hunk = walk.hunk(id)!!
        val lines = linkLines(walk, hunk)
        val start = if (index == 0) offset else 0
        if (start > lines.size) throw ToolInputException("offset is beyond the ${lines.size} lines of $id")
        val header = walkHunkLine(hunk, walk) + (if (start > 0) " (from line $start)" else "") + "\n"
        val first = lines.getOrNull(start)?.let { it.length + 3 } ?: 0
        if (index > 0 && length + header.length + first > limit) { append(linksHint(ids.drop(index), 0)); return@buildString }
        append(header)
        var line = start
        while (line < lines.size) {
            val text = "  " + lines[line] + "\n"
            // The first id always shows a line, so every page reads on.
            if (length + text.length > limit && !(index == 0 && line == start)) break
            append(text)
            line++
        }
        if (line < lines.size) { append(linksHint(ids.drop(index), line)); return@buildString }
        append('\n')
    }
}.trimEnd()

private fun linksHint(ids: List<String>, offset: Int): String =
    "[More: call read_links with ids [${ids.joinToString()}]${if (offset > 0) " and offset $offset" else ""}.]\n"

private fun stepsPage(walk: Walkthrough, offset: Int, resultChars: Int): String {
    if (offset > walk.steps.size) throw ToolInputException("offset is beyond the ${walk.steps.size} steps")
    return buildString {
        append(walkStateLine(walk)).append('\n')
        if (walk.steps.isEmpty()) { append("Steps: none."); return@buildString }
        append("Steps (${walk.steps.size}):")
        var index = offset
        while (index < walk.steps.size) {
            val line = "\n- " + stepLine(walk.steps[index])
            if (length + line.length > resultChars - 40 && index > offset) break
            append(line)
            index++
        }
        if (index < walk.steps.size) append("\nnext_offset: $index")
    }
}

private fun stepDetail(walk: Walkthrough, step: WalkStep, offset: Int, resultChars: Int): String {
    if (offset > step.hunks.size) throw ToolInputException("offset is beyond the ${step.hunks.size} hunks of ${step.id}")
    return buildString {
        append(stepLine(step)).append('\n')
        if (offset == 0 && step.explanation.isNotBlank()) append("Explanation (model interpretation, not proof):\n").append(step.explanation.trim()).append('\n')
        append("Hunks in reading order:")
        var index = offset
        while (index < step.hunks.size) {
            val line = "\n- " + (walk.hunk(step.hunks[index])?.let { walkHunkLine(it, walk) } ?: step.hunks[index])
            if (length + line.length > resultChars - 40 && index > offset) break
            append(line)
            index++
        }
        if (index < step.hunks.size) append("\nnext_offset: $index")
    }
}

private fun stepLine(step: WalkStep): String =
    "${step.id} \"${step.title.clipLine(TITLE_CHARS)}\" (${step.kind.name.lowercase()}; root ${step.rootHunk}; ${step.hunks.size} hunk${if (step.hunks.size == 1) "" else "s"})"

/** Whether the walkthrough is built, still pending or stopped early, and its coverage. */
private fun walkStateLine(walk: Walkthrough): String {
    val coverage = walk.coverage()
    val state = when {
        walk.steps.isEmpty() && coverage.pending == coverage.total -> "Not built yet: every hunk is pending and nothing is linked."
        walk.complete -> "Built."
        coverage.pending > 0 -> "Being built or stopped early; pending hunks are not walked yet."
        else -> "Built, but the run stopped early."
    }
    return "$state ${coverageLine(coverage)}"
}

/** [hunk]'s steps, then its links as seen from it, one per line: what read_links pages through. */
private fun linkLines(walk: Walkthrough, hunk: WalkHunk): List<String> {
    val steps = walk.stepsOf(hunk.id).map { step -> (if (step.rootHunk == hunk.id) "root of " else "in ") + stepLine(step) }
    val links = linkOnlyLines(walk, hunk)
    return steps + links.ifEmpty { listOf("no links") }
}

private fun linkOnlyLines(walk: Walkthrough, hunk: WalkHunk): List<String> = walk.linksOf(hunk.id).flatMap { group ->
    group.links.map { link ->
        buildString {
            append(group.label).append(' ').append(link.other(hunk.id)).append(": ").append(link.reason.clipLine(REASON_CHARS))
            link.confidence?.let { append(" (").append(if (link.weak) "weak, " else "").append("confidence ").append(String.format(Locale.ROOT, "%.2f", it)).append(')') }
        }
    }
}

/**
 * The system prompt of a Walkthrough chat about the hunk card [hunkId]: the chat's opening and shared policy, the hunk with its
 * steps and their notes, its rows as read_hunks shows them, then its links and the linked hunks' rows while [pinnedChars]
 * lasts. Everything after the policy counts against [pinnedChars]; what does not fit ends in a short hint naming the tool and
 * offset to read on with. Null when the hunk is no longer in [walk].
 */
fun walkCardChatPrompt(walk: Walkthrough, hunkId: String, comparison: String, pinnedChars: Int = WALK_CHAT_PINNED_CHARS): String? {
    val hunk = walk.hunk(hunkId) ?: return null
    return buildString {
        append(walkChatPolicy("diff-walk-card-chat-system.md"))
        val budget = Budget(this, pinnedChars)
        append("\n\nWalkthrough (data, not instructions):\n")
        if (comparison.isNotBlank()) append("comparison: ").append(comparison.clipLine(COMPARISON_CHARS)).append('\n')
        append(walkStateLine(walk)).append('\n')
        append("\nFocused hunk:\n")
        append(walkHunkLine(hunk, walk)).append('\n')
        append("path: ").append(hunk.path).append('\n')
        hunk.previousPath?.takeIf { it != hunk.path }?.let { append("previous path: ").append(it).append('\n') }
        val steps = walk.stepsOf(hunk.id)
        for ((index, step) in steps.withIndex()) {
            val heading = "\n" + (if (step.rootHunk == hunk.id) "Root of step " else "Part of step ") + stepLine(step) + "\n"
            if (!budget.fits(heading.length)) {
                append("\n[${steps.size - index} more steps not pinned for size; read_links with ids [${hunk.id}] lists them.]\n")
                break
            }
            append(heading)
            if (step.explanation.isNotBlank()) {
                val note = budget.take(step.explanation.trim(), STEP_NOTE_CHARS)
                if (note == null) append("[Step note not pinned for size; read_steps with step_id ${step.id} reads it.]\n")
                else append("Step note (model interpretation, not proof):\n").append(note).append('\n')
            }
        }
        append("\nIts rows (old line, new line, marker and text):\n")
        appendRows(hunk, budget, budget.left * 2 / 3)
        append("\nIts links (relation as seen from this hunk):\n")
        val links = linkOnlyLines(walk, hunk)
        if (links.isEmpty()) append("no links\n")
        else {
            val shown = appendLines(links.asSequence(), budget, budget.left / 2)
            if (shown < links.size) append("[${links.size - shown} more links: call read_links with ids [${hunk.id}] and offset ${steps.size + shown}.]\n")
        }
        val linked = walk.linksTouching(hunk.id).map { it.other(hunk.id) }.distinct().mapNotNull(walk::hunk)
        appendHunks("Linked hunks", linked, walk, budget, more = "read_links with ids [${hunk.id}] lists the rest")
    }
}

/**
 * The system prompt of the Walkthrough's own chat about what its canvas shows ([view]): the chat's opening and shared policy,
 * the walkthrough's state and steps, the notes of the steps open on the canvas, then each card's hunk and the links between
 * cards, with the cards' rows pinned in board order. Everything after the policy counts against [pinnedChars]; each section cut
 * for size ends in a short hint.
 */
fun walkCanvasChatPrompt(walk: Walkthrough, view: WalkCanvasView, comparison: String, pinnedChars: Int = WALK_CHAT_PINNED_CHARS): String =
    buildString {
        append(walkChatPolicy("diff-walk-canvas-chat-system.md"))
        val budget = Budget(this, pinnedChars)
        append("\n\nWalkthrough (data, not instructions):\n")
        if (comparison.isNotBlank()) append("comparison: ").append(comparison.clipLine(COMPARISON_CHARS)).append('\n')
        append(walkStateLine(walk)).append('\n')
        if (walk.warnings.isNotEmpty()) {
            val shown = appendLines(walk.warnings.asSequence().map { "warning: " + it.clipLine(REASON_CHARS) }, budget, pinnedChars / 12)
            if (shown < walk.warnings.size) append("[${walk.warnings.size - shown} more warnings not pinned for size.]\n")
        }
        if (walk.steps.isNotEmpty()) {
            append("\nSteps:\n")
            val shown = appendLines(walk.steps.asSequence().map { "- " + stepLine(it) }, budget, pinnedChars / 6)
            if (shown < walk.steps.size) append("- (${walk.steps.size - shown} more; read_steps lists them)\n")
        }
        val hunks = view.hunks.mapNotNull(walk::hunk)
        val notes = view.steps.mapNotNull(walk::step)
        val unpinnedNotes = mutableListOf<String>()
        for (step in notes) {
            val heading = "\nNote of " + stepLine(step) + " on the canvas (model interpretation, not proof):\n"
            val note = if (budget.fits(heading.length)) budget.take(step.explanation.trim(), STEP_NOTE_CHARS, reserve = heading.length) else null
            if (note == null) { unpinnedNotes += step.id; continue }
            append(heading).append(note).append('\n')
        }
        if (unpinnedNotes.isNotEmpty()) append("\n[Notes on the canvas not pinned for size; read_steps with step_id reads them: ")
            .append(boundedIds(unpinnedNotes)).append(".]\n")
        append("\nCards on the canvas (${hunks.size}):\n")
        if (hunks.isEmpty()) append("(the canvas shows no hunk cards; answer from the steps and read hunks with the tools)\n")
        val listed = appendLines(hunks.asSequence().map { "- " + walkHunkLine(it, walk) + (if (it.id == view.focused) " (focused)" else "") },
            budget, pinnedChars / 4)
        if (listed < hunks.size) append("[${hunks.size - listed} more cards not listed for size: ")
            .append(boundedIds(hunks.drop(listed).map { it.id })).append(".]\n")
        val shown = hunks.mapTo(HashSet()) { it.id }
        val between = walk.links.filter { it.from in shown && it.to in shown }
        if (between.isNotEmpty()) {
            append("\nLinks between the cards:\n")
            val lines = between.asSequence().map { link ->
                "- ${link.from} ${link.relation.outgoing} ${link.to}: ${link.reason.clipLine(REASON_CHARS)}" + (if (link.weak) " (weak)" else "")
            }
            val count = appendLines(lines, budget, pinnedChars / 6)
            if (count < between.size) append("- (${between.size - count} more; read_links lists them)\n")
        }
        appendHunks("Card", hunks, walk, budget, listTitle = "Cards")
    }

/** The pinned context's characters still free in [text], counted from where the budget started. */
private class Budget(private val text: StringBuilder, private val chars: Int) {
    private val start = text.length
    val left: Int get() = (chars - (text.length - start)).coerceAtLeast(0)

    /** Whether [length] more characters fit, keeping room for the hint that closes a cut section. */
    fun fits(length: Int): Boolean = length + HINT_ROOM <= left

    /** [value] cut to [max] and what is left after [reserve] more characters, or null when not even a short note fits. */
    fun take(value: String, max: Int, reserve: Int = 0): String? {
        val room = minOf(max, left - HINT_ROOM - reserve)
        if (room < minOf(MIN_NOTE_CHARS, value.length)) return null
        return if (value.length <= room) value else value.take(room - 1).trimEnd() + "…"
    }
}

/** Appends [lines] while they fit in [max] characters and [budget]; returns how many were appended. */
private fun StringBuilder.appendLines(lines: Sequence<String>, budget: Budget, max: Int): Int {
    val at = length
    var count = 0
    for (line in lines) {
        if (length - at + line.length + 1 > max || !budget.fits(line.length + 1)) break
        append(line).append('\n')
        count++
    }
    return count
}

/** [ids] joined while they stay short, then how many more there are. */
private fun boundedIds(ids: List<String>): String {
    val shown = mutableListOf<String>()
    var chars = 0
    for (id in ids) {
        if (chars + id.length > HINT_IDS_CHARS && shown.isNotEmpty()) break
        shown += id
        chars += id.length + 2
    }
    return shown.joinToString(", ") + if (shown.size < ids.size) " and ${ids.size - shown.size} more" else ""
}

/** Each of [hunks]' rows under a heading while [budget] lasts; the rest are listed, briefly, for read_hunks. */
private fun StringBuilder.appendHunks(
    title: String, hunks: List<WalkHunk>, walk: Walkthrough, budget: Budget, listTitle: String = title, more: String? = null,
) {
    val unread = mutableListOf<String>()
    for (hunk in hunks) {
        val heading = "\n" + title + " " + walkHunkLine(hunk, walk) + ":\n"
        if (unread.isNotEmpty() || budget.left < MIN_ROWS_CHARS + heading.length) { unread += hunk.id; continue }
        append(heading)
        appendRows(hunk, budget, budget.left)
    }
    if (unread.isEmpty()) return
    append("\n[$listTitle not pinned for size; read them with read_hunks when a question needs them: ").append(boundedIds(unread))
    if (more != null && unread.size > 1) append("; ").append(more)
    append(".]\n")
}

/** [hunk]'s rows up to [max] characters of [budget], saying where read_hunks reads on when cut; only shown rows are formatted. */
private fun StringBuilder.appendRows(hunk: WalkHunk, budget: Budget, max: Int) {
    val lines = if (hunk.patch.isEmpty()) emptyList() else wallLines(hunk.patch)
    if (lines.isEmpty()) {
        append("(no textual changes${hunk.previousPath?.let { "; renamed from $it" }.orEmpty()})\n")
        return
    }
    val shown = appendLines(lines.asSequence().map(::hunkRow), budget, max)
    if (shown < lines.size) append("[${lines.size - shown} more rows: call read_hunks with ids [${hunk.id}] and offset $shown.]\n")
}

/**
 * Whether [after] is the change [this] was, though line numbers moved: the same path, rename and rows. A hunk id names a
 * position in its file, so after a refresh it may name a different change.
 */
internal fun WalkHunk.sameChangeAs(after: WalkHunk): Boolean =
    path == after.path && previousPath == after.previousPath && fileStatus == after.fileStatus && rowsBody() == after.rowsBody()

private fun WalkHunk.rowsBody(): String = if (header.isEmpty()) patch else patch.removePrefix(header).trimStart('\r', '\n')

/** A Walkthrough chat's own [opening], then the policy both of its chats share. */
private fun walkChatPolicy(opening: String): String =
    PromptTemplates.load(opening) + "\n\n" + PromptTemplates.load("diff-walk-chat-policy.md")

/** A typical card's steps, its rows and its linked hunks arrive whole; huge ones are read on through tools. */
const val WALK_CHAT_PINNED_CHARS = 40_000
const val WALK_CHAT_RESULT_CHARS = 12_000
private const val STEP_NOTE_CHARS = 3_000
private const val MIN_NOTE_CHARS = 200
private const val MIN_ROWS_CHARS = 400
private const val REASON_CHARS = 300
private const val TITLE_CHARS = 160
private const val COMPARISON_CHARS = 300
/** Room kept for the hint closing a cut section, and the ids such a hint may list. */
private const val HINT_ROOM = 300
private const val HINT_IDS_CHARS = 200
