package dev.insight.board

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardJsonTest {
    private fun parse(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun roundTripOfEveryBlockKind() {
        val board = fullBoard()
        assertEquals(board, BoardJson.decode(BoardJson.encode(board)))
        assertEquals(board, BoardJson.fromString(BoardJson.toString(board)))
    }

    @Test
    fun roundTripOfMinimalBoard() {
        val board = Board.new("Empty", now = 5, id = "abc")
        assertEquals(board, BoardJson.fromString(BoardJson.toString(board)))
    }

    @Test
    fun encodesDiscriminatorsVersionAndLowercaseEnums() {
        val json = BoardJson.encode(fullBoard())
        assertEquals(BoardJson.VERSION, json.get("version").asInt)
        val blocks = json.getAsJsonArray("blocks").map { it.asJsonObject }
        assertEquals(listOf("heading", "text", "code", "symbol", "chain", "steps", "table", "text", "symbol", "group"),
            blocks.map { it.get("kind").asString })
        val code = blocks[2]
        assertEquals("full", code.get("width").asString)
        assertEquals("diff", code.get("codeKind").asString)
        assertEquals("warning", code.getAsJsonArray("annotations")[0].asJsonObject.get("severity").asString)
        val anchor = code.getAsJsonObject("anchor")
        assertEquals("src/A.kt", anchor.get("path").asString)
        assertEquals(5, anchor.get("first").asInt)
        assertEquals(9, anchor.get("last").asInt)
        assertEquals("after", anchor.get("side").asString)
        assertEquals("before", blocks[3].getAsJsonObject("anchor").get("side").asString)
        assertEquals("insight", blocks[1].get("tone").asString)
    }

    @Test
    fun missingIdOrTitleDecodesToNull() {
        assertNull(BoardJson.decode(parse("""{"title":"T"}""")))
        assertNull(BoardJson.decode(parse("""{"id":"x"}""")))
        assertNull(BoardJson.decode(parse("""{"id":"x","title":"  "}""")))
        assertNull(BoardJson.decode(parse("""{"id":5,"title":"T"}""")))
    }

    @Test
    fun garbageTextDecodesToNull() {
        assertNull(BoardJson.fromString("not json"))
        assertNull(BoardJson.fromString("[1,2]"))
        assertNull(BoardJson.fromString(""))
        assertNull(BoardJson.fromString("\"str\""))
    }

    @Test
    fun minimalBoardUsesDefaults() {
        val board = BoardJson.decode(parse("""{"id":"x","title":"T"}"""))!!
        assertEquals(Board("x", "T"), board)
    }

    @Test
    fun junkBlocksAreDropped() {
        val board = BoardJson.decode(parse("""
            {"id":"x","title":"T","blocks":[
              42, null, "str", [], {},
              {"kind":"hologram","id":"u1"},
              {"kind":"text"},
              {"kind":"text","id":"","text":"blank id"},
              {"kind":"text","id":"nt"},
              {"kind":"code","id":"c1","text":"x","anchor":{"path":"a","first":0,"last":3}},
              {"kind":"code","id":"c2","text":"x","anchor":{"path":"a","first":5,"last":3}},
              {"kind":"code","id":"c3","text":"x"},
              {"kind":"table","id":"tb","columns":["a","b"],"rows":[[{"text":"1"}]]},
              {"kind":"table","id":"tb2","columns":[],"rows":[]},
              {"kind":"symbol","id":"sy","node":"seven","title":"S"},
              {"kind":"heading","id":"ok","text":"Good","level":3,"width":"wide"},
              {"kind":"heading","id":"ok","text":"Duplicate id"},
              {"kind":"text","id":"t","text":"Fine","tone":"nonsense","width":"huge"}
            ]}
        """.trimIndent()))!!
        assertEquals(listOf("ok", "t"), board.blocks.map { it.id })
        assertEquals(HeadingBlock("ok", "Good", 3, BlockWidth.WIDE), board.block("ok"))
        // Unknown enum values fall back to defaults.
        assertEquals(TextBlock("t", "Fine"), board.block("t"))
    }

    @Test
    fun malformedPartsOfABlockAreDroppedWhenOptional() {
        val board = BoardJson.decode(parse("""
            {"id":"x","title":"T","blocks":[
              {"kind":"code","id":"c","text":"a\nb","anchor":{"path":"a.kt","first":1,"last":2},
               "annotations":[{"line":1,"text":"ok"},{"line":99,"text":"outside"},{"line":2},"junk",{"line":2,"text":"bad sev","severity":"loud"}]},
              {"kind":"symbol","id":"s","node":1,"title":"S","anchor":{"path":"","first":1,"last":1}},
              {"kind":"steps","id":"st","steps":[{"text":"one"},{"nope":1},{"text":"two","anchor":{"path":"a","first":3,"last":1}}]},
              {"kind":"chain","id":"ch","steps":[{"node":1,"title":"a"},{"title":"no node"},{"node":2,"title":"b"}]}
            ]}
        """.trimIndent()))!!
        val code = board.block("c") as CodeBlock
        assertEquals(listOf(Annotation(1, "ok"), Annotation(2, "bad sev")), code.annotations)
        assertNull((board.block("s") as SymbolBlock).anchor)
        assertEquals(listOf(Step("one"), Step("two")), (board.block("st") as StepsBlock).steps)
        assertEquals(listOf(1, 2), (board.block("ch") as ChainBlock).steps.map { it.node })
        assertTrue((board.block("ch") as ChainBlock).complete)
    }

    @Test
    fun edgesToMissingBlocksAreDropped() {
        val board = BoardJson.decode(parse("""
            {"id":"x","title":"T","blocks":[{"kind":"text","id":"a","text":"A"},{"kind":"text","id":"b","text":"B"},{"kind":"hologram","id":"h"}],
             "edges":[{"from":"a","to":"b","label":"L"},{"from":"a","to":"ghost"},{"from":"ghost","to":"b"},{"from":"a","to":"h"},
                      {"from":"a","to":"a"},{"from":"a","to":"b"},{"from":"a"},7,null]}
        """.trimIndent()))!!
        assertEquals(listOf(BoardEdge("a", "b", "L")), board.edges)
    }

    @Test
    fun groupChildrenThatDoNotExistAreDropped() {
        val board = BoardJson.decode(parse("""
            {"id":"x","title":"T","blocks":[
              {"kind":"text","id":"a","text":"A"},{"kind":"text","id":"b","text":"B"},{"kind":"text","id":"c","text":"C"},
              {"kind":"group","id":"g","title":"G","children":["a","ghost","b","a","g","g2",5,null]},
              {"kind":"group","id":"g2","children":["b","c"]}
            ]}
        """.trimIndent()))!!
        assertEquals(listOf("a", "b"), (board.block("g") as GroupBlock).children)
        // b already belongs to g, c stays with g2.
        assertEquals(listOf("c"), (board.block("g2") as GroupBlock).children)
        assertNotNull(board.parent("c"))
        assertEquals(listOf("g", "g2"), board.topLevel.map { it.id })
    }

    @Test
    fun updatedDefaultsToCreated() {
        val board = BoardJson.decode(parse("""{"id":"x","title":"T","created":77}"""))!!
        assertEquals(77L, board.created)
        assertEquals(77L, board.updated)
    }

    @Test
    fun originRoundTripsAndJunkOriginIsIgnored() {
        val withOrigin = BoardJson.decode(parse("""{"id":"x","title":"T","origin":{"kind":"scope","title":"S","paths":["a",1,"b"]}}"""))!!
        assertEquals(BoardOrigin("scope", "S", listOf("a", "b")), withOrigin.origin)
        assertNull(BoardJson.decode(parse("""{"id":"x","title":"T","origin":"junk"}"""))!!.origin)
    }

    @Test
    fun encodedBlocksAreJsonObjectsInAnArray() {
        val json = BoardJson.encode(fullBoard())
        assertTrue(json.get("blocks") is JsonArray)
        assertTrue(json.getAsJsonArray("blocks").all { it is JsonObject })
    }
}
