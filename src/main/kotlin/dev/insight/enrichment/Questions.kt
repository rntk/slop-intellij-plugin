package dev.insight.enrichment

import com.google.gson.JsonPrimitive
import dev.insight.decisions.DecisionQuestion

/** A cross-cutting technical concern; several may apply to one symbol, so each is its own Noul. */
data class Concern(val id: String, val label: String, val description: String)

/**
 * The fixed vocabulary of node and edge judgments. Change [VERSION] whenever a question's meaning changes,
 * so cached answers to the old wording are not reused.
 */
object Questions {
    const val VERSION = 2

    val concerns = listOf(
        Concern("storage", "Storage", "reading or writing persistent data: files, databases, key-value stores, on-disk caches or settings persistence"),
        Concern("network", "Network", "network or inter-process communication: HTTP clients or servers, sockets, RPC, message queues"),
        Concern("config", "Configuration", "loading, parsing or resolving configuration, settings, environment variables or command-line arguments"),
        Concern("validation", "Validation", "checking inputs, invariants or preconditions and rejecting invalid data"),
        Concern("serialization", "Serialization", "converting data to or from a wire or text format such as JSON, XML, YAML or a binary protocol"),
        Concern("errors", "Error handling", "handling, wrapping, reporting or recovering from errors and failures"),
        Concern("logging", "Logging & telemetry", "logging, metrics, tracing or other diagnostics output"),
        Concern("ui", "User interface", "building, painting or updating a user interface, or reacting to user input events"),
        Concern("security", "Security", "authentication, authorization, credentials, secrets or cryptography"),
        Concern("concurrency", "Concurrency", "threads, coroutines, scheduling, locking or other coordination of concurrent work"),
        Concern("test", "Testing", "tests, test fixtures, fakes or test utilities"),
    )

    val phases = linkedMapOf(
        "bootstrap" to "application or component startup and initialization: wiring, registration, loading initial state",
        "steady" to "normal operation: handling requests, user actions or events while the application runs",
        "background" to "scheduled, deferred or background work running outside the user's immediate action",
        "shutdown" to "cleanup, disposal and shutdown",
        "test" to "tests and test fixtures only",
        "none" to "not tied to any phase, such as pure data types and general helpers",
    )

    val layers = linkedMapOf(
        "presentation" to "user interface or other presentation: views, panels, rendering, CLI output formatting",
        "application" to "orchestration of a use case: services, controllers or coordinators that call other parts in order",
        "domain" to "core domain logic and models: algorithms and rules specific to what this application does",
        "infrastructure" to "adapters to the outside world: storage, network clients, platform or framework integration",
        "utility" to "generic helpers that could be reused unchanged in an unrelated project",
        "test" to "tests and test support code",
    )

    val importance = listOf(
        "Trivial plumbing: a getter, a delegating wrapper, boilerplate or a data holder with no logic",
        "Minor helper: small, local logic that supports something else",
        "Supporting logic: a meaningful step used by a feature, but not the feature itself",
        "Important logic: implements a significant part of a feature or a key decision",
        "Core logic: the central algorithm, model or workflow that defines what this application does",
    )

    /** Question ids; [concernId] maps a concern question id back to [Concern.id]. */
    fun concernQuestionId(concern: Concern) = "concern.${concern.id}"
    fun concernId(questionId: String): String? = questionId.removePrefix("concern.").takeIf { questionId.startsWith("concern.") }
    const val PHASE = "phase"
    const val LAYER = "layer"
    const val IMPORTANCE = "importance"
    const val ENTRY_POINT = "entryPoint"
    const val UTILITY = "utility"
    const val DOMAIN = "domain"
    const val ERROR_PATH = "errorPath"
    const val CONTINUES = "continues"

    /**
     * Questions about `symbol` in a state shaped like
     * `{symbol: {name, kind, container, path, language, signature, doc, annotations, code}, callers: [...], callees: [...]}`.
     * [domains] adds the project-specific domain choice when a taxonomy exists.
     */
    fun node(domains: List<Domain>): Map<String, DecisionQuestion> = buildMap {
        concerns.forEach { concern ->
            put(concernQuestionId(concern), DecisionQuestion.Noul(
                "Is a main responsibility of `symbol` ${concern.description}? " +
                    "Answer no when it merely calls something that does this as an incidental step."))
        }
        put(PHASE, DecisionQuestion.Choice("In which lifecycle phase of the application does `symbol` typically run?", phases.json()))
        put(LAYER, DecisionQuestion.Choice("Which architectural layer does `symbol` belong to?", layers.json()))
        put(IMPORTANCE, DecisionQuestion.Score(
            "How central is `symbol` to what this application does? Judge the symbol's own logic, not its callers or callees.",
            importance.map(::JsonPrimitive)))
        put(ENTRY_POINT, DecisionQuestion.Noul(
            "Is `symbol` an entry point that the runtime, framework, user or another process invokes directly, such as a main function, " +
                "a command-line command, an HTTP route handler, a UI action or event listener, a plugin extension or a test method?"))
        put(UTILITY, DecisionQuestion.Noul(
            "Is `symbol` a generic, reusable utility that could be copied unchanged into an unrelated project, " +
                "rather than code specific to this application's features?"))
        if (domains.isNotEmpty()) {
            val options = LinkedHashMap<String, String>()
            domains.forEach { options[it.id] = "${it.name}: ${it.description}" }
            options[Domain.NONE] = "none of these areas; generic or cross-cutting code"
            put(DOMAIN, DecisionQuestion.Choice("Which functional area of the application does `symbol` mainly belong to?", options.json()))
        }
    }

    /**
     * Questions about the call from `caller` to `callee` in a state shaped like
     * `{caller: {name, kind, container, path, signature, code}, callee: {name, kind, container, path, signature, doc}}`.
     */
    fun edge(): Map<String, DecisionQuestion> = linkedMapOf(
        ERROR_PATH to DecisionQuestion.Noul(
            "Does `caller` call `callee` only on an error, failure, fallback or exceptional path, " +
                "such as inside a catch block, after a failed check, or to report a problem?"),
        CONTINUES to DecisionQuestion.Noul(
            "Does the call from `caller` to `callee` carry out a step of the same logical operation that `caller` performs, " +
                "as opposed to an incidental helper call such as logging, formatting, conversion, a generic utility or a simple accessor?"),
    )

    private fun Map<String, String>.json() = mapValues { (_, value) -> JsonPrimitive(value) }
}
