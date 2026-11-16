package dev.insight.diff

import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ChangeMapRun
import dev.insight.review.ReviewSummaries

/**
 * The chat answer of /change-map: the whole change's summary once every summary is written; otherwise what the map holds,
 * what the judgments did, why a step stopped, and that running /change-map again continues. [build] is null when an
 * existing map was only continued.
 */
internal fun changeMapReply(fileCount: Int, map: ChangeMap?, summaries: ReviewSummaries, requests: Int, build: ChangeMapRun? = null,
                            error: String? = null): String {
    if (fileCount == 0) return "There are no changed files to map."
    if (map == null) return "No change map was built.${error?.let { " $it" }.orEmpty()} Run /change-map again to retry."
    val atoms = map.graph.atoms.size
    if (atoms == 0) return "No changed declarations found."
    val shape = "${plural(map.chains.size, "logical change")} · ${map.standalone.size} standalone · ${plural(atoms, "declaration")}"
    val allRequests = requests + (build?.requests ?: 0)
    val root = summaries.text(summaries.tree.root.id)
    val problems = listOfNotNull(build?.error, error)
    if (summaries.complete && root != null)
        return "$root\n\n[$shape · ${summaries.total} summaries · ${plural(allRequests, "request")}]" + problems.joinToString("") { "\n\n$it" }
    return buildString {
        append("Mapped ${plural(atoms, "changed declaration")} into ${plural(map.chains.size, "logical change")} and ")
        append("${plural(map.standalone.size, "standalone change")}.")
        build?.takeIf { it.merged + it.attached > 0 }?.let { append(" Judgments merged ${it.merged} and attached ${it.attached}.") }
        append(" Summarized ${summaries.count} of ${summaries.total} entries with ${plural(allRequests, "request")}.")
        problems.forEach { append("\n\n").append(it) }
        append("\n\n[Run /change-map again to write the missing summaries.]")
    }
}

private fun plural(value: Int, one: String) = if (value == 1) "1 $one" else "$value ${one}s"
