package com.example.hitbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : Activity() {
    private lateinit var pc: ProController
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        pc = ProController(this)
        val root = FrameLayout(this)
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            setBackgroundColor(Color.BLACK)
            addJavascriptInterface(object { @JavascriptInterface fun sendState(j: String) { pc.setState(JSONObject(j)) } }, "Android")
            loadUrl("file:///android_asset/index.html")
        }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "Bluetooth off" }
        val btn = Button(this).apply { text = "Bluetooth"; setOnClickListener { menu() } }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(btn); addView(status) }
        root.addView(wv); root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
        pc.onStatus = { s -> runOnUiThread { status.text = s } }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != 0)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        else pc.init()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.all { it == 0 }) pc.init() else status.text = "Bluetooth permission denied"
    }

    private fun menu() {
        val ad = getSystemService(BluetoothManager::class.java).adapter
        val devs: List<BluetoothDevice> = ad.bondedDevices.toList()
        val items = devs.map { (it.name ?: it.address) + if (it.bondState == BluetoothDevice.BOND_BONDED) " (paired)" else "" } +
                listOf("➕ Pair new device (Bluetooth settings)", "👁 Make phone discoverable")
        AlertDialog.Builder(this).setTitle("Connect to Switch").setItems(items.toTypedArray()) { _, i ->
            when {
                i < devs.size -> pc.connect(devs[i])
                i == devs.size -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                else -> startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            }
        }.show()
    }
}
