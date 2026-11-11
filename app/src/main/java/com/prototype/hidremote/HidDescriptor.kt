package com.prototype.hidremote

/**
 * USB HID Report Map exposed through the Bluetooth SDP record.
 * Report ID 1 = boot-style keyboard (8 bytes), Report ID 2 = relative mouse with vertical wheel
 * and horizontal AC Pan (5 bytes), Report ID 3 = DualShock 4-style gamepad (9 bytes).
 */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_GAMEPAD = 3

    const val KEYBOARD_REPORT_SIZE = 8
    const val MOUSE_REPORT_SIZE = 5
    const val GAMEPAD_REPORT_SIZE = 9

    val REPORT_MAP: ByteArray = byteArrayOf(
        // ---------- Keyboard ----------
        0x05, 0x01,                   // Usage Page (Generic Desktop)
        0x09, 0x06,                   // Usage (Keyboard)
        0xA1.toByte(), 0x01,          // Collection (Application)
        0x85.toByte(), ID_KEYBOARD.toByte(), //   Report ID (1)
        0x05, 0x07,                   //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),          //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(),          //   Usage Maximum (Right GUI)
        0x15, 0x00,                   //   Logical Minimum (0)
        0x25, 0x01,                   //   Logical Maximum (1)
        0x75, 0x01,                   //   Report Size (1)
        0x95.toByte(), 0x08,          //   Report Count (8)
        0x81.toByte(), 0x02,          //   Input (Data, Variable, Absolute) - modifiers
        0x95.toByte(), 0x01,          //   Report Count (1)
        0x75, 0x08,                   //   Report Size (8)
        0x81.toByte(), 0x01,          //   Input (Constant) - reserved byte
        0x95.toByte(), 0x05,          //   Report Count (5)
        0x75, 0x01,                   //   Report Size (1)
        0x05, 0x08,                   //   Usage Page (LEDs)
        0x19, 0x01,                   //   Usage Minimum (Num Lock)
        0x29, 0x05,                   //   Usage Maximum (Kana)
        0x91.toByte(), 0x02,          //   Output (Data, Variable, Absolute) - LEDs
        0x95.toByte(), 0x01,          //   Report Count (1)
        0x75, 0x03,                   //   Report Size (3)
        0x91.toByte(), 0x01,          //   Output (Constant) - LED padding
        0x95.toByte(), 0x06,          //   Report Count (6)
        0x75, 0x08,                   //   Report Size (8)
        0x15, 0x00,                   //   Logical Minimum (0)
        0x25, 0x65,                   //   Logical Maximum (101)
        0x05, 0x07,                   //   Usage Page (Key Codes)
        0x19, 0x00,                   //   Usage Minimum (0)
        0x29, 0x65,                   //   Usage Maximum (101)
        0x81.toByte(), 0x00,          //   Input (Data, Array) - 6 key slots
        0xC0.toByte(),                // End Collection

        // ---------- Mouse ----------
        0x05, 0x01,                   // Usage Page (Generic Desktop)
        0x09, 0x02,                   // Usage (Mouse)
        0xA1.toByte(), 0x01,          // Collection (Application)
        0x85.toByte(), ID_MOUSE.toByte(), //   Report ID (2)
        0x09, 0x01,                   //   Usage (Pointer)
        0xA1.toByte(), 0x00,          //   Collection (Physical)
        0x05, 0x09,                   //     Usage Page (Buttons)
        0x19, 0x01,                   //     Usage Minimum (1)
        0x29, 0x03,                   //     Usage Maximum (3)
        0x15, 0x00,                   //     Logical Minimum (0)
        0x25, 0x01,                   //     Logical Maximum (1)
        0x95.toByte(), 0x03,          //     Report Count (3)
        0x75, 0x01,                   //     Report Size (1)
        0x81.toByte(), 0x02,          //     Input (Data, Variable, Absolute) - buttons
        0x95.toByte(), 0x01,          //     Report Count (1)
        0x75, 0x05,                   //     Report Size (5)
        0x81.toByte(), 0x01,          //     Input (Constant) - padding
        0x05, 0x01,                   //     Usage Page (Generic Desktop)
        0x09, 0x30,                   //     Usage (X)
        0x09, 0x31,                   //     Usage (Y)
        0x09, 0x38,                   //     Usage (Wheel)
        0x15, 0x81.toByte(),          //     Logical Minimum (-127)
        0x25, 0x7F,                   //     Logical Maximum (127)
        0x75, 0x08,                   //     Report Size (8)
        0x95.toByte(), 0x03,          //     Report Count (3)
        0x81.toByte(), 0x06,          //     Input (Data, Variable, Relative)
        0x05, 0x0C,                   //     Usage Page (Consumer)
        0x0A, 0x38, 0x02,             //     Usage (AC Pan) - horizontal scroll
        0x15, 0x81.toByte(),          //     Logical Minimum (-127)
        0x25, 0x7F,                   //     Logical Maximum (127)
        0x75, 0x08,                   //     Report Size (8)
        0x95.toByte(), 0x01,          //     Report Count (1)
        0x81.toByte(), 0x06,          //     Input (Data, Variable, Relative)
        0xC0.toByte(),                //   End Collection
        0xC0.toByte(),                // End Collection

        // ---------- Gamepad (DualShock 4 layout of its report 0x01, first 9 bytes) ----------
        0x05, 0x01,                   // Usage Page (Generic Desktop)
        0x09, 0x05,                   // Usage (Game Pad)
        0xA1.toByte(), 0x01,          // Collection (Application)
        0x85.toByte(), ID_GAMEPAD.toByte(), //   Report ID (3)
        0x09, 0x30,                   //   Usage (X)  - left stick
        0x09, 0x31,                   //   Usage (Y)  - left stick
        0x09, 0x32,                   //   Usage (Z)  - right stick X
        0x09, 0x35,                   //   Usage (Rz) - right stick Y
        0x15, 0x00,                   //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00,    //   Logical Maximum (255)
        0x75, 0x08,                   //   Report Size (8)
        0x95.toByte(), 0x04,          //   Report Count (4)
        0x81.toByte(), 0x02,          //   Input (Data, Variable, Absolute) - sticks
        0x09, 0x39,                   //   Usage (Hat Switch) - D-pad
        0x15, 0x00,                   //   Logical Minimum (0)
        0x25, 0x07,                   //   Logical Maximum (7)
        0x35, 0x00,                   //   Physical Minimum (0)
        0x46, 0x3B, 0x01,             //   Physical Maximum (315)
        0x65, 0x14,                   //   Unit (Degrees)
        0x75, 0x04,                   //   Report Size (4)
        0x95.toByte(), 0x01,          //   Report Count (1)
        0x81.toByte(), 0x42,          //   Input (Data, Variable, Absolute, Null State) - 8 = centered
        0x65, 0x00,                   //   Unit (None)
        0x05, 0x09,                   //   Usage Page (Buttons)
        0x19, 0x01,                   //   Usage Minimum (1)
        0x29, 0x0E,                   //   Usage Maximum (14)
        0x15, 0x00,                   //   Logical Minimum (0)
        0x25, 0x01,                   //   Logical Maximum (1)
        0x75, 0x01,                   //   Report Size (1)
        0x95.toByte(), 0x0E,          //   Report Count (14)
        0x81.toByte(), 0x02,          //   Input (Data, Variable, Absolute) - buttons
        0x06, 0x00, 0xFF.toByte(),    //   Usage Page (Vendor Defined 0xFF00)
        0x09, 0x20,                   //   Usage (0x20) - report counter
        0x75, 0x06,                   //   Report Size (6)
        0x95.toByte(), 0x01,          //   Report Count (1)
        0x15, 0x00,                   //   Logical Minimum (0)
        0x25, 0x3F,                   //   Logical Maximum (63)
        0x81.toByte(), 0x02,          //   Input (Data, Variable, Absolute)
        0x05, 0x01,                   //   Usage Page (Generic Desktop)
        0x09, 0x33,                   //   Usage (Rx) - L2 analog
        0x09, 0x34,                   //   Usage (Ry) - R2 analog
        0x15, 0x00,                   //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00,    //   Logical Maximum (255)
        0x75, 0x08,                   //   Report Size (8)
        0x95.toByte(), 0x02,          //   Report Count (2)
        0x81.toByte(), 0x02,          //   Input (Data, Variable, Absolute) - triggers
        0xC0.toByte()                 // End Collection
    )
}
