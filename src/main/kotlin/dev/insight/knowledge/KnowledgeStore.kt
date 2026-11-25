package dev.insight.knowledge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Directory of a project's knowledge, relative to its root. */
const val KNOWLEDGE_DIR = ".insight/knowledge"

/** The knowledge directory of the project at [projectRoot]. */
fun defaultKnowledgeDir(projectRoot: Path): Path = projectRoot.resolve(KNOWLEDGE_DIR)

/** Append-only persistence of ledger entries. */
interface KnowledgeStore {
    /** Every stored entry in order. */
    fun load(): List<LedgerEntry>
    fun append(entry: LedgerEntry)
}

/** A store in memory, for tests and transient knowledge bases. */
class InMemoryKnowledgeStore(initial: List<LedgerEntry> = emptyList()) : KnowledgeStore {
    private val entries = initial.toMutableList()
    @Synchronized override fun load(): List<LedgerEntry> = entries.toList()
    @Synchronized override fun append(entry: LedgerEntry) { entries += entry }
}

/** A stored line cannot be read and is not the last one, so the ledger is damaged. */
class KnowledgeStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Stores entries in `<dir>/ledger.jsonl`, one JSON object per line (see [KnowledgeJson]). A trailing line that cannot be
 * read, such as one cut short by a crash, is dropped and truncated away on [load]; an unreadable earlier line throws
 * [KnowledgeStoreException] rather than silently losing knowledge.
 */
class FileKnowledgeStore(dir: Path) : KnowledgeStore {
    val file: Path = dir.resolve(LEDGER_FILE)

    @Synchronized override fun load(): List<LedgerEntry> {
        if (!Files.exists(file)) return emptyList()
        val bytes = Files.readAllBytes(file)
        val entries = mutableListOf<LedgerEntry>()
        var start = 0
        var line = 0
        while (start < bytes.size) {
            val newline = bytes.indexOf('\n'.code.toByte(), start)
            val end = if (newline < 0) bytes.size else newline
            line++
            val text = String(bytes, start, end - start, Charsets.UTF_8).trim()
            if (text.isNotEmpty()) {
                try {
                    entries += KnowledgeJson.decodeEntry(text)
                } catch (e: Exception) {
                    if (bytes.indexOfNonBlank(end) >= 0) throw KnowledgeStoreException("$file line $line is unreadable: ${e.message}", e)
                    truncate(start.toLong())
                    break
                }
            }
            start = end + 1
        }
        return entries
    }

    @Synchronized override fun append(entry: LedgerEntry) {
        Files.createDirectories(file.parent)
        val prefix = if (Files.exists(file) && Files.size(file) > 0 && !endsWithNewline()) "\n" else ""
        Files.writeString(file, prefix + KnowledgeJson.encodeEntry(entry) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
    }

    private fun endsWithNewline(): Boolean = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val buffer = java.nio.ByteBuffer.allocate(1)
        channel.read(buffer, channel.size() - 1)
        buffer.get(0) == '\n'.code.toByte()
    }

    private fun truncate(size: Long) = FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(size) }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == byte } ?: -1
    private fun ByteArray.indexOfNonBlank(from: Int): Int =
        (from until size).firstOrNull { !Char(this[it].toInt() and 0xff).isWhitespace() } ?: -1

    companion object {
        const val LEDGER_FILE = "ledger.jsonl"
    }
}

/**
 * The JSON form of ledger entries. Polymorphic values carry a `type`: ops `add`, `rewrite`, `merge`, `split`, `retire`,
 * `mark_suspect`, `confirm`, `reanchor`, `contradict`, `restore`; statuses `live`, `suspect`, `retired`, `superseded`;
 * evidence `anchors_gone`, `contradicted`, `by_user`; verdicts `accepted`, `rejected`. Null optional fields are omitted.
 */
object KnowledgeJson {
    fun encodeEntry(entry: LedgerEntry): String = JsonObject().apply {
        addProperty("seq", entry.seq)
        addProperty("at", entry.at)
        addProperty("actor", entry.actor)
        add("op", encodeOp(entry.op))
        add("verdict", when (val v = entry.verdict) {
            is Verdict.Accepted -> typed("accepted").apply { add("notes", strings(v.notes)) }
            is Verdict.Rejected -> typed("rejected").apply { addProperty("reason", v.reason) }
        })
    }.toString()

