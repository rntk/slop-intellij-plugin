package dev.insight.graph

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

/**
 * A graph snapshot plus pointers for navigation. Pointers are indexed by node id and are null
 * for code that was edited during the search.
 */
class CallGraphResult(val graph: CallGraph, val pointers: List<SmartPsiElementPointer<PsiElement>?>,
    val occurrences: List<CallOccurrence> = emptyList())
