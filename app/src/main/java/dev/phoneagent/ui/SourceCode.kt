package dev.phoneagent.ui

internal const val MaxMatches = 4_096

internal fun sourceMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    return buildList {
        var from = 0
        while (size < MaxMatches) {
            val match = text.indexOf(query, from, ignoreCase = true)
            if (match < 0) break
            add(match)
            from = match + query.length
        }
    }
}

internal fun sourceLineStarts(text: String): IntArray {
    val starts = IntArray(1 + text.count { it == '\n' })
    var next = 1
    text.forEachIndexed { index, character ->
        if (character == '\n') starts[next++] = index + 1
    }
    return starts
}

private const val CBlock = """/\*[\s\S]*?(?:\*/|\z)"""
private const val CLine = """//[^\r\n]*"""
private const val HashLine = """\#[^\r\n]*"""
private const val Strings = """"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'"""
private const val Numbers = """\b(?:0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d[\d_]*)?)\b"""
private const val CWords = """
    abstract|as|async|await|bool|break|case|catch|char|class|const|continue|default|defer|do|double|else|enum|export|extends|extern|false|final|finally|float|fn|for|func|fun|go|if|impl|implements|import|in|int|interface|let|long|match|mod|move|mut|namespace|new|nil|null|nullptr|object|operator|override|package|private|protected|pub|public|range|return|sealed|static|string|struct|super|suspend|switch|this|throw|trait|true|try|type|typedef|typename|union|unsafe|use|using|val|var|void|when|where|while|yield
"""
private const val ScriptWords = """
    and|as|assert|async|await|break|case|class|continue|def|del|do|done|elif|else|esac|except|export|false|fi|finally|for|from|function|global|if|import|in|is|lambda|match|nonlocal|None|not|or|pass|raise|readonly|return|select|then|trap|True|true|False|try|until|while|with|yield
"""

private fun grammar(comment: String, keyword: String, quoted: String = Strings): Regex = Regex(
    "(?<comment>" + comment + ")|(?<string>" + quoted + ")|(?<number>" + Numbers + ")|(?<keyword>" + keyword + ")",
)

private val cFamily by lazy { grammar("$CBlock|$CLine", """\b(?:$CWords)\b""".replace(Regex("\\s+"), "")) }
private val scriptFamily by lazy { grammar(HashLine, """\b(?:$ScriptWords)\b""".replace(Regex("\\s+"), "")) }
private val markup by lazy { grammar("""<!--[\s\S]*?(?:-->|\z)""", """</?[\w:-]+|/?>|&[\w#]+;""") }
private val css by lazy { grammar(CBlock, """[-\w]+(?=\s*:)|@[a-zA-Z-]+""") }
private val json by lazy { grammar("""(?!)""", """\b(?:true|false|null)\b""") }
private val yaml by lazy { grammar(HashLine, """[-\w]+(?=\s*:)|\b(?:true|false|null)\b""") }
private val markdown by lazy { grammar("""<!--[\s\S]*?(?:-->|\z)""", """(?m)^\s{0,3}#{1,6}(?=\s)|\*{1,2}|\[[^\]\r\n]+]""") }

internal fun sourceGrammar(fileName: String): Regex? = when (fileName.substringAfterLast('.', "").lowercase()) {
    "kt", "kts", "java", "rs", "js", "jsx", "mjs", "cjs", "ts", "tsx", "c", "cc", "cpp", "cxx", "h", "hpp", "go" -> cFamily
    "py", "sh", "bash", "zsh", "fish" -> scriptFamily
    "html", "htm", "xml", "svg" -> markup
    "css", "scss" -> css
    "json" -> json
    "yaml", "yml" -> yaml
    "md", "markdown" -> markdown
    else -> null
}