    fun decodeEntry(line: String): LedgerEntry {
        val json = JsonParser.parseString(line).asJsonObject
        val verdict = json.obj("verdict")
        return LedgerEntry(json.long("seq"), decodeOp(json.obj("op")), json.str("actor"), json.long("at"),
            when (val type = verdict.str("type")) {
                "accepted" -> Verdict.Accepted(verdict.strings("notes"))
                "rejected" -> Verdict.Rejected(verdict.str("reason"))
                else -> unknown("verdict", type)
            })
    }

    fun encodeOp(op: KnowledgeOp): JsonObject = when (op) {
        is KnowledgeOp.Add -> typed("add").apply { add("claim", encodeClaim(op.claim)) }
        is KnowledgeOp.Rewrite -> typed("rewrite").apply { addProperty("fromId", op.fromId); add("to", encodeClaim(op.to)) }
        is KnowledgeOp.Merge -> typed("merge").apply { add("fromIds", strings(op.fromIds)); add("to", encodeClaim(op.to)) }
        is KnowledgeOp.Split -> typed("split").apply {
            addProperty("fromId", op.fromId)
            add("to", JsonArray().apply { op.to.forEach { add(encodeClaim(it)) } })
        }
        is KnowledgeOp.Retire -> typed("retire").apply {
            addProperty("id", op.id); addProperty("reason", op.reason); add("evidence", encodeEvidence(op.evidence))
        }
        is KnowledgeOp.MarkSuspect -> typed("mark_suspect").apply { addProperty("id", op.id); addProperty("reason", op.reason) }
        is KnowledgeOp.Confirm -> typed("confirm").apply { addProperty("id", op.id); add("anchors", anchors(op.anchors)) }
        is KnowledgeOp.Reanchor -> typed("reanchor").apply { addProperty("id", op.id); add("anchors", anchors(op.anchors)) }
        is KnowledgeOp.Contradict -> typed("contradict").apply {
            addProperty("aId", op.aId); addProperty("bId", op.bId); addProperty("reason", op.reason)
        }
        is KnowledgeOp.Restore -> typed("restore").apply { addProperty("id", op.id) }
    }

    fun decodeOp(json: JsonObject): KnowledgeOp = when (val type = json.str("type")) {
        "add" -> KnowledgeOp.Add(decodeClaim(json.obj("claim")))
        "rewrite" -> KnowledgeOp.Rewrite(json.str("fromId"), decodeClaim(json.obj("to")))
        "merge" -> KnowledgeOp.Merge(json.strings("fromIds"), decodeClaim(json.obj("to")))
        "split" -> KnowledgeOp.Split(json.str("fromId"), json.array("to").map { decodeClaim(it.asJsonObject) })
        "retire" -> KnowledgeOp.Retire(json.str("id"), json.str("reason"), decodeEvidence(json.obj("evidence")))
        "mark_suspect" -> KnowledgeOp.MarkSuspect(json.str("id"), json.str("reason"))
        "confirm" -> KnowledgeOp.Confirm(json.str("id"), decodeAnchors(json.array("anchors")))
        "reanchor" -> KnowledgeOp.Reanchor(json.str("id"), decodeAnchors(json.array("anchors")))
        "contradict" -> KnowledgeOp.Contradict(json.str("aId"), json.str("bId"), json.str("reason"))
        "restore" -> KnowledgeOp.Restore(json.str("id"))
        else -> unknown("op", type)
    }

    fun encodeClaim(claim: Claim): JsonObject = JsonObject().apply {
        addProperty("id", claim.id)
        addProperty("text", claim.text)
        addProperty("level", claim.level.name)
        addProperty("origin", claim.origin.name)
        addProperty("area", claim.area)
        add("anchors", anchors(claim.anchors))
        add("citations", JsonArray().apply {
            claim.citations.forEach { c ->
                add(JsonObject().apply {
                    addProperty("path", c.path)
                    c.startLine?.let { addProperty("startLine", it) }
                    c.endLine?.let { addProperty("endLine", it) }
                    c.note?.let { addProperty("note", it) }
                })
            }
        })
        add("tags", strings(claim.tags))
        claim.stability?.let { addProperty("stability", it) }
        add("status", encodeStatus(claim.status))
        claim.source?.let { addProperty("source", it) }
    }

