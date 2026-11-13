package dev.insight.review

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
    /** The root's name in the outline's header: "All changes" | "Whole scope". */
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
    /** The initial summaries view; source reviews start with the complete files as a document. */
    val summariesMode: ReviewSummariesMode,
    /** Start a large topic outline folded to its first level. */
    val foldLargeTopics: Boolean,
    /** The wall's name, as its view option, tab and label row call it: "Diffs wall" | "Code wall". */
    val wallName: String,
    /** The entry menu item that opens an entry's files on the wall, and its tooltip. */
    val openOnWall: String,
    val openOnWallTooltip: String,
    /** The hub tile that opens the Start page, where the source is chosen: "Diff" | "Scope", and what it does. */
    val sourceTitle: String,
    val sourceHint: String,
    /** The hub tile that opens the tab's chat in the side panel. */
    val chatHint: String,
    /** The hub page's heading and hint; see [dev.insight.review.ui.ReviewHubScreen]. */
    val hubHeading: String,
    val hubHint: String,
    /** The tooltip of the hub's New tab button. */
    val newTabTooltip: String,
    /** What the floating button on the Start and view pages does: "Back to the Diff review views". */
    val backToHub: String,
    val groupingTitle: String = "Change grouping",
    val groupingTooltip: String = "Every changed diff in one document, ordered by shared change group and affected code. " +
        "File boundaries, order and alignment come from code; groups and summaries are the assistant's interpretation.",
) {
    companion object {
        val DIFF = ReviewVocabulary(
            canvasTitle = "Diff Review",
            canvasHeading = "Diff review tabs",
            canvasHints = listOf(
                "Select files on the left to inspect changes.",
                "Click Summaries or type /summaries to read the change from the whole down to each file.",
                "Click Topics or type /topics to read every diff as one document, grouped by topic, with each file's summary.",
            ),
            summariesTitle = "Change summaries · From the whole change down to each file",
            summariesTooltip = "The tree comes from the changed paths. Each summary is the assistant's interpretation, written from the files up to the root.",
            summariesOutlineTooltip = "Indented summaries from the whole change down to each file",
            summarizeTooltip = "Summarize the files, then the folders above them, up to the whole change (/summaries)",
            summariesFooter = "The tree comes from the changed paths; each folder is its own level and large folders split into groups. " +
                "Summaries are LLM interpretation. Click a summary to expand or collapse its children.",
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
                val facts = file?.facts.orEmpty()
                "${facts.firstOrNull() ?: "changed"} file" + facts.drop(1).joinToString("") { " · $it" }
            },
            summariesMode = ReviewSummariesMode.SPLIT,
            foldLargeTopics = false,
            wallName = "Diffs wall",
            openOnWall = "Open on diffs wall",
            openOnWallTooltip = "Show the raw diffs of this entry's files together on the diffs wall",
            sourceTitle = "Diff",
            sourceHint = "Choose the diff to review and browse its changed files",
            chatHint = "Ask about this tab's changes; /summaries, /topics, /grouping and /explain run here too",
            hubHeading = "Diff review views",
            hubHint = "Each review tab has its own chat and views of the chosen diff. Pick a view to open it in the editor; " +
                "the side panel shows only the selected tab's chat.",
            newTabTooltip = "Start a fresh chat and canvas for all files in the selected diff",
            backToHub = "Back to the Diff review views",
        )

        val CODE = ReviewVocabulary(
            canvasTitle = "Code Review",
            canvasHeading = "Code review tabs",
            canvasHints = listOf(
                "Ask about the code in scope; answers cite files and lines.",
                "Click Summaries or type /summaries to read every source file as one document, with file paths and summaries.",
                "Click Topics or type /topics to read every source file as one document, grouped by topic.",
            ),
            summariesTitle = "Code summaries · From the whole scope down to each file",
            summariesTooltip = "The tree comes from the folders in scope. Each summary is the assistant's interpretation, written from the code up to the root.",
            summariesOutlineTooltip = "Indented summaries from the whole scope down to each file",
            summarizeTooltip = "Summarize the files, then the folders above them, up to the whole scope (/summaries)",
            summariesFooter = "The tree comes from the folders in scope; each folder is its own level, large folders split into groups " +
                "and large files into parts. Summaries are LLM interpretation. Click a summary to expand or collapse its children.",
            topicsTitle = "Code topics",
            topicsTooltip = "Every source file in one document, ordered by topic, with file summaries and topic cards. " +
                "Topics and summaries are the assistant's interpretation of the code.",
            topicsSummarizeTooltip = "Write the file summaries shown beside each file (/summaries)",
            rootName = "Whole scope",
            noFiles = "No files in scope",
            openLabel = "Open file",
            openTooltip = { path -> "Open $path in the editor" },
            showOnCanvas = "Show source in tab",
            showOnCanvasIcon = AllIcons.Actions.Preview,
            describeFile = { file -> file?.facts?.joinToString(" · ")?.takeIf { it.isNotEmpty() } ?: "file" },
            summariesMode = ReviewSummariesMode.SPLIT,
            foldLargeTopics = true,
            wallName = "Code wall",
            openOnWall = "Open on code wall",
            openOnWallTooltip = "Show the source of this entry's files together on the code wall",
            sourceTitle = "Scope",
            sourceHint = "Choose the folder or entry file to review and browse its files",
            chatHint = "Ask about this tab's code; /summaries, /topics and /explain run here too",
            hubHeading = "Code review views",
            hubHint = "Each review tab has its own chat and views of the chosen scope. Pick a view to open it in the editor; " +
                "the side panel shows only the selected tab's chat.",
            newTabTooltip = "Start a fresh chat and canvas for the whole scope",
            backToHub = "Back to the Code review views",
        )
    }
}
