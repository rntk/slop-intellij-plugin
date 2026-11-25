package dev.insight.knowledge.ide

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.AnchorKind
import dev.insight.knowledge.AnchorResolver
import dev.insight.knowledge.AnchorState
import dev.insight.knowledge.ClaimAnchorer
import dev.insight.links.ProjectSymbols
import dev.insight.links.SymbolHints
import dev.insight.links.SymbolMention
import dev.insight.links.rankSymbolCandidates
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReportTargetKind
import dev.insight.review.findReportTextLinks
import dev.insight.review.ui.collectProjectReportSnapshot

/**
 * Finds the code a piece of Markdown prose is about, with the same link resolution the report viewer uses: Markdown links
 * and bare paths to project files and directories (relative ones resolved against [docPath]'s directory) and code-shaped
 * symbol mentions. Anchors come back deduplicated in order of appearance, each carrying its current fingerprint from
 * [resolver]; anything that no longer resolves, and ambiguous file names, are skipped.
 *
 * A symbol becomes a SYMBOL anchor with the qualified ref of its best-ranked declaration (`pkg.Owner.name`). An unqualified
 * name with more than [MAX_AMBIGUOUS] declarations that the text gives no file hint for is too generic to anchor and is skipped.
 */
class ProjectClaimAnchorer(
    private val project: Project,
    private val resolver: AnchorResolver = PsiAnchorResolver(project),
) : ClaimAnchorer {
    private class Cached(val stamp: Long, val directory: String?, val index: ReportProjectIndex)

    @Volatile private var cached: Cached? = null

    override suspend fun anchors(text: String, docPath: String?): List<Anchor> {
        if (text.isBlank()) return emptyList()
        val document = docPath?.let(::normalizeProjectPath)?.takeIf { it.isNotEmpty() }
        val index = projectIndex(document?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() })
        val names = ProjectSymbols.names(project)
        val links = findReportTextLinks(text, index, names, document).map { it.link }
        val raw = LinkedHashSet<Anchor>()
        for (link in links) {
            when (link.kind) {
                ReportTargetKind.FILE -> link.candidates.singleOrNull()?.takeIf { it != document }?.let { raw += Anchor(AnchorKind.FILE, it) }
                ReportTargetKind.DIRECTORY -> link.candidates.singleOrNull()?.let { raw += Anchor(AnchorKind.DIRECTORY, it) }
                ReportTargetKind.SYMBOL -> {
                    val mention = link.symbol ?: continue
                    symbolRef(mention, link.symbolHints ?: SymbolHints())?.let { raw += Anchor(AnchorKind.SYMBOL, it) }
                }
                ReportTargetKind.EXTERNAL_URL -> {}
            }
            if (raw.size >= MAX_ANCHORS) break
        }
        return raw.mapNotNull { anchor ->
            when (val state = resolver.resolve(anchor)) {
                is AnchorState.Present -> anchor.copy(fingerprint = state.fingerprint)
                is AnchorState.Moved -> anchor.copy(ref = state.newRef, fingerprint = state.fingerprint)
                AnchorState.Gone -> null
            }
        }.distinctBy { it.kind to it.ref }
    }

    private suspend fun symbolRef(mention: SymbolMention, hints: SymbolHints): String? {
        val candidates = ProjectSymbols.find(project, mention.name)
        val ranked = rankSymbolCandidates(candidates, mention, hints, MAX_AMBIGUOUS + 1)
        val best = ranked.firstOrNull() ?: return null
        if (mention.qualifier.isEmpty() && ranked.size > MAX_AMBIGUOUS && best.path !in hints.files) return null
        return smartReadAction(project) {
            SymbolLookup.locate(project, best)?.let { (it.pkg + it.owners + best.name).joinToString(".") }
        }
    }

    /** The project's file index, rebuilt only when the virtual file system or the directory to resolve against changes. */
    private fun projectIndex(directory: String?): ReportProjectIndex {
        val stamp = VirtualFileManager.getInstance().modificationCount
        cached?.takeIf { it.stamp == stamp && it.directory == directory }?.let { return it.index }
        val index = collectProjectReportSnapshot(project, emptyList(), directory).index
        cached = Cached(stamp, directory, index)
        return index
    }

    private companion object {
        const val MAX_ANCHORS = 100
        const val MAX_AMBIGUOUS = 3
    }
}
