package com.sdformatter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.text.InputType
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
    private lateinit var shizukuHelper: ShizukuFormatHelper
    private lateinit var usbManager: UsbManager

    private var adbReady = false
    private var adbConnected = false
    private var adbPort: ShizukuFormatHelper.AdbPort? = null

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
        shizukuHelper = ShizukuFormatHelper(this)

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
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("DEPRECATION") registerReceiver(usbReceiver, filter)

        refreshDevices()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            refreshDevices()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) { }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        usbManager.requestPermission(device, UsbPermissionReceiver.createPendingIntent(this))
    }

    private fun refreshDevices() {
        lifecycleScope.launch {
            statusText.text = "Scanning..."
            statusText.visibility = View.VISIBLE

            val devices = withContext(Dispatchers.IO) {
                usbHelper.getStorageDevices()
            }

            adapter.submitList(devices)
            if (devices.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                deviceList.visibility = View.GONE
                statusText.text = "No USB storage devices found"
            } else {
                emptyView.visibility = View.GONE
                deviceList.visibility = View.VISIBLE
                val mounted = devices.count { it.mountPath != null }
                statusText.text = "${devices.size} device(s) found, $mounted mounted"
            }
        }
    }

    // ─── format dialog ─────────────────────────────────────────────

    private fun showFormatDialog(device: StorageDevice) {
        val options = mutableListOf<String>()
        options.add("Quick Wipe (delete all files, no special permissions)")

        val hasAdb = adbReady || shizukuHelper.extractAdb()
        if (hasAdb) {
            options.add("Format via ADB Shell (requires Wireless Debugging)")
        } else {
            options.add("Format via ADB Shell (install adb first)")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Format: ${device.label}")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> performQuickWipe(device)
                    1 -> if (hasAdb) startAdbFormatFlow(device)
                         else showAdbInstallGuide()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── quick wipe ───────────────────────────────────────────────

    private fun performQuickWipe(device: StorageDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Quick Wipe?")
            .setMessage("Delete all files on ${device.label}? The filesystem will be preserved.")
            .setPositiveButton("Wipe") { _, _ ->
                lifecycleScope.launch {
                    val pos = adapter.positionForDevice(device)
                    adapter.setFormatStatus(pos, "Wiping...")
                    val r = withContext(Dispatchers.IO) { usbHelper.wipeFiles(device) }
                    adapter.setFormatStatus(pos, null)
                    Snackbar.make(findViewById(android.R.id.content), r.message, Snackbar.LENGTH_LONG).show()
                    refreshDevices()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── ADB format flow ──────────────────────────────────────────

    private fun startAdbFormatFlow(device: StorageDevice) {
        lifecycleScope.launch {
            // Step 1: Ensure adb is extracted
            if (!shizukuHelper.extractAdb()) {
                Snackbar.make(findViewById(android.R.id.content), "Failed to extract adb", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            adbReady = true

            // Step 2: Check for existing connection
            adbConnected = shizukuHelper.isAdbShellReady
            adbPort = null

            if (adbConnected) {
                // Already connected – format directly
                showFilesystemPicker(device)
                return@launch
            }

            // Step 3: Scan for ports
            showOpenDebugSettingsPrompt(device)
        }
    }

    private fun showOpenDebugSettingsPrompt(device: StorageDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Wireless Debugging")
            .setMessage(
                "1. Open Wireless Debugging in Settings > Developer Options\n" +
                "2. Tap 'Pair device with pairing code'\n" +
                "3. Remember the 6-digit code shown\n\n" +
                "Then come back here and tap Next to scan for the port."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openWirelessDebuggingSettings()
                lifecycleScope.launch {
                    // Wait a moment for settings to open, then scan
                    kotlinx.coroutines.delay(1000)
                    scanForPortAndShowCodeDialog(device)
                }
            }
            .setNeutralButton("Already Open") { _, _ ->
                scanForPortAndShowCodeDialog(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openWirelessDebuggingSettings() {
        try {
            // Open developer options -> wireless debugging
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    private fun scanForPortAndShowCodeDialog(device: StorageDevice) {
        lifecycleScope.launch {
            statusText.text = "Scanning for ADB Wireless Debugging port..."
            Snackbar.make(findViewById(android.R.id.content), "Scanning for ADB port...", Snackbar.LENGTH_INDEFINITE).show()

            adbPort = withContext(Dispatchers.IO) { shizukuHelper.scanForAdb() }

            if (adbPort == null) {
                Snackbar.make(findViewById(android.R.id.content), "ADB port not found. Ensure Wireless Debugging is enabled.", Snackbar.LENGTH_LONG).show()
                return@launch
            }

            showPairingCodeDialog(device, adbPort!!)
        }
    }

    private fun showPairingCodeDialog(device: StorageDevice, port: ShizukuFormatHelper.AdbPort) {
        val input = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Enter Pairing Code")
            .setMessage("Port ${port.pairing} detected. Enter the 6-digit code from the pairing dialog.")
            .setView(input)
            .setPositiveButton("Pair & Format") { _, _ ->
                val code = input.text.toString().trim()
                if (code.length < 6) {
                    Snackbar.make(findViewById(android.R.id.content), "Enter a valid 6-digit code", Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch { performAdbPairing(device, port, code) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun performAdbPairing(device: StorageDevice, port: ShizukuFormatHelper.AdbPort, code: String) {
        statusText.text = "Pairing via ADB..."
        Snackbar.make(findViewById(android.R.id.content), "Pairing...", Snackbar.LENGTH_INDEFINITE).show()

        val pairResult = withContext(Dispatchers.IO) {
            shizukuHelper.pair(port.pairing, code)
        }

        if (!pairResult.success) {
            AdbResult("Pairing failed. Check the code and try again. ${pairResult.stderr}")
            return
        }

        Snackbar.make(findViewById(android.R.id.content), "Paired! Connecting...", Snackbar.LENGTH_INDEFINITE).show()

        val connectResult = withContext(Dispatchers.IO) {
            shizukuHelper.connect(port.service)
        }

        if (!connectResult.success) {
            AdbResult("Connection failed. ${connectResult.stderr}")
            return
        }

        adbConnected = true
        Snackbar.make(findViewById(android.R.id.content), "Connected! Ready to format.", Snackbar.LENGTH_SHORT).show()

        showFilesystemPicker(device)
    }

    private fun AdbResult(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
        statusText.text = "Error"
    }

    // ─── filesystem picker ─────────────────────────────────────────

    private fun showFilesystemPicker(device: StorageDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Filesystem")
            .setMessage("Format ${device.label} to:")
            .setItems(arrayOf("FAT32 (default)", "FAT16")) { _, which ->
                val fs = if (which == 0) "FAT32" else "FAT"
                lifecycleScope.launch {
                    performAdbFormat(device, fs)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── ADB format execution ──────────────────────────────────────

    private suspend fun performAdbFormat(device: StorageDevice, filesystem: String) {
        val pos = adapter.positionForDevice(device)
        adapter.setFormatStatus(pos, "Formatting to $filesystem...")
        statusText.text = "Formatting via adb shell..."

        val result = withContext(Dispatchers.IO) {
            val mountPath = device.mountPath
            if (mountPath != null) {
                val volId = shizukuHelper.resolveVolumeId(mountPath)
                if (volId != null) {
                    shizukuHelper.formatVolumeForce(volId)
                } else {
                    FormatResult(false, "Could not resolve volume ID")
                }
            } else {
                FormatResult(false, "Volume is not mounted")
            }
        }

        adapter.setFormatStatus(pos, null)
        Snackbar.make(findViewById(android.R.id.content), result.message, Snackbar.LENGTH_LONG).show()
        statusText.text = result.message

        if (result.success) {
            // Disconnect after formatting
            withContext(Dispatchers.IO) {
                adbPort?.let { shizukuHelper.disconnect(it.service) }
            }
            adbConnected = false
        }

        refreshDevices()
    }

    private fun showAdbInstallGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle("ADB Required")
            .setMessage("The bundled adb binary failed to extract. Please reinstall the app or report this as a bug.")
            .setPositiveButton("OK", null)
            .show()
    }

    // ─── adapter ──────────────────────────────────────────────────

    inner class DeviceAdapter(
        private val onFormatClick: (StorageDevice) -> Unit,
        private val onRequestPermission: (UsbDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        private var items: List<StorageDevice> = emptyList()
        private val formatStatuses = mutableMapOf<Int, String>()

        fun submitList(list: List<StorageDevice>) { items = list; formatStatuses.clear(); notifyDataSetChanged() }
        fun positionForDevice(device: StorageDevice): Int = items.indexOf(device)
        fun setFormatStatus(pos: Int, status: String?) {
            if (pos !in items.indices) return
            if (status != null) formatStatuses[pos] = status else formatStatuses.remove(pos)
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
                if (device.mountPath != null) append(" · Mounted") else append(" · Unmounted")
                device.filesystem?.let { append(" · $it") }
            }
            val status = formatStatuses[pos]
            if (status != null) {
                h.formatStatus.visibility = View.VISIBLE; h.formatStatus.text = status
                h.formatButton.isEnabled = false
            } else {
                h.formatStatus.visibility = View.GONE; h.formatButton.isEnabled = true
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
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
