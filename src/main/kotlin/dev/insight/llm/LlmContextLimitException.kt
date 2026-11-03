package dev.insight.llm

import java.io.IOException

/** A local context estimate exceeded the user's budget; no HTTP request was sent. */
class LlmContextLimitException(message: String) : IOException(message)
