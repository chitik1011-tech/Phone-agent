package dev.phoneagent.termux

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONTokener

internal data class TerminalViewport(val rows: Int, val columns: Int)

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class TerminalWebView(
    context: Context,
    onMessage: (String) -> Unit,
) : WebView(context) {
    val ready = CompletableDeferred<Unit>()
    val input = Channel<String>(128)
    val viewport = MutableStateFlow<TerminalViewport?>(null)
    val modifier = MutableStateFlow("")
    private val host = Host(onMessage)

    init {
        setBackgroundColor(Color.rgb(16, 16, 16))
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        addJavascriptInterface(host, "TerminalHost")
        loadUrl("file:///android_asset/termux/terminal.html")
    }

    fun write(encodedBytes: String) = evaluate("write", JSONObject.quote(encodedBytes))
    fun resetTerminal() = evaluate("reset")
    fun terminalKey(name: String) = evaluate("key", JSONObject.quote(name))
    fun paste(text: String) = evaluate("paste", JSONObject.quote(text))
    fun font(size: Int) = evaluate("font", size.toString())

    fun copy(consume: (String) -> Unit) {
        evaluateJavascript("window.phoneTerminal && window.phoneTerminal.copy()") { result ->
            val selected = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (!selected.isNullOrEmpty()) consume(selected)
        }
    }

    fun search(text: String, consume: (Boolean) -> Unit) {
        val query = JSONObject.quote(text)
        evaluateJavascript("window.phoneTerminal && window.phoneTerminal.search($query)") { result -> consume(result == "true") }
    }

    fun release() {
        input.close()
        ready.cancel()
        removeJavascriptInterface("TerminalHost")
        stopLoading()
        destroy()
    }

    private fun evaluate(operation: String, argument: String = "") {
        evaluateJavascript("window.phoneTerminal && window.phoneTerminal.$operation($argument)", null)
    }

    internal inner class Host(private val onMessage: (String) -> Unit) {
        @JavascriptInterface
        fun ready() {
            this@TerminalWebView.ready.complete(Unit)
        }

        @JavascriptInterface
        fun input(value: String) {
            if (value.length > 256 * 1024 || input.trySend(value).isFailure) {
                post { onMessage("Terminal input is too large or the input queue is busy.") }
            }
        }

        @JavascriptInterface
        fun resize(rows: Int, columns: Int) {
            if (rows in 2..200 && columns in 2..500) viewport.value = TerminalViewport(rows, columns)
        }

        @JavascriptInterface
        fun modifier(value: String) {
            if (value in listOf("", "Ctrl", "Alt")) this@TerminalWebView.modifier.value = value
        }
    }
}
