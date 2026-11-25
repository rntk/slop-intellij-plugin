package dev.insight.knowledge

/**
 * Ports between the pure knowledge workflows and the IDE. Workflows take these in their constructors;
 * `dev.insight.knowledge.ide` implements them with PSI, the call graph and the project index.
 */

/** A bounded excerpt of code or facts the model can read as evidence for a claim. */
data class Evidence(
    /** The anchor ref or project-relative path the excerpt is about. */
    val ref: String,
    /** Signature, doc, outline or source excerpt; already trimmed to a budget. */
    val text: String,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

/** Reads evidence for anchors without exposing PSI. Implementations take their own read actions. */
fun interface EvidenceProvider {
    suspend fun evidence(anchors: List<Anchor>, maxChars: Int): List<Evidence>
}

/** Finds the code a piece of prose refers to: file and directory links, code-shaped symbol mentions. */
fun interface ClaimAnchorer {
    suspend fun anchors(text: String, docPath: String?): List<Anchor>
}

/** One unit of distillation work: a part of the codebase a single worker owns. */
data class KnowledgePartition(
    /** Project-relative directory or module path; becomes the claims' area. */
    val area: String,
    /** Project-relative files the worker may read. */
    val files: List<String>,
    /** Parent area for the organizer's bottom-up merge, null for the root. */
    val parent: String?,
)

/** Splits a review scope into partitions for parallel workers. */
fun interface KnowledgePartitioner {
    suspend fun partitions(scope: List<String>): List<KnowledgePartition>
}

/** Progress shared by knowledge runs: [done] of [total] units, with a short phase label. */
fun interface KnowledgeProgress {
    fun report(phase: String, done: Int, total: Int)
}
