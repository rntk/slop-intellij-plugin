package dev.insight.chat

import com.intellij.openapi.util.text.StringUtil

/** `[[node:ID]]`, `[[node:ID|Label]]`, `[[beat:BEAT]]`, or `[[beat:BEAT|Label]]` in an assistant reply: the only markup the chart chat renders. */
private val REFERENCE = Regex("""\[\[node:(\d{1,9})(?:\|([^\[\]\n]{0,80}))?]]|\[\[beat:([1-9]\d?)(?:\|([^\[\]\n]{0,80}))?]]""")

/** A function reference in a reply, written for the graph revision of the message. */
data class ChatReference(val node: Int, val label: String?)

/** A story beat reference in a reply, written for the story revision of the message. */
data class ChatBeatReference(val beat: Int, val label: String?)

/** Splits [text] into plain text (String), [ChatReference]s, and [ChatBeatReference]s; malformed references stay plain text. */
fun parseChatReferences(text: String): List<Any> = buildList {
    var last = 0
    for (match in REFERENCE.findAll(text)) {
        if (match.range.first > last) add(text.substring(last, match.range.first))
        val nodeStr = match.groups[1]?.value
        if (nodeStr != null) {
            add(ChatReference(nodeStr.toInt(), match.groups[2]?.value?.trim()?.ifEmpty { null }))
        } else {
            val beatStr = match.groups[3]!!.value
            add(ChatBeatReference(beatStr.toInt(), match.groups[4]?.value?.trim()?.ifEmpty { null }))
        }
        last = match.range.last + 1
    }
    if (last < text.length) add(text.substring(last))
}

/**
 * HTML body of a reply: everything is escaped and only references become links `node:REVISION:ID` or
 * `beat:REVISION:BEAT`, showing their label or the function/beat title. References that do not resolve
 * (unknown, or written for an older graph) are plain text.
 */
fun chatReferenceHtml(
    text: String,
    revision: Int?,
    beatTitle: (Int) -> String? = { null },
    name: (Int) -> String?,
): String = parseChatReferences(text).joinToString("") { part ->
    when (part) {
        is ChatReference -> {
            val known = revision?.let { name(part.node) }
            val label = escapeChatText(part.label ?: known ?: "#${part.node}")
            if (known == null) label else "<a href=\"node:$revision:${part.node}\">$label</a>"
        }
        is ChatBeatReference -> {
            val known = revision?.let { beatTitle(part.beat) }
            val label = escapeChatText(part.label ?: known ?: "Beat ${part.beat}")
            if (known == null) label else "<a href=\"beat:$revision:${part.beat}\">$label</a>"
        }
        else -> escapeChatText(part as String)
    }
}

/** Parses a link written by [chatReferenceHtml] into (revision, node). */
fun parseChatReferenceLink(link: String?): Pair<Int, Int>? =
    link?.split(':')?.takeIf { it.size == 3 && it[0] == "node" }?.let { (_, revision, node) ->
        revision.toIntOrNull()?.let { r -> node.toIntOrNull()?.let { r to it } }
    }

/** Parses a link written by [chatReferenceHtml] into (revision, beat). */
fun parseChatBeatLink(link: String?): Pair<Int, Int>? =
    link?.split(':')?.takeIf { it.size == 3 && it[0] == "beat" }?.let { (_, revision, beat) ->
        revision.toIntOrNull()?.let { r -> beat.toIntOrNull()?.takeIf { it >= 1 }?.let { r to it } }
    }

private fun escapeChatText(text: String) = StringUtil.escapeXmlEntities(text).replace("  ", " &nbsp;").replace("\n", "<br>")
