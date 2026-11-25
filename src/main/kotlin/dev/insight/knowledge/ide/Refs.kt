package dev.insight.knowledge.ide

/**
 * Parsing and matching of anchor refs, free of IDE types so it can be tested without a project.
 *
 * A symbol ref is one of
 *  - `dev.insight.diff.walk.WalkModel`: a dotted name, qualified by packages and owner types;
 *  - `WalkthroughWorkflow.run` or `Foo::bar` or `Foo#bar`: an owner and a member;
 *  - `WalkModel.kt#WalkModel` or `src/a/Foo.java#Foo.bar`: the same, pinned to a file (the path may be a suffix).
 * A trailing parameter list (`run()`, `run(a, b)`) is ignored.
 */
internal data class SymbolRef(val path: String?, val qualifiers: List<String>, val name: String) {
    /** The ref as text: `path#a.b.name` when pinned to a file, else `a.b.name`. */
    fun render(): String {
        val dotted = (qualifiers + name).joinToString(".")
        return if (path != null) "$path#$dotted" else dotted
    }

    companion object {
        private val EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "scala", "groovy", "cs", "cpp", "cc", "c", "h", "hpp",
            "rb", "php", "swift", "dart", "xml", "md",
        )
        private val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

        /** Null when [ref] is not shaped like a symbol ref. */
        fun parse(ref: String): SymbolRef? {
            var text = ref.trim()
            if (text.isEmpty()) return null
            val paren = text.indexOf('(')
            if (paren >= 0) text = text.substring(0, paren).trim()
            var path: String? = null
            val hash = text.indexOf('#')
            if (hash > 0 && looksLikePath(text.substring(0, hash))) {
                path = normalizeProjectPath(text.substring(0, hash)).ifEmpty { null }
                text = text.substring(hash + 1)
            }
            val segments = text.replace("::", ".").replace('#', '.').split('.').filter { it.isNotEmpty() }
            if (segments.isEmpty() || !segments.all { IDENTIFIER.matches(it) }) return null
            return SymbolRef(path, segments.dropLast(1), segments.last())
        }

        private fun looksLikePath(text: String): Boolean {
            if ('/' in text) return true
            val dot = text.lastIndexOf('.')
            return dot > 0 && text.substring(dot + 1).lowercase() in EXTENSIONS
        }
    }
}

/** `caller->callee`, each side a symbol ref. */
internal data class CallEdgeRef(val caller: String, val callee: String) {
    companion object {
        fun parse(ref: String): CallEdgeRef? {
            val at = ref.indexOf("->")
            if (at <= 0) return null
            val caller = ref.substring(0, at).trim()
            val callee = ref.substring(at + 2).trim()
            return if (caller.isEmpty() || callee.isEmpty()) null else CallEdgeRef(caller, callee)
        }
    }
}

/** How a declaration relates to the ref that names it. */
internal enum class RefMatch {
    /** Same qualifiers (and file, when the ref pins one). */
    EXACT,
    /** Same name and owner types, but another package or file: the symbol moved. */
    DRIFT,
    NONE,
}

/**
 * Compares [ref] with a declaration found under [path], in [pkg] (package segments), owned by [owners] (enclosing types,
 * outermost first) in a file called [fileBase] (without extension). Qualifiers starting upper case are taken as types and
 * must be owners (or the file's name, for top-level members); lower case qualifiers are packages and may drift.
 */
internal fun matchRef(ref: SymbolRef, path: String, pkg: List<String>, owners: List<String>, fileBase: String): RefMatch {
    val q = ref.qualifiers
    val pathOk = ref.path == null || path == ref.path || path.endsWith("/" + ref.path)
    fun suffixOf(chain: List<String>) = q.size <= chain.size && chain.takeLast(q.size) == q
    if (pathOk && (suffixOf(pkg + owners) || suffixOf(pkg + fileBase + owners))) return RefMatch.EXACT
    val known = owners.toSet() + fileBase
    val types = q.filter { it.first().isUpperCase() }
    return if (types.all { it in known }) RefMatch.DRIFT else RefMatch.NONE
}

/** A project-relative path with `/` separators and no leading `./` or `/` or trailing `/`; `""` is the project root. */
internal fun normalizeProjectPath(path: String): String {
    var p = path.trim().replace('\\', '/')
    while (p.startsWith("./")) p = p.substring(2)
    p = p.trim('/')
    return if (p == ".") "" else p
}
