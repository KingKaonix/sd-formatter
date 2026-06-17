package com.sdformatter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val SHIZUKU_PERMISSION_REQUEST = 1001

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DeviceAdapter
    private lateinit var usbHelper: UsbStorageHelper
    private lateinit var shizukuHelper: ShizukuFormatHelper
    private lateinit var usbManager: UsbManager

    private var shizukuAvailable = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED)
                            "USB device connected" else "USB device disconnected",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    refreshDevices()
                }
            }
        }
    }

    private val shizukuBinderReceiver = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            shizukuAvailable = shizukuHelper.isAvailable
            if (!shizukuHelper.hasPermission) {
                shizukuHelper.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        }

        override fun onBinderDead() {
            shizukuAvailable = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: com.google.android.material.appbar.MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        emptyView = findViewById(R.id.emptyView)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbHelper = UsbStorageHelper(this)
        shizukuHelper = ShizukuFormatHelper(this)

        adapter = DeviceAdapter(
            onFormatClick = { device -> showFormatDialog(device) },
            onRequestPermission = { device -> requestUsbPermission(device) }
        )
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

        // USB plug/unplug events
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }

        // Shizuku binder listener
        Shizuku.addBinderReceivedListener(shizukuBinderReceiver)
        shizukuAvailable = shizukuHelper.isAvailable
        if (shizukuAvailable && !shizukuHelper.hasPermission) {
            shizukuHelper.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        }

        refreshDevices()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            item.isEnabled = false
            refreshDevices()
            item.isEnabled = true
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) { }
        Shizuku.removeBinderReceivedListener(shizukuBinderReceiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Shizuku permission result handled internally
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val pi = UsbPermissionReceiver.createPendingIntent(this)
        usbManager.requestPermission(device, pi)
    }

    private fun refreshDevices() {
        lifecycleScope.launch {
            statusText.text = "Scanning..."
            statusText.visibility = View.VISIBLE

            val result = withContext(Dispatchers.IO) {
                usbHelper.getStorageDevices()
            }

            statusText.visibility = View.GONE

            if (result.isEmpty()) {
                deviceList.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                deviceList.visibility = View.VISIBLE
                adapter.submitList(result)
            }
        }
    }

    private fun showFormatDialog(device: StorageDevice) {
        val usbDev = device.usbDevice
        if (usbDev != null && !usbManager.hasPermission(usbDev)) {
            requestUsbPermission(usbDev)
            Snackbar.make(findViewById(android.R.id.content),
                "USB permission requested — try again after granting",
                Snackbar.LENGTH_SHORT).show()
            return
        }

        val hasRoot = usbHelper.hasRootAccess()
        val isMounted = device.mountPath != null

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Non-root options (always available if mounted)
        if (isMounted) {
            items.add("Quick wipe — delete all files (no root)")
            actions.add { performWipe(device) }

            items.add("Try system format — Android built-in formatter (no root)")
            actions.add { performSystemFormat(device) }
        }

        // Shizuku options
        if (shizukuAvailable) {
            val label = shizukuHelper.modeLabel
            items.add("Format via $label")
            actions.add { performShizukuFormat(device) }

            if (shizukuHelper.isRootMode && device.blockDevice != null) {
                items.add("Shizuku: format to FAT32")
                actions.add { performShizukuMkfs(device, "FAT32") }
                items.add("Shizuku: format to FAT")
                actions.add { performShizukuMkfs(device, "FAT") }
            }
        }

        // Root options
        if (hasRoot) {
            items.add("Root: format to FAT32")
            actions.add { performRootFormat(device, "FAT32") }
            items.add("Root: format to FAT")
            actions.add { performRootFormat(device, "FAT") }
        }

        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(device.label)
                .setMessage(
                    if (!isMounted) "Volume is not mounted. Try reconnecting the device."
                    else "No format options available."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Build device info for the dialog subtitle
        val infoLine = buildString {
            append("${device.label}  ·  ${formatSize(device.totalSize)}")
            device.filesystem?.let { append("  ·  $it") }
        }

        if (items.size == 1) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Format?")
                .setMessage("$infoLine\n\n${items[0]}")
                .setPositiveButton("Continue") { _, _ -> actions[0]() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Format ${device.label}")
                .setMessage(infoLine)
                .setItems(items.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─── format actions ──────────────────────────────────────────

    private fun performWipe(device: StorageDevice) {
        lifecycleScope.launch {
            val pos = adapter.positionForDevice(device)
            adapter.setFormatStatus(pos, "Wiping...")
            val result = withContext(Dispatchers.IO) {
                usbHelper.formatWithoutRoot(device)
            }
            adapter.setFormatStatus(pos, null)
            Snackbar.make(findViewById(android.R.id.content),
                result?.message ?: "Wipe completed", Snackbar.LENGTH_LONG).show()
            refreshDevices()
        }
    }

    private fun performSystemFormat(device: StorageDevice) {
        lifecycleScope.launch {
            val pos = adapter.positionForDevice(device)
            adapter.setFormatStatus(pos, "Requesting system format...")
            val result = withContext(Dispatchers.IO) {
                usbHelper.formatWithoutRoot(device)
            }
            adapter.setFormatStatus(pos, null)
            if (result != null && result.success) {
                Snackbar.make(findViewById(android.R.id.content), result.message, Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(findViewById(android.R.id.content),
                    "System format unavailable. Try quick wipe instead.", Snackbar.LENGTH_LONG).show()
            }
            refreshDevices()
        }
    }

    private fun performShizukuFormat(device: StorageDevice) {
        lifecycleScope.launch {
            val pos = adapter.positionForDevice(device)
            adapter.setFormatStatus(pos, "Shizuku: formatting...")

            val result = if (shizukuHelper.isRootMode && device.blockDevice != null) {
                withContext(Dispatchers.IO) {
                    shizukuHelper.formatWithMkfs(device.blockDevice, "FAT32")
                }
            } else if (device.mountPath != null) {
                withContext(Dispatchers.IO) {
                    val volId = shizukuHelper.resolveVolumeId(device.mountPath)
                    if (volId != null) shizukuHelper.formatVolume(volId)
                    else FormatResult(false, "Could not resolve volume ID")
                }
            } else {
                FormatResult(false, "Volume not mounted")
            }

            adapter.setFormatStatus(pos, null)
            Snackbar.make(findViewById(android.R.id.content),
                result.message, Snackbar.LENGTH_LONG).show()
            refreshDevices()
        }
    }

    private fun performShizukuMkfs(device: StorageDevice, fs: String) {
        lifecycleScope.launch {
            val pos = adapter.positionForDevice(device)
            adapter.setFormatStatus(pos, "Shizuku: formatting to $fs...")
            val result = withContext(Dispatchers.IO) {
                shizukuHelper.formatWithMkfs(device.blockDevice ?: "", fs)
            }
            adapter.setFormatStatus(pos, null)
            Snackbar.make(findViewById(android.R.id.content),
                result.message, Snackbar.LENGTH_LONG).show()
            refreshDevices()
        }
    }

    private fun performRootFormat(device: StorageDevice, fs: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Format to $fs?")
            .setMessage("Device: ${device.label}\nFilesystem: $fs\n\nALL DATA will be erased.")
            .setPositiveButton("Format") { _, _ ->
                lifecycleScope.launch {
                    val pos = adapter.positionForDevice(device)
                    adapter.setFormatStatus(pos, "Formatting to $fs...")
                    val result = withContext(Dispatchers.IO) {
                        usbHelper.formatWithRoot(device, fs)
                    }
                    adapter.setFormatStatus(pos, null)
                    Snackbar.make(findViewById(android.R.id.content),
                        result.message, Snackbar.LENGTH_LONG).show()
                    refreshDevices()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── adapter ──────────────────────────────────────────────────

    inner class DeviceAdapter(
        private val onFormatClick: (StorageDevice) -> Unit,
        private val onRequestPermission: (UsbDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var items: List<StorageDevice> = emptyList()
        private val formatStatuses = mutableMapOf<Int, String>()

        fun submitList(list: List<StorageDevice>) {
            items = list; formatStatuses.clear(); notifyDataSetChanged()
        }

        fun positionForDevice(device: StorageDevice): Int = items.indexOf(device)

        fun setFormatStatus(pos: Int, status: String?) {
            if (pos !in items.indices) return
            if (status != null) formatStatuses[pos] = status
            else formatStatuses.remove(pos)
            notifyItemChanged(pos)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_usb_device, parent, false)
        )

        override fun onBindViewHolder(h: ViewHolder, pos: Int) {
            val device = items[pos]

            h.label.text = device.label
            h.info.text = buildString {
                append(formatSize(device.totalSize))
                if (device.mountPath != null) append(" · Mounted")
                else append(" · Unmounted")
                device.filesystem?.let { append(" · $it") }
                if (shizukuAvailable) append(" · S")
            }

            val status = formatStatuses[pos]
            if (status != null) {
                h.formatStatus.visibility = View.VISIBLE
                h.formatStatus.text = status
                h.formatButton.isEnabled = false
            } else {
                h.formatStatus.visibility = View.GONE
                h.formatButton.isEnabled = true
            }

            h.formatButton.setOnClickListener { onFormatClick(device) }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.deviceLabel)
            val info: TextView = itemView.findViewById(R.id.deviceInfo)
            val formatButton: Button = itemView.findViewById(R.id.formatButton)
            val formatStatus: TextView = itemView.findViewById(R.id.formatStatus)
        }
    }

    companion object {
        @JvmStatic fun onUsbPermissionGranted(device: UsbDevice) {}
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
