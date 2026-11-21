package dev.insight.review

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** What a clickable link inside a Markdown or plain-text report points to. */
internal enum class ReportTargetKind { FILE, DIRECTORY, EXTERNAL_URL }

/**
 * One resolved link inside a rendered report.
 * [candidates] holds one project-relative path for an unambiguous target, or several ordered candidates when a bare
 * filename or partial path matches more than one file or directory in the project.
 */
internal data class ReportLink(
    val id: Int,
    val displayText: String,
    val kind: ReportTargetKind,
    val candidates: List<String> = emptyList(),
    val lines: IntRange? = null,
    val column: Int? = null,
    val contextual: Boolean = false,
    val externalUrl: String? = null,
) {
    val isAmbiguous: Boolean get() = candidates.size > 1
    val primaryPath: String? get() = candidates.firstOrNull()
}

/** One matched span `[start, end)` inside a single text segment, mapped to its [link]. */
internal data class LineLinkMatch(val start: Int, val end: Int, val link: ReportLink)

/** Parsed line range (1-based, inclusive) and optional 1-based column from a suffix such as `:L10-L20` or `:42:5`. */
internal data class ParsedLineSpec(val lines: IntRange, val column: Int? = null)

/**
 * Tracks contextual files and directories across headings, list hierarchies, and table rows so relative paths and
 * bare line references (`L42-L50`, `lines 10-20`, `at L151`) bind accurately without bleeding across unrelated entries.
 */
internal class ReportLinkContext {
    val sectionDirs: MutableSet<String> = linkedSetOf()
    val sectionFiles: MutableSet<String> = linkedSetOf()
    val recentFiles: LinkedHashSet<String> = linkedSetOf()
    val lineDirs: MutableSet<String> = linkedSetOf()
    var activeFiles: List<String> = emptyList()
        private set

    private val listStack = ArrayDeque<Pair<Int, List<String>>>()

    fun onHeading() {
        activeFiles = emptyList()
        listStack.clear()
        sectionFiles.clear()
        sectionDirs.clear()
        lineDirs.clear()
    }

    fun onRule() {
        activeFiles = emptyList()
        listStack.clear()
        lineDirs.clear()
    }

    fun onListItem(indent: Int) {
        lineDirs.clear()
        while (listStack.isNotEmpty() && listStack.last().first >= indent) {
            listStack.removeLast()
        }
        activeFiles = listStack.lastOrNull()?.second.orEmpty()
        listStack.addLast(indent to activeFiles)
    }

    fun onPlainLine() {
        lineDirs.clear()
    }

    fun onBlankLine() {
        lineDirs.clear()
        listStack.clear()
    }

    fun <T> withTableRow(block: () -> T): T {
        val savedFiles = activeFiles
        val savedLineDirs = lineDirs.toList()
        lineDirs.clear()
        return try {
            block()
        } finally {
            activeFiles = savedFiles
            lineDirs.clear()
            lineDirs.addAll(savedLineDirs)
        }
    }

    fun recordResolvedFile(candidates: List<String>, inListItem: Boolean) {
        if (candidates.isEmpty()) return
        val distinct = candidates.distinct()
        activeFiles = distinct
        if (distinct.size == 1) {
            val path = distinct.single()
            sectionFiles += path
            recentFiles.remove(path)
            recentFiles.add(path)
        }
        if (inListItem && listStack.isNotEmpty()) {
            val top = listStack.removeLast()
            listStack.addLast(top.first to distinct)
        }
    }

    fun recordResolvedDir(candidates: List<String>, inHeading: Boolean) {
        if (candidates.isEmpty()) return
        lineDirs.addAll(candidates)
        if (inHeading || candidates.size == 1) {
            sectionDirs.addAll(candidates)
        }
    }
}

/** One indexed project file or directory entry with optional [absolutePath] for root-preserving resolution. */
internal data class ReportIndexedEntry(
    val path: String,
    val absolutePath: String? = null,
    val inReviewScope: Boolean = false,
)

/**
 * In-memory index of a project's files and directories for smart report link resolution and disambiguation.
 * All paths are stored normalized with forward slashes and without leading/trailing slashes.
 */
