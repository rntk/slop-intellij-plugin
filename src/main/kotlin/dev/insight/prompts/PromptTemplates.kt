package dev.insight.prompts

import java.util.concurrent.ConcurrentHashMap

/** Bundled UTF-8 prompts. Substitutions are literal and single-pass, so project evidence stays untouched. */
internal object PromptTemplates {
    private val templates = ConcurrentHashMap<String, String>()
    private val placeholder = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}")

    /** Removes the resource's final line ending, matching the former Kotlin trimIndent literals. */
    fun load(name: String): String = templates.computeIfAbsent(name) {
        require(name.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.md"))) { "Invalid prompt name: $name" }
        val stream = checkNotNull(javaClass.getResourceAsStream("/prompts/$name")) { "Missing prompt template: $name" }
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n").removeSuffix("\n")
    }

    fun render(name: String, values: Map<String, String> = emptyMap()): String = renderText(load(name), values)

    internal fun renderText(template: String, values: Map<String, String>): String {
        val required = placeholder.findAll(template).map { it.groupValues[1] }.toSet()
        require(required == values.keys) {
            "Prompt placeholders differ: missing ${required - values.keys}, unexpected ${values.keys - required}"
        }
        return placeholder.replace(template) { values.getValue(it.groupValues[1]) }
    }
}
