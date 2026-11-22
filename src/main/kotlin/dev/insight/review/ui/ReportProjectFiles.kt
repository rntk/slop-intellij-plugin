package dev.insight.review.ui

import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import dev.insight.agent.SourceSide
import dev.insight.agent.codeScopeRoots
import dev.insight.agent.findSourceFile
import dev.insight.context.CodeContext
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextPick
import dev.insight.context.ContextScopeKind
import dev.insight.context.ContextSummaryActions
import dev.insight.context.ReviewDestination
import dev.insight.context.SUMMARY_SOURCE_TEXT
import dev.insight.context.capContext
import dev.insight.context.defaultScopeIndex
import dev.insight.context.directoryScope
import dev.insight.context.reviewDiffPick
import dev.insight.context.sourceScopes
import dev.insight.context.symbolsForCapturedText
import dev.insight.diff.ReviewFile
import dev.insight.review.PatchSource
import dev.insight.review.ReportIndexedEntry
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode

private const val MAX_INDEXED_FILES = 100_000
private val REPORT_FILE_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "txt", "log")
private val IGNORED_DIR_NAMES = setOf(".idea", ".git", ".insight-review-cache")

/** Project-wide file/directory index plus sorted `.md`/`.txt`/`.log` report candidates. */
internal data class ProjectReportSnapshot(
    val index: ReportProjectIndex,
    val reportFiles: List<String>,
)

/** Collects project content paths and report file candidates inside a read action. */
internal fun collectProjectReportSnapshot(
    project: Project,
    reviewFiles: Collection<ReviewSource>,
    reportFileDir: String? = null,
): ProjectReportSnapshot = ReadAction.compute<ProjectReportSnapshot, RuntimeException> {
    val roots = codeScopeRoots(project)
    val rootPaths = (roots.mapNotNull { ReportProjectIndex.normalizeAbsPath(it.path) } +
        listOfNotNull(project.basePath?.let(ReportProjectIndex::normalizeAbsPath))).distinct()

    fun isUnderKnownRoots(normAbs: String): Boolean = rootPaths.any { root ->
        normAbs == root || (root == "/" && normAbs.startsWith("/")) || normAbs.startsWith("$root/")
    }

    val scopeByAbs = HashSet<String>()
    val scopeByRel = HashSet<String>()
    for (rf in reviewFiles) {
        val abs = rf.absolutePath.takeIf { it.isNotBlank() }?.let(ReportProjectIndex::normalizeAbsPath)
        if (abs != null && isUnderKnownRoots(abs)) scopeByAbs += abs else scopeByRel += rf.path
    }
    val fileEntries = mutableListOf<ReportIndexedEntry>()
    val dirEntries = mutableListOf<ReportIndexedEntry>()
    val reportEntries = mutableListOf<ReportIndexedEntry>()
    val indexedAbs = HashSet<String>()
    val indexedRels = HashSet<String>()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val types = FileTypeRegistry.getInstance()
    val changes = ChangeListManager.getInstance(project)

    fun relativeToRoots(file: VirtualFile): String? =
        roots.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() }

    fun isIgnored(file: VirtualFile, rel: String): Boolean {
        if (rel.split('/').any { it in IGNORED_DIR_NAMES }) return true
        return types.isFileIgnored(file) || changes.isIgnoredFile(file)
    }

    var indexedFileCount = 0
    fileIndex.iterateContent { file ->
        ProgressManager.checkCanceled()
        val rel = relativeToRoots(file) ?: return@iterateContent true
        if (isIgnored(file, rel)) return@iterateContent true
        val normAbs = ReportProjectIndex.normalizeAbsPath(file.path)
        if (file.isDirectory) {
            dirEntries += ReportIndexedEntry(rel, normAbs)
        } else {
            val inScope = (normAbs != null && normAbs in scopeByAbs) || rel in scopeByRel
            val entry = ReportIndexedEntry(rel, normAbs, inReviewScope = inScope)
            fileEntries += entry
            indexedRels += rel
            if (normAbs != null) indexedAbs += normAbs
            indexedFileCount++
            if (!file.fileType.isBinary && file.extension?.lowercase() in REPORT_FILE_EXTENSIONS) {
                reportEntries += entry
            }
        }
        indexedFileCount < MAX_INDEXED_FILES
    }

    for (rf in reviewFiles) {
        val normAbs = rf.absolutePath.takeIf { it.isNotBlank() }?.let(ReportProjectIndex::normalizeAbsPath)
        val underRoots = normAbs != null && isUnderKnownRoots(normAbs)
        val shouldAddEntry = rf.path !in indexedRels || (underRoots && normAbs !in indexedAbs)
        if (shouldAddEntry) {
            val entry = ReportIndexedEntry(rf.path, normAbs, inReviewScope = true)
            fileEntries += entry
            val ext = rf.path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
            if (ext in REPORT_FILE_EXTENSIONS) reportEntries += entry
        }
    }

    val index = ReportProjectIndex(
        projectRoot = null,
        contentRoots = (roots.map { it.path } + listOfNotNull(project.basePath)).distinct(),
        reportFileDir = reportFileDir,
        fileEntries = fileEntries,
        directoryEntries = dirEntries,
    )
    val sortedReports = reportEntries
        .mapTo(LinkedHashSet()) { entry ->
            entry.absolutePath?.let(index::pathForAbsolute) ?: entry.path
        }
        .sortedWith(compareBy<String>({ it.count { c -> c == '/' } }, { it.lowercase() }, { it }))
    ProjectReportSnapshot(index, sortedReports)
}

