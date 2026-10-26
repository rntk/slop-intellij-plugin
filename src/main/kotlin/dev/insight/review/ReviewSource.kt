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
