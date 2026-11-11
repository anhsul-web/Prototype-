package com.prototype.hidremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
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

enum class Mode { Mouse, Keyboard, Gamepad }

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

    override fun onStart() {
        super.onStart()
        hid.ensureRegistered()
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
    var settingsOpen by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var cursorSpeed by remember { mutableFloatStateOf(prefs.getFloat(PREF_CURSOR_SPEED, 1f)) }
    var scrollSpeed by remember { mutableFloatStateOf(prefs.getFloat(PREF_SCROLL_SPEED, 1f)) }

    DisposableEffect(mode) {
        (context as? Activity)?.requestedOrientation =
            if (mode == Mode.Gamepad) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }

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

    // Once the HID app is registered, make the phone discoverable so a new host can pair with it.
    // Skipped when a bonded host exists: the service reconnects to it directly.
    LaunchedEffect(registered) {
        if (registered && connectionState != BluetoothProfile.STATE_CONNECTED && hid.knownHost == null) {
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
            Mode.Mouse -> Trackpad(hid, cursorSpeed, scrollSpeed)
            Mode.Keyboard -> KeyboardCapture(hid)
            Mode.Gamepad -> GamepadScreen(hid)
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
                DropdownMenuItem(
                    text = { Text("Gamepad") },
                    onClick = { mode = Mode.Gamepad; menuOpen = false }
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { settingsOpen = true; menuOpen = false }
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            cursorSpeed = cursorSpeed,
            scrollSpeed = scrollSpeed,
            onCursorSpeedChange = {
                cursorSpeed = it
                prefs.edit().putFloat(PREF_CURSOR_SPEED, it).apply()
            },
            onScrollSpeedChange = {
                scrollSpeed = it
                prefs.edit().putFloat(PREF_SCROLL_SPEED, it).apply()
            },
            onDismiss = { settingsOpen = false }
        )
    }
}

private const val PREFS_NAME = "hid_settings"
private const val PREF_CURSOR_SPEED = "cursor_speed"
private const val PREF_SCROLL_SPEED = "scroll_speed"

@Composable
fun SettingsDialog(
    cursorSpeed: Float,
    scrollSpeed: Float,
    onCursorSpeedChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column {
                Text("Cursor Speed: %.1fx".format(cursorSpeed))
                Slider(value = cursorSpeed, onValueChange = onCursorSpeedChange, valueRange = 0.5f..3f)
                Text("Scroll Speed: %.1fx".format(scrollSpeed))
                Slider(value = scrollSpeed, onValueChange = onScrollSpeedChange, valueRange = 0.5f..3f)
            }
        }
    )
}

private const val SENSITIVITY = 1.5f
private const val TAP_TIMEOUT_MS = 250L
private const val DOUBLE_TAP_SLOP_PX = 100f
/** Finger travel (px, at 1.0x scroll speed) per wheel detent. */
private const val SCROLL_PX_PER_DETENT = 40f

private fun PointerInputChange.delta(): Offset =
    if (previousPressed && pressed) position - previousPosition else Offset.Zero

/**
 * 1 finger drag = move, 1 finger tap = left click, double tap = double click,
 * 2 finger tap = right click, 2 finger drag = vertical/horizontal scroll.
 */
