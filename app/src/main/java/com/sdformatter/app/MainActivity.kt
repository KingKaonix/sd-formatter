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
            onFormatClick = { device -> showFormatOptionsDialog(device) },
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

    private fun showFormatOptionsDialog(device: StorageDevice) {
        val hasRoot = usbHelper.hasRootAccess()

        if (!hasRoot) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Format")
                .setMessage("Root access not available.\n\n" +
                    "The app will try the system format API, which formats to the " +
                    "device default filesystem (not guaranteed FAT/FAT32).\n\n" +
                    "For full FAT/FAT32 control, root is required.")
                .setPositiveButton("Continue anyway") { _, _ ->
                    performFormat(device, "FAT32", adapter.positionForDevice(device))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_format_options, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Select filesystem")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .show()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.optionFat)
            .setOnClickListener { dialog.dismiss(); showConfirmDialog(device, "FAT") }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.optionFat32)
            .setOnClickListener { dialog.dismiss(); showConfirmDialog(device, "FAT32") }
    }

    private fun showConfirmDialog(device: StorageDevice, fs: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Format SD Card?")
            .setMessage("This will erase ALL data on ${device.label}.\n" +
                "Filesystem: $fs\n\nThis cannot be undone.")
            .setPositiveButton("Format") { _, _ ->
                performFormat(device, fs, adapter.positionForDevice(device))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performFormat(device: StorageDevice, fs: String, pos: Int) {
        lifecycleScope.launch {
            adapter.setFormatStatus(pos, "Formatting...")
            val result = withContext(Dispatchers.IO) {
                usbHelper.formatDevice(device, fs)
            }

            if (result.success) {
                adapter.setFormatStatus(pos, "Done")
                Snackbar.make(findViewById(android.R.id.content), result.message, Snackbar.LENGTH_LONG).show()
            } else {
                adapter.setFormatStatus(pos, null)
                Snackbar.make(findViewById(android.R.id.content),
                    "Failed: ${result.message}", Snackbar.LENGTH_LONG).show()
            }
        }
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
            val hasRoot = usbHelper.hasRootAccess()

            h.label.text = device.label
            h.info.text = buildString {
                append(formatSize(device.totalSize))
                if (device.mountPath != null) append(" · Mounted")
                else append(" · Unmounted")
                if (device.usbDevice != null) append(" · USB device")
                if (!hasRoot) append(" · No root")
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

            h.formatButton.setOnClickListener {
                val usbDev = device.usbDevice
                if (usbDev != null && !usbManager.hasPermission(usbDev)) {
                    onRequestPermission(usbDev)
                    Snackbar.make(
                        h.itemView,
                        "USB permission requested — tap Format again after granting",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                onFormatClick(device)
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.deviceLabel)
            val info: TextView = itemView.findViewById(R.id.deviceInfo)
            val formatButton: Button = itemView.findViewById(R.id.formatButton)
            val formatStatus: TextView = itemView.findViewById(R.id.formatStatus)
        }
    }

    // ─── called from UsbPermissionReceiver ────────────────────────

    companion object {
        @JvmStatic
        fun onUsbPermissionGranted(device: UsbDevice) {
            // triggers refresh on next interaction
        }
    }

    // ─── utils ────────────────────────────────────────────────────

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