    fun decodeClaim(json: JsonObject): Claim = Claim(
        id = json.str("id"),
        text = json.str("text"),
        level = ClaimLevel.valueOf(json.str("level")),
        origin = ClaimOrigin.valueOf(json.str("origin")),
        area = json.str("area"),
        anchors = decodeAnchors(json.array("anchors")),
        citations = json.optArray("citations").map {
            val c = it.asJsonObject
            Citation(c.str("path"), c.optInt("startLine"), c.optInt("endLine"), c.optStr("note"))
        },
        tags = json.optArray("tags").mapTo(LinkedHashSet()) { it.asString },
        stability = json.opt("stability")?.asDouble,
        status = json.opt("status")?.let { decodeStatus(it.asJsonObject) } ?: ClaimStatus.Live,
        source = json.optStr("source"),
    )

    private fun encodeStatus(status: ClaimStatus): JsonObject = when (status) {
        ClaimStatus.Live -> typed("live")
        is ClaimStatus.Suspect -> typed("suspect").apply { addProperty("reason", status.reason) }
        is ClaimStatus.Retired -> typed("retired").apply {
            addProperty("reason", status.reason); add("evidence", encodeEvidence(status.evidence))
        }
        is ClaimStatus.Superseded -> typed("superseded").apply { add("by", strings(status.by)) }
    }

    private fun decodeStatus(json: JsonObject): ClaimStatus = when (val type = json.str("type")) {
        "live" -> ClaimStatus.Live
        "suspect" -> ClaimStatus.Suspect(json.str("reason"))
        "retired" -> ClaimStatus.Retired(json.str("reason"), decodeEvidence(json.obj("evidence")))
        "superseded" -> ClaimStatus.Superseded(json.strings("by"))
        else -> unknown("status", type)
    }

    private fun encodeEvidence(evidence: RetireEvidence): JsonObject = when (evidence) {
        is RetireEvidence.AnchorsGone -> typed("anchors_gone").apply { add("refs", strings(evidence.refs)) }
        is RetireEvidence.Contradicted -> typed("contradicted").apply {
            addProperty("confidence", evidence.confidence); addProperty("explanation", evidence.explanation)
        }
        is RetireEvidence.ByUser -> typed("by_user").apply { addProperty("note", evidence.note) }
    }

    private fun decodeEvidence(json: JsonObject): RetireEvidence = when (val type = json.str("type")) {
        "anchors_gone" -> RetireEvidence.AnchorsGone(json.strings("refs"))
        "contradicted" -> RetireEvidence.Contradicted(json.get("confidence").asDouble, json.str("explanation"))
        "by_user" -> RetireEvidence.ByUser(json.str("note"))
        else -> unknown("evidence", type)
    }

    private fun anchors(anchors: List<Anchor>) = JsonArray().apply {
        anchors.forEach { a ->
            add(JsonObject().apply {
                addProperty("kind", a.kind.name)
                addProperty("ref", a.ref)
                a.fingerprint?.let { addProperty("fingerprint", it) }
            })
        }
    }

    private fun decodeAnchors(array: JsonArray): List<Anchor> = array.map {
        val a = it.asJsonObject
        Anchor(AnchorKind.valueOf(a.str("kind")), a.str("ref"), a.optStr("fingerprint"))
    }

    private fun typed(type: String) = JsonObject().apply { addProperty("type", type) }
    private fun strings(values: Collection<String>) = JsonArray().apply { values.forEach { add(it) } }
    private fun unknown(what: String, type: String): Nothing = throw IllegalArgumentException("Unknown $what type '$type'")

    private fun JsonObject.opt(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }
    private fun JsonObject.str(name: String): String = opt(name)?.asString ?: throw IllegalArgumentException("Missing '$name'")
    private fun JsonObject.long(name: String): Long = opt(name)?.asLong ?: throw IllegalArgumentException("Missing '$name'")
    private fun JsonObject.obj(name: String): JsonObject = opt(name)?.asJsonObject ?: throw IllegalArgumentException("Missing '$name'")
    private fun JsonObject.array(name: String): JsonArray = opt(name)?.asJsonArray ?: throw IllegalArgumentException("Missing '$name'")
    private fun JsonObject.optArray(name: String): JsonArray = opt(name)?.asJsonArray ?: JsonArray()
    private fun JsonObject.optStr(name: String): String? = opt(name)?.asString
    private fun JsonObject.optInt(name: String): Int? = opt(name)?.asInt
    private fun JsonObject.strings(name: String): List<String> = optArray(name).map { it.asString }
}
