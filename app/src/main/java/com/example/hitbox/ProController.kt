package com.example.hitbox

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.concurrent.Executors

/** Emulates a Nintendo Switch Pro Controller over the Android Bluetooth HID Device profile. */
@SuppressLint("MissingPermission")
class ProController(private val ctx: Context) {
    var onStatus: (String) -> Unit = {}
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    @Volatile private var b2 = 0; @Volatile private var b3 = 0; @Volatile private var b4 = 0
    private var timer = 0
    private val thread = HandlerThread("hid").also { it.start() }
    private val h = Handler(thread.looper)
    @Volatile private var streaming = false

    // Pro Controller HID report descriptor
    private val DESC = ("05 01 15 00 09 04 A1 01 85 30 05 01 05 09 19 01 29 0A 15 00 25 01 75 01 95 0A 55 00 65 00 81 02 " +
        "05 09 19 0B 29 0E 15 00 25 01 75 01 95 04 81 02 75 01 95 02 81 03 0B 01 00 01 00 A1 00 0B 30 00 01 00 0B 31 00 01 00 " +
        "0B 32 00 01 00 0B 35 00 01 00 15 00 27 FF FF 00 00 75 10 95 04 81 02 C0 0B 39 00 01 00 15 00 25 08 35 00 46 3B 01 65 14 " +
        "75 04 95 01 81 02 05 09 19 0F 29 12 15 00 25 01 75 01 95 04 81 02 75 08 95 34 81 03 06 00 FF 85 21 09 01 75 08 95 30 81 02 " +
        "85 81 09 02 75 08 95 30 81 02 85 01 09 03 75 08 95 30 91 02 85 10 09 04 75 08 95 30 91 02 85 80 09 05 75 08 95 30 91 02 " +
        "85 82 09 06 75 08 95 30 91 02 C0").split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // Fake SPI flash: 0xFF everywhere except stick calibration + colours
    private val spi = ByteArray(0x10100) { 0xFF.toByte() }.also { s ->
        val cal = intArrayOf(0x00, 0x07, 0x70, 0x00, 0x08, 0x80, 0x00, 0x07, 0x70)
        for (base in intArrayOf(0x603D, 0x6046)) cal.forEachIndexed { i, v -> s[base + i] = v.toByte() }
        intArrayOf(0x32, 0x32, 0x32, 0xFF, 0xFF, 0xFF).forEachIndexed { i, v -> s[0x6050 + i] = v.toByte() }
    }

    fun init() {
        val ad = ctx.getSystemService(BluetoothManager::class.java).adapter
        ad.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val d = proxy as BluetoothHidDevice; hid = d
                val sdp = BluetoothHidDeviceAppSdpSettings("Pro Controller", "Gamepad", "Nintendo",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD, DESC)
                d.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), cb)
            }
            override fun onServiceDisconnected(p: Int) { hid = null; onStatus("HID service lost") }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun connect(d: BluetoothDevice) { onStatus("Connecting to ${d.name}…"); hid?.connect(d) }

    fun setState(j: JSONObject) {
        fun f(k: String) = j.optInt(k, 0) == 1
        var r = 0; var s = 0; var l = 0
        if (f("Y")) r = r or 1; if (f("X")) r = r or 2; if (f("B")) r = r or 4; if (f("A")) r = r or 8
        if (f("R")) r = r or 0x40; if (f("ZR")) r = r or 0x80
        if (f("select")) s = s or 1; if (f("start")) s = s or 2; if (f("home")) s = s or 0x10
        if (f("Down")) l = l or 1; if (f("Up")) l = l or 2; if (f("Right")) l = l or 4; if (f("Left")) l = l or 8
        if (f("L")) l = l or 0x40; if (f("ZL")) l = l or 0x80
        b2 = r; b3 = s; b4 = l
    }

    @Synchronized private fun input(): ByteArray {
        val r = ByteArray(48)
        r[0] = (timer++ and 0xFF).toByte(); r[1] = 0x90.toByte()
        r[2] = b2.toByte(); r[3] = b3.toByte(); r[4] = b4.toByte()
        for (o in intArrayOf(5, 8)) { r[o] = 0x00; r[o + 1] = 0x08; r[o + 2] = 0x80.toByte() }
        r[11] = 0x80.toByte()
        return r
    }

    private fun reply(ack: Int, sub: Int, data: ByteArray = ByteArray(0)) {
        val r = input(); r[13] = ack.toByte(); r[14] = sub.toByte()
        data.copyInto(r, 15, 0, minOf(data.size, 33))
        hid?.sendReport(host, 0x21, r)
    }

    private fun handle(id: Int, d: ByteArray) {
        if (id != 0x01 || d.size < 10) return
        val sub = d[9].toInt() and 0xFF
        val a = d.copyOfRange(10, maxOf(10, d.size))
        when (sub) {
            0x02 -> reply(0x82, sub, byteArrayOf(0x03, 0x48, 0x03, 0x02, 0x7C, 0xBB.toByte(), 0x8A.toByte(), 0x11, 0x22, 0x33, 0x01, 0x01))
            0x04 -> reply(0x83, sub)
            0x10 -> if (a.size >= 5) {
                val addr = (a[0].toInt() and 255) or ((a[1].toInt() and 255) shl 8)
                val n = minOf(a[4].toInt() and 255, 0x1D)
                val out = ByteArray(5 + n); a.copyInto(out, 0, 0, 5); spi.copyInto(out, 5, addr, addr + n)
                reply(0x90, sub, out)
            }
            else -> reply(0x80, sub)   // 0x03 mode, 0x08, 0x30 lights, 0x38, 0x40 IMU, 0x48 rumble, ...
        }
    }

    private fun loop() { if (!streaming) return
        host?.let { hid?.sendReport(it, 0x30, input()) }
        h.postDelayed({ loop() }, 15)
    }

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(p: BluetoothDevice?, registered: Boolean) {
            onStatus(if (registered) "Ready – pick your Switch" else "Not registered")
        }
        override fun onConnectionStateChanged(d: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = d; streaming = true; h.post { loop() }; onStatus("Connected: ${d.name}") }
                BluetoothProfile.STATE_DISCONNECTED -> { streaming = false; host = null; onStatus("Disconnected") }
            }
        }
        override fun onInterruptData(d: BluetoothDevice, id: Byte, data: ByteArray) = handle(id.toInt(), data)
        override fun onSetReport(d: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = handle(id.toInt(), data)
    }
}
