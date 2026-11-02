package dev.insight.diff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.insight.canvas.CanvasSession
import dev.insight.canvas.CanvasSessionController
import dev.insight.canvas.CanvasSessionFile
import dev.insight.canvas.CanvasSessionModel
import dev.insight.canvas.openCanvasSessionFile
import dev.insight.canvas.relativePath
import dev.insight.graph.RelationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A resolved cross-file relation, with declaration offsets on both sides for incoming as well as outgoing arrows. */
internal data class DiffFileConnection(val from: String, val to: String, val fromOffset: Int, val toOffset: Int)
internal data class RelatedDiffFile(val path: String, val parent: String, val offset: Int)

/** Walk both directions, once per changed file. Each card is attached to the file that led to it. */
internal fun relatedDiffFiles(root: String, connections: List<DiffFileConnection>): List<RelatedDiffFile> {
    val neighbours = linkedMapOf<String, MutableList<Pair<String, Int>>>()
    connections.forEach {
        if (it.from != it.to) {
            neighbours.getOrPut(it.from) { mutableListOf() } += it.to to it.fromOffset
            neighbours.getOrPut(it.to) { mutableListOf() } += it.from to it.toOffset
        }
    }
    val seen = hashSetOf(root)
    val queue = ArrayDeque<String>().apply { add(root) }
    return buildList {
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            neighbours[parent].orEmpty().sortedBy { it.first }.forEach { (path, offset) ->
                if (seen.add(path)) {
                    add(RelatedDiffFile(path, parent, offset))
                    queue.add(path)
                }
            }
        }
    }
}

internal data class DiffCanvasSession(val file: CanvasSessionFile, val unavailable: List<String>)

/**
 * Builds a fresh exploration of this comparison, without touching another session's chats or cards. Only source
 * matching the after-side can contribute PSI evidence. Resolve every eligible changed file to find incoming links,
 * but open only the selected file's connected component; unchanged files never bridge two changed files.
 */
internal suspend fun prepareDiffCanvasSession(project: Project, snapshot: ReviewSnapshot, path: String): DiffCanvasSession {
    val selected = snapshot.files.firstOrNull { it.path == path } ?: error("$path is no longer in this diff.")
    val eligible = smartReadAction(project) {
        snapshot.files.mapNotNull { review ->
            if (review.status == "deleted" || review.after == null) return@mapNotNull null
            val file = LocalFileSystem.getInstance().findFileByPath(review.absolutePath) ?: return@mapNotNull null
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
            if (psi.text != StringUtil.convertLineSeparators(review.after)) return@mapNotNull null
            review.path to file
        }.toMap()
    }
    val source = eligible[selected.path]
        ?: error("${selected.path} has no current source matching the reviewed after-side. Refresh the diff or check out its target revision before opening it on canvas.")
    val opened = openCanvasSessionFile(project, source, store = null, saved = CanvasSession.new(
        relativePath(project, source.path), "${source.name} · ${snapshot.comparison.summary}"))
        ?: error("${selected.path} cannot be shown on canvas. Check that its language plugin is enabled.")
    val supported = linkedMapOf<String, VirtualFile>()
    for ((reviewPath, file) in eligible) {
        if (opened.exploration.fileSchematic(file) != null) supported[reviewPath] = file
    }
    val byAbsolute = supported.entries.associate { it.value.path to it.key }
    val connections = smartReadAction(project) {
        for (review in snapshot.files.filter { it.path in supported }) {
            check(PsiManager.getInstance(project).findFile(supported.getValue(review.path))?.text ==
                StringUtil.convertLineSeparators(checkNotNull(review.after))) {
                "${review.path} changed while preparing the canvas. Refresh the diff and retry."
            }
        }
        val result = opened.exploration.result
        result.graph.edges.mapNotNull { edge ->
            if (edge.kind !in setOf(RelationKind.CALL, RelationKind.REFERENCE, RelationKind.INHERITS)) return@mapNotNull null
            val from = result.graph.nodes[edge.caller]
            val to = result.graph.nodes[edge.callee]
            if (from.unresolved || to.unresolved) return@mapNotNull null
            val fromPath = byAbsolute[from.filePath] ?: return@mapNotNull null
            val toPath = byAbsolute[to.filePath] ?: return@mapNotNull null
            DiffFileConnection(fromPath, toPath, result.pointers.getOrNull(from.id)?.element?.textOffset ?: 0,
                result.pointers.getOrNull(to.id)?.element?.textOffset ?: 0)
        }.distinct()
    }
    val model = CanvasSessionModel(opened.session)
    val controller = CanvasSessionController(project, source, opened.exploration, opened.root, model)
    try {
        controller.loadRoot()
        val cards = mutableMapOf<String, String>()
        for (related in relatedDiffFiles(path, connections)) {
            val parent = cards[related.parent]
            val shown = checkNotNull(controller.source(parent))
            // Prefer the actual reference site; incoming relations anchor at the referenced declaration.
            val targetPath = supported.getValue(related.path).path
            val link = shown.links.firstOrNull { link -> link.targets.any { controller.node(it)?.filePath == targetPath } }
            val start = link?.range?.first ?: related.offset
            val end = link?.range?.last?.plus(1) ?: start
            val card = controller.openFile(parent, start, end, supported.getValue(related.path))
            cards[related.path] = card.id
        }
        // Check source stamps again before handing this graph to the editor.
        opened.exploration.fileDeclarations(source)
        withContext(Dispatchers.EDT) { opened.session = model.session }
    } finally {
        controller.dispose()
    }
    return DiffCanvasSession(opened, snapshot.files.filter { it.path !in supported }.map { it.path })
}
