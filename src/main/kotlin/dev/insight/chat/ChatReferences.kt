package dev.insight.chat

import com.intellij.openapi.util.text.StringUtil

/** `[[node:ID]]` or `[[node:ID|Label]]` in an assistant reply: the only markup the chart chat renders. */
private val REFERENCE = Regex("""\[\[node:(\d{1,9})(?:\|([^\[\]\n]{0,80}))?]]""")

/** A function reference in a reply, written for the graph revision of the message. */
data class ChatReference(val node: Int, val label: String?)

/** Splits [text] into plain text (String) and [ChatReference]s; malformed references stay plain text. */
fun parseChatReferences(text: String): List<Any> = buildList {
    var last = 0
    for (match in REFERENCE.findAll(text)) {
        if (match.range.first > last) add(text.substring(last, match.range.first))
        add(ChatReference(match.groupValues[1].toInt(), match.groupValues[2].trim().ifEmpty { null }))
        last = match.range.last + 1
    }
    if (last < text.length) add(text.substring(last))
}

/**
 * HTML body of a reply: everything is escaped and only references become links `node:REVISION:ID`, showing their label or
 * the function [name]. References [name] does not resolve (unknown, or written for an older graph) are plain text.
 */
fun chatReferenceHtml(text: String, revision: Int?, name: (Int) -> String?): String = parseChatReferences(text).joinToString("") { part ->
    if (part !is ChatReference) return@joinToString escapeChatText(part as String)
    val known = revision?.let { name(part.node) }
    val label = escapeChatText(part.label ?: known ?: "#${part.node}")
    if (known == null) label else "<a href=\"node:$revision:${part.node}\">$label</a>"
}

/** Parses a link written by [chatReferenceHtml] into (revision, node). */
fun parseChatReferenceLink(link: String?): Pair<Int, Int>? =
    link?.split(':')?.takeIf { it.size == 3 && it[0] == "node" }?.let { (_, revision, node) ->
        revision.toIntOrNull()?.let { r -> node.toIntOrNull()?.let { r to it } }
    }

private fun escapeChatText(text: String) = StringUtil.escapeXmlEntities(text).replace("  ", " &nbsp;").replace("\n", "<br>")
