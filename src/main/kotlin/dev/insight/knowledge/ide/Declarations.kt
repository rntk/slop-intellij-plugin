package dev.insight.knowledge.ide

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.graph.SymbolKind

/**
 * A declaration read token by token, so comments and formatting never matter.
 *
 * [header] is the declaration's source up to its body, whitespace collapsed, for people and models. [signatureKey] is the same
 * tokens joined by single spaces, the stable identity of the signature. [bodyKey] is the tokens after the header start of the
 * body (`{`, a Kotlin or Java `=`, a Python `:`), when requested. [doc] is the leading doc comment's text.
 */
internal class DeclarationShape(
    val header: String,
    val signatureKey: String,
    val bodyKey: String?,
    val doc: String?,
    /** Offset into the declaration's text where its body starts, or -1 when it has none. */
    val bodyStart: Int,
)

/** Language-agnostic declaration reading on top of the call graph's symbol kinds. Call inside a read action. */
internal object Declarations {
    private val MEMBER_KINDS = setOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY)
    private const val MAX_MEMBERS = 2_000

    fun isMemberKind(kind: SymbolKind?) = kind in MEMBER_KINDS

    fun kindOf(element: PsiElement): SymbolKind? = ProjectGraphAnalyzer.kind(element)

    fun shape(element: PsiElement, withBody: Boolean): DeclarationShape {
        val text = element.text
        val base = element.textRange.startOffset
        val python = element.language.id.equals("Python", ignoreCase = true)
        val signature = mutableListOf<String>()
        val body = mutableListOf<String>()
        var doc: String? = null
        var firstCode = -1
        var cut = -1
        var depth = 0
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(e: PsiElement) {
                if (e is PsiComment) {
                    if (doc == null && firstCode < 0 && e.text.startsWith("/**")) doc = e.text
                    return
                }
                if (e is PsiWhiteSpace) return
                if (e.firstChild != null) return super.visitElement(e)
                val token = e.text
                if (token.isBlank()) return
                val offset = e.textRange.startOffset - base
                if (firstCode < 0) firstCode = offset
                if (cut >= 0) {
                    body += token
                    return
                }
                when {
                    token == "(" || token == "[" -> depth++
                    token == ")" || token == "]" -> depth = maxOf(0, depth - 1)
                    depth == 0 && (token == "{" || token == "=" || (python && token == ":")) -> {
                        cut = offset
                        body += token
                        if (!withBody) stopWalking()
                        return
                    }
                }
                signature += token
            }
        })
        val header = if (firstCode < 0) "" else text.substring(firstCode, if (cut >= 0) cut else text.length).replace(WHITESPACE, " ").trim()
        return DeclarationShape(header, signature.joinToString(" "), if (withBody) body.joinToString(" ") else null, doc, cut)
    }

    /** Declarations directly inside [root] (a class or a file), not looking into other declarations. */
    fun members(root: PsiElement): List<PsiElement> {
        val found = mutableListOf<PsiElement>()
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(e: PsiElement) {
                if (found.size >= MAX_MEMBERS) return stopWalking()
                if (e !== root && e !is PsiFile && isMemberKind(kindOf(e))) {
                    found += e
                    return
                }
                super.visitElement(e)
            }
        })
        return found
    }

    /**
     * The signatures of the declarations inside [root], sorted, with nested types' members folded into their entry as
     * `signature{members}`. Bodies of functions are left out: this is the structure of [root], not its code.
     */
    fun outlineKeys(root: PsiElement, budget: IntArray = intArrayOf(MAX_MEMBERS)): List<String> =
        members(root).mapNotNull { member ->
            if (budget[0]-- <= 0) return@mapNotNull null
            val signature = shape(member, withBody = false).signatureKey
            if (kindOf(member) == SymbolKind.CLASS) "$signature{${outlineKeys(member, budget).joinToString(";")}}" else signature
        }.sorted()

    /** Owner types of [element], outermost first. */
    fun owners(element: PsiElement): List<String> =
        generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }
            .filter { kindOf(it) == SymbolKind.CLASS }
            .mapNotNull { (it as? com.intellij.psi.PsiNamedElement)?.name }
            .toList().asReversed()

    private val PACKAGE = Regex("(?m)^\\s*package\\s+([\\p{L}_][\\p{L}\\p{N}_.]*)")
    private val WHITESPACE = Regex("\\s+")

    /** Package segments declared at the top of [file] (`package a.b`), empty for languages without one. */
    fun packageOf(file: PsiFile): List<String> =
        PACKAGE.find(file.text.take(4_000))?.groupValues?.get(1)?.split('.')?.filter { it.isNotEmpty() }.orEmpty()
}
