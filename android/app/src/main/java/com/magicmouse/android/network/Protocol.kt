package com.magicmouse.android.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * MagicMouse UDP Packet Protocol  (v1)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * All packets are LITTLE-ENDIAN binary.
 * Max packet size: 24 bytes (fits in a single UDP MTU).
 *
 * Byte layout:
 *   [0]     u8   packetType    — see PacketType constants
 *   [1]     u8   sequenceNum  — wrapping 0..255, used to detect out-of-order
 *   [2-5]   f32  dx           — cursor delta X (pixels, right=positive)
 *   [6-9]   f32  dy           — cursor delta Y (pixels, down=positive)
 *   [10]    u8   buttonState  — bit flags: bit0=left, bit1=right, bit2=middle
 *   [11]    u8   clickType    — 0=none,1=singleClick,2=doubleClick,3=press,4=release
 *   [12-15] f32  scrollDy     — vertical scroll (positive=down)
 *   [16-19] f32  scrollDx     — horizontal scroll (positive=right)
 *   [20-23] u32  timestampMs  — sender timestamp (for latency measurement)
 *
 * Total: 24 bytes
 * ─────────────────────────────────────────────────────────────────────────────
 */
object Protocol {

    const val PACKET_SIZE = 24
    const val DEFAULT_PORT = 5555

    // ── Packet types ──────────────────────────────────────────────────────────
    object PacketType {
        const val MOTION: Byte    = 0x01  // cursor movement only
        const val CLICK: Byte     = 0x02  // click event (no movement)
        const val SCROLL: Byte    = 0x03  // scroll event
        const val COMBO: Byte     = 0x04  // motion + gesture combined
        const val HELLO: Byte     = 0x10  // handshake: Android → Windows
        const val ACK: Byte       = 0x11  // handshake: Windows → Android
        const val BYE: Byte       = 0x12  // disconnect notification
        const val PING: Byte      = 0x13  // keepalive
    }

    // ── Button state bit flags ────────────────────────────────────────────────
    object Button {
        const val LEFT:   Int = 0b001
        const val RIGHT:  Int = 0b010
        const val MIDDLE: Int = 0b100
    }

    // ── Click types ───────────────────────────────────────────────────────────
    object ClickType {
        const val NONE:         Byte = 0
        const val SINGLE_CLICK: Byte = 1
        const val DOUBLE_CLICK: Byte = 2
        const val PRESS:        Byte = 3   // mouse button down (for drag)
        const val RELEASE:      Byte = 4   // mouse button up (end drag)
    }

    private var sequenceNum: Byte = 0
    private fun nextSeq(): Byte = sequenceNum++

    // ── Builders ──────────────────────────────────────────────────────────────

    fun buildMotionPacket(dx: Float, dy: Float): ByteArray =
        buildPacket(PacketType.MOTION, dx = dx, dy = dy)

    fun buildClickPacket(clickType: Byte, buttonState: Int = Button.LEFT): ByteArray =
        buildPacket(PacketType.CLICK, clickType = clickType, buttonState = buttonState)

    fun buildScrollPacket(scrollDy: Float, scrollDx: Float = 0f): ByteArray =
        buildPacket(PacketType.SCROLL, scrollDy = scrollDy, scrollDx = scrollDx)

    fun buildComboPacket(
        dx: Float, dy: Float,
        scrollDy: Float = 0f, scrollDx: Float = 0f,
        clickType: Byte = ClickType.NONE, buttonState: Int = 0
    ): ByteArray = buildPacket(
        PacketType.COMBO, dx, dy, buttonState, clickType, scrollDy, scrollDx
    )

    fun buildHelloPacket(): ByteArray =
        buildPacket(PacketType.HELLO)

    fun buildByePacket(): ByteArray =
        buildPacket(PacketType.BYE)

    fun buildPingPacket(): ByteArray =
        buildPacket(PacketType.PING)

    // ── Internal builder ──────────────────────────────────────────────────────

    private fun buildPacket(
        type: Byte,
        dx: Float = 0f,
        dy: Float = 0f,
        buttonState: Int = 0,
        clickType: Byte = ClickType.NONE,
        scrollDy: Float = 0f,
        scrollDx: Float = 0f
    ): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(type)
        buf.put(nextSeq())
        buf.putFloat(dx)
        buf.putFloat(dy)
        buf.put(buttonState.toByte())
        buf.put(clickType)
        buf.putFloat(scrollDy)
        buf.putFloat(scrollDx)
        buf.putInt(System.currentTimeMillis().toInt())
        return buf.array()
    }
}
