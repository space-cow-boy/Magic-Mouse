package com.magicmouse.android.controller

import android.content.Context
import com.magicmouse.android.network.BluetoothDeviceItem
import com.magicmouse.android.network.BluetoothHidClient
import com.magicmouse.android.network.Protocol
import com.magicmouse.android.sensor.FusedOrientation
import com.magicmouse.android.sensor.MotionProcessor
import com.magicmouse.android.sensor.SensorFusion
import com.magicmouse.android.sensor.SensorReader
import com.magicmouse.android.touch.GestureDetector
import com.magicmouse.android.touch.GestureEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * High-level connection state exposed to the UI.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting   : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
    data class Error(val message: String)    : ConnectionState()
}

/**
 * Snapshot of current sensor data for the debug/Phase 1 display.
 */
data class SensorSnapshot(
    val gyroX: Float = 0f, val gyroY: Float = 0f, val gyroZ: Float = 0f,
    val accelX: Float = 0f, val accelY: Float = 0f, val accelZ: Float = 0f,
    val pitch: Float = 0f, val yaw: Float = 0f, val roll: Float = 0f,
    val cursorDx: Float = 0f, val cursorDy: Float = 0f,
    val packetsSent: Long = 0L
)

/**
 * MouseController is the central orchestrator using native Bluetooth HID.
 */
class MouseController(context: Context) {

    // ── Sub-components ────────────────────────────────────────────────────────
    val sensorReader     = SensorReader(context)
    private val fusion   = SensorFusion()
    val motionProcessor  = MotionProcessor()
    val bluetoothHidClient = BluetoothHidClient(context)
    val gestureDetector  = GestureDetector { event -> handleGesture(event) }

    // ── Coroutine scope (lifecycle managed externally by ViewModel) ────────────
    private var scope: CoroutineScope? = null

    // ── UI-observable state flows ─────────────────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sensorSnapshot = MutableStateFlow(SensorSnapshot())
    val sensorSnapshot: StateFlow<SensorSnapshot> = _sensorSnapshot.asStateFlow()

    private var frameCount = 0L

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun start(externalScope: CoroutineScope) {
        scope = externalScope
        sensorReader.start(samplingUs = 10_000)   // Request 100 Hz
        startSensorPipeline(externalScope)
    }

    fun stop() {
        sensorReader.stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Connection management
    // ─────────────────────────────────────────────────────────────────────────

    fun getPairedDevices(): List<BluetoothDeviceItem> = bluetoothHidClient.getPairedDevices()

    fun connect(deviceAddress: String, deviceName: String = "PC") {
        scope?.launch {
            _connectionState.value = ConnectionState.Connecting
            val error = bluetoothHidClient.connect(deviceAddress)
            _connectionState.value = if (error == null) {
                ConnectionState.Connected(deviceName, deviceAddress)
            } else {
                ConnectionState.Error(error)
            }
        }
    }

    fun disconnect() {
        scope?.launch {
            bluetoothHidClient.disconnect()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Calibration
    // ─────────────────────────────────────────────────────────────────────────

    fun recenter() {
        val current = sensorSnapshot.value
        val orientation = FusedOrientation(current.pitch, current.yaw, current.roll)
        motionProcessor.recenter(orientation)
        fusion.reset()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sensor Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private fun startSensorPipeline(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            sensorReader.gyroFlow.collect { gyro ->
                val accel = sensorReader.latestAccel

                // 1. Fuse sensors
                val orientation = fusion.update(gyro, accel)

                // 2. Convert to cursor delta
                val (dx, dy) = motionProcessor.process(orientation)

                // 3. Update UI snapshot (every frame)
                frameCount++
                _sensorSnapshot.value = SensorSnapshot(
                    gyroX = gyro.x, gyroY = gyro.y, gyroZ = gyro.z,
                    accelX = accel.x, accelY = accel.y, accelZ = accel.z,
                    pitch = orientation.pitch, yaw = orientation.yaw, roll = orientation.roll,
                    cursorDx = dx, cursorDy = dy,
                    packetsSent = bluetoothHidClient.packetsSent
                )

                // 4. Send motion report (only if connected)
                if (bluetoothHidClient.isConnected) {
                    bluetoothHidClient.sendMotion(dx, dy)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Gesture handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleGesture(event: GestureEvent) {
        motionProcessor.isTouching = when (event) {
            is GestureEvent.DragStart -> true
            is GestureEvent.DragEnd   -> false
            else -> motionProcessor.isTouching
        }

        if (!bluetoothHidClient.isConnected) return

        when (event) {
            is GestureEvent.LeftClick   -> bluetoothHidClient.sendClick(Protocol.ClickType.SINGLE_CLICK, Protocol.Button.LEFT)
            is GestureEvent.DoubleClick -> bluetoothHidClient.sendClick(Protocol.ClickType.DOUBLE_CLICK, Protocol.Button.LEFT)
            is GestureEvent.RightClick  -> bluetoothHidClient.sendClick(Protocol.ClickType.SINGLE_CLICK, Protocol.Button.RIGHT)
            is GestureEvent.DragStart   -> bluetoothHidClient.sendClick(Protocol.ClickType.PRESS, Protocol.Button.LEFT)
            is GestureEvent.DragEnd     -> bluetoothHidClient.sendClick(Protocol.ClickType.RELEASE, Protocol.Button.LEFT)
            is GestureEvent.ScrollV     -> bluetoothHidClient.sendScroll(event.amount)
            is GestureEvent.ScrollH     -> bluetoothHidClient.sendScroll(0f, event.amount)
            is GestureEvent.DragMove    -> bluetoothHidClient.sendMotion(event.dx * 2f, event.dy * 2f)
            is GestureEvent.TrackpadMove -> bluetoothHidClient.sendMotion(event.dx * 2f, event.dy * 2f)
        }
    }
}
