package com.magicmouse.android.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Raw readings from a single sensor event.
 */
data class SensorReading(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNs: Long   // nanoseconds (from SensorEvent.timestamp)
)

/**
 * Both sensors bundled together for a single processing tick.
 */
data class CombinedSensorData(
    val gyro: SensorReading,
    val accel: SensorReading
)

/**
 * Reads raw gyroscope and accelerometer data from the hardware and exposes
 * them as Kotlin Flows.
 *
 * Usage:
 *   val reader = SensorReader(context)
 *   reader.start()
 *   reader.gyroFlow.collect { reading -> ... }
 *   reader.stop()
 */
class SensorReader(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // --- Public availability flags ---
    val hasGyroscope: Boolean get() = gyroSensor != null
    val hasAccelerometer: Boolean get() = accelSensor != null

    // --- Flows (replay=1 so new collectors get the latest value immediately) ---
    private val _gyroFlow = MutableSharedFlow<SensorReading>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val gyroFlow: SharedFlow<SensorReading> = _gyroFlow.asSharedFlow()

    private val _accelFlow = MutableSharedFlow<SensorReading>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val accelFlow: SharedFlow<SensorReading> = _accelFlow.asSharedFlow()

    // --- Latest values for synchronous access ---
    @Volatile var latestGyro: SensorReading = SensorReading(0f, 0f, 0f, 0L)
        private set
    @Volatile var latestAccel: SensorReading = SensorReading(0f, 0f, 0f, 0L)
        private set

    /**
     * Start receiving sensor events.
     * @param samplingUs Target sampling rate in microseconds.
     *                   SENSOR_DELAY_GAME = 20_000 µs = 50 Hz
     *                   10_000 µs = 100 Hz (for lower latency)
     */
    fun start(samplingUs: Int = 10_000) {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, samplingUs)
        }
        accelSensor?.let {
            // Accelerometer only needs ~50 Hz for drift correction
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = SensorReading(
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
            timestampNs = event.timestamp
        )
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = reading
                _gyroFlow.tryEmit(reading)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = reading
                _accelFlow.tryEmit(reading)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used for this application
    }
}
