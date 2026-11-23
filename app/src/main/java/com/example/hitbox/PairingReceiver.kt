package com.example.hitbox

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        try {
            device.setPairingConfirmation(true)
            abortBroadcast()
        } catch (e: SecurityException) {
        }
    }

    companion object {
        fun register(context: Context): PairingReceiver {
            val receiver = PairingReceiver()
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
            return receiver
        }
    }
}
