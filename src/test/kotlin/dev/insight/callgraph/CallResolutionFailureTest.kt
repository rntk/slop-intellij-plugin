package dev.insight.callgraph

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException

class CallResolutionFailureTest : BasePlatformTestCase() {
    private fun reference(failure: RuntimeException) = object : PsiReferenceBase<PsiElement>(
        myFixture.configureByText("reference.txt", "missing")
    ) {
        override fun resolve(): PsiElement = throw failure
        override fun getVariants(): Array<Any> = emptyArray()
    }

    fun testBrokenReferenceRemainsUnresolved() {
        assertTrue(resolveTargets(reference(IllegalStateException("Broken resolver"))).isEmpty())
    }

    fun testCancellationIsNeverConvertedIntoAnUnresolvedReference() {
        for (failure in listOf(CancellationException("Cancelled"), ProcessCanceledException())) {
            try {
                resolveTargets(reference(failure))
                fail("Cancellation must propagate")
            } catch (actual: RuntimeException) {
                assertSame(failure, actual)
            }
        }
    }
}
