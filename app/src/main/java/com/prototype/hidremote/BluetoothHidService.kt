package com.prototype.hidremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
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
            val size = if (id.toInt() == HidDescriptor.ID_MOUSE) MOUSE_REPORT_SIZE else KEYBOARD_REPORT_SIZE
            hidDevice?.replyReport(device, type, id, ByteArray(size))
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
            val ok = device.registerApp(sdpSettings, null, null, callbackExecutor, hidCallback)
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

    private fun send(id: Int, report: ByteArray) {
        val device = hidDevice ?: return
        val host = hostDevice ?: return
        if (reportExecutor.isShutdown) return
        reportExecutor.execute { device.sendReport(host, id, report) }
    }

    // ---------- Mouse ----------

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        send(
            HidDescriptor.ID_MOUSE,
            byteArrayOf(
                (buttons and 0x07).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
        )
    }

    fun mouseMove(dx: Int, dy: Int) = sendMouse(0, dx, dy)

    fun leftClick() {
        sendMouse(BUTTON_LEFT, 0, 0)
        sendMouse(0, 0, 0)
    }

    // ---------- Keyboard ----------

    fun sendKeyboard(modifiers: Int, keyCode: Int) {
        send(
            HidDescriptor.ID_KEYBOARD,
            byteArrayOf(modifiers.toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0)
        )
    }

    /** Key down followed immediately by key up (all keys released). */
    fun pressKey(stroke: KeyStroke) {
        sendKeyboard(stroke.modifiers, stroke.keyCode)
        sendKeyboard(0, 0)
    }

    companion object {
        private const val TAG = "BluetoothHidService"
        const val BUTTON_LEFT = 0x01
        private const val MOUSE_REPORT_SIZE = 4
        private const val KEYBOARD_REPORT_SIZE = 8
    }
}
