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
import java.io.File

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

    private fun StorageVolume.getVolumePath(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            directory?.absolutePath
        } else {
            @Suppress("DEPRECATION")
            path
        }
    }

    private fun isVolumeMounted(volume: StorageVolume, volPath: String?): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.state == Environment.MEDIA_MOUNTED
        } else if (volPath != null) {
            Environment.getExternalStorageState(File(volPath)) == Environment.MEDIA_MOUNTED
        } else {
            false
        }
    }

    fun getStorageDevices(): List<StorageDevice> {
        val devices = mutableListOf<StorageDevice>()

        val volumes = storageManager.storageVolumes ?: emptyList()
        for (volume in volumes) {
            if (!volume.isRemovable) continue
            val volPath = volume.getVolumePath()
            if (!isVolumeMounted(volume, volPath)) continue
            if (volPath == null) continue

            val file = File(volPath)
            val total = try {
                file.totalSpace
            } catch (e: Exception) {
                0L
            }
            devices.add(
                StorageDevice(
                    id = volume.uuid ?: volPath,
                    label = volume.getDescription(context),
                    mountPath = volPath,
                    blockDevice = resolveBlockDevice(volPath),
                    totalSize = total,
                    isRemovable = true,
                    usbDevice = null
                )
            )
        }

        // Enumerate USB mass storage devices not yet covered
        val usbDeviceMap = usbManager.deviceList ?: emptyMap()
        for ((_, device) in usbDeviceMap) {
            if (!isMassStorageDevice(device)) continue
            val alreadyListed = devices.any { it.label.contains(device.productName ?: "") }
            if (!alreadyListed) {
                devices.add(
                    StorageDevice(
                        id = device.deviceName,
                        label = device.productName ?: "USB Mass Storage",
                        mountPath = findMountPathForUsb(device),
                        blockDevice = findBlockDeviceForUsb(device),
                        totalSize = 0L,
                        isRemovable = true,
                        usbDevice = device
                    )
                )
            }
        }

        return devices
    }

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface: UsbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
        }
        return false
    }

    private fun resolveBlockDevice(mountPath: String): String? {
        return try {
            val mounts = File("/proc/self/mounts").readLines()
            for (line in mounts) {
                val parts = line.split(" ")
                if (parts.size >= 2 && parts[1] == mountPath) {
                    return parts[0]
                }
            }
            val mounts2 = File("/proc/mounts").readLines()
            for (line in mounts2) {
                val parts = line.split(" ")
                if (parts.size >= 2 && parts[1] == mountPath) {
                    return parts[0]
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findMountPathForUsb(device: UsbDevice): String? {
        val expectedName = device.productName ?: return null
        return try {
            for (line in File("/proc/self/mounts").readLines()) {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    val mountPath = parts[1]
                    if (mountPath.contains(expectedName, ignoreCase = true) ||
                        mountPath.contains("usb", ignoreCase = true) ||
                        mountPath.contains("otg", ignoreCase = true)) {
                        if (mountPath.startsWith("/mnt/") || mountPath.startsWith("/storage/")) {
                            return mountPath
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findBlockDeviceForUsb(device: UsbDevice): String? {
        val expectedName = device.productName ?: return null
        return try {
            for (line in File("/proc/self/mounts").readLines()) {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    val mountPath = parts[1]
                    if (mountPath.contains(expectedName, ignoreCase = true) ||
                        mountPath.contains("usb", ignoreCase = true) ||
                        mountPath.contains("otg", ignoreCase = true)) {
                        if (mountPath.startsWith("/mnt/") || mountPath.startsWith("/storage/")) {
                            return parts[0]
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun formatDevice(device: StorageDevice, filesystem: String): FormatResult {
        return if (hasRootAccess()) {
            formatWithRoot(device, filesystem)
        } else {
            formatWithStorageManager(device)
        }
    }

    private fun formatWithRoot(device: StorageDevice, filesystem: String): FormatResult {
        val blockDev = device.blockDevice ?: return FormatResult(false, "Block device not found")
        val fatFlags = when (filesystem) {
            "FAT" -> "-F 16"
            else -> "-F 32"
        }

        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "umount", blockDev)).waitFor()

            val cmd = "su -c \"mkfs.vfat $fatFlags -I '$blockDev'\""
            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                FormatResult(true, "Format successful")
            } else {
                val error = process.errorStream.bufferedReader().readText()
                FormatResult(false, "mkfs.vfat failed: $error")
            }
        } catch (e: Exception) {
            FormatResult(false, "Format failed: ${e.message}")
        }
    }

    private fun formatWithStorageManager(device: StorageDevice): FormatResult {
        return try {
            val volumes = storageManager.storageVolumes ?: emptyList()
            val targetVolume = volumes.find { it.getVolumePath() == device.mountPath }
            if (targetVolume != null) {
                val clazz = targetVolume.javaClass
                val formatMethod = clazz.getMethod("format", Context::class.java)
                formatMethod.invoke(targetVolume, context)
                FormatResult(true, "Format initiated via system. Reconnect device afterwards.")
            } else {
                FormatResult(false, "Volume not found. Root may be required for this operation.")
            }
        } catch (e: NoSuchMethodException) {
            FormatResult(false, "Direct format not supported on this Android version. Root required.")
        } catch (e: Exception) {
            FormatResult(false, "Format failed: ${e.message}")
        }
    }
}

data class FormatResult(val success: Boolean, val message: String)
