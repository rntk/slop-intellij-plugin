package dev.insight.links

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import dev.insight.callgraph.ProjectGraphAnalyzer

/**
 * The project's declarations as the IDE's Go to Class and Go to Symbol find them, so mentions link in every language the IDE
 * indexes. [names] is the cheap set that decides what links; [find] looks up the declarations of one name when a link is followed.
 */
object ProjectSymbols {
    /** Names declared in project sources, cached until PSI changes. Empty while the project is indexing. */
    suspend fun names(project: Project): SymbolNames = smartReadAction(project) { namesNow(project) }

    /**
     * [names] inside a read action in smart mode. Without [filter], indexes offer every name they hold, libraries and the JDK
     * included; light tests pass null because their in-memory files have no ids to filter by. Collecting the names again
     * after an edit that declared nothing new returns the same instance, so callers can tell a change by identity.
     */
    internal fun namesNow(
        project: Project,
        filter: IdFilter? = if (ApplicationManager.getApplication()?.isUnitTestMode == true) null else IdFilter.getProjectIdFilter(project, false),
    ): SymbolNames =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(stableNames(project, collectNames(project, filter)), PsiModificationTracker.getInstance(project))
        }

    private fun stableNames(project: Project, names: Set<String>): SymbolNames {
        project.getUserData(LAST_NAMES)?.takeIf { it.first == names }?.let { return it.second }
        val next = if (names.isEmpty()) SymbolNames.NONE else SymbolNames { it in names }
        project.putUserData(LAST_NAMES, names to next)
        return next
    }

    /**
     * Declarations named [name] in project sources, unordered and at most [limit]. The limit only bounds the scan; callers
     * rank the result with [rankSymbolCandidates], which keeps the most likely [MAX_LISTED_CANDIDATES].
     */
    suspend fun find(project: Project, name: String, limit: Int = MAX_CANDIDATES): List<SymbolCandidate> =
        smartReadAction(project) { findNow(project, name, limit) }

    /** [find] inside a read action in smart mode. */
    internal fun findNow(project: Project, name: String, limit: Int = MAX_CANDIDATES): List<SymbolCandidate> {
        val scope = GlobalSearchScope.projectScope(project)
        val found = LinkedHashMap<SymbolCandidate.Identity, SymbolCandidate>()
        val process = { item: NavigationItem ->
            ProgressManager.checkCanceled()
            candidate(project, item, name)?.let { found.putIfAbsent(it.identity, it) }
            found.size < limit
        }
        for (contributor in contributors()) {
            if (found.size >= limit) break
            if (contributor is ChooseByNameContributorEx) {
                contributor.processElementsWithName(name, { process(it) }, FindSymbolParameters.wrap(name, scope))
            } else {
                for (item in contributor.getItemsByName(name, name, project, false)) if (!process(item)) break
            }
        }
        return found.values.toList()
    }

    private fun collectNames(project: Project, filter: IdFilter?): Set<String> {
        val scope = GlobalSearchScope.projectScope(project)
        val names = HashSet<String>()
        for (contributor in contributors()) {
            if (contributor is ChooseByNameContributorEx) {
                val contributorFilter = if (contributor.javaClass.name.startsWith("org.rust.")) null else filter
                contributor.processNames({ ProgressManager.checkCanceled(); names += it; true }, scope, contributorFilter)
            } else {
                names += contributor.getNames(project, false)
            }
        }
        return names
    }

    private fun contributors(): List<ChooseByNameContributor> =
        (ChooseByNameContributor.CLASS_EP_NAME.extensionList + ChooseByNameContributor.SYMBOL_EP_NAME.extensionList).distinct()

    private fun candidate(project: Project, item: NavigationItem, name: String): SymbolCandidate? {
        val raw = (item as? PsiElement) ?: (item as? PsiElementNavigationItem)?.targetElement ?: return null
        val element = ProjectGraphAnalyzer.source(raw).takeIf { it.isValid } ?: return null
        if (element is PsiFile) return null
        val file = element.containingFile ?: return null
        val virtual = file.virtualFile ?: return null
        if (!ProjectFileIndex.getInstance(project).isInContent(virtual)) return null
        val offset = element.textOffset.coerceAtLeast(0)
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val line = document?.takeIf { offset <= it.textLength }?.getLineNumber(offset) ?: file.text.take(offset).count { it == '\n' }
        val column = document?.let { offset - it.getLineStartOffset(line) } ?: 0
        val presentation = item.presentation ?: (element as? NavigationItem)?.presentation
        val container = presentation?.locationString?.trim()?.removeSurrounding("(", ")")?.removePrefix("in ")?.takeIf { it.isNotBlank() }
        val base = project.basePath?.trimEnd('/')
        val path = if (base != null && virtual.path.startsWith("$base/")) virtual.path.removePrefix("$base/") else virtual.path
        return SymbolCandidate(
            name = (element as? PsiNamedElement)?.name ?: item.name ?: name,
            kind = ProjectGraphAnalyzer.kind(element),
            container = container,
            path = path,
            absolutePath = virtual.path,
            line = line + 1,
            column = column + 1,
        )
    }

    private const val MAX_CANDIDATES = 2_000
    private val LAST_NAMES = Key.create<Pair<Set<String>, SymbolNames>>("insight.projectSymbolNames")
}
