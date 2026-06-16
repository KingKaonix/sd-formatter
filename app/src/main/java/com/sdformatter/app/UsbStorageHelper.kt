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
    val totalSize: Long,
    val isRemovable: Boolean,
    val usbDevice: UsbDevice?
)

class UsbStorageHelper(private val context: Context) {

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val storageManager: StorageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    /**
     * Main entry: returns all discoverable storage devices.
     * Merges info from StorageManager volumes and raw USB device enumeration.
     */
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
            devices.add(
                StorageDevice(
                    id = volume.uuid ?: volPath,
                    label = volume.getDescription(context),
                    mountPath = volPath,
                    blockDevice = resolveBlockDevice(volPath),
                    totalSize = try { info.totalSpace } catch (_: Exception) { 0L },
                    isRemovable = true,
                    usbDevice = null
                )
            )
            seen.add(volPath)
        }

        // 2. Raw USB devices — useful even if not mounted
        val usbMap = usbManager.deviceList ?: emptyMap()
        for ((_, usbDev) in usbMap) {
            val info = describeUsbDevice(usbDev)

            // try to find a mount point by probing /proc/self/mounts broadly
            val mountPath = findMountPathForUsb(usbDev, seen)

            devices.add(
                StorageDevice(
                    id = usbDev.deviceName,
                    label = usbDev.productName ?: "Unknown USB device",
                    mountPath = mountPath,
                    blockDevice = mountPath?.let { resolveBlockDevice(it) },
                    totalSize = mountPath?.let {
                        try { File(it).totalSpace } catch (_: Exception) { 0L }
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

    // ─── USB device helpers ───────────────────────────────────────

    private fun isMassStorageClass(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
        }
        return false
    }

    /** Human-readable description of what this USB device looks like. */
    private fun describeUsbDevice(device: UsbDevice): String {
        val sb = StringBuilder()
        sb.append("VID:${device.vendorId} PID:${device.productId}")
        if (device.productName != null) sb.append(" \"${device.productName}\"")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            sb.append(" [IF$i cls:${iface.interfaceClass} sub:${iface.interfaceSubclass} proto:${iface.interfaceProtocol}]")
        }
        return sb.toString()
    }

    /**
     * Try to find a mounted path for this USB device.
     * Strategy: scan /proc/self/mounts (and /proc/mounts) for any block device
     * that was *not* already claimed by a StorageManager volume.
     */
    private fun findMountPathForUsb(device: UsbDevice, alreadySeen: Set<String>): String? {
        val mounts = readMounts() ?: return null

        // prefer block devices that belong to USB storage first
        val candidates = mutableListOf<String>()

        for ((blockDev, mountPoint) in mounts) {
            if (mountPoint in alreadySeen) continue
            if (!mountPoint.startsWith("/mnt/") &&
                !mountPoint.startsWith("/storage/") &&
                mountPoint != "/") continue

            val lower = mountPoint.lowercase()
            val blower = blockDev.lowercase()

            // strong match: usb, otg, sd card keywords
            if ("usb" in lower || "otg" in lower || "sd" in lower ||
                "usb" in blower || "otg" in blower) {
                candidates.add(0, mountPoint)
            } else {
                candidates.add(mountPoint)
            }
        }

        // if nothing specific, take any removable-looking mount we haven't seen
        return candidates.firstOrNull()
    }

    private fun readMounts(): List<Pair<String, String>>? {
        // Try /proc/self/mounts first, then /proc/mounts
        for (path in listOf("/proc/self/mounts", "/proc/mounts")) {
            val f = File(path)
            if (!f.canRead()) continue
            try {
                val result = mutableListOf<Pair<String, String>>()
                BufferedReader(FileReader(f)).use { reader ->
                    for (line in reader.readLines()) {
                        val parts = line.split(" ")
                        if (parts.size >= 2) {
                            result.add(parts[0] to parts[1])
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            } catch (_: Exception) { }
        }
        return null
    }

    // ─── block device resolution ──────────────────────────────────

    private fun resolveBlockDevice(mountPath: String): String? {
        val mounts = readMounts() ?: return null
        for ((blockDev, mp) in mounts) {
            if (mp == mountPath) return blockDev
        }
        return null
    }

    // ─── root / format ────────────────────────────────────────────

    fun hasRootAccess(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            "uid=0" in out
        } catch (_: Exception) { false }
    }

    fun formatDevice(device: StorageDevice, filesystem: String): FormatResult {
        return if (hasRootAccess()) {
            formatWithRoot(device, filesystem)
        } else {
            formatWithStorageManager(device)
        }
    }

    private fun formatWithRoot(device: StorageDevice, filesystem: String): FormatResult {
        val blockDev = device.blockDevice
            ?: return FormatResult(false, "Block device not found")

        val fatFlag = when (filesystem) { "FAT" -> "-F 16"; else -> "-F 32" }

        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "umount", blockDev)).waitFor()

            val proc = Runtime.getRuntime().exec("su -c \"mkfs.vfat $fatFlag -I '$blockDev'\"")
            val code = proc.waitFor()

            if (code == 0) FormatResult(true, "Format successful")
            else {
                val err = proc.errorStream.bufferedReader().readText()
                FormatResult(false, "mkfs.vfat failed: $err")
            }
        } catch (e: Exception) {
            FormatResult(false, "Format failed: ${e.message}")
        }
    }

    private fun formatWithStorageManager(device: StorageDevice): FormatResult {
        return try {
            val volumes = storageManager.storageVolumes ?: emptyList()
            val vol = volumes.firstOrNull { resolveVolumePath(it) == device.mountPath }
            if (vol != null) {
                vol.javaClass.getMethod("format", Context::class.java).invoke(vol, context)
                FormatResult(true, "Format initiated. Reconnect device afterwards.")
            } else {
                FormatResult(false, "Volume not found. Root required.")
            }
        } catch (_: NoSuchMethodException) {
            FormatResult(false, "Format not supported on this Android version. Root required.")
        } catch (e: Exception) {
            FormatResult(false, "Format failed: ${e.message}")
        }
    }
}

data class FormatResult(val success: Boolean, val message: String)
