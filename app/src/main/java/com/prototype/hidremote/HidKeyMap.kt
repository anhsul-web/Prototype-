package com.prototype.hidremote

import android.view.KeyEvent

data class KeyStroke(val modifiers: Int, val keyCode: Int)

/** Maps characters / Android key codes to USB HID Usage IDs (Keyboard page 0x07). */
object HidKeyMap {
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08

    const val KEY_ENTER = 0x28
    const val KEY_ESCAPE = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_DELETE = 0x4C
    const val KEY_RIGHT = 0x4F
    const val KEY_LEFT = 0x50
    const val KEY_DOWN = 0x51
    const val KEY_UP = 0x52

    private val unshifted: Map<Char, Int> = mapOf(
        '1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21, '5' to 0x22,
        '6' to 0x23, '7' to 0x24, '8' to 0x25, '9' to 0x26, '0' to 0x27,
        '\n' to KEY_ENTER, '\t' to KEY_TAB, ' ' to KEY_SPACE,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
    )

    private val shifted: Map<Char, Int> = mapOf(
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
        '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38
    )

    fun forChar(c: Char): KeyStroke? = when (c) {
        in 'a'..'z' -> KeyStroke(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> KeyStroke(MOD_LEFT_SHIFT, 0x04 + (c - 'A'))
        else -> unshifted[c]?.let { KeyStroke(0, it) }
            ?: shifted[c]?.let { KeyStroke(MOD_LEFT_SHIFT, it) }
    }

    /** Maps a (hardware) Android key event to a HID stroke, including Ctrl/Alt/Shift/Meta. */
    fun forAndroidKey(keyCode: Int, metaState: Int): KeyStroke? {
        var mods = 0
        if (metaState and KeyEvent.META_CTRL_ON != 0) mods = mods or MOD_LEFT_CTRL
        if (metaState and KeyEvent.META_SHIFT_ON != 0) mods = mods or MOD_LEFT_SHIFT
        if (metaState and KeyEvent.META_ALT_ON != 0) mods = mods or MOD_LEFT_ALT
        if (metaState and KeyEvent.META_META_ON != 0) mods = mods or MOD_LEFT_GUI
        val usage = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
            KeyEvent.KEYCODE_0 -> 0x27
            KeyEvent.KEYCODE_ENTER -> KEY_ENTER
            KeyEvent.KEYCODE_ESCAPE -> KEY_ESCAPE
            KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
            KeyEvent.KEYCODE_TAB -> KEY_TAB
            KeyEvent.KEYCODE_SPACE -> KEY_SPACE
            KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
            KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
            KeyEvent.KEYCODE_MINUS -> 0x2D
            KeyEvent.KEYCODE_EQUALS -> 0x2E
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            else -> return null
        }
        return KeyStroke(mods, usage)
    }
}
