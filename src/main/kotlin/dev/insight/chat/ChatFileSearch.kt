package dev.insight.chat

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.codeStyle.NameUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

/** One path snapshot shared by all chat panes; content edits do not invalidate file names. */
@Service(Service.Level.PROJECT)
internal class ChatFileSearch(private val project: Project, val scope: CoroutineScope) {
    private data class Snapshot(val structure: Long, val roots: Long, val paths: List<String>)
    private val mutex = Mutex()
    private var snapshot: Snapshot? = null

    suspend fun search(query: String): List<String> = withContext(Dispatchers.Default) {
        val paths = mutex.withLock {
            readAction {
                val structure = VirtualFileManager.getInstance().structureModificationCount
                val roots = ProjectRootModificationTracker.getInstance(project).modificationCount
                snapshot?.takeIf { it.structure == structure && it.roots == roots }?.paths ?: run {
                    val files = ProjectFiles(project)
                    files.contentFiles(null, null).map(files::display).distinct().also {
                        snapshot = Snapshot(structure, roots, it)
                    }
                }
            }
        }
        val context = coroutineContext
        rankChatFiles(query, paths) { context.ensureActive() }
    }
}

/** Bounded ranking: prefer file names, then paths; accept camel humps and partial letters. */
internal fun rankChatFiles(
    query: String,
    paths: List<String>,
    limit: Int = 50,
    checkCanceled: () -> Unit = {},
): List<String> {
    if (limit <= 0) return emptyList()
    val pattern = query.trim().replace('\\', '/')
    if (pattern.isEmpty()) return paths.take(limit)
    val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    data class Match(val path: String, val tier: Int, val degree: Int)
    val order = compareBy<Match> { it.tier }.thenByDescending { it.degree }
        .thenBy { it.path.length }.thenBy { it.path }
    val best = PriorityQueue(limit, order.reversed())
    paths.forEachIndexed { index, path ->
        if (index % 128 == 0) checkCanceled()
        val name = path.substringAfterLast('/')
        val tier = when {
            name.equals(pattern, ignoreCase = true) -> 0
            name.startsWith(pattern, ignoreCase = true) -> 1
            name.contains(pattern, ignoreCase = true) -> 2
            matcher.matches(name) -> 3
            path.contains(pattern, ignoreCase = true) -> 4
            matcher.matches(path) -> 5
            else -> return@forEachIndexed
        }
        val match = Match(path, tier, matcher.matchingDegree(if (tier <= 3) name else path))
        if (best.size < limit) best.add(match)
        else if (order.compare(match, best.peek()) < 0) { best.poll(); best.add(match) }
    }
    return best.sortedWith(order).map { it.path }
}