/** Finds a project source file preferring an exact [absolutePath] across VFS/local roots before relative lookup. */
internal fun findReportSourceFile(project: Project, path: String, absolutePath: String?): VirtualFile? {
    val normAbs = absolutePath?.let(ReportProjectIndex::normalizeAbsPath)
    if (normAbs != null) {
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> { codeScopeRoots(project) }
        val exact = ReadAction.compute<VirtualFile?, RuntimeException> {
            roots.firstNotNullOfOrNull { it.fileSystem.findFileByPath(normAbs) }
        }?.takeIf { it.isValid && !it.isDirectory }
        if (exact != null) return exact

        val underRoots = roots.any { root ->
            val rootAbs = ReportProjectIndex.normalizeAbsPath(root.path) ?: return@any false
            normAbs == rootAbs || (rootAbs == "/" && normAbs.startsWith("/")) || normAbs.startsWith("$rootAbs/")
        }
        // If absolutePath belongs to a known project root and is absent on disk, don't fall back to another root.
        if (underRoots) return null
    }
    return findSourceFile(project, path, absolutePath)
}

/**
 * Opens the project file at [path] in the IDE editor, optionally positioning the caret at 1-based [lines]/[column]
 * and highlighting the entire target line range until Escape or an edit.
 */
internal fun openReportFile(
    project: Project,
    path: String,
    lines: IntRange?,
    column: Int? = null,
    absolutePath: String? = null,
): Boolean {
    if (project.isDisposed) return false
    val vf = findReportSourceFile(project, path, absolutePath)
    if (vf == null) {
        StatusBar.Info.set("Cannot find $path in the project", project)
        return false
    }
    if (lines == null) {
        OpenFileDescriptor(project, vf).navigate(true)
        return true
    }
    val lineIndex = (lines.first - 1).coerceAtLeast(0)
    val colIndex = ((column ?: 1) - 1).coerceAtLeast(0)
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, lineIndex, colIndex), true)
        ?: return false
    val document = editor.document
    if (document.lineCount > 0) {
        val first = lines.first.coerceIn(1, document.lineCount)
        val last = lines.last.coerceIn(first, document.lineCount)
        HighlightManager.getInstance(project).addRangeHighlight(
            editor,
            document.getLineStartOffset(first - 1),
            document.getLineEndOffset(last - 1),
            EditorColors.SEARCH_RESULT_ATTRIBUTES,
            true,
            null,
        )
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
    return true
}

