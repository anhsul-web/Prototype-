package com.prototype.hidremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

enum class Mode { Mouse, Keyboard }

class MainActivity : ComponentActivity() {

    private lateinit var hid: BluetoothHidService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hid = BluetoothHidService(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HidApp(hid)
            }
        }
    }

    override fun onDestroy() {
        hid.stop()
        super.onDestroy()
    }
}

private val REQUIRED_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        emptyArray()
    }

@Composable
fun HidApp(hid: BluetoothHidService) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connectionState by hid.connectionState.collectAsState()
    val registered by hid.registered.collectAsState()
    var permissionsGranted by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var bluetoothOn by remember { mutableStateOf(hid.isBluetoothEnabled) }
    var mode by remember { mutableStateOf(Mode.Mouse) }
    var menuOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> permissionsGranted = result.values.all { it } }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { bluetoothOn = hid.isBluetoothEnabled }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    LaunchedEffect(permissionsGranted, bluetoothOn) {
        if (!permissionsGranted) return@LaunchedEffect
        if (!bluetoothOn) {
            @SuppressLint("MissingPermission")
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return@LaunchedEffect
        }
        hid.start()
    }

    // Once the HID app is registered, make the phone discoverable so a host can pair with it.
    LaunchedEffect(registered) {
        if (registered && connectionState != BluetoothProfile.STATE_CONNECTED) {
            discoverableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (mode) {
            Mode.Mouse -> Trackpad(hid)
            Mode.Keyboard -> KeyboardCapture(hid)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (connectionState == BluetoothProfile.STATE_CONNECTED) Color.Green
                        else Color.White
                    )
                    .clickable { menuOpen = true }
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Mouse") },
                    onClick = { mode = Mode.Mouse; menuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text("Keyboard") },
                    onClick = { mode = Mode.Keyboard; menuOpen = false }
                )
            }
        }
    }
}

private const val SENSITIVITY = 1.5f
private const val TAP_TIMEOUT_MS = 250L

@Composable
fun Trackpad(hid: BluetoothHidService) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(hid) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var last = down.position
                    var travelled = 0f
                    var moved = false
                    var remX = 0f
                    var remY = 0f
                    var upTime = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta: Offset = change.position - last
                        last = change.position
                        upTime = change.uptimeMillis
                        travelled += delta.getDistance()
                        if (travelled > slop) moved = true
                        if (moved) {
                            val fx = delta.x * SENSITIVITY + remX
                            val fy = delta.y * SENSITIVITY + remY
                            var dx = fx.roundToInt()
                            var dy = fy.roundToInt()
                            remX = fx - dx
                            remY = fy - dy
                            // Split large deltas into multiple reports (each axis is limited to ±127).
                            while (dx != 0 || dy != 0) {
                                val stepX = dx.coerceIn(-127, 127)
                                val stepY = dy.coerceIn(-127, 127)
                                hid.mouseMove(stepX, stepY)
                                dx -= stepX
                                dy -= stepY
                            }
                        }
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (!moved && upTime - down.uptimeMillis <= TAP_TIMEOUT_MS) {
                        hid.leftClick()
                    }
                }
            }
    )
}

/** Non-empty buffer so that backspace always produces a detectable deletion. */
private const val SENTINEL = "                "

private fun sentinelValue() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

@Composable
fun KeyboardCapture(hid: BluetoothHidService) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(sentinelValue()) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
    ) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                val text = new.text
                var common = 0
                while (common < text.length && common < SENTINEL.length &&
                    text[common] == SENTINEL[common]
                ) common++
                repeat(SENTINEL.length - common) {
                    hid.pressKey(KeyStroke(0, HidKeyMap.KEY_BACKSPACE))
                }
                text.substring(common).forEach { c ->
                    HidKeyMap.forChar(c)?.let(hid::pressKey)
                }
                // Swallow the edit so nothing is ever rendered/accumulated.
                value = sentinelValue()
            },
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // Hardware keys carrying Ctrl/Alt/Meta modifiers bypass the text diff.
                    val native = event.nativeKeyEvent
                    val hasModifier = native.isCtrlPressed || native.isAltPressed || native.isMetaPressed
                    if (!hasModifier) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        HidKeyMap.forAndroidKey(native.keyCode, native.metaState)?.let(hid::pressKey)
                    }
                    true
                }
        )
    }
}
