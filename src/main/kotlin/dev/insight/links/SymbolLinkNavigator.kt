package dev.insight.links

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JList

/**
 * Follows a symbol link from any view: looks up the mention's declarations, opens the only one, or lists several the way
 * Find Usages does, most likely first. Views decide what opening means through [open] and how a miss is reported through
 * [notice]; by default a declaration opens in the editor. [preferred] may pick one of several declarations without asking,
 * such as one the view already shows.
 */
object SymbolLinkNavigator {
    private val LOG = logger<SymbolLinkNavigator>()

    fun follow(
        project: Project,
        scope: CoroutineScope,
        mention: SymbolMention,
        hints: SymbolHints,
        at: () -> RelativePoint,
        notice: (String) -> Unit,
        relativePath: (SymbolCandidate) -> String = { it.path },
        preferred: (List<SymbolCandidate>) -> SymbolCandidate? = { null },
        open: (SymbolCandidate) -> Unit = { openDeclaration(project, it) },
    ) {
        scope.launch(Dispatchers.EDT) {
            try {
                val found = ProjectSymbols.find(project, mention.name).map { it.copy(path = relativePath(it)) }
                if (project.isDisposed) return@launch
                val ranked = rankSymbolCandidates(found, mention, hints)
                when (ranked.size) {
                    0 -> notice("No declaration of ${mention.name} found in project sources")
                    1 -> open(ranked.single())
                    else -> preferred(ranked)?.let(open) ?: choose(mention.name, ranked, at(), open)
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to find declarations of ${mention.name}", e)
                notice("Unable to find declarations of ${mention.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Lists [candidates] in a popup at [point] and passes the chosen one to [onChosen]. */
    fun choose(name: String, candidates: List<SymbolCandidate>, point: RelativePoint, onChosen: (SymbolCandidate) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setTitle(symbolCandidatesTitle(name, candidates.size))
            .setRenderer(CandidateRenderer())
            .setNamerForFiltering { "${it.container.orEmpty()} ${it.name} ${it.path}" }
            .setItemChosenCallback(onChosen)
            .createPopup()
            .show(point)
    }

    fun openDeclaration(project: Project, candidate: SymbolCandidate) {
        val file = LocalFileSystem.getInstance().findFileByPath(candidate.absolutePath) ?: return
        OpenFileDescriptor(project, file, (candidate.line - 1).coerceAtLeast(0), (candidate.column - 1).coerceAtLeast(0)).navigate(true)
    }

    private class CandidateRenderer : ColoredListCellRenderer<SymbolCandidate>() {
        override fun customizeCellRenderer(list: JList<out SymbolCandidate>, value: SymbolCandidate, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(value.name)
            append("  " + symbolCandidateDetail(value), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  " + symbolCandidateLocation(value), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
