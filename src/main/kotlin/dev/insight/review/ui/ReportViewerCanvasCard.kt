package dev.insight.review.ui

import com.intellij.diff.DiffManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.canvas.relativePath
import dev.insight.context.ContextSummaryActions
import dev.insight.context.ReviewDestination
import dev.insight.context.showContextPicker
import dev.insight.diff.ReviewDiffSession
import dev.insight.diff.ReviewFile
import dev.insight.diff.createDiffRequest
import dev.insight.diff.showDiffLines
import dev.insight.review.ReferencedFile
import dev.insight.review.RenderedReport
import dev.insight.review.ReportIndexedEntry
import dev.insight.review.ReportLink
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReportThemeColors
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryNode
import dev.insight.review.formatLinkLocation
import dev.insight.review.isMarkdownFileName
import dev.insight.review.linkTooltip
import dev.insight.review.looksLikeMarkdown
import dev.insight.review.parseReportLinkHref
import dev.insight.review.renderMarkdownReport
import dev.insight.review.renderPlainTextReport
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<ReportViewerCanvasCard>()

/** Scale factor applied to the IDE label font for Report viewer reading prose and code. */
internal const val REPORT_TEXT_FONT_SCALE = 1.2f

/** Report text is 20% larger than standard IDE labels for comfortable reading. */
internal fun Font.forReportText(): Font = deriveFont(size2D * REPORT_TEXT_FONT_SCALE)

/** The review data supplied to [ReportViewerCanvasCard]. */
internal data class ReportViewerCard(
    val reviewFiles: List<ReviewSource>,
    val summaryTree: ReviewSummaryTree?,
    val revision: Int = 0,
)

/** What report the viewer currently displays. */
internal sealed interface ReportSource {
    val label: String
    val reportFileDir: String?
    val isMarkdownHint: Boolean

    data class ProjectFile(val path: String, val absolutePath: String? = null) : ReportSource {
        override val label: String get() = path
        override val reportFileDir: String get() = path.substringBeforeLast('/', "")
        override val isMarkdownHint: Boolean get() = isMarkdownFileName(path)
    }

    data class ExternalFile(val file: VirtualFile, val displayName: String = file.name) : ReportSource {
        override val label: String get() = displayName
        override val reportFileDir: String? get() = null
        override val isMarkdownHint: Boolean get() = isMarkdownFileName(displayName)
    }

    data class PastedText(override val label: String, val text: String) : ReportSource {
        override val reportFileDir: String? get() = null
        override val isMarkdownHint: Boolean get() = looksLikeMarkdown(text)
    }
}

/** Persistent per-tab state of the Report viewer across canvas rebuilds. */
internal class ReportViewerState {
    var source: ReportSource? = null
    var rawTextOverride: Boolean? = null
    var scroll: Int = 0
    var fileFilter: String = ""
    var reportFiles: List<String> = emptyList()
    var rendered: RenderedReport? = null
    var rawContent: String? = null
    var rawContentSource: ReportSource? = null
    var errorMessage: String? = null
    var noticeMessage: String? = null
    var loading: Boolean = false
    var indexLoaded: Boolean = false
}

/**
 * Shared Markdown and plain-text report viewer hosted on [NoteCanvas] for both Code Review and Diff Review.
 * Resolves project file paths, directories, line ranges, and contextual line mentions into clickable links with
 * full [dev.insight.context.showContextPicker] support.
 */
