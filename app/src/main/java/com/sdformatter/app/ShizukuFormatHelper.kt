package com.sdformatter.app

import android.content.Context

/**
 * Wraps Shizuku for formatting operations using the `shizuku` shell command.
 *
 * The `shizuku` binary is provided by Shizuku Manager. When Shizuku is
 * running in ADB mode, commands execute as the shell user (UID 2000) which
 * is sufficient for `sm format <volume_id>` on most devices.
 *
 * When running in root mode, full block-device access is available.
 */
class ShizukuFormatHelper(private val context: Context) {

    // ─── detection via shell ─────────────────────────────────────

    /** Whether the `shizuku` command is available. */
    val isAvailable: Boolean
        get() = runShell("command -v shizuku").exitCode == 0

    /** Whether Shizuku is running in root mode. */
    val isRootMode: Boolean
        get() {
            val result = runShell("shizuku sh -c 'id -u'")
            return result.exitCode == 0 && result.stdout.trim() == "0"
        }

    /** Human-readable label for the current Shizuku state. */
    val modeLabel: String
        get() = when {
            !isAvailable -> "Shizuku not running"
            isRootMode -> "Shizuku (root)"
            else -> "Shizuku (ADB)"
        }

    // ─── format operations ───────────────────────────────────────

    /**
     * Format a volume via `sm format <volumeId>` through Shizuku.
     * Works in ADB mode on most devices.
     */
    fun formatVolume(volumeId: String): FormatResult {
        if (!isAvailable) return FormatResult(false, "Shizuku is not running")

        val result = runShell("shizuku sh -c 'sm format $volumeId'")

        if (result.exitCode == 0) {
            return FormatResult(true,
                "Format initiated via Shizuku. " +
                "Volume reformatted to system default filesystem."
            )
        }

        val err = if (result.stderr.isNotBlank()) result.stderr
                  else "exit code ${result.exitCode}"

        // Common failure: shell user doesn't have the permission
        if (err.contains("Permission denied") || err.contains("SecurityException")) {
            return FormatResult(false,
                "Permission denied via Shizuku ADB mode. " +
                "Try Shizuku root mode or root directly."
            )
        }

        return FormatResult(false, "Shizuku format failed: $err")
    }

    /**
     * Format with explicit mkfs.vfat via Shizuku (root mode only).
     */
    fun formatWithMkfs(blockDevice: String, filesystem: String): FormatResult {
        if (!isAvailable) return FormatResult(false, "Shizuku is not running")
        if (!isRootMode) return FormatResult(false, "mkfs.vfat requires Shizuku root mode")

        val fatFlag = when (filesystem) { "FAT" -> "-F 16"; else -> "-F 32" }

        // Unmount first
        runShell("shizuku sh -c 'umount $blockDevice'")

        // Format
        val result = runShell("shizuku sh -c 'mkfs.vfat $fatFlag -I $blockDevice'")

        return if (result.exitCode == 0) {
            FormatResult(true, "Formatted to $filesystem via Shizuku")
        } else {
            val err = if (result.stderr.isNotBlank()) result.stderr
                      else "exit code ${result.exitCode}"
            FormatResult(false, "mkfs.vfat failed: $err")
        }
    }

    /**
     * Resolve the volume ID that `sm format` expects from a mount path.
     */
    fun resolveVolumeId(mountPath: String): String? {
        if (!isAvailable) return null

        val result = runShell("shizuku sh -c 'sm list-volumes'")
        if (result.exitCode != 0) return null

        for (line in result.stdout.lines()) {
            val parts = line.trim().split(" ")
            if (parts.size >= 2 && parts.last() == mountPath) {
                return parts.first().substringAfter(":").trimEnd(',')
            }
        }
        return null
    }

    // ─── shell helper ────────────────────────────────────────────

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private fun runShell(command: String): ShellResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            ShellResult(code, stdout, stderr)
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }
}
