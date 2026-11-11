package com.prototype.hidremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Wraps [BluetoothHidDevice]: obtains the profile proxy, registers the combo
 * mouse/keyboard SDP record and sends input reports to the connected host.
 * Callers must hold BLUETOOTH_CONNECT (API 31+) before calling [start].
 */
@SuppressLint("MissingPermission")
class BluetoothHidService(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val reportExecutor = Executors.newSingleThreadExecutor()

    private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var hostDevice: BluetoothDevice? = null
    @Volatile private var stopped = false

    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "HID Remote",
        "Mouse and Keyboard",
        "Prototype",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidDescriptor.REPORT_MAP
    )

    // Same outgoing QoS as Google's WearMouse sample; some hosts drop input without it.
    private val qosOut = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800, 9, 0, 11250,
        BluetoothHidDeviceAppQosSettings.MAX
    )

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged registered=$registered plugged=$pluggedDevice")
            _registered.value = registered
            if (!registered) {
                hostDevice = null
                _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
            } else if (pluggedDevice != null) {
                hidDevice?.connect(pluggedDevice)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged $device state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> hostDevice = device
                BluetoothProfile.STATE_DISCONNECTED -> if (hostDevice == device) hostDevice = null
            }
            _connectionState.value = state
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport type=$type id=$id")
            val size = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD -> HidDescriptor.KEYBOARD_REPORT_SIZE
                HidDescriptor.ID_MOUSE -> HidDescriptor.MOUSE_REPORT_SIZE
                HidDescriptor.ID_GAMEPAD -> HidDescriptor.GAMEPAD_REPORT_SIZE
                else -> 0
            }
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && size > 0) {
                hidDevice?.replyReport(device, type, id, ByteArray(size))
            } else {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            Log.d(TAG, "onSetProtocol $protocol")
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as BluetoothHidDevice
            if (stopped) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                return
            }
            hidDevice = device
            val ok = device.registerApp(sdpSettings, null, qosOut, callbackExecutor, hidCallback)
            Log.d(TAG, "registerApp -> $ok")
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            hostDevice = null
            _registered.value = false
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    fun start(): Boolean {
        if (stopped) return false
        if (hidDevice != null) return true
        val bt = adapter ?: return false
        return bt.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        stopped = true
        hidDevice?.let { device ->
            hostDevice?.let { device.disconnect(it) }
            device.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hidDevice = null
        hostDevice = null
        _registered.value = false
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        callbackExecutor.shutdown()
        reportExecutor.shutdown()
    }

    private fun send(id: Int, report: ByteArray) = enqueue { sendNow(id, report) }

    private fun enqueue(block: () -> Unit) {
        if (hostDevice == null || reportExecutor.isShutdown) return
        reportExecutor.execute(block)
    }

    /** Must run on [reportExecutor]. */
    private fun sendNow(id: Int, report: ByteArray) {
        val device = hidDevice ?: return
        val host = hostDevice ?: return
        if (!device.sendReport(host, id, report)) Log.w(TAG, "sendReport failed id=$id")
    }

    // ---------- Mouse ----------

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        send(
            HidDescriptor.ID_MOUSE,
            byteArrayOf(
                (buttons and 0x07).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte(),
                pan.coerceIn(-127, 127).toByte()
            )
        )
    }

    fun mouseMove(dx: Int, dy: Int) = sendMouse(0, dx, dy)

    fun leftClick() = click(BUTTON_LEFT)

    fun rightClick() = click(BUTTON_RIGHT)

    private fun click(button: Int) {
        sendMouse(button, 0, 0)
        sendMouse(0, 0, 0)
    }

    /** [wheel] > 0 scrolls up, [pan] > 0 scrolls right (one unit per detent). */
    fun scroll(wheel: Int, pan: Int) = sendMouse(0, 0, 0, wheel, pan)

    // ---------- Keyboard ----------

    fun sendKeyboard(modifiers: Int, keyCode: Int) {
        send(HidDescriptor.ID_KEYBOARD, keyboardReport(modifiers, keyCode))
    }

    private fun keyboardReport(modifiers: Int, keyCode: Int) =
        byteArrayOf(modifiers.toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0)

    /** Key down followed immediately by key up (all keys released). */
    fun pressKey(stroke: KeyStroke) = pressKeys(listOf(stroke))

    /** Sends each stroke as key down -> key up, pausing [KEY_DELAY_MS] between strokes. */
    fun pressKeys(strokes: List<KeyStroke>) {
        if (strokes.isEmpty()) return
        enqueue {
            strokes.forEachIndexed { i, stroke ->
                if (i > 0) Thread.sleep(KEY_DELAY_MS)
                sendNow(HidDescriptor.ID_KEYBOARD, keyboardReport(stroke.modifiers, stroke.keyCode))
                sendNow(HidDescriptor.ID_KEYBOARD, keyboardReport(0, 0))
            }
        }
    }

    // ---------- Gamepad ----------

    /** [buttons] bit n = HID button n+1; axes are -127..127 (Y/Rz positive = down). */
    fun sendGamepad(buttons: Int, x: Int, y: Int, z: Int, rz: Int) {
        send(
            HidDescriptor.ID_GAMEPAD,
            byteArrayOf(
                (buttons and 0xFF).toByte(),
                ((buttons shr 8) and 0xFF).toByte(),
                x.coerceIn(-127, 127).toByte(),
                y.coerceIn(-127, 127).toByte(),
                z.coerceIn(-127, 127).toByte(),
                rz.coerceIn(-127, 127).toByte()
            )
        )
    }

    companion object {
        private const val TAG = "BluetoothHidService"
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        private const val KEY_DELAY_MS = 10L
    }
}
