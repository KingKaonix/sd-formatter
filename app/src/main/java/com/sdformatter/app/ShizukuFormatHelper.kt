package com.sdformatter.app

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Wraps Shizuku API for formatting operations.
 *
 * Shizuku ADB mode (no root): can run `sm format <vol_id>` which calls
 * StorageManagerService.format() as the shell user. This bypasses the
 * MOUNT_FORMAT_FILESYSTEMS permission restriction on many devices.
 *
 * Shizuku root mode: can run `mkfs.vfat -F 32 <block_dev>` directly.
 */
class ShizukuFormatHelper(private val context: Context) {

    // ─── availability ────────────────────────────────────────────

    /** Whether the Shizuku service is currently bound and running. */
    val isAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (_: Exception) { false }

    /** Whether Shizuku is running in root mode (vs ADB mode). */
    val isRootMode: Boolean
        get() = try {
            val pid = Shizuku.getUid()
            pid == 0  // UID 0 = root
        } catch (_: Exception) { false }

    /** Human-readable description of the current Shizuku mode. */
    val modeLabel: String
        get() = when {
            !isAvailable -> "Shizuku not running"
            isRootMode -> "Shizuku (root)"
            else -> "Shizuku (ADB)"
        }

    // ─── permission ──────────────────────────────────────────────

    /** Request Shizuku runtime permission (needed on Android 11+). */
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) { }
    }

    /** Whether Shizuku runtime permission has been granted. */
    val hasPermission: Boolean
        get() = Shizuku.getVersion() > 0 &&
                Shizuku.checkSelfPermission() == 0  // 0 = GRANTED (PERMISSION_GRANTED)

    // ─── format operations ───────────────────────────────────────

    /**
     * Format a volume via Shizuku using `sm format <volumeId>`.
     * Works in ADB mode on most devices.
     */
    fun formatVolume(volumeId: String): FormatResult {
        if (!isAvailable) return FormatResult(false, "Shizuku is not running")
        if (!hasPermission) return FormatResult(false, "Shizuku permission not granted")

        return try {
            // Use Shizuku newProcess to run the shell command
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "sm format " + volumeId),
                null,
                null
            )
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                FormatResult(true,
                    "Format initiated via Shizuku. " +
                    "The volume will be reformatted to the system default filesystem."
                )
            } else {
                val err = process.errorStream?.bufferedReader()?.readText() ?: "exit code $exitCode"
                FormatResult(false, "sm format failed: $err")
            }
        } catch (e: SecurityException) {
            FormatResult(false, "Shizuku: permission denied. Try granting in Shizuku app.")
        } catch (e: Exception) {
            FormatResult(false, "Shizuku format failed: ${e.message}")
        }
    }

    /**
     * Format with explicit mkfs.vfat via Shizuku (root mode only).
     */
    fun formatWithMkfs(blockDevice: String, filesystem: String): FormatResult {
        if (!isAvailable) return FormatResult(false, "Shizuku is not running")
        if (!isRootMode) return FormatResult(false, "mkfs.vfat requires Shizuku root mode")

        val fatFlag = when (filesystem) { "FAT" -> "-F 16"; else -> "-F 32" }

        return try {
            // Unmount first
            Shizuku.newProcess(
                arrayOf("sh", "-c", "umount " + blockDevice),
                null, null
            ).waitFor()

            // Format
            val proc = Shizuku.newProcess(
                arrayOf("sh", "-c", "mkfs.vfat $fatFlag -I '$blockDevice'"),
                null, null
            )
            val code = proc.waitFor()

            if (code == 0) {
                FormatResult(true, "Formatted to $filesystem via Shizuku")
            } else {
                val err = proc.errorStream?.bufferedReader()?.readText() ?: "exit code $code"
                FormatResult(false, "mkfs.vfat failed: $err")
            }
        } catch (e: Exception) {
            FormatResult(false, "Shizuku format failed: ${e.message}")
        }
    }

    /**
     * Get the volume ID from a mount path using `sm list-volumes`.
     */
    fun resolveVolumeId(mountPath: String): String? {
        if (!isAvailable) return null
        return try {
            val proc = Shizuku.newProcess(
                arrayOf("sh", "-c", "sm list-volumes"),
                null, null
            )
            val output = proc.inputStream?.bufferedReader()?.readText() ?: return null
            proc.waitFor()

            // Parse output: "public:179,64 /storage/XXXX-XXXX"
            for (line in output.lines()) {
                val parts = line.trim().split(" ")
                if (parts.size >= 2) {
                    val path = parts.last()
                    if (path == mountPath) {
                        return parts.first().substringAfter(":").trimEnd(',')
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
