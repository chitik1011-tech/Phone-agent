package dev.phoneagent.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {
    @Test
    fun carriesOutARealEditThenReportsActualResults() = runBlocking {
        val project = FakeProject()
        val provider = scripted(
            assistant(call("a", "read_file", "path" to "main.kt")),
            assistant(call("b", "edit_file", "path" to "main.kt", "old_text" to "one", "new_text" to "two")),
            answer("Updated main.kt"),
        )
        val checkpoints = mutableListOf<List<AgentMessage>>()
        val result = AgentRunner(provider, project).run("Update the value", AgentOptions(mode = AgentMode.AGENT)) {
            if (it is AgentEvent.Checkpoint) checkpoints.add(it.messages)
        }
        assertEquals("Updated main.kt", result.answer)
        assertEquals("two", project.files["main.kt"])
        assertEquals("one", result.messages.first { it.toolCallId == "a" }.content)
        assertEquals("main.kt updated", result.messages.first { it.toolCallId == "b" }.content)
        assertEquals(4, checkpoints.size)
        assertEquals(result.messages, checkpoints.last())
        assertEquals(result.messages, result.messages.map { AgentMessage.fromJson(it.toJson()) })
    }

    @Test
    fun appliesIndependentApprovalToEveryCommandAndAskModeWrite() = runBlocking {
        val project = FakeProject()
        val provider = scripted(
            assistant(
                call("a", "execute_command", "command" to "ls"),
                call("b", "execute_command", "command" to "pwd"),
                call("c", "write_file", "path" to "main.kt", "text" to "two"),
            ),
            answer("Done"),
        )
        val decisions = mutableListOf<String>()
        val result = AgentRunner(provider, project).run("Inspect and edit", approve = {
            decisions.add(it.id)
            it.id == "b"
        })
        assertEquals(listOf("a", "b", "c"), decisions)
        assertEquals(listOf("pwd"), project.commands)
        assertEquals("one", project.files["main.kt"])
        assertTrue(result.messages.first { it.toolCallId == "a" }.content.contains("Denied"))
        assertTrue(result.messages.first { it.toolCallId == "c" }.content.contains("Denied"))
    }

    @Test
    fun autonomousStillRequiresApprovalForShellAndRejectsPathTraversal() = runBlocking {
        val project = FakeProject()
        val provider = scripted(
            assistant(
                call("a", "execute_command", "command" to "ls"),
                call("b", "read_file", "path" to "../other"),
                call("c", "write_file", "path" to "/outside", "text" to "bad"),
            ),
            answer("Done"),
        )
        val result = AgentRunner(provider, project).run("Inspect", AgentOptions(mode = AgentMode.AUTONOMOUS))
        assertTrue(project.commands.isEmpty())
        assertEquals(mapOf("main.kt" to "one"), project.files)
        assertTrue(result.messages.first { it.toolCallId == "b" }.content.contains("Tool error"))
        assertTrue(result.messages.first { it.toolCallId == "c" }.content.contains("Tool error"))
    }

    @Test
    fun independentlyRunsReadOnlyReviewsConcurrentlyAndDeniesViolatingTools() = runBlocking {
        val project = FakeProject()
        val provider = scripted(
            assistant(
                call("a", "delegate_review", "task" to "Review code style"),
                call("b", "delegate_review", "task" to "Review tests"),
            ),
            answer("Merged review findings"),
        )
        val arrived = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val reviewer = object : AgentProvider {
            override val model = "test-review"
            override suspend fun complete(
                messages: List<AgentMessage>,
                tools: List<JSONObject>,
                onText: suspend (String) -> Unit,
            ): ModelTurn {
                assertEquals(listOf("list_directory", "read_file", "search_files"), tools.map {
                    it.getJSONObject("function").getString("name")
                })
                assertTrue(messages[1].content.contains("Main task:"))
                if (messages.size == 2) {
                    if (arrived.incrementAndGet() == 2) ready.complete(Unit)
                    ready.await()
                    return assistant(call("sub", "write_file", "path" to "main.kt", "text" to "denied"))
                }
                assertTrue(messages.last().content.contains("can only read"))
                return answer("Reviewed with read-only rights")
            }
        }
        val states = mutableListOf<AgentEvent.Subagent>()
        val result = withTimeout(5_000) {
            AgentRunner(provider, project, reviewer).run("Review project") {
                if (it is AgentEvent.Subagent) states.add(it)
            }
        }
        assertEquals("Merged review findings", result.answer)
        assertEquals("one", project.files["main.kt"])
        assertEquals(listOf(SubagentState.RUNNING, SubagentState.RUNNING, SubagentState.COMPLETED, SubagentState.COMPLETED),
            states.map { it.state })
    }

    @Test
    fun compactionPreservesTaskAndCompleteToolGroups() {
        val largeText = "x".repeat(36_000)
        val original = listOf(
            AgentMessage("user", "Original work"),
            AgentMessage("assistant", toolCalls = listOf(call("a", "read_file", "path" to "src/file.kt"))),
            AgentMessage("tool", largeText, toolCallId = "a"),
            AgentMessage("user", "Current work"),
            AgentMessage("assistant", toolCalls = listOf(call("b", "read_file", "path" to "src/current.kt"))),
            AgentMessage("tool", largeText, toolCallId = "b"),
            AgentMessage("assistant", toolCalls = listOf(call("partial", "read_file", "path" to "never.kt"))),
        )
        val context = prepareContext(original, AgentOptions(contextChars = 12_000), "System")
        assertTrue(context.summary.orEmpty().contains("Original work"))
        assertTrue(context.summary.orEmpty().contains("Current work"))
        assertEquals("b", context.messages.last().toolCallId)
        assertFalse(context.messages.any { it.toolCalls.any { call -> call.id == "partial" } })
        assertTrue(context.messages.sumOf(AgentMessage::estimatedChars) <= 12_000)
    }

    @Test
    fun searchesNestedProjectFilesWithoutCallingTheShell() = runBlocking {
        val project = FakeProject().apply {
            files["src/main.kt"] = "first line\nthis line contains needle"
            files["src/other.kt"] = "different"
            files["other/main.kt"] = "needle outside selected search directory"
        }
        val provider = scripted(
            assistant(call("search", "search_files", "path" to "src", "query" to "needle")),
            answer("Found it"),
        )
        val result = AgentRunner(provider, project).run("Search for needle", AgentOptions(mode = AgentMode.READ_ONLY))
        val output = result.messages.first { it.toolCallId == "search" }.content
        assertTrue(output.contains("src/main.kt:2: this line contains needle"))
        assertFalse(output.contains("outside selected search directory"))
        assertTrue(project.commands.isEmpty())
    }

    @Test
    fun gitInspectionUsesOnlyApprovedFixedCommands() = runBlocking {
        val project = FakeProject()
        val provider = scripted(
            assistant(call("status", "git_status", "command" to "user-controlled"), call("diff", "git_diff")),
            answer("Reviewed Git changes"),
        )
        val approvedCommands = mutableListOf<String>()
        val result = AgentRunner(provider, project).run("Inspect Git", approve = { tool ->
            assertEquals("execute_command", tool.name)
            approvedCommands.add(JSONObject(tool.arguments).getString("command"))
            tool.id == "diff"
        })
        assertEquals(2, approvedCommands.size)
        assertTrue(approvedCommands[0].endsWith("status --short --branch"))
        assertTrue(approvedCommands[1].endsWith("diff --no-ext-diff --no-textconv --cached"))
        assertFalse(approvedCommands[0].contains("user-controlled"))
        assertEquals(listOf(approvedCommands[1]), project.commands)
        assertTrue(result.messages.first { it.toolCallId == "status" }.content.contains("Denied"))
    }

    @Test
    fun checkpointIsBoundedAfterJsonEscaping() {
        val initial = AgentMessage("user", "Keep this goal")
        val modelTurns = (1..25).flatMap { index ->
            listOf(
                AgentMessage("assistant", toolCalls = listOf(call("$index", "read_file", "path" to "main.kt"))),
                AgentMessage("tool", "\u0000".repeat(20_000), toolCallId = "$index"),
            )
        }
        val checkpoint = checkpointConversation(listOf(initial) + modelTurns)
        assertTrue(checkpoint.first().content.contains("Keep this goal"))
        assertEquals("25", checkpoint.last().toolCallId)
        assertEquals(checkpoint, validGroups(checkpoint).flatten())
        assertTrue(JSONArray(checkpoint.map(AgentMessage::toJson)).toString().length < 240_000)
    }

    private class FakeProject : ProjectTools {
        val files = mutableMapOf("main.kt" to "one")
        val commands = mutableListOf<String>()
        override suspend fun list(path: String): String {
            val prefix = if (path == ".") "" else "$path/"
            return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.map {
                if ('/' in it) it.substringBefore('/') + "/" else it
            }.distinct().sorted().joinToString("\n")
        }
        override suspend fun read(path: String): String = files.getValue(path)
        override suspend fun write(path: String, text: String): String {
            files[path] = text
            return "$path updated"
        }
        override suspend fun edit(path: String, oldText: String, newText: String): String =
            write(path, files.getValue(path).replace(oldText, newText))

        override suspend fun command(command: String): String {
            commands.add(command)
            return "exit code 0"
        }
    }
}

private fun scripted(vararg turns: ModelTurn): AgentProvider = object : AgentProvider {
    override val model = "test-model"
    private val iterator = turns.iterator()

    override suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<JSONObject>,
        onText: suspend (String) -> Unit,
    ): ModelTurn = iterator.next().also {
        if (it.message.content.isNotEmpty()) onText(it.message.content)
    }
}

private fun call(id: String, name: String, vararg args: Pair<String, String>): ToolCall =
    ToolCall(id, name, JSONObject(args.toMap()).toString())

private fun assistant(vararg calls: ToolCall): ModelTurn =
    ModelTurn(AgentMessage("assistant", toolCalls = calls.toList()))

private fun answer(text: String): ModelTurn = ModelTurn(AgentMessage("assistant", text))