internal class ReportViewerCanvasCard(
    private val vocabulary: ReviewVocabulary,
    private val refresh: () -> Unit,
    private val close: (() -> Unit)? = null,
    private val project: Project? = null,
    private val openOnCanvas: ((SummaryNode) -> Unit)? = null,
    private val openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    private val reviewDestinations: () -> Map<ReviewDestination, () -> Unit> = { emptyMap() },
    private val summaryActions: ContextSummaryActions? = null,
) : Disposable {
    val view = ReportViewerState()
    var onStatsChanged: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private var disposed = false
    private var currentCard: JPanel? = null
    internal var navigation: ReportViewerNavigation? = null
        private set
    private var pendingFocusTarget: String? = null
    private var pendingFilterCaret: Int? = null
    private var latestContent: ReportViewerCard? = null
    private var snapshot: ProjectReportSnapshot? = null
    private var snapshotRevision: Int = -1
    private var snapshotDir: String? = null
    private var indexJob: Job? = null
    private var renderJob: Job? = null
    private var hoveredLink: ReportLink? = null

    /** Tile stats: empty before a report has been rendered; link and file counts once loaded. */
    val stats: List<ReviewTileStat>
        get() {
            val rendered = view.rendered ?: return emptyList()
            return listOf(
                countStat(rendered.links.size, "link"),
                countStat(rendered.referencedFiles.size, "file"),
            )
        }

    fun installNavigation(card: JPanel, page: JScrollPane, host: JComponent) {
        if (card === currentCard) navigation?.install(page, host)
    }

    fun update(content: ReportViewerCard?) {
        latestContent = content
        if (content == null) {
            navigation?.dispose()
            navigation = null
            pendingFocusTarget = null
            pendingFilterCaret = null
            currentCard = null
            hoveredLink = null
            return
        }
        if (snapshot != null && snapshotRevision != content.revision) {
            ensureProjectIndex(content, forceReload = true, rerender = view.source != null)
        }
    }

    fun card(content: ReportViewerCard): JPanel {
        latestContent = content
        captureFocusIfNeeded()
        if (snapshot == null || snapshotRevision != content.revision || snapshotDir != view.source?.reportFileDir) {
            ensureProjectIndex(content, forceReload = false, rerender = view.source != null && view.rendered == null)
        }

        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
        }
        val statusLabel = JLabel(defaultStatusText()).apply {
            name = "report-viewer-status"
            font = JBFont.small()
            foreground = if (view.noticeMessage != null) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(4, 10)
        }

        card.add(buildToolbar(), BorderLayout.NORTH)
        val center = if (view.source == null) {
            buildEmptyState()
        } else {
            buildReaderBody(card, content, statusLabel)
        }
        card.add(center, BorderLayout.CENTER)
        card.add(statusLabel, BorderLayout.SOUTH)

        val bodyScroll = (center as? JScrollPane)?.takeIf {
            it.name == REPORT_VIEWER_SCROLL_NAME || it.name == REPORT_VIEWER_FILE_LIST_SCROLL_NAME
        } ?: (center as? Container)?.let { container ->
            findDescendant<JScrollPane>(container) {
                it.name == REPORT_VIEWER_SCROLL_NAME || it.name == REPORT_VIEWER_FILE_LIST_SCROLL_NAME
            }
        }
        val pane = (center as? Container)?.let { container ->
            findDescendant<JEditorPane>(container) { it.name == REPORT_VIEWER_PANE_NAME }
        }
        navigation?.dispose()
        navigation = ReportViewerNavigation(card, bodyScroll, pane)
        currentCard = card

        val targetName = pendingFocusTarget
        if (targetName != null) {
            val target = findDescendant<JComponent>(card) { it.name == targetName }
            if (target != null) {
                val restoreCaret = pendingFilterCaret
                pendingFocusTarget = null
                pendingFilterCaret = null
                SwingUtilities.invokeLater {
                    if (currentCard !== card || !target.isShowing) return@invokeLater
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    val scope = navigation?.scopeRoot ?: card
                    val insideScope = owner == null || owner === scope || SwingUtilities.isDescendingFrom(owner, scope)
                    if (insideScope) {
                        target.requestFocusInWindow()
                        if (target is JBTextField && restoreCaret != null) {
                            target.caretPosition = restoreCaret.coerceIn(0, target.document.length)
                        }
                    }
                }
            }
        }
        return card
    }

    /** Opens a project-relative `.md`, `.txt`, or `.log` file in the report viewer. */
    fun openProjectFile(relativePath: String) {
        val clean = relativePath.trim().replace('\\', '/').trimStart('/')
        val abs = snapshot?.index?.absolutePathFor(clean)
        view.source = ReportSource.ProjectFile(clean, abs)
        view.rawTextOverride = null
        view.scroll = 0
        view.rendered = null
        view.rawContent = null
        view.rawContentSource = null
        view.errorMessage = null
        view.noticeMessage = null
        pendingFocusTarget = REPORT_VIEWER_PANE_NAME
        pendingFilterCaret = null
        loadAndRenderCurrent(latestContent)
    }

    /** Opens any IDE [VirtualFile] (inside or outside the project) in the report viewer. */
    fun openVirtualFile(file: VirtualFile) {
        val proj = project
        val indexedRel = snapshot?.index?.pathForAbsolute(file.path)
        val rel = indexedRel ?: if (proj != null) ReadAction.compute<String?, RuntimeException> {
            val candidate = relativePath(proj, file.path)
            if (candidate != file.path && findReportSourceFile(proj, candidate, file.path) != null) candidate else null
        } else null
        view.source = if (rel != null) ReportSource.ProjectFile(rel, file.path) else ReportSource.ExternalFile(file)
        view.rawTextOverride = null
        view.scroll = 0
        view.rendered = null
        view.rawContent = null
        view.rawContentSource = null
        view.errorMessage = null
        view.noticeMessage = null
        pendingFocusTarget = REPORT_VIEWER_PANE_NAME
        pendingFilterCaret = null
        loadAndRenderCurrent(latestContent)
    }

    /** Renders pasted report [text] directly against the current project index. */
    fun loadPastedText(text: String, label: String = "Pasted report") {
        val src = ReportSource.PastedText(label, text)
        view.source = src
        view.rawTextOverride = null
        view.scroll = 0
        view.errorMessage = null
        view.noticeMessage = null
        view.rawContent = text
        view.rawContentSource = src
        pendingFocusTarget = REPORT_VIEWER_PANE_NAME
        pendingFilterCaret = null
        renderTextContent(text, latestContent)
    }

    /** Reloads the project index and re-renders the active report. */
    fun reload() {
        val content = latestContent
        indexJob?.cancel()
        snapshot = null
        view.noticeMessage = null
        val src = view.source
        if (src != null) {
            pendingFocusTarget = REPORT_VIEWER_PANE_NAME
            pendingFilterCaret = null
            if (src !is ReportSource.PastedText) {
                view.rawContent = null
                view.rawContentSource = null
            }
            if (content != null) ensureProjectIndex(content, forceReload = true, rerender = false)
            loadAndRenderCurrent(content)
        } else {
            if (content != null) ensureProjectIndex(content, forceReload = true, rerender = false)
            refreshIfShowing()
        }
    }

    /** Toggles between Markdown HTML rendering and numbered plain-text rendering. */
    fun setRawTextMode(raw: Boolean) {
        view.rawTextOverride = raw
        view.scroll = 0
        view.noticeMessage = null
        if (view.source != null) {
            pendingFocusTarget = REPORT_VIEWER_PANE_NAME
            pendingFilterCaret = null
        }
        val text = view.rawContent
        if (text != null && view.rawContentSource === view.source) renderTextContent(text, latestContent)
        else if (view.source != null) loadAndRenderCurrent(latestContent)
        else refreshIfShowing()
    }

    private fun isRawMode(): Boolean {
        view.rawTextOverride?.let { return it }
        val src = view.source ?: return false
        return when (src) {
            is ReportSource.PastedText -> !looksLikeMarkdown(view.rawContent ?: src.text)
            else -> !src.isMarkdownHint && !(view.rawContent?.let(::looksLikeMarkdown) ?: false)
        }
    }

    private fun buildToolbar(): JComponent {
        val model = DefaultComboBoxModel<String>()
        val activeSource = view.source
        if (activeSource == null) {
            model.addElement(SELECT_REPORT_ITEM)
        }
        val knownFiles = view.reportFiles
        for (path in knownFiles) model.addElement(path)
        if (activeSource != null && activeSource.label !in knownFiles) {
            model.insertElementAt(activeSource.label, 0)
        }

        var updating = true
        val selector = ComboBox(model).apply {
            name = "report-viewer-file-selector"
            font = JBFont.small()
            preferredSize = Dimension(JBUI.scale(260), preferredSize.height)
            selectedItem = activeSource?.label ?: SELECT_REPORT_ITEM
            toolTipText = "Choose a Markdown, text, or log file in the project"
            addActionListener {
                if (updating) return@addActionListener
                val chosen = selectedItem as? String ?: return@addActionListener
                if (chosen != SELECT_REPORT_ITEM && chosen != view.source?.label && chosen in view.reportFiles) {
                    openProjectFile(chosen)
                }
            }
        }
        updating = false

        val openBtn = JButton("Open file…").apply {
            name = "report-viewer-open-file"
            font = JBFont.small()
            toolTipText = "Open any .md, .txt, or .log file from disk"
            addActionListener { chooseExternalFile() }
        }
        val pasteBtn = JButton("Paste").apply {
            name = "report-viewer-paste"
            font = JBFont.small()
            toolTipText = "Render report text from the clipboard with smart project links"
            addActionListener { pasteFromClipboard() }
        }
        val reloadBtn = JButton("Reload").apply {
            name = "report-viewer-reload"
            font = JBFont.small()
            toolTipText = "Refresh project files and re-read the current report"
            addActionListener { reload() }
        }
        val rawToggle = JCheckBox("Raw text", isRawMode()).apply {
            name = "report-viewer-raw-toggle"
            isOpaque = false
            font = JBFont.small()
            isEnabled = view.source != null
            toolTipText = "Switch between formatted Markdown and numbered plain text (links stay active)"
            addActionListener { setRawTextMode(isSelected) }
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(selector)
            add(openBtn)
            add(pasteBtn)
            add(reloadBtn)
            add(rawToggle)
            view.rendered?.referencedFiles?.takeIf { it.isNotEmpty() }?.let { refs ->
                add(buildReferencedFilesButton(refs))
            }
        }

        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            name = "report-viewer-toolbar"
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 10))
            add(left, BorderLayout.CENTER)
            close?.let { remove ->
                val closeBtn = InplaceButton(IconButton("Close report viewer", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                    remove()
                }.apply { cursor = Cursor.getDefaultCursor() }
                add(closeBtn, BorderLayout.EAST)
            }
        }
    }

    private fun buildReferencedFilesButton(refs: List<ReferencedFile>): JButton {
        val btn = JButton("Files (${refs.size})").apply {
            name = "report-viewer-referenced-files"
            font = JBFont.small()
            toolTipText = "Jump to any project file referenced in this report"
        }
        btn.addActionListener {
            val proj = project ?: return@addActionListener
            data class RefEntry(val ref: ReferencedFile) {
                override fun toString(): String {
                    val scopeTag = if (ref.inReviewScope) {
                        if (vocabulary == ReviewVocabulary.DIFF) " · in diff" else " · in scope"
                    } else ""
                    val count = if (ref.mentions == 1) "1 link" else "${ref.mentions} links"
                    return "${ref.path} ($count$scopeTag)"
                }
            }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(refs.map(::RefEntry))
                .setTitle("Referenced project files (${refs.size})")
                .setItemChosenCallback { chosen ->
                    openReportFile(proj, chosen.ref.path, null, absolutePath = snapshot?.index?.absolutePathFor(chosen.ref.path))
                }
                .createPopup()
                .showUnderneathOf(btn)
        }
        return btn
    }

    private fun buildEmptyState(): JComponent {
        val listModel = DefaultListModel<String>()
        val list = JBList(listModel).apply {
            name = REPORT_VIEWER_FILE_LIST_NAME
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = when {
                !view.indexLoaded -> "Scanning project files…"
                view.reportFiles.isEmpty() -> "No .md, .txt, or .log files found in the project"
                else -> "No matching report files"
            }
        }
        fun refillList(query: String) {
            val previous = list.selectedValue
            listModel.clear()
            val q = query.trim()
            val matches = if (q.isEmpty()) view.reportFiles
            else view.reportFiles.filter { it.contains(q, ignoreCase = true) }
            matches.forEach(listModel::addElement)
            if (!listModel.isEmpty) {
                val idx = previous?.let { prev ->
                    (0 until listModel.size).firstOrNull { listModel.getElementAt(it) == prev }
                } ?: 0
                list.selectedIndex = idx
                list.ensureIndexIsVisible(idx)
            }
        }
        refillList(view.fileFilter)

        fun openSelectedReport() {
            val target = list.selectedValue ?: if (!listModel.isEmpty) listModel.getElementAt(0) else null
            target?.let(::openProjectFile)
        }

        val filterField = JBTextField(view.fileFilter).apply {
            name = REPORT_VIEWER_FILTER_NAME
            emptyText.text = "Filter project .md, .txt, and .log files…"
            document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    view.fileFilter = text
                    refillList(text)
                }
            })
        }

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelectedReport()
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openSelectedReport()
                    e.consume()
                }
            }
        })
        installFilterListNavigation(filterField, list, ::openSelectedReport)

        val header = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 16, 8, 16)
            add(JLabel("Report viewer").apply { font = JBFont.label().asBold().biggerOn(2f) })
            add(JLabel("Read Markdown or plain-text agent reports with smart clickable project paths, line links, and Context Picker support.").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JButton("Open file…").apply { addActionListener { chooseExternalFile() } })
                add(JButton("Paste from clipboard").apply { addActionListener { pasteFromClipboard() } })
            })
        }

        val browser = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 16, 12, 16)
            add(filterField, BorderLayout.NORTH)
            add(JBScrollPane(list).apply {
                name = REPORT_VIEWER_FILE_LIST_SCROLL_NAME
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                horizontalScrollBar.unitIncrement = JBUI.scale(16)
                preferredSize = Dimension(JBUI.scale(740), JBUI.scale(420))
            }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(browser, BorderLayout.CENTER)
        }
    }

    private fun buildReaderBody(card: JPanel, content: ReportViewerCard, statusLabel: JLabel): JComponent {
        val error = view.errorMessage
        if (error != null) {
            return JPanel(VerticalLayout(JBUI.scale(8))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(16)
                add(JLabel(error).apply { foreground = UIUtil.getErrorForeground() })
                add(JButton("Back to file list").apply {
                    addActionListener {
                        view.source = null
                        view.rendered = null
                        view.rawContent = null
                        view.rawContentSource = null
                        view.errorMessage = null
                        view.noticeMessage = null
                        pendingFocusTarget = REPORT_VIEWER_FILTER_NAME
                        pendingFilterCaret = null
                        onStatsChanged()
                        refreshIfShowing()
                    }
                })
            }
        }

        val rendered = view.rendered
        if (rendered == null) {
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(16)
                add(JLabel(if (view.loading) "Loading report…" else "Preparing report…").apply {
                    foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.NORTH)
            }
        }

        val pane = JEditorPane().apply {
            name = REPORT_VIEWER_PANE_NAME
            editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            isEditable = false
            isOpaque = true
            background = UIUtil.getListBackground()
            foreground = UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont().forReportText()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            text = rendered.html
            caretPosition = 0
        }

        pane.addHyperlinkListener { event ->
            val linkId = parseReportLinkHref(event.description) ?: return@addHyperlinkListener
            val link = rendered.links.getOrNull(linkId) ?: return@addHyperlinkListener
            val mousePoint = (event.inputEvent as? MouseEvent)?.point
            when (event.eventType) {
                HyperlinkEvent.EventType.ENTERED -> {
                    hoveredLink = link
                    val tip = linkTooltipFor(link, content)
                    pane.toolTipText = tip
                    statusLabel.foreground = UIUtil.getContextHelpForeground()
                    statusLabel.text = tip
                }
                HyperlinkEvent.EventType.EXITED -> {
                    hoveredLink = null
                    pane.toolTipText = null
                    statusLabel.foreground = if (view.noticeMessage != null) {
                        UIUtil.getErrorForeground()
                    } else {
                        UIUtil.getContextHelpForeground()
                    }
                    statusLabel.text = defaultStatusText()
                }
                HyperlinkEvent.EventType.ACTIVATED -> {
                    view.noticeMessage = null
                    val point = mousePoint ?: Point(pane.width / 2, pane.height / 2)
                    val shift = (event.inputEvent as? MouseEvent)?.isShiftDown == true
                    handleLinkActivation(link, pane, point, shift, content)
                }
            }
        }

        pane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val link = linkAtPoint(pane, e.point, rendered.links) ?: hoveredLink ?: return
                showLinkContextMenu(link, pane, e.point, content)
                e.consume()
            }
        })

        val contextKey = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(contextKey, "report-context-picker")
        pane.actionMap.put("report-context-picker", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val link = hoveredLink ?: linkAtOffset(pane, pane.caretPosition, rendered.links) ?: return
                val point = pane.modelToView2D(pane.caretPosition.coerceIn(0, pane.document.length))
                    ?.let { Point(it.x.toInt(), it.y.toInt() + it.height.toInt()) }
                    ?: Point(pane.width / 2, pane.height / 2)
                triggerContextPicker(link, pane, point, content)
            }
        })

        return JBScrollPane(pane).apply {
            name = REPORT_VIEWER_SCROLL_NAME
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            horizontalScrollBar.unitIncrement = JBUI.scale(16)
            // Keep toolbar and status bar pinned inside NoteCanvas.page(card).
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
            val restore = view.scroll
            verticalScrollBar.addAdjustmentListener {
                if (currentCard === card) view.scroll = it.value
            }
            SwingUtilities.invokeLater {
                if (currentCard === card) {
                    verticalScrollBar.value = restore.coerceIn(0, maxOf(0, verticalScrollBar.maximum - verticalScrollBar.visibleAmount))
                }
            }
        }
    }

    private fun handleLinkActivation(
        link: ReportLink,
        owner: JEditorPane,
        point: Point,
        shiftDown: Boolean,
        content: ReportViewerCard,
    ) {
        if (link.kind == ReportTargetKind.EXTERNAL_URL) {
            link.externalUrl?.let(BrowserUtil::browse)
            return
        }
        val proj = project ?: return
        if (shiftDown || link.kind == ReportTargetKind.DIRECTORY) {
            triggerContextPicker(link, owner, point, content)
            return
        }
        if (link.isAmbiguous) {
            chooseCandidate(link, owner, point, "Open matching file…") { chosen ->
                openReportFile(proj, chosen, link.lines, link.column, absolutePath = snapshot?.index?.absolutePathFor(chosen))
            }
        } else {
            link.primaryPath?.let { path ->
                openReportFile(proj, path, link.lines, link.column, absolutePath = snapshot?.index?.absolutePathFor(path))
            }
        }
    }

    private fun showLinkContextMenu(link: ReportLink, owner: JEditorPane, point: Point, content: ReportViewerCard) {
        val proj = project ?: return
        if (link.kind == ReportTargetKind.EXTERNAL_URL) return
        val menu = JPopupMenu()
        menu.add(JMenuItem("Context…").apply {
            name = "report-link-action:Context"
            addActionListener { triggerContextPicker(link, owner, point, content) }
        })
        if (link.kind == ReportTargetKind.FILE) {
            menu.add(JMenuItem("Open in editor").apply {
                name = "report-link-action:Open"
                addActionListener {
                    if (link.isAmbiguous) {
                        chooseCandidate(link, owner, point, "Open matching file…") { chosen ->
                            openReportFile(proj, chosen, link.lines, link.column, absolutePath = snapshot?.index?.absolutePathFor(chosen))
                        }
                    } else {
                        link.primaryPath?.let { path ->
                            openReportFile(proj, path, link.lines, link.column, absolutePath = snapshot?.index?.absolutePathFor(path))
                        }
                    }
                }
            })
            val primaryPath = link.primaryPath
            val primaryAbs = primaryPath?.let { snapshot?.index?.absolutePathFor(it) }?.let(ReportProjectIndex::normalizeAbsPath)
            val diffFile = if (vocabulary == ReviewVocabulary.DIFF && !link.isAmbiguous && primaryPath != null) {
                val allDiffs = content.reviewFiles.filterIsInstance<ReviewFile>()
                (if (primaryAbs != null) allDiffs.firstOrNull { ReportProjectIndex.normalizeAbsPath(it.absolutePath) == primaryAbs } else null)
                    ?: allDiffs.firstOrNull { it.path == primaryPath }
            } else null
            if (diffFile != null) {
                menu.add(JMenuItem("Open in IDE diff").apply {
                    name = "report-link-action:OpenDiff"
                    addActionListener { openReviewFileDiff(proj, diffFile, link.lines, content) }
                })
            }
        }
        menu.add(JMenuItem("Copy path").apply {
            name = "report-link-action:CopyPath"
            addActionListener {
                val path = link.primaryPath ?: return@addActionListener
                val text = if (link.kind == ReportTargetKind.FILE) formatLinkLocation(path, link.lines, link.column) else "$path/"
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })
        menu.show(owner, point.x, point.y)
    }

    private fun openReviewFileDiff(proj: Project, file: ReviewFile, lines: IntRange?, content: ReportViewerCard) {
        val allDiffs = content.reviewFiles.filterIsInstance<ReviewFile>()
        val sessionProvider: (() -> ReviewDiffSession?)? = openOnWall?.let { wall ->
            {
                ReviewDiffSession(
                    allDiffs,
                    { selected, title -> wall(selected, title) },
                    reviewDestinations(),
                    summaryActions,
                )
            }
        }
        if (lines != null && file.before != null && file.after != null) {
            val evidence = lines.take(2_000).map { EvidenceLine(LineSide.AFTER, it, "", ' ', diff = true) }
            showDiffLines(proj, file, LineTrace(file.path, file.path, "", emptyList(), evidence), sessionProvider)
        } else {
            DiffManager.getInstance().showDiff(proj, createDiffRequest(proj, file, sessionProvider))
        }
    }

    private fun triggerContextPicker(link: ReportLink, owner: JComponent, point: Point, content: ReportViewerCard) {
        val proj = project ?: return
        if (link.kind == ReportTargetKind.EXTERNAL_URL) return
        fun showForPath(path: String) {
            val abs = when (link.kind) {
                ReportTargetKind.FILE -> snapshot?.index?.absolutePathFor(path)
                ReportTargetKind.DIRECTORY -> snapshot?.index?.absoluteDirPathFor(path)
                ReportTargetKind.EXTERNAL_URL -> null
            }
            val pick = reportLinkContextPick(
                project = proj,
                kind = link.kind,
                path = path,
                lines = link.lines,
                reviewFiles = content.reviewFiles,
                summaryTree = content.summaryTree,
                reviewDestinations = reviewDestinations(),
                summaryActions = summaryActions,
                openOnWall = openOnWall,
                openOnCanvas = openOnCanvas,
                absolutePath = abs,
            ) ?: return
            showContextPicker(proj, pick, owner, RelativePoint(owner, point))
        }
        if (link.isAmbiguous) {
            val title = if (link.kind == ReportTargetKind.DIRECTORY) "Choose matching directory…" else "Choose matching file…"
            chooseCandidate(link, owner, point, title, ::showForPath)
        } else {
            link.primaryPath?.let(::showForPath)
        }
    }

    private fun chooseCandidate(
        link: ReportLink,
        owner: JComponent,
        point: Point,
        title: String,
        onChosen: (String) -> Unit,
    ) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(link.candidates)
            .setTitle(title)
            .setItemChosenCallback(onChosen)
            .createPopup()
            .show(RelativePoint(owner, point))
    }

    private fun chooseExternalFile() {
        val proj = project
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Open Markdown or Text Report")
            .withDescription("Choose a Markdown, text, or log file to view with smart project links")
        val initial = proj?.guessProjectDir()
        FileChooser.chooseFile(descriptor, proj, initial)?.let(::openVirtualFile)
    }

    internal fun pasteFromClipboard() {
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (text.isNullOrBlank()) {
            view.noticeMessage = "Clipboard does not contain text."
            refreshIfShowing()
            return
        }
        loadPastedText(text)
    }

    private fun ensureProjectIndex(content: ReportViewerCard, forceReload: Boolean, rerender: Boolean) {
        val proj = project
        val reportDir = view.source?.reportFileDir
        if (proj == null || proj.isDisposed) {
            val entries = content.reviewFiles.map {
                ReportIndexedEntry(it.path, it.absolutePath.takeIf { abs -> abs.isNotBlank() }, inReviewScope = true)
            }
            snapshot = ProjectReportSnapshot(ReportProjectIndex(fileEntries = entries, reportFileDir = reportDir), emptyList())
            snapshotRevision = content.revision
            snapshotDir = reportDir
            view.indexLoaded = true
            val currentSource = view.source
            val currentText = view.rawContent
            if (rerender && currentSource != null && currentText != null && view.rawContentSource === currentSource && renderJob?.isActive != true) {
                renderTextContent(currentText, content)
            }
            return
        }
        if (!forceReload && indexJob?.isActive == true && snapshotRevision == content.revision && snapshotDir == reportDir) return
        indexJob?.cancel()
        val reviewFilesSnapshot = content.reviewFiles
        val revision = content.revision
        indexJob = scope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    collectProjectReportSnapshot(proj, reviewFilesSnapshot, reportDir)
                }
                if (disposed || proj.isDisposed) return@launch
                snapshot = loaded
                snapshotRevision = revision
                snapshotDir = reportDir
                view.reportFiles = loaded.reportFiles
                view.indexLoaded = true
                val currentSource = view.source
                val currentText = view.rawContent
                val canRerender = currentSource != null &&
                    currentText != null &&
                    view.rawContentSource === currentSource &&
                    renderJob?.isActive != true
                if ((rerender || currentSource != null) && canRerender) {
                    renderTextContent(currentText!!, latestContent ?: content)
                } else {
                    refreshIfShowing()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Could not index project report files", e)
            }
        }
    }

    private fun loadAndRenderCurrent(content: ReportViewerCard?) {
        val src = view.source ?: return
        renderJob?.cancel()
        view.loading = true
        view.errorMessage = null
        refreshIfShowing()

        renderJob = scope.launch {
            try {
                val text = when (src) {
                    is ReportSource.PastedText -> src.text
                    is ReportSource.ExternalFile -> withContext(Dispatchers.IO) {
                        ReadAction.compute<String, RuntimeException> {
                            if (!src.file.isValid || src.file.isDirectory) error("File no longer exists: ${src.displayName}")
                            StringUtil.convertLineSeparators(VfsUtilCore.loadText(src.file))
                        }
                    }
                    is ReportSource.ProjectFile -> withContext(Dispatchers.IO) {
                        val proj = project ?: error("No active project")
                        val abs = src.absolutePath ?: snapshot?.index?.absolutePathFor(src.path)
                        val vf = findReportSourceFile(proj, src.path, abs)
                            ?: error("Cannot find ${src.path} in the project")
                        readVirtualFileText(vf) ?: error("Cannot read ${src.path}")
                    }
                }
                if (disposed || view.source !== src) return@launch
                view.rawContent = text
                view.rawContentSource = src
                renderLoadedText(src, text, latestContent ?: content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Could not load report", e)
                if (!disposed && view.source === src) {
                    view.loading = false
                    view.rendered = null
                    view.rawContent = null
                    view.rawContentSource = null
                    view.errorMessage = e.message ?: "Could not load report"
                    pendingFocusTarget = null
                    pendingFilterCaret = null
                    onStatsChanged()
                    refreshIfShowing()
                }
            }
        }
    }

    private fun renderTextContent(text: String, content: ReportViewerCard?) {
        val src = view.source ?: return
        renderJob?.cancel()
        view.loading = true
        renderJob = scope.launch {
            try {
                renderLoadedText(src, text, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Could not render report", e)
            }
        }
    }

    private suspend fun renderLoadedText(src: ReportSource, text: String, content: ReportViewerCard?) {
        for (attempt in 0 until 2) {
            val effectiveContent = latestContent ?: content
            val index = ensureIndexReady(effectiveContent, src.reportFileDir)
            val usedSnapshot = snapshot
            if (disposed || view.source !== src) return
            val rawMode = isRawMode()
            val colors = themeColors()
            val rendered = withContext(Dispatchers.Default) {
                if (rawMode) renderPlainTextReport(text, index, colors) else renderMarkdownReport(text, index, colors)
            }
            if (disposed || view.source !== src) return
            val latestSnapshot = snapshot
            if (attempt == 0 && latestSnapshot != null && latestSnapshot !== usedSnapshot && snapshotDir == src.reportFileDir) {
                continue
            }
            view.rendered = rendered
            view.loading = false
            view.errorMessage = null
            onStatsChanged()
            refreshIfShowing()
            return
        }
    }

    private suspend fun ensureIndexReady(content: ReportViewerCard?, reportFileDir: String?): ReportProjectIndex {
        val proj = project
        val reviewFiles = content?.reviewFiles.orEmpty()
        val rev = content?.revision ?: -1
        val existing = snapshot
        if (existing != null && snapshotRevision == rev && snapshotDir == reportFileDir) {
            return existing.index
        }
        if (proj == null || proj.isDisposed) {
            val entries = reviewFiles.map {
                ReportIndexedEntry(it.path, it.absolutePath.takeIf { abs -> abs.isNotBlank() }, inReviewScope = true)
            }
            return ReportProjectIndex(fileEntries = entries, reportFileDir = reportFileDir)
        }
        val loaded = withContext(Dispatchers.Default) {
            collectProjectReportSnapshot(proj, reviewFiles, reportFileDir)
        }
        if (!disposed) {
            snapshot = loaded
            snapshotRevision = rev
            snapshotDir = reportFileDir
            view.reportFiles = loaded.reportFiles
            view.indexLoaded = true
        }
        return loaded.index
    }

    private fun linkTooltipFor(link: ReportLink, content: ReportViewerCard): String {
        val path = link.primaryPath
        val inScope = path != null && (
            snapshot?.index?.reviewScopeFiles?.contains(path) == true ||
                content.reviewFiles.any { it.path == path }
            )
        return linkTooltip(link, inScope, vocabulary == ReviewVocabulary.DIFF)
    }

    private fun defaultStatusText(): String {
        view.noticeMessage?.let { return it }
        val rendered = view.rendered ?: return when {
            view.source == null -> "${view.reportFiles.size} project report files · Click Open file… or Paste to load any report"
            view.loading -> "Loading report…"
            else -> "Ready"
        }
        val linkWord = if (rendered.links.size == 1) "link" else "links"
        val fileWord = if (rendered.referencedFiles.size == 1) "file" else "files"
        val ambig = if (rendered.ambiguousCount > 0) " (${rendered.ambiguousCount} ambiguous)" else ""
        val trunc = if (rendered.truncated) " · Truncated" else ""
        return "${rendered.links.size} $linkWord across ${rendered.referencedFiles.size} $fileWord$ambig$trunc · " +
            "Click to open · Shift+Click or right-click for Context…"
    }

    private fun linkAtPoint(pane: JEditorPane, point: Point, links: List<ReportLink>): ReportLink? {
        val pos = pane.viewToModel2D(point)
        return if (pos >= 0) linkAtOffset(pane, pos, links) else null
    }

    private fun linkAtOffset(pane: JEditorPane, offset: Int, links: List<ReportLink>): ReportLink? {
        val doc = pane.document as? HTMLDocument ?: return null
        val elem = doc.getCharacterElement(offset.coerceIn(0, maxOf(0, doc.length - 1)))
        val anchor = elem?.attributes?.getAttribute(HTML.Tag.A) as? javax.swing.text.AttributeSet ?: return null
        val href = anchor.getAttribute(HTML.Attribute.HREF) as? String ?: return null
        val id = parseReportLinkHref(href) ?: return null
        return links.getOrNull(id)
    }

    private fun captureFocusIfNeeded() {
        if (pendingFocusTarget != null) return
        val card = currentCard ?: return
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return
        if (owner !== card && !SwingUtilities.isDescendingFrom(owner, card)) return
        when (owner.name) {
            REPORT_VIEWER_PANE_NAME, REPORT_VIEWER_FILE_LIST_NAME -> {
                pendingFocusTarget = owner.name
                pendingFilterCaret = null
            }
            REPORT_VIEWER_FILTER_NAME -> {
                pendingFocusTarget = owner.name
                pendingFilterCaret = (owner as? JBTextField)?.caretPosition
            }
        }
    }

    private fun refreshIfShowing() {
        if (!disposed && currentCard != null) {
            captureFocusIfNeeded()
            refresh()
        }
    }

    private fun themeColors(): ReportThemeColors {
        val bg = UIUtil.getListBackground()
        val fg = UIUtil.getLabelForeground()
        val muted = UIUtil.getContextHelpForeground()
        val border = JBColor.border()
        val linkFg = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val codeBg = JBColor(Color(0xF2F4F7), Color(0x2B2D30))
        val codeBlockBg = JBColor(Color(0xF6F8FA), Color(0x26282E))
        val linkBg = JBColor(Color(0xEEF4FF), Color(0x263449))
        val ambigBg = JBColor(Color(0xFFF4E5), Color(0x453520))
        val tableHeaderBg = JBColor(Color(0xF5F7FA), Color(0x32343A))
        return ReportThemeColors(
            text = "#${ColorUtil.toHex(fg)}",
            muted = "#${ColorUtil.toHex(muted)}",
            background = "#${ColorUtil.toHex(bg)}",
            codeBg = "#${ColorUtil.toHex(codeBg)}",
            codeBlockBg = "#${ColorUtil.toHex(codeBlockBg)}",
            border = "#${ColorUtil.toHex(border)}",
            linkFg = "#${ColorUtil.toHex(linkFg)}",
            linkBg = "#${ColorUtil.toHex(linkBg)}",
            ambiguousLinkBg = "#${ColorUtil.toHex(ambigBg)}",
            tableHeaderBg = "#${ColorUtil.toHex(tableHeaderBg)}",
        )
    }

    override fun dispose() {
        disposed = true
        indexJob?.cancel()
        renderJob?.cancel()
        scope.cancel()
        navigation?.dispose()
        navigation = null
        currentCard = null
        latestContent = null
    }

    private companion object {
        const val SELECT_REPORT_ITEM = "Select project report…"
    }
}
