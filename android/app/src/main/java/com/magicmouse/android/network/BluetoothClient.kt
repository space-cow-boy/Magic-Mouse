package com.magicmouse.android.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

data class BluetoothDeviceItem(
    val name: String,
    val address: String
)

/**
 * Bluetooth Classic RFCOMM client that sends MagicMouse packets to a paired Windows receiver.
 */
class BluetoothClient(private val context: Context) {

    companion object {
        // Standard Serial Port Profile (SPP) UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    /** Total packets sent (for debug display). */
    @Volatile var packetsSent: Long = 0L
        private set

    /**
     * Get list of paired Bluetooth devices. Requires BLUETOOTH_CONNECT permission on Android 12+.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceItem> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        try {
            val bondedDevices = adapter.bondedDevices ?: return emptyList()
            return bondedDevices.map { device ->
                BluetoothDeviceItem(
                    name = device.name ?: "Unknown Device",
                    address = device.address
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Connect to a Bluetooth device by MAC address.
     * @return null on success, error message on failure.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String): String? =
        withContext(Dispatchers.IO) {
            var tmpSocket: BluetoothSocket? = null
            try {
                disconnectInternal()
                val adapter = bluetoothAdapter ?: return@withContext "Bluetooth is not available"
                if (!adapter.isEnabled) return@withContext "Bluetooth is turned off"

                val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
                    ?: return@withContext "Device not found"

                // Cancel discovery if ongoing
                adapter.cancelDiscovery()

                // Try secure connection first, then insecure fallback
                try {
                    tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    tmpSocket.connect()
                } catch (e: IOException) {
                    try { tmpSocket?.close() } catch (_: Exception) {}
                    tmpSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    tmpSocket.connect()
                }

                socket = tmpSocket
                outputStream = tmpSocket.outputStream
                packetsSent = 0L

                // Small delay to stabilize RFCOMM socket
                delay(200)

                // Send handshake packet
                try {
                    sendRaw(Protocol.buildHelloPacket())
                } catch (_: Exception) {}

                null
            } catch (e: SecurityException) {
                try { tmpSocket?.close() } catch (_: Exception) {}
                disconnectInternal()
                "Permission denied: Missing Bluetooth permissions"
            } catch (e: Exception) {
                try { tmpSocket?.close() } catch (_: Exception) {}
                disconnectInternal()
                val msg = e.message ?: e.javaClass.simpleName
                when {
                    msg.contains("read failed") || msg.contains("socket might closed") ->
                        "Connection failed: PC receiver app not running or listening."
                    msg.contains("Connection refused") ->
                        "Connection refused by PC."
                    else ->
                        "Bluetooth error: $msg"
                }
            }
        }

    /**
     * Send pre-built packet byte array over Bluetooth output stream.
     */
    fun sendRaw(data: ByteArray): Boolean {
        val out = outputStream ?: return false
        return try {
            out.write(data)
            out.flush()
            packetsSent++
            true
        } catch (_: Exception) {
            false
        }
    }

    // Convenience send helpers
    fun sendMotion(dx: Float, dy: Float) =
        sendRaw(Protocol.buildMotionPacket(dx, dy))

    fun sendClick(clickType: Byte, buttonState: Int = Protocol.Button.LEFT) =
        sendRaw(Protocol.buildClickPacket(clickType, buttonState))

    fun sendScroll(scrollDy: Float, scrollDx: Float = 0f) =
        sendRaw(Protocol.buildScrollPacket(scrollDy, scrollDx))

    fun sendCombo(dx: Float, dy: Float, scrollDy: Float = 0f, scrollDx: Float = 0f,
                  clickType: Byte = Protocol.ClickType.NONE, buttonState: Int = 0) =
        sendRaw(Protocol.buildComboPacket(dx, dy, scrollDy, scrollDx, clickType, buttonState))

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            sendRaw(Protocol.buildByePacket())
        } catch (_: Exception) {}
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null
    }

    val isConnected: Boolean
        get() = socket != null
}
