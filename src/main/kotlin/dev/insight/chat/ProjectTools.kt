package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.llm.LlmTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.regex.PatternSyntaxException

/**
 * Tools over the project's content files (sources, tests, and resources; excluded directories such
 * as build output are skipped). Paths are relative to the project directory. Lookups run in
 * cancellable read actions, so they neither block the EDT nor delay edits. Presentation tools show
 * locations in the editor without modifying project files.
 */
fun projectTools(project: Project): List<ChatTool> = projectTools(project, SourcePoints(project, "Project chat"))

internal fun projectTools(project: Project, sourcePoints: SourcePoints): List<ChatTool> {
    val files = ProjectFiles(project)
    return listOf(ListFilesTool(files), ReadFileTool(files), SearchTextTool(files), OpenFileTool(files), sourcePointTool { point ->
        val text = readAction {
            val file = files.resolve(point.path)
            if (file.isDirectory) throw ToolInputException("${point.path} is a directory; use list_files.")
            files.text(file) ?: throw ToolInputException("${point.path} is binary or too large to read.")
        }
        if (ApplicationManager.getApplication().isDispatchThread) sourcePoints.show(point, text)
        else withContext(Dispatchers.EDT) { sourcePoints.show(point, text) }
    })
}

internal class ProjectFiles(val project: Project) {
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

    /** Accepts "true"/"false" strings for the same reason as [int]. */
    protected fun JsonObject.boolean(name: String): Boolean? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        val flag = if (value.isJsonPrimitive) value.asString.trim().lowercase().toBooleanStrictOrNull() else null
        return flag ?: throw ToolInputException("$name must be true or false")
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
        "end_line":{"type":"integer","description":"Last line to read, inclusive (default start_line + 79). At most $MAX_LINES lines per call."}
    },"required":["path"],"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.string("path") ?: throw ToolInputException("path is required")
        val start = arguments.int("start_line") ?: 1
        val end = arguments.int("end_line") ?: (start + 79)
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
    "Case-insensitive search across project text files, like grep -i. Returns path:line: text for each matching line.",
    """{"type":"object","properties":{
        "query":{"type":"string","description":"Text to find; a Java regular expression when regex is true."},
        "regex":{"type":"boolean","description":"Treat query as a regular expression (default false)."},
        "directory":{"type":"string","description":"Project-relative directory to search. Omit for the whole project."},
        "glob":{"type":"string","description":"File-name pattern such as *.kt."},
        "max_results":{"type":"integer","description":"Maximum matching lines (default 50, at most 200)."}
    },"required":["query"],"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val query = arguments.string("query") ?: throw ToolInputException("query is required")
        val limit = (arguments.int("max_results") ?: 50).coerceIn(1, 200)
        val matcher = arguments.glob()
        val matches: (String) -> Boolean = if (arguments.boolean("regex") == true) {
            val pattern = try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                throw ToolInputException("query is not a valid regular expression: ${e.description}")
            }
            { line -> pattern.containsMatchIn(CancellableSearchInput(line)) }
        } else {
            { line -> line.contains(query, ignoreCase = true) }
        }
        return readAction {
            val found = mutableListOf<String>()
            var more = false
            search@ for (file in files.contentFiles(directory(files, arguments.string("directory")), matcher)) {
                ProgressManager.checkCanceled()
                val lines = files.text(file)?.lines() ?: continue
                for ((index, line) in lines.withIndex()) {
                    ProgressManager.checkCanceled()
                    if (!matches(line)) continue
                    if (found.size == limit) { more = true; break@search }
                    found += "${files.display(file)}:${index + 1}: ${line.trim().take(200)}"
                }
            }
            if (found.isEmpty()) "No matches for '$query'."
            else found.joinToString("\n") + if (more) "\n[More matches not shown; narrow the search.]" else ""
        }
    }
}

/** Java's backtracking matcher repeatedly reads characters, even while stuck on one line. */
internal class CancellableSearchInput(
    private val text: CharSequence,
    private val checkCanceled: () -> Unit = { ProgressManager.checkCanceled() },
) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        checkCanceled()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        CancellableSearchInput(text.subSequence(startIndex, endIndex), checkCanceled)

    override fun toString(): String = text.toString()
}

/**
 * Shows a location to the user: opens the file in the editor, puts the caret on the line, and
 * selects the lines up to end_line when given. The chat keeps focus so the user can keep typing.
 */
private class OpenFileTool(private val files: ProjectFiles) : ProjectTool(
    "open_file",
    "Open a project file in the user's editor at a line, to show the user the code you are talking about. " +
        "Call it once you have found the relevant location, usually the most important one for your answer.",
    """{"type":"object","properties":{
        "path":{"type":"string","description":"Project-relative file path, as returned by list_files or search_text."},
        "line":{"type":"integer","description":"1-based line to place the caret on (default 1)."},
        "end_line":{"type":"integer","description":"Last line to highlight, inclusive. Omit to only place the caret."},
        "column":{"type":"integer","description":"1-based column on line (default 1)."}
    },"required":["path"],"additionalProperties":false}""",
) {
    override suspend fun call(arguments: JsonObject): String {
        val path = arguments.string("path") ?: throw ToolInputException("path is required")
        val line = arguments.int("line") ?: 1
        val endLine = arguments.int("end_line")
        val column = arguments.int("column") ?: 1
        if (line < 1 || column < 1) throw ToolInputException("line and column must be at least 1")
        if (endLine != null && endLine < line) throw ToolInputException("end_line must not be before line")
        // Validate before opening, so a bad call does not switch the user's editor and then fail.
        val file = readAction {
            files.resolve(path).also {
                if (it.isDirectory) throw ToolInputException("'$path' is a directory; use list_files.")
                if (it.fileType.isBinary) throw ToolInputException("'$path' is binary and cannot be shown at a line.")
                val document = FileDocumentManager.getInstance().getDocument(it)
                    ?: throw ToolInputException("'$path' cannot be opened in a text editor.")
                val lineCount = maxOf(document.lineCount, 1)
                if (line > lineCount) throw ToolInputException("'$path' has only $lineCount lines.")
            }
        }
        return onEdt {
            val project = files.project
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), false)
                ?: throw ToolInputException("'$path' cannot be opened in a text editor.")
            val document = editor.document
            // An empty document still has one (empty) line to point at.
            val lineCount = maxOf(document.lineCount, 1)
            if (line > lineCount) throw ToolInputException("'$path' has only $lineCount lines.")
            val last = minOf(endLine ?: line, lineCount)
            fun start(n: Int) = if (document.lineCount == 0) 0 else document.getLineStartOffset(n - 1)
            fun end(n: Int) = if (document.lineCount == 0) 0 else document.getLineEndOffset(n - 1)
            editor.caretModel.moveToOffset(minOf(start(line) + column - 1, end(line)))
            if (endLine != null) editor.selectionModel.setSelection(start(line), end(last))
            else editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            val range = if (last > line) "lines $line-$last" else "line $line"
            "Opened ${files.display(file)} at $range in the user's editor."
        }
    }

    /** Runs inline when already on the EDT (as in platform tests), where dispatching to it would deadlock. */
    private suspend fun <T> onEdt(action: () -> T): T =
        if (ApplicationManager.getApplication().isDispatchThread) action() else withContext(Dispatchers.EDT) { action() }
}
