package dev.insight.codereview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.insight.review.ReviewSource
import dev.insight.review.SourceOutline
import dev.insight.review.SourceOutlines
import java.util.concurrent.CancellationException

private val LOG = logger<CodeOutlines>()

/** Outlines of captured source text, parsed when a reader unfolds a file and kept for the most recently used files. */
internal class CodeOutlines(private val project: Project, private val parent: Disposable) : SourceOutlines {
    private val cache = object : LinkedHashMap<String, SourceOutline>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceOutline>) = size > MAX_CACHED
    }
    private val waiting = mutableMapOf<String, MutableList<() -> Unit>>()

    override fun cached(file: ReviewSource): SourceOutline? = cache[file.fingerprint]

    override fun load(file: ReviewSource, done: () -> Unit) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val key = file.fingerprint
        if (key in cache) return done()
        waiting[key]?.let { it += done; return }
        val text = file.text ?: run { cache[key] = SourceOutline.EMPTY; return done() }
        waiting[key] = mutableListOf(done)
        val name = file.path.substringAfterLast('/')
        ReadAction.nonBlocking<SourceOutline> {
            try {
                codeStructure(project, name, FileTypeManager.getInstance().getFileTypeByFileName(name), text)?.outline ?: SourceOutline.EMPTY
            } catch (e: Throwable) {
                if (e is ControlFlowException || e is CancellationException) throw e
                LOG.warn("Could not outline ${file.path}", e)
                SourceOutline.EMPTY
            }
        }.expireWith(parent).coalesceBy(this, key).finishOnUiThread(ModalityState.any()) { outline ->
            cache[key] = outline
            waiting.remove(key)?.forEach { it() }
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private companion object {
        const val MAX_CACHED = 256
    }
}
