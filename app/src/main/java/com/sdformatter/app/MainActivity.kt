package com.sdformatter.app

import android.app.AlertDialog
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var deviceList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DeviceAdapter
    private lateinit var usbHelper: UsbStorageHelper

    private var devices: List<StorageDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        emptyView = findViewById(R.id.emptyView)

        usbHelper = UsbStorageHelper(this)

        adapter = DeviceAdapter { device ->
            showFormatOptionsDialog(device)
        }
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

        refreshDevices()
    }

    private fun refreshDevices() {
        lifecycleScope.launch {
            statusText.text = getString(R.string.scanning)
            statusText.visibility = View.VISIBLE

            val result = withContext(Dispatchers.IO) {
                usbHelper.getStorageDevices()
            }
            devices = result

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
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_format_options, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.format_confirm_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.optionFat)
            .setOnClickListener {
                dialog.dismiss()
                showFormatConfirmDialog(device, "FAT")
            }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.optionFat32)
            .setOnClickListener {
                dialog.dismiss()
                showFormatConfirmDialog(device, "FAT32")
            }
    }

    private fun showFormatConfirmDialog(device: StorageDevice, filesystem: String) {
        val hasRoot = usbHelper.hasRootAccess()
        val message = if (hasRoot) {
            getString(R.string.format_confirm_message, device.label)
        } else {
            getString(R.string.format_confirm_message, device.label) + "\n\n" +
                getString(R.string.root_required)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.format_confirm_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.format)) { _, _ ->
                performFormat(device, filesystem, adapter.positionForDevice(device))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performFormat(device: StorageDevice, filesystem: String, position: Int) {
        lifecycleScope.launch {
            adapter.setFormatStatus(position, getString(R.string.formatting_in_progress))

            val result = withContext(Dispatchers.IO) {
                usbHelper.formatDevice(device, filesystem)
            }

            adapter.setFormatStatus(position, null)

            if (result.success) {
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity,
                    getString(R.string.format_failed, result.message),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class DeviceAdapter(
        private val onFormatClick: (StorageDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var items: List<StorageDevice> = emptyList()
        private val formatStatuses = mutableMapOf<Int, String>()

        fun submitList(list: List<StorageDevice>) {
            items = list
            formatStatuses.clear()
            notifyDataSetChanged()
        }

        fun positionForDevice(device: StorageDevice): Int = items.indexOf(device)

        fun setFormatStatus(position: Int, status: String?) {
            if (position in items.indices) {
                if (status != null) {
                    formatStatuses[position] = status
                } else {
                    formatStatuses.remove(position)
                }
                notifyItemChanged(position)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_usb_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = items[position]
            val hasRoot = usbHelper.hasRootAccess()

            holder.label.text = device.label
            holder.info.text = formatSize(device.totalSize)
            holder.formatButton.isEnabled = true

            val status = formatStatuses[position]
            if (status != null) {
                holder.formatStatus.visibility = View.VISIBLE
                holder.formatStatus.text = status
                holder.formatButton.isEnabled = false
            } else {
                holder.formatStatus.visibility = View.GONE
            }

            holder.formatButton.setOnClickListener {
                onFormatClick(device)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.deviceLabel)
            val info: TextView = itemView.findViewById(R.id.deviceInfo)
            val formatButton: Button = itemView.findViewById(R.id.formatButton)
            val formatStatus: TextView = itemView.findViewById(R.id.formatStatus)
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "Unknown size"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unitIndex = 0
            while (value >= 1024 && unitIndex < units.size - 1) {
                value /= 1024
                unitIndex++
            }
            return "%.1f %s".format(value, units[unitIndex])
        }
    }
}
