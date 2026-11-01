package dev.insight.agent

import com.google.gson.JsonObject
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmTool

/**
 * Tools a turn may load on demand instead of advertising every schema in every request. Each tool is registered under
 * a short [topic][register] saying what its group is for; search_tools lists the topics and finds tools by name or by
 * words from their names, descriptions and topics. Tool names are unique within a registry. Build one per workflow
 * run when its tools hold per-run state, such as a [CallAllowance].
 */
class ToolRegistry {
    private class Entry(val tool: ChatTool, val topic: String, val index: Int) {
        val name: String get() = tool.definition.name
        val nameWords = words(name)
        val topicWords = words(topic)
        val descriptionWords = words(tool.definition.description)
    }

    private val entries = LinkedHashMap<String, Entry>()

    val tools: List<ChatTool> get() = entries.values.map { it.tool }

    /** Adds [tools] under [topic], a phrase such as "opening cards on the canvas". */
    fun register(topic: String, vararg tools: ChatTool): ToolRegistry = register(topic, tools.toList())

    fun register(topic: String, tools: List<ChatTool>): ToolRegistry {
        require(topic.isNotBlank()) { "A tool topic must not be blank" }
        for (tool in tools) {
            val name = tool.definition.name
            require(name !in entries) { "Tool '$name' is already registered" }
            entries[name] = Entry(tool, topic.trim(), entries.size)
        }
        return this
    }

    operator fun get(name: String): ChatTool? = entries[name]?.tool

    /** The registered tools named [names], in that order; every name must be registered. */
    fun select(vararg names: String): List<ChatTool> = names.map { get(it) ?: throw IllegalArgumentException("Tool '$it' is not registered") }

    /** Topics of the tools not in [exclude], in registration order. */
    fun topics(exclude: Set<String> = emptySet()): List<String> =
        entries.values.filter { it.name !in exclude }.map { it.topic }.distinct()

    /**
     * Up to [limit] tools not in [exclude] that match [query], best first: tools named in it, then by matching words,
     * names counting most. Queries consisting of exact tool names return only those tools. Descriptive queries
     * keep tools matching the most query words in their names or descriptions; topic-only matches are a fallback.
     * A blank query matches every tool.
     */
    fun search(query: String, exclude: Set<String> = emptySet(), limit: Int = Int.MAX_VALUE): List<ChatTool> {
        val candidates = entries.values.filter { it.name !in exclude }
        val named = query.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.toSet()
        if (named.isNotEmpty() && named.all { it in entries })
            return candidates.filter { it.name in named }.take(limit).map { it.tool }
        val terms = words(query)
        if (query.isBlank()) return candidates.take(limit).map { it.tool }
        if (terms.isEmpty()) return emptyList()
        data class Match(val entry: Entry, val directMatches: Int, val score: Int)
        val scored = candidates.map { entry ->
            val score = (if (entry.name in named) NAMED else 0) + terms.sumOf { term ->
                (if (matches(term, entry.nameWords)) NAME_WORD else 0) +
                    (if (matches(term, entry.topicWords)) TOPIC_WORD else 0) +
                    (if (matches(term, entry.descriptionWords)) DESCRIPTION_WORD else 0)
            }
            Match(entry, terms.count { matches(it, entry.nameWords) || matches(it, entry.descriptionWords) }, score)
        }
        val mostDirectMatches = scored.maxOfOrNull { it.directMatches } ?: return emptyList()
        return scored.filter { it.score > 0 && it.directMatches == mostDirectMatches }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.entry.index })
            .take(limit).map { it.entry.tool }
    }

    private companion object {
        const val NAMED = 100
        const val NAME_WORD = 4
        const val TOPIC_WORD = 2
        const val DESCRIPTION_WORD = 1

        val STOP_WORDS = setOf("the", "and", "for", "with", "that", "this", "from", "into", "its", "any", "all", "tool", "tools")

        /** Lowercase words of [text] without a plural "s", splitting snake_case and camelCase. */
        fun words(text: String): Set<String> = text.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP_WORDS }
            .map { if (it.length > 3 && it.endsWith('s') && !it.endsWith("ss")) it.dropLast(1) else it }.toSet()

        /** Whether [term] is one of [words], or a word of four letters or more starts the other, as "open" and "opening". */
        fun matches(term: String, words: Set<String>): Boolean = term in words || words.any { word ->
            minOf(word.length, term.length) >= 4 && (word.startsWith(term) || term.startsWith(word))
        }
    }
}

