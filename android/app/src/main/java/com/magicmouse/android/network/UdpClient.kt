package com.magicmouse.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

/**
 * UDP client that sends MagicMouse packets to the Windows receiver.
 *
 * Design choices:
 *   - A single DatagramSocket is reused across all sends (avoids per-send overhead)
 *   - Fire-and-forget: no acknowledgment expected for motion packets (too slow)
 *   - Thread-safe: send() can be called from any coroutine
 *   - Non-blocking: uses Dispatchers.IO to avoid blocking the sensor processing thread
 */
class UdpClient {

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = Protocol.DEFAULT_PORT

    /** Total packets sent (for debug display). */
    @Volatile var packetsSent: Long = 0L
        private set

    /** Last measured round-trip time (ms). -1 = not measured. */
    @Volatile var lastRttMs: Long = -1L

    /**
     * Open the socket and resolve the target address.
     * @return null on success, error message on failure.
     */
    suspend fun connect(host: String, port: Int = Protocol.DEFAULT_PORT): String? =
        withContext(Dispatchers.IO) {
            try {
                socket?.close()
                targetAddress = InetAddress.getByName(host)
                targetPort = port
                socket = DatagramSocket().apply {
                    soTimeout = 0       // non-blocking receives (we don't receive much)
                    sendBufferSize = 4096
                }
                // Send handshake
                sendRaw(Protocol.buildHelloPacket())
                null
            } catch (e: Exception) {
                e.message ?: "Unknown connection error"
            }
        }

    /**
     * Send a pre-built packet byte array. Non-suspending for sensor hot path.
     * Returns false if the socket is not connected.
     */
    fun sendRaw(data: ByteArray): Boolean {
        val sock = socket ?: return false
        val addr = targetAddress ?: return false
        return try {
            val packet = DatagramPacket(data, data.size, addr, targetPort)
            sock.send(packet)
            packetsSent++
            true
        } catch (e: SocketException) {
            false
        }
    }

    /**
     * Convenience send helpers.
     */
    fun sendMotion(dx: Float, dy: Float) =
        sendRaw(Protocol.buildMotionPacket(dx, dy))

    fun sendClick(clickType: Byte, buttonState: Int = Protocol.Button.LEFT) =
        sendRaw(Protocol.buildClickPacket(clickType, buttonState))

    fun sendScroll(scrollDy: Float, scrollDx: Float = 0f) =
        sendRaw(Protocol.buildScrollPacket(scrollDy, scrollDx))

    fun sendCombo(dx: Float, dy: Float, scrollDy: Float = 0f, scrollDx: Float = 0f,
                  clickType: Byte = Protocol.ClickType.NONE, buttonState: Int = 0) =
        sendRaw(Protocol.buildComboPacket(dx, dy, scrollDy, scrollDx, clickType, buttonState))

    /**
     * Disconnect: send BYE packet and close socket.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { sendRaw(Protocol.buildByePacket()) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        targetAddress = null
    }

    val isConnected: Boolean get() = socket != null && !socket!!.isClosed
}
