package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.treeStructure.Tree
import dev.insight.review.ChatFocus
import dev.insight.review.chatFocusTabName
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The review tabs of a tool window: adding, renaming, selecting and closing them, To Chat, and opening the selected tab's
 * canvas when the tool window shows once the project has started. [create] makes a tab for a scope (null: everything) and
 * chat focus; [prepare] brings a new tab up to date with the panel's snapshot.
 */
internal class ReviewTabsController<T : ReviewTab<*, *>>(
    private val project: Project,
    private val parent: Disposable,
    private val toolWindowId: String,
    /** The name of the first tab: "All changes" | "Whole scope". */
    private val firstName: String,
    /** The action place of the tab labels' close button. */
    private val actionPlace: String,
    private val pages: ReviewSidePages,
    startupReady: Boolean,
    private val selectedPaths: () -> List<String>,
    private val create: (paths: Set<String>?, focus: ChatFocus?, toChat: (ChatFocus) -> Unit) -> T,
    private val prepare: (T) -> Unit,
) : Disposable {
    private var disposed = false
    private var readyToOpenCanvas = startupReady
    private var selectingFromCanvas = false
    private var chatActivations = 0
    private val tabs = JBTabsFactory.createTabs(project, this).apply {
        presentation.isHideTabs = false
        presentation.setSingleRow(true).setSupportsCompression(false).setTabLabelActionsAutoHide(false)
    }
    private val reviewTabs = mutableListOf<T>()
    private var nextTab = 1
    val component: JComponent get() = tabs.component
    /** Every open tab, in the order they were added. */
    val all: List<T> get() = reviewTabs

    init {
        Disposer.register(parent, this)
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                showSelectedChat()
                tabOf(newSelection)?.let {
                    it.highlight(selectedPaths().toSet())
                    if (!selectingFromCanvas && readyToOpenCanvas) it.openCanvas()
                }
            }
        }, this)
        tabs.addTabMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val info = (e.component as? TabLabel
                    ?: SwingUtilities.getAncestorOfClass(TabLabel::class.java, e.component) as? TabLabel)?.info
                    ?: tabs.findInfo(e) ?: return
                if (e.clickCount == 1) {
                    if (e.component !is InplaceButton && SwingUtilities.getAncestorOfClass(InplaceButton::class.java, e.component) == null) {
                        tabOf(info)?.openCanvas(true)
                    }
                    return
                }
                if (e.clickCount != 2) return
                val renamed = Messages.showInputDialog(project, "Tab name:", "Rename Review Tab",
                    null, info.text, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                info.setText(renamed)
                info.setTooltipText("$renamed · Double-click to rename")
                showSelectedChat()
                tabOf(info)?.rename(renamed)
            }
        })
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow.id == toolWindowId && readyToOpenCanvas && !disposed &&
                    !selectingFromCanvas && chatActivations == 0) openCanvas()
            }
        })
        if (!readyToOpenCanvas) {
            StartupManager.getInstance(project).runAfterOpened {
                val open = Runnable {
                    if (!disposed) {
                        readyToOpenCanvas = true
                        openCanvas()
                    }
                }
                if (SwingUtilities.isEventDispatchThread()) open.run() else SwingUtilities.invokeLater(open)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tabOf(info: TabInfo?): T? = info?.component?.takeIf { it is ReviewTab<*, *> } as T?

    val selected: T? get() = tabOf(tabs.selectedInfo)

    fun openCanvas(focus: Boolean = false) { selected?.openCanvas(focus) }

    /**
     * Opens a new tab with the scope [paths] whose chat is focused on [focus], and shows its chat. The canvas stays
     * where it is, so To Chat from a summary does not replace the view it was chosen from.
     */
    fun openChat(focus: ChatFocus, paths: Set<String>?) {
        if (disposed) return
        selectingFromCanvas = true
        try { add(paths, focus) } finally { selectingFromCanvas = false }
        ToolWindowManager.getInstance(project).getToolWindow(toolWindowId)?.let { window ->
            // Activation may show the window asynchronously, after the tab-selection guard has been released.
            chatActivations++
            try { window.activate { chatActivations-- } }
            catch (e: Exception) { chatActivations--; throw e }
        }
        pages.selectChat()
    }

    fun add(paths: Set<String>? = null, focus: ChatFocus? = null) {
        val number = nextTab++
        val name = when {
            focus != null -> chatFocusTabName(focus)
            number == 1 -> firstName
            paths == null -> "Review $number"
            else -> "Selection $number"
        }
        val tab = create(paths, focus) { openChat(it, paths) }
        Disposer.register(parent, tab)
        reviewTabs += tab
        prepare(tab)
        val info = TabInfo(tab).setText(name).setTooltipText("$name · Double-click to rename")
        tab.rename(name)
        tab.onCanvasSelected = {
            if (!disposed && tab in reviewTabs && tabs.selectedInfo !== info) {
                selectingFromCanvas = true
                try { tabs.select(info, false) } finally { selectingFromCanvas = false }
            }
        }
        info.setTabLabelActions(DefaultActionGroup(object : DumbAwareAction(
            "Close Tab", "Discard this tab's chat history, draft and canvas", AllIcons.Actions.Close,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) { close(info) }
        }), actionPlace)
        tabs.addTab(info)
        tabs.select(info, false)
        showSelectedChat()
    }

    private fun close(info: TabInfo) {
        val tab = tabOf(info) ?: return
        if (!reviewTabs.remove(tab)) return
        Disposer.dispose(tab)
        tabs.removeTab(info)
        if (reviewTabs.isEmpty()) add()
        showSelectedChat()
    }

    private fun showSelectedChat() { pages.showChat(selected?.chat, tabs.selectedInfo?.text.orEmpty()) }

    /**
     * The To Chat action of a file tree: a new tab focused on the selected [entries], scoped like the selected tab. When
     * the selection is outside that tab, [outside] is reported.
     */
    fun toChatAction(description: String, enabled: () -> Boolean, entries: () -> List<String>, outside: () -> Unit): AnAction =
        object : DumbAwareAction("To Chat", description, AllIcons.Toolwindows.ToolWindowMessages) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() && entries().isNotEmpty() }
            override fun actionPerformed(e: AnActionEvent) {
                val tab = selected ?: return
                tab.focusOn(entries())?.let { openChat(it, tab.state.scopePaths) } ?: outside()
            }
        }

    override fun dispose() { disposed = true }
}

/** The paths of the [F] files beneath the selected nodes of a file tree. */
internal inline fun <reified F : dev.insight.review.ReviewSource> Tree.selectedFilePaths(): List<String> =
    selectionPaths.orEmpty().flatMap { path ->
        val node = path.lastPathComponent as DefaultMutableTreeNode
        node.depthFirstEnumeration().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? F }.map { it.path }
    }.distinct()

/** Opens the selected file of a file tree on double-click and Enter, where Enter on a folder folds or unfolds it instead. */
internal fun Tree.openFilesOnActivate(actionKey: String, openSelected: () -> Boolean) {
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) openSelected() }
    })
    getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), actionKey)
    actionMap.put(actionKey, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) {
            if (!openSelected()) selectionPath?.let { if (isExpanded(it)) collapsePath(it) else expandPath(it) }
        }
    })
}
