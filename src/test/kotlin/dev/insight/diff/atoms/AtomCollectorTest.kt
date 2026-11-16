package dev.insight.diff.atoms

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.ReviewFile

/** Atoms and references read from real PSI: the after side is the live project file, the before side a string. */
class AtomCollectorTest : BasePlatformTestCase() {
    private fun review(path: String, before: String, after: String, status: String = "modified"): ReviewFile {
        val live = myFixture.addFileToProject(path, after)
        return atomTestFile(path, before, after, status).copy(absolutePath = live.virtualFile.path)
    }

    private fun facts(vararg files: ReviewFile): AtomFacts =
        ApplicationManager.getApplication().runReadAction<AtomFacts> { collectAtomFacts(project, files.toList()) }

    private fun map(vararg files: ReviewFile): ChangeMap =
        ApplicationManager.getApplication().runReadAction<ChangeMap> { changeMap(project, files.toList()) }

    private fun ChangeMap.edge(from: String, to: String): AtomRelation? = graph.edges.firstOrNull { it.from == from && it.to == to }?.relation

    private val greetBefore = "fun greet(name: String): String {\n    return name\n}\n"
    private val greetAfter = "fun greet(name: String, loud: Boolean): String {\n    return if (loud) name.uppercase() else name\n}\n"

    private val lib get() = review("src/Lib.kt", greetBefore, greetAfter)

    fun testUpdatedCallerOfChangedSignatureClustersWithItAndAnUnrelatedEditStaysApart() {
        val app = review("src/App.kt",
            "fun main() {\n    val a = greet(\"x\")\n}\n\nfun other() {\n    val b = 1\n}\n",
            "fun main() {\n    val a = greet(\"x\", true)\n}\n\nfun other() {\n    val b = 2\n}\n")
        val files = arrayOf(lib, app)
        val facts = facts(*files)
        assertEquals(listOf("src/Lib.kt#greet", "src/App.kt#main", "src/App.kt#other"), facts.atoms.map { it.id })
        assertTrue(facts.atoms.first().signatureChanged)
        assertEquals(listOf(AtomReference("src/App.kt#main", "src/Lib.kt#greet", ReferenceKind.CALL, siteChanged = true)), facts.references)

        val map = map(*files)
        assertEquals(AtomRelation.SIGNATURE_USE, map.edge("src/Lib.kt#greet", "src/App.kt#main"))
        assertEquals(setOf("src/Lib.kt#greet", "src/App.kt#main"), map.clusterOf("src/App.kt#main")!!.atoms.toSet())
        assertTrue(map.clusterOf("src/App.kt#other")!!.standalone)
    }

    fun testNewFunctionAndItsNewCallSiteFormOneChange() {
        val lib = review("src/Lib.kt", "fun keep() = 1\n", "fun keep() = 1\n\nfun fresh(): Int {\n    return 2\n}\n")
        val app = review("src/App.kt", "fun main() {\n    keep()\n}\n", "fun main() {\n    keep()\n    fresh()\n}\n")
        val map = map(lib, app)
        assertEquals(AtomStatus.ADDED, map.graph.atom("src/Lib.kt#fresh")!!.status)
        assertEquals(AtomRelation.NEW_SYMBOL_USE, map.edge("src/Lib.kt#fresh", "src/App.kt#main"))
        assertEquals(setOf("src/Lib.kt#fresh", "src/App.kt#main"), map.clusterOf("src/App.kt#main")!!.atoms.toSet())
    }

    fun testDroppedCallOfARemovedFunctionIsLinkedByName() {
        val lib = review("src/Lib.kt", "fun keep() = 1\n\nfun obsolete(): Int {\n    return 2\n}\n", "fun keep() = 1\n")
        val app = review("src/App.kt", "fun main() {\n    keep()\n    obsolete()\n}\n", "fun main() {\n    keep()\n}\n")
        val facts = facts(lib, app)
        assertTrue(AtomReference("src/App.kt#main", "src/Lib.kt#obsolete", ReferenceKind.REFERENCE, siteChanged = true, before = true) in facts.references)
        assertEquals(AtomRelation.REMOVED_SYMBOL_USE, map(lib, app).edge("src/Lib.kt#obsolete", "src/App.kt#main"))
    }

    fun testTestCallingTheChangedFunctionTestsIt() {
        val test = review("src/test/GreetTest.kt", "fun checkGreet() {\n    greet(\"a\")\n}\n", "fun checkGreet() {\n    greet(\"a\", false)\n}\n")
        val map = map(lib, test)
        assertEquals(AtomRelation.TEST_OF, map.edge("src/Lib.kt#greet", "src/test/GreetTest.kt#checkGreet"))
        assertEquals(AtomRole.TEST, map.clusterOf("src/Lib.kt#greet")!!.steps.single { it.atom == "src/test/GreetTest.kt#checkGreet" }.role)
    }

