package dev.phoneagent.access

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal sealed interface RootIdentity {
    data object Verified : RootIdentity
    data object Unavailable : RootIdentity
    data object TimedOut : RootIdentity
    data class Unverified(val exitCode: Int, val uid: Int?) : RootIdentity
}

/** Runs only the fixed, read-only identity check requested from the Settings UI. */
internal suspend fun checkRootIdentity(): RootIdentity = runInterruptible(Dispatchers.IO) {
    val process = try {
        ProcessBuilder("su", "-c", "id -u")
            .redirectError(File("/dev/null"))
            .start()
    } catch (_: IOException) {
        return@runInterruptible RootIdentity.Unavailable
    } catch (_: SecurityException) {
        return@runInterruptible RootIdentity.Unavailable
    }
    try {
        process.outputStream.close()
        if (!process.waitFor(30, TimeUnit.SECONDS)) return@runInterruptible RootIdentity.TimedOut
        val output = process.inputStream.use { stream ->
            val bytes = ByteArray(stream.available().coerceIn(0, 4096))
            val count = if (bytes.isEmpty()) 0 else stream.read(bytes).coerceAtLeast(0)
            bytes.decodeToString(endIndex = count)
        }
        parseRootIdentity(process.exitValue(), output)
    } catch (_: IOException) {
        RootIdentity.Unavailable
    } finally {
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

internal fun parseRootIdentity(exitCode: Int, output: String): RootIdentity {
    val uid = output.trim().toIntOrNull()?.takeIf { it >= 0 }
    return if (exitCode == 0 && uid == 0) RootIdentity.Verified else RootIdentity.Unverified(exitCode, uid)
}
