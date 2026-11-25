package dev.insight.knowledge

/**
 * Line-based unified diff between an existing doc and a rendered draft, for display only. It is a proposal: nothing here
 * reads or writes files.
 */
object KnowledgeDocDiff {
    /**
     * Unified diff turning [existing] (null when the doc does not exist yet) into [draft]; empty when the texts match
     * apart from line endings. [path] labels both sides.
     */
    fun unified(existing: String?, draft: String, path: String = "doc.md", context: Int = 3): String {
        val old = existing?.let(::lines).orEmpty()
        val new = lines(draft)
        if (existing != null && old == new) return ""
        val ops = edit(old, new)
        val out = StringBuilder()
        out.append(if (existing == null) "--- /dev/null\n" else "--- a/$path\n").append("+++ b/$path\n")
        val changes = ops.indices.filter { ops[it].kind != ' ' }
        if (changes.isEmpty()) return out.toString()
        // Group changes whose context windows touch into hunks.
        var start = maxOf(0, changes.first() - context)
        var end = minOf(ops.size, changes.first() + context + 1)
        val hunks = ArrayList<IntRange>()
        for (index in changes.drop(1)) {
            if (index - context <= end) end = minOf(ops.size, index + context + 1)
            else { hunks += start until end; start = maxOf(0, index - context); end = minOf(ops.size, index + context + 1) }
        }
        hunks += start until end
        for (range in hunks) {
            val before = ops.subList(0, range.first)
            var oldStart = before.count { it.kind != '+' } + 1
            var newStart = before.count { it.kind != '-' } + 1
            val slice = ops.subList(range.first, range.last + 1)
            val oldCount = slice.count { it.kind != '+' }
            val newCount = slice.count { it.kind != '-' }
            if (oldCount == 0) oldStart -= 1
            if (newCount == 0) newStart -= 1
            out.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            slice.forEach { out.append(it.kind).append(it.line).append('\n') }
        }
        return out.toString()
    }

    private data class Op(val kind: Char, val line: String)

    private fun lines(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) return emptyList()
        return normalized.removeSuffix("\n").split('\n')
    }

    private fun edit(old: List<String>, new: List<String>): List<Op> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val a = old.subList(prefix, old.size - suffix)
        val b = new.subList(prefix, new.size - suffix)
        val ops = ArrayList<Op>()
        old.subList(0, prefix).forEach { ops += Op(' ', it) }
        ops += middle(a, b)
        old.subList(old.size - suffix, old.size).forEach { ops += Op(' ', it) }
        return ops
    }

    /** Longest-common-subsequence diff; falls back to replace-all for very large inputs. */
    private fun middle(a: List<String>, b: List<String>): List<Op> {
        if (a.isEmpty()) return b.map { Op('+', it) }
        if (b.isEmpty()) return a.map { Op('-', it) }
        if (a.size.toLong() * b.size > MAX_CELLS) return a.map { Op('-', it) } + b.map { Op('+', it) }
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val ops = ArrayList<Op>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops += Op(' ', a[i]); i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> ops += Op('-', a[i++])
                else -> ops += Op('+', b[j++])
            }
        }
        while (i < a.size) ops += Op('-', a[i++])
        while (j < b.size) ops += Op('+', b[j++])
        return ops
    }

    private const val MAX_CELLS = 4_000_000L
}
