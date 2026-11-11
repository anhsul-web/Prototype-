package com.prototype.hidremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Bit index in the gamepad button mask (bit n = HID button n + 1), Xbox-style order. */
object GamepadButton {
    const val A = 0
    const val B = 1
    const val X = 2
    const val Y = 3
    const val LB = 4
    const val RB = 5
    const val SCOPE = 6 // LT
    const val FIRE = 7  // RT
}

/** Aggregates every control into one gamepad report and sends it whenever anything changes. */
class GamepadState(private val hid: BluetoothHidService) {
    private var buttons = 0
    private var x = 0
    private var y = 0
    private var z = 0
    private var rz = 0

    fun setButton(index: Int, pressed: Boolean) {
        val next = if (pressed) buttons or (1 shl index) else buttons and (1 shl index).inv()
        if (next == buttons) return
        buttons = next
        send()
    }

    fun setLeftStick(nx: Int, ny: Int) {
        if (nx == x && ny == y) return
        x = nx; y = ny
        send()
    }

    fun setRightStick(nz: Int, nrz: Int) {
        if (nz == z && nrz == rz) return
        z = nz; rz = nrz
        send()
    }

    fun reset() {
        buttons = 0; x = 0; y = 0; z = 0; rz = 0
        send()
    }

    private fun send() = hid.sendGamepad(buttons, x, y, z, rz)
}

/**
 * Landscape controller. Every stick and button is its own pointerInput node, so Compose routes
 * each finger to the control it landed on and all controls work simultaneously.
 */
@Composable
fun GamepadScreen(hid: BluetoothHidService) {
    val state = remember(hid) { GamepadState(hid) }
    DisposableEffect(state) { onDispose { state.reset() } }

    Row(modifier = Modifier.fillMaxSize()) {
        FloatingJoystick(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            idleCenter = Offset(0.35f, 0.65f),
            onChange = state::setLeftStick
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            FloatingJoystick(
                modifier = Modifier.fillMaxSize(),
                idleCenter = Offset(0.3f, 0.5f),
                onChange = state::setRightStick
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 32.dp)
            ) {
                PadButton("Scope", 72.dp) { state.setButton(GamepadButton.SCOPE, it) }
                Spacer(Modifier.width(24.dp))
                PadButton("Fire", 72.dp) { state.setButton(GamepadButton.FIRE, it) }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .size(180.dp)
            ) {
                PadButton("Y", modifier = Modifier.align(Alignment.TopCenter)) {
                    state.setButton(GamepadButton.Y, it)
                }
                PadButton("X", modifier = Modifier.align(Alignment.CenterStart)) {
                    state.setButton(GamepadButton.X, it)
                }
                PadButton("B", modifier = Modifier.align(Alignment.CenterEnd)) {
                    state.setButton(GamepadButton.B, it)
                }
                PadButton("A", modifier = Modifier.align(Alignment.BottomCenter)) {
                    state.setButton(GamepadButton.A, it)
                }
            }
        }
    }
}

private val JOYSTICK_RADIUS = 60.dp

/**
 * A floating stick: the base appears where the finger lands and the knob follows the finger,
 * clamped to the radius. Reports (-127..127, -127..127); y is positive downward.
 */
@Composable
fun FloatingJoystick(
    modifier: Modifier,
    idleCenter: Offset,
    onChange: (Int, Int) -> Unit
) {
    val radius = with(LocalDensity.current) { JOYSTICK_RADIUS.toPx() }
    val report by rememberUpdatedState(onChange)
    var origin by remember { mutableStateOf<Offset?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier.pointerInput(radius) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                first.consume()
                var activeId: PointerId = first.id
                var center = first.position
                origin = center
                knob = Offset.Zero
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.firstOrNull { it.id == activeId }
                        if (active == null || !active.pressed) {
                            // Hand the stick over to another finger still down in this zone.
                            val next = event.changes.firstOrNull { it.pressed && it.id != activeId }
                                ?: break
                            activeId = next.id
                            center = next.position
                            origin = center
                            knob = Offset.Zero
                            report(0, 0)
                        } else {
                            val raw = active.position - center
                            val dist = raw.getDistance()
                            val clamped = if (dist > radius) raw * (radius / dist) else raw
                            knob = clamped
                            report(
                                (clamped.x / radius * 127).roundToInt(),
                                (clamped.y / radius * 127).roundToInt()
                            )
                        }
                        event.changes.forEach { it.consume() }
                    }
                } finally {
                    origin = null
                    knob = Offset.Zero
                    report(0, 0)
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val active = origin != null
            val c = origin ?: Offset(size.width * idleCenter.x, size.height * idleCenter.y)
            val alpha = if (active) 0.6f else 0.25f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius,
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius * 0.4f,
                center = c + knob
            )
        }
    }
}

/** Held while the finger that pressed it stays down, even if it slides off the button. */
@Composable
fun PadButton(
    label: String,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    onPressedChange: (Boolean) -> Unit
) {
    val callback by rememberUpdatedState(onPressedChange)
    var pressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) Color.White.copy(alpha = 0.35f) else Color.Transparent)
            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    callback(true)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressed = false
                        callback(false)
                    }
                }
            }
    ) {
        Text(label, color = Color.White)
    }
}
