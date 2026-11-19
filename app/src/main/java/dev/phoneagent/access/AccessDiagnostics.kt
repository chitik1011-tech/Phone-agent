package dev.phoneagent.access

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@Composable
fun AccessDiagnostics(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShizukuDiagnostics()
        RootDiagnostics()
    }
}

@Composable
private fun ShizukuDiagnostics() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking service…") }
    var canRequest by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) { shizukuStatus(context) }
            status = result.message
            canRequest = result.canRequest
            if (!result.canRequest) requestPending = false
        }
    }
    DisposableEffect(lifecycle) {
        val connected = Shizuku.OnBinderReceivedListener { refresh() }
        val disconnected = Shizuku.OnBinderDeadListener {
            requestPending = false
            refresh()
        }
        val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                requestPending = false
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    refresh()
                } else {
                    status = "Permission declined. You can manage access in Shizuku."
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        Shizuku.addBinderReceivedListenerSticky(connected)
        Shizuku.addBinderDeadListener(disconnected)
        Shizuku.addRequestPermissionResultListener(permission)
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            Shizuku.removeBinderReceivedListener(connected)
            Shizuku.removeBinderDeadListener(disconnected)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
    AccessCard("Shizuku", "Verify service availability and app permission.") {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (canRequest) {
            Button(
                enabled = !requestPending,
                onClick = {
                    try {
                        requestPending = true
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
                    } catch (_: RuntimeException) {
                        requestPending = false
                        refresh()
                    }
                },
            ) { Text(if (requestPending) "Waiting for Shizuku…" else "Request Shizuku permission") }
        } else {
            OutlinedButton(onClick = ::refresh) { Text("Refresh Shizuku") }
        }
    }
}

@Composable
private fun RootDiagnostics() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready to check.") }
    var checking by remember { mutableStateOf(false) }
    AccessCard("Root", "Run an identity check through your installed root manager.") {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                status = "Waiting for the root manager…"
                scope.launch {
                    try {
                        status = when (val result = checkRootIdentity()) {
                            RootIdentity.Verified -> "Root verified (UID 0)."
                            RootIdentity.Unavailable -> "The app could not run su."
                            RootIdentity.TimedOut -> "The root manager did not respond within 30 seconds."
                            is RootIdentity.Unverified -> result.uid?.let { "Root unverified (UID $it)." }
                                ?: "Root unverified (exit code ${result.exitCode}). Check your root manager."
                        }
                    } finally {
                        checking = false
                    }
                }
            },
        ) { Text("Check root identity") }
    }
}

@Composable
private fun AccessCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

private data class ShizukuStatus(val message: String, val canRequest: Boolean = false)

private fun shizukuStatus(context: Context): ShizukuStatus {
    return try {
        when {
            !Shizuku.pingBinder() -> ShizukuStatus(
                if (isShizukuInstalled(context)) "Start the service in Shizuku and return here."
                else "Shizuku is unavailable on this device.",
            )
            Shizuku.isPreV11() -> ShizukuStatus("Update Shizuku to v11 or later.")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                val uid = Shizuku.getUid()
                val access = when (uid) {
                    0 -> "root"
                    2000 -> "ADB shell"
                    else -> "UID $uid"
                }
                ShizukuStatus("Permission granted. Service is running as $access.")
            }
            Shizuku.shouldShowRequestPermissionRationale() ->
                ShizukuStatus("Enable access for PHONE AGENT in Shizuku.")
            else -> ShizukuStatus("Service is running. App permission is required.", canRequest = true)
        }
    } catch (_: RuntimeException) {
        ShizukuStatus("The Shizuku service disconnected. Refresh to check again.")
    }
}

@Suppress("DEPRECATION")
private fun isShizukuInstalled(context: Context): Boolean {
    return try {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            packages.getApplicationInfo(SHIZUKU_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packages.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_PERMISSION_REQUEST = 41
