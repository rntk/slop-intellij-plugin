package dev.insight.review

import com.google.gson.GsonBuilder
import dev.insight.core.Hashing

/** Evidence JSON stays readable, including source punctuation. */
internal val reviewJson = GsonBuilder().disableHtmlEscaping().create()

internal fun reviewFingerprint(vararg values: String): String {
    return Hashing.sha256(*values)
}

