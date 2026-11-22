package dev.insight.context

import com.intellij.openapi.util.text.StringUtil
import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewSource

/** Associates an embedded source location with the review only while its evidence matches. */
internal fun embeddedPickerEvidence(
    location: ReviewCodeLocation,
    files: List<ReviewSource>,
    summaryActions: ContextSummaryActions?,
): ContextData {
    val data = ContextData()
    location.reviewFile?.let { data.put(ContextKeys.REVIEW_FILE, it) }
    val file = files.firstOrNull { file ->
        when (file) {
            is ReviewFile -> {
                val before = location.side == SourceSide.BEFORE
                val path = if (before) file.previousPath ?: file.path else file.path
                val captured = location.reviewFile
                if (captured != null) {
                    file.path == captured.path && file.fingerprint == captured.fingerprint
                } else path == location.path && sameCapturedText(if (before) file.before else file.after, location.text)
            }
            else -> file.path == location.path && sameCapturedText(file.text, location.text)
        }
    } ?: return data
    if (file is ReviewFile) data.put(ContextKeys.REVIEW_FILE, file)
    summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
    return data
}

private fun sameCapturedText(text: String?, captured: String): Boolean = text != null &&
    normalizeCapturedText(text) == normalizeCapturedText(captured)

private fun normalizeCapturedText(text: String) = StringUtil.convertLineSeparators(text)