internal class ReportProjectIndex(
    files: Collection<String> = emptyList(),
    directories: Collection<String> = emptyList(),
    projectRoot: String? = null,
    contentRoots: List<String> = emptyList(),
    reviewScopeFiles: Set<String> = emptySet(),
    val reportFileDir: String? = null,
    fileEntries: Collection<ReportIndexedEntry> = emptyList(),
    directoryEntries: Collection<ReportIndexedEntry> = emptyList(),
) {
    private data class ResolvedEntry(
        val key: String,
        val absolutePath: String?,
        val inReviewScope: Boolean,
    )

    // Keep caller root order (matching codeScopeRoots) so nested modules strip against outer roots first.
    private val normalizedRoots: List<String> = (listOfNotNull(projectRoot) + contentRoots)
        .mapNotNull { normalizeRoot(it) }
        .distinct()

    private val rootNames: Set<String> = normalizedRoots
        .mapNotNull { it.substringAfterLast('/', "").takeIf(String::isNotEmpty) }
        .toSet()

    val reviewScopeFiles: Set<String>

    private val exactFiles: Map<String, String>
    private val lowerFiles: Map<String, List<String>>
    private val filesByName: Map<String, List<String>>
    private val lowerFilesByName: Map<String, List<String>>
    private val exactAbsFiles: Map<String, String>
    private val lowerAbsFiles: Map<String, List<String>>
    private val fileAbsByKey: Map<String, String>

    private val exactDirs: Map<String, String>
    private val lowerDirs: Map<String, List<String>>
    private val dirsByName: Map<String, List<String>>
    private val lowerDirsByName: Map<String, List<String>>
    private val exactAbsDirs: Map<String, String>
    private val lowerAbsDirs: Map<String, List<String>>
    private val dirAbsByKey: Map<String, String>

    val fileCount: Int get() = exactFiles.size

    init {
        val rawFiles = mutableListOf<ReportIndexedEntry>()
        val rawDirs = mutableListOf<ReportIndexedEntry>()
        for (entry in fileEntries) {
            rawFiles += entry
        }
        for (raw in files) {
            rawFiles += ReportIndexedEntry(raw, absolutePath = null, inReviewScope = raw in reviewScopeFiles)
        }
        for (entry in rawFiles) {
            val normRel = normalizeRelPath(entry.path) ?: continue
            val normAbs = entry.absolutePath?.let(::normalizeAbsPath)
            var parentRel = normRel.substringBeforeLast('/', "")
            var parentAbs = normAbs?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            while (parentRel.isNotEmpty()) {
                rawDirs += ReportIndexedEntry(parentRel, parentAbs)
                parentRel = parentRel.substringBeforeLast('/', "")
                parentAbs = parentAbs?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            }
        }
        for (entry in directoryEntries) {
            var normRel = normalizeRelPath(entry.path) ?: continue
            var normAbs = entry.absolutePath?.let(::normalizeAbsPath)
            while (normRel.isNotEmpty()) {
                rawDirs += ReportIndexedEntry(normRel, normAbs)
                normRel = normRel.substringBeforeLast('/', "")
                normAbs = normAbs?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            }
        }
        for (raw in directories) {
            var normRel = normalizeRelPath(raw) ?: continue
            while (normRel.isNotEmpty()) {
                rawDirs += ReportIndexedEntry(normRel, absolutePath = null)
                normRel = normRel.substringBeforeLast('/', "")
            }
        }

        val resolvedFiles = disambiguateEntries(rawFiles)
        val resolvedDirsBase = disambiguateEntries(rawDirs)
        val extraRootDirs = mutableListOf<ReportIndexedEntry>()
        val coveredDirAbs = resolvedDirsBase.mapNotNullTo(HashSet()) { it.absolutePath }
        val coveredDirKeys = resolvedDirsBase.mapTo(HashSet()) { it.key }
        for (file in resolvedFiles) {
            var parentKey = file.key.substringBeforeLast('/', "")
            var parentAbs = file.absolutePath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            while (parentKey.isNotEmpty()) {
                val absAllowed = parentAbs == null || normalizedRoots.isEmpty() || isWithinNormalizedRoot(parentAbs)
                if (absAllowed && (parentAbs == null || parentAbs !in coveredDirAbs) && parentKey !in coveredDirKeys) {
                    extraRootDirs += ReportIndexedEntry(parentKey, parentAbs)
                    if (parentAbs != null) coveredDirAbs += parentAbs
                    coveredDirKeys += parentKey
                }
                parentKey = parentKey.substringBeforeLast('/', "")
                parentAbs = parentAbs?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            }
        }
        val resolvedDirs = if (extraRootDirs.isEmpty()) resolvedDirsBase else {
            resolvedDirsBase + disambiguateEntries(extraRootDirs)
        }

        val scopeSet = LinkedHashSet<String>()
        for (file in resolvedFiles) {
            if (file.inReviewScope) scopeSet += file.key
        }
        this.reviewScopeFiles = scopeSet

        val sortedFiles = resolvedFiles.map { it.key }.distinct().sortedWith(baseCandidateComparator(scopeSet))
        exactFiles = sortedFiles.associateBy { it }
        lowerFiles = sortedFiles.groupBy { it.lowercase() }
        filesByName = sortedFiles.groupBy { it.substringAfterLast('/') }
        lowerFilesByName = sortedFiles.groupBy { it.substringAfterLast('/').lowercase() }

        val fAbsByKey = LinkedHashMap<String, String>()
        val fExactAbs = LinkedHashMap<String, String>()
        val fLowerAbsList = LinkedHashMap<String, MutableList<String>>()
        for (file in resolvedFiles) {
            val abs = file.absolutePath ?: continue
            fAbsByKey.putIfAbsent(file.key, abs)
            fExactAbs.putIfAbsent(abs, file.key)
            fLowerAbsList.getOrPut(abs.lowercase()) { mutableListOf() }.let { list ->
                if (file.key !in list) list += file.key
            }
        }
        fileAbsByKey = fAbsByKey
        exactAbsFiles = fExactAbs
        lowerAbsFiles = fLowerAbsList

        val sortedDirs = resolvedDirs.map { it.key }.distinct().sortedWith(compareBy<String> { it.length }.thenBy { it })
        exactDirs = sortedDirs.associateBy { it }
        lowerDirs = sortedDirs.groupBy { it.lowercase() }
        dirsByName = sortedDirs.groupBy { it.substringAfterLast('/') }
        lowerDirsByName = sortedDirs.groupBy { it.substringAfterLast('/').lowercase() }

        val dAbsByKey = LinkedHashMap<String, String>()
        val dExactAbs = LinkedHashMap<String, String>()
        val dLowerAbsList = LinkedHashMap<String, MutableList<String>>()
        for (dir in resolvedDirs) {
            val abs = dir.absolutePath ?: continue
            dAbsByKey.putIfAbsent(dir.key, abs)
            dExactAbs.putIfAbsent(abs, dir.key)
            dLowerAbsList.getOrPut(abs.lowercase()) { mutableListOf() }.let { list ->
                if (dir.key !in list) list += dir.key
            }
        }
        dirAbsByKey = dAbsByKey
        exactAbsDirs = dExactAbs
        lowerAbsDirs = dLowerAbsList
    }

    /** Returns the captured absolute path for an indexed file [path], if known. */
    fun absolutePathFor(path: String): String? = fileAbsByKey[path]

    /** Returns the captured absolute path for an indexed directory [path], if known. */
    fun absoluteDirPathFor(path: String): String? = dirAbsByKey[path]

    /** Resolves an on-disk [absolutePath] back to its canonical indexed path key. */
    fun pathForAbsolute(absolutePath: String): String? {
        val norm = normalizeAbsPath(absolutePath) ?: return null
        return exactAbsFiles[norm] ?: lowerAbsFiles[norm.lowercase()]?.singleOrNull()
    }

    /** Resolves [rawPath] to matching project-relative file paths, ordered from most likely to least likely. */
    fun resolveFile(
        rawPath: String,
        context: ReportLinkContext,
        allowExtensionlessBare: Boolean = false,
        requireExactCaseForExtensionless: Boolean = false,
    ): List<String> {
        val cleaned = cleanCandidatePath(rawPath) ?: return emptyList()

        if (isAbsolutePath(cleaned)) {
            val (matchedLocalRoot, localHits) = resolveAbsolute(
                cleaned, exactFiles, lowerFiles, exactAbsFiles, lowerAbsFiles, fileAbsByKey,
            )
            if (localHits != null) return disambiguate(localHits, context)
            if (!matchedLocalRoot) {
                resolveForeignAbsoluteSuffix(cleaned, exactFiles, lowerFiles)?.let { return disambiguate(it, context) }
            }
            return emptyList()
        }

        val stripped = stripLeadingDotSegments(cleaned) ?: return emptyList()
        if ('/' !in cleaned) {
            val hasValidExtension = '.' in stripped &&
                stripped.substringBeforeLast('.').isNotEmpty() &&
                stripped.substringAfterLast('.').isNotEmpty() &&
                stripped.any { it in 'a'..'z' || it in 'A'..'Z' }
            if (!hasValidExtension) {
                if (!allowExtensionlessBare) return emptyList()
                if (requireExactCaseForExtensionless) {
                    return exactFiles[stripped]?.let { listOf(it) } ?: emptyList()
                }
            }
        }

        if (reportFileDir != null && !cleaned.startsWith("/")) {
            resolveRelativePath(reportFileDir, cleaned)?.let { rel ->
                lookupExactOrLower(rel, exactFiles, lowerFiles)?.let { return disambiguate(it, context) }
            }
        }

        lookupExactOrLower(stripped, exactFiles, lowerFiles)?.let { return disambiguate(it, context) }

        if (stripped.startsWith("a/") || stripped.startsWith("b/")) {
            lookupExactOrLower(stripped.drop(2), exactFiles, lowerFiles)?.let { return disambiguate(it, context) }
        }

        for (dir in context.lineDirs) {
            lookupExactOrLower("$dir/$stripped", exactFiles, lowerFiles)?.let { return disambiguate(it, context) }
            dirAbsByKey[dir]?.let { dirAbs ->
                lookupExactOrLower("$dirAbs/$stripped", exactAbsFiles, lowerAbsFiles)?.let { return disambiguate(it, context) }
            }
        }
        for (dir in context.sectionDirs) {
            lookupExactOrLower("$dir/$stripped", exactFiles, lowerFiles)?.let { return disambiguate(it, context) }
            dirAbsByKey[dir]?.let { dirAbs ->
                lookupExactOrLower("$dirAbs/$stripped", exactAbsFiles, lowerAbsFiles)?.let { return disambiguate(it, context) }
            }
        }

        if ('/' in stripped) {
            val base = stripped.substringAfterLast('/')
            val exactMatches = filesByName[base].orEmpty().filter { it.endsWith("/$stripped") }
            if (exactMatches.isNotEmpty()) return disambiguate(exactMatches, context)
            val lowerMatches = lowerFilesByName[base.lowercase()].orEmpty().filter { it.endsWith("/$stripped", ignoreCase = true) }
            if (lowerMatches.isNotEmpty()) return disambiguate(lowerMatches, context)
            return emptyList()
        }

        val exactByName = filesByName[stripped]
        if (!exactByName.isNullOrEmpty()) return disambiguate(exactByName, context)
        val lowerByName = lowerFilesByName[stripped.lowercase()]
        if (!lowerByName.isNullOrEmpty()) return disambiguate(lowerByName, context)
        return emptyList()
    }

    /** Resolves [rawPath] to matching project-relative directory paths. */
    fun resolveDirectory(
        rawPath: String,
        explicitMarkdownLink: Boolean,
        context: ReportLinkContext,
        allowBareName: Boolean = false,
    ): List<String> {
        val rawNorm = stripFileUri(rawPath).trim().replace('\\', '/')
        val hasSlash = '/' in rawNorm
        if (!explicitMarkdownLink && !allowBareName && !hasSlash) return emptyList()

        val withoutGlob = rawNorm.removeSuffix("/*").trimEnd('/')
        val cleaned = cleanCandidatePath(withoutGlob) ?: return emptyList()
        if (cleaned.isEmpty()) return emptyList()

        if (isAbsolutePath(cleaned)) {
            val (matchedLocalRoot, localHits) = resolveAbsolute(
                cleaned, exactDirs, lowerDirs, exactAbsDirs, lowerAbsDirs, dirAbsByKey,
            )
            if (localHits != null) return disambiguate(localHits, context)
            if (!matchedLocalRoot) {
                resolveForeignAbsoluteSuffix(cleaned, exactDirs, lowerDirs)?.let { return disambiguate(it, context) }
            }
            return emptyList()
        }

        if (reportFileDir != null) {
            resolveRelativePath(reportFileDir, cleaned)?.let { rel ->
                if (rel.isNotEmpty()) lookupExactOrLower(rel, exactDirs, lowerDirs)?.let { return disambiguate(it, context) }
            }
        }

        val stripped = stripLeadingDotSegments(cleaned) ?: return emptyList()
        if (stripped.isEmpty()) return emptyList()

        lookupExactOrLower(stripped, exactDirs, lowerDirs)?.let { return disambiguate(it, context) }

        for (dir in context.lineDirs) {
            lookupExactOrLower("$dir/$stripped", exactDirs, lowerDirs)?.let { return disambiguate(it, context) }
            dirAbsByKey[dir]?.let { dirAbs ->
                lookupExactOrLower("$dirAbs/$stripped", exactAbsDirs, lowerAbsDirs)?.let { return disambiguate(it, context) }
            }
        }
        for (dir in context.sectionDirs) {
            lookupExactOrLower("$dir/$stripped", exactDirs, lowerDirs)?.let { return disambiguate(it, context) }
            dirAbsByKey[dir]?.let { dirAbs ->
                lookupExactOrLower("$dirAbs/$stripped", exactAbsDirs, lowerAbsDirs)?.let { return disambiguate(it, context) }
            }
        }

        val base = stripped.substringAfterLast('/')
        val suffix = if ('/' in stripped) "/$stripped" else "/$base"
        val exactMatches = dirsByName[base].orEmpty().filter { it == stripped || it.endsWith(suffix) }
        if (exactMatches.isNotEmpty()) return disambiguate(exactMatches, context)
        val lowerMatches = lowerDirsByName[base.lowercase()].orEmpty().filter { it.equals(stripped, ignoreCase = true) || it.endsWith(suffix, ignoreCase = true) }
        if (lowerMatches.isNotEmpty()) return disambiguate(lowerMatches, context)
        return emptyList()
    }

    private fun resolveAbsolute(
        path: String,
        exact: Map<String, String>,
        lower: Map<String, List<String>>,
        exactAbs: Map<String, String>,
        lowerAbs: Map<String, List<String>>,
        keyToAbs: Map<String, String>,
    ): Pair<Boolean, List<String>?> {
        val norm = path.trimEnd('/').ifEmpty { "/" }
        exactAbs[norm]?.let { return true to listOf(it) }
        lowerAbs[norm.lowercase()]?.takeIf { it.isNotEmpty() }?.let { return true to it }

        for (root in normalizedRoots) {
            val prefix = if (root == "/") "/" else "$root/"
            val winDrive = isWindowsDrive(root)
            if (norm.startsWith(prefix, ignoreCase = winDrive)) {
                val rel = norm.substring(prefix.length).trim('/')
                if (rel.isNotEmpty()) {
                    val hits = lookupExactOrLower(rel, exact, lower)
                        ?.filter { key ->
                            val keyAbs = keyToAbs[key]
                            keyAbs == null || keyAbs.equals(norm, ignoreCase = winDrive)
                        }
                        ?.takeIf { it.isNotEmpty() }
                    if (hits != null) return true to hits
                }
                return true to null
            }
        }
        return false to null
    }

    private fun resolveForeignAbsoluteSuffix(
        path: String,
        exact: Map<String, String>,
        lower: Map<String, List<String>>,
    ): List<String>? {
        val segments = path.split('/').filter { it.isNotEmpty() && !(it.length == 2 && it[1] == ':' && it[0].isLetter()) }
        if (segments.isEmpty()) return null
        for (start in segments.indices) {
            val count = segments.size - start
            val allowSingle = count == 1 && start > 0 && segments[start - 1] in rootNames
            if (count >= 2 || allowSingle) {
                val suffix = segments.subList(start, segments.size).joinToString("/")
                lookupExactOrLower(suffix, exact, lower)?.let { return it }
            }
        }
        return null
    }

    private fun lookupExactOrLower(
        key: String,
        exact: Map<String, String>,
        lower: Map<String, List<String>>,
    ): List<String>? {
        exact[key]?.let { return listOf(it) }
        return lower[key.lowercase()]?.takeIf { it.isNotEmpty() }
    }

    private fun disambiguate(matches: List<String>, context: ReportLinkContext): List<String> {
        if (matches.size <= 1) return matches
        var current = matches.distinct()
        fun narrow(predicate: (String) -> Boolean) {
            val subset = current.filter(predicate)
            if (subset.isNotEmpty()) current = subset
        }
        if (context.lineDirs.isNotEmpty()) narrow { path -> context.lineDirs.any { dir -> matchesDirContext(path, dir) } }
        if (current.size > 1 && context.sectionDirs.isNotEmpty()) narrow { path -> context.sectionDirs.any { dir -> matchesDirContext(path, dir) } }
        if (current.size > 1 && context.sectionFiles.isNotEmpty()) narrow { path -> path in context.sectionFiles }
        if (current.size > 1 && context.recentFiles.isNotEmpty()) narrow { path -> path in context.recentFiles }
        if (current.size > 1 && reviewScopeFiles.isNotEmpty()) narrow { path -> path in reviewScopeFiles }
        return current.sortedWith(baseCandidateComparator(reviewScopeFiles))
    }

    private fun matchesDirContext(key: String, dirKey: String): Boolean {
        if (isUnderDir(key, dirKey)) return true
        val dirAbs = dirAbsByKey[dirKey] ?: return false
        val targetAbs = fileAbsByKey[key] ?: dirAbsByKey[key] ?: return false
        return isUnderDir(targetAbs, dirAbs)
    }

    private fun isWithinNormalizedRoot(absPath: String): Boolean = normalizedRoots.any { root ->
        if (root == "/") absPath.startsWith("/")
        else absPath.equals(root, ignoreCase = isWindowsDrive(root)) ||
            absPath.startsWith("$root/", ignoreCase = isWindowsDrive(root))
    }

    private fun disambiguateEntries(entries: List<ReportIndexedEntry>): List<ResolvedEntry> {
        val grouped = LinkedHashMap<String, LinkedHashMap<String?, Boolean>>()
        for (entry in entries) {
            val rel = normalizeRelPath(entry.path) ?: continue
            val abs = entry.absolutePath?.let(::normalizeAbsPath)
            val byAbs = grouped.getOrPut(rel) { LinkedHashMap() }
            byAbs[abs] = (byAbs[abs] ?: false) || entry.inReviewScope
        }
        for (byAbs in grouped.values) {
            if (null in byAbs && byAbs.size >= 2) {
                val nullScope = byAbs.remove(null) ?: false
                if (nullScope) {
                    for (absKey in byAbs.keys.toList()) {
                        byAbs[absKey] = true
                    }
                }
            }
        }

        val resolved = mutableListOf<ResolvedEntry>()
        val occupiedKeys = LinkedHashSet<String>()
        for ((rel, byAbs) in grouped) {
            if (byAbs.size == 1) {
                val (abs, inScope) = byAbs.entries.single()
                occupiedKeys += rel
                resolved += ResolvedEntry(rel, abs, inScope)
            }
        }
        for ((rel, byAbs) in grouped) {
            if (byAbs.size <= 1) continue
            val relSegCount = rel.split('/').size
            val items = byAbs.entries.map { (abs, inScope) ->
                val nonNullAbs = abs.orEmpty()
                val absSegs = nonNullAbs.trimStart('/').split('/').filter { it.isNotEmpty() }
                val rootSegs = if (absSegs.size > relSegCount) absSegs.dropLast(relSegCount) else absSegs.dropLast(1)
                Triple(abs, inScope, rootSegs)
            }
            val maxPrefix = items.maxOfOrNull { it.third.size } ?: 0
            var chosen: List<String>? = null
            for (k in 1..maxPrefix) {
                val candidates = items.map { (_, _, rootSegs) ->
                    val prefix = if (k >= rootSegs.size) rootSegs else rootSegs.subList(rootSegs.size - k, rootSegs.size)
                    if (prefix.isEmpty()) rel else "${prefix.joinToString("/")}/$rel"
                }
                if (candidates.distinct().size == candidates.size && candidates.none { it in occupiedKeys }) {
                    chosen = candidates
                    break
                }
            }
            val finalKeys = chosen ?: items.mapIndexed { idx, (abs, _, _) ->
                val fallback = abs?.trimStart('/')?.takeIf { it.isNotEmpty() && it !in occupiedKeys }
                fallback ?: "root-${idx + 1}/$rel"
            }
            items.forEachIndexed { idx, (abs, inScope, _) ->
                val key = finalKeys[idx]
                occupiedKeys += key
                resolved += ResolvedEntry(key, abs, inScope)
            }
        }
        return resolved
    }

    companion object {
        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:(/|$)")
        private val PERCENT_HEX = Regex("%([0-9A-Fa-f]{2})")
        private val INVALID_PERCENT = Regex("%(?![0-9A-Fa-f]{2})")

        private fun isWindowsDrive(path: String): Boolean = WINDOWS_DRIVE.containsMatchIn(path)
        private fun isAbsolutePath(path: String): Boolean = path.startsWith("/") || isWindowsDrive(path)

        private fun isUnderDir(path: String, dir: String): Boolean =
            path == dir || path.startsWith("$dir/")

        private fun baseCandidateComparator(scopeFiles: Set<String>): Comparator<String> =
            compareBy<String>({ it !in scopeFiles }, { it.length }, { it })

        private fun normalizeRoot(root: String): String? {
            val cleaned = root.trim().replace('\\', '/').replace(Regex("/+"), "/")
            if (cleaned.isEmpty()) return null
            return if (cleaned == "/") "/" else cleaned.trimEnd('/')
        }

        internal fun normalizeAbsPath(raw: String): String? = normalizeRoot(raw)

        private fun normalizeRelPath(raw: String): String? {
            val cleaned = raw.trim().replace('\\', '/').replace(Regex("/+"), "/").removePrefix("./").trim('/')
            return cleaned.takeIf { it.isNotEmpty() && it != "." }
        }

        /** Decodes UTF-8 `%XX` sequences while preserving literal `+` and malformed `%` sequences. */
        fun decodePercentEncoded(raw: String): String {
            if (!PERCENT_HEX.containsMatchIn(raw)) return raw
            val safe = raw.replace("+", "%2B").replace(INVALID_PERCENT, "%25")
            return runCatching { URLDecoder.decode(safe, StandardCharsets.UTF_8) }.getOrDefault(raw)
        }

        /** Strips `file://` scheme (including `file:///C:/...` -> `C:/...`) and percent-decodes valid UTF-8 sequences. */
        fun stripFileUri(raw: String): String {
            if (!raw.startsWith("file://", ignoreCase = true)) return raw
            var rest = raw.substring("file://".length)
            if (rest.startsWith("localhost/", ignoreCase = true)) {
                rest = rest.substring("localhost".length)
            }
            if (rest.length >= 4 && rest[0] == '/' && rest[1].isLetter() && rest[2] == ':' && rest[3] == '/') {
                rest = rest.substring(1)
            }
            return decodePercentEncoded(rest)
        }

        /** Resolves [relative] against [baseDir] (both forward-slash project-relative), returning null if `..` escapes root. */
        fun resolveRelativePath(baseDir: String, relative: String): String? {
            val stack = ArrayDeque<String>()
            if (baseDir.isNotEmpty()) {
                for (part in baseDir.split('/')) {
                    if (part.isNotEmpty() && part != ".") stack.addLast(part)
                }
            }
            for (part in relative.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                    else -> stack.addLast(part)
                }
            }
            return stack.joinToString("/")
        }

        private fun cleanCandidatePath(raw: String): String? {
            var s = stripFileUri(raw.trim()).replace('\\', '/').replace(Regex("/+"), "/")
            if (s.isEmpty() || s == "." || s == "..") return null
            if (!isAbsolutePath(s) && s.startsWith("./")) s = s.removePrefix("./")
            return s.trimEnd('/').takeIf { it.isNotEmpty() }
        }

        private fun stripLeadingDotSegments(path: String): String? {
            var s = path
            while (s.startsWith("./") || s.startsWith("../")) {
                s = if (s.startsWith("./")) s.removePrefix("./") else s.removePrefix("../")
            }
            return s.trim('/').takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Collects [ReportLink]s while scanning text segments and markdown link targets against a [ReportProjectIndex].
 */
internal class ReportLinkCollector(
    private val index: ReportProjectIndex,
    val context: ReportLinkContext = ReportLinkContext(),
) {
    private val collected = mutableListOf<ReportLink>()
    val links: List<ReportLink> get() = collected

    private fun register(linkBuilder: (Int) -> ReportLink): ReportLink {
        val link = linkBuilder(collected.size)
        collected += link
        return link
    }

    /** Resolves an explicit Markdown `[label](target)` link, returning null if it points to neither a project entry nor HTTP(S). */
    fun resolveMarkdownTarget(
        target: String,
        label: String,
        inHeading: Boolean = false,
        inListItem: Boolean = false,
    ): ReportLink? {
        val trimmed = target.trim().removeSurrounding("<", ">")
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("mailto:", ignoreCase = true)) return null
        if (HTTP_URL_START.containsMatchIn(trimmed)) {
            return register { id ->
                ReportLink(id, label, ReportTargetKind.EXTERNAL_URL, externalUrl = trimmed)
            }
        }
        val (pathPart, spec) = splitTargetAndLineSpec(trimmed)
        val parsedSpec = spec?.let(::parseLineSpec)
        val isFileUri = pathPart.startsWith("file://", ignoreCase = true)
        val strippedPath = ReportProjectIndex.stripFileUri(pathPart)
        val decodedPath = if (isFileUri) strippedPath else ReportProjectIndex.decodePercentEncoded(strippedPath)
        val pathCandidates = if (decodedPath != strippedPath) listOf(decodedPath, strippedPath) else listOf(decodedPath)
        val prefersDir = decodedPath.endsWith("/") || decodedPath.endsWith("/*") ||
            strippedPath.endsWith("/") || strippedPath.endsWith("/*")
        if (prefersDir && parsedSpec == null) {
            for (candidate in pathCandidates) {
                val dirs = index.resolveDirectory(candidate, explicitMarkdownLink = true, context)
                if (dirs.isNotEmpty()) {
                    context.recordResolvedDir(dirs, inHeading)
                    return register { id -> ReportLink(id, label, ReportTargetKind.DIRECTORY, dirs) }
                }
            }
        }
        for (candidate in pathCandidates) {
            val files = index.resolveFile(candidate, context, allowExtensionlessBare = true)
            if (files.isNotEmpty()) {
                context.recordResolvedFile(files, inListItem)
                return register { id ->
                    ReportLink(id, label, ReportTargetKind.FILE, files, parsedSpec?.lines, parsedSpec?.column)
                }
            }
        }
        if (parsedSpec == null) {
            for (candidate in pathCandidates) {
                val dirs = index.resolveDirectory(candidate, explicitMarkdownLink = true, context)
                if (dirs.isNotEmpty()) {
                    context.recordResolvedDir(dirs, inHeading)
                    return register { id -> ReportLink(id, label, ReportTargetKind.DIRECTORY, dirs) }
                }
            }
        }
        return null
    }

    /**
     * Scans a plain or backticked text [segment] left-to-right for external URLs, project files/directories (with optional
     * line/column specs and comma-separated continuation ranges), and contextual `L...` / `line ...` references.
     */
    fun scanSegment(
        segment: String,
        inBackticks: Boolean,
        allowContextualLines: Boolean,
        inHeading: Boolean = false,
        inListItem: Boolean = false,
    ): List<LineLinkMatch> {
        if (segment.isEmpty()) return emptyList()
        val matches = mutableListOf<LineLinkMatch>()
        var cursor = 0

        while (cursor < segment.length) {
            if (!inBackticks) {
                val urlMatch = HTTP_URL_PREFIX.matchAt(segment, cursor)
                if (urlMatch != null) {
                    val trimmedUrl = trimTrailingPunctuation(urlMatch.value)
                    val end = cursor + trimmedUrl.length
                    val link = register { id ->
                        ReportLink(id, trimmedUrl, ReportTargetKind.EXTERNAL_URL, externalUrl = trimmedUrl)
                    }
                    matches += LineLinkMatch(cursor, end, link)
                    cursor = end
                    continue
                }
            }

            val tokenMatch = tryMatchPathToken(segment, cursor)
            if (tokenMatch != null) {
                val resolved = resolvePathCandidate(
                    segment,
                    tokenMatch.start,
                    tokenMatch.rawToken,
                    tokenMatch.end,
                    inBackticks,
                    inHeading,
                    inListItem,
                )
                if (resolved.isNotEmpty()) {
                    matches.addAll(resolved)
                    cursor = resolved.last().end
                    continue
                }
                val nextPos = if (allowContextualLines) tryEmitContextualLine(segment, cursor, matches) else null
                cursor = nextPos ?: tokenMatch.end
                continue
            }

            val nextPos = if (allowContextualLines) tryEmitContextualLine(segment, cursor, matches) else null
            cursor = nextPos ?: (cursor + 1)
        }

        return matches
    }

    private data class RawPathToken(val start: Int, val end: Int, val rawToken: String)

    private fun tryMatchPathToken(segment: String, pos: Int): RawPathToken? {
        if (pos > 0 && segment[pos - 1] in PATH_LEFT_FORBIDDEN) {
            // Allow `./` or `../` only if not glued onto a word character.
            return null
        }
        val fileUri = FILE_URI_TOKEN.matchAt(segment, pos)
        if (fileUri != null) {
            return RawPathToken(pos, fileUri.range.last + 1, fileUri.value)
        }
        val m = PATH_TOKEN.matchAt(segment, pos) ?: return null
        val token = m.value
        if (token.isEmpty()) return null
        val first = token[0]
        if (!first.isLetterOrDigit() && first != '.' && first != '/' && first != '\\' && first != '~') return null
        return RawPathToken(pos, m.range.last + 1, token)
    }

    private fun resolvePathCandidate(
        segment: String,
        tokenStart: Int,
        rawToken: String,
        rawTokenEnd: Int,
        inBackticks: Boolean,
        inHeading: Boolean,
        inListItem: Boolean,
    ): List<LineLinkMatch> {
        // First inspect an attached line spec right after rawToken (before trimming trailing punctuation).
        val attached = matchAttachedLineSpec(segment, rawTokenEnd)
        val tokenToResolve: String
        val pathEnd: Int
        val parsedSpec: ParsedLineSpec?
        if (attached != null) {
            tokenToResolve = rawToken
            pathEnd = attached.end
            parsedSpec = attached.spec
        } else {
            val trimmed = trimTrailingPunctuation(rawToken)
            if (trimmed.isEmpty()) return emptyList()
            val trimmedEnd = tokenStart + trimmed.length
            // Re-check tight-paren or colon line spec if trimming exposed one (rare), or keep null.
            val postTrimSpec = if (trimmedEnd < rawTokenEnd) null else matchAttachedLineSpec(segment, trimmedEnd)
            tokenToResolve = trimmed
            pathEnd = postTrimSpec?.end ?: trimmedEnd
            parsedSpec = postTrimSpec?.spec
        }

        // Reject if immediately followed by an identifier character or call parens (unless a line spec consumed it).
        if (parsedSpec == null && pathEnd < segment.length &&
            (segment[pathEnd].isLetterOrDigit() || segment[pathEnd] == '_' || segment[pathEnd] == '(')
        ) {
            return emptyList()
        }

        val prefersDir = tokenToResolve.endsWith("/") || tokenToResolve.endsWith("\\") || tokenToResolve.endsWith("/*")
        if (prefersDir && parsedSpec == null) {
            val dirs = index.resolveDirectory(tokenToResolve, explicitMarkdownLink = false, context, allowBareName = inHeading && inBackticks)
            if (dirs.isNotEmpty()) {
                context.recordResolvedDir(dirs, inHeading)
                val link = register { id -> ReportLink(id, segment.substring(tokenStart, pathEnd), ReportTargetKind.DIRECTORY, dirs) }
                return listOf(LineLinkMatch(tokenStart, pathEnd, link))
            }
            return emptyList()
        }

        val files = index.resolveFile(
            tokenToResolve,
            context,
            allowExtensionlessBare = inBackticks && '/' !in tokenToResolve,
            requireExactCaseForExtensionless = true,
        )
        if (files.isNotEmpty()) {
            context.recordResolvedFile(files, inListItem)
            val results = mutableListOf<LineLinkMatch>()
            val primaryDisplay = segment.substring(tokenStart, pathEnd)
            val primaryLink = register { id ->
                ReportLink(id, primaryDisplay, ReportTargetKind.FILE, files, parsedSpec?.lines, parsedSpec?.column)
            }
            results += LineLinkMatch(tokenStart, pathEnd, primaryLink)

            if (parsedSpec != null) {
                var pos = pathEnd
                while (true) {
                    val cont = CONTINUATION_RANGE.matchAt(segment, pos) ?: break
                    val rangeGroup = cont.groups[1] ?: break
                    val startLine = cont.groupValues[2].toIntOrNull() ?: break
                    val endLine = cont.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: startLine
                    if (startLine !in 1..MAX_LINE_NUMBER || endLine !in 1..MAX_LINE_NUMBER) break
                    val range = minOf(startLine, endLine)..maxOf(startLine, endLine)
                    val spanStart = rangeGroup.range.first
                    val spanEnd = cont.range.last + 1
                    val link = register { id ->
                        ReportLink(id, segment.substring(spanStart, spanEnd), ReportTargetKind.FILE, files, range)
                    }
                    results += LineLinkMatch(spanStart, spanEnd, link)
                    pos = spanEnd
                }
            }
            return results
        }

        if (parsedSpec == null) {
            val dirs = index.resolveDirectory(tokenToResolve, explicitMarkdownLink = false, context, allowBareName = inHeading && inBackticks)
            if (dirs.isNotEmpty()) {
                context.recordResolvedDir(dirs, inHeading)
                val link = register { id -> ReportLink(id, segment.substring(tokenStart, pathEnd), ReportTargetKind.DIRECTORY, dirs) }
                return listOf(LineLinkMatch(tokenStart, pathEnd, link))
            }
        }
        return emptyList()
    }

    private fun tryEmitContextualLine(segment: String, pos: Int, matches: MutableList<LineLinkMatch>): Int? {
        val active = context.activeFiles
        if (active.isEmpty()) return null
        val ctxMatch = tryMatchContextualLine(segment, pos) ?: return null
        val link = register { id ->
            ReportLink(
                id = id,
                displayText = ctxMatch.displayText,
                kind = ReportTargetKind.FILE,
                candidates = active,
                lines = ctxMatch.lines,
                contextual = true,
            )
        }
        matches += LineLinkMatch(ctxMatch.start, ctxMatch.end, link)
        return ctxMatch.end
    }

    private data class AttachedSpecMatch(val end: Int, val spec: ParsedLineSpec)

    private fun matchAttachedLineSpec(segment: String, pos: Int): AttachedSpecMatch? {
        if (pos >= segment.length) return null
        val c = segment[pos]
        if (c == ':' || c == '#') {
            val m = COLON_OR_HASH_SPEC.matchAt(segment, pos) ?: return null
            val parsed = parseLineSpec(m.value) ?: return null
            return AttachedSpecMatch(m.range.last + 1, parsed)
        }
        if (c == '(') {
            val m = PAREN_SPEC.matchAt(segment, pos) ?: return null
            val parsed = parseLineSpec(m.value) ?: return null
            return AttachedSpecMatch(m.range.last + 1, parsed)
        }
        return null
    }

    private data class ContextualMatch(val start: Int, val end: Int, val displayText: String, val lines: IntRange)

    private fun tryMatchContextualLine(segment: String, pos: Int): ContextualMatch? {
        if (pos > 0 && segment[pos - 1] in CONTEXTUAL_LEFT_FORBIDDEN) return null
        val lMatch = CONTEXTUAL_L_REF.matchAt(segment, pos)
        if (lMatch != null) {
            val startLine = lMatch.groupValues[1].toIntOrNull() ?: return null
            val endLine = lMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: startLine
            if (startLine !in 1..MAX_LINE_NUMBER || endLine !in 1..MAX_LINE_NUMBER) return null
            val end = lMatch.range.last + 1
            return ContextualMatch(pos, end, lMatch.value, minOf(startLine, endLine)..maxOf(startLine, endLine))
        }
        if (pos > 0 && (segment[pos - 1].isLetterOrDigit() || segment[pos - 1] == '_')) return null
        val wordMatch = CONTEXTUAL_WORD_REF.matchAt(segment, pos)
        if (wordMatch != null) {
            val startLine = wordMatch.groupValues[1].toIntOrNull() ?: return null
            val endLine = wordMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: startLine
            if (startLine !in 1..MAX_LINE_NUMBER || endLine !in 1..MAX_LINE_NUMBER) return null
            val end = wordMatch.range.last + 1
            return ContextualMatch(pos, end, wordMatch.value, minOf(startLine, endLine)..maxOf(startLine, endLine))
        }
        return null
    }

    companion object {
        private const val MAX_LINE_NUMBER = 1_000_000
        private val PATH_LEFT_FORBIDDEN = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '$', '@')).toSet()
        private val CONTEXTUAL_LEFT_FORBIDDEN = (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', ':', '#', '/')).toSet()
        private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', '\'', '"')

        private val HTTP_URL_START = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val HTTP_URL_PREFIX = Regex("https?://[^\\s<>()\\[\\]\"'`]+", RegexOption.IGNORE_CASE)
        private val FILE_URI_TOKEN = Regex("file://(?:/+[A-Za-z]:)?[^\\s<>()\\[\\]\"'`:#]+", RegexOption.IGNORE_CASE)
        private val PATH_TOKEN = Regex("(?:[A-Za-z]:[/\\\\])?[A-Za-z0-9_.\\-/\\\\~]+")
        private val COLON_OR_HASH_SPEC = Regex(
            "(?::L?\\d{1,6}(?::\\d{1,6})?(?:\\s*[-–]\\s*L?\\d{1,6}(?::\\d{1,6})?)?|#L\\d{1,6}(?:C\\d{1,6})?(?:-L?\\d{1,6}(?:C\\d{1,6})?)?)(?![A-Za-z0-9_])",
        )
        private val PAREN_SPEC = Regex("\\(\\d{1,6}(?:,\\s*\\d{1,6}|\\s*[-–]\\s*\\d{1,6})?\\)(?![A-Za-z0-9_])")
        private val CONTINUATION_RANGE = Regex(",\\s*(L?(\\d{1,6})(?:\\s*[-–]\\s*L?(\\d{1,6}))?)(?![A-Za-z0-9_])")

        private val CONTEXTUAL_L_REF = Regex("L(\\d{1,6})(?:\\s*[-–]\\s*L?(\\d{1,6}))?(?![A-Za-z0-9_])")
        private val CONTEXTUAL_WORD_REF = Regex("[Ll]ines?\\s+(\\d{1,6})(?:\\s*(?:[-–]|to|through)\\s*(\\d{1,6}))?(?![A-Za-z0-9_])")

        private val SPEC_COLON_OR_HASH = Regex(
            "^[:#]L?(\\d{1,6})(?:[:C](\\d{1,6}))?(?:\\s*[-–]\\s*L?(\\d{1,6})(?:[:C](\\d{1,6}))?)?$",
        )
        private val SPEC_PAREN = Regex("^\\((\\d{1,6})(?:,\\s*(\\d{1,6})|\\s*[-–]\\s*(\\d{1,6}))?\\)$")
        private val TARGET_TRAILING_SPEC = Regex("(#L\\d{1,6}(?:C\\d{1,6})?(?:-L?\\d{1,6}(?:C\\d{1,6})?)?|:L?\\d{1,6}(?::\\d{1,6})?(?:-L?\\d{1,6}(?::\\d{1,6})?)?)$")

        /** Parses line specifications such as `:L256-L337`, `:42:10`, `#L42-L58`, or `(42, 10)`. */
        fun parseLineSpec(raw: String): ParsedLineSpec? {
            val s = raw.trim()
            SPEC_COLON_OR_HASH.matchEntire(s)?.let { m ->
                val l1 = m.groupValues[1].toIntOrNull() ?: return null
                val c1 = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                val l2 = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: l1
                if (l1 !in 1..MAX_LINE_NUMBER || l2 !in 1..MAX_LINE_NUMBER) return null
                return ParsedLineSpec(minOf(l1, l2)..maxOf(l1, l2), c1?.takeIf { it in 1..MAX_LINE_NUMBER })
            }
            SPEC_PAREN.matchEntire(s)?.let { m ->
                val l1 = m.groupValues[1].toIntOrNull() ?: return null
                val col = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                val l2 = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: l1
                if (l1 !in 1..MAX_LINE_NUMBER || l2 !in 1..MAX_LINE_NUMBER) return null
                return ParsedLineSpec(minOf(l1, l2)..maxOf(l1, l2), col?.takeIf { it in 1..MAX_LINE_NUMBER })
            }
            return null
        }

        private fun splitTargetAndLineSpec(target: String): Pair<String, String?> {
            val m = TARGET_TRAILING_SPEC.find(target) ?: return target to null
            val pathPart = target.substring(0, m.range.first)
            if (pathPart.isEmpty()) return target to null
            return pathPart to m.value
        }

        private fun trimTrailingPunctuation(token: String): String {
            var end = token.length
            while (end > 0 && token[end - 1] in TRAILING_PUNCTUATION) {
                end--
            }
            return token.substring(0, end)
        }
    }
}
