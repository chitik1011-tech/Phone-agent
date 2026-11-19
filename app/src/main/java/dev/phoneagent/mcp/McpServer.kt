package dev.phoneagent.mcp

import android.content.Context
import dev.phoneagent.providers.EncryptedRecord
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = true,
) {
    fun endpoint(): HttpUrl {
        require(name.isNotBlank() && name.length <= 80) { "Enter a connection name." }
        require(url.length <= 2048) { "The MCP URL is too long." }
        val endpoint = url.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter the full MCP endpoint URL.")
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null
        ) { "Enter an endpoint URL without credentials, a query, or a fragment." }
        require(endpoint.isHttps || endpoint.host in LOOPBACK_HOSTS) {
            "Use HTTPS for a remote MCP server."
        }
        val key = token.trim()
        require(key.length <= 4096 && BEARER.matches(key)) {
            "Enter a valid bearer token."
        }
        return endpoint
    }

    override fun toString(): String = "McpServer(id=$id, enabled=$enabled)"

    private companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        val BEARER = Regex("[A-Za-z0-9._~+/-]+=*")
    }
}

internal class McpStore(context: Context) {
    private val record = EncryptedRecord(
        context,
        fileName = "mcp-servers.enc",
        keyAlias = "phone-agent.mcp-servers.v1",
        maxRecordBytes = 64 * 1024,
    )

    fun all(): List<McpServer> = synchronized(lock) {
        val text = record.read() ?: return@synchronized emptyList()
        val entries = JSONArray(text)
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            McpServer(
                id = entry.getString("id"),
                name = entry.getString("name"),
                url = entry.getString("url"),
                token = entry.getString("token"),
                enabled = entry.getBoolean("enabled"),
            ).also { it.endpoint() }
        }
    }

    fun save(server: McpServer): List<McpServer> = synchronized(lock) {
        server.endpoint()
        val current = all()
        require(current.size < MAX_SERVERS || current.any { it.id == server.id }) {
            "Remove an existing MCP server before adding another."
        }
        val updated = current.filterNot { it.id == server.id } + server.copy(
            name = server.name.trim(),
            url = server.url.trim(),
            token = server.token.trim(),
        )
        write(updated)
        updated
    }

    fun remove(id: String): List<McpServer> = synchronized(lock) {
        val current = all()
        val updated = current.filterNot { it.id == id }
        if (updated.isEmpty()) record.clear() else write(updated)
        updated
    }

    private fun write(servers: List<McpServer>) {
        val entries = JSONArray()
        for (server in servers) {
            entries.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.url)
                    .put("token", server.token)
                    .put("enabled", server.enabled),
            )
        }
        record.write(entries.toString())
    }

    private companion object {
        const val MAX_SERVERS = 8
        val lock = Any()
    }
}
