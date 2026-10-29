package dev.insight.diff

import dev.insight.chat.ChatTool
import dev.insight.chat.SourcePoint
import dev.insight.chat.ToolInputException
import dev.insight.chat.intArg
import dev.insight.chat.objectTool
import dev.insight.chat.pathsArg
import dev.insight.chat.sourcePointTool
import dev.insight.chat.stringArg
import dev.insight.enrichment.JsonSchemas
import dev.insight.review.ReviewContext
import dev.insight.review.ReviewSourceRange
import dev.insight.review.addNotesTool
import dev.insight.review.pathListSchema
import dev.insight.review.sourceLinesArg

/** One reply's view of a tab. The captured revision is checked by the context, never echoed by the model. */
interface DiffReviewContext : ReviewContext {
    suspend fun pointToCode(point: SourcePoint): String = throw ToolInputException("Source locations are unavailable")
    /** One size-bounded page; [patchBudget] inlines whole patches that also fit the serialized result. */
    suspend fun state(offset: Int, patchBudget: Int = 0): String
    suspend fun read(paths: List<String>, offset: Int): String
    suspend fun symbols(paths: List<String>, query: String, offset: Int): String =
        throw ToolInputException("Symbol discovery is unavailable")

    suspend fun summaries(offset: Int): String = throw ToolInputException("Change summaries are unavailable")
}

fun diffReviewTools(context: DiffReviewContext): List<ChatTool> {
    fun pathList(max: Int) = pathListSchema(max, "File paths exactly as listed in the snapshot.")
    return listOf(
        sourcePointTool(diff = true, show = context::pointToCode),
        objectTool("get_current_diff", "List the changed files under review (up to $DIFF_PAGE_SIZE per page), the user's selected files and existing canvas notes. " +
            "Selected paths and note excerpts may be omitted to fit the result limit; counts include omitted entries. " +
            "If next_offset is present, call again with it to get the next page.",
            JsonSchemas.obj("offset" to JsonSchemas.integer(minimum = 0,
                description = "Start of the page; use next_offset from the previous page."),
                required = emptyList(), additionalProperties = false)) {
            context.state(it.intArg("offset", 0))
        },
        objectTool("read_diff_files", "Read the unified diffs of 1–$MAX_READ_PATHS files, including removed lines. Each patch line starts with its " +
            "number in the whole patch and \"|\"; first_line and last_line give the numbers on this page. A long patch returns next_offset " +
            "(a character offset): call again with only that path and offset to continue. Files in not_read did not fit in this result; request them again.",
            JsonSchemas.obj("paths" to pathList(MAX_READ_PATHS),
                "offset" to JsonSchemas.integer(minimum = 0, description = "Only with a single path: continue its patch from next_offset."),
                required = listOf("paths"), additionalProperties = false)) {
            val paths = pathsArg(it["paths"], MAX_READ_PATHS)
            val offset = it.intArg("offset", 0)
            if (offset > 0 && paths.size > 1) throw ToolInputException("offset requires exactly one path")
            context.read(paths, offset)
        },
        objectTool("find_diff_symbols", "Find current-source declarations in 1–10 included files. Returns exact selectors, kinds and lines for add_diff_notes. " +
            "Deleted, unavailable or edited-since-snapshot source cannot be resolved. Use next_offset to continue; query optionally filters names.",
            JsonSchemas.obj("paths" to pathList(10), "query" to JsonSchemas.string(minLength = 1),
                "offset" to JsonSchemas.integer(minimum = 0), required = listOf("paths"), additionalProperties = false)) {
            context.symbols(pathsArg(it["paths"], 10), if (it.has("query")) it.stringArg("query") else "", it.intArg("offset", 0))
        },
        objectTool("show_diff_source", "Show a separate read-only source code block on the canvas for an included file. " +
            "Uses exact snapshot source with syntax highlighting and original line numbers, never model-authored code. " +
            "Lines are one-based and inclusive (maximum 200 lines / 20000 characters). side defaults to after; " +
            "use before for removed code. Repeating the same path, side and range reuses its card. Does not count as an explanatory note.",
            JsonSchemas.obj("path" to JsonSchemas.string(minLength = 1),
                "start_line" to JsonSchemas.integer(minimum = 1), "end_line" to JsonSchemas.integer(minimum = 1),
                "side" to JsonSchemas.enum(listOf("before", "after")),
                required = listOf("path", "start_line", "end_line"), additionalProperties = false)) {
            val lines = it.sourceLinesArg(200)
            val side = if (it.has("side")) it.stringArg("side") else "after"
            if (side !in setOf("before", "after")) throw ToolInputException("side must be before or after")
            context.showSource(ReviewSourceRange(it.stringArg("path"), lines.first, lines.last, side))
        },
        addNotesTool("add_diff_notes", "find_diff_symbols", "diffs", ::pathList, "paths in current source", context::addNotes),
    )
}

internal fun diffChatTools(context: DiffReviewContext): List<ChatTool> =
    diffReviewTools(context) + changeSummaryTools(context)

internal const val DIFF_PAGE_SIZE = 50
internal const val MAX_READ_PATHS = 10

internal fun diffReviewPrompt() = dev.insight.chat.visualCodeAssistantPrompt + "\n\n" + """
    You help a developer understand the changes under review using the available snapshot tools.
    Use existing summaries to navigate, then verify claims against snapshot source.
    /summaries and /topics generate the corresponding views.
    /explain explains a file, folder or part in depth: how it works and why, with canvas notes and diagrams.

    The comparison identifies the repository and revisions. Before and after refer to those snapshot sides;
    a historical after-side is not the working tree. Use the corresponding side's line numbers.
    Use before for removed code and include the side when citing a diff location. Symbol discovery is available
    only where current project source matches the snapshot after-side; otherwise use snapshot source locations.
""".trimIndent() + "\n\n" + dev.insight.chat.projectContextPrompt
