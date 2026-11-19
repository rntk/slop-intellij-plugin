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

    private fun structure(name: String, text: String): dev.insight.review.SourceStructure =
        ReadAction.compute<dev.insight.review.SourceStructure?, Throwable> {
            codeStructure(project, name, FileTypeManager.getInstance().getFileTypeByFileName(name), text)
        } ?: error("No symbol support for $name")

    fun testKotlinLensesAndOutlineNestMembersWithInclusiveCounts() {
        val result = structure("Example.kt", """
            import a.b.C
            import a.b.D
            interface Shape
            data class Point(val x: Int, val y: Int)
            enum class Color { RED }
            typealias Name = String
            object Registry {
                const val LIMIT = 3
                suspend fun load(items: List<Int>) = items.map { it + 1 }
                @Test fun loadsItems() {
                    val local = 1
                }
            }
        """.trimIndent())
        val symbols = result.symbols
        assertEquals(2, symbols[IMPORT])
        assertEquals(1, symbols[INTERFACE])
        assertEquals(1, symbols[STRUCT])
        assertEquals(1, symbols[ENUM])
        assertEquals(1, symbols[OBJECT])
        assertEquals(1, symbols[TYPE_ALIAS])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[ASYNC])
        assertEquals(1, symbols[TEST])
        assertEquals(1, symbols[LAMBDA])
        // Refining lenses never change the disjoint declaration total.
        assertEquals(symbols.counts.filterKeys { it.isBase && it != COMMENT }.values.sum(), symbols.declarations)

        val top = result.outline.declarations
        assertEquals(listOf("Shape", "Point", "Color", "Name", "Registry"), top.map { it.name })
        assertEquals(listOf(INTERFACE, STRUCT, ENUM, TYPE_ALIAS, OBJECT), top.map { it.kind })
        val registry = top.last()
        assertEquals(7..13, registry.lines)
        assertEquals(listOf("LIMIT", "load", "loadsItems"), registry.children.map { it.name })
        assertEquals(listOf(CONSTANT, FUNCTION, TEST), registry.children.map { it.kind })
        // Locals stay counts; the object counts its own members and itself.
        assertTrue(registry.children.last().children.isEmpty())
        assertEquals(2, registry.symbols[FUNCTION])
        assertEquals(1, registry.symbols[VARIABLE])
        assertEquals(1, registry.symbols[CLASS])
        // Constructor properties belong to the constructor entry.
        val point = top[1]
        assertEquals(listOf("x", "y"), point.children.single().children.map { it.name })
        assertEquals("data class Point(val x: Int, val y: Int)".length, point.bytes)
    }

    fun testJavaLenses() {
        val symbols = structure("Example.java", """
            import java.util.List;
            interface Shape {}
            record Point(int x, int y) {}
            enum Color { RED }
            class Example {
                Example() {}
                @Test void works() { Runnable r = () -> {}; }
            }
        """.trimIndent()).symbols
        assertEquals(1, symbols[IMPORT])
        assertEquals(1, symbols[INTERFACE])
        assertEquals(1, symbols[STRUCT])
        assertEquals(1, symbols[ENUM])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[TEST])
        assertEquals(1, symbols[LAMBDA])
    }

    fun testGoLenses() {
        val symbols = structure("example_test.go", """
            package example
            import (
                "fmt"
                "testing"
            )
            type Shape interface{}
            type Point struct{}
            type Name = string
            func NewPoint() Point { go func() { fmt.Println() }(); return Point{} }
            func TestPoint(t *testing.T) {}
        """.trimIndent()).symbols
        assertEquals(2, symbols[IMPORT])
        assertEquals(1, symbols[INTERFACE])
        assertEquals(1, symbols[STRUCT])
        assertEquals(1, symbols[TYPE_ALIAS])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[TEST])
        assertEquals(1, symbols[ASYNC])
        assertEquals(1, symbols[LAMBDA])
    }

    fun testPythonLenses() {
        val symbols = structure("example.py", """
            from dataclasses import dataclass
            from enum import Enum
            @dataclass
            class Point:
                x: int
            class Color(Enum):
                RED = 1
            class Service:
                def __init__(self):
                    self.handler = lambda value: value
                async def load(self):
                    pass
            def test_service():
                pass
        """.trimIndent()).symbols
        assertEquals(2, symbols[IMPORT])
        assertEquals(1, symbols[STRUCT])
        assertEquals(1, symbols[ENUM])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[ASYNC])
        assertEquals(1, symbols[TEST])
        assertEquals(1, symbols[LAMBDA])
    }

    fun testRustLenses() {
        val result = structure("example.rs", """
            use std::fmt;
            trait Shape {}
            struct Point;
            enum Color { Red }
            type Name = String;
            impl Point {
                fn new() -> Self { let f = |x: i32| x; Point }
                async fn load(&self) {}
            }
            #[test]
            fn works() {}
        """.trimIndent())
        val symbols = result.symbols
        assertEquals(1, symbols[IMPORT])
        assertEquals(1, symbols[INTERFACE])
        assertEquals(1, symbols[STRUCT])
        assertEquals(1, symbols[ENUM])
        assertEquals(1, symbols[TYPE_ALIAS])
        assertEquals(1, symbols[IMPL])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[ASYNC])
        assertEquals(1, symbols[TEST])
        assertEquals(1, symbols[LAMBDA])
        assertEquals("impl Point", result.outline.declarations.single { it.kind == IMPL }.name)
    }

    fun testJavaScriptLenses() {
        val symbols = structure("example.test.ts", """
            import { a } from "./a";
            interface Shape {}
            enum Color { Red }
            type Name = string;
            class Service {
                constructor() {}
                async load() { [1].map(x => x); }
            }
            describe("service", () => {
                it("loads", () => {});
                test.only("saves", () => {});
            });
        """.trimIndent()).symbols
        assertEquals(1, symbols[IMPORT])
        assertEquals(1, symbols[INTERFACE])
        assertEquals(1, symbols[ENUM])
        assertEquals(1, symbols[TYPE_ALIAS])
        assertEquals(1, symbols[CONSTRUCTOR])
        assertEquals(1, symbols[ASYNC])
        assertEquals(2, symbols[TEST])
        assertEquals(4, symbols[LAMBDA])
    }
}
