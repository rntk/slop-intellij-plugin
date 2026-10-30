package dev.insight.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.impl.DiffRequestProcessorListener
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolder
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.roundToInt
import dev.insight.review.ui.LineTrace
import dev.insight.review.ui.highlightLines

internal fun createFullFileSnapshot(file: ReviewFile): LightVirtualFile {
    val label = if (file.status == "deleted") file.beforeLabel else file.afterLabel
    val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
    return LightVirtualFile("${file.path.substringAfterLast('/')} ($label snapshot)", type, requireNotNull(file.fullFileText)).apply {
        isWritable = false
    }
}

/** Build each native diff only when visited, keeping large folders inexpensive to open. */
internal fun createDirectoryDiffChain(project: Project?, files: List<ReviewFile>): SimpleDiffRequestChain {
    require(files.isNotEmpty())
    return SimpleDiffRequestChain.fromProducers(files.map { file ->
        object : DiffRequestProducer {
            override fun getName() = file.path
            override fun process(context: UserDataHolder, indicator: ProgressIndicator): SimpleDiffRequest {
                indicator.checkCanceled()
                return createDiffRequest(project, file)
            }
        }
    })
}

internal fun showDirectoryDiff(project: Project, files: List<ReviewFile>) {
    if (files.isNotEmpty()) DiffManager.getInstance().showDiff(project, createDirectoryDiffChain(project, files), DiffDialogHints.DEFAULT)
}

internal fun createDiffRequest(project: Project?, file: ReviewFile): SimpleDiffRequest {
    val factory = DiffContentFactory.getInstance()
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
    val request = if (file.before != null && file.after != null) SimpleDiffRequest(
        file.path,
        factory.create(project, file.before, fileType),
        factory.create(project, file.after, fileType),
        "${file.previousPath ?: file.path} · ${file.beforeLabel}",
        "${file.path} · ${file.afterLabel}",
    ) else SimpleDiffRequest(
        file.path,
        factory.create(project, ""),
        factory.create(project, file.patch),
        "Before",
        "Content unavailable",
    )
    request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
    request.putUserData(REVIEW_DIFF_FILE, file)
    return request
}

/**
 * Opens [file]'s diff scrolled to the first of [lines] and highlights them on their side. The viewer builds its editors after
 * the request is shown, so the highlights wait for them briefly; a unified viewer has no editor per side and only scrolls.
 */
internal fun showDiffLines(project: Project, file: ReviewFile, lines: LineTrace) {
    val request = createDiffRequest(project, file)
    val (side, first) = lines.after.minOrNull()?.let { Side.RIGHT to it } ?: lines.before.minOrNull()?.let { Side.LEFT to it } ?: return
    request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, first - 1))
    DiffManager.getInstance().showDiff(project, request)
    val documents = request.contents.map { (it as? DocumentContent)?.document }
    val sides = listOf(lines.before, lines.after)
    var attempts = 0
    Timer(100, null).apply {
        addActionListener {
            val editors = documents.map { document -> document?.let { EditorFactory.getInstance().getEditors(it, project).toList() }.orEmpty() }
            if (editors.any { it.isNotEmpty() } || ++attempts >= 20) {
                stop()
                editors.forEachIndexed { index, sideEditors -> sideEditors.forEach { highlightLines(project, it, sides[index]) } }
            }
        }
        initialDelay = 0
        start()
    }
}

/**
 * Embeds the IDE's native read-only diff viewer for a snapshot file inside a canvas card, optionally opened at the
 * zero-based line [scrollTo] of one side.
 */
internal class DiffFileEditor(val file: ReviewFile, project: Project?, scrollTo: Pair<Side, Int>? = null) : JPanel(BorderLayout()), Disposable {
    private val request = createDiffRequest(project, file).apply { scrollTo?.let { putUserData(DiffUserDataKeys.SCROLL_TO_LINE, it) } }
    internal val panel = object : DiffRequestProcessor(project) {
        override fun updateRequest(force: Boolean, scrollToChangePolicy: ScrollToPolicy?) {
            applyRequest(request, force, scrollToChangePolicy)
        }
    }.apply {
        updateRequest()
    }
    internal val editors: List<EditorEx>
        get() = UIUtil.findComponentsOfType(panel.component, EditorComponentImpl::class.java).map { it.editor }.distinct()
    /** The editors showing one side; none when the viewer merges both sides into one document, as the unified viewer does. */
    internal fun editors(side: Side): List<EditorEx> {
        val document = (request.contents[side.index] as? DocumentContent)?.document ?: return emptyList()
        return editors.filter { it.document == document }
    }
    private val baseFontSize =editors.firstOrNull()?.colorsScheme?.editorFontSize ?: JBUI.Fonts.label().size
    private var zoom = 1.0
    internal var isDisposed = false
        private set

    init {
        Disposer.register(this, panel)
        panel.addListener(object : DiffRequestProcessorListener {
            override fun onViewerChanged() = applyZoom()
        }, this)
        add(panel.component, BorderLayout.CENTER)
    }

    fun resizeFor(width: Int, zoom: Double) {
        this.zoom = zoom
        applyZoom()
        preferredSize = Dimension(width, heightFor(file, zoom, editors.firstOrNull { !it.isDisposed }?.lineHeight))
    }

    private fun applyZoom() {
        val fontSize = (baseFontSize * zoom).toFloat()
        editors.forEach { if (!it.isDisposed) it.setFontSize(fontSize) }
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        Disposer.dispose(panel)
    }

    companion object {
        /** The height [resizeFor] gives [file]'s viewer; without a [lineHeight] it estimates one, for placeholders. */
        internal fun heightFor(file: ReviewFile, zoom: Double, lineHeight: Int? = null): Int {
            val lineCount = maxOf(lines(file.before), lines(file.after), lines(file.patch), 1)
            return ((lineHeight ?: (JBUI.scale(20) * zoom).roundToInt()) * lineCount + (JBUI.scale(64) * zoom).roundToInt()).coerceIn(
                (JBUI.scale(180) * zoom).roundToInt(),
                (JBUI.scale(420) * zoom).roundToInt(),
            )
        }

        private fun lines(text: String?): Int =
            if (text.isNullOrEmpty()) 0 else text.lineSequence().toList().let { if (it.last().isEmpty()) it.size - 1 else it.size }
    }
}
