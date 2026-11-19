package dev.phoneagent.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Doc
import android.util.Base64
import dev.phoneagent.agent.ProjectTools
import dev.phoneagent.termux.TermuxRuntime
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WorkspaceRef(val location: String, val title: String, val termux: Boolean)

interface Workspace : ProjectTools {
    val ref: WorkspaceRef
    suspend fun git(operation: String): String
}

internal const val MAX_FILE_BYTES = 20_000

fun workspace(context: Context, runtime: TermuxRuntime, ref: WorkspaceRef): Workspace =
    if (ref.termux) TermuxWorkspace(context, runtime, ref)
    else DocumentWorkspace(context.contentResolver, ref)

internal fun pathParts(path: String, allowRoot: Boolean = false): List<String> {
    if (allowRoot && (path.isEmpty() || path == ".")) return emptyList()
    require(path.length in 1..512 && '\\' !in path && path.none(Char::isISOControl)) {
        "Use a relative project path."
    }
    val parts = path.split('/')
    require(parts.all { it.isNotEmpty() && it != "." && it != ".." && !restrictedName(it) }) {
        "Use a relative path within the project, excluding private files."
    }
    return parts
}

internal fun restrictedName(name: String): Boolean {
    val lower = name.lowercase()
    return lower == ".git" || lower == ".env" || lower.startsWith(".env.") ||
        lower in setOf(".ssh", ".aws", ".gnupg", ".netrc", ".npmrc", ".pypirc", ".git-credentials",
            "local.properties", "credentials.json", "id_rsa", "id_ed25519") ||
        listOf(".jks", ".keystore", ".pem", ".key", ".p12").any(lower::endsWith)
}

internal fun replaceOnce(content: String, oldText: String, newText: String): String {
    require(oldText.isNotEmpty()) { "The original text must be nonempty." }
    val index = content.indexOf(oldText)
    require(index >= 0 && content.indexOf(oldText, index + 1) < 0) {
        "The original text must occur exactly once."
    }
    return content.replaceRange(index, index + oldText.length, newText)
}

private class DocumentWorkspace(
    private val resolver: ContentResolver,
    override val ref: WorkspaceRef,
) : Workspace {
    private val tree = Uri.parse(ref.location)
    private val root = Node(Doc.getTreeDocumentId(tree), ref.title, Doc.Document.MIME_TYPE_DIR)

    private data class Node(val id: String, val name: String, val mime: String) {
        val directory get() = mime == Doc.Document.MIME_TYPE_DIR
    }

    private fun uri(node: Node) = Doc.buildDocumentUriUsingTree(tree, node.id)

    private fun children(node: Node): List<Node> {
        require(node.directory) { "This path is a file." }
        val children = Doc.buildChildDocumentsUriUsingTree(tree, node.id)
        val columns = arrayOf(
            Doc.Document.COLUMN_DOCUMENT_ID,
            Doc.Document.COLUMN_DISPLAY_NAME,
            Doc.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(children, columns, null, null, null)?.use { rows ->
            buildList {
                while (rows.moveToNext() && size < 500) {
                    val name = rows.getString(1)
                    if (!restrictedName(name) && '/' !in name && name.none(Char::isISOControl)) {
                        add(Node(rows.getString(0), name, rows.getString(2)))
                    }
                }
            }
        } ?: error("Android could not list the selected folder. Check its access grant.")
    }

    private fun find(parts: List<String>): Node = parts.fold(root) { parent, name ->
        children(parent).singleOrNull { it.name == name } ?: error("Path not found: $name")
    }

    private fun readNode(node: Node): String {
        require(!node.directory) { "Select a text file." }
        val bytes = resolver.openInputStream(uri(node))?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                output.write(chunk, 0, count)
                require(output.size() <= MAX_FILE_BYTES) { "This file exceeds the 20 KB editor limit." }
            }
            output.toByteArray()
        } ?: error("Android could not read this file.")
        require(0.toByte() !in bytes) { "This is a binary file." }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun writeNode(node: Node, text: String) {
        require(!node.directory) { "Cannot write to a directory." }
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES) { "This file exceeds the 20 KB editor limit." }
        resolver.openOutputStream(uri(node), "wt")?.use { it.write(bytes) }
            ?: error("The document provider did not open this file for writing.")
    }

    override suspend fun list(path: String) = withContext(Dispatchers.IO) {
        children(find(pathParts(path, allowRoot = true))).sortedBy { it.name.lowercase() }
            .joinToString("\n") { if (it.directory) "${it.name}/" else it.name }
    }

    override suspend fun read(path: String) = withContext(Dispatchers.IO) {
        readNode(find(pathParts(path)))
    }

    override suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= MAX_FILE_BYTES) { "This file exceeds the 20 KB editor limit." }
        val parts = pathParts(path)
        val parent = find(parts.dropLast(1))
        val name = parts.last()
        val node = children(parent).singleOrNull { it.name == name } ?: run {
            val created = Doc.createDocument(resolver, uri(parent), "application/octet-stream", name)
                ?: error("Android could not create the file.")
            Node(Doc.getDocumentId(created), name, "application/octet-stream")
        }
        writeNode(node, text)
        "Saved $path"
    }

    override suspend fun edit(path: String, oldText: String, newText: String) =
        withContext(Dispatchers.IO) {
            val node = find(pathParts(path))
            writeNode(node, replaceOnce(readNode(node), oldText, newText))
            "Updated $path"
        }

    override suspend fun command(command: String): String =
        error("Open the project's exact Termux directory to run commands.")

    override suspend fun git(operation: String): String =
        error("Open the project's exact Termux directory to run Git.")
}

