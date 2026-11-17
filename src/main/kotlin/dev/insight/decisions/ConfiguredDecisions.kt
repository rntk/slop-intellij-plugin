package dev.insight.decisions

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import dev.insight.settings.ChatSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<DecisionRequest>()

/**
 * The configured Decisions API, or null when it cannot be set up, so maps keep the groups code built. Reads the password
 * safe on IO.
 */
internal suspend fun configuredDecisions(): DecisionFn? = withContext(Dispatchers.IO) {
    try {
        ChatSettings.getInstance().createDecisionsClient().let { client -> client::evaluate }
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        LOG.info("Map judgments are off: ${e.message}")
        null
    }
}
