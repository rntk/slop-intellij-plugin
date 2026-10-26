package dev.insight.prompts

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

class PromptLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `example metadata remains intact and searchable`() {
        val content = "---\r\ntitle: 'Review Code'\r\ndescription: Find bugs\r\ntags: code, quality\r\n---\r\n\r\n# Other title\r\nBody"
        val item = PromptItem.parse("review.md", PromptKind.PROMPT, content)
        assertEquals("Review Code", item.title)
        assertEquals("Find bugs", item.description)
        assertTrue(item.matches("QUALITY"))
        assertEquals(content, item.content)
        assertEquals("Heading", PromptItem.parse("test.md", PromptKind.PROMPT, "# Heading\nText").title)
    }

    @Test fun `library supports separate categories create overwrite refresh and delete`() {
        val root = temporary.root.toPath()
        val repository = PromptLibrary(root)
        assertTrue(repository.list().isEmpty())
        repository.save(PromptKind.PROMPT, "review", "# Review")
        repository.save(PromptKind.APPENDIX, "review", "# Appendix")
        assertEquals(2, repository.list().size)
        assertThrows(FileAlreadyExistsException::class.java) { repository.save(PromptKind.PROMPT, "review", "Lost") }
        repository.save(PromptKind.PROMPT, "review", "# Updated", overwrite = true)
        assertEquals("# Updated", PromptLibrary(root).list().single { it.kind == PromptKind.PROMPT }.content)
        repository.delete(repository.list().single { it.kind == PromptKind.APPENDIX })
        assertEquals(1, repository.list().size)
    }

    @Test fun `unsafe filenames and symbolic link writes cannot escape library`() {
        for (name in listOf("", "../escape", "nested/file", "nested\\file", ".hidden", "bad name", "/tmp/file")) {
            assertThrows(IllegalArgumentException::class.java) { PromptLibrary.safeFilename(name) }
        }
        val root = temporary.newFolder("library").toPath()
        val outside = temporary.newFile("outside.md").toPath()
        Files.writeString(outside, "Preserve")
        Files.createDirectories(root.resolve("prompts"))
        Files.createSymbolicLink(root.resolve("prompts/link.md"), outside)
        val repository = PromptLibrary(root)
        assertTrue(repository.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.save(PromptKind.PROMPT, "link", "Changed", true) }
        assertEquals("Preserve", Files.readString(outside))
    }

    @Test fun `composition handles empty drafts and adds a separator only between components`() {
        assertEquals("Snippet", appendPrompt("  ", " Snippet "))
        assertEquals("Base\n\n---\n\nSnippet", appendPrompt("Base\n", "Snippet"))
        assertEquals("Base", appendPrompt("Base", ""))
    }

    @Test fun `preview escapes HTML and closes unclosed code fences`() {
        val html = promptPreview("# Title\n<script>alert(1)</script>\n```\n<img src=x>")
        assertTrue(html.contains("<h1>Title</h1>"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.endsWith("</pre></body></html>"))
    }
}
