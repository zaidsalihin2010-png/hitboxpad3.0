package com.example.hitbox

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.Arrays
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class ProController(private val ctx: Context) {
    var onStatus: (String) -> Unit = {}

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var adapter: BluetoothAdapter? = null

    private val thread = HandlerThread("hid").also { it.start() }
    private val h = Handler(thread.looper)
    @Volatile private var fullMode = false
    @Volatile private var streaming = false

    private val DESC = hexToBytes(
        "05010905a1010601ff852109217508953081028530093075089530810285310931750896690181028532" +
        "0932750896690181028533093375089669018102853f0509190129101500250175019510810205010939" +
        "1500250775049501814205097504950181010501093009310933093416000027ffff0000751095048102" +
        "0601ff850109017508953091028510091075089530910285110911750895309102851209127508953091" +
        "02c0"
    )
    private val SUBCLASS: Byte = 0x08
    private val HID_NAME = "Wireless Gamepad"
    private val HID_DESCRIPTION = "Gamepad"
    private val HID_PROVIDER = "Nintendo"
    private val BT_DEVICE_NAME = "Pro Controller"

    private val BUTTON_REPORT: Byte = 0x3F
    private val SUBCOMMAND_REPLY_REPORT: Byte = 0x21
    private val FULL_BUTTON_REPORT: Byte = 0x30
    private val REQUEST_RUMBLE_AND_SUBCOMMAND: Byte = 0x01
    private val REQUEST_RUMBLE_ONLY: Byte = 0x10
    private val REQUEST_NFC_IR_MCU: Byte = 0x11

    private val REQUEST_DEVICE_INFO: Byte = 0x02
    private val REQUEST_SET_SHIPMENT: Byte = 0x08
    private val REQUEST_INPUT_REPORT_MODE: Byte = 0x03
    private val REQUEST_SPI_FLASH_READ: Byte = 0x10
    private val REQUEST_SET_PLAYER_LIGHTS: Byte = 0x30
    private val ACK: Byte = 0x80.toByte()

    private val SIMPLE_HID_MODE: Byte = 0x3F
    private val STANDARD_FULL_MODE: Byte = 0x30

    private val DOWN_BIT = 0x01; private val RIGHT_BIT = 0x02
    private val LEFT_BIT = 0x04; private val UP_BIT = 0x08
    private val SL_BIT = 0x10; private val SR_BIT = 0x20
    private val MINUS_BIT = 0x01; private val PLUS_BIT = 0x02
    private val LEFT_STICK_BIT = 0x04; private val RIGHT_STICK_BIT = 0x08
    private val HOME_BIT = 0x10; private val CAPTURE_BIT = 0x20
    private val L_R_BIT = 0x40; private val ZL_ZR_BIT = 0x80

    private val FULL_MINUS_BIT = 0x01; private val FULL_PLUS_BIT = 0x02
    private val FULL_RIGHT_STICK_BIT = 0x04; private val FULL_LEFT_STICK_BIT = 0x08
    private val FULL_HOME_BIT = 0x10; private val FULL_CAPTURE_BIT = 0x20
    private val FULL_DOWN_BIT = 0x01; private val FULL_UP_BIT = 0x02
    private val FULL_RIGHT_BIT = 0x04; private val FULL_LEFT_BIT = 0x08
    private val FULL_SR_BIT = 0x10; private val FULL_SL_BIT = 0x20
    private val FULL_L_R_BIT = 0x40; private val FULL_ZL_ZR_BIT = 0x80
    private val FULL_Y_BIT = 0x01; private val FULL_X_BIT = 0x02
    private val FULL_B_BIT = 0x04; private val FULL_A_BIT = 0x08

    @Volatile private var bDown=0; @Volatile private var bUp=0
    @Volatile private var bLeft=0; @Volatile private var bRight=0
    @Volatile private var bY=0; @Volatile private var bX=0
    @Volatile private var bB=0; @Volatile private var bA=0
    @Volatile private var bL=0; @Volatile private var bR=0
    @Volatile private var bZl=0; @Volatile private var bZr=0
    @Volatile private var bMinus=0; @Volatile private var bPlus=0
    @Volatile private var bHome=0; @Volatile private var bCapture=0
    @Volatile private var bLStick=0; @Volatile private var bRStick=0

    private var timeByte: Byte = 0

    private val spi = ByteArray(0x10100) { 0xFF.toByte() }.also { s ->
        s[0x6012] = 0x03
        s[0x601B] = 0x01
        val cal = intArrayOf(0x00, 0x07, 0x70, 0x00, 0x08, 0x80, 0x00, 0x07, 0x70)
        for (base in intArrayOf(0x603D, 0x6046, 0x6020, 0x8010)) {
            cal.forEachIndexed { i, v -> if (base + i < s.size) s[base + i] = v.toByte() }
        }
        intArrayOf(0x32, 0x32, 0x32, 0xFF, 0xFF, 0xFF).forEachIndexed { i, v -> s[0x6050 + i] = v.toByte() }
    }

    fun init() {
        adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
        adapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                registerApp()
            }
            override fun onServiceDisconnected(p: Int) { hid = null; onStatus("HID service lost") }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(HID_NAME, HID_DESCRIPTION, HID_PROVIDER, SUBCLASS, DESC)
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
            21720, 362, 21720, 16667, 16667
        )
        try {
            adapter?.name = BT_DEVICE_NAME
            hid?.registerApp(sdp, qos, qos, Executors.newSingleThreadExecutor(), cb)
        } catch (e: SecurityException) {
            onStatus("Missing Bluetooth permission")
        }
    }

    fun connect(d: BluetoothDevice) {
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            onStatus("Not paired yet - use 'Make phone discoverable' and let the Switch connect")
            return
        }
        onStatus("Connecting to ${d.name}…")
        h.postDelayed({
            try { hid?.connect(d) } catch (e: SecurityException) { onStatus("Missing Bluetooth permission") }
        }, 2000)
    }

    fun setState(j: JSONObject) {
        fun f(k: String) = j.optInt(k, 0) == 1
        bDown = if (f("Down")) 1 else 0; bUp = if (f("Up")) 1 else 0
        bLeft = if (f("Left")) 1 else 0; bRight = if (f("Right")) 1 else 0
        bY = if (f("Y")) 1 else 0; bX = if (f("X")) 1 else 0
        bB = if (f("B")) 1 else 0; bA = if (f("A")) 1 else 0
        bL = if (f("L")) 1 else 0; bR = if (f("R")) 1 else 0
        bZl = if (f("ZL")) 1 else 0; bZr = if (f("ZR")) 1 else 0
        bMinus = if (f("select")) 1 else 0; bPlus = if (f("start")) 1 else 0
        bHome = if (f("home")) 1 else 0; bCapture = 0
        if (!fullMode) sendSimpleReport()
    }

    private fun sendSimpleReport() {
        val buf = ByteArray(11)
        var b0 = 0
        if (bDown == 1) b0 = b0 or DOWN_BIT
        if (bRight == 1) b0 = b0 or RIGHT_BIT
        if (bLeft == 1) b0 = b0 or LEFT_BIT
        if (bUp == 1) b0 = b0 or UP_BIT
        buf[0] = b0.toByte()
        var b1 = 0
        if (bMinus == 1) b1 = b1 or MINUS_BIT
        if (bPlus == 1) b1 = b1 or PLUS_BIT
        if (bHome == 1) b1 = b1 or HOME_BIT
        buf[1] = b1.toByte()
        buf[2] = 8
        for (i in 3..10) buf[i] = if (i % 2 == 0) 0x80.toByte() else 0x00
        sendReport(BUTTON_REPORT, buf)
    }

    private fun fillFullButtonReport(out: ByteArray, idx: Int) {
        Arrays.fill(out, idx, idx + 9, 0)
        var b0 = 0
        if (bY == 1) b0 = b0 or FULL_Y_BIT
        if (bX == 1) b0 = b0 or FULL_X_BIT
        if (bB == 1) b0 = b0 or FULL_B_BIT
        if (bA == 1) b0 = b0 or FULL_A_BIT
        if (bR == 1) b0 = b0 or FULL_L_R_BIT
        if (bZr == 1) b0 = b0 or FULL_ZL_ZR_BIT
        out[idx] = b0.toByte()

        var b1 = 0
        if (bMinus == 1) b1 = b1 or FULL_MINUS_BIT
        if (bPlus == 1) b1 = b1 or FULL_PLUS_BIT
        if (bRStick == 1) b1 = b1 or FULL_RIGHT_STICK_BIT
        if (bLStick == 1) b1 = b1 or FULL_LEFT_STICK_BIT
        if (bHome == 1) b1 = b1 or FULL_HOME_BIT
        if (bCapture == 1) b1 = b1 or FULL_CAPTURE_BIT
        out[idx + 1] = b1.toByte()

        var b2 = 0
        if (bDown == 1) b2 = b2 or FULL_DOWN_BIT
        if (bRight == 1) b2 = b2 or FULL_RIGHT_BIT
        if (bLeft == 1) b2 = b2 or FULL_LEFT_BIT
        if (bUp == 1) b2 = b2 or FULL_UP_BIT
        if (bL == 1) b2 = b2 or FULL_L_R_BIT
        if (bZl == 1) b2 = b2 or FULL_ZL_ZR_BIT
        out[idx + 2] = b2.toByte()

        val cx = 2047; val cy = 2047
        out[idx + 3] = (cx and 0xFF).toByte()
        out[idx + 4] = (((cx shr 8) and 0xF) or ((cy and 0xF) shl 4)).toByte()
        out[idx + 5] = ((cy shr 4) and 0xFF).toByte()
        out[idx + 6] = (cx and 0xFF).toByte()
        out[idx + 7] = (((cx shr 8) and 0xF) or ((cy and 0xF) shl 4)).toByte()
        out[idx + 8] = ((cy shr 4) and 0xFF).toByte()
    }

    private fun sendFullReport() {
        val out = ByteArray(48)
        out[0] = timeByte++
        out[1] = 0x91.toByte()
        fillFullButtonReport(out, 2)
        out[11] = 0x0C
        sendReport(FULL_BUTTON_REPORT, out)
    }

    private fun startFullReportLoop() {
        if (streaming) return
        streaming = true
        val r = object : Runnable {
            override fun run() {
                if (!streaming) return
                sendFullReport()
                h.postDelayed(this, 30)
            }
        }
        h.post(r)
    }

    private fun handleRumbleAndSubcommand(data: ByteArray) {
        val out = ByteArray(48)
        out[0] = timeByte++
        out[1] = 0x91.toByte()
        fillFullButtonReport(out, 2)
        out[11] = 0x0C

        val subcommand = data[9]
        when (subcommand) {
            REQUEST_DEVICE_INFO -> {
                out[12] = (ACK.toInt() or REQUEST_DEVICE_INFO.toInt()).toByte()
                out[13] = subcommand
                fillDeviceInfo(out, 14)
            }
            REQUEST_SET_SHIPMENT -> { out[12] = ACK; out[13] = subcommand }
            REQUEST_INPUT_REPORT_MODE -> {
                out[12] = ACK; out[13] = subcommand
                val mode = data[10]
                when (mode) {
                    STANDARD_FULL_MODE -> { fullMode = true; startFullReportLoop() }
                    SIMPLE_HID_MODE -> { fullMode = false; streaming = false }
                }
            }
            REQUEST_SPI_FLASH_READ -> {
                var loc = 0
                for (i in 0..3) loc = loc or ((data[10 + i].toInt() and 0xFF) shl (i * 8))
                val len = data[14].toInt() and 0xFF
                out[12] = (ACK.toInt() or REQUEST_SPI_FLASH_READ.toInt()).toByte()
                out[13] = subcommand
                System.arraycopy(data, 10, out, 14, 5)
                val safeLen = minOf(len, spi.size - loc)
                if (loc in spi.indices && safeLen > 0) {
                    System.arraycopy(spi, loc, out, 19, safeLen)
                }
            }
            REQUEST_SET_PLAYER_LIGHTS -> { out[12] = ACK; out[13] = subcommand }
            else -> { out[12] = ACK; out[13] = subcommand }
        }
        sendReport(SUBCOMMAND_REPLY_REPORT, out)
    }

    private fun fillDeviceInfo(out: ByteArray, idx: Int) {
        out[idx] = 0x04; out[idx + 1] = 0x06
        out[idx + 2] = spi[0x6012]
        out[idx + 3] = 0x02
        val mac = adapter?.address ?: "00:00:00:00:00:00"
        val parts = mac.split(":").map { it.toInt(16).toByte() }
        for (i in 0..5) if (i < parts.size) out[idx + 4 + i] = parts[5 - i]
        out[idx + 10] = 0x01
        out[idx + 11] = spi[0x601B]
    }

    private fun sendReport(reportId: Byte, data: ByteArray): Boolean {
        val h2 = host ?: return false
        return try { hid?.sendReport(h2, reportId.toInt(), data) ?: false }
        catch (e: SecurityException) { false }
    }

    private val cb = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            onStatus(if (registered) "Ready – pick your Switch" else "Not registered")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    onStatus("Connected: ${device.name}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    streaming = false; fullMode = false; host = null
                    onStatus("Disconnected")
                }
            }
        }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            when (reportId) {
                REQUEST_RUMBLE_AND_SUBCOMMAND -> handleRumbleAndSubcommand(data)
                else -> { }
            }
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {}
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {}
    }
}

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
    return out
}
