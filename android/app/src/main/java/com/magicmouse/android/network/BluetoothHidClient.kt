package com.magicmouse.android.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Bluetooth HID Device client enabling Android to act as a native Bluetooth Mouse
 * to a Windows PC without needing any PC receiver app.
 */
class BluetoothHidClient(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null

    private var buttonStateBits: Int = 0
    private var totalPacketsSent: Long = 0L

    val packetsSent: Long get() = totalPacketsSent
    val isConnected: Boolean get() = connectedHost != null && hidDevice != null

    companion object {
        private const val REPORT_ID_MOUSE = 1

        private val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            // Buttons (Left, Right, Middle)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x01.toByte(), //     Input (Constant)
            // X, Y Relative Axis
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x02.toByte(), //     Report Count (2)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            // Scroll Wheel
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection
            0xC0.toByte()                 // End Collection
        )
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedHost == device) {
                    connectedHost = null
                }
            }
        }
    }

    init {
        try {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Magic Mouse",
            "Magic Mouse Air & Touchpad",
            "MagicMouse",
            0x40.toByte(), // Subclass mouse
            MOUSE_REPORT_DESCRIPTOR
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            0, 0, 0, 0, 0
        )
        try {
            hidDevice?.registerApp(sdp, null, qos, Executors.newSingleThreadExecutor(), hidCallback)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceItem> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return try {
            adapter.bondedDevices?.map {
                BluetoothDeviceItem(name = it.name ?: "Unknown", address = it.address)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String): String? {
        val adapter = bluetoothAdapter ?: return "Bluetooth not available"
        if (!adapter.isEnabled) return "Bluetooth is turned off"
        val device = adapter.getRemoteDevice(deviceAddress) ?: return "Device not found"
        
        return try {
            val success = hidDevice?.connect(device) ?: false
            if (success) null else "Failed to initiate Bluetooth HID connection"
        } catch (e: Exception) {
            e.message ?: "Connection error"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val host = connectedHost ?: return
        try {
            hidDevice?.disconnect(host)
        } catch (_: Exception) {}
        connectedHost = null
    }

    @SuppressLint("MissingPermission")
    fun sendReport(buttons: Int, dx: Float, dy: Float, scroll: Float = 0f) {
        val hid = hidDevice ?: return
        val host = connectedHost ?: return

        val bX = dx.coerceIn(-127f, 127f).toInt().toByte()
        val bY = dy.coerceIn(-127f, 127f).toInt().toByte()
        val bScroll = scroll.coerceIn(-127f, 127f).toInt().toByte()

        val report = byteArrayOf(buttons.toByte(), bX, bY, bScroll)
        try {
            hid.sendReport(host, REPORT_ID_MOUSE, report)
            totalPacketsSent++
        } catch (_: Exception) {}
    }

    fun sendMotion(dx: Float, dy: Float) {
        sendReport(buttonStateBits, dx, dy, 0f)
    }

    fun sendClick(clickType: Byte, buttonState: Int = Protocol.Button.LEFT) {
        buttonStateBits = when (clickType) {
            Protocol.ClickType.SINGLE_CLICK -> buttonState
            Protocol.ClickType.PRESS -> buttonState
            Protocol.ClickType.RELEASE -> 0
            Protocol.ClickType.DOUBLE_CLICK -> buttonState
            else -> buttonStateBits
        }
        sendReport(buttonStateBits, 0f, 0f, 0f)
        if (clickType == Protocol.ClickType.SINGLE_CLICK || clickType == Protocol.ClickType.DOUBLE_CLICK) {
            buttonStateBits = 0
            sendReport(0, 0f, 0f, 0f)
        }
    }

    fun sendScroll(scrollDy: Float, scrollDx: Float = 0f) {
        sendReport(buttonStateBits, 0f, 0f, -scrollDy)
    }

    fun sendCombo(dx: Float, dy: Float, scrollDy: Float = 0f, scrollDx: Float = 0f,
                  clickType: Byte = Protocol.ClickType.NONE, buttonState: Int = 0) {
        if (clickType != Protocol.ClickType.NONE) {
            buttonStateBits = when (clickType) {
                Protocol.ClickType.PRESS -> buttonState
                Protocol.ClickType.RELEASE -> 0
                else -> buttonState
            }
        }
        sendReport(buttonStateBits, dx, dy, -scrollDy)
    }
}