/**
 * Builds a [ContextPick] for a file or directory link clicked inside a report, wiring review destinations,
 * walls, canvas sessions, and summary generation when the target belongs to the active review.
 */
internal fun reportLinkContextPick(
    project: Project,
    kind: ReportTargetKind,
    path: String,
    lines: IntRange?,
    reviewFiles: List<ReviewSource>,
    summaryTree: ReviewSummaryTree?,
    reviewDestinations: Map<ReviewDestination, () -> Unit>,
    summaryActions: ContextSummaryActions?,
    openOnWall: ((List<ReviewSource>, String) -> Unit)?,
    openOnCanvas: ((SummaryNode) -> Unit)?,
    absolutePath: String? = null,
): ContextPick? {
    if (project.isDisposed) return null
    return when (kind) {
        ReportTargetKind.EXTERNAL_URL, ReportTargetKind.SYMBOL -> null
        ReportTargetKind.FILE -> buildFileContextPick(
            project, path, lines, reviewFiles, summaryTree, reviewDestinations, summaryActions, openOnWall, openOnCanvas, absolutePath,
        )
        ReportTargetKind.DIRECTORY -> buildDirectoryContextPick(
            project, path, reviewFiles, summaryTree, reviewDestinations, summaryActions, openOnWall, absolutePath,
        )
    }
}

internal fun matchingReviewSource(reviewFiles: List<ReviewSource>, path: String, absolutePath: String?): ReviewSource? {
    val normAbs = absolutePath?.let(ReportProjectIndex::normalizeAbsPath)
    if (normAbs != null) {
        reviewFiles.firstOrNull { rf ->
            rf.absolutePath.isNotBlank() && ReportProjectIndex.normalizeAbsPath(rf.absolutePath) == normAbs
        }?.let { return it }
    }
    return reviewFiles.firstOrNull { it.path == path }
}

