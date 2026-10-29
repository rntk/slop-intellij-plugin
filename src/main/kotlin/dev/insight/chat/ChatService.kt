package dev.insight.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class ChatService(project: Project, scope: CoroutineScope) :
    ChatSession(project, scope, ProjectChatBackendFactory(project))
