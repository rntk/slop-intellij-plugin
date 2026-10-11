package dev.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the installability regression where `untilBuild = "252.*"` blocked
 * installation on IDE build 262 (IU-262.x): the declared compatibility range
 * must cover branch 262 while still building against the lowest supported
 * platform (Java 21 level).
 */
class BuildRangeTest {
    private fun buildScript(): File {
        var dir = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            val candidate = File(dir, "build.gradle.kts")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: error("build.gradle.kts not found from ${System.getProperty("user.dir")}")
        }
    }

    private fun literal(text: String, key: String): String? =
        Regex("""$key\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)

    private fun branchOf(value: String): Int =
        Regex("""^(\d+)""").find(value.trim())?.groupValues?.get(1)?.toInt()
            ?: error("unparseable build value: $value")

    @Test fun `compatibility range covers IDE build 262`() {
        val text = buildScript().readText()
        val since = literal(text, "sinceBuild")
        val until = literal(text, "untilBuild")
        val openEnded = until == null &&
            Regex("""untilBuild\s*=\s*provider\s*\{\s*null\s*}""").containsMatchIn(text)

        requireNotNull(since) { "sinceBuild must be declared in build.gradle.kts" }
        assertTrue(
            "sinceBuild $since must not exclude older supported IDEs (branch <= 262)",
            branchOf(since) <= 262,
        )
        assertTrue(
            "untilBuild must cover IDE branch 262 (IU-262.x); found: ${until ?: "open-ended"}",
            openEnded || (until != null && branchOf(until) >= 262),
        )
    }

    @Test fun `effective plugin descriptor allows IDE build 262`() {
        // The test classpath serves the patched descriptor (patchPluginXml stamps
        // since-build/until-build from build.gradle.kts), i.e. what the IDE reads.
        val url = requireNotNull(javaClass.getResource("/META-INF/plugin.xml")) { "missing /META-INF/plugin.xml" }
        url.openStream().use { stream ->
            val root = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(stream).documentElement
            val versions = (0 until root.childNodes.length)
                .map { root.childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>()
                .filter { it.tagName == "idea-version" }
            assertEquals("expected a single idea-version in the effective descriptor", 1, versions.size)
            val until = versions.single().getAttribute("until-build")
            assertTrue(
                "effective until-build must cover IDE branch 262 (IU-262.x); found: '$until'",
                until.isEmpty() || branchOf(until.trimEnd('*').trimEnd('.')) >= 262,
            )
        }
    }
}
