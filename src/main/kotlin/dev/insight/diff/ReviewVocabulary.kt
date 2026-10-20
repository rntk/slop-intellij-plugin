package dev.insight.diff

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * The words of the review tabs, so the same cards serve a diff and a whole code base. [DIFF] holds the exact strings of
 * Diff Review; [CODE] speaks of source files and lines instead of changes.
 */
internal data class ReviewVocabulary(
    /** The canvas editor's name and its tab prefix: "$canvasTitle: name". */
    val canvasTitle: String,
    /** The heading and hint lines of the empty canvas. */
    val canvasHeading: String,
    val canvasHints: List<String>,
    val summariesTitle: String,
    val summariesTooltip: String,
    val summariesOutlineTooltip: String,
    val summarizeTooltip: String,
    val summariesFooter: String,
    val topicsTitle: String,
    val topicsTooltip: String,
    /** The tooltip of the topics card's button that writes the file summaries. */
    val topicsSummarizeTooltip: String,
    /** The root's name in the outline's details: "All changes" | "Whole scope". */
    val rootName: String,
    /** Shown instead of a tree without files. */
    val noFiles: String,
    /** The link and button text that opens a file: "View diff" | "Open file". */
    val openLabel: String,
    val openTooltip: (String) -> String,
    /** The button that puts a file on the canvas; null hides it. */
    val showOnCanvas: String?,
    val showOnCanvasIcon: Icon,
    /** What a file node's details say after its name: "modified file · renamed from x" | "420 lines · 3 parts". */
    val describeFile: (ReviewSource?) -> String,
    /** Topics Document mode shows patches, so it exists only for diffs. */
    val documentMode: Boolean,
    /** Start a large topic outline folded to its first level. */
    val foldLargeTopics: Boolean,
    /** Score only the nodes currently shown, as a huge tree has too many words to score at once. */
    val scoreVisibleOnly: Boolean,
) {
    companion object {
        val DIFF = ReviewVocabulary(
            canvasTitle = "Diff Review",
            canvasHeading = "Diff review tabs",
            canvasHints = listOf(
                "Select files on the left to inspect changes.",
                "Click Description or type /description below to add notes.",
                "Click Flows or type /flows to map the change from overview to code.",
                "Click Summaries or type /summaries to read the change from the whole down to each file.",
                "Click Topics or type /topics to read every diff as one document, grouped by topic, with each file's summary.",
            ),
            summariesTitle = "Change summaries · From the whole change down to each file",
            summariesTooltip = "The tree comes from the changed paths. Each summary is the assistant's interpretation, written from the files up to the root.",
            summariesOutlineTooltip = "Indented summaries from the whole change down to each file",
            summarizeTooltip = "Summarize the files, then the folders above them, up to the whole change (/summaries)",
            summariesFooter = "The tree comes from the changed paths; single-entry folders are merged and large folders split into groups. " +
                "Summaries are LLM interpretation. Select one to see its file or folder.",
            topicsTitle = "Change topics",
            topicsTooltip = "Every changed diff in one document, ordered by topic. File boundaries, order and alignment come from code; " +
                "topics and summaries are the assistant's interpretation.",
            topicsSummarizeTooltip = "Write the file summaries shown beside each diff (/summaries)",
            rootName = "All changes",
            noFiles = "No changed files",
            openLabel = "View diff",
            openTooltip = { path -> "View changes in $path" },
            showOnCanvas = "Show diff in tab",
            showOnCanvasIcon = AllIcons.Actions.Diff,
            describeFile = { file ->
                val review = file as? ReviewFile
                "${review?.status ?: "changed"} file" + (review?.previousPath?.let { " · renamed from $it" } ?: "")
            },
            documentMode = true,
            foldLargeTopics = false,
            scoreVisibleOnly = false,
        )

        val CODE = ReviewVocabulary(
            canvasTitle = "Code Review",
            canvasHeading = "Code review tabs",
            canvasHints = listOf(
                "Ask about the code in scope; answers cite files and lines.",
                "Click Summaries or type /summaries to read the code from the whole scope down to each file.",
                "Click Topics or type /topics to group the files by responsibility, with each file's summary.",
            ),
            summariesTitle = "Code summaries · From the whole scope down to each file",
            summariesTooltip = "The tree comes from the folders in scope. Each summary is the assistant's interpretation, written from the code up to the root.",
            summariesOutlineTooltip = "Indented summaries from the whole scope down to each file",
            summarizeTooltip = "Summarize the files, then the folders above them, up to the whole scope (/summaries)",
            summariesFooter = "The tree comes from the folders in scope; single-entry folders are merged, large folders split into groups " +
                "and large files into parts. Summaries are LLM interpretation. Select one to see its file or folder.",
            topicsTitle = "Code topics",
            topicsTooltip = "The files in scope grouped by topic. Topics and summaries are the assistant's interpretation of the code.",
            topicsSummarizeTooltip = "Write the file summaries shown beside each file (/summaries)",
            rootName = "Whole scope",
            noFiles = "No files in scope",
            openLabel = "Open file",
            openTooltip = { path -> "Open $path in the editor" },
            showOnCanvas = "Show source in tab",
            showOnCanvasIcon = AllIcons.Actions.Preview,
            describeFile = { file -> file?.facts?.joinToString(" · ")?.takeIf { it.isNotEmpty() } ?: "file" },
            documentMode = false,
            foldLargeTopics = true,
            scoreVisibleOnly = true,
        )
    }
}
