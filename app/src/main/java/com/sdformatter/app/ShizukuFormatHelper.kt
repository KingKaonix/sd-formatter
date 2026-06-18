package com.sdformatter.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages ADB Wireless Debugging pairing + formatting via bundled adb.
 *
 * Flow (the user only types the 6-digit pairing code):
 * 1. Extract bundled adb + libs from assets
 * 2. Scan 127.0.0.1:37001-39000 for ADB pairing port
 * 3. Open Wireless Debugging settings for the user
 * 4. User enables it, starts pairing, sees 6-digit code
 * 5. User types code in the app dialog
 * 6. App runs `adb pair` with the code
 * 7. App runs `adb connect`
 * 8. App runs `adb shell sm format <volume>` to format
 */
class ShizukuFormatHelper(private val context: Context) {

    private val binDir: File get() = File(context.filesDir, "adb-bin")
    private val adbFile: File get() = File(binDir, "adb")

    /** Whether the bundled adb has been extracted. */
    val isAdbReady: Boolean get() = adbFile.canExecute()

    /** Whether adb shell is usable (shell or root user). */
    val isAdbShellReady: Boolean
        get() {
            val r = runAdb(arrayOf("shell", "id -u"))
            return r.exitCode == 0 && r.stdout.trim() in listOf("2000", "0")
        }

    // ─── extraction ───────────────────────────────────────────────

    /** Extract bundled adb and all needed shared libs from APK assets. */
    fun extractAdb(): Boolean {
        if (adbFile.canExecute()) return true
        try {
            binDir.mkdirs()

            val files = listOf(
                "adb-bin/adb",
                "adb-bin/libprotobuf.so",
                "adb-bin/libc++_shared.so",
                "adb-bin/libbrotlienc.so",
                "adb-bin/libbrotlidec.so",
                "adb-bin/libabsl_flags_internal.so",
                "adb-bin/libz.so.1.3.2",
                "adb-bin/libz.so.1",
                "adb-bin/libzstd.so.1.5.7",
                "adb-bin/libzstd.so.1",
                "adb-bin/liblz4.so",
            )

            for (assetPath in files) {
                val out = File(binDir, assetPath.removePrefix("adb-bin/"))
                context.assets.open(assetPath).use { src ->
                    out.outputStream().use { dst -> src.copyTo(dst) }
                }
                out.setExecutable(true)
            }

            adbFile.setExecutable(true)
            return adbFile.canExecute()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ─── port scanning ─────────────────────────────────────────────

    data class AdbPort(
        val pairing: Int,
        val service: Int
    )

    /** Scan 127.0.0.1 for an active ADB Wireless Debugging service. */
    fun scanForAdb(): AdbPort? {
        for (port in 37001..39000) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", port), 150)
                s.soTimeout = 200
                s.outputStream.write("CNXN".encodeToByteArray())
                val buf = ByteArray(4)
                val n = s.inputStream.read(buf)
                s.close()
                if (n < 4) continue
                val header = String(buf)
                if (header == "CNXN" || header == "AUTH")
                    return AdbPort(pairing = port - 1, service = port)
            } catch (_: Exception) { }
        }
        return null
    }

    // ─── pairing / connection ──────────────────────────────────────

    data class AdbResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = exitCode == 0
    }

    /** Run `adb pair 127.0.0.1:<port> <code>`. */
    fun pair(pairingPort: Int, code: String): AdbResult =
        runAdb(arrayOf("pair", "127.0.0.1:$pairingPort", code))

    /** Run `adb connect 127.0.0.1:<port>`. */
    fun connect(servicePort: Int): AdbResult =
        runAdb(arrayOf("connect", "127.0.0.1:$servicePort"))

    /** Run `adb disconnect 127.0.0.1:<port>`. */
    fun disconnect(servicePort: Int): AdbResult =
        runAdb(arrayOf("disconnect", "127.0.0.1:$servicePort"))

    /** Run a command via `adb shell`. */
    fun shell(command: String): AdbResult =
        runAdb(arrayOf("shell", command))

    // ─── format operations ─────────────────────────────────────────

    /**
     * Format a storage volume via `adb shell sm format`.
     * Requires an active ADB connection (pair → connect first).
     */
    fun formatVolume(volumeId: String): FormatResult {
        val r = shell("sm format $volumeId")
        return if (r.success) {
            FormatResult(true, "Format initiated. Reconnect the device if it doesn't remount.")
        } else {
            FormatResult(false, "sm format failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        }
    }

    /**
     * Format via `adb shell sm format` with force flag.
     */
    fun formatVolumeForce(volumeId: String): FormatResult {
        val r = shell("sm format $volumeId force")
        return if (r.success) {
            FormatResult(true, "Format initiated (forced).")
        } else {
            FormatResult(false, "sm format failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        }
    }

    /**
     * List volumes via `adb shell sm list-volumes` and
     * return the volume ID matching the given mount path.
     */
    fun resolveVolumeId(mountPath: String): String? {
        val r = shell("sm list-volumes")
        if (!r.success) return null
        for (line in r.stdout.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val parts = trimmed.split(" ")
            if (parts.size >= 2 && parts.last() == mountPath) {
                val volId = parts.first().substringAfter(":").trimEnd(',')
                if (volId.isNotBlank()) return volId
            }
        }
        return null
    }

    // ─── internal ──────────────────────────────────────────────────

    /** Run adb with LD_LIBRARY_PATH pointing to our bundled libs. */
    private fun runAdb(args: Array<String>): AdbResult {
        if (!adbFile.canExecute()) {
            return AdbResult(-1, "", "adb binary not extracted")
        }
        val quotedArgs = args.joinToString(" ") { a ->
            if (a.contains(" ")) "'$a'" else a
        }
        val cmd = "LD_LIBRARY_PATH=${binDir.absolutePath} ${adbFile.absolutePath} $quotedArgs"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdout = readStream(proc.inputStream)
            val stderr = readStream(proc.errorStream)
            val code = proc.waitFor()
            AdbResult(code, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            AdbResult(-1, "", e.message ?: "adb run failed")
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return try {
            BufferedReader(InputStreamReader(stream)).readText()
        } catch (_: Exception) { "" }
    }

    /** Clean up extracted adb binaries. */
    fun cleanup() {
        binDir.deleteRecursively()
    }
}