private class TermuxWorkspace(
    private val context: Context,
    private val runtime: TermuxRuntime,
    override val ref: WorkspaceRef,
) : Workspace {
    private val helper by lazy {
        context.assets.open("workspace/files.py").bufferedReader().use { it.readText() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    private suspend fun file(operation: String, path: String, vararg args: String): String {
        pathParts(path, allowRoot = operation == "list")
        require(args.all { it.toByteArray().size <= MAX_FILE_BYTES }) { "Text exceeds 20 KB." }
        val data = args.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        val invocation = listOf("python3", "-I", "-S", "-c", helper, ref.location, operation, path) + data
        val result = runtime.run(invocation.joinToString(" ", transform = ::quote), ref.location)
        check(result.error == null) { result.error.orEmpty() }
        check(result.exitCode == 0) { result.stderr.trim().ifEmpty { "Termux exited with ${result.exitCode}." } }
        check(!result.truncated) { "Termux truncated file output." }
        return result.stdout
    }

    override suspend fun list(path: String) = file("list", path)
    override suspend fun read(path: String) = file("read", path)
    override suspend fun write(path: String, text: String) = file("write", path, text)
    override suspend fun edit(path: String, oldText: String, newText: String) =
        file("edit", path, oldText, newText)

    override suspend fun command(command: String): String {
        val result = runtime.run(command, ref.location)
        return buildString {
            result.error?.let { appendLine(it) }
            appendLine("Exit code: ${result.exitCode ?: "unknown"}")
            if (result.truncated) appendLine("Termux truncated this output.")
            if (result.stdout.isNotEmpty()) appendLine(result.stdout)
            if (result.stderr.isNotEmpty()) {
                appendLine("stderr:")
                append(result.stderr)
            }
        }
    }

    override suspend fun git(operation: String): String = command(
        when (operation) {
            "Status" -> "git -c core.fsmonitor=false --no-pager status --short --branch"
            "Diff" -> "git -c core.fsmonitor=false --no-pager diff --no-ext-diff --no-textconv; git -c core.fsmonitor=false --no-pager diff --cached --no-ext-diff --no-textconv"
            "History" -> "git -c core.fsmonitor=false --no-pager log --oneline -30"
            "Branches" -> "git -c core.fsmonitor=false --no-pager branch --all"
            "Worktrees" -> "git -c core.fsmonitor=false --no-pager worktree list"
            else -> error("Unsupported Git view.")
        }
    )
}
