package dev.insight.codereview

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.CanvasTabKey
import dev.insight.review.WordScope
import dev.insight.review.ui.CanvasExtension
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.ChangeSummaryActions
import dev.insight.review.ui.SnapshotCanvasCards
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryDiffView
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The Words page while it is shown: the words of [scope], one of [scopes]. [words] is null until the first count is done, and
 * stays the last count while [updating].
 */
internal data class CodeWordsPage(
    val words: CodeWords?,
    val scope: WordScope,
    val scopes: List<WordScope>,
    val updating: Boolean = false,
)

/** The code review's own canvas pages: the cloud of the words in scope. Set [words] before the canvas rebuilds. */
internal class CodeCanvasCards(
    private val open: (String) -> Unit,
    private val removeWords: () -> Unit = {},
    /** Counts the words of one file or directory of the scope. */
    private val chooseWordScope: (WordScope) -> Unit = {},
) : CanvasExtension {
    var words: CodeWordsPage? = null
    /** Placing the words is slow, so the cloud is kept across rebuilds while its counts are the same. */
    private var cloud: CodeWordCloud? = null

    override fun attach(refresh: (CanvasTabKey) -> Unit) {}

    override fun update() {
        if (cloud?.words !== words?.words) cloud = null
    }

    override fun leadingCards(): Map<CanvasTabKey, JPanel> = listOfNotNull(words?.let { CanvasTabKey.Words to wordsCard(it) }).toMap()

    override fun trailingCards(snapshots: SnapshotCanvasCards): Map<CanvasTabKey, JPanel> = emptyMap()

    override fun card(key: CanvasTabKey, snapshots: SnapshotCanvasCards): JPanel? =
        if (key == CanvasTabKey.Words) words?.let(::wordsCard) else null

    override fun title(key: CanvasTabKey): String? = if (key == CanvasTabKey.Words) "Words" else null

    override fun summaryDiffView(card: SummariesCard, state: ChangeSummariesViewState, zoom: Double,
                                 actions: ChangeSummaryActions): SummaryDiffView? = null

    /** The most frequent words of the source in scope; a word opens its file, or a list of its files. */
    private fun wordsCard(page: CodeWordsPage): JPanel {
        val width = scaled(WORDS_WIDTH)
        val words = page.words
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(when {
                words == null -> "Words · Counting the words in scope…"
                else -> "Words · ${words.words.size} most frequent of ${words.distinctWords} words" +
                    if (page.updating) " · updating…" else ""
            }).apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                val counted = if (page.scope.path.isEmpty()) "the source files in scope" else page.scope.path
                toolTipText = "Counted from $counted${words?.let { " · ${it.totalWords} words in all" }.orEmpty()}. " +
                    "A word's size and color show how often it occurs; the most frequent sit in the middle. " +
                    "Click a word to open its files."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(wordScopeButton(page))
                add(InplaceButton(IconButton("Remove words", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeWords() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(when {
                words == null -> JLabel("Counting…").apply { foreground = UIUtil.getContextHelpForeground() }
                words.words.isEmpty() -> JLabel("No words in the files in scope.").apply { foreground = UIUtil.getContextHelpForeground() }
                else -> cloud?.takeIf { it.words === words }
                    ?: CodeWordCloud(words, width - scaled(2 * PADDING + 4), scaled(WORDS_HEIGHT), ::openWord).also { cloud = it }
            }, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /** Limits the cloud to one file or directory of the scope, chosen from a list that typing filters. */
    private fun wordScopeButton(page: CodeWordsPage): JButton = JButton(page.scope.toString(), AllIcons.General.ArrowDown).apply {
        name = "word-scope"
        horizontalTextPosition = SwingConstants.LEFT
        font = scaledFont(JBFont.small())
        toolTipText = "Count the words of one file or directory · type in the list to filter it"
        addActionListener {
            JBPopupFactory.getInstance().createPopupChooserBuilder(page.scopes)
                .setTitle("Words Scope")
                .setNamerForFiltering { it.filterName }
                .setSelectedValue(page.scope, true)
                .setMovable(true)
                .setResizable(true)
                .setItemChosenCallback(chooseWordScope)
                .createPopup().showUnderneathOf(this)
        }
    }

    /** Opens the word's only file, or lists its files, most occurrences first, to choose one. */
    private fun openWord(word: CodeWord, click: MouseEvent) {
        val files = word.files.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (files.size == 1) { open(files.single().key); return }
        if (files.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(files)
            .setTitle("Files with “${word.word}”")
            .setRenderer(SimpleListCellRenderer.create("") { "${it.key} · ${it.value}" })
            .setNamerForFiltering { it.key }
            .setItemChosenCallback { open(it.key) }
            .createPopup().show(RelativePoint(click))
    }

    override fun dispose() {
        words = null
        cloud = null
    }

    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    private companion object {
        const val PADDING = 12
        const val WORDS_WIDTH = 1100
        const val WORDS_HEIGHT = 640
    }
}
