package dev.insight.prompts

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "InsightPromptConstructor", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class PromptConstructorState : SimplePersistentStateComponent<PromptConstructorState.State>(State()) {
    class State : BaseState() {
        var libraryPath by string("")
        var draft by string("")
    }
}
