package dev.insight.agent

import com.google.gson.JsonObject

/** Changes the advertised schema without mutating the wrapped tool's definition. */
class SchemaOverride(tool: ChatTool, update: (JsonObject) -> Unit) : ChatTool by tool {
    override val definition = tool.definition.copy(parameters = tool.definition.parameters.deepCopy().also(update))
}

/** Records successful calls, including the arguments actually passed to the wrapped tool. */
class RecordingTool(
    private val tool: ChatTool,
    private val record: suspend (JsonObject, String) -> Unit,
) : ChatTool by tool {
    override suspend fun call(arguments: JsonObject): String {
        val recordedArguments = arguments.deepCopy()
        val result = tool.call(arguments)
        record(recordedArguments, result)
        return result
    }
}

/**
 * Shared source-output allowance for numbered file reads and path:line search matches.
 * Create one per workflow run and invoke its tools sequentially; the mutable allowance is not thread-safe.
 */
class SourceReadBudget(val maxLines: Int, val maxChars: Int) {
    var lines: Int = 0
        private set
    var chars: Int = 0
        private set
    val remainingLines: Int get() = maxLines - lines

    init {
        require(maxLines > 0 && maxChars > 0) { "Source read budgets must be positive" }
    }

    fun checkAvailable() {
        if (lines >= maxLines || chars >= maxChars)
            throw ToolInputException("Source read budget reached; answer now.")
    }

    internal fun consume(result: String, sourceLine: Regex, fallbackLines: Int = 0): String {
        var matched = 0
        val limitedLines = result.lineSequence().takeWhile { line ->
            if (!sourceLine.containsMatchIn(line)) true else ++matched <= remainingLines
        }.joinToString("\n")
        val remaining = maxChars - chars
        val shown = if (limitedLines.length <= remaining) limitedLines else limitedLines.take(remaining).substringBeforeLast('\n', "")
        val numbered = shown.lineSequence().count { sourceLine.containsMatchIn(it) }
        lines += if (numbered > 0) numbered else fallbackLines
        chars += if (limitedLines.length > remaining) remaining else shown.length
        return shown + when {
            limitedLines.length > remaining -> "\n[Source character budget reached.]"
            limitedLines != result -> "\n[Source line budget reached.]"
            else -> ""
        }
    }
}

/** Bounds the request and output of a source tool against an allowance shared with other tools. */
class BudgetedTool(
    private val tool: ChatTool,
    private val budget: SourceReadBudget,
    private val format: Format,
) : ChatTool by tool {
    enum class Format(internal val sourceLine: Regex) {
        READ(Regex("^\\s*\\d+\\|")),
        SEARCH(Regex("^.+:\\d+: ")),
    }

    override suspend fun call(arguments: JsonObject): String {
        budget.checkAvailable()
        val bounded = arguments.deepCopy()
        var requestedEnd = 0L
        var last = 0
        var start = 0
        when (format) {
            Format.SEARCH -> {
                val limit = (arguments.lenientInt("max_results") ?: 50).coerceIn(1, 80)
                bounded.addProperty("max_results", minOf(limit, budget.remainingLines))
            }
            Format.READ -> {
                start = arguments.lenientInt("start_line") ?: 1
                requestedEnd = arguments.lenientInt("end_line")?.toLong() ?: minOf(start.toLong() + 79, Int.MAX_VALUE.toLong())
                if (start < 1 || requestedEnd < start) throw ToolInputException("Invalid source line range")
                last = minOf(requestedEnd, start.toLong() + 79, start.toLong() + budget.remainingLines - 1).toInt()
                bounded.addProperty("start_line", start)
                bounded.addProperty("end_line", last)
            }
        }
        val result = tool.call(bounded)
        val shown = budget.consume(result, format.sourceLine, if (format == Format.READ) last - start + 1 else 0)
        return shown + if (format == Format.READ && last < requestedEnd) "\n[Read limited to $start-$last.]" else ""
    }
}
