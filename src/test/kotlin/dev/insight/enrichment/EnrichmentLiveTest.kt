package dev.insight.enrichment

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.graph.RelationKind
import dev.insight.llm.LLAMA_CPP_DEFAULT_BASE_URL
import dev.insight.llm.LlamaCppClient
import dev.insight.settings.ChatSettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Enriches this plugin's own sources against the live servers and writes `build/enrichment-report.json` for review.
 * Does nothing unless `INSIGHT_LIVE=1`; `INSIGHT_LLM_URL`, `INSIGHT_ENDPOINT`, and `INSIGHT_DECISIONS_KEY` override the local defaults.
 * Judgments are cached in `build/enrichment-cache.json`, so reruns are fast.
 */
class EnrichmentLiveTest : LightJavaCodeInsightFixtureTestCase() {
    private val root = System.getProperty("user.dir")
    override fun getTestDataPath() = "$root/src/main/kotlin"

    /** The plugin's real dependencies, so calls into and through them resolve as in the IDE and the graph is complete. */
    override fun getProjectDescriptor() = object : DefaultLightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            LIBRARY_CLASSES.mapNotNull { name ->
                com.intellij.openapi.application.PathManager.getJarPathForClass(Class.forName(name))?.let(Path::of)
            }.filter { it.toString().endsWith(".jar") }.distinct().forEach { jar ->
                PsiTestUtil.addLibrary(model, jar.fileName.toString(), jar.parent.toString(), jar.fileName.toString())
            }
        }
    }

    fun `test enriches the plugin's own sources`() {
        if (System.getenv("INSIGHT_LIVE") != "1") return // Platform tests are JUnit 3 style, where assumptions fail instead of skipping.
        myFixture.copyDirectoryToProject("dev/insight", "dev/insight")
        val result = ProjectGraphAnalyzer.build(project)
        val facts = SymbolFactsExtractor.extract(project, result)
        val calls = { graph: dev.insight.graph.CallGraph -> graph.edges.count { it.kind == RelationKind.CALL } }
        println("LIVE graph: ${result.graph.nodes.size} nodes, ${calls(result.graph)} calls (${calls(liftCalls(result.graph))} lifted), ${facts.size} facts")
        val input = EnrichmentInput("Insight Workbench (IntelliJ plugin: application graph, LLM chat, dashboard)", result.graph, facts)
        val cache = FileEnrichmentCache(Path.of(root, "build", "enrichment-cache.json"))
        val llm = LlamaCppClient(System.getenv("INSIGHT_LLM_URL") ?: LLAMA_CPP_DEFAULT_BASE_URL, requestTimeout = Duration.ofSeconds(300))
        val decisions = ChatSettings.decisionsClient(System.getenv("INSIGHT_ENDPOINT").orEmpty(), System.getenv("INSIGHT_DECISIONS_KEY"))
        val pipeline = EnrichmentPipeline(decisions::evaluate, llm::complete, cache)
        var last = ""
        val enrichment = runBlocking(Dispatchers.IO) {
            pipeline.run(input) { phase, done, total ->
                if (phase != last || done == total || done % 50 == 0) println("LIVE $phase $done/$total")
                last = phase
            }
        }
        val report = Path.of(root, "build", "enrichment-report.json")
        Files.writeString(report, EnrichmentReport.toJson(input, enrichment))
        println("LIVE stats ${enrichment.stats}")
        println("LIVE ${facts.size} facts, ${enrichment.nodes.size} symbols, ${enrichment.edges.size} calls, " +
            "${enrichment.flows.size} flows, ${enrichment.domains.size} domains, ${enrichment.groupLabels.size} labels → $report")
        assertTrue(enrichment.nodes.isNotEmpty())
        assertTrue(enrichment.stats.failures <= enrichment.stats.nodeRequests / 20 + enrichment.stats.edgeRequests / 20 + 2)
    }

    private companion object {
        val LIBRARY_CLASSES = listOf(
            "kotlin.Unit", "kotlinx.coroutines.CoroutineScope", "com.google.gson.JsonObject",
            "com.intellij.openapi.project.Project", "com.intellij.psi.PsiElement", "com.intellij.openapi.wm.ToolWindowFactory",
            "com.intellij.util.ui.JBUI", "com.intellij.openapi.actionSystem.AnAction", "com.intellij.openapi.components.Service",
            "com.intellij.openapi.fileEditor.FileEditorManager", "com.intellij.ui.components.JBScrollPane",
            "com.intellij.openapi.application.CoroutinesKt", "com.intellij.platform.ide.progress.TasksKt",
            "com.intellij.platform.util.progress.RawProgressReporter", "com.intellij.ui.dsl.builder.BuilderKt",
            "com.intellij.openapi.diagnostic.Logger", "com.intellij.openapi.vfs.VirtualFile", "com.intellij.ide.passwordSafe.PasswordSafe",
        )
    }
}
