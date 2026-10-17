package dev.insight.diff

import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException

/** Reserve file metadata and pagination before adding optional context, budgeting escaped JSON. */
internal fun diffStateResult(
    snapshot: ReviewSnapshot,
    scopePaths: Set<String>?,
    selected: List<String>,
    notes: List<DiffNote>,
    offset: Int,
    patchBudget: Int,
    sources: List<DiffSourceBlock> = emptyList(),
): String {
    if (offset < 0 || offset > snapshot.files.size) throw ToolInputException("Offset is outside the file list")
    val selectedSet = selected.toHashSet()
    val annotatedPaths = notes.flatMapTo(hashSetOf()) { it.paths }
    val files = mutableListOf<MutableMap<String, Any>>()
    val selectedPaths = mutableListOf<String>()
    val excerpts = mutableListOf<Map<String, Any>>()
    var pageNotes = emptyList<DiffNote>()
    fun updatePageNotes() {
        val paths = files.mapTo(hashSetOf()) { it.getValue("path") }
        pageNotes = notes.filter { note -> note.paths.any { it in paths } }
    }
    fun result(): String = reviewJson.toJson(buildMap<String, Any> {
        put("scope", if (scopePaths == null) "all changes" else "fixed file selection")
        put("total_files", snapshot.files.size)
        if (sources.isNotEmpty()) put("source_blocks_count", sources.size)
        if (selected.isNotEmpty()) put("selected_count", selected.size)
        if (selectedPaths.isNotEmpty()) put("selected_paths", selectedPaths)
        if (pageNotes.isNotEmpty()) put("notes_count", pageNotes.size)
        if (excerpts.isNotEmpty()) put("notes", excerpts)
        put("files", files)
        (offset + files.size).takeIf { it < snapshot.files.size }?.let { put("next_offset", it) }
    })
    fun fits() = result().length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS

    val candidates = snapshot.files.asSequence().drop(offset).take(DIFF_PAGE_SIZE).toList()
    for (file in candidates) {
        files += mutableMapOf<String, Any>("path" to file.path, "status" to file.status,
            "patch_characters" to file.patch.length).apply {
            file.previousPath?.let { put("previous_path", it) }
            if (file.path in selectedSet) put("selected", true)
            if (file.path in annotatedPaths) put("has_note", true)
            sources.filter { it.range.path == file.path }.takeIf { it.isNotEmpty() }?.let { blocks ->
                put("source_blocks", blocks.map { mapOf("side" to it.range.side,
                    "start_line" to it.range.startLine, "end_line" to it.range.endLine) })
            }
        }
        updatePageNotes()
        if (!fits()) {
            files.removeAt(files.lastIndex)
            updatePageNotes()
            if (files.isEmpty()) throw ToolInputException("File metadata exceeds the result limit at offset $offset.")
            break
        }
    }
    // Counts expose omitted context; selection flags remain available on every file's own page.
    for (path in selected.take(100)) {
        selectedPaths += path
        if (!fits()) { selectedPaths.removeAt(selectedPaths.lastIndex); break }
    }
    for (note in pageNotes.take(20)) {
        excerpts += buildMap {
            put("paths", note.paths)
            put("text_excerpt", note.text.take(160))
            note.symbol?.let { put("symbol", it) }
        }
        if (!fits()) excerpts.removeAt(excerpts.lastIndex)
    }
    var budget = patchBudget
    for ((index, file) in candidates.take(files.size).withIndex()) {
        if (file.patch.length > budget) continue
        val entry = files[index]
        entry.remove("patch_characters")
        entry["patch"] = file.patch
        if (fits()) budget -= file.patch.length
        else {
            entry.remove("patch")
            entry["patch_characters"] = file.patch.length
        }
    }
    return result()
}
