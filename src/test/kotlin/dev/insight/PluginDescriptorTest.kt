package dev.insight

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the K2-compatibility declaration required by `verifyPluginStructure`:
 * a plugin that depends on `org.jetbrains.kotlin` must declare
 * `supportsKotlinPluginMode` in the main `plugin.xml`.
 */
class PluginDescriptorTest {
    private fun loadDescriptor(name: String): Element =
        requireNotNull(javaClass.getResource("/META-INF/$name")) { "missing /META-INF/$name" }
            .openStream().use { stream ->
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder().parse(stream)
            }.documentElement

    private fun childElements(element: Element): Sequence<Element> =
        (0 until element.childNodes.length).asSequence()
            .map { element.childNodes.item(it) }
            .filterIsInstance<Element>()

    /** All `supportsKotlinPluginMode` declarations, each paired with its descriptor file name. */
    private fun kotlinModeDeclarations(vararg names: String): List<Pair<String, Element>> =
        names.flatMap { name ->
            childElements(loadDescriptor(name))
                .filter { it.tagName == "extensions" && it.getAttribute("defaultExtensionNs") == "org.jetbrains.kotlin" }
                .flatMap { childElements(it) }
                .filter { it.tagName == "supportsKotlinPluginMode" }
                .map { name to it }
        }

    @Test fun `kotlin plugin mode is declared once in the main plugin descriptor`() {
        val declarations = kotlinModeDeclarations("plugin.xml", "call-graph-kotlin.xml", "call-graph-java.xml")
        assertEquals(
            "expected a single supportsKotlinPluginMode declaration, found in: ${declarations.map { it.first }}",
            1,
            declarations.size,
        )
        val (file, element) = declarations.single()
        assertEquals("plugin.xml", file)
        assertEquals("true", element.getAttribute("supportsK2"))
    }

    /** The plugin must still load without Git, so Git code is only registered through the optional descriptor. */
    @Test fun `git integration for the diff is optional`() {
        val git = childElements(loadDescriptor("plugin.xml")).single { it.tagName == "depends" && it.textContent.trim() == "Git4Idea" }
        assertEquals("true", git.getAttribute("optional"))
        assertEquals("diff-git.xml", git.getAttribute("config-file"))
        val providers = childElements(loadDescriptor("diff-git.xml"))
            .filter { it.tagName == "extensions" && it.getAttribute("defaultExtensionNs") == "dev.insight" }
            .flatMap { childElements(it) }.toList()
        assertEquals(listOf("diffBaseProvider", "reviewDiffProvider"), providers.map { it.tagName })
    }
}
