package dev.insight.review

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** Evidence keyed review marks in project workspace state. One operation changes only its targeted keys. */
@Service(Service.Level.PROJECT)
@State(name = "InsightReviewedMarks", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewedMarkStore : PersistentStateComponent<ReviewedMarkStore.SavedState> {
    class Scope {
        var slot: String = ""
        var keys: MutableList<String> = mutableListOf()
    }

    class SavedState {
        var scopes: MutableList<Scope> = mutableListOf()
    }

    private val scopes = HashMap<String, MutableSet<String>>()

    fun keys(slot: String): Set<String> = keys(slot, null)

    @Synchronized
    internal fun keys(slot: String, legacyKeys: (() -> Set<String>)?): Set<String> {
        if (slot.isEmpty()) return emptySet()
        val key = reviewFingerprint(slot)
        scopes[key]?.let { return it.toSet() }
        return legacyKeys?.invoke().orEmpty().also {
            if (it.isNotEmpty()) scopes[key] = it.toMutableSet()
        }
    }

    @Synchronized
    fun set(slot: String, keys: List<String>, reviewed: Boolean) {
        if (slot.isEmpty() || keys.isEmpty()) return
        val key = reviewFingerprint(slot)
        val saved = scopes.getOrPut(key) { LinkedHashSet() }
        // Keep empty scopes so a cleared mark cannot return from the legacy cache.
        if (reviewed) saved.addAll(keys) else saved.removeAll(keys.toSet())
    }

    @Synchronized
    override fun getState(): SavedState = SavedState().apply {
        scopes = this@ReviewedMarkStore.scopes.entries.sortedBy { it.key }.map { (slot, keys) ->
            Scope().apply { this.slot = slot; this.keys = keys.sorted().toMutableList() }
        }.toMutableList()
    }

    @Synchronized
    override fun loadState(state: SavedState) {
        scopes.clear()
        state.scopes.forEach { scope ->
            if (scope.slot.isNotEmpty()) scopes.getOrPut(scope.slot) { LinkedHashSet() }.addAll(scope.keys)
        }
    }
}
