package dev.insight.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

/** What an action acts on: the picked [context] among the host's [candidates], where it was picked, and the host's extras. */
class ContextRequest(
    val project: Project,
    val context: CodeContext,
    val candidates: List<CodeContext>,
    val origin: ContextOrigin,
    val data: ContextData,
    /** The component the picker was opened over, for positioning follow-up popups. */
    val component: Component?,
)

/** A follow-up for a picked context, such as opening it in another view. Register with [EP_NAME] or [ContextActionRegistry.register]. */
interface ContextAction {
    /** Unique; a programmatic action with the id of an extension replaces it. */
    val id: String
    val title: String
    /** The section the menu lists the action under, such as `Open`, `Explore`, `Ask`, `Edit` or `Copy`. */
    val group: String
    val order: Int get() = 0
    val icon: Icon? get() = null

    /** Whether the action applies to [request]; called on the EDT for every scope the user browses, so it must be cheap. */
    fun isAvailable(request: ContextRequest): Boolean

    fun perform(request: ContextRequest)

    companion object {
        val EP_NAME = ExtensionPointName.create<ContextAction>("dev.insight.contextAction")
    }
}

/** Menu sections in this order; other groups follow alphabetically. */
private val GROUP_ORDER = listOf("Open", "Explore", "Ask", "Edit", "Copy")

/** [actions] in menu sections: known groups first in [GROUP_ORDER], then by [ContextAction.order] and title within each. */
fun arrangeActions(actions: List<ContextAction>): List<Pair<String, List<ContextAction>>> =
    actions.groupBy { it.group }.entries
        .sortedWith(compareBy<Map.Entry<String, List<ContextAction>>> { GROUP_ORDER.indexOf(it.key).let { i -> if (i < 0) GROUP_ORDER.size else i } }
            .thenBy { it.key })
        .map { (group, members) -> group to members.sortedWith(compareBy<ContextAction> { it.order }.thenBy { it.title }) }

/** Extension and view-local context actions. */
@Service(Service.Level.APP)
class ContextActionRegistry {
    private val local = CopyOnWriteArrayList<ContextAction>()

    /** Adds a view-local [action] until [parentDisposable] is disposed. */
    fun register(action: ContextAction, parentDisposable: Disposable) {
        local += action
        Disposer.register(parentDisposable) { local.remove(action) }
    }

    /** All registered actions, later registrations replacing earlier ones with the same id. */
    fun all(): List<ContextAction> = (ContextAction.EP_NAME.extensionList + local).associateBy { it.id }.values.toList()

    /** The actions available for [request]; one that throws from [ContextAction.isAvailable] is logged and left out. */
    fun actions(request: ContextRequest): List<ContextAction> = all().filter { action ->
        try {
            action.isAvailable(request)
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            LOG.warn("Context action ${action.id} failed its availability check", e)
            false
        }
    }

    companion object {
        private val LOG = logger<ContextActionRegistry>()
        fun getInstance(): ContextActionRegistry = ApplicationManager.getApplication().getService(ContextActionRegistry::class.java)
    }
}
