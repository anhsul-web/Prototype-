package com.prototype.hidremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
    @Volatile private var started = false
    private var receiverRegistered = false
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    // True when there is no bonded host to reconnect to, or the reconnect attempt failed.
    private val _needsPairing = MutableStateFlow(false)
    val needsPairing: StateFlow<Boolean> = _needsPairing.asStateFlow()
    @Volatile private var reconnectTarget: BluetoothDevice? = null

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** A previously connected host that is still bonded; we reconnect to it after (re)registration. */
    val knownHost: BluetoothDevice?
        get() {
            val address = prefs.getString(PREF_LAST_HOST, null) ?: return null
            return adapter?.bondedDevices?.firstOrNull { it.address == address }
        }

    // Re-acquire the profile when Bluetooth comes back on; drop it cleanly when it turns off.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> releaseProfile()
                BluetoothAdapter.STATE_ON -> if (started && !stopped) acquireProfile()
            }
        }
    }

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        SPOOF_NAME,
        "Mouse, Keyboard and Gamepad",
        "Sony Interactive Entertainment",
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
                // Also happens when Android unregisters a backgrounded app; ensureRegistered() restores it.
                hostDevice = null
                _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
            } else if (hostDevice == null) {
                // Device-initiated reconnect to the last host (hosts like Windows wait for the device).
                val target = pluggedDevice ?: knownHost
                reconnectTarget = target
                _needsPairing.value = target == null || hidDevice?.connect(target) != true
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged $device state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    reconnectTarget = null
                    _needsPairing.value = false
                    prefs.edit().putString(PREF_LAST_HOST, device.address).apply()
                    _connectionState.value = state
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    if (hostDevice == null) _connectionState.value = state
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    // Ignore other devices dropping (e.g. a failed reconnect attempt) while a host is live.
                    if (hostDevice == null || hostDevice == device) {
                        hostDevice = null
                        _connectionState.value = state
                    }
                    if (state == BluetoothProfile.STATE_DISCONNECTED && device == reconnectTarget) {
                        reconnectTarget = null
                        _needsPairing.value = true
                    }
                }
            }
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
                val report = if (id.toInt() == HidDescriptor.ID_GAMEPAD) buildGamepadReport(0, 0, 0, 0, 0) else ByteArray(size)
                hidDevice?.replyReport(device, type, id, report)
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
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            hostDevice = null
            _registered.value = false
            _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        }
    }

    private fun register(device: BluetoothHidDevice) {
        val ok = device.registerApp(sdpSettings, null, qosOut, callbackExecutor, hidCallback)
        Log.d(TAG, "registerApp -> $ok")
    }

    fun start(): Boolean {
        if (stopped) return false
        started = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                // Sent by the Bluetooth app (not system_server); it's a protected broadcast, so exporting is safe.
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
        spoofAdapterName()
        return acquireProfile()
    }

    /** Call when the UI returns to the foreground: re-registers if Android dropped the app. */
    fun ensureRegistered() {
        if (!started || stopped || !isBluetoothEnabled) return
        val device = hidDevice
        if (device == null) acquireProfile() else if (!_registered.value) register(device)
    }

    private fun acquireProfile(): Boolean {
        if (hidDevice != null) return true
        val bt = adapter ?: return false
        return bt.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    /** Unregisters the SDP record and closes the proxy so the next registration starts clean. */
    private fun releaseProfile() {
        hidDevice?.let { device ->
            hostDevice?.let { device.disconnect(it) }
            device.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hidDevice = null
        hostDevice = null
        _registered.value = false
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
    }

    fun stop() {
        stopped = true
        if (receiverRegistered) {
            appContext.unregisterReceiver(bluetoothStateReceiver)
            receiverRegistered = false
        }
        releaseProfile()
        restoreAdapterName()
        callbackExecutor.shutdown()
        reportExecutor.shutdown()
    }

    // The original name is persisted so it's restored on the next stop even if the process was killed.
    private fun spoofAdapterName() {
        val bt = adapter ?: return
        val current = bt.name ?: return
        if (current != SPOOF_NAME && !prefs.contains(PREF_ORIGINAL_NAME)) {
            prefs.edit().putString(PREF_ORIGINAL_NAME, current).apply()
        }
        if (current != SPOOF_NAME) Log.d(TAG, "setName(spoof) -> ${bt.setName(SPOOF_NAME)}")
    }

    private fun restoreAdapterName() {
        val original = prefs.getString(PREF_ORIGINAL_NAME, null) ?: return
        if (adapter?.setName(original) == true) prefs.edit().remove(PREF_ORIGINAL_NAME).apply()
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

    @Volatile private var gamepadCounter = 0

    /**
     * Report ID 3 payload in DualShock 4 order (9 bytes, report ID added by the stack):
     * [0] LX [1] LY [2] RX [3] RY (unsigned, 128 = center), [4] hat (low nibble, 8 = none) +
     * buttons 1-4 (high nibble), [5] buttons 5-12, [6] buttons 13-14 + 6-bit counter, [7] L2, [8] R2.
     * Inputs: [buttons] bit n = HID button n+1; axes -127..127 (Y/Rz positive = down).
     */
    fun sendGamepad(buttons: Int, x: Int, y: Int, z: Int, rz: Int) =
        send(HidDescriptor.ID_GAMEPAD, buildGamepadReport(buttons, x, y, z, rz))

    private fun buildGamepadReport(buttons: Int, x: Int, y: Int, z: Int, rz: Int): ByteArray {
        fun axis(v: Int) = (v.coerceIn(-127, 127) + 128).toByte()
        val counter = gamepadCounter
        gamepadCounter = (counter + 1) and 0x3F
        val report = ByteArray(HidDescriptor.GAMEPAD_REPORT_SIZE)
        report[0] = axis(x)
        report[1] = axis(y)
        report[2] = axis(z)
        report[3] = axis(rz)
        report[4] = (HAT_CENTERED or ((buttons and 0x0F) shl 4)).toByte()
        report[5] = ((buttons ushr 4) and 0xFF).toByte()
        report[6] = (((buttons ushr 12) and 0x03) or (counter shl 2)).toByte()
        report[7] = if (buttons and (1 shl GamepadButton.SCOPE) != 0) 0xFF.toByte() else 0
        report[8] = if (buttons and (1 shl GamepadButton.FIRE) != 0) 0xFF.toByte() else 0
        return report
    }

    companion object {
        private const val TAG = "BluetoothHidService"
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        private const val KEY_DELAY_MS = 10L
        private const val PREFS_NAME = "hid_host"
        private const val PREF_LAST_HOST = "last_host_address"
        private const val PREF_ORIGINAL_NAME = "original_adapter_name"
        private const val SPOOF_NAME = "DUALSHOCK 4 Wireless Controller"
        private const val HAT_CENTERED = 0x08
    }
}
