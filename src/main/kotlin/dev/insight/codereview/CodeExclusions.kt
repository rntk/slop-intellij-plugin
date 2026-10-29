package dev.insight.codereview

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo

private val LOG = logger<CodeExclusions>()

/**
 * Patterns that leave files and folders out of a scope, like a `.gitignore` without negation:
 * - a pattern without `/`, such as `*.js`, `test` or `generated/`, matches the name of a file or folder at any depth;
 * - a pattern with `/`, such as `src/legacy`, `web/**/*.js` or `src/a.kt`, matches the project-relative path, or for a folder
 *   scope the path below [base];
 * - a trailing `/` matches folders only; `*` and `?` stay within one name, `**` spans folders, `{a,b}` and `[abc]` choose.
 *
 * An excluded folder excludes everything under it.
 */
internal class CodeExclusions(val patterns: List<String>, private val base: String = "") {
    private class Pattern(val regex: Regex, val name: Boolean, val folderOnly: Boolean)

    private val compiled = patterns.map(::compile)

    val isEmpty: Boolean get() = compiled.isEmpty()

    /** Whether the file or folder at the project-relative [path] matches a pattern itself. */
    fun matches(path: String, directory: Boolean): Boolean {
        if (compiled.isEmpty() || path.isEmpty()) return false
        val below = if (base.isNotEmpty() && path.startsWith("$base/")) path.removePrefix("$base/") else null
        return compiled.any { pattern ->
            when {
                pattern.folderOnly && !directory -> false
                pattern.name -> pattern.regex.matches(path.substringAfterLast('/'))
                else -> pattern.regex.matches(path) || below != null && pattern.regex.matches(below)
            }
        }
    }

    /** Whether the file at the project-relative [path], or a folder holding it below [from], matches a pattern. */
    fun excludes(path: String, from: String = ""): Boolean {
        if (compiled.isEmpty()) return false
        if (matches(path, directory = false)) return true
        var folder = path.substringBeforeLast('/', "")
        while (folder.isNotEmpty() && folder != from && (from.isEmpty() || folder.startsWith("$from/"))) {
            if (matches(folder, directory = true)) return true
            folder = folder.substringBeforeLast('/', "")
        }
        return false
    }

    companion object {
        val NONE = CodeExclusions(emptyList())

        /**
         * The patterns in [text], separated by commas, semicolons or new lines; commas inside `{}`, and characters after `\`,
         * belong to the pattern.
         * Throws [IllegalArgumentException] with a message for the user for an invalid pattern.
         */
        fun parse(text: String): List<String> {
            val patterns = mutableListOf<String>()
            val current = StringBuilder()
            var braces = 0
            var escaped = false
            for (c in text) {
                when {
                    escaped -> { escaped = false; current.append(c) }
                    c == '\\' -> { escaped = true; current.append(c) }
                    c == '{'-> { braces++; current.append(c) }
                    c == '}' -> { braces--; current.append(c) }
                    braces <= 0 && (c == ',' || c == ';' || c == '\n') -> { patterns += current.toString(); current.clear() }
                    else -> current.append(c)
                }
            }
            patterns += current.toString()
            return patterns.map(::normalize).filter { it.isNotEmpty() }.distinct().onEach(::compile)
        }

        /** The text of [patterns] for the exclude field. */
        fun format(patterns: List<String>): String = patterns.joinToString(", ")

        /** A pattern matching exactly the project-relative [path], a folder when [directory]. */
        fun exact(path: String, directory: Boolean): String {
            val escaped = escape(path)
            // A path without a slash would match the name at any depth; anchor it to the project root.
            return (if ('/' in escaped) escaped else "/$escaped") + if (directory) "/" else ""
        }

        /** A pattern matching the files with [extension] at any depth under the project-relative [folder], "" for all. */
        fun ofType(folder: String, extension: String): String =
            (if (folder.isEmpty()) "" else escape(folder) + "/**/") + "*." + escape(extension)

        private fun escape(text: String) = text.replace(Regex("""[\\*?\[\]{},;]""")) { "\\" + it.value }

        /** A backslash before a special character escapes it; elsewhere it is a Windows separator. */
        private fun normalize(pattern: String): String =
            pattern.trim().replace(Regex("""\\(?![\\*?\[\]{},;])"""), "/").removePrefix("./")

        private fun compile(pattern: String): Pattern {
            require("/../" !in "/${pattern.trimEnd('/')}/") { "An exclude pattern must not contain '..': $pattern" }
            val folderOnly = pattern.endsWith("/")
            val body = pattern.trimEnd('/')
            require(body.isNotEmpty() && body != "/") { "An exclude pattern must name a file or folder: $pattern" }
            // A leading / anchors a pattern to the project root, like a pattern with a slash inside.
            val name = '/' !in body
            val regex = try {
                Regex(globRegex(body.removePrefix("/")), if (SystemInfo.isFileSystemCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                LOG.debug("Invalid exclude pattern $pattern", e)
                throw IllegalArgumentException("$pattern is not a valid exclude pattern.")
            }
            return Pattern(regex, name, folderOnly)
        }

        private fun globRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            var braces = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '\\' && i + 1 < glob.length -> { out.append(Regex.escape(glob[i + 1].toString())); i++ }
                    c == '*' && glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 2 }
                    c == '*' && glob.startsWith("**", i) -> { out.append(".*"); i++ }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    c == '{' -> { braces++; out.append("(?:") }
                    c == '}' && braces > 0 -> { braces--; out.append(')') }
                    c == ',' && braces > 0 -> out.append('|')
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 2)
                        require(end > 0) { "unclosed [" }
                        val set = glob.substring(i + 1, end).let { if (it.startsWith("!")) "^" + it.drop(1) else it }
                        out.append('[').append(set.replace("\\", "\\\\").replace("[", "\\[")).append(']')
                        i = end
                    }
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            require(braces == 0) { "unclosed {" }
            return out.toString()
        }
    }
}
