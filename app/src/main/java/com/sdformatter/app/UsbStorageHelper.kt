package com.sdformatter.app

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class StorageDevice(
    val id: String,
    val label: String,
    val mountPath: String?,
    val blockDevice: String?,
    val filesystem: String?,
    val totalSize: Long,
    val usedSize: Long,
    val isRemovable: Boolean,
    val usbDevice: UsbDevice?
)

class UsbStorageHelper(private val context: Context) {

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val storageManager: StorageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    fun getStorageDevices(): List<StorageDevice> {
        val seen = mutableSetOf<String>()
        val devices = mutableListOf<StorageDevice>()

        // 1. StorageManager volumes — already mounted by the system
        val volumes = storageManager.storageVolumes ?: emptyList()
        for (volume in volumes) {
            if (!volume.isRemovable) continue
            val volPath = resolveVolumePath(volume) ?: continue
            if (!isVolumeMounted(volume, volPath)) continue

            val info = File(volPath)
            val (blockDev, fs) = resolveMountInfo(volPath)
            devices.add(
                StorageDevice(
                    id = volume.uuid ?: volPath,
                    label = volume.getDescription(context),
                    mountPath = volPath,
                    blockDevice = blockDev,
                    filesystem = fs,
                    totalSize = try { info.totalSpace } catch (_: Exception) { 0L },
                    usedSize = try { info.totalSpace - info.freeSpace } catch (_: Exception) { 0L },
                    isRemovable = true,
                    usbDevice = null
                )
            )
            seen.add(volPath)
        }

        // 2. Raw USB devices not yet covered by StorageManager
        val usbMap = usbManager.deviceList ?: emptyMap()
        for ((_, usbDev) in usbMap) {
            val mountPath = findMountPathForUsb(usbDev, seen)
            val (blockDev, fs) = mountPath?.let { resolveMountInfo(it) } ?: (null to null)

            devices.add(
                StorageDevice(
                    id = usbDev.deviceName,
                    label = usbDev.productName ?: "Unknown USB device",
                    mountPath = mountPath,
                    blockDevice = blockDev,
                    filesystem = fs,
                    totalSize = mountPath?.let {
                        try { File(it).totalSpace } catch (_: Exception) { 0L }
                    } ?: 0L,
                    usedSize = mountPath?.let {
                        try { File(it).totalSpace - File(it).freeSpace } catch (_: Exception) { 0L }
                    } ?: 0L,
                    isRemovable = true,
                    usbDevice = usbDev
                )
            )
            if (mountPath != null) seen.add(mountPath)
        }

        return devices
    }

    // ─── volume path helpers ──────────────────────────────────────

