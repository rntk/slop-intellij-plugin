package dev.insight.core

import com.intellij.platform.util.progress.reportRawProgress

/** Runs a pipeline that reports "[done] of [total] [unit]" under [text] as it goes. */
internal suspend fun <R> withCountProgress(text: String, unit: String,
                                           run: suspend (onProgress: suspend (Int, Int) -> Unit) -> R): R =
    withPhaseProgress(text, unit) { onProgress -> run { done, total -> onProgress(text, done, total) } }

/** Like [withCountProgress], for pipelines that name the current phase; it replaces [text] once reported. */
internal suspend fun <R> withPhaseProgress(text: String, unit: String,
                                           run: suspend (onProgress: (phase: String, done: Int, total: Int) -> Unit) -> R): R =
    reportRawProgress { reporter ->
        reporter.text(text)
        run { phase, done, total ->
            reporter.text(phase)
            reporter.details(if (total > 0) "$done of $total $unit".trimEnd() else null)
            reporter.fraction(if (total > 0) done.toDouble() / total else null)
        }
    }
