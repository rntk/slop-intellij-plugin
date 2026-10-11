package dev.insight.dashboard

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.insight.ui.card
import dev.insight.ui.heading
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

class DashboardPanel(project: Project) : JPanel(BorderLayout(0, JBUI.scale(20))) {
    val range = JComboBox(arrayOf("Last 7 days", "Last 14 days"))
    init {
        border = JBUI.Borders.empty(24)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(heading("Insight Workbench", 26f), BorderLayout.NORTH)
            add(JLabel("${project.name} · Sample data · A space to explore your project"), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 8)).apply {
                add(range)
                add(JButton("Open chat").apply {
                    addActionListener { ToolWindowManager.getInstance(project).getToolWindow("Insight Chat")?.activate(null) }
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)

        val activity = ActivityChart(DemoData.activityFor(7))
        range.addActionListener {
            activity.values = DemoData.activityFor(if (range.selectedIndex == 0) 7 else 14)
        }
        val detail = JLabel("Select a module to inspect its sample metrics.")
        val modules = JBList(DemoData.modules.map { it.name }).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            addListSelectionListener {
                if (!it.valueIsAdjusting && selectedIndex >= 0) {
                    val module = DemoData.modules[selectedIndex]
                    detail.text = "${module.name}: ${module.files} files · ${module.coverage}% coverage (sample)"
                }
            }
        }
        val body = JPanel(BorderLayout(0, 20)).apply {
            add(JPanel(GridLayout(1, 3, 12, 0)).apply {
                add(card("Files · sample", heading("160", 28f)))
                add(card("Modules · sample", heading("4", 28f)))
                add(card("Activity · sample", heading("${DemoData.activity.sum()} events", 28f)))
            }, BorderLayout.NORTH)
            add(JPanel(GridLayout(2, 1, 0, 16)).apply {
                add(card("Activity · events per day", activity))
                add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    card("Module coverage · %", CoverageChart()),
                    card("Module relationships · sample", ModuleGraph())
                ).apply { resizeWeight = 0.5; border = JBUI.Borders.empty() })
            }, BorderLayout.CENTER)
            add(card("Explore modules", JPanel(BorderLayout(0, 8)).apply {
                add(JBScrollPane(modules).apply { preferredSize = Dimension(200, 90) }, BorderLayout.CENTER)
                add(detail, BorderLayout.SOUTH)
            }), BorderLayout.SOUTH)
        }
        add(JBScrollPane(body).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }
}
