package com.prototype.hidremote

/**
 * USB HID Report Map exposed through the Bluetooth SDP record.
 * Report ID 1 = boot-style keyboard (8 bytes), Report ID 2 = relative mouse (4 bytes).
 */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2

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
        0xC0.toByte(),                //   End Collection
        0xC0.toByte()                 // End Collection
    )
}
