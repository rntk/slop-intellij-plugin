package dev.insight.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.roundToInt

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

/** Embeds the IDE's native read-only diff viewer for a snapshot file inside a canvas card. */
internal class DiffFileEditor(val file: ReviewFile, project: Project?) : JPanel(BorderLayout()), Disposable {
    private val request = createDiffRequest(project, file)
    internal val panel = object : DiffRequestProcessor(project) {
        override fun updateRequest(force: Boolean, scrollToChangePolicy: ScrollToPolicy?) {
            applyRequest(request, force, scrollToChangePolicy)
        }
    }.apply {
        updateRequest()
    }
    internal val editors: List<EditorEx>
        get() = UIUtil.findComponentsOfType(panel.component, EditorComponentImpl::class.java).map { it.editor }.distinct()
    private val baseFontSize = editors.firstOrNull()?.colorsScheme?.editorFontSize ?: JBUI.Fonts.label().size
    private val lineCount = maxOf(lines(file.before), lines(file.after), lines(file.patch), 1)
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
        val lineHeight = editors.firstOrNull { !it.isDisposed }?.lineHeight ?: (JBUI.scale(20) * zoom).roundToInt()
        val height = (lineHeight * lineCount + (JBUI.scale(64) * zoom).roundToInt()).coerceIn(
            (JBUI.scale(180) * zoom).roundToInt(),
            (JBUI.scale(420) * zoom).roundToInt(),
        )
        preferredSize = Dimension(width, height)
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

    private companion object {
        fun lines(text: String?): Int =
            if (text.isNullOrEmpty()) 0 else text.lineSequence().toList().let { if (it.last().isEmpty()) it.size - 1 else it.size }
    }
}
