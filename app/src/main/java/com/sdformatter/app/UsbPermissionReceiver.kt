package com.sdformatter.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        val permission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

        if (device != null && permission) {
            MainActivity.onUsbPermissionGranted(device)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.sdformatter.app.USB_PERMISSION"

        fun createPendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION_USB_PERMISSION)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
