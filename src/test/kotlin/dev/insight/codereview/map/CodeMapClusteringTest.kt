package dev.insight.codereview.map

import dev.insight.codereview.map.CodeMapFixture.unit
import dev.insight.codereview.map.CodeMapFixture.use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMapClusteringTest {
    private val command = unit("src/Command.kt", "Command")
    private val workflow = unit("src/Workflow.kt", "Workflow")
    private val parser = unit("src/Parser.kt", "Parser")
    private val other = unit("src/Other.kt", "Other")

    private fun CodeGraph.relation(from: CodeUnit, to: CodeUnit): CodeRelation? = edges.firstOrNull { it.from == from.id && it.to == to.id }?.relation

    @Test
    fun `a unit with one user is owned by it and the flow starts at the root nothing uses`() {
        val graph = relateCode(listOf(command, workflow, parser, other), listOf(use(command, workflow), use(workflow, parser)))
        assertEquals(CodeRelation.SOLE_USE, graph.relation(command, workflow))
        assertEquals(CodeRelation.SOLE_USE, graph.relation(workflow, parser))
        val map = clusterCode(graph)
        val flow = map.flowOf(command.id)!!
        assertEquals(listOf(command.id, workflow.id, parser.id), flow.units)
        assertEquals(listOf(FlowRole.ROOT, FlowRole.FOLLOWS, FlowRole.FOLLOWS), flow.steps.map { it.role })
        assertEquals(listOf(0, 1, 2), flow.steps.map { it.depth })
        assertTrue("The root is used by nothing in scope", flow.entry)
        assertTrue(map.flowOf(other.id)!!.standalone)
        assertEquals(listOf(flow.id, map.flowOf(other.id)!!.id), map.flows.map { it.id })
    }

    @Test
    fun `a unit with a few users is linked by a medium edge that a model judges`() {
        val a = unit("src/A.kt", "A")
        val b = unit("src/B.kt", "B")
        val helper = unit("src/Helper.kt", "Helper")
        val aOwn = unit("src/AOwn.kt", "AOwn")
        val bOwn = unit("src/BOwn.kt", "BOwn")
        val graph = relateCode(listOf(a, b, helper, aOwn, bOwn), listOf(use(a, helper), use(b, helper), use(a, aOwn), use(b, bOwn)))
        assertEquals(CodeRelation.USES, graph.relation(a, helper))
        assertEquals(2, graph.fanIn[helper.id])
        val map = clusterCode(graph)
        assertTrue(map.flowOf(helper.id)!!.standalone)
        assertEquals(listOf(a.id, aOwn.id), map.flowOf(a.id)!!.units)
        assertTrue("Flows linked only by the standalone helper are not merge candidates", flowMergeCandidates(map).isEmpty())
        assertEquals(setOf(map.flowOf(a.id)!!.id, map.flowOf(b.id)!!.id), flowAttachCandidates(map, map.flowOf(helper.id)!!).map { it.id }.toSet())
    }

    @Test
    fun `a widely used unit is a shared foundation that its users list instead of joining`() {
        val base = unit("src/Tool.kt", "Tool")
        val baseHelper = unit("src/ToolHelper.kt", "ToolHelper")
        val users = (1..6).map { unit("src/T$it.kt", "T$it") }
        val graph = relateCode(listOf(base, baseHelper) + users,
            users.map { use(it, base, CodeReferenceKind.INHERITS) } + use(base, baseHelper), CodeMapOptions(sharedFanIn = 6))
        assertEquals(setOf(base.id), graph.shared)
        assertEquals(CodeRelation.SHARED_USE, graph.relation(users.first(), base))
        val map = clusterCode(graph)
        val foundation = map.flowOf(base.id)!!
        assertTrue(foundation.foundation)
        assertFalse(foundation.standalone)
        assertEquals(listOf(base.id, baseHelper.id), foundation.units)
        users.forEach { user ->
            val flow = map.flowOf(user.id)!!
            assertEquals(listOf(user.id), flow.units)
            assertEquals(listOf(base.id), flow.shared)
        }
        assertEquals(6, map.sharers(foundation).size)
        assertEquals("Foundations follow the other flows", foundation.id, map.chains.last().id)
    }

    @Test
    fun `inheritance below the shared threshold groups a subclass with its base`() {
        val base = unit("src/Base.kt", "Base")
        val sub = unit("src/Sub.kt", "Sub")
        val user = unit("src/User.kt", "User")
        val graph = relateCode(listOf(base, sub, user), listOf(use(sub, base, CodeReferenceKind.INHERITS), use(user, base)))
        assertEquals(CodeRelation.EXTENDS, graph.relation(sub, base))
        assertEquals(setOf(base.id, sub.id), clusterCode(graph).flowOf(base.id)!!.units.toSet())
    }

    @Test
    fun `a test tests the unit its name names and only exercises the others`() {
        val test = unit("src/test/WorkflowTest.kt", "WorkflowTest")
        val graph = relateCode(listOf(command, workflow, parser, test),
            listOf(use(command, workflow), use(test, workflow), use(test, parser, count = 5)))
        assertEquals(CodeRelation.TESTS, graph.relation(test, workflow))
        assertEquals(CodeRelation.ALSO_TESTS, graph.relation(test, parser))
        assertEquals("Test users do not count as users of the code", 1, graph.fanIn[workflow.id])
        val flow = clusterCode(graph).flowOf(workflow.id)!!
        assertEquals(listOf(command.id, workflow.id, test.id), flow.units)
        assertEquals(FlowRole.TEST, flow.steps.last().role)
    }

    @Test
    fun `a test without a matching name tests the unit it refers to most`() {
        val test = unit("src/test/ScenarioTest.kt", "ScenarioTest")
        val graph = relateCode(listOf(workflow, parser, test), listOf(use(test, workflow), use(test, parser, count = 3)))
        assertEquals(CodeRelation.TESTS, graph.relation(test, parser))
        assertEquals(CodeRelation.ALSO_TESTS, graph.relation(test, workflow))
    }

    @Test
    fun `documentation joins the flow it mentions most`() {
        val doc = unit("docs/flow.md")
        val graph = relateCode(listOf(command, workflow, parser, doc),
            listOf(use(command, workflow), CodeReference(doc.id, workflow.id, CodeReferenceKind.MENTION, count = 3),
                CodeReference(doc.id, parser.id, CodeReferenceKind.MENTION)))
        assertEquals(CodeRelation.DOCUMENTS, graph.relation(doc, workflow))
        val map = clusterCode(graph)
        assertEquals(listOf(command.id, workflow.id, doc.id), map.flowOf(doc.id)!!.units)
        assertEquals(FlowRole.DOCUMENTATION, map.flowOf(doc.id)!!.steps.last().role)
    }

    @Test
    fun `a cycle starts at the unit that uses the most of the rest`() {
        val a = unit("src/A.kt", "A")
        val b = unit("src/B.kt", "B")
        val c = unit("src/C.kt", "C")
        val graph = relateCode(listOf(a, b, c), listOf(use(a, b), use(b, c), use(c, a), use(b, a)))
        val flow = clusterCode(graph).flowOf(a.id)!!
        assertEquals(b.id, flow.steps.first().unit)
        assertEquals(FlowRole.ROOT, flow.steps.first().role)
        assertFalse("Every unit of a cycle has a user", flow.entry)
    }

    @Test
    fun `merged flows are recomputed as one chain and drop their titles`() {
        val a = unit("src/A.kt", "A")
        val aOwn = unit("src/AOwn.kt", "AOwn")
        val b = unit("src/B.kt", "B")
        val bOwn = unit("src/BOwn.kt", "BOwn")
        val shared = unit("src/Shared.kt", "Shared")
        val sharedOwn = unit("src/SharedOwn.kt", "SharedOwn")
        val graph = relateCode(listOf(a, aOwn, b, bOwn, shared, sharedOwn),
            listOf(use(a, aOwn), use(b, bOwn), use(aOwn, shared), use(bOwn, shared), use(shared, sharedOwn)))
        val map = clusterCode(graph).let { it.withTitles(it.flows.associate { flow -> flow.id to "T" }) }
        val candidates = flowMergeCandidates(map)
        assertEquals(2, candidates.size)
        val merged = map.mergedFlows(listOf(setOf(map.flowOf(a.id)!!.id, map.flowOf(shared.id)!!.id)))
        val flow = merged.flowOf(a.id)!!
        assertEquals(setOf(a.id, aOwn.id, shared.id, sharedOwn.id), flow.units.toSet())
        assertNull(flow.title)
        assertEquals("T", merged.flowOf(b.id)!!.title)
        assertEquals(FlowRole.ROOT, flow.steps.first().role)
        assertEquals(a.id, flow.steps.first().unit)
    }

    @Test
    fun `evidence kinds come from the path`() {
        assertEquals("test", codeEvidenceKind("src/test/kotlin/FooTest.kt"))
        assertEquals("test", codeEvidenceKind("pkg/test_foo.py"))
        assertEquals("test", codeEvidenceKind("web/foo.spec.ts"))
        assertEquals("documentation", codeEvidenceKind("flows/change-map.md"))
        assertEquals("implementation", codeEvidenceKind("src/main/kotlin/Testing.kt"))
    }

    @Test
    fun `documentation mentions file names and unique class names`() {
        val doc = unit("flows/code.md")
        val units = listOf(workflow, parser, unit("src/Two.kt", "Parser2"), doc)
        val text = "See [Workflow.kt](../src/Workflow.kt) and the Parser. Workflow.kt again; NotParser stays out."
        val mentions = documentationMentions(listOf(doc to text), units)
        assertEquals(mapOf(workflow.id to 3, parser.id to 1), mentions.associate { it.to to it.count })
        assertTrue(mentions.all { it.kind == CodeReferenceKind.MENTION && it.from == doc.id })
    }
}
