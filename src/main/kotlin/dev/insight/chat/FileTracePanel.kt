package dev.insight.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseEvent
import javax.swing.AbstractCellEditor
import com.intellij.openapi.ui.ComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import dev.insight.agent.FileTrace
import dev.insight.agent.TraceDocument
import dev.insight.agent.TraceKey
import dev.insight.agent.mergeRanges
import dev.insight.agent.traceCoverage
import dev.insight.agent.traceLineRanges
import dev.insight.agent.traceLines

/** A compact, deterministic view of the source supplied during each chat turn. */
internal class FileTracePanel(
    project: Project,
    private val openRange: (TraceDocument, IntRange) -> Unit = FileTraceNavigator(project)::open,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private data class Row(val document: TraceDocument, val ranges: List<IntRange>) {
        val lines by lazy { traceLineRanges(document, ranges) }
        val coverage by lazy { if (document.key.kind == "Unavailable") "Unknown" else traceCoverage(document, ranges) }
    }

    private var traces = emptyList<FileTrace>()
    private var rows = emptyList<Row>()
    private var updating = false
    private val turns = ComboBox<String>()
    private val status = JBLabel()
    private val model = object : DefaultTableModel(arrayOf("File", "Version", "Read", "Lines read"), 0) {
        override fun isCellEditable(row: Int, column: Int) = column == 3 && rows[row].lines.isNotEmpty()
    }
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point).takeIf { it >= 0 }?.let { rows[convertRowIndexToModel(it)] } ?: return null
            return when (columnAtPoint(event.point).takeIf { it >= 0 }?.let(::convertColumnIndexToModel)) {
                0 -> row.document.key.path
                2 -> "${row.coverage} content characters; overlapping reads count once"
                3 -> "Click a range to open and highlight it. Partial means only part of a line was read."
                else -> null
            }
        }
    }.apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoCreateRowSorter = true
        preferredScrollableViewportSize = JBUI.size(360, 160)
        putClientProperty("terminateEditOnFocusLost", true)
        emptyText.text = "No files read yet"
        columnModel.getColumn(0).apply { minWidth = JBUI.scale(100); preferredWidth = JBUI.scale(220) }
        columnModel.getColumn(1).apply { minWidth = JBUI.scale(55); preferredWidth = JBUI.scale(60) }
        columnModel.getColumn(2).apply { minWidth = JBUI.scale(65); preferredWidth = JBUI.scale(70) }
        columnModel.getColumn(3).apply { minWidth = JBUI.scale(110); preferredWidth = JBUI.scale(150) }
    }

    init {
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JLabel("Turn"), BorderLayout.WEST)
            add(turns, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        status.toolTipText = "Percentage of content sent to the model in successful requests. Project means current project content; other versions are review snapshots. Presentation calls alone do not count as model reads. Overlapping reads count once."
        val links = RangeCells()
        table.columnModel.getColumn(3).apply { cellRenderer = links; cellEditor = links }
        table.rowSorter.addRowSorterListener { updateRowHeights() }
        turns.addActionListener { if (!updating) renderFiles() }
        render(emptyList())
    }

    fun render(value: List<FileTrace>) {
        val followLatest = traces.isEmpty() || turns.selectedIndex == traces.lastIndex
        val old = turns.selectedIndex
        val changedTurns = traces != value
        traces = value.toList()
        updating = true
        if (changedTurns) {
            turns.removeAllItems()
            value.forEachIndexed { index, trace -> turns.addItem("${index + 1}: ${trace.question.replace('\n', ' ').take(45)}") }
            if (value.isNotEmpty()) turns.selectedIndex = if (followLatest) value.lastIndex else old.coerceIn(value.indices)
        }
        updating = false
        renderFiles()
    }

    private fun renderFiles() {
        val data = traces.getOrNull(turns.selectedIndex)?.snapshot()
        val accepted = data?.requests.orEmpty().filter { it.state == "Accepted" }
        val evidence = accepted.flatMap { it.evidence.entries }.groupBy({ it.key }, { it.value })
            .mapValues { mergeRanges(it.value.flatten()) }
        val paths = evidence.keys.map { it.path }.toSet() + data?.reads.orEmpty().keys
        val documents = data?.documents.orEmpty()
        val next = documents.values.filter { it.key.path in paths }.map { Row(it, evidence[it.key].orEmpty()) } +
            (paths - documents.keys.map { it.path }.toSet()).sorted().map { Row(TraceDocument(TraceKey(it, "Unavailable"), ""), emptyList()) }
        if (rows != next) {
            val selected = table.selectedRow.takeIf { it >= 0 }?.let { rows[table.convertRowIndexToModel(it)].document.key }
            table.cellEditor?.cancelCellEditing()
            updating = true
            model.rowCount = 0
            rows = next
            rows.forEach { row -> model.addRow(arrayOf(row.document.key.path, row.document.key.kind,
                row.coverage.substringBefore(' '), traceLines(row.document, row.ranges))) }
            updating = false
            updateRowHeights()
            rows.indexOfFirst { it.document.key == selected }.takeIf { it >= 0 }?.let {
                val view = table.convertRowIndexToView(it)
                table.setRowSelectionInterval(view, view)
            }
        }
        status.text = if (rows.isEmpty()) "No files read yet" else "${paths.size} file${if (paths.size == 1) "" else "s"} · Click a line range to open"
        if (accepted.any { it.unparsed > 0 }) status.text += " · Some content unmeasured"
    }

    private fun updateRowHeights() {
        if (updating) return
        for (view in 0 until table.rowCount) {
            val row = rows[table.convertRowIndexToModel(view)]
            table.setRowHeight(view, maxOf(1, row.lines.size) * (table.getFontMetrics(table.font).height + JBUI.scale(4)) + JBUI.scale(4))
        }
    }

    /** Real link controls in the cell editor provide mouse and keyboard navigation. */
    private inner class RangeCells : AbstractCellEditor(), TableCellRenderer, TableCellEditor {
        private var editingValue: Any? = null
        override fun getCellEditorValue(): Any? = editingValue
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component =
            cell(row, selected)
        override fun getTableCellEditorComponent(table: JTable, value: Any?, selected: Boolean, row: Int, column: Int): Component {
            editingValue = value
            return cell(row, selected).apply {
                // JTable forwards the initiating click before the editor's first layout pass.
                setSize(table.getCellRect(row, column, false).size)
                doLayout()
            }
        }

        private fun cell(view: Int, selected: Boolean): JPanel {
            val row = rows[table.convertRowIndexToModel(view)]
            return JPanel(GridLayout(0, 1)).apply {
                border = JBUI.Borders.empty(2, 4)
                background = if (selected) table.selectionBackground else table.background
                if (row.lines.isEmpty()) add(JLabel("None").apply { foreground = if (selected) table.selectionForeground else table.foreground })
                row.lines.forEach { range ->
                    add(LinkLabel(range.label, null, { _, clicked ->
                        cancelCellEditing()
                        openRange(row.document, clicked.lines)
                    }, range).apply {
                        toolTipText = "Open ${row.document.key.path}:${range.label} (${row.document.key.kind.lowercase()})"
                        isFocusable = true
                    })
                }
            }
        }
    }
}
