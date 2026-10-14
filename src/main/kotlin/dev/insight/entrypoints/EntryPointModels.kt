package dev.insight.entrypoints

enum class EntryPointApi(val label: String) { LLM("LLM API"), DECISIONS("Decisions API") }

enum class EntryPointKind(val label: String) {
    APPLICATION("Application"), SERVER("Server"), CLI("Command line"), SCRIPT("Script"), PLUGIN("Plugin / extension"),
    HANDLER("Handler"), JOB("Scheduled job"), BUILD("Build / run task"), CONTAINER("Container"), OTHER("Other");

    override fun toString() = label

    companion object {
        fun fromWire(raw: String?): EntryPointKind =
            entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) } ?: OTHER
    }
}

/** A place where execution starts. [path] is project-relative; [line] is 1-based. */
data class EntryPoint(
    val path: String,
    val line: Int,
    val symbol: String,
    val kind: EntryPointKind,
    val title: String,
    val description: String,
    val confidence: Double,
)

/** What the model concluded about one file it read, kept while the file's content is unchanged. */
data class FileNote(val path: String, val hash: String, val note: String, val entryPoints: List<EntryPoint>)

data class EntryPointResult(
    val entryPoints: List<EntryPoint>,
    val summary: String,
    /** Files read by the model in this run, project-relative. */
    val filesRead: List<String>,
    /** Files whose earlier LLM notes or Decisions judgments were reused without new API requests. */
    val filesReused: List<String>,
    val requestCount: Int,
    /** True when the whole answer came from the cache without contacting the model. */
    val cached: Boolean,
)

/** A project file identified by its canonical project-relative path and a hash of its current content. */
data class FileId(val path: String, val hash: String, val lineCount: Int? = null)

/** The project as the finder sees it. Paths are project-relative. */
interface ProjectSnapshot {
    /** Content files in path order (build output and excluded folders skipped). */
    suspend fun files(): List<String>

    /** Resolves a path as the model wrote it to the canonical path and current content hash; null if it is no project file. */
    suspend fun identify(path: String): FileId?
}

fun interface EntryPointProgress {
    fun report(message: String)

    companion object {
        val NONE = EntryPointProgress { }
    }
}
