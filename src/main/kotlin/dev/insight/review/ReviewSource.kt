package dev.insight.review

/** A file under review: a changed file of a diff, or a source file in scope. Summary trees and topics see only this. */
interface ReviewSource {
    val path: String
    val absolutePath: String
    /** Complete snapshot text for a source review; null when the evidence is a diff instead. */
    val text: String? get() = null
    /** Equal while the evidence for this file is unchanged, so its summary or topic can be kept. */
    val fingerprint: String
    /** Short dimmed facts for a reader, such as `modified` and `renamed from x`. */
    val facts: List<String>
}

/**
 * A source whose evidence is a unified patch instead of its text. Shared views paint, trace and open it through this;
 * diff's `ReviewFile` implements it.
 */
interface PatchSource : ReviewSource {
    val patch: String
    val previousPath: String?
    /** The snapshot's sides; null when unavailable, so lines are numbered in the patch. */
    val before: String?
    val after: String?
    /** The snapshot text a whole-file view opens: before for a deleted file, else after; null when unavailable. */
    val fullFileText: String?
}
