package dev.insight.context.actions

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import dev.insight.InsightBundle
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.openCallGraph
import dev.insight.callgraph.openFlowChart
import dev.insight.canvas.OpenCanvasSessionAction
import dev.insight.codereview.findCodeScopeFile
import dev.insight.codereview.showInSummaryTree
import dev.insight.codereview.openOnCodeWall
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextScopeKind
import dev.insight.context.location
import dev.insight.graph.SymbolKind
import dev.insight.ui.SourceChainLocation
import dev.insight.ui.openSourceChain

/** The directory or file on canvas, with captured file diffs opened by their host. */
internal class OpenCanvasSessionContextAction : ContextAction {
    override val id = "explore.canvas"
    override val title get() = InsightBundle.message("context.action.canvas")
    override val group = "Explore"
    override val order = 10

    override fun isAvailable(request: ContextRequest) = canOpenOnCanvas(request.facts())

    override fun perform(request: ContextRequest) {
        if (request.context.kind == ContextScopeKind.DIRECTORY) {
            val directory = request.data[ContextKeys.VIRTUAL_FILE]?.takeIf { it.isValid && it.isDirectory }
            if (directory != null) return OpenCanvasSessionAction().open(request.project, directory)
            val path = request.context.absolutePath ?: return
            resolveThenShow(request.project,
                InsightBundle.message("context.action.missing.file", request.context.location()),
                resolve = { findCodeScopeFile(request.project, path, directory = true) },
                show = { OpenCanvasSessionAction().open(request.project, it) })
            return
        }
        request.data[ContextKeys.OPEN_ON_CANVAS]?.let { return it(request.context) }
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", request.context.location()))
        OpenCanvasSessionAction().open(request.project, file)
    }
}

/** The call graph of the function the context covers. */
internal class ShowCallGraphContextAction : ContextAction {
    override val id = "explore.callGraph"
    override val title get() = InsightBundle.message("context.action.call.graph")
    override val group = "Explore"
    override val order = 20

    override fun isAvailable(request: ContextRequest) = canShowCallGraph(request.facts())

    override fun perform(request: ContextRequest) {
        val context = request.context
        val symbol = context.symbol ?: return
        val lines = context.lines ?: return
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", context.location()))
        val project = request.project
        resolveThenShow(project, InsightBundle.message("context.action.missing.symbol", symbol.name, context.path ?: file.name),
            resolve = {
                declarationAt(project, file, lines.first, symbol, CallGraphAnalyzer::findFunction)
                    ?.let { it to CallGraphAnalyzer.describe(project, it, 0, 0).name }
            },
            show = { (function, name) -> openCallGraph(project, function, name) })
    }
}

/** The declaration the context covers, added to the Application Graph's flow chart. */
internal class AddToFlowChartContextAction : ContextAction {
    override val id = "explore.flowChart"
    override val title get() = InsightBundle.message("context.action.flow.chart")
    override val group = "Explore"
    override val order = 30

    override fun isAvailable(request: ContextRequest) = canAddToFlowChart(request.facts())

    override fun perform(request: ContextRequest) {
        val context = request.context
        val symbol = context.symbol ?: return
        val lines = context.lines ?: return
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", context.location()))
        val project = request.project
        resolveThenShow(project, InsightBundle.message("context.action.missing.symbol", symbol.name, context.path ?: file.name),
            resolve = {
                declarationAt(project, file, lines.first, symbol) { element: PsiElement ->
                    element.takeIf { ProjectGraphAnalyzer.kind(it)?.name?.lowercase() == symbol.kind }
                }?.let { SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it) }
            },
            show = { pointer -> openFlowChart(project, pointer) })
    }
}

/** The context's lines of its file in a one-column source chain. */
internal class OpenSideBySideContextAction : ContextAction {
    override val id = "explore.sideBySide"
    override val title get() = InsightBundle.message("context.action.side.by.side")
    override val group = "Explore"
    override val order = 40

    override fun isAvailable(request: ContextRequest) = canOpenSideBySide(request.facts())

    override fun perform(request: ContextRequest) {
        val context = request.context
        val lines = context.lines ?: return
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", context.location()))
        // Source chain lines are 0-based.
        val first = (lines.first - 1).coerceAtLeast(0)
        val location = SourceChainLocation(context.path ?: file.name, context.label, first, (lines.last - 1).coerceAtLeast(first), file)
        openSourceChain(request.project, listOf(location), "${file.name} · ${context.label}")
    }
}

/** The file in the Code Review summaries, in the Tree view. */
internal class ShowInSummaryTreeContextAction : ContextAction {
    override val id = "explore.summaryTree"
    override val title get() = InsightBundle.message("context.action.summary.tree")
    override val group = "Explore"
    override val order = 50

    override fun isAvailable(request: ContextRequest) = canShowInSummaryTree(request.facts())

    override fun perform(request: ContextRequest) {
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", request.context.location()))
        showInSummaryTree(request.project, file)
    }
}

internal class OpenDiffsWallContextAction : ContextAction {
    override val id = "explore.diffsWall"
    override val title get() = InsightBundle.message("context.action.diffs.wall")
    override val group = "Explore"
    override val order = 11
    override fun isAvailable(request: ContextRequest) = request.origin != ContextOrigin.DIFFS_WALL &&
        ContextKeys.OPEN_ON_DIFFS_WALL in request.data && request.context.files.isNotEmpty()
    override fun perform(request: ContextRequest) { request.data[ContextKeys.OPEN_ON_DIFFS_WALL]?.invoke(request.context) }
}

internal class OpenCodeWallContextAction : ContextAction {
    override val id = "explore.codeWall"
    override val title get() = InsightBundle.message("context.action.code.wall")
    override val group = "Explore"
    override val order = 12
    override fun isAvailable(request: ContextRequest) = request.origin != ContextOrigin.CODE_WALL &&
        ((ContextKeys.OPEN_ON_CODE_WALL in request.data && request.context.files.isNotEmpty()) || canOpenCodeWall(request.facts()))
    override fun perform(request: ContextRequest) {
        request.data[ContextKeys.OPEN_ON_CODE_WALL]?.let { return it(request.context) }
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", request.context.location()))
        openOnCodeWall(request.project, file)
    }
}