    private fun resolveVolumePath(volume: StorageVolume): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            try {
                volume.javaClass.getMethod("getPath").invoke(volume) as? String
            } catch (_: Exception) { null }
        }
    }

    private fun isVolumeMounted(volume: StorageVolume, volPath: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val st = volume.javaClass.getMethod("getState").invoke(volume) as? String
                st == Environment.MEDIA_MOUNTED
            } catch (_: Exception) { true }
        } else {
            Environment.getExternalStorageState(File(volPath)) == Environment.MEDIA_MOUNTED
        }
    }

    // ─── mount info parser ────────────────────────────────────────

    private data class MountEntry(
        val blockDevice: String,
        val mountPoint: String,
        val filesystem: String
    )

    private fun readMounts(): List<MountEntry> {
        for (path in listOf("/proc/self/mounts", "/proc/mounts")) {
            val f = File(path)
            if (!f.canRead()) continue
            try {
                val result = mutableListOf<MountEntry>()
                BufferedReader(FileReader(f)).use { reader ->
                    for (line in reader.readLines()) {
                        val parts = line.split(" ")
                        if (parts.size >= 3) {
                            result.add(MountEntry(parts[0], parts[1], parts[2]))
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    private fun resolveMountInfo(mountPath: String): Pair<String?, String?> {
        val mounts = readMounts()
        for (entry in mounts) {
            if (entry.mountPoint == mountPath) {
                return entry.blockDevice to entry.filesystem
            }
        }
        return null to null
    }

    private fun findMountPathForUsb(device: UsbDevice, alreadySeen: Set<String>): String? {
        val mounts = readMounts()
        if (mounts.isEmpty()) return null

        val candidates = mutableListOf<String>()

        for (entry in mounts) {
            val mp = entry.mountPoint
            if (mp in alreadySeen) continue
            if (!mp.startsWith("/mnt/") && !mp.startsWith("/storage/") && mp != "/") continue

            val lower = mp.lowercase()
            val blower = entry.blockDevice.lowercase()

            if ("usb" in lower || "otg" in lower || "sd" in lower ||
                "usb" in blower || "otg" in blower) {
                candidates.add(0, mp)
            } else {
                candidates.add(mp)
            }
        }

        return candidates.firstOrNull()
    }

    // ─── FORMAT METHODS ───────────────────────────────────────────

    fun hasRootAccess(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            "uid=0" in out
        } catch (_: Exception) { false }
    }

    /**
     * Try to format without root. Returns null if no non-root method worked.
     */
    fun formatWithoutRoot(device: StorageDevice): FormatResult? {
        // Method 1: StorageVolume.format() via reflection
        val result = trySystemFormat(device)
        if (result != null) return result

        // Method 2: File-level wipe (delete all files)
        return wipeFiles(device)
    }

    /**
     * Format with root (mkfs.vfat).
     */
    fun formatWithRoot(device: StorageDevice, filesystem: String): FormatResult {
        val blockDev = device.blockDevice
            ?: return FormatResult(false, "Block device not found")

        val fatFlag = when (filesystem) { "FAT" -> "-F 16"; else -> "-F 32" }

        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "umount", blockDev)).waitFor()

            val proc = Runtime.getRuntime().exec("su -c \"mkfs.vfat $fatFlag -I '$blockDev'\"")
            val code = proc.waitFor()

            if (code == 0) FormatResult(true, "Format successful (FAT32)".also {
                // System should auto-detect and remount
            })
            else {
                val err = proc.errorStream.bufferedReader().readText()
                FormatResult(false, "mkfs.vfat failed: $err")
            }
        } catch (e: Exception) {
            FormatResult(false, "Format failed: ${e.message}")
        }
    }

    /**
     * Attempt StorageVolume.format() via reflection.
     */
    private fun trySystemFormat(device: StorageDevice): FormatResult? {
        try {
            val volumes = storageManager.storageVolumes ?: emptyList()
            for (volume in volumes) {
                val volPath = resolveVolumePath(volume) ?: continue
                if (volPath != device.mountPath) continue

                val formatMethod = volume.javaClass.getMethod("format", Context::class.java)
                formatMethod.invoke(volume, context)

                return FormatResult(true,
                    "Format initiated via system. The volume will be reformatted. " +
                    "Reconnect the device afterwards if it doesn't remount automatically."
                )
            }
        } catch (e: SecurityException) {
            // System permission not granted to this app — expected on stock Android
            return null
        } catch (e: NoSuchMethodException) {
            return null
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /**
     * Delete all files on the volume (file-level wipe, preserves filesystem).
     * This is the guaranteed non-root fallback.
     */
    fun wipeFiles(device: StorageDevice): FormatResult {
        val mountPath = device.mountPath
            ?: return FormatResult(false, "Volume is not mounted. Cannot wipe.")

        return try {
            val root = File(mountPath)
            val deleted = deleteRecursive(root, keepRoot = true)
            FormatResult(true,
                if (deleted > 0)
                    "Cleared $deleted files/directories. Filesystem (${device.filesystem ?: "unknown"}) preserved."
                else
                    "Volume is already empty. Filesystem: ${device.filesystem ?: "unknown"}"
            )
        } catch (e: Exception) {
            FormatResult(false, "Wipe failed: ${e.message}")
        }
    }

    private fun deleteRecursive(file: File, keepRoot: Boolean): Int {
        if (!file.exists()) return 0
        var count = 0
        if (file.isDirectory) {
            val children = file.listFiles() ?: emptyArray()
            for (child in children) {
                val name = child.name
                // Skip special directories
                if (name == "lost+found" || name == "LOST.DIR" || name == "." || name == "..") continue
                count += deleteRecursive(child, keepRoot = false)
            }
        }
        if (!keepRoot && file.isFile) {
            if (file.delete()) count++
        }
        if (!keepRoot && file.isDirectory) {
            if (file.delete()) count++
        }
        return count
    }
}

data class FormatResult(val success: Boolean, val message: String)
