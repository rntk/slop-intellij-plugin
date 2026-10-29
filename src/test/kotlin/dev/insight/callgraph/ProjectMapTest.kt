package dev.insight.callgraph

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind
import org.junit.Assert.*
import org.junit.Test

class ProjectMapTest {
    private val root = "/p/src/main/kotlin/com/acme/app"
    private val graph = CallGraph(listOf(
        CallGraphNode(0, "handle", "com.acme.app.orders.OrderService", "OrderService.kt:3", 0, true, filePath = "$root/orders/OrderService.kt"),
        CallGraphNode(1, "save", "com.acme.app.orders.OrderService", "OrderService.kt:9", 0, true, filePath = "$root/orders/OrderService.kt"),
        CallGraphNode(2, "validate", "com.acme.app.orders.OrderService", "OrderService.kt:15", 0, true, filePath = "$root/orders/OrderService.kt"),
        CallGraphNode(3, "charge", "com.acme.app.billing.Billing", "Billing.kt:2", 0, true, filePath = "$root/billing/Billing.kt"),
        CallGraphNode(4, "refund", "com.acme.app.billing.Billing", "Billing.kt:8", 0, true, filePath = "$root/billing/Billing.kt"),
        CallGraphNode(5, "post", "com.acme.app.billing.gateway.Gateway", "Gateway.kt:4", 0, true, filePath = "$root/billing/gateway/Gateway.kt"),
        CallGraphNode(6, "main", "com.acme.app.MainKt", "Main.kt:1", 0, true, filePath = "$root/Main.kt"),
        CallGraphNode(7, "testHandle", "com.acme.app.orders.OrderServiceTest", "OrderServiceTest.kt:5", 0, true,
            filePath = "/p/src/test/kotlin/com/acme/app/orders/OrderServiceTest.kt"),
        CallGraphNode(8, "println", "kotlin.io", "", 0, false),
        CallGraphNode(9, "OrderService", "com.acme.app.orders", "OrderService.kt:1", 0, true, SymbolKind.CLASS, "$root/orders/OrderService.kt"),
    ), setOf(CallGraphEdge(6, 0), CallGraphEdge(0, 1), CallGraphEdge(0, 2), CallGraphEdge(2, 1), CallGraphEdge(0, 3), CallGraphEdge(3, 5),
        CallGraphEdge(4, 5), CallGraphEdge(7, 0), CallGraphEdge(0, 8)), false)

    private fun map(data: ProjectMapData, offset: Int = 0, graph: CallGraph = this.graph) =
        JsonParser.parseString(chartProjectMap(graph, 4, offset, data)).asJsonObject

    private fun JsonObject.areas() = getAsJsonArray("areas").map { it.asJsonObject }

