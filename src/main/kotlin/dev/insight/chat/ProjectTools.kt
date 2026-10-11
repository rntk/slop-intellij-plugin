package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.llm.LlmTool
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * Read-only tools over the project's content files (sources, tests, and resources; excluded
 * directories such as build output are skipped). Paths are relative to the project directory.
 * Each call runs in a cancellable read action, so it neither blocks the EDT nor delays edits.
 */
fun projectTools(project: Project): List<ChatTool> {
    val files = ProjectFiles(project)
    return listOf(ListFilesTool(files), ReadFileTool(files), SearchTextTool(files))
}

internal class ProjectFiles(private val project: Project) {
    val index: ProjectFileIndex get() = ProjectFileIndex.getInstance(project)

    private fun roots(): List<VirtualFile> =
        (listOfNotNull(project.guessProjectDir()) + ProjectRootManager.getInstance(project).contentRoots).distinct()

    fun display(file: VirtualFile): String =
        roots().firstNotNullOfOrNull { root -> VfsUtilCore.getRelativePath(file, root)?.ifEmpty { "." } } ?: file.path

    /** Resolves a model-supplied path to a project content file or directory. */
    fun resolve(path: String): VirtualFile {
        val trimmed = path.trim().replace('\\', '/')
        if ("/../" in "/$trimmed/") throw ToolInputException("Path must not contain '..': $path")
        val roots = roots()
        // Absolute paths are accepted when they point inside the project.
        val relative = roots.firstOrNull { trimmed == it.path || trimmed.startsWith(it.path.trimEnd('/') + "/") }
            ?.let { trimmed.removePrefix(it.path) } ?: trimmed
        val clean = relative.trim('/').removePrefix("./").ifEmpty { "." }
        val file = roots.firstNotNullOfOrNull { if (clean == ".") it else it.findFileByRelativePath(clean) }
        if (file == null || !index.isInContent(file)) throw ToolInputException("No project file or directory at '$path'.")
        return file
    }

    /** Content files under [directory] (or the whole project) in path order. */
    fun contentFiles(directory: VirtualFile?, matcher: PathMatcher?): List<VirtualFile> {
        val found = mutableListOf<VirtualFile>()
        val visitor = { file: VirtualFile ->
            ProgressManager.checkCanceled()
            if (!file.isDirectory && (matcher == null || matcher.matches(Path.of(file.name)))) found += file
            true
        }
        if (directory == null) index.iterateContent(visitor) else index.iterateContentUnderDirectory(directory, visitor)
        return found.distinct().sortedBy { display(it) }
    }

    fun text(file: VirtualFile): String? {
        if (file.fileType.isBinary || file.length > MAX_FILE_BYTES) return null
        return FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
    }

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}

private abstract class ProjectTool(name: String, description: String, parameters: String) : ChatTool {
    final override val definition = LlmTool(name, description, JsonParser.parseString(parameters).asJsonObject)

    protected fun JsonObject.string(name: String): String? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw ToolInputException("$name must be a string")
        return value.asString.takeIf { it.isNotBlank() }
    }

    /** Local models sometimes quote numbers, so numeric strings are accepted. */
    protected fun JsonObject.int(name: String): Int? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        val number = if (value.isJsonPrimitive) value.asString.trim().toIntOrNull() else null
        return number ?: throw ToolInputException("$name must be an integer")
    }

    protected fun JsonObject.glob(): PathMatcher? = string("glob")?.let {
        try {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        } catch (e: IllegalArgumentException) {
            throw ToolInputException("glob is not a valid pattern: $it")
        }
    }

    protected fun directory(files: ProjectFiles, path: String?): VirtualFile? = path?.let {
        files.resolve(it).also { dir -> if (!dir.isDirectory) throw ToolInputException("'$path' is a file, not a directory.") }
    }
}

