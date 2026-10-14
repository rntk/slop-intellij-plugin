package dev.insight.entrypoints

import dev.insight.apilog.ApiLogs
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.insight.callgraph.CallGraphResult
import dev.insight.chat.ProjectFiles
import dev.insight.chat.ToolInputException
import dev.insight.chat.projectTools
import dev.insight.enrichment.configuredLlmClient
import dev.insight.core.decisionsClient
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the project's entry points on the current code, reusing earlier work only where the files are unchanged. */
@Service(Service.Level.PROJECT)
class EntryPointService(private val project: Project, val scope: CoroutineScope) {
    val cache = EntryPointCache()
    val decisionsCache = EntryPointDecisionCache()
    private val files = ProjectFiles(project)

    /** [graph] supplies call-graph hints when the application graph is built; the search works without it. */
    suspend fun find(graph: CallGraphResult?, progress: EntryPointProgress = EntryPointProgress.NONE,
        api: EntryPointApi = EntryPointApi.LLM): EntryPointResult {
        val hints = graph?.let { built ->
            readAction {
                EntryPointHints.fromGraph(built.graph, { path ->
                    LocalFileSystem.getInstance().findFileByPath(path)?.takeIf(files.index::isInContent)?.let(files::display)
                })
            }
        }.orEmpty()
        val tools = projectTools(project).filter { it.definition.name in setOf(EntryPointFinder.LIST_TOOL, EntryPointFinder.READ_TOOL) }
        return withContext(Dispatchers.IO) {
            when (api) {
                EntryPointApi.LLM -> EntryPointFinder(configuredLlmClient()::complete, IdeSnapshot(files), tools, cache)
                    .find(project.name, hints, progress)
                EntryPointApi.DECISIONS -> DecisionsEntryPointFinder(
                    decisionsClient("local", requestTimeout = Duration.ofSeconds(120), maxRetries = 4,
                        traffic = ApiLogs.decisions)::evaluate,
                    IdeSnapshot(files),
                    { path -> readAction { files.text(files.resolve(path)) } },
                    cache = decisionsCache,
                ).find(project.name, hints, progress)
            }
        }
    }

    private class IdeSnapshot(private val files: ProjectFiles) : ProjectSnapshot {
        override suspend fun files(): List<String> = readAction { files.contentFiles(null, null).map(files::display) }

        override suspend fun identify(path: String): FileId? = readAction {
            val file = try { files.resolve(path) } catch (_: ToolInputException) { return@readAction null }
            if (file.isDirectory) return@readAction null
            // Binary or very large files are identified by size and timestamp instead of content.
            val content = files.text(file) ?: "${file.length}:${file.timeStamp}"
            FileId(files.display(file), EntryPointFinder.digest(content))
        }
    }
}