    @Test fun `areas drop source roots and group one level below the shared prefix with tests apart`() {
        val json = map(ProjectMapData(
            entryPoints = listOf(MapEntryPoint(6, "CLI", "Command line", "discovery"), MapEntryPoint(8, "library", "Other", "discovery")),
            flows = listOf(MapFlow("Place order", "Checkout to payment", 0, 4)),
            effects = { if (it == 1) setOf("storage") else if (it == 5) setOf("network") else emptySet() },
            summary = { if (it == 1) "Persists the order" else null }, basePath = "/p"))
        assertEquals(4, json["revision"].asInt)
        assertEquals(8, json["functions_total"].asInt)
        assertEquals("com/acme/app", json["common_prefix"].asString)
        val areas = json.areas().associateBy { it["area"].asString }
        assertEquals(listOf("com/acme/app/billing", "com/acme/app/orders", "com/acme/app", "com/acme/app/orders (tests)"),
            json.areas().map { it["area"].asString })
        assertEquals(3, areas.getValue("com/acme/app/orders")["functions"].asInt)
        assertEquals(listOf("src/main/kotlin/com/acme/app/billing", "src/main/kotlin/com/acme/app/billing/gateway"),
            areas.getValue("com/acme/app/billing")["directories"].asJsonArray.map { it.asString })
        assertEquals(1, areas.getValue("com/acme/app")["entry_points"].asInt)
        assertEquals(0, areas.getValue("com/acme/app/orders")["entry_points"].asInt)
        assertEquals(1, areas.getValue("com/acme/app/orders")["effects"].asJsonObject["storage"].asInt)
        assertEquals(1, areas.getValue("com/acme/app/billing")["effects"].asJsonObject["network"].asInt)
        val hubs = areas.getValue("com/acme/app/orders")["hubs"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(1, 2), hubs.map { it["node_id"].asInt })
        assertEquals(2, hubs[0]["in_area_callers"].asInt)
        assertEquals("Persists the order", hubs[0]["summary"].asString)
        assertEquals("OrderService.save", hubs[0]["name"].asString)
        assertEquals(listOf(5), areas.getValue("com/acme/app/billing")["hubs"].asJsonArray.map { it.asJsonObject["node_id"].asInt })
        val calls = json["area_calls"].asJsonArray.map { it.asJsonObject.let { c -> Triple(c["from"].asString, c["to"].asString, c["calls"].asInt) } }
        assertEquals(setOf(Triple("com/acme/app", "com/acme/app/orders", 1), Triple("com/acme/app/orders", "com/acme/app/billing", 1),
            Triple("com/acme/app/orders (tests)", "com/acme/app/orders", 1)), calls.toSet())
        val entries = json["entry_points"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(6), entries.map { it["node_id"].asInt })
        assertEquals("com/acme/app", entries.single()["area"].asString)
        assertEquals("Command line", entries.single()["kind"].asString)
        assertEquals("Place order", json["flows"].asJsonArray.single().asJsonObject["title"].asString)
        assertEquals(0, json["unavailable"].asJsonArray.size())
    }

    @Test fun `missing discovery and enrichment are marked unavailable`() {
        val json = map(ProjectMapData())
        assertTrue(json["entry_points"].asString.startsWith("unavailable: run the Entry Points search"))
        assertTrue(json["flows"].asString.startsWith("unavailable: run Enrich"))
        assertEquals(4, json["unavailable"].asJsonArray.size())
        val area = json.areas().first()
        assertFalse(area.has("effects"))
        assertFalse(area.has("entry_points"))
        assertFalse(area["hubs"].asJsonArray.single().asJsonObject.has("summary"))
        assertTrue(json["evidence"].asString.contains("not by runtime behaviour"))
    }

    @Test fun `areas are paged and global lists are capped`() {
        val nodes = (0 until 250).map { CallGraphNode(it, "f$it", "m${it % 25}", "F.kt:$it", 0, true, filePath = "/p/src/m${it % 25}/F$it.kt") }
        val edges = (0 until 250).flatMap { i -> listOf(CallGraphEdge(i, (i + 1) % 250), CallGraphEdge(i, (i + 7) % 250)) }.toSet()
        val big = CallGraph(nodes, edges, false)
        val data = ProjectMapData(entryPoints = nodes.map { MapEntryPoint(it.id, "e${it.id}", "Handler", "discovery") }, basePath = "/p")
        val first = map(data, 0, big)
        assertEquals(25, first["areas_total"].asInt)
        assertNull(first["common_prefix"])
        assertEquals(10, first.areas().size)
        assertEquals(10, first["next_offset"].asInt)
        assertEquals(15, first["area_calls"].asJsonArray.size())
        assertTrue(first["area_calls_total"].asInt > 15)
        assertEquals(250, first["entry_points_total"].asInt)
        assertEquals(25, first["entry_points"].asJsonArray.size())
        val last = map(data, 20, big)
        assertEquals(5, last.areas().size)
        assertNull(last["next_offset"])
        assertFalse(last.has("area_calls"))
        assertFalse(last.has("entry_points"))
        val all = (0..2).flatMap { page -> map(data, page * 10, big).areas().map { it["area"].asString } }
        assertEquals((0 until 25).map { "m$it" }.sorted(), all.sorted())
        assertEquals(chartProjectMap(big, 1, 0, data), chartProjectMap(big, 1, 0, data))
        assertTrue(chartProjectMap(big, 1, 0, data).length < 12_000)
    }
}