private fun buildFileContextPick(
    project: Project,
    path: String,
    lines: IntRange?,
    reviewFiles: List<ReviewSource>,
    summaryTree: ReviewSummaryTree?,
    reviewDestinations: Map<ReviewDestination, () -> Unit>,
    summaryActions: ContextSummaryActions?,
    openOnWall: ((List<ReviewSource>, String) -> Unit)?,
    openOnCanvas: ((SummaryNode) -> Unit)?,
    absolutePath: String?,
): ContextPick? {
    val reviewSource = matchingReviewSource(reviewFiles, path, absolutePath)
    val effectiveAbs = absolutePath ?: reviewSource?.absolutePath?.takeIf { it.isNotBlank() }
    val vf = findReportSourceFile(project, reviewSource?.path ?: path, effectiveAbs)
    val data = ContextData()
    vf?.let { data.put(ContextKeys.VIRTUAL_FILE, it) }
    if (reviewDestinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, reviewDestinations)

    if (reviewSource is ReviewFile) {
        val side = if (reviewSource.after != null) SourceSide.AFTER else SourceSide.BEFORE
        val allDiffs = reviewFiles.filterIsInstance<ReviewFile>()
        val sideText = (if (side == SourceSide.BEFORE) reviewSource.before else reviewSource.after).orEmpty()
        val liveText = vf?.let(::readVirtualFileText).orEmpty()
        val lineBasis = sideText.ifEmpty { liveText }
        val clamped = if (lineBasis.isNotEmpty()) clampLineRange(lines, lineBasis) else null
        summaryActions?.takeIf { it.matchesDiff(reviewSource) }?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
        openOnWall?.let { open ->
            data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context ->
                val selected = allDiffs.filter { it.path in context.files.toSet() }
                if (selected.isNotEmpty()) open(selected, context.label)
            }
        }
        if (openOnCanvas != null) {
            val baseNode = summaryTree?.scopeNode(reviewSource.path, SummaryKind.FILE)
                ?: SummaryNode(
                    id = -1,
                    kind = SummaryKind.FILE,
                    path = reviewSource.path,
                    label = reviewSource.path.substringAfterLast('/'),
                    parent = null,
                    children = emptyList(),
                    files = listOf(reviewSource.path),
                    key = "file:${reviewSource.path}",
                    identity = "file:${reviewSource.path}",
                    file = reviewSource,
                )
            data.put(ContextKeys.OPEN_ON_CANVAS) { context ->
                openOnCanvas(baseNode.copy(lines = context.lines ?: clamped))
            }
        }
        val pick = reviewDiffPick(project, ContextOrigin.OTHER, reviewSource, clamped?.first, side, allDiffs, data)
        val scopes = if (clamped != null && clamped.first < clamped.last && lineBasis.isNotEmpty()) {
            val selectionText = sliceLines(lineBasis, clamped)
            val selectionScope = CodeContext(
                ContextScopeKind.SELECTION,
                "Lines ${clamped.first}-${clamped.last}",
                reviewSource.path,
                reviewSource.absolutePath,
                clamped,
                side,
                capContext(selectionText),
            )
            listOf(selectionScope) + pick.candidates
        } else pick.candidates
        return ContextPick(ContextOrigin.OTHER, scopes, pick.data, if (clamped != null) 0 else defaultScopeIndex(scopes))
    }

    val liveText = vf?.let(::readVirtualFileText) ?: reviewSource?.text ?: return null
    val resolvedPath = reviewSource?.path ?: path
    val resolvedAbs = vf?.path ?: effectiveAbs
    data.put(SUMMARY_SOURCE_TEXT, liveText)
    if (reviewSource != null) {
        summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
        openOnWall?.let { open ->
            data.put(ContextKeys.OPEN_ON_CODE_WALL) { context ->
                val paths = context.files.toSet()
                val selected = reviewFiles.filter { it !is ReviewFile && it !is PatchSource && it.path in paths }
                if (selected.isNotEmpty()) open(selected, context.label)
            }
        }
    }
    val clamped = clampLineRange(lines, liveText)
    val scopes = if (clamped == null) {
        listOf(
            CodeContext(
                ContextScopeKind.FILE,
                "File ${resolvedPath.substringAfterLast('/')}",
                resolvedPath,
                resolvedAbs,
                null,
                SourceSide.CURRENT,
                capContext(liveText),
            ),
        )
    } else {
        val selection = clamped.takeIf { it.first < it.last }
        val symbols = symbolsForCapturedText(project, resolvedPath, resolvedAbs, liveText, clamped.first)
        sourceScopes(resolvedPath, resolvedAbs, liveText, clamped.first, selection, symbols, null)
    }
    return ContextPick(ContextOrigin.OTHER, scopes, data, if (clamped != null) 0 else defaultScopeIndex(scopes))
}

