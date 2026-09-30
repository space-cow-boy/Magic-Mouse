package com.magicmouse.android.sensor

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Fused orientation angles derived from the complementary filter.
 *
 * @property pitch  Tilt forward/backward  (degrees). Maps to cursor Y.
 * @property yaw    Rotation left/right     (degrees). Maps to cursor X.
 * @property roll   Sideways tilt           (degrees). Not used for mouse.
 */
data class FusedOrientation(
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val roll: Float = 0f
)

/**
 * Complementary filter that fuses gyroscope + accelerometer into stable orientation.
 *
 * The complementary filter is defined as:
 *
 *   orientation = α * (orientation + gyro_rate * dt) + (1 - α) * accel_estimate
 *
 * where:
 *   α       = GYRO_WEIGHT (how much we trust the gyroscope vs accelerometer)
 *   dt      = time delta between sensor readings (seconds)
 *   gyro_rate * dt = change in angle from gyroscope integration
 *   accel_estimate = absolute tilt angle derived from gravity vector
 *
 * Why this works:
 *   - Gyroscope:     Very accurate short-term, drifts long-term
 *   - Accelerometer: Noisy short-term, accurate long-term (gravity never drifts)
 *   - Combined:      Best of both worlds — responsive AND drift-free
 *
 * α = 0.98 means 98% gyro, 2% accelerometer each update cycle.
 */
class SensorFusion {

    companion object {
        /** How much to trust the gyroscope (vs accelerometer). Range: 0..1 */
        const val GYRO_WEIGHT = 0.98f

        /** Clamp accelerometer-derived pitch to avoid singularities */
        private const val PITCH_CLAMP_DEGREES = 85f
    }

    private var fusedPitch = 0f
    private var fusedYaw   = 0f
    private var fusedRoll  = 0f

    private var lastTimestampNs = 0L
    private var initialized = false

    /** Current fused orientation (read-only snapshot). */
    val orientation: FusedOrientation
        get() = FusedOrientation(fusedPitch, fusedYaw, fusedRoll)

    /**
     * Feed new sensor readings into the filter.
     * Should be called every time a new gyroscope reading arrives.
     *
     * @param gyro  Latest gyroscope reading (rad/s)
     * @param accel Latest accelerometer reading (m/s²)
     * @return Updated fused orientation
     */
    fun update(gyro: SensorReading, accel: SensorReading): FusedOrientation {
        // ── 1. Compute dt (seconds) from sensor timestamps ──────────────────
        val nowNs = gyro.timestampNs
        if (!initialized || lastTimestampNs == 0L) {
            lastTimestampNs = nowNs
            initialized = true
            // Seed initial orientation from accelerometer
            seedFromAccelerometer(accel)
            return orientation
        }
        val dtNs = nowNs - lastTimestampNs
        lastTimestampNs = nowNs

        if (dtNs <= 0 || dtNs > 100_000_000L) {
            // Timestamp gap too large (>100ms) — skip and reset timestamp
            // This handles the case where the app was paused
            lastTimestampNs = nowNs
            return orientation
        }
        val dt = dtNs / 1_000_000_000f  // nanoseconds → seconds

        // ── 2. Gyroscope integration (predict step) ──────────────────────────
        // gyro.x = angular velocity around X axis (rad/s) → affects pitch
        // gyro.z = angular velocity around Z axis (rad/s) → affects yaw
        // gyro.y = angular velocity around Y axis (rad/s) → affects roll
        //
        // NOTE: The exact sign and axis mapping depends on phone orientation.
        // These signs assume portrait mode, screen facing user.
        val gyroPitchDelta = Math.toDegrees((-gyro.x * dt).toDouble()).toFloat()
        val gyroYawDelta   = Math.toDegrees((-gyro.z * dt).toDouble()).toFloat()
        val gyroRollDelta  = Math.toDegrees(( gyro.y * dt).toDouble()).toFloat()

        val gyroEstPitch = fusedPitch + gyroPitchDelta
        val gyroEstYaw   = fusedYaw   + gyroYawDelta
        val gyroEstRoll  = fusedRoll  + gyroRollDelta

        // ── 3. Accelerometer absolute tilt estimate (correct step) ───────────
        val ax = accel.x
        val ay = accel.y
        val az = accel.z
        val accelMagnitude = sqrt(ax * ax + ay * ay + az * az)

        // Only use accelerometer if it's within 10% of 1g (9.8 m/s²)
        // If the magnitude is very different, the user is in free-fall or
        // undergoing strong linear acceleration — accelerometer is unreliable
        val gravityOk = accelMagnitude in (8.0f..11.0f)

        fusedRoll = if (gravityOk) {
            val accelRoll  = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
            GYRO_WEIGHT * gyroEstRoll + (1f - GYRO_WEIGHT) * accelRoll
        } else {
            gyroEstRoll
        }

        fusedPitch = if (gravityOk) {
            val accelPitch = Math.toDegrees(atan2(-ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                .coerceIn(-PITCH_CLAMP_DEGREES, PITCH_CLAMP_DEGREES)
            GYRO_WEIGHT * gyroEstPitch + (1f - GYRO_WEIGHT) * accelPitch
        } else {
            gyroEstPitch
        }

        // Yaw has no absolute accelerometer reference (gravity doesn't constrain it)
        // Pure gyro integration for yaw — recenter button is the correction mechanism
        fusedYaw = gyroEstYaw

        return orientation
    }

    /**
     * Reset the filter. The next update() call will re-seed from accelerometer.
     * Call this when the app regains focus or the user presses Recenter.
     */
    fun reset() {
        initialized = false
        lastTimestampNs = 0L
        fusedPitch = 0f
        fusedYaw = 0f
        fusedRoll = 0f
    }

    private fun seedFromAccelerometer(accel: SensorReading) {
        val ax = accel.x; val ay = accel.y; val az = accel.z
        fusedPitch = Math.toDegrees(atan2(-ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
        fusedRoll  = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
        fusedYaw   = 0f
    }
}
