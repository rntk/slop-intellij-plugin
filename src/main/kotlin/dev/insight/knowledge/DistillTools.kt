package dev.insight.knowledge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.objectTool
import dev.insight.agent.objectsArg
import dev.insight.agent.optionalStringArg
import dev.insight.agent.stringArg
import dev.insight.llm.JsonSchemas

/** Names of the worker's recording tools. */
object DistillToolNames {
    const val PROPOSE_CLAIM = "propose_claim"
    const val CONFIRM_CLAIM = "confirm_claim"
    const val REVISE_CLAIM = "revise_claim"
    const val REFUTE_CLAIM = "refute_claim"

    val ALL = setOf(PROPOSE_CLAIM, CONFIRM_CLAIM, REVISE_CLAIM, REFUTE_CLAIM)
}

/**
 * One worker's recording tools and its proposal buffer. Calls only append to [proposals] (and notify [onProposal]);
 * nothing reaches the knowledge base until the organizer turns proposals into guarded ops. [known] are the area's
 * active claims the worker may confirm, revise or refute; [verify] the ids it is asked to re-verify. Calls run in
 * sequence within one worker turn.
 */
class DistillTools(
    val area: String,
    known: List<Claim>,
    verify: Collection<String> = emptyList(),
    private val maxProposals: Int = 24,
    private val maxClaimChars: Int = 400,
    private val onProposal: (DistillProposal) -> Unit = {},
) {
    val actor: String = "worker:$area"
    private val known = known.associateBy { it.id }
    private val knownTexts = known.associateBy { normalizeClaimText(it.text) }
    private val verify = verify.toSet()
    private val handled = HashSet<String>()
    private val buffer = mutableListOf<DistillProposal>()
    private var spent = 0

    val proposals: List<DistillProposal> get() = buffer.toList()

    /** Ids to re-verify that the worker has not confirmed, revised or refuted yet. */
    fun unhandled(): List<String> = verify.filter { it !in handled }.sorted()

    /** A follow-up note while claims to re-verify are unhandled, or null. */
    fun followUp(): String? = unhandled().takeIf { it.isNotEmpty() }?.let {
        "Still to re-verify: ${it.joinToString()}. Before answering, call confirm_claim, revise_claim or refute_claim for each."
    }

    fun recording(): List<ChatTool> = listOf(proposeClaim, confirmClaim, reviseClaim, refuteClaim)

    val proposeClaim: ChatTool = objectTool(DistillToolNames.PROPOSE_CLAIM,
        "Propose one new claim about this area: an atomic, abstract, stable statement, anchored to the code it is about and " +
            "citing what you read. Do not propose known claims again. Returns whether it was recorded.",
        JsonSchemas.obj(
            "text" to JsonSchemas.string(minLength = 1, maxLength = maxClaimChars, description = "One sentence; no frequently changing values."),
            "level" to JsonSchemas.enum(ClaimLevel.entries.map { it.name.lowercase() }, description = "symbol, file, module (directory) or flow."),
            "anchors" to JsonSchemas.array(ANCHOR_SCHEMA, minItems = 1, maxItems = MAX_ANCHORS, description = "Code the claim is about."),
            "citations" to JsonSchemas.array(CITATION_SCHEMA, maxItems = MAX_CITATIONS, description = "Where the claim was read."),
            required = listOf("text", "level", "anchors"), additionalProperties = false)) { args ->
        val text = claimText(args, "text")
        knownTexts[normalizeClaimText(text)]?.let { throw ToolInputException("Already known as ${it.id}; do not propose it again.") }
        if (buffer.any { it is DistillProposal.Propose && normalizeClaimText(it.text) == normalizeClaimText(text) })
            throw ToolInputException("You already proposed this claim.")
        val level = levelArg(args)
        val anchors = objectsArg(args["anchors"], "anchors", "anchor", MAX_ANCHORS, ::anchorArg)
        val citations = args["citations"]?.takeUnless { it.isJsonNull || it.isJsonArray && it.asJsonArray.isEmpty }
            ?.let { objectsArg(it, "citations", "citation", MAX_CITATIONS, ::citationArg) }.orEmpty()
        spend()
        record(DistillProposal.Propose(actor, text, level, anchors, citations))
        "Recorded proposal ${buffer.size}${remaining()}."
    }

    val confirmClaim: ChatTool = objectTool(DistillToolNames.CONFIRM_CLAIM,
        "Confirm that a known claim still holds for the current code, after reading its anchors.",
        JsonSchemas.obj("id" to ID_SCHEMA, required = listOf("id"), additionalProperties = false)) { args ->
        val claim = claimArg(args)
        record(DistillProposal.Confirm(actor, claim.id))
        "Recorded confirmation of ${claim.id}."
    }

    val reviseClaim: ChatTool = objectTool(DistillToolNames.REVISE_CLAIM,
        "Propose new wording for a known claim with the same meaning, such as after a rename. Use refute_claim instead when " +
            "the claim is no longer true.",
        JsonSchemas.obj(
            "id" to ID_SCHEMA,
            "new_text" to JsonSchemas.string(minLength = 1, maxLength = maxClaimChars, description = "The revised claim, one sentence."),
            "reason" to JsonSchemas.string(minLength = 1, description = "Why the wording must change."),
            required = listOf("id", "new_text", "reason"), additionalProperties = false)) { args ->
        val claim = claimArg(args)
        val text = claimText(args, "new_text")
        if (normalizeClaimText(text) == normalizeClaimText(claim.text)) throw ToolInputException("new_text is the claim's current text; use confirm_claim.")
        val reason = args.stringArg("reason")
        spend()
        record(DistillProposal.Revise(actor, claim.id, text, reason))
        "Recorded revision of ${claim.id}${remaining()}."
    }

    val refuteClaim: ChatTool = objectTool(DistillToolNames.REFUTE_CLAIM,
        "Report that the code contradicts a known claim. The claim is checked against the code before anything changes.",
        JsonSchemas.obj(
            "id" to ID_SCHEMA,
            "reason" to JsonSchemas.string(minLength = 1, description = "What is no longer true."),
            "evidence" to JsonSchemas.string(minLength = 1, description = "The code that contradicts it, with path:line."),
            required = listOf("id", "reason", "evidence"), additionalProperties = false)) { args ->
        val claim = claimArg(args)
        record(DistillProposal.Refute(actor, claim.id, args.stringArg("reason"), args.stringArg("evidence")))
        "Recorded refutation of ${claim.id}; it will be verified."
    }

    private fun record(proposal: DistillProposal) {
        buffer += proposal
        when (proposal) {
            is DistillProposal.Confirm -> handled += proposal.id
            is DistillProposal.Revise -> handled += proposal.id
            is DistillProposal.Refute -> handled += proposal.id
            is DistillProposal.Propose -> Unit
        }
        onProposal(proposal)
    }

    private fun spend() {
        if (spent >= maxProposals) throw ToolInputException("No proposals left for this area ($maxProposals); answer now.")
        spent++
    }

    private fun remaining() = (maxProposals - spent).let { if (it <= 3) "; $it proposal${if (it == 1) "" else "s"} left" else "" }

    private fun claimText(args: JsonObject, name: String): String {
        val text = args.stringArg(name).trim().replace(Regex("\\s+"), " ")
        if (text.length > maxClaimChars) throw ToolInputException("$name must be at most $maxClaimChars characters; keep claims atomic.")
        return text
    }

    private fun claimArg(args: JsonObject): Claim {
        val id = args.stringArg("id").trim()
        return known[id] ?: throw ToolInputException("Unknown claim id $id; use an id from the known claims or claims to re-verify.")
    }

    private fun levelArg(args: JsonObject): ClaimLevel {
        val value = args.stringArg("level").trim().uppercase()
        return ClaimLevel.entries.firstOrNull { it.name == value } ?: throw ToolInputException("level must be symbol, file, module or flow")
    }

    private fun anchorArg(json: JsonObject, prefix: String): Anchor {
        val kind = json.stringArg("kind", prefix).trim().uppercase()
        val anchorKind = AnchorKind.entries.firstOrNull { it.name == kind }
            ?: throw ToolInputException("${prefix}kind must be one of ${AnchorKind.entries.joinToString { it.name.lowercase() }}")
        return Anchor(anchorKind, json.stringArg("ref", prefix).trim())
    }

    private fun citationArg(json: JsonObject, prefix: String): Citation {
        fun line(name: String): Int? {
            val value: JsonElement = json[name]?.takeUnless { it.isJsonNull } ?: return null
            val number = if (value.isJsonPrimitive) value.asString.trim().toIntOrNull() else null
            return number?.takeIf { it >= 1 } ?: throw ToolInputException("$prefix$name must be a positive line number")
        }
        val start = line("start_line")
        val end = line("end_line")
        if (start != null && end != null && end < start) throw ToolInputException("${prefix}end_line is before start_line")
        return Citation(json.stringArg("path", prefix).trim(), start, end, json.optionalStringArg("note", prefix))
    }

    private companion object {
        const val MAX_ANCHORS = 8
        const val MAX_CITATIONS = 8
        val ID_SCHEMA = JsonSchemas.string(minLength = 1, description = "A claim id such as c-0123456789ab.")
        val ANCHOR_SCHEMA = JsonSchemas.obj(
            "kind" to JsonSchemas.enum(AnchorKind.entries.map { it.name.lowercase() },
                description = "file or directory (project-relative path), symbol or entrypoint (FQN), call_edge (caller->callee)."),
            "ref" to JsonSchemas.string(minLength = 1),
            additionalProperties = false)
        val CITATION_SCHEMA = JsonSchemas.obj(
            "path" to JsonSchemas.string(minLength = 1, description = "Project-relative path."),
            "start_line" to JsonSchemas.integer(minimum = 1),
            "end_line" to JsonSchemas.integer(minimum = 1),
            "note" to JsonSchemas.string(minLength = 1),
            required = listOf("path"), additionalProperties = false)
    }
}
