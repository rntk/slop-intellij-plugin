package dev.insight.diff

import dev.insight.chat.ChatTool
import dev.insight.enrichment.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewTopicGenerator
import dev.insight.review.ReviewTopics
import dev.insight.review.TopicEvidence
import dev.insight.prompts.PromptTemplates

/** Supplies VCS patch evidence and instructions to the shared topic pipeline. */
internal fun diffTopicGenerator(
    complete: CompletionFn,
    readTool: ChatTool?,
    patchChars: Int = 12_000,
    attempts: Int = 5,
    system: String = DIFF_TOPIC_SYSTEM,
    evidence: (ReviewSource, ReviewTopics) -> TopicEvidence = { source, _ -> diffTopicEvidence(source as ReviewFile, patchChars) },
    concurrency: Int = 1,
): ReviewTopicGenerator = ReviewTopicGenerator(complete, readTool, patchChars, attempts, system, evidence, concurrency)

/** A changed file's status and patch, cut at [patchChars]. */
internal fun diffTopicEvidence(file: ReviewFile, patchChars: Int): TopicEvidence = TopicEvidence(mapOf(
    "path" to file.path, "previous_path" to file.previousPath, "status" to file.status,
    "patch" to file.patch.take(patchChars), "patch_length" to file.patch.length,
    "patch_cut_at" to patchChars.takeIf { file.patch.length > patchChars },
), cut = file.patch.length > patchChars)

internal val DIFF_TOPIC_SYSTEM = PromptTemplates.render("diff-topic-system.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA))
