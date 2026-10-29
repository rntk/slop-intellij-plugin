package dev.insight.core

import java.security.MessageDigest

internal object Hashing {
    /** Lowercase hex SHA-256 of [text] as UTF-8. */
    fun sha256Of(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** Lowercase hex SHA-256 of [parts], each followed by a NUL so that `("ab", "c")` and `("a", "bc")` differ. */
    fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
