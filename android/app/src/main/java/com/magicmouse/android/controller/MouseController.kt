package com.magicmouse.android.controller

import android.content.Context
import com.magicmouse.android.network.Protocol
import com.magicmouse.android.network.UdpClient
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
    data class Connected(val host: String, val port: Int) : ConnectionState()
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
 * MouseController is the central orchestrator.
 *
 * Responsibilities:
 *   - Start/stop sensor reading
 *   - Run the sensor fusion + motion processing pipeline
 *   - Send motion packets at ~100 Hz via UDP
 *   - Forward gesture events as click/scroll packets
 *   - Expose state flows for the Compose UI to observe
 *
 * The sensor → network pipeline runs on a dedicated coroutine scope
 * using Dispatchers.Default (CPU-bound processing) to avoid blocking
 * the main thread or the sensor callback thread.
 */
class MouseController(context: Context) {

    // ── Sub-components ────────────────────────────────────────────────────────
    val sensorReader     = SensorReader(context)
    private val fusion   = SensorFusion()
    val motionProcessor  = MotionProcessor()
    val udpClient        = UdpClient()
    val gestureDetector  = GestureDetector { event -> handleGesture(event) }

    // ── Coroutine scope (lifecycle managed externally by ViewModel) ────────────
    private var scope: CoroutineScope? = null

    // ── UI-observable state flows ─────────────────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _sensorSnapshot = MutableStateFlow(SensorSnapshot())
    val sensorSnapshot: StateFlow<SensorSnapshot> = _sensorSnapshot.asStateFlow()

    // ── Send rate limiting ─────────────────────────────────────────────────────
    // We target ~100 Hz (10ms between packets). Since sensor events arrive at
    // ~100-200 Hz, we send every other reading rather than on every event.
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

    fun connect(host: String, port: Int = Protocol.DEFAULT_PORT) {
        scope?.launch {
            _connectionState.value = ConnectionState.Connecting
            val error = udpClient.connect(host, port)
            _connectionState.value = if (error == null) {
                ConnectionState.Connected(host, port)
            } else {
                ConnectionState.Error(error)
            }
        }
    }

    fun disconnect() {
        scope?.launch {
            udpClient.disconnect()
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
        // Collect gyro events and drive the fusion + motion pipeline
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
                    packetsSent = udpClient.packetsSent
                )

                // 4. Send motion packet (only if connected and significant movement)
                if (udpClient.isConnected) {
                    // Always send at full rate — let Windows smoother handle it
                    udpClient.sendMotion(dx, dy)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Gesture handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleGesture(event: GestureEvent) {
        // Notify motion processor that touch is active (adjusts dead zone)
        motionProcessor.isTouching = when (event) {
            is GestureEvent.DragStart -> true
            is GestureEvent.DragEnd   -> false
            else -> motionProcessor.isTouching
        }

        if (!udpClient.isConnected) return

        val packet = gestureDetector.gestureToPacket(event) ?: return
        scope?.launch(Dispatchers.IO) {
            udpClient.sendRaw(packet)
        }
    }
}
