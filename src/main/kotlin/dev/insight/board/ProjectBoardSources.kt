package dev.insight.board

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.insight.agent.ProjectFiles
import dev.insight.agent.ToolInputException

/** Board sources over current project files (including unsaved edits) and the call graph through [graph] (loaded on first use); no comparison. */
class ProjectBoardSources(project: Project, private val graph: BoardGraph) : BoardSources {
    private val files = ProjectFiles(project)

    override val hasComparison: Boolean get() = false

    override suspend fun read(anchor: BoardAnchor): ResolvedSource {
        if (anchor.side == BoardSide.BEFORE) throw ToolInputException("There is no before side outside a comparison; anchors read current files.")
        val (path, text) = readAction {
            val file = files.resolve(anchor.path)
            if (file.isDirectory) throw ToolInputException("'${anchor.path}' is a directory, not a file.")
            files.display(file) to (files.text(file) ?: throw ToolInputException("'${anchor.path}' is binary or too large to show."))
        }
        return sourceExcerpt(anchor.copy(path = path), text)
    }

    override suspend fun diff(path: String, first: Int?, last: Int?): ResolvedSource =
        throw ToolInputException("This board explains current code, not a comparison, so there is no diff; use add_code.")

    override suspend fun symbols(name: String, path: String?): List<ResolvedSymbol> = requireGraph().symbols(name, path)
    override suspend fun symbol(node: Int): ResolvedSymbol? = requireGraph().symbol(node)
    override suspend fun chain(from: Int, to: Int): ResolvedChain = requireGraph().chain(from, to)
    override suspend fun callees(node: Int, limit: Int): List<ResolvedSymbol> = requireGraph().callees(node, limit)

    private suspend fun requireGraph(): BoardGraphSources = graph() ?: throw noCallGraph()
}
