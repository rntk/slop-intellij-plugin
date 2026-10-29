package dev.insight.chat

/** A chart session; implementations marshal chart reads and changes onto the EDT. */
interface ChartChatContext {
    suspend fun snapshot(): String
    /** Whether snapshots contain a fixed scoped_context anchor. */
    val scoped: Boolean get() = false

    /** Called on the EDT when an assistant reply starts; its first chart change records a restore point. */
    fun beginReply() {}
    /** Whether the chart changes of the latest reply can be undone (EDT). */
    val restorable: Boolean get() = false
    /** Restores the chart to before the latest reply's first change (EDT). */
    fun restore(): Boolean = false
}

/** UI references are resolved and opened on the EDT. */
interface ChartReferences {
    /** The graph revision that [[node:ID]] references in a finished reply belong to. */
    val revision: Int
    /** The active story ID that [[beat:BEAT]] references belong to, or null when no story is open. */
    val storyId: Int?
    fun referenceName(revision: Int, node: Int): String?
    fun openReference(revision: Int, node: Int): String?
    fun beatTitle(revision: Int, beat: Int, storyId: Int?): String?
    fun openBeat(revision: Int, beat: Int, storyId: Int?): String?
    /** Transiently emphasises the referenced function; null clears. */
    fun hoverReference(revision: Int, node: Int?)
}
