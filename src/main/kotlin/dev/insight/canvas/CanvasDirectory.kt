package dev.insight.canvas

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.ProjectFiles
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun directoryCanvasFiles(project: Project, directory: VirtualFile): List<VirtualFile> = readAction {
    check(directory.isValid && directory.isDirectory) { "The directory is no longer available." }
    val projectFiles = ProjectFiles(project)
    val roots = if (projectFiles.index.isInContent(directory)) listOf(directory)
        else ProjectRootManager.getInstance(project).contentRoots.filter { VfsUtilCore.isAncestor(directory, it, false) }
    roots.flatMap { projectFiles.contentFiles(it, null) }.distinct()
        .sortedBy { relativePath(project, it.path) }
}

internal data class DirectoryCards(
    val exploration: ProgressiveSchematic,
    val cards: List<CanvasCard>,
    val sources: Map<String, CanvasSource>,
)

internal suspend fun buildDirectoryCards(project: Project, files: List<VirtualFile>,
    onProgress: (Int, Int) -> Unit): DirectoryCards = withContext(Dispatchers.Default) {
    val exploration = ProgressiveSchematic.openFiles(project, files)
    val (cards, sources) = populateDirectoryBatch(project, exploration, emptyMap(), emptyMap(), files, onProgress)
    DirectoryCards(exploration, cards, sources)
}

/**
 * Adds [files] to an existing directory [exploration] and builds cards connected to [existingCards] (keyed by absolute
 * file path) where graph relations exist.
 */
internal suspend fun buildDirectoryBatch(
    project: Project,
    exploration: ProgressiveSchematic,
    existingCards: Map<String, CanvasCard>,
    existingSources: Map<String, CanvasSource>,
    files: List<VirtualFile>,
    onProgress: (Int, Int) -> Unit,
): Pair<List<CanvasCard>, Map<String, CanvasSource>> = withContext(Dispatchers.Default) {
    if (files.isEmpty()) return@withContext emptyList<CanvasCard>() to emptyMap()
    exploration.addFiles(files)
    populateDirectoryBatch(project, exploration, existingCards, existingSources, files, onProgress)
}

private suspend fun populateDirectoryBatch(
    project: Project,
    exploration: ProgressiveSchematic,
    existingCards: Map<String, CanvasCard>,
    existingSources: Map<String, CanvasSource>,
    files: List<VirtualFile>,
    onProgress: (Int, Int) -> Unit,
): Pair<List<CanvasCard>, Map<String, CanvasSource>> {
    val byPath = files.associateBy { it.path }
    val allPaths = (existingCards.keys + byPath.keys).associateWith { it }
    val result = exploration.result
    val nodes = result.graph.nodes.filter { it.kind == SymbolKind.FILE && it.filePath in byPath }.associateBy { it.filePath }
    val connections = smartReadAction(project) { fileConnections(result, allPaths) }
    val cards = linkedMapOf<String, CanvasCard>()
    val sources = linkedMapOf<String, CanvasSource>()
    extendFileConnectionForest(existingCards.keys.sorted(), byPath.keys.sorted(), connections).forEachIndexed { index, entry ->
        withContext(Dispatchers.EDT) { onProgress(index + 1, byPath.size) }
        val file = byPath.getValue(entry.path)
        val path = relativePath(project, file.path)
        val node = nodes[entry.path]
        val schematic = node?.let { exploration.schematic(it.id, whole = true) }
        val parent = entry.parent?.let { cards[it] ?: existingCards[it] }
        val parentSource = parent?.let { sources[it.id] ?: existingSources[it.id] }
        val anchor = if (parent != null && parentSource != null) {
            fileConnectionAnchor(parentSource, file.path, entry.offset, result.graph.nodes::getOrNull)
        } else CardAnchor(0, 0, 0)
        val id = newCanvasId()
        val card = if (schematic != null) {
            val shown = schematicSource(schematic, path, schematic.scope)
            val (text, truncated) = boundCardText(shown.text)
            sources[id] = shown
            CanvasCard(id, parent?.id ?: CANVAS_ROOT, CardKind.SYMBOL, anchor,
                SymbolRef(path, file.name, SymbolKind.FILE.name, line = 0), path, shown.firstLine, text, truncated,
                wholeFile = true, openedBy = CardOpenedBy.DIRECTORY)
        } else {
            val content = directoryAttachment(file)
            sources[id] = CanvasSource(content.text, emptyList(), 0, path, file, notice = attachmentNotice(content.attachment))
            CanvasCard(id, parent?.id ?: CANVAS_ROOT, CardKind.ATTACHMENT, anchor, null, path, 0, content.text, content.truncated,
                openedBy = CardOpenedBy.DIRECTORY, attachment = content.attachment)
        }
        cards[entry.path] = card
    }
    exploration.ensureSourcesCurrent()
    return cards.values.toList() to sources
}

internal suspend fun directoryAttachment(file: VirtualFile): CanvasAttachmentContent {
    val cached = readAction { FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence }
    return withContext(Dispatchers.IO) {
        val type = Files.probeContentType(Path.of(file.path)) ?: "application/octet-stream"
        val metadata = CanvasAttachment(type, file.length)
        if (cached != null && !file.fileType.isBinary) {
            val prefix = cached.take(CANVAS_CARD_TEXT_LIMIT + 1).toString()
            val (text, truncated) = boundCardText(prefix)
            return@withContext CanvasAttachmentContent(text, metadata, truncated)
        }
        try {
            file.inputStream.use { readCanvasAttachment(it, file.length, type, file.fileType.isBinary, file.charset) }
        } catch (_: IOException) {
            CanvasAttachmentContent("", metadata)
        }
    }
}
