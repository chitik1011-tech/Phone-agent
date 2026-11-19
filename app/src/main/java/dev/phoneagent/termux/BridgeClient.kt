package dev.phoneagent.termux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class TerminalTab(val id: String, val directory: String)
internal data class TerminalChunk(
    val offset: Long,
    val encodedBytes: String,
    val dropped: Boolean,
    val exitCode: Int?,
)

internal class BridgeFailure(message: String, val missing: Boolean = false) : IOException(message)

internal class BridgeClient(private val port: Int, hexKey: String) {
    private val key = SecretKeySpec(
        hexKey.also { require(it.length == 64) }.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        "HmacSHA256",
    )

    init {
        require(port in 1024..65535)
    }

    suspend fun isReachable(): Boolean = try {
        call("ping").optInt("version") == VERSION
    } catch (_: IOException) {
        false
    }

    suspend fun run(command: String, directory: String, timeoutMs: Long): CommandResult {
        val reply = call("run", replyTimeoutMs = (timeoutMs + 4_000).toInt()) {
            put("command", command)
            put("directory", directory)
            put("timeoutMs", timeoutMs)
        }
        fun output(field: String): String =
            Base64.getDecoder().decode(reply.getString(field)).toString(Charsets.UTF_8)
        return CommandResult(
            stdout = output("stdout"),
            stderr = output("stderr"),
            exitCode = reply.getInt("exit"),
            error = if (reply.getBoolean("timedOut")) "Termux command timed out." else null,
            truncated = reply.getBoolean("truncated"),
        )
    }

    suspend fun list(): List<TerminalTab> {
        val sessions = call("list").getJSONArray("sessions")
        return List(sessions.length()) { tab(sessions.getJSONObject(it)) }
    }

    suspend fun open(directory: String, rows: Int = 24, columns: Int = 80): TerminalTab =
        tab(call("open") {
            put("directory", directory)
            put("rows", rows)
            put("columns", columns)
        })

    suspend fun poll(id: String, offset: Long): TerminalChunk {
        val reply = call("poll") {
            put("id", id)
            put("offset", offset)
        }
        return TerminalChunk(
            offset = reply.getLong("offset"),
            encodedBytes = reply.getString("data"),
            dropped = reply.getBoolean("dropped"),
            exitCode = if (reply.isNull("exit")) null else reply.getInt("exit"),
        )
    }

    suspend fun write(id: String, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        for (start in data.indices step MAX_INPUT) {
            val count = minOf(MAX_INPUT, data.size - start)
            call("write") {
                put("id", id)
                put("data", Base64.getEncoder().encodeToString(data.copyOfRange(start, start + count)))
            }
        }
    }

    suspend fun resize(id: String, rows: Int, columns: Int) {
        call("resize") {
            put("id", id)
            put("rows", rows)
            put("columns", columns)
        }
    }

    suspend fun close(id: String) {
        call("close") { put("id", id) }
    }

    private fun tab(value: JSONObject) = TerminalTab(value.getString("id"), value.getString("directory"))

    private suspend fun call(
        op: String,
        replyTimeoutMs: Int = IO_TIMEOUT_MS,
        fields: JSONObject.() -> Unit = {},
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = JSONObject().put("version", VERSION).put("op", op).apply(fields)
            .toString().toByteArray(Charsets.UTF_8)
        if (request.size > MAX_REQUEST) throw BridgeFailure("Bridge request is too large.")
        suspendCancellableCoroutine { continuation ->
            val result = runCatching {
                Socket().use { socket ->
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    exchange(socket, request, replyTimeoutMs)
                }
            }
            continuation.resumeWith(result)
        }
    }

    private fun exchange(socket: Socket, request: ByteArray, replyTimeoutMs: Int): JSONObject {
        socket.connect(InetSocketAddress("127.0.0.1", port), IO_TIMEOUT_MS)
        socket.soTimeout = IO_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val client = ByteArray(32).also(random::nextBytes)
        output.write(client)
        val server = ByteArray(32).also(input::readFully)
        verify(input, signature('s', client, server))
        output.writeInt(request.size)
        output.write(request)
        output.write(signature('q', client, server, request))
        socket.soTimeout = replyTimeoutMs
        val size = input.readInt()
        if (size !in 1..MAX_RESPONSE) throw BridgeFailure("Invalid bridge response length.")
        val response = ByteArray(size).also(input::readFully)
        verify(input, signature('r', client, server, response))
        val reply = JSONObject(response.toString(Charsets.UTF_8))
        if (reply.has("error")) throw BridgeFailure(reply.getString("error"), reply.optBoolean("missing"))
        return reply
    }

    private fun signature(kind: Char, client: ByteArray, server: ByteArray, body: ByteArray = byteArrayOf()): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.update(kind.code.toByte())
        mac.update(client)
        mac.update(server)
        return mac.doFinal(body)
    }

    private fun verify(input: DataInputStream, expected: ByteArray) {
        val received = ByteArray(expected.size).also(input::readFully)
        if (!MessageDigest.isEqual(received, expected)) throw BridgeFailure("Bridge authentication failed.")
    }

    companion object {
        const val VERSION = 1
        private const val MAX_INPUT = 24 * 1024
        private const val MAX_REQUEST = 128 * 1024
        private const val MAX_RESPONSE = 192 * 1024
        private const val IO_TIMEOUT_MS = 2_500
        private val random = SecureRandom()
    }
}
