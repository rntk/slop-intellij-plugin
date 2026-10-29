package dev.insight.graph

import java.awt.Color

/**
 * Swimlane presentation of a displayed [CallGraph], like a service blueprint: a title banner, one titled column per
 * section with a short annotation above its nodes, and an optional footer of cross-cutting items. Ids are displayed ids.
 */
data class GraphSections(
    val title: String,
    val subtitle: String? = null,
    val sections: List<Section>,
    /** Displayed id → index into [sections]. Nodes without an entry are not drawn, nor are their relationships. */
    val sectionOf: Map<Int, Int>,
    val footer: SectionFooter? = null,
)

data class Section(
    val title: String,
    /** Bold lead of the annotation, for example an LLM group label. */
    val headline: String? = null,
    /** Plain text continuing the [headline]. */
    val description: String? = null,
    /** Small print under the annotation: counts and dominant judgments. */
    val facts: List<String> = emptyList(),
    /** Tint of the section; a neutral tint when null. */
    val color: Color? = null,
    /** Extra tooltip lines for the section header. */
    val tooltip: String? = null,
)

data class SectionFooter(val title: String, val items: List<FooterItem>)

data class FooterItem(val headline: String, val text: String? = null)
