package dev.phoneagent.mcp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64
import java.util.Locale

internal data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val schema: JSONObject,
    private val headerParameters: List<HeaderParameter>,
) {
    fun argumentHeaders(arguments: JSONObject): Map<String, String> = buildMap {
        for (parameter in headerParameters) {
            var value: Any? = arguments
            for (property in parameter.path) value = (value as? JSONObject)?.opt(property)
            if (value == null) continue
            val primitive = when (parameter.type) {
                "string" -> value as? String
                "boolean" -> (value as? Boolean)?.toString()
                "integer" -> (value as? Number)?.toString()?.toLongOrNull()
                    ?.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }?.toString()
                else -> null
            } ?: throw McpException("An argument does not match its MCP header parameter type.")
            if (primitive.length > MAX_HEADER_VALUE) throw McpException("An MCP header argument is too large.")
            put("Mcp-Param-${parameter.header}", encodeMcpHeader(primitive))
        }
    }
}

internal data class HeaderParameter(val path: List<String>, val header: String, val type: String)

internal fun readMcpTool(value: JSONObject): McpTool {
    val name = value.opt("name") as? String ?: throw McpException("An MCP tool has no name.")
    val schema = value.optJSONObject("inputSchema")
        ?: throw McpException("An MCP tool has no input schema.")
    if (name.isBlank() || name.length > 256 || name.any(Char::isISOControl) ||
        schema.optString("type") != "object"
    ) {
        throw McpException("An MCP tool has an invalid name or input schema.")
    }

    val title = (value.opt("title") as? String)?.trim()?.take(256)
        ?: (value.optJSONObject("annotations")?.opt("title") as? String)?.trim()?.take(256)
    val description = (value.opt("description") as? String)?.trim()?.take(2000).orEmpty()
    return McpTool(
        name = name,
        title = title.orEmpty().ifBlank { name },
        description = description,
        schema = schema,
        headerParameters = schemaHeaders(schema),
    )
}

private fun schemaHeaders(schema: JSONObject): List<HeaderParameter> {
    val headers = linkedMapOf<String, HeaderParameter>()

    fun inspect(node: Any?, path: List<String>?) {
        if (node is JSONArray) {
            for (index in 0 until node.length()) inspect(node.opt(index), null)
            return
        }
        if (node !is JSONObject) return
        if (node.has("x-mcp-header")) {
            val header = node.opt("x-mcp-header") as? String
            val type = node.opt("type") as? String
            if (path.isNullOrEmpty() || header == null || !HTTP_TOKEN.matches(header) ||
                type !in setOf("string", "boolean", "integer")
            ) {
                throw McpException("An MCP tool contains an unsupported header annotation.")
            }
            val key = header.lowercase(Locale.ROOT)
            val parameter = HeaderParameter(path, header, requireNotNull(type))
            if (headers.put(key, parameter) != null || headers.size > MAX_HEADER_PARAMETERS) {
                throw McpException("An MCP tool has too many or duplicate header annotations.")
            }
        }
        for (key in node.keys()) {
            val child = node.opt(key)
            if (key == "properties" && child is JSONObject && path != null) {
                for (property in child.keys()) inspect(child.opt(property), path + property)
            } else {
                inspect(child, null)
            }
        }
    }

    inspect(schema, emptyList())
    return headers.values.toList()
}

internal fun encodeMcpHeader(value: String): String {
    val sentinel = value.startsWith("=?base64?") && value.endsWith("?=")
    if (!sentinel && value == value.trim() && value.all { it.code in 32..126 }) return value
    return "=?base64?${Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}?="
}

internal fun mcpArguments(raw: String): JSONObject {
    if (raw.toByteArray(Charsets.UTF_8).size > 24 * 1024) throw McpException("The tool arguments are too large.")
    return parseMcpJson(raw.ifBlank { "{}" }, "Enter the arguments as a JSON object.")
}

internal fun parseMcpJson(text: String, invalidMessage: String): JSONObject {
    var nested = 0
    var quoted = false
    var escape = false
    var previous: Char? = null
    for (character in text) {
        if (quoted) {
            if (character.code < 32) throw McpException(invalidMessage)
            if (escape) {
                escape = false
            } else if (character == '\\') {
                escape = true
            } else if (character == '"') {
                quoted = false
                previous = character
            }
        } else {
            when (character) {
                '"' -> {
                    if (previous != null && previous !in "{[,:") throw McpException(invalidMessage)
                    quoted = true
                }
                '\'', '/', '#' -> throw McpException(invalidMessage)
                '{', '[' -> if (++nested > MAX_JSON_NESTING) throw McpException(invalidMessage)
                '}', ']' -> if (--nested < 0) throw McpException(invalidMessage)
            }
            if (!character.isWhitespace()) previous = character
        }
    }
    if (quoted || nested != 0) throw McpException(invalidMessage)

    try {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue() as? JSONObject ?: throw McpException(invalidMessage)
        if (tokener.nextClean() != '\u0000') throw McpException(invalidMessage)
        return value
    } catch (_: JSONException) {
        throw McpException(invalidMessage)
    }
}

private const val MAX_HEADER_PARAMETERS = 12
private const val MAX_HEADER_VALUE = 1024
private const val MAX_JSON_NESTING = 48
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val HTTP_TOKEN = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]{1,80}")
