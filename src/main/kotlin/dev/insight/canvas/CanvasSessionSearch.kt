package dev.insight.canvas

import com.intellij.psi.codeStyle.NameUtil

/** Match only the root file's name, keeping the store's newest-first order. */
internal fun filterCanvasSessions(entries: List<CanvasSessionEntry>, query: String): List<CanvasSessionEntry> {
    val pattern = query.trim()
    if (pattern.isEmpty()) return entries
    val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    return entries.filter { matcher.matches(it.rootPath.replace('\\', '/').substringAfterLast('/')) }
}
