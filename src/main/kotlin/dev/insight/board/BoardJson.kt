package dev.insight.board

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The board's stored form: a tolerant JSON codec. Unknown block kinds and malformed blocks are dropped on decode so an
 * old or hand-edited file still opens; a board without an id or title decodes to null.
 */
object BoardJson {
    const val VERSION = 1

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(board: Board): JsonObject = JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("id", board.id)
        addProperty("title", board.title)
        addProperty("created", board.created)
        addProperty("updated", board.updated)
        board.origin?.let { origin ->
            add("origin", JsonObject().apply {
                addProperty("kind", origin.kind)
                addProperty("title", origin.title)
                add("paths", strings(origin.paths))
            })
        }
        add("blocks", JsonArray().also { array -> board.blocks.forEach { array.add(encodeBlock(it)) } })
        add("edges", JsonArray().also { array ->
            board.edges.forEach { edge ->
                array.add(JsonObject().apply {
                    addProperty("from", edge.from)
                    addProperty("to", edge.to)
                    edge.label?.let { addProperty("label", it) }
                })
            }
        })
    }

    fun decode(json: JsonObject): Board? = runCatching {
        val id = json.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = json.str("title")?.takeIf { it.isNotBlank() } ?: return null
        val blocks = LinkedHashMap<String, Block>()
        for (element in json.array("blocks")) {
            val block = runCatching { decodeBlock(element) }.getOrNull() ?: continue
            if (block.id !in blocks) blocks[block.id] = block
        }
        val fixed = repairGroups(blocks)
        val edges = ArrayList<BoardEdge>()
        for (element in json.array("edges")) {
            val obj = element.asObjectOrNull() ?: continue
            val from = obj.str("from") ?: continue
            val to = obj.str("to") ?: continue
            if (from == to || from !in fixed || to !in fixed) continue
            if (edges.any { it.from == from && it.to == to }) continue
            edges += BoardEdge(from, to, obj.str("label"))
        }
        val created = json.long("created") ?: 0L
        Board(id, title, fixed.values.toList(), edges, decodeOrigin(json.get("origin")), created, json.long("updated") ?: created)
    }.getOrNull()

    fun toString(board: Board): String = gson.toJson(encode(board))

    fun fromString(text: String): Board? = runCatching {
        JsonParser.parseString(text).asObjectOrNull()?.let(::decode)
    }.getOrNull()

    // --- encode ---

    private fun encodeBlock(block: Block): JsonObject = JsonObject().apply {
        addProperty("kind", kindOf(block))
        addProperty("id", block.id)
        addProperty("width", block.width.name.lowercase())
        when (block) {
            is HeadingBlock -> {
                addProperty("text", block.text)
                addProperty("level", block.level)
            }
            is TextBlock -> {
                addProperty("text", block.text)
                put("title", block.title)
                put("tone", block.tone?.name?.lowercase())
            }
            is CodeBlock -> {
                add("anchor", encodeAnchor(block.anchor))
                addProperty("text", block.text)
                addProperty("codeKind", block.kind.name.lowercase())
                put("title", block.title)
                add("annotations", JsonArray().also { array ->
                    block.annotations.forEach { a ->
                        array.add(JsonObject().apply {
                            addProperty("line", a.line)
                            addProperty("text", a.text)
                            addProperty("severity", a.severity.name.lowercase())
                        })
                    }
                })
            }
            is SymbolBlock -> {
                addProperty("node", block.node)
                addProperty("title", block.title)
                put("meta", block.meta)
                put("summary", block.summary)
                block.anchor?.let { add("anchor", encodeAnchor(it)) }
            }
            is ChainBlock -> {
                put("title", block.title)
                addProperty("complete", block.complete)
                add("steps", JsonArray().also { array ->
                    block.steps.forEach { s ->
                        array.add(JsonObject().apply {
                            addProperty("node", s.node)
                            addProperty("title", s.title)
                            s.anchor?.let { add("anchor", encodeAnchor(it)) }
                            put("summary", s.summary)
                        })
                    }
                })
            }
            is StepsBlock -> {
                put("title", block.title)
                add("steps", JsonArray().also { array ->
                    block.steps.forEach { s ->
                        array.add(JsonObject().apply {
                            addProperty("text", s.text)
                            s.anchor?.let { add("anchor", encodeAnchor(it)) }
                        })
                    }
                })
            }
            is TableBlock -> {
                put("title", block.title)
                add("columns", strings(block.columns))
                add("rows", JsonArray().also { rows ->
                    block.rows.forEach { row ->
                        rows.add(JsonArray().also { cells ->
                            row.forEach { c ->
                                cells.add(JsonObject().apply {
                                    addProperty("text", c.text)
                                    c.anchor?.let { add("anchor", encodeAnchor(it)) }
                                })
                            }
                        })
                    }
                })
            }
            is GroupBlock -> {
                put("title", block.title)
                put("tone", block.tone?.name?.lowercase())
                add("children", strings(block.children))
            }
        }
    }

    private fun kindOf(block: Block) = when (block) {
        is HeadingBlock -> "heading"
        is TextBlock -> "text"
        is CodeBlock -> "code"
        is SymbolBlock -> "symbol"
        is ChainBlock -> "chain"
        is StepsBlock -> "steps"
        is TableBlock -> "table"
        is GroupBlock -> "group"
    }

    private fun encodeAnchor(a: BoardAnchor) = JsonObject().apply {
        addProperty("path", a.path)
        addProperty("first", a.first)
        addProperty("last", a.last)
        addProperty("side", a.side.name.lowercase())
    }

    private fun JsonObject.put(name: String, value: String?) {
        if (value != null) addProperty(name, value)
    }

    private fun strings(values: List<String>) = JsonArray().also { array -> values.forEach { array.add(it) } }

    // --- decode ---

    private fun decodeBlock(element: JsonElement): Block? {
        val obj = element.asObjectOrNull() ?: return null
        val kind = obj.str("kind") ?: return null
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        fun width(default: BlockWidth) = obj.str("width")?.let { w -> BlockWidth.entries.firstOrNull { it.name.equals(w, true) } } ?: default
        val title = obj.str("title")
        return when (kind) {
            "heading" -> HeadingBlock(id, obj.str("text") ?: return null, (obj.int("level") ?: 1).coerceAtLeast(1), width(BlockWidth.FULL))
            "text" -> TextBlock(id, obj.str("text") ?: return null, title, tone(obj), width(BlockWidth.NARROW))
            "code" -> {
                val anchor = decodeAnchor(obj.get("anchor")) ?: return null
                val annotations = obj.array("annotations").mapNotNull { e ->
                    val a = e.asObjectOrNull() ?: return@mapNotNull null
                    val line = a.int("line") ?: return@mapNotNull null
                    val text = a.str("text") ?: return@mapNotNull null
                    if (line !in anchor.first..anchor.last) return@mapNotNull null
                    val severity = a.str("severity")?.let { s -> Severity.entries.firstOrNull { it.name.equals(s, true) } } ?: Severity.INFO
                    Annotation(line, text, severity)
                }
                val codeKind = obj.str("codeKind")?.let { k -> CodeKind.entries.firstOrNull { it.name.equals(k, true) } } ?: CodeKind.SOURCE
                CodeBlock(id, anchor, obj.str("text") ?: return null, codeKind, title, annotations, width(BlockWidth.WIDE))
            }
            "symbol" -> SymbolBlock(id, obj.int("node") ?: return null, title ?: return null, obj.str("meta"), obj.str("summary"),
                decodeAnchor(obj.get("anchor")), width(BlockWidth.NARROW))
            "chain" -> {
                val steps = obj.array("steps").mapNotNull { e ->
                    val s = e.asObjectOrNull() ?: return@mapNotNull null
                    ChainStep(s.int("node") ?: return@mapNotNull null, s.str("title") ?: return@mapNotNull null,
                        decodeAnchor(s.get("anchor")), s.str("summary"))
                }
                ChainBlock(id, steps, title, obj.bool("complete") ?: true, width(BlockWidth.WIDE))
            }
            "steps" -> {
                val steps = obj.array("steps").mapNotNull { e ->
                    val s = e.asObjectOrNull() ?: return@mapNotNull null
                    Step(s.str("text") ?: return@mapNotNull null, decodeAnchor(s.get("anchor")))
                }
                StepsBlock(id, steps, title, width(BlockWidth.NARROW))
            }
            "table" -> {
                val columns = obj.array("columns").map { it.asStringOrNull() ?: return null }
                if (columns.isEmpty()) return null
                val rows = obj.array("rows").map { row ->
                    val cells = (row as? JsonArray ?: return null).map { c ->
                        val cell = c.asObjectOrNull() ?: return null
                        Cell(cell.str("text") ?: return null, decodeAnchor(cell.get("anchor")))
                    }
                    if (cells.size != columns.size) return null
                    cells
                }
                TableBlock(id, columns, rows, title, width(BlockWidth.WIDE))
            }
            "group" -> GroupBlock(id, obj.array("children").mapNotNull { it.asStringOrNull() }, title, tone(obj), width(BlockWidth.FULL))
            else -> null
        }
    }

    private fun tone(obj: JsonObject): Tone? = obj.str("tone")?.let { t -> Tone.entries.firstOrNull { it.name.equals(t, true) } }

    private fun decodeAnchor(element: JsonElement?): BoardAnchor? {
        val obj = element.asObjectOrNull() ?: return null
        return runCatching {
            val side = obj.str("side")?.let { s -> BoardSide.entries.firstOrNull { it.name.equals(s, true) } } ?: BoardSide.AFTER
            BoardAnchor(obj.str("path") ?: return null, obj.int("first") ?: return null, obj.int("last") ?: return null, side)
        }.getOrNull()
    }

    private fun decodeOrigin(element: JsonElement?): BoardOrigin? {
        val obj = element.asObjectOrNull() ?: return null
        val kind = obj.str("kind") ?: return null
        return BoardOrigin(kind, obj.str("title") ?: "", obj.array("paths").mapNotNull { it.asStringOrNull() })
    }

    /** Keeps only group children that exist, are not groups or the group itself, and belong to no earlier group. */
    private fun repairGroups(blocks: Map<String, Block>): Map<String, Block> {
        val taken = HashSet<String>()
        val result = LinkedHashMap<String, Block>()
        for ((id, block) in blocks) {
            if (block !is GroupBlock) {
                result[id] = block
                continue
            }
            val children = block.children.distinct().filter { child ->
                child != id && blocks[child].let { it != null && it !is GroupBlock } && taken.add(child)
            }
            result[id] = block.copy(children = children)
        }
        return result
    }

    // --- tolerant readers ---

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement?.asStringOrNull(): String? =
        this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.str(name: String): String? = get(name).asStringOrNull()

    private fun JsonObject.number(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asJsonPrimitive

    private fun JsonObject.int(name: String): Int? = number(name)?.let { n -> n.asLong.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() }

    private fun JsonObject.long(name: String): Long? = number(name)?.asLong

    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
}
