package dev.insight.review

import com.google.gson.GsonBuilder
import java.security.MessageDigest

/** Evidence JSON stays readable, including source punctuation. */
internal val reviewJson = GsonBuilder().disableHtmlEscaping().create()

internal fun reviewFingerprint(vararg values: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
    values.forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update(0.toByte()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