@Composable
fun Trackpad(hid: BluetoothHidService, cursorSpeed: Float, scrollSpeed: Float) {
    val cursorMultiplier by rememberUpdatedState(cursorSpeed)
    val scrollMultiplier by rememberUpdatedState(scrollSpeed)
    var lastTapUpTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(hid) {
                val slop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var maxPointers = 1
                    var travelled = 0f
                    var moved = false
                    var remX = 0f
                    var remY = 0f
                    var scrollRemX = 0f
                    var scrollRemY = 0f
                    var upTime = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        upTime = event.changes.maxOf { it.uptimeMillis }
                        if (pressed.isEmpty()) break
                        maxPointers = maxOf(maxPointers, pressed.size)

                        if (pressed.size >= 2) {
                            // Two-finger pan: average movement of the fingers that were already down.
                            val moving = pressed.filter { it.previousPressed }
                            val delta = if (moving.isEmpty()) Offset.Zero
                            else moving.fold(Offset.Zero) { acc, c -> acc + c.delta() } / moving.size.toFloat()
                            // Use the farthest-moving finger so a pinch (canceling average) is not a tap.
                            travelled += moving.maxOfOrNull { it.delta().getDistance() } ?: 0f
                            if (travelled > slop) moved = true
                            if (moved) {
                                // Reversed (non-natural) scrolling: fingers up = scroll up, fingers right = scroll right.
                                val fy = -delta.y * scrollMultiplier / SCROLL_PX_PER_DETENT + scrollRemY
                                val fx = delta.x * scrollMultiplier / SCROLL_PX_PER_DETENT + scrollRemX
                                val wheel = fy.toInt().coerceIn(-127, 127)
                                val pan = fx.toInt().coerceIn(-127, 127)
                                scrollRemY = fy - wheel
                                scrollRemX = fx - pan
                                if (wheel != 0 || pan != 0) hid.scroll(wheel, pan)
                            }
                        } else if (maxPointers == 1) {
                            val delta = pressed.first().delta()
                            travelled += delta.getDistance()
                            if (travelled > slop) moved = true
                            if (moved) {
                                val speed = SENSITIVITY * cursorMultiplier
                                val fx = delta.x * speed + remX
                                val fy = delta.y * speed + remY
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
                        }
                        event.changes.forEach { it.consume() }
                    }

                    if (moved || upTime - down.uptimeMillis > TAP_TIMEOUT_MS) return@awaitEachGesture
                    if (maxPointers >= 2) {
                        hid.rightClick()
                        lastTapUpTime = 0L
                        return@awaitEachGesture
                    }
                    val isDoubleTap = down.uptimeMillis - lastTapUpTime <= doubleTapTimeout &&
                        (down.position - lastTapPosition).getDistance() <= DOUBLE_TAP_SLOP_PX
                    // The first tap already sent a click; the second click completes the double click
                    // without delaying single taps.
                    hid.leftClick()
                    if (isDoubleTap) {
                        lastTapUpTime = 0L
                    } else {
                        lastTapUpTime = upTime
                        lastTapPosition = down.position
                    }
                }
            }
    )
}

/**
 * Non-empty buffers so that backspace always produces a detectable deletion. The field alternates
 * between two distinct sentinels so every reset is a real state change that resyncs the IME.
 */
private const val SENTINEL_A = "                "
private const val SENTINEL_B = "                 "

private val NON_TEXT_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    android.view.KeyEvent.KEYCODE_ESCAPE,
    android.view.KeyEvent.KEYCODE_FORWARD_DEL
)

private fun sentinelValue(text: String) = TextFieldValue(text, TextRange(text.length))

@Composable
fun KeyboardCapture(hid: BluetoothHidService) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(sentinelValue(SENTINEL_A)) }
    // Text the host already has; committed edits are diffed against it.
    var baseline by remember { mutableStateOf(SENTINEL_A) }

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
                // While the IME is composing (e.g. a glide-typed word), let it edit locally.
                if (new.composition != null || new.text == baseline) {
                    value = new
                    return@BasicTextField
                }
                // Committed: send backspaces for removed chars, then each new char in order.
                val text = new.text
                var common = 0
                while (common < text.length && common < baseline.length &&
                    text[common] == baseline[common]
                ) common++
                val strokes = buildList {
                    repeat(baseline.length - common) { add(KeyStroke(0, HidKeyMap.KEY_BACKSPACE)) }
                    text.substring(common).forEach { c -> HidKeyMap.forChar(c)?.let(::add) }
                }
                hid.pressKeys(strokes)
                // Clear: alternate sentinels so every reset is a real state change that resyncs the IME.
                baseline = if (baseline == SENTINEL_A) SENTINEL_B else SENTINEL_A
                value = sentinelValue(baseline)
            },
            // Plain text with suggestions enabled: IMEs disable glide typing on password fields.
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // Non-text keys and keys carrying Ctrl/Alt/Meta modifiers bypass the text diff.
                    val native = event.nativeKeyEvent
                    val hasModifier = native.isCtrlPressed || native.isAltPressed || native.isMetaPressed
                    if (!hasModifier && native.keyCode !in NON_TEXT_KEYS) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        HidKeyMap.forAndroidKey(native.keyCode, native.metaState)?.let(hid::pressKey)
                    }
                    true
                }
        )
    }
}
