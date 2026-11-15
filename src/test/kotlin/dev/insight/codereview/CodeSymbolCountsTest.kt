package dev.insight.codereview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.RadarSymbolKind.*
import dev.insight.review.RadarSymbols

class CodeSymbolCountsTest : BasePlatformTestCase() {
    private fun counts(name: String, text: String): RadarSymbols =
        ReadAction.compute<RadarSymbols?, Throwable> {
            codeSymbolCounts(project, name, FileTypeManager.getInstance().getFileTypeByFileName(name), text)
        } ?: error("No symbol support for $name")

    fun testKotlinCountsNestedDeclarationsAndCommentBlocks() {
        val result = counts("Example.kt", """
            /** A class with a documentation tag. @see Example */
            class Example(val value: Int) {
                // One line comment
                fun work(input: Int): Int {
                    val local = input
                    fun nested() = local
                    return nested()
                }
            }
            /* A multiline
               comment */
            const val LIMIT = 10
        """.trimIndent())
        assertEquals(1, result[CLASS])
        assertEquals(3, result[FUNCTION]) // Includes the primary constructor.
        assertEquals(1, result[PROPERTY])
        assertEquals(1, result[VARIABLE])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[CONSTANT])
        assertEquals(3, result[COMMENT])
        assertEquals(8, result.declarations)
    }

    fun testJavaConstantsAreSeparateFromPropertiesAndLocalVariables() {
        val result = counts("Example.java", """
            /** Documentation with {@link String}. */
            class Example {
                static final int LIMIT = 3;
                int field;
                void work(int input) {
                    final int local = input;
                    String text = "// not a comment";
                }
            }
        """.trimIndent())
        assertEquals(1, result[CLASS])
        assertEquals(1, result[FUNCTION])
        assertEquals(1, result[PROPERTY])
        assertEquals(2, result[VARIABLE])
        assertEquals(1, result[CONSTANT])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[COMMENT])
    }

    fun testJavaScriptFunctionBindingsAreCountedOnce() {
        val result = counts("example.js", "// comment\nconst LIMIT = 3; let value = 1; const work = (input) => input + LIMIT;")
        assertEquals(1, result[FUNCTION])
        assertEquals(1, result[CONSTANT])
        assertEquals(1, result[VARIABLE])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[COMMENT])
    }

    fun testGoConstants() {
        val result = counts("example.go", "package example\n// comment\nconst Limit = 3\nvar value = 1\nfunc work(input int) int { return input }\n")
        assertEquals(1, result[FUNCTION])
        assertEquals(1, result[CONSTANT])
        assertEquals(1, result[VARIABLE])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[COMMENT])
    }

    fun testRustConstantsAndStatics() {
        val result = counts("example.rs", "// comment\nconst LIMIT: i32 = 3;\nstatic VALUE: i32 = 1;\nfn work(input: i32) -> i32 { let local = input; local }\n")
        assertEquals(1, result[FUNCTION])
        assertEquals(1, result[CONSTANT])
        assertEquals(2, result[VARIABLE])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[COMMENT])
    }

    fun testPythonAssignmentsAndComments() {
        val result = counts("example.py", "# comment\nvalue = 1\nvalue = 2\ndef work(input):\n    local = input\n    return local\n")
        assertEquals(1, result[FUNCTION])
        assertEquals(2, result[VARIABLE])
        assertEquals(1, result[PARAMETER])
        assertEquals(1, result[COMMENT])
    }

    fun testCapturedTextIsUsedInsteadOfCurrentProjectContents() {
        myFixture.addFileToProject("Example.kt", "class Current { fun newer() = 1 }")
        val result = counts("Example.kt", "fun captured() = 1\n")
        assertEquals(0, result[CLASS])
        assertEquals(1, result[FUNCTION])
    }

    fun testUnsupportedFilesAreUnavailableRatherThanZero() {
        val result = ReadAction.compute<RadarSymbols?, Throwable> {
            codeSymbolCounts(project, "notes.txt", com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, "class NotCode {}")
        }
        assertNull(result)
        assertEquals(RadarSymbols(), counts("empty.kt", ""))
    }
}