/**
 * The tools of one turn: [initial] ones, plus those loaded from [registry] as the turn goes on. When the registry holds
 * tools beyond the initial ones, the set also offers search_tools, which loads matches so later requests advertise
 * their schemas. An initial tool shadows a registered tool of the same name. Not thread-safe; calls run in sequence.
 */
class ToolSet(initial: List<ChatTool>, private val registry: ToolRegistry? = null) {
    private val active = LinkedHashMap<String, ChatTool>()

    init {
        for (tool in initial) {
            require(active.put(tool.definition.name, tool) == null) { "Tool names must be unique" }
        }
        if (registry != null && registry.tools.any { it.definition.name !in active }) {
            val search = SearchToolsTool(this, registry)
            require(active.put(search.definition.name, search) == null) { "Tool name '${search.definition.name}' is reserved" }
        }
    }

    val names: Set<String> get() = active.keys.toSet()
    val definitions: List<LlmTool> get() = active.values.map { it.definition }

    operator fun get(name: String): ChatTool? = active[name]

    /** Whether [name] can be loaded but is not active yet. */
    fun loadable(name: String): Boolean = name !in active && registry?.get(name) != null

    /** Activates [tools] that are not active yet, returning those it added. */
    fun load(tools: List<ChatTool>): List<ChatTool> = tools.filter { active.putIfAbsent(it.definition.name, it) == null }

    fun load(name: String): ChatTool? = registry?.get(name)?.let { load(listOf(it)).singleOrNull() }
}

/** Finds registered tools that the turn does not have yet and loads them; tools it already has are never listed again. */
private class SearchToolsTool(private val tools: ToolSet, private val registry: ToolRegistry) : ChatTool {
    override val definition = LlmTool(
        SEARCH_TOOLS,
        "Find and load more tools when the ones you have can't do what's needed. Search by what you want to do or by " +
            "tool names; matching tools you don't have yet are loaded. Wait for their schemas in the next reply " +
            "before calling them; don't batch discovery with calls to the tools being loaded. " +
            "Leave query empty to load any of them. More tools cover: ${registry.topics(tools.names).joinToString("; ")}.",
        JsonSchemas.obj(
            "query" to JsonSchemas.string(description = "Words for what the tool should do, such as \"highlight a card\", or exact tool names."),
            "max_results" to JsonSchemas.integer(description = "Most tools to load (default $DEFAULT_RESULTS, at most $MAX_RESULTS)."),
            required = emptyList(), additionalProperties = false,
        ),
    )

    override suspend fun call(arguments: JsonObject): String {
        val query = arguments.optionalStringArg("query").orEmpty()
        val limit = (arguments.lenientInt("max_results") ?: DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)
        val found = registry.search(query, exclude = tools.names, limit = limit)
        if (found.isEmpty()) {
            val left = registry.topics(tools.names)
            return if (left.isEmpty()) "No more tools to load; you already have every tool."
            else "No tool you don't already have matches '$query'. More tools cover: ${left.joinToString("; ")}."
        }
        val loaded = tools.load(found)
        return "Loaded ${loaded.size} tool${if (loaded.size == 1) "" else "s"}; call ${if (loaded.size == 1) "it" else "them"} directly:\n" +
            loaded.joinToString("\n") { "- ${it.definition.name}: ${firstSentence(it.definition.description)}" }
    }

    private fun firstSentence(text: String): String =
        Regex("^.*?[.!?](\\s|$)").find(text)?.value?.trim() ?: text

    companion object {
        const val DEFAULT_RESULTS = 5
        const val MAX_RESULTS = 10
    }
}

/** The name of the tool a [ToolSet] adds for loading tools from its registry. */
const val SEARCH_TOOLS = "search_tools"
