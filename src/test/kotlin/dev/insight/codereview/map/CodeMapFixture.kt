package dev.insight.codereview.map

import dev.insight.codereview.CodeFile

/** Builders for code-map units and references, PSI-free. */
internal object CodeMapFixture {
    fun unit(path: String, name: String? = null, lines: IntRange? = 1..10, members: List<String> = emptyList()): CodeUnit {
        val kind = if (name == null) UnitKind.FILE else UnitKind.CLASS
        val id = if (name == null) path else "$path#$name"
        return CodeUnit(id, path, name ?: path.substringAfterLast('/'), kind, if (name == null) null else lines,
            if (name == null) "" else "class $name", members.mapIndexed { index, member ->
                CodeMember("$path#$name.$member", "$name.$member", "function", (lines!!.first + 1 + index)..(lines.first + 1 + index), "fun $member()")
            }, lines?.count() ?: 10, codeEvidenceKind(path), "fp-$id")
    }

    fun use(from: CodeUnit, to: CodeUnit, kind: CodeReferenceKind = CodeReferenceKind.CALL, count: Int = 1) =
        CodeReference(from.id, to.id, kind, count = count)

    /** A file holding [lines] numbered lines "line N", so excerpts and evidence are predictable. */
    fun file(path: String, lines: Int = 40) = CodeFile(path, "/abs/$path", (1..lines).joinToString("\n") { "line $it" } + "\n")
}
