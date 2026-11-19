package dev.phoneagent.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SourceCodeTest {
    @Test
    fun lineStartsIncludeEmptyLastLine() {
        assertArrayEquals(intArrayOf(0, 2, 5), sourceLineStarts("a\nbc\n"))
        assertArrayEquals(intArrayOf(0), sourceLineStarts(""))
    }

    @Test
    fun findIsCaseInsensitiveAndLimitsResults() {
        assertEquals(listOf(0, 12, 18), sourceMatches("Alpha bravo alpha ALPHA", "alpha"))
        assertEquals(emptyList<Int>(), sourceMatches("Alpha", ""))
        assertEquals(MaxMatches, sourceMatches("a".repeat(5_000), "a").size)
    }

    @Test
    fun grammarSeparatesStringsFromComments() {
        val tokens = requireNotNull(sourceGrammar("source.kt"))
            .findAll("val url = \"https://host\" // note")
            .toList()

        assertEquals(3, tokens.size)
        assertEquals("val", tokens[0].groups["keyword"]?.value)
        assertEquals("\"https://host\"", tokens[1].groups["string"]?.value)
        assertEquals("// note", tokens[2].groups["comment"]?.value)
    }

    @Test
    fun editorRecognizesConfiguredLanguages() {
        val extensions = listOf(
            "kt", "java", "rs", "py", "js", "ts", "c", "cpp",
            "go", "json", "yaml", "xml", "html", "css", "sh", "md",
        )

        extensions.forEach { assertNotNull(sourceGrammar("source." + it)) }
    }
}
