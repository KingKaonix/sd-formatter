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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DeviceAdapter
    private lateinit var usbHelper: UsbStorageHelper
    private lateinit var usbManager: UsbManager

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

        adapter = DeviceAdapter(
            onFormatClick = { device -> showFormatDialog(device) },
            onRequestPermission = { device -> requestUsbPermission(device) }
        )
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

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
        // Check USB permission first if this is a raw USB device
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

        // Build the dialog content
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (isMounted) {
            items.add("Quick wipe — delete all files (no root, preserves filesystem)")
            actions.add { performWipe(device) }

            items.add("Try system format — uses Android's built-in formatter (no root)")
            actions.add { performSystemFormat(device) }
        }

        if (hasRoot) {
            items.add("Full format to FAT32 — requires root")
            actions.add { performRootFormat(device, "FAT32") }
            items.add("Full format to FAT — requires root")
            actions.add { performRootFormat(device, "FAT") }
        }

        if (items.isEmpty()) {
            // No action possible
            MaterialAlertDialogBuilder(this)
                .setTitle(device.label)
                .setMessage(
                    if (!isMounted) "Volume is not mounted. Try reconnecting the device."
                    else "No format options available for this device."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Show single-item as direct action, multiple as chooser
        if (items.size == 1) {
            val msg = buildString {
                appendLine("Device: ${device.label}")
                appendLine("Size: ${formatSize(device.totalSize)}")
                if (device.filesystem != null) appendLine("Current filesystem: ${device.filesystem}")
                if (isMounted) appendLine("Mounted at: ${device.mountPath}")
                appendLine()
                append(items[0])
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Format ${device.label}?")
                .setMessage(msg)
                .setPositiveButton("Continue") { _, _ -> actions[0]() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Format ${device.label}")
                .setItems(items.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun performWipe(device: StorageDevice) {
        lifecycleScope.launch {
            val pos = adapter.positionForDevice(device)
            adapter.setFormatStatus(pos, "Wiping...")
            val result = withContext(Dispatchers.IO) {
                usbHelper.formatWithoutRoot(device)
            }
            adapter.setFormatStatus(pos, null)
            Snackbar.make(findViewById(android.R.id.content),
                result?.message ?: "Wipe completed",
                Snackbar.LENGTH_LONG).show()
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
                Snackbar.make(findViewById(android.R.id.content),
                    result.message, Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(findViewById(android.R.id.content),
                    "System format unavailable. Try quick wipe instead.",
                    Snackbar.LENGTH_LONG).show()
            }
            refreshDevices()
        }
    }

    private fun performRootFormat(device: StorageDevice, fs: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Format to $fs?")
            .setMessage(
                "Device: ${device.label}\n" +
                "Filesystem: $fs\n\n" +
                "ALL DATA will be erased. This cannot be undone."
            )
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
                if (device.usbDevice != null) append(" · USB device")
                device.filesystem?.let { append(" · $it") }
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
        @JvmStatic
        fun onUsbPermissionGranted(device: UsbDevice) {}
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
