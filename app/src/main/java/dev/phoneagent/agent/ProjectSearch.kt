package dev.phoneagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_SEARCH_FILES = 72
private const val MAX_SEARCH_DIRECTORIES = 32
private const val MAX_SEARCH_QUEUED = 260
private const val MAX_SEARCH_CHARS = 800_000
private const val MAX_SEARCH_MATCHES = 40

private data class SearchEntry(val path: String, val directory: Boolean)

internal suspend fun searchProject(project: ProjectTools, path: String, query: String): String {
    val root = safePath(path, allowRoot = true)
    require(query.isNotBlank() && query.length <= 256 && query.none(Char::isISOControl)) {
        "Enter a single-line literal query of up to 256 characters."
    }

    val queue = ArrayDeque<SearchEntry>()
    queue.add(SearchEntry(root, directory = true))
    val matches = mutableListOf<String>()
    var directories = 0
    var fileAttempts = 0
    var textFiles = 0
    var scannedChars = 0
    var skipped = 0
    var reachedLimit = false

    while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        if (matches.size >= MAX_SEARCH_MATCHES || scannedChars >= MAX_SEARCH_CHARS) {
            reachedLimit = true
            break
        }
        val entry = queue.removeFirst()
        if (entry.directory) {
            if (directories >= MAX_SEARCH_DIRECTORIES) {
                reachedLimit = true
                continue
            }
            directories++
            val listing = if (entry.path == root) project.list(entry.path) else attemptRead { project.list(entry.path) }
            if (listing == null) {
                skipped++
                continue
            }
            for (name in listing.lineSequence()) {
                if (name.isBlank()) continue
                val directory = name.endsWith('/')
                val child = name.removeSuffix("/")
                if ('/' in child) {
                    skipped++
                    continue
                }
                val relative = if (entry.path == ".") child else "${entry.path}/$child"
                val checkedPath = runCatching { safePath(relative) }.getOrNull()
                if (checkedPath == null) {
                    skipped++
                    continue
                }
                if (queue.size >= MAX_SEARCH_QUEUED) {
                    reachedLimit = true
                    break
                }
                queue.add(SearchEntry(checkedPath, directory))
            }
        } else {
            if (fileAttempts >= MAX_SEARCH_FILES) {
                reachedLimit = true
                break
            }
            fileAttempts++
            val content = attemptRead { project.read(entry.path) }
            if (content == null) {
                skipped++
                continue
            }
            val remaining = MAX_SEARCH_CHARS - scannedChars
            val searched = content.take(remaining)
            if (searched.length < content.length) reachedLimit = true
            textFiles++
            scannedChars += searched.length
            for ((index, line) in searched.lineSequence().withIndex()) {
                val match = line.indexOf(query)
                if (match < 0) continue
                val start = (match - 60).coerceAtLeast(0)
                val end = (match + query.length + 100).coerceAtMost(line.length)
                val before = if (start == 0) "" else "…"
                val after = if (end == line.length) "" else "…"
                matches.add("${entry.path}:${index + 1}: $before${line.substring(start, end)}$after")
                if (matches.size >= MAX_SEARCH_MATCHES) {
                    reachedLimit = true
                    break
                }
            }
        }
    }
    return buildString {
        appendLine(if (matches.isEmpty()) "No matching lines in $textFiles text files." else
            "Found ${matches.size} matching lines in $textFiles text files.")
        if (skipped > 0) appendLine("Skipped $skipped unreadable files or directory entries.")
        if (reachedLimit) appendLine("Search reached its limit. Narrow the directory or query for more results.")
        append(matches.joinToString("\n"))
    }.trimEnd()
}

private suspend fun attemptRead(read: suspend () -> String): String? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
