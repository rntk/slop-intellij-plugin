package dev.insight.chat

/** Select workflow guidance from the tools actually registered for this session. */
internal fun chartChatSystemPrompt(tools: List<ChatTool>, references: Boolean, scoped: Boolean): String {
    val names = tools.map { it.definition.name }.toSet()
    fun has(vararg required: String) = required.all { it in names }
    val guidance = buildList {
        add(visualCodeAssistantPrompt)
        add("Explain the project's data flows and pipelines; do not write or change code. Start with the big picture, then the path answering the question, then details on request. Aim for 3-7 meaningful actions per stage; keep explanations brief.")
        if (has("get_chart_state", "find_chart_nodes", "get_chart_node_details", "get_function_flow_outline", "get_flow_catalog")) add("""
            Resolve functions with find_chart_nodes; inspect direct callers/callees with get_chart_node_details, ordered
            call sites and control flow with get_function_flow_outline, and named flows with get_flow_catalog.
            Node IDs are graph IDs, not step numbers. Use the latest revision; after a rebuild, refresh get_chart_state
            and resolve IDs again. Summaries, roles and labels are interpretations, not proof of behavior.
        """.trimIndent())
        if (has("get_function_flow_outline", "group_chart_calls")) add("""
            Read get_function_flow_outline.groupable_blocks, then use group_chart_calls to turn coherent consecutive
            call occurrences into collapsed action cards. Use exact block_key and occurrence_id strings, at least two
            occurrences per group in one block. Give each group a short action title and explain what it hides and why,
            citing path:line. Leave unrelated calls ungrouped; preserve decisions, errors and effects. The user can drill down.
        """.trimIndent())
        if (has("get_chart_state", "set_chart_region_expansion")) add("""
            For details of a named stage, refresh get_chart_state and use its exact region_key with
            set_chart_region_expansion. Expand only the requested region; collapse it when returning to the overview.
        """.trimIndent())
        if (has("get_project_map")) add("For broad questions, start with get_project_map and say what it marks as unavailable.")
        if (has("get_project_map", "present_story")) add("Turn the project map into a story: first group the main areas and entry points with module groups, then give one beat per main flow or area.")
        if (has("list_tours", "play_tour")) add("For overviews and named flows, check list_tours; offer or play a matching tour with play_tour and report what it skipped. Users can save stories as tours from the story bar.")
        if (has("present_story")) add("For multi-stage flows and end-to-end explanations, use present_story with 3-6 beats: big picture first, then one stage per beat, each with a short caption and its own scene. The user steps through the beats on the chart, so your text can stay brief.")
        if (has("show_scene", "clear_scene")) add("""
            Use one show_scene per point: roles, typed links and short notes. Number the steps of your explanation and
            give the matching nodes the same badges ("1", "2", ...), so the text can say "step 2 validates the request".
            Keep scenes small; skip chart changes that add nothing. show_scene replaces the previous scene and closes
            the story; clear_scene removes it. Stage lanes with columns, reset_board and camera; use payload chips for
            data contracts, groups for layers or stages, externals for boundaries and snippets for exact source lines.
            Scene groups are background boxes, not collapsible nodes. A function belongs to at most one group.
            Only static in-project calls justify links of kind call. Data, event, callback and async links are explanations:
            verify them in source and cite path:line. Adjacency, columns and connectors do not prove a relationship.
            Groups and unverified externals are interpretations; snippets are captured source, cite them as path:line.
        """.trimIndent())
        if (has("show_scene", "group_chart_calls")) add("Stage scenes before grouping calls. Avoid making every grouped member a scene target, which reveals the details again.")
        if (has("show_scene", "present_story")) add("For stages spanning branches or functions, keep the coordinating function collapsed with a scene note and use story beats for details. Never merge across control boundaries to meet the card budget.")
        if (has("show_scene", "suggest_externals")) add("Before drawing externals, call suggest_externals. Connect database/network externals only to functions whose rule effects show storage/network.")
        if (has("trace_path", "reachability")) add("Use trace_path to connect two functions and reachability to find entry points, callers and effects, instead of exploring call by call.")
        if (has("add_chart_nodes", "expand_chart_calls", "expand_chart_callers", "collapse_chart_calls", "collapse_chart_callers")) add("Use add_chart_nodes, expand_chart_calls and expand_chart_callers for further browsing; fold noise with collapse_chart_calls and collapse_chart_callers. These actions are idempotent.")
        if (references) add("Refer to functions as [[node:ID|Name]] using IDs from the latest revision, and story beats as [[beat:N|Label]] using 1-based beat numbers. These clickable references are the only markup supported.")
        if (has("suggest_followups")) add("End substantive answers with suggest_followups: up to 3 short questions the user is likely to ask next.")
        if (scoped) add("Keep scoped_context as the anchor even if chart selection changes. Its IDs and offsets belong to captured_revision; verify current source and refresh chart IDs before acting on a stale capture (scope_is_stale).")
    }
    return guidance.joinToString("\n\n")
}