private class ListFilesTool(private val files: ProjectFiles) : ProjectTool(
    "list_files",
    "List project source and resource files (build output and excluded folders are skipped). Returns project-relative paths.",
    """{"type":"object","properties":{
        "directory":{"type":"string","description":"Project-relative directory to list recursively. Omit for the whole project."},
        "glob":{"type":"string","description":"File-name pattern such as *.kt or *Test.java."},
        "max_results":{"type":"integer","description":"Maximum paths to return (default 200, at most 500)."}
    },"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val limit = (arguments.int("max_results") ?: 200).coerceIn(1, 500)
        val matcher = arguments.glob()
        return readAction {
            val found = files.contentFiles(directory(files, arguments.string("directory")), matcher)
            if (found.isEmpty()) "No matching files."
            else found.take(limit).joinToString("\n") { files.display(it) } +
                if (found.size > limit) "\n[${found.size - limit} more files not shown.]" else ""
        }
    }
}

private class ReadFileTool(private val files: ProjectFiles) : ProjectTool(
    "read_file",
    "Read lines of a project text file. Each line is prefixed with its 1-based line number. Includes unsaved editor changes.",
    """{"type":"object","properties":{
        "path":{"type":"string","description":"Project-relative file path, as returned by list_files or search_text."},
        "start_line":{"type":"integer","description":"First line to read, 1-based (default 1)."},
        "end_line":{"type":"integer","description":"Last line to read, inclusive (default start_line + 299). At most $MAX_LINES lines per call."}
    },"required":["path"],"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.string("path") ?: throw ToolInputException("path is required")
        val start = arguments.int("start_line") ?: 1
        val end = arguments.int("end_line") ?: (start + 299)
        if (start < 1 || end < start) throw ToolInputException("start_line must be at least 1 and not after end_line")
        return readAction {
            val file = files.resolve(path)
            if (file.isDirectory) throw ToolInputException("'$path' is a directory; use list_files.")
            val text = FileDocumentManager.getInstance().getDocument(file)?.takeUnless { file.fileType.isBinary }?.text
                ?: throw ToolInputException("'$path' is binary or too large to read.")
            val lines = text.lines()
            if (start > lines.size) throw ToolInputException("'$path' has only ${lines.size} lines.")
            val last = minOf(end, lines.size, start + MAX_LINES - 1)
            val width = last.toString().length
            "${files.display(file)} (lines $start-$last of ${lines.size})\n" +
                (start..last).joinToString("\n") { "${it.toString().padStart(width)}| ${lines[it - 1]}" }
        }
    }

    private companion object {
        const val MAX_LINES = 400
    }
}

private class SearchTextTool(private val files: ProjectFiles) : ProjectTool(
    "search_text",
    "Case-insensitive plain-text search across project text files. Returns path:line: text for each matching line.",
    """{"type":"object","properties":{
        "query":{"type":"string","description":"Text to find (not a regular expression)."},
        "directory":{"type":"string","description":"Project-relative directory to search. Omit for the whole project."},
        "glob":{"type":"string","description":"File-name pattern such as *.kt."},
        "max_results":{"type":"integer","description":"Maximum matching lines (default 50, at most 200)."}
    },"required":["query"],"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val query = arguments.string("query") ?: throw ToolInputException("query is required")
        val limit = (arguments.int("max_results") ?: 50).coerceIn(1, 200)
        val matcher = arguments.glob()
        return readAction {
            val matches = mutableListOf<String>()
            var more = false
            search@ for (file in files.contentFiles(directory(files, arguments.string("directory")), matcher)) {
                ProgressManager.checkCanceled()
                val lines = files.text(file)?.lines() ?: continue
                for ((index, line) in lines.withIndex()) {
                    if (!line.contains(query, ignoreCase = true)) continue
                    if (matches.size == limit) { more = true; break@search }
                    matches += "${files.display(file)}:${index + 1}: ${line.trim().take(200)}"
                }
            }
            if (matches.isEmpty()) "No matches for '$query'."
            else matches.joinToString("\n") + if (more) "\n[More matches not shown; narrow the search.]" else ""
        }
    }
}