private fun buildDirectoryContextPick(
    project: Project,
    path: String,
    reviewFiles: List<ReviewSource>,
    summaryTree: ReviewSummaryTree?,
    reviewDestinations: Map<ReviewDestination, () -> Unit>,
    summaryActions: ContextSummaryActions?,
    openOnWall: ((List<ReviewSource>, String) -> Unit)?,
    absolutePath: String?,
): ContextPick? {
    val dir = findDirectoryVirtualFile(project, path, absolutePath)
    val normDirAbs = absolutePath?.let(ReportProjectIndex::normalizeAbsPath)?.trimEnd('/')
    val prefix = if (path.isEmpty()) "" else "$path/"
    val scoped = reviewFiles.filter { rf ->
        if (normDirAbs != null && rf.absolutePath.isNotBlank()) {
            val rfAbs = ReportProjectIndex.normalizeAbsPath(rf.absolutePath)
            rfAbs != null && (rfAbs == normDirAbs || rfAbs.startsWith("$normDirAbs/"))
        } else {
            path.isEmpty() || rf.path.startsWith(prefix)
        }
    }
    if (scoped.isEmpty() && dir == null) return null

    val data = ContextData()
    if (reviewDestinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, reviewDestinations)
    dir?.let { data.put(ContextKeys.VIRTUAL_FILE, it) }

    if (scoped.isNotEmpty()) {
        val dirNode = summaryTree?.scopeNode(path, if (path.isEmpty()) SummaryKind.ROOT else SummaryKind.DIRECTORY)
        if (dirNode != null || summaryTree == null) {
            summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
        }
        val isDiff = scoped.all { it is ReviewFile }
        val isCode = scoped.none { it is ReviewFile || it is PatchSource }
        openOnWall?.let { open ->
            when {
                isDiff -> data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context ->
                    val selected = scoped.filter { it.path in context.files.toSet() }
                    if (selected.isNotEmpty()) open(selected, context.label)
                }
                isCode -> data.put(ContextKeys.OPEN_ON_CODE_WALL) { context ->
                    val selected = scoped.filter { it.path in context.files.toSet() }
                    if (selected.isNotEmpty()) open(selected, context.label)
                }
            }
        }
        val baseScope = directoryScope(path, scoped)
        val scope = if (baseScope.absolutePath == null && dir != null) baseScope.copy(absolutePath = dir.path) else baseScope
        return ContextPick(ContextOrigin.OTHER, listOf(scope), data)
    }

    val virtualDir = dir ?: return null
    val childrenPreview = ReadAction.compute<String, RuntimeException> {
        virtualDir.children.joinToString("\n", limit = 250) { it.name + if (it.isDirectory) "/" else "" }
    }
    val scope = CodeContext(
        ContextScopeKind.DIRECTORY,
        "Directory ${path.ifEmpty { "." }}",
        path,
        virtualDir.path,
        null,
        SourceSide.CURRENT,
        capContext(childrenPreview),
        files = emptyList(),
    )
    return ContextPick(ContextOrigin.OTHER, listOf(scope), data)
}

internal fun readVirtualFileText(file: VirtualFile): String? = ReadAction.compute<String?, RuntimeException> {
    if (!file.isValid || file.isDirectory) return@compute null
    val text = FileDocumentManager.getInstance().getDocument(file)?.text
        ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
        ?: return@compute null
    StringUtil.convertLineSeparators(text)
}

private fun findDirectoryVirtualFile(
    project: Project,
    relPath: String,
    absolutePath: String? = null,
): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
    if (absolutePath != null) {
        codeScopeRoots(project).firstNotNullOfOrNull { it.fileSystem.findFileByPath(absolutePath) }
            ?.takeIf { it.isValid && it.isDirectory }
            ?.let { return@compute it }
    }
    codeScopeRoots(project).firstNotNullOfOrNull { root ->
        if (relPath.isEmpty()) root else root.findFileByRelativePath(relPath)
    }?.takeIf { it.isValid && it.isDirectory }
}

private fun clampLineRange(lines: IntRange?, text: String): IntRange? {
    if (lines == null) return null
    val total = countLines(text)
    val first = lines.first.coerceIn(1, total)
    val last = lines.last.coerceIn(first, total)
    return first..last
}

private fun countLines(text: String): Int {
    if (text.isEmpty()) return 1
    var count = text.count { it == '\n' } + 1
    if (text.endsWith('\n')) count = maxOf(1, count - 1)
    return count
}

private fun sliceLines(text: String, range: IntRange): String {
    val split = text.removeSuffix("\n").split('\n')
    val from = (range.first - 1).coerceIn(0, split.size)
    val to = range.last.coerceIn(from, split.size)
    return split.subList(from, to).joinToString("\n")
}
