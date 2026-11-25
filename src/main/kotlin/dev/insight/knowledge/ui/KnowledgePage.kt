package dev.insight.knowledge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.Citation
import dev.insight.knowledge.Claim
import dev.insight.knowledge.ClaimStatus
import dev.insight.knowledge.LedgerEntry
import dev.insight.review.ui.LinkedTextPane
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The colors of the Knowledge page. */
internal object KnowledgeColors {
    /** Row background of a ledger entry the ratchet rejected. */
    val REJECTED_BG: Color = JBColor(Color(0xFF, 0xE3, 0xE3), Color(0x4B, 0x2A, 0x2D))
    val REJECTED_FG: Color = JBColor(Color(0xB7, 0x1C, 0x1C), Color(0xFF, 0x8A, 0x80))
    val SUSPECT: Color = JBColor(Color(0xB2, 0x6A, 0x00), Color(0xE0, 0xA0, 0x40))
    val LIVE: Color = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x6A, 0xBF, 0x69))

    fun of(status: ClaimStatus): Color = when (status) {
        ClaimStatus.Live -> LIVE
        is ClaimStatus.Suspect -> SUSPECT
        is ClaimStatus.Retired -> REJECTED_FG
        is ClaimStatus.Superseded -> JBColor.GRAY
    }
}

/** The most ledger entries the Ledger list shows, newest first. */
internal const val LEDGER_ROWS = 500

/**
 * The Knowledge page of a code review: the run controls and counts in a header, then the Claims browser (grouped by area, with
 * a claim's anchors, citations and ledger history), the Ledger (rejected entries, the ratchet's catches, highlighted with their
 * reason), the Reports of the last runs as linked Markdown, and the Activity of the last run. Passive: [show] draws a
 * [KnowledgeContent] and every button calls [actions]. One page lives for the review tab, so a run's updates keep the selection.
 */