    fun testUnchangedCallSiteOfABodyChangeIsNotAChangedSite() {
        val lib = review("src/Lib.kt", "fun util(x: Int): Int {\n    return x + 1\n}\n", "fun util(x: Int): Int {\n    return x + 2\n}\n")
        val app = review("src/App.kt", "fun caller() {\n    util(1)\n    val y = 3\n}\n", "fun caller() {\n    util(1)\n    val y = 4\n}\n")
        assertEquals(listOf(AtomReference("src/App.kt#caller", "src/Lib.kt#util", ReferenceKind.CALL, siteChanged = false)), facts(lib, app).references)
        val map = map(lib, app)
        assertEquals(AtomRelation.CALL, map.edge("src/Lib.kt#util", "src/App.kt#caller"))
        assertTrue(map.clusterOf("src/App.kt#caller")!!.standalone)
    }

    fun testMembersOfAnAddedClassMapToTheClassAndTypesAreTypeReferences() {
        val model = review("src/Model.kt", "", "class Point(val x: Int) {\n    fun shifted(): Point = Point(x + 1)\n}\n", "added")
        val app = review("src/App.kt", "fun main() {\n}\n", "fun main() {\n    val p: Point = Point(1)\n    p.shifted()\n    p.x\n}\n")
        val references = facts(model, app).references.filter { it.from == "src/App.kt#main" }
        assertEquals(setOf("src/Model.kt#Point"), references.map { it.to }.toSet())
        assertEquals(setOf(ReferenceKind.TYPE, ReferenceKind.CALL, ReferenceKind.REFERENCE), references.map { it.kind }.toSet())
        assertTrue(references.all { it.siteChanged })
    }

    fun testOverrideOfAChangedSignature() {
        val shape = review("src/Shape.kt", "interface Shape {\n    fun area(): Int\n}\n", "interface Shape {\n    fun area(scale: Int): Int\n}\n")
        val square = review("src/Square.kt", "class Square : Shape {\n    override fun area(): Int = 4\n}\n",
            "class Square : Shape {\n    override fun area(scale: Int): Int = 4 * scale\n}\n")
        val facts = facts(shape, square)
        assertTrue(facts.references.toString(), AtomReference("src/Square.kt#Square.area", "src/Shape.kt#Shape.area", ReferenceKind.OVERRIDE, siteChanged = true) in facts.references)
        assertEquals(AtomRelation.OVERRIDE, map(shape, square).edge("src/Shape.kt#Shape.area", "src/Square.kt#Square.area"))
    }

    fun testJavaCallerOfAChangedMethod() {
        val lib = review("src/Lib.java", "class Lib {\n    static int twice(int x) {\n        return x * 2;\n    }\n}\n",
            "class Lib {\n    static int twice(int x, int y) {\n        return x * y;\n    }\n}\n")
        val use = review("src/Use.java", "class Use {\n    int run() {\n        return Lib.twice(1);\n    }\n}\n",
            "class Use {\n    int run() {\n        return Lib.twice(1, 2);\n    }\n}\n")
        val facts = facts(lib, use)
        assertTrue(facts.references.toString(), AtomReference("src/Use.java#Use.run", "src/Lib.java#Lib.twice", ReferenceKind.CALL, siteChanged = true) in facts.references)
        assertEquals(AtomRelation.SIGNATURE_USE, map(lib, use).edge("src/Lib.java#Lib.twice", "src/Use.java#Use.run"))
    }

    fun testFilesWithoutCodeAnalysisAreFileAtoms() {
        val readme = review("README.md", "# Title\n", "# Title\n\nMore.\n")
        val missing = atomTestFile("src/Gone.kt", "fun a() = 1\n", "fun a() = 2\n").copy(before = null)
        val atoms = facts(readme, missing).atoms
        assertEquals(listOf("README.md" to AtomKind.FILE, "src/Gone.kt" to AtomKind.FILE), atoms.map { it.id to it.kind })
    }

    fun testAfterSideParsedFromACopyStillSlicesAndSkipsStaleTargets() {
        val live = myFixture.addFileToProject("src/Lib.kt", greetAfter)
        val lib = atomTestFile("src/Lib.kt", greetBefore, greetAfter + "\nfun extra() = 1\n").copy(absolutePath = live.virtualFile.path)
        val app = review("src/App.kt", "fun main() {\n    greet(\"x\")\n}\n", "fun main() {\n    greet(\"x\", true)\n}\n")
        val facts = facts(lib, app)
        assertEquals(listOf("src/Lib.kt#greet", "src/Lib.kt#extra", "src/App.kt#main"), facts.atoms.map { it.id })
        // The project's Lib.kt is not the reviewed text, so lines of the copy say nothing about where `greet` resolved.
        assertTrue(facts.references.none { it.to == "src/Lib.kt#greet" && !it.before })
    }
}
