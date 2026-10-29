package dev.insight.chat

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.insight.agent.ChatTool
import dev.insight.agent.FileTraceContext
import dev.insight.agent.ProjectFiles
import dev.insight.agent.SourcePoint
import dev.insight.agent.SourceSide
import dev.insight.agent.ToolInputException
import dev.insight.agent.TraceFile
import dev.insight.agent.openFileTool
import dev.insight.agent.projectContextTools
import dev.insight.agent.sourcePointTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Tools over the project's content files (sources, tests, and resources; excluded directories such
 * as build output are skipped). Paths are relative to the project directory. Lookups run in
 * cancellable read actions, so they neither block the EDT nor delay edits. Presentation tools show
 * locations in the editor without modifying project files.
 */
fun projectTools(project: Project): List<ChatTool> = projectTools(project, SourcePoints(project, "Project chat"))

internal fun projectTools(project: Project, sourcePoints: SourcePoints): List<ChatTool> {
    val files = ProjectFiles(project)
    return projectContextTools(project) + listOf(openFileTool(files), sourcePointTool { point ->
        showProjectSource(project, sourcePoints, point)
    })
}

/** Shared by project chat and review chats when a location is outside the captured snapshot. */
internal suspend fun showProjectSource(project: Project, sourcePoints: SourcePoints, point: SourcePoint): String {
    if (point.side == SourceSide.BEFORE) throw ToolInputException(
        "${point.path} has no before-side in this snapshot. Omit side to show current project content.")
    val files = ProjectFiles(project)
    val (path, text) = readAction {
        val file = files.resolve(point.path)
        if (file.isDirectory) throw ToolInputException("${point.path} is a directory; use list_files.")
        files.display(file) to (files.text(file) ?: throw ToolInputException("${point.path} is binary or too large to read."))
    }
    currentCoroutineContext()[FileTraceContext]?.trace?.register(listOf(TraceFile(path, project = text)))
    val location = point.copy(path = path, side = null)
    val result = withContext(Dispatchers.EDT) { sourcePoints.show(location, text) }
    return "$result Current project content (including unsaved edits)."
}
