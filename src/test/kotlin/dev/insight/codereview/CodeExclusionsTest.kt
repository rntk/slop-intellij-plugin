package dev.insight.codereview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeExclusionsTest {
    @Test fun `a name pattern matches files and folders at any depth`() {
        val exclusions = CodeExclusions(listOf("*.js", "test", "generated/"))
        assertTrue(exclusions.excludes("web/app.js"))
        assertTrue(exclusions.excludes("app.js"))
        assertFalse(exclusions.excludes("web/app.jsx"))
        assertTrue(exclusions.excludes("src/test/A.kt"))
        assertTrue(exclusions.excludes("test"))
        assertFalse(exclusions.excludes("src/testing/A.kt"))
        // A trailing slash matches folders only.
        assertTrue(exclusions.excludes("src/generated/A.kt"))
        assertFalse(exclusions.excludes("src/generated"))
        assertTrue(exclusions.matches("src/generated", directory = true))
    }

    @Test fun `a path pattern matches from the project root or below the base folder`() {
        val exclusions = CodeExclusions(listOf("src/legacy", "web/**/*.js", "/Root.kt"), base = "app")
        assertTrue(exclusions.excludes("src/legacy/Old.kt"))
        assertTrue(exclusions.excludes("app/src/legacy/Old.kt"))
        assertFalse(exclusions.excludes("lib/src/legacy/Old.kt"))
        assertTrue(exclusions.excludes("web/app.js"))
        assertTrue(exclusions.excludes("web/a/b/app.js"))
        assertFalse(exclusions.excludes("web/a/b/app.ts"))
        assertTrue(exclusions.excludes("Root.kt"))
        assertFalse(exclusions.excludes("src/Root.kt"))
    }

    @Test fun `folders at or above from are not checked`() {
        val exclusions = CodeExclusions(listOf("test/"))
        assertTrue(exclusions.excludes("test/unit/A.kt"))
        assertFalse(exclusions.excludes("test/unit/A.kt", from = "test/unit"))
        assertFalse(exclusions.excludes("test/unit/A.kt", from = "test"))
        assertTrue(exclusions.excludes("test/unit/test/A.kt", from = "test/unit"))
    }

    @Test fun `parse splits on commas outside braces and validates`() {
        assertEquals(listOf("*.js", "src/{a,b}/**", "test/", "x.kt"), CodeExclusions.parse(" *.js, src/{a,b}/** ;test/\n./x.kt,, *.js"))
        assertEquals(listOf("src/legacy"), CodeExclusions.parse("src\\legacy"))
        assertThrows(IllegalArgumentException::class.java) { CodeExclusions.parse("src/../x") }
        assertThrows(IllegalArgumentException::class.java) { CodeExclusions.parse("src/[ab") }
        assertThrows(IllegalArgumentException::class.java) { CodeExclusions.parse("/") }
        val braces = CodeExclusions(CodeExclusions.parse("src/{a,b}/**"))
        assertTrue(braces.excludes("src/a/x.kt"))
        assertFalse(braces.excludes("src/c/x.kt"))
    }

    @Test fun `exact and type patterns escape special characters and round-trip through parse`() {
        val page = CodeExclusions.exact("web/pages/[id].tsx", directory = false)
        val folder = CodeExclusions.exact("a,b", directory = true)
        val type = CodeExclusions.ofType("web/{x}", "js")
        val exclusions = CodeExclusions(CodeExclusions.parse(CodeExclusions.format(listOf(page, folder, type))))
        assertEquals(3, exclusions.patterns.size)
        assertTrue(exclusions.excludes("web/pages/[id].tsx"))
        assertFalse(exclusions.excludes("web/pages/i.tsx"))
        assertTrue(exclusions.excludes("a,b/c.kt"))
        assertFalse(exclusions.excludes("x/a,b/c.kt"))
        assertTrue(exclusions.excludes("web/{x}/deep/app.js"))
        assertFalse(exclusions.excludes("web/x/app.js"))
        assertTrue(CodeExclusions(listOf(CodeExclusions.ofType("", "js"))).excludes("a/b.js"))
    }
}
