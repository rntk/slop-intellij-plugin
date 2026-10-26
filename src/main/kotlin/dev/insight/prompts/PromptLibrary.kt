package dev.insight.prompts

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal enum class PromptKind(val folder: String, val label: String) {
    PROMPT("prompts", "Prompt"), APPENDIX("appendices", "Appendix");
    override fun toString() = label
}

internal data class PromptItem(
    val filename: String,
    val kind: PromptKind,
    val content: String,
    val title: String,
    val description: String,
    val tags: String,
) {
    override fun toString() = "${kind.label} · $title"
    fun matches(query: String) = listOf(filename, title, description, tags, content).any { it.contains(query, ignoreCase = true) }

    companion object {
        fun parse(filename: String, kind: PromptKind, content: String): PromptItem {
            val normalized = content.replace("\r\n", "\n")
            val front = Regex("\\A---[ \\t]*\\n(.*?)\\n---[ \\t]*(?:\\n|$)", RegexOption.DOT_MATCHES_ALL).find(normalized)
            val metadata = front?.groupValues?.get(1)?.lineSequence()?.mapNotNull {
                if (':' !in it) null else it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim().trim('\'', '"')
            }?.toMap().orEmpty()
            val body = normalized.substring(front?.range?.last?.plus(1) ?: 0)
            val title = metadata["title"]?.takeIf { it.isNotBlank() }
                ?: Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)?.trim()
                ?: filename.removeSuffix(".md").replace('_', ' ').replace('-', ' ')
            return PromptItem(filename, kind, content, title, metadata["description"].orEmpty(), metadata["tags"].orEmpty())
        }
    }
}

/** A library is a folder containing prompts/ and appendices/, compatible with the example project. */
internal class PromptLibrary(private val root: Path) {
    fun list(): List<PromptItem> = PromptKind.entries.flatMap { kind ->
        val directory = root.resolve(kind.folder)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) emptyList() else {
            require(!Files.isSymbolicLink(directory)) { "Library directories cannot be symbolic links" }
            Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".md") && !it.fileName.toString().startsWith('.') }
                    .map { PromptItem.parse(it.fileName.toString(), kind, Files.readString(it)) }.toList()
            }
        }
    }.sortedBy { it.title.lowercase() }

    fun save(kind: PromptKind, filename: String, content: String, overwrite: Boolean = false): PromptItem {
        val name = safeFilename(filename)
        val directory = root.resolve(kind.folder)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Library directories cannot be symbolic links" }
        val path = directory.resolve(name)
        require(!Files.isSymbolicLink(path)) { "Cannot overwrite a symbolic link" }
        Files.writeString(path, content, WRITE, if (overwrite) TRUNCATE_EXISTING else CREATE_NEW)
        return PromptItem.parse(name, kind, content)
    }

    fun delete(item: PromptItem) {
        val directory = root.resolve(item.kind.folder)
        require(!Files.isSymbolicLink(directory)) { "Library directories cannot be symbolic links" }
        Files.delete(directory.resolve(safeFilename(item.filename)))
    }

    companion object {
        fun safeFilename(value: String): String {
            val name = value.trim().let { if (it.endsWith(".md")) it else "$it.md" }
            require(name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.md")) && ".." !in name) {
                "Use a filename with letters, numbers, hyphens or underscores, without folders."
            }
            return name
        }
    }
}

internal fun appendPrompt(base: String, addition: String): String =
    listOf(base.trimEnd(), addition.trim()).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")

/** Escapes all input before producing a small, offline Markdown preview. */
internal fun promptPreview(text: String): String = buildString {
    append("<html><body>")
    var code = false
    for (line in text.lines()) {
        val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        when {
            line.startsWith("```") -> { append(if (code) "</pre>" else "<pre>"); code = !code }
            code -> append(escaped).append('\n')
            line.matches(Regex("#{1,6} .*")) -> {
                val level = line.takeWhile { it == '#' }.length
                append("<h$level>").append(escaped.drop(level + 1)).append("</h$level>")
            }
            line == "---" -> append("<hr>")
            else -> append(escaped).append("<br>")
        }
    }
    if (code) append("</pre>")
    append("</body></html>")
}
