package dev.insight.codereview.map

import com.google.gson.JsonObject
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.codereview.map.CodeMapFixture.file
import dev.insight.codereview.map.CodeMapFixture.unit
import dev.insight.codereview.map.CodeMapFixture.use
import dev.insight.review.ReviewSummaries
import dev.insight.review.Summary
import dev.insight.review.SummarySegment
import dev.insight.review.SummaryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMapSummariesTest {
    private val command = unit("src/Command.kt", "Command", 1..5, members = listOf("run", "stop"))
    private val workflow = unit("src/Workflow.kt", "Workflow", 3..12)
    private val step1 = unit("src/S1.kt", "S1")
    private val step2 = unit("src/S2.kt", "S2")
    private val step3 = unit("src/S3.kt", "S3")
    private val lonely = unit("src/Lonely.kt", "Lonely")
    private val test = unit("src/test/WorkflowTest.kt", "WorkflowTest")
    private val units = listOf(command, workflow, step1, step2, step3, lonely, test)
    private val references = listOf(use(command, workflow), use(workflow, step1), use(workflow, step2), use(workflow, step3), use(test, workflow),
        CodeReference(test.id, command.id, CodeReferenceKind.CALL, null, "src/Command.kt#Command.run"))
    private val map = clusterCode(relateCode(units, references), files = 7)
    private val files = units.map { file(it.path) }
    private val snapshot = CodeSnapshot(CodeScope("src", "/abs/src"), files)
    private val tree = buildCodeMapTree(map, files)

    @Test
    fun `the tree holds a branch per flow with its units in chain order and one for standalone units`() {
        val root = tree.root
        assertEquals(CODE_MAP_ROOT_IDENTITY, root.identity)
        val branches = root.children.map(tree::get)
        assertEquals(listOf(FLOW_IDENTITY_PREFIX + map.flowOf(command.id)!!.id, STANDALONE_FLOW_IDENTITY), branches.map { it.identity })
        val leaves = branches.first().children.map(tree::get)
        assertEquals(map.flowOf(command.id)!!.units.map { UNIT_IDENTITY_PREFIX + it }, leaves.map { it.identity })
        assertEquals(SummaryKind.CHUNK, leaves.first().kind)
        assertEquals(1..5, leaves.first().lines)
        assertEquals(listOf(UNIT_IDENTITY_PREFIX + lonely.id), branches.last().children.map { tree[it].identity })
    }

    @Test
    fun `a retitled flow keeps the key of its branch`() {
        val retitled = buildCodeMapTree(map.withTitles(mapOf(map.flowOf(command.id)!!.id to "Run commands")), files)
        assertEquals(tree.nodes.map { it.key }, retitled.nodes.map { it.key })
    }

    @Test
    fun `a leaf shows its own numbered lines and how it relates to other units`() {
        val evidence = CodeMapSummaryEvidence(map, snapshot)
        val leaf = tree.nodes.single { it.identity == UNIT_IDENTITY_PREFIX + workflow.id }
        val item = JsonObject()
        evidence.describe(leaf, item, 10_000)
        assertEquals("Workflow", item.get("unit").asString)
        assertEquals("class", item.get("unit_kind").asString)
        assertTrue(item.get("code").asString.startsWith("3|line 3\n4|line 4"))
        assertTrue(item.get("code").asString.endsWith("12|line 12"))
        assertEquals(3..12, evidence.rows(leaf))
        val relations = item.getAsJsonArray("relations").map { it.asString }
        assertTrue(relations.toString(), "is the only user of S1 (src/S1.kt)" in relations)
        assertTrue(relations.toString(), relations.any { it.startsWith("used only by Command") })
        assertTrue(relations.toString(), relations.any { it.startsWith("tested by WorkflowTest") })

        val cut = JsonObject()
        evidence.describe(leaf, cut, 20)
        assertTrue(cut.get("code_cut").asBoolean)
        assertTrue(cut.get("code").asString.length <= 20)
    }

    @Test
    fun `a flow branch carries its chain and the root its counts`() {
        val evidence = CodeMapSummaryEvidence(map, snapshot)
        val branch = tree.nodes.single { it.identity == FLOW_IDENTITY_PREFIX + map.flowOf(command.id)!!.id }
        val item = JsonObject()
        evidence.describeBranch(branch, item)
        val chain = item.getAsJsonArray("chain").map { it.asJsonObject }
        assertEquals(listOf("root", "follows", "follows", "follows", "follows", "test"), chain.map { it.get("role").asString })
        assertEquals("Command (src/Command.kt) is the only user of Workflow (src/Workflow.kt)", chain[1].get("relation").asString)
        assertTrue(item.has("entry"))
        val root = JsonObject()
        evidence.describeBranch(tree.root, root)
        assertEquals(1, root.get("flows").asInt)
        assertEquals(1, root.get("standalone_units").asInt)
        assertTrue(evidence.branchSystem("").contains("A node of kind \"topic\" is a flow"))
        assertTrue(evidence.leafSystem(tree, "").contains("Every node in \"nodes\" is one unit"))
    }

    @Test
    fun `the presentation reads a flow from its roots and folds units that follow one unit the same way`() {
        val written = ReviewSummaries(tree).summarize(tree.nodes.filter { it.children.isEmpty() }.associate { it.id to summary("Does ${it.label}. More detail.") })
        val model = CodeMapPresentation(CodeMapCard(map, written, running = false, canRun = true))
        assertEquals(CodeMapAction.FILL, model.action)
        assertEquals("Code map · 1 flow · 0 shared · 1 standalone · 7 units in 7 files", model.header)
        val entries = model.entries
        assertEquals(listOf(map.flowOf(command.id)!!.id, STANDALONE_FLOWS), entries.map { it.id })
        assertTrue(entries.first().entry)
        val detail = model.detail(entries.first().id)!!
        assertEquals(listOf("Roots", "Uses", "Tests"), detail.sections.map { it.title })
        val uses = detail.sections[1]
        assertEquals(listOf(workflow.id), (uses.items.first() as SingleUnit).row.unit.id.let(::listOf))
        val fan = uses.items.last() as UnitFan
        assertEquals("3 used only by Workflow", fan.title)
        assertEquals(listOf(step1.id, step2.id, step3.id), fan.rows.map { it.unit.id })
        val root = detail.rows.first()
        assertEquals("Does Command.", root.gist)
        assertEquals("More detail.", root.more)
        assertEquals(listOf("run", "stop"), root.members.map { it.member.name.substringAfter('.') })
        assertEquals(listOf("WorkflowTest"), root.members.first().usedBy)
        assertEquals("src/Command.kt:1-5", root.location)
        assertTrue(detail.keys.containsAll(listOf("section:ROOT", fan.key, root.key)))
        assertNull(detail.summary)

        val complete = written.summarize(tree.nodes.filter { it.children.isNotEmpty() }.associate { it.id to summary("Branch.") })
        assertEquals(CodeMapAction.REGENERATE, CodeMapPresentation(CodeMapCard(map, complete, false, true)).action)
        assertEquals(CodeMapAction.GENERATE, CodeMapPresentation(CodeMapCard(null, complete, false, true)).action)
        assertFalse(CodeMapPresentation(CodeMapCard(map, complete, false, true)).empty)
    }

    @Test
    fun `a flow lists the shared code it uses and a foundation the flows that share it`() {
        val base = unit("src/Base.kt", "Base")
        val users = (1..6).map { unit("src/U$it.kt", "U$it") }
        val own = unit("src/U1Own.kt", "U1Own")
        val shared = clusterCode(relateCode(listOf(base, own) + users, users.map { use(it, base) } + use(users.first(), own)))
        val sharedTree = buildCodeMapTree(shared, (listOf(base, own) + users).map { file(it.path) })
        val model = CodeMapPresentation(CodeMapCard(shared, ReviewSummaries(sharedTree), false, true))
        val flow = model.detail(shared.flowOf(users.first().id)!!.id)!!
        assertEquals(listOf(base.id), flow.shared.map { it.unit.id })
        assertEquals(listOf("U1"), flow.shared.single().users)
        assertEquals(shared.flowOf(base.id)!!.id, flow.shared.single().owner!!.entry)
        val foundation = model.detail(shared.flowOf(base.id)!!.id)!!
        assertEquals(listOf(shared.flowOf(users.first().id)!!.id, STANDALONE_FLOWS), foundation.sharers.map { it.entry })
        assertTrue(model.entries.single { it.id == shared.flowOf(base.id)!!.id }.foundation)
    }

    @Test
    fun `the reply reports the map's shape until every summary is written`() {
        val reply = codeMapReply(7, map, ReviewSummaries(tree), 0, CodeMapRun(map, 2, 0, 0, null))
        assertTrue(reply, reply.startsWith("Mapped 7 units into 1 flow, 0 shared foundations and 1 standalone unit."))
        assertTrue(reply, reply.endsWith("[Run /code-map again to write the missing summaries.]"))
        assertEquals("There are no files in scope to map.", codeMapReply(0, null, ReviewSummaries(tree), 0))
    }

    private fun summary(text: String) = Summary(listOf(SummarySegment(text)))
}
