package dev.insight.review

/**
 * The files a word chart counts: one file, the files beneath a directory, or every file when [path] is empty. [all] names
 * the scope of every file.
 */
internal data class WordScope(val path: String, val directory: Boolean, val files: Int, val all: String = "All changed files") {
    fun contains(file: String) = path.isEmpty() || if (directory) file.startsWith("$path/") else file == path

    /** The name typing filters the scope list by. */
    val filterName get() = path.ifEmpty { all }

    override fun toString() = when {
        path.isEmpty() -> "$all · $files"
        directory -> "$path/ · $files"
        else -> path
    }
}

/** Every file of [paths] and every directory above one, after the scope of all files; a directory comes before its contents. */
internal fun pathWordScopes(paths: List<String>, all: String): List<WordScope> {
    val directories = hashMapOf<String, Int>()
    paths.forEach { path ->
        var end = path.lastIndexOf('/')
        while (end > 0) {
            val directory = path.substring(0, end)
            directories.merge(directory, 1, Int::plus)
            end = directory.lastIndexOf('/')
        }
    }
    val scopes = directories.map { (path, count) -> WordScope(path, true, count, all) } + paths.map { WordScope(it, false, 1, all) }
    return listOf(WordScope("", true, paths.size, all)) + scopes.sortedWith(compareBy({ it.path.replace('/', '\u0000') }, { !it.directory }))
}
