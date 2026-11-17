package dev.insight.canvas

import dev.insight.graph.RelationKind

/** One usage site within a card's file: 0-based document [line], whether it is a [call], and its enclosing [owner]'s name. */
data class CardUsageSite(val line: Int, val call: Boolean, val owner: String)

/** The usages of [targetName] found in the file a card shows, so the reader can jump between them inside that card. */
data class CardUsages(val targetName: String, val sites: List<CardUsageSite>)

/** The usages found in one file: [filePath] absolute, [path] project-relative, [usages] in line order. */
data class CanvasUsageFile(val filePath: String, val path: String, val usages: List<CanvasUsage>) {
    val fileName: String get() = path.ifEmpty { filePath }.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")
    val calls: Int get() = usages.count { it.kind == RelationKind.CALL }

    /** `2 calls, 1 use in run(), main()`. */
    val detail: String get() {
        val uses = usages.size - calls
        val counts = listOfNotNull(
            calls.takeIf { it > 0 }?.let { "$it ${if (it == 1) "call" else "calls"}" },
            uses.takeIf { it > 0 }?.let { "$it ${if (it == 1) "use" else "uses"}" },
        ).joinToString(", ")
        val owners = usages.map { it.ownerName }.distinct()
        val shown = owners.take(3).joinToString(", ") + if (owners.size > 3) " +${owners.size - 3}" else ""
        return "$counts in $shown"
    }

    /** What the card opened for this file lists, with 0-based lines. */
    fun sites(targetName: String) = CardUsages(targetName,
        usages.map { CardUsageSite(it.line - 1, it.kind == RelationKind.CALL, it.ownerName) }.distinct())
}

/** [usages] joined by file, in the order the files were first found, each file's usages in line order. */
fun canvasUsageFiles(usages: List<CanvasUsage>): List<CanvasUsageFile> = usages.groupBy { it.filePath }.map { (filePath, found) ->
    CanvasUsageFile(filePath, found.first().path, found.sortedWith(compareBy({ it.line }, { it.offset })))
}

/** `Used by 5 sites in 2 files`, with `+` when the search stopped early. */
fun canvasUsedByTitle(found: CanvasUsageSearch): String {
    val files = found.usages.map { it.filePath }.distinct().size
    val more = if (found.truncated) "+" else ""
    return "Used by ${found.usages.size}$more ${if (found.usages.size == 1) "site" else "sites"} in $files ${if (files == 1) "file" else "files"}"
}

/** `Line 12 · Call in run()`, with a 1-based line. */
fun canvasUsageSiteLabel(site: CardUsageSite): String = "Line ${site.line + 1} · ${if (site.call) "Call" else "Use"} in ${site.owner}"

/** `Usages of work (3)`: the title of a card's usage-site block. */
fun canvasUsageSitesTitle(usages: CardUsages): String = "Usages of ${usages.targetName} (${usages.sites.size})"
