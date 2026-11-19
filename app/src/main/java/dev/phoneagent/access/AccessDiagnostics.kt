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
import dev.phoneagent.R
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
    val labels = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(labels.getString(R.string.shizuku_checking)) }
    var canRequest by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) { shizukuStatus(labels) }
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
                    status = labels.getString(R.string.shizuku_denied)
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
    AccessCard("Shizuku", labels.getString(R.string.shizuku_hint)) {
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
            ) { Text(if (requestPending) labels.getString(R.string.wait_shizuku) else labels.getString(R.string.request_shizuku)) }
        } else {
            OutlinedButton(onClick = ::refresh) { Text(labels.getString(R.string.refresh_shizuku)) }
        }
    }
}

@Composable
private fun RootDiagnostics() {
    val labels = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(labels.getString(R.string.root_ready)) }
    var checking by remember { mutableStateOf(false) }
    AccessCard("Root", labels.getString(R.string.root_help)) {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                status = labels.getString(R.string.root_waiting)
                scope.launch {
                    try {
                        status = when (val result = checkRootIdentity()) {
                            RootIdentity.Verified -> labels.getString(R.string.root_verified)
                            RootIdentity.Unavailable -> labels.getString(R.string.root_missing)
                            RootIdentity.TimedOut -> labels.getString(R.string.root_timeout)
                            is RootIdentity.Unverified -> result.uid?.let { labels.getString(R.string.root_uid, it) }
                                ?: labels.getString(R.string.root_exit, result.exitCode)
                        }
                    } finally {
                        checking = false
                    }
                }
            },
        ) { Text(labels.getString(R.string.root_identity)) }
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
                if (isShizukuInstalled(context)) context.getString(R.string.shizuku_start)
                else context.getString(R.string.shizuku_missing),
            )
            Shizuku.isPreV11() -> ShizukuStatus(context.getString(R.string.shizuku_update))
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                val uid = Shizuku.getUid()
                val access = when (uid) {
                    0 -> "root"
                    2000 -> "ADB shell"
                    else -> "UID $uid"
                }
                ShizukuStatus(context.getString(R.string.shizuku_granted, access))
            }
            Shizuku.shouldShowRequestPermissionRationale() ->
                ShizukuStatus(context.getString(R.string.shizuku_enable))
            else -> ShizukuStatus(context.getString(R.string.shizuku_permission), canRequest = true)
        }
    } catch (_: RuntimeException) {
        ShizukuStatus(context.getString(R.string.shizuku_disconnected))
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