internal class KnowledgePage(
    private val project: Project?,
    private val scope: CoroutineScope?,
    private val actions: KnowledgeActions,
) : Disposable {
    private var content = KnowledgeContent()
    private var shownRevision = -1L
    private var shownFilter: ClaimFilter? = null
    private var shownReportSerial = 0
    private var selectedClaim: String? = null

    // ---- Header --------------------------------------------------------------------------------------------------

    internal val countsLabel = JBLabel().apply { name = "knowledge-counts"; font = JBFont.label().asBold() }
    internal val statusLabel = JBLabel().apply { name = "knowledge-status"; foreground = UIUtil.getContextHelpForeground() }
    internal val progressLabel = JBLabel().apply { name = "knowledge-progress"; foreground = UIUtil.getContextHelpForeground() }
    internal val progressBar = JProgressBar(0, 1000).apply { name = "knowledge-progress-bar"; isVisible = false; preferredSize = Dimension(JBUI.scale(160), preferredSize.height) }
    internal val warningsLabel = JBLabel().apply { name = "knowledge-warnings"; isVisible = false; foreground = KnowledgeColors.SUSPECT }
    private val locationLabel = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }

    internal val checkButton = JButton("Check docs", AllIcons.Actions.Checked).apply {
        name = "knowledge-check"
        toolTipText = "Compare the statements of hand-written docs with the code. Docs are never edited"
        addActionListener { actions.checkDocs() }
    }
    internal val chooseButton = JButton("Choose docs…").apply {
        name = "knowledge-choose-docs"
        toolTipText = "Choose the Markdown docs to check; flows/*.md when none is chosen"
        addActionListener { actions.chooseDocs() }
    }
    internal val distillButton = JButton("Distill scope", AllIcons.Actions.Execute).apply {
        name = "knowledge-distill"
        toolTipText = "Read the files in this review's scope and record what holds true about them as claims"
        addActionListener { actions.distill() }
    }
    private val areaChoice = ComboBox<String>().apply {
        name = "knowledge-render-area"
        toolTipText = "The area whose claims the draft doc is written from"
        renderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value?.let(::areaLabel).orEmpty())
            }
        }
    }
    internal val renderButton = JButton("Render doc").apply {
        name = "knowledge-render"
        toolTipText = "Write a draft doc from the claims of the chosen area and compare it with the existing doc. Nothing is written to disk"
        addActionListener { actions.renderDoc(areaChoice.selectedItem as? String ?: "") }
    }
    internal val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply {
        name = "knowledge-stop"
        toolTipText = "Stop the run; what it already recorded stays in the ledger"
        addActionListener { actions.stop() }
    }
    private val closeLabel = JBLabel(AllIcons.Actions.Close).apply {
        name = "knowledge-close"
        toolTipText = "Remove the Knowledge page"
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = actions.close() })
    }

    // ---- Claims --------------------------------------------------------------------------------------------------

    private class AreaNode(val area: String, val count: Int)

    private val filter = ComboBox(ClaimFilter.entries.toTypedArray()).apply {
        name = "knowledge-claim-filter"
        addActionListener { if (selectedItem != shownFilter) redrawClaims() }
    }
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    internal val tree = Tree(treeModel).apply {
        name = "knowledge-claims"
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No claims yet. Check docs or distill the scope"
        cellRenderer = ClaimRenderer()
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? DefaultMutableTreeNode
            (node?.userObject as? Claim)?.let { selectedClaim = it.id }
            (node?.userObject as? AreaNode)?.let { if (areaChoice.itemCount > 0) areaChoice.selectedItem = it.area }
            showClaim((node?.userObject as? Claim))
        }
    }

    private val claimTitle = JBLabel().apply { name = "knowledge-claim-title"; font = JBFont.label().asBold() }
    private val claimText = textArea("knowledge-claim-text")
    private val claimMeta = textArea("knowledge-claim-meta").apply { foreground = UIUtil.getContextHelpForeground() }
    private val anchorModel = DefaultListModel<Anchor>()
    internal val anchors = JBList(anchorModel).apply {
        name = "knowledge-anchors"
        visibleRowCount = 4
        emptyText.text = "No anchors"
        cellRenderer = object : ColoredListCellRenderer<Anchor>() {
            override fun customizeCellRenderer(list: JList<out Anchor>, value: Anchor?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.kind.name.lowercase() + " ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.ref, SimpleTextAttributes.LINK_ATTRIBUTES)
                toolTipText = "Open ${value.ref}"
            }
        }
        onClick { anchor -> actions.openAnchor(anchor) }
    }
    private val citationModel = DefaultListModel<Citation>()
    internal val citations = JBList(citationModel).apply {
        name = "knowledge-citations"
        visibleRowCount = 3
        emptyText.text = "No citations"
        cellRenderer = object : ColoredListCellRenderer<Citation>() {
            override fun customizeCellRenderer(list: JList<out Citation>, value: Citation?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                val lines = value.startLine?.let { start -> value.endLine?.takeIf { it > start }?.let { ":$start-$it" } ?: ":$start" }.orEmpty()
                append(value.path + lines, SimpleTextAttributes.LINK_ATTRIBUTES)
                value.note?.let { append("  ${oneLine(it, 80)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                toolTipText = "Open ${value.path}"
            }
        }
        onClick { citation -> actions.openCitation(citation) }
    }
    private val historyModel = DefaultListModel<String>()
    internal val history = JBList(historyModel).apply { name = "knowledge-history"; visibleRowCount = 5; emptyText.text = "No ledger entries" }
    internal val retireButton = JButton("Retire").apply {
        name = "knowledge-retire"
        toolTipText = "Take this claim out of the live set as the user. It stays in the ledger and can be restored"
        addActionListener { selectedClaim?.let(actions.retire) }
    }
    internal val restoreButton = JButton("Restore").apply {
        name = "knowledge-restore"
        toolTipText = "Make this retired claim live again"
        addActionListener { selectedClaim?.let(actions.restore) }
    }

    // ---- Ledger --------------------------------------------------------------------------------------------------

    private val ledgerModel = DefaultListModel<LedgerEntry>()
    internal val ledger = JBList(ledgerModel).apply {
        name = "knowledge-ledger"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "The ledger is empty"
        cellRenderer = LedgerRenderer()
    }
    private val rejectedOnly = JCheckBox("Only rejected").apply {
        name = "knowledge-rejected-only"
        toolTipText = "Show only the ops the ratchet rejected"
        addActionListener { redrawLedger() }
    }
    private val ledgerSummary = JBLabel().apply { name = "knowledge-ledger-summary"; foreground = UIUtil.getContextHelpForeground() }

    // ---- Reports -------------------------------------------------------------------------------------------------

    private val reportModel = javax.swing.DefaultComboBoxModel<KnowledgeReport>()
    internal val reportChoice = ComboBox(reportModel).apply {
        name = "knowledge-report-select"
        addActionListener { showSelectedReport() }
    }
    internal val copyDraft = JButton("Copy draft").apply {
        name = "knowledge-copy-draft"
        toolTipText = "Copy the rendered draft to the clipboard. The doc itself is never written"
        isEnabled = false
        addActionListener { (reportChoice.selectedItem as? KnowledgeReport)?.copyText?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }
    }
    internal val report: JEditorPane = (if (project != null && scope != null) LinkedTextPane(project, scope)
    else JEditorPane("text/plain", "").apply { isEditable = false; isOpaque = false }).apply { name = "knowledge-report" }

    private val activity = JBTextArea().apply { name = "knowledge-activity"; isEditable = false; lineWrap = true; wrapStyleWord = true }

    internal val tabs = JBTabbedPane().apply { name = "knowledge-tabs" }
    private val reportPanel = JPanel(BorderLayout())

    val card: JPanel = JPanel(BorderLayout()).apply {
        name = "knowledge"
        // Small, so the page's scroll pane hands it the whole viewport.
        preferredSize = Dimension(JBUI.scale(640), JBUI.scale(420))
        add(header(), BorderLayout.NORTH)
        tabs.addTab("Claims", claimsPanel())
        tabs.addTab("Ledger", ledgerPanel())
        tabs.addTab("Reports", reportPanel.apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { add(reportChoice); add(copyDraft) }, BorderLayout.NORTH)
            add(JBScrollPane(report).apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.CENTER)
        })
        tabs.addTab("Activity", JBScrollPane(activity))
        add(tabs, BorderLayout.CENTER)
    }

    private fun header(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 8, 4, 8)
        add(row(countsLabel, locationLabel))
        add(row(checkButton, chooseButton, distillButton, areaChoice, renderButton, stopButton, closeLabel))
        add(row(progressBar, progressLabel, statusLabel))
        add(row(warningsLabel))
    }

    private fun row(vararg parts: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        parts.forEach(::add)
    }

    private fun claimsPanel(): JComponent = JBSplitter(false, 0.45f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(row(JBLabel("Show: "), filter), BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        secondComponent = JBScrollPane(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8)
            add(left(claimTitle))
            add(left(claimText))
            add(left(claimMeta))
            add(left(JBLabel("Anchors").apply { font = JBFont.small().asBold(); border = JBUI.Borders.emptyTop(6) }))
            add(left(JBScrollPane(anchors).apply { preferredSize = Dimension(JBUI.scale(200), JBUI.scale(70)) }))
            add(left(JBLabel("Citations").apply { font = JBFont.small().asBold(); border = JBUI.Borders.emptyTop(6) }))
            add(left(JBScrollPane(citations).apply { preferredSize = Dimension(JBUI.scale(200), JBUI.scale(60)) }))
            add(left(JBLabel("Ledger history").apply { font = JBFont.small().asBold(); border = JBUI.Borders.emptyTop(6) }))
            add(left(JBScrollPane(history).apply { preferredSize = Dimension(JBUI.scale(200), JBUI.scale(90)) }))
            add(left(row(retireButton, restoreButton)))
        })
    }

    private fun ledgerPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(row(rejectedOnly, ledgerSummary), BorderLayout.NORTH)
        add(JBScrollPane(ledger), BorderLayout.CENTER)
    }

    private fun left(component: JComponent): JComponent = component.also { it.alignmentX = Component.LEFT_ALIGNMENT }

    private fun textArea(name: String) = JBTextArea().apply {
        this.name = name
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
    }

    private fun <T> JBList<T>.onClick(open: (T) -> Unit) {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!UIUtil.isActionClick(e, MouseEvent.MOUSE_CLICKED)) return
                val index = locationToIndex(e.point).takeIf { it >= 0 && getCellBounds(it, it)?.contains(e.point) == true } ?: return
                open(model.getElementAt(index))
            }
        })
    }

    // ---- Showing content -----------------------------------------------------------------------------------------

    /** Draws [next]; the claims and ledger are redrawn only when the ledger moved on, so a run's progress keeps the selection. */
    fun show(next: KnowledgeContent) {
        val previous = content
        content = next
        countsLabel.text = "Knowledge · " + countsLine(next.counts)
        locationLabel.text = next.error ?: next.location.orEmpty()
        locationLabel.foreground = if (next.error != null) KnowledgeColors.REJECTED_FG else UIUtil.getContextHelpForeground()
        progressLabel.text = next.progress.orEmpty()
        progressBar.isVisible = next.running && next.fraction != null
        progressBar.value = ((next.fraction ?: 0.0) * 1000).toInt()
        statusLabel.text = next.status.orEmpty()
        warningsLabel.isVisible = next.warnings.isNotEmpty()
        warningsLabel.text = next.warnings.firstOrNull()?.let { "${next.warnings.size} warnings · ${oneLine(it, 160)}" }.orEmpty()
        warningsLabel.toolTipText = next.warnings.joinToString("\n").ifEmpty { null }
        checkButton.isEnabled = !next.running && next.error == null
        chooseButton.isEnabled = !next.running
        checkButton.toolTipText = "Compare the statements of ${next.docs} with the code. Docs are never edited"
        distillButton.isEnabled = !next.running && next.error == null && next.canDistill
        renderButton.isEnabled = !next.running && next.error == null && next.counts.active > 0
        stopButton.isEnabled = next.running
        updateAreas(next)
        if (next.revision != shownRevision || filter.selectedItem != shownFilter) {
            redrawClaims()
            redrawLedger()
            shownRevision = next.revision
        }
        if (previous.activity != next.activity) {
            activity.text = next.activity.joinToString("\n")
            activity.caretPosition = activity.document.length
        }
        updateReports(next)
        card.revalidate()
        card.repaint()
    }

    private fun updateAreas(next: KnowledgeContent) {
        val areas = next.areas
        val current = (0 until areaChoice.itemCount).map { areaChoice.getItemAt(it) }
        if (current == areas) return
        val chosen = areaChoice.selectedItem as? String
        areaChoice.removeAllItems()
        areas.forEach(areaChoice::addItem)
        if (chosen != null && chosen in areas) areaChoice.selectedItem = chosen
    }

    private fun redrawClaims() {
        val chosen = filter.selectedItem as? ClaimFilter ?: ClaimFilter.ACTIVE
        shownFilter = chosen
        val root = DefaultMutableTreeNode()
        var selection: TreePath? = null
        for ((area, claims) in claimsByArea(content.claims, chosen)) {
            val areaNode = DefaultMutableTreeNode(AreaNode(area, claims.size))
            root.add(areaNode)
            for (claim in claims) {
                val node = DefaultMutableTreeNode(claim)
                areaNode.add(node)
                if (claim.id == selectedClaim) selection = TreePath(node.path)
            }
        }
        treeModel.setRoot(root)
        for (row in 0 until root.childCount) tree.expandPath(TreePath(arrayOf(root, root.getChildAt(row))))
        if (selection != null) tree.selectionPath = selection else { tree.clearSelection(); showClaim(null) }
        // Reselecting redraws the detail, which is what a ledger change to the selected claim needs.
        if (selection != null) showClaim(content.claims.firstOrNull { it.id == selectedClaim })
    }

    private fun showClaim(claim: Claim?) {
        anchorModel.clear(); citationModel.clear(); historyModel.clear()
        retireButton.isEnabled = claim?.isActive == true
        restoreButton.isEnabled = claim?.status is ClaimStatus.Retired
        if (claim == null) {
            claimTitle.text = "Select a claim"
            claimText.text = ""
            claimMeta.text = ""
            return
        }
        claimTitle.text = "${claim.id} · ${statusLabel(claim.status)}"
        claimTitle.foreground = KnowledgeColors.of(claim.status)
        claimText.text = claim.text
        claimMeta.text = buildList {
            add("${claim.origin.name.lowercase()} · ${claim.level.name.lowercase()} · area ${areaLabel(claim.area)}")
            statusDetail(claim.status).takeIf { it.isNotBlank() }?.let { add(it) }
            claim.source?.let { add("from $it") }
            if (claim.tags.isNotEmpty()) add("tags: " + claim.tags.sorted().joinToString(", "))
        }.joinToString("\n")
        claim.anchors.forEach(anchorModel::addElement)
        claim.citations.forEach(citationModel::addElement)
        claimHistory(content.entries, claim.id).asReversed().forEach { historyModel.addElement(describeEntry(it)) }
    }

    private fun redrawLedger() {
        ledgerModel.clear()
        val onlyRejected = rejectedOnly.isSelected
        content.entries.asReversed().asSequence().filter { !onlyRejected || rejectionOf(it) != null }.take(LEDGER_ROWS)
            .forEach(ledgerModel::addElement)
        val rejected = content.entries.count { rejectionOf(it) != null }
        ledgerSummary.text = "${content.entries.size} entries · $rejected rejected"
        ledgerSummary.foreground = if (rejected > 0) KnowledgeColors.REJECTED_FG else UIUtil.getContextHelpForeground()
        tabs.setTitleAt(LEDGER_TAB, if (rejected > 0) "Ledger ($rejected rejected)" else "Ledger")
    }

    private fun updateReports(next: KnowledgeContent) {
        val current = (0 until reportModel.size).map { reportModel.getElementAt(it) }
        if (current != next.reports) {
            val kept = (reportChoice.selectedItem as? KnowledgeReport)?.kind
            reportChoice.removeAllItems()
            next.reports.forEach(reportChoice::addItem)
            next.reports.firstOrNull { it.kind == kept }?.let { reportChoice.selectedItem = it }
        }
        if (next.reportSerial != shownReportSerial) {
            shownReportSerial = next.reportSerial
            next.reports.firstOrNull { it.kind == next.selectedReport }?.let {
                reportChoice.selectedItem = it
                tabs.selectedIndex = REPORT_TAB
            }
        }
        showSelectedReport()
    }

    private fun showSelectedReport() {
        val shown = reportChoice.selectedItem as? KnowledgeReport
        report.let { if (it is LinkedTextPane) it.markdown = shown?.markdown.orEmpty() else it.text = shown?.markdown.orEmpty() }
        copyDraft.isEnabled = shown?.copyText != null
        reportChoice.isVisible = reportModel.size > 0
    }

    /** One ledger row: the entry in one line, a rejected one on a highlighted background with its reason. */
    private class LedgerRenderer : ColoredListCellRenderer<LedgerEntry>() {
        override fun customizeCellRenderer(list: JList<out LedgerEntry>, value: LedgerEntry?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            val reason = rejectionOf(value)
            if (reason == null) {
                append("#${value.seq} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.actor + " · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(describeOp(value.op))
                toolTipText = null
            } else {
                icon = AllIcons.General.Error
                val bold = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, KnowledgeColors.REJECTED_FG)
                append("#${value.seq} rejected ", bold)
                append(value.actor + " · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(describeOp(value.op))
                append("  ${oneLine(reason, 240)}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, KnowledgeColors.REJECTED_FG))
                toolTipText = reason
                if (!selected) background = KnowledgeColors.REJECTED_BG
            }
        }
    }

    private class ClaimRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is AreaNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(areaLabel(item.area), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Claim -> {
                    append(statusLabel(item.status).uppercase() + " ", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD, KnowledgeColors.of(item.status)))
                    append(oneLine(item.text, 110))
                    append("  ${item.origin.name.lowercase()} · ${item.level.name.lowercase()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = item.text
                }
            }
        }
    }

    override fun dispose() = Unit

    internal companion object {
        const val LEDGER_TAB = 1
        const val REPORT_TAB = 2
    }
}
