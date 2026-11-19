package dev.phoneagent.termux

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BridgeClientTest {
    @Test
    fun authenticatedClientExchangesTerminalBytesWithPython() = runBlocking {
        withBridge { client, home ->
            assertEquals(emptyList<TerminalTab>(), client.list())
            val terminal = client.open(home.toString(), rows = 32, columns = 101)
            try {
                assertEquals(listOf(terminal), client.list())
                client.write(terminal.id, "printf 'INTEGRATION:%s\\n' 'Привет'; stty size; exit 9\n")
                val transcript = ByteArrayOutputStream()
                val exit = withTimeout(15_000) {
                    var offset = 0L
                    var exitCode: Int?
                    do {
                        val chunk = client.poll(terminal.id, offset)
                        transcript.write(Base64.getDecoder().decode(chunk.encodedBytes))
                        offset = chunk.offset
                        exitCode = chunk.exitCode
                        if (exitCode == null) delay(25)
                    } while (exitCode == null)
                    exitCode
                }
                assertEquals(9, exit)
                // Prompts and bracketed-paste escapes differ across bash configurations.
                val lines = transcript.toString(Charsets.UTF_8.name())
                    .replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
                    .lineSequence().toList()
                assertTrue("Expected an exact UTF-8 output line: $lines", "INTEGRATION:Привет" in lines)
                assertTrue("Expected an exact PTY size line: $lines", "32 101" in lines)
            } finally {
                client.close(terminal.id)
            }
        }
    }

    @Test
    fun managedCommandSeparatesOutputAndEnforcesTimeout() = runBlocking {
        withBridge(commandOnly = true) { client, home ->
            val output = client.run("printf 'Привет'; printf 'failure' >&2; exit 7", home.toString(), 5_000)
            assertEquals("Привет", output.stdout)
            assertEquals("failure", output.stderr)
            assertEquals(7, output.exitCode)
            assertFalse(output.truncated)

            val limit = client.run("head -c 40000 /dev/zero", home.toString(), 5_000)
            assertEquals(32 * 1024, limit.stdout.length)
            assertTrue(limit.truncated)

            val timeout = client.run("printf 'before timeout'; sleep 60", home.toString(), 1_000)
            assertEquals("before timeout", timeout.stdout)
            assertEquals(124, timeout.exitCode)
        }
    }

    @Test
    fun cancellationStopsTheCommandAndItsChild() = runBlocking {
        withBridge(commandOnly = true) { client, home ->
            val job = launch {
                client.run(
                    "trap '' TERM; (sleep 3; printf leaked > leaked) & printf ready > started; wait",
                    home.toString(), 15_000,
                )
            }
            try {
                withTimeout(10_000) {
                    while (!Files.exists(home.resolve("started"))) delay(20)
                }
                withTimeout(2_000) { job.cancelAndJoin() }
                delay(3_200)
                assertFalse("A child survived command cancellation.", Files.exists(home.resolve("leaked")))
                assertEquals(0, client.run("true", home.toString(), 5_000).exitCode)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private suspend fun withBridge(
        commandOnly: Boolean = false,
        block: suspend (BridgeClient, Path) -> Unit,
    ) {
        assumeTrue(System.getProperty("os.name")?.startsWith("Linux") == true)
        val path = "src/main/assets/termux/phone_agent_bridge.py"
        val script = listOf(Path.of(path), Path.of("app", path)).first(Files::exists).toAbsolutePath()
        val home = Files.createTempDirectory("phone-agent-termux-test")
        val state = Files.createDirectories(home.resolve(".local/share/phone-agent-bridge"))
            .resolve(if (commandOnly) "command-socket.json" else "socket.json")
        try {
            val shell = home.resolve("test-shell").toFile().apply {
                writeText("#!/bin/sh\nexec /bin/bash --noprofile --norc -i\n")
                check(setExecutable(true)) { "Could not set up the test shell." }
            }
            val mode = if (commandOnly) "--serve-commands" else "--serve"
            val server = ProcessBuilder("python3", script.toString(), mode)
                .redirectOutput(home.resolve("bridge.stdout").toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply {
                    environment()["HOME"] = home.toString()
                    environment()["SHELL"] = shell.absolutePath
                    environment().remove("BASH_ENV")
                }
                .start()
            try {
                withTimeout(15_000) {
                    while (!Files.exists(state) && server.isAlive) delay(25)
                }
                val info = JSONObject(state.toFile().readText())
                val client = BridgeClient(info.getInt("port"), info.getString("key"))
                assertTrue("The authenticated bridge should answer a status request.", client.isReachable())
                block(client, home)
            } finally {
                server.destroy()
                if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
