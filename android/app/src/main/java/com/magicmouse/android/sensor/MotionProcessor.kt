package com.magicmouse.android.sensor

import kotlin.math.abs
import kotlin.math.sign

/**
 * Converts fused orientation deltas into cursor pixel deltas.
 *
 * Pipeline:
 *   1. Compute frame-to-frame orientation delta (degrees)
 *   2. Apply dead zone  → suppress micro-movements / hand tremor
 *   3. Apply non-linear acceleration curve → slow=precise, fast=large jump
 *   4. Scale to pixel delta
 *
 * All parameters are publicly mutable so the UI can expose sliders.
 */
class MotionProcessor {

    // ── Tunable parameters (exposed for UI sliders) ──────────────────────────

    /**
     * Dead zone in degrees. Movements smaller than this are ignored.
     * Typical range: 0.1 .. 2.0
     */
    var deadZoneDegrees: Float = 0.15f

    /**
     * Base sensitivity: pixels moved per degree of phone rotation (slow movements).
     * Typical range: 5 .. 40
     */
    var baseSensitivity: Float = 15f

    /**
     * Threshold speed (deg/frame) above which acceleration kicks in.
     * Typical range: 1.0 .. 5.0
     */
    var accelerationThreshold: Float = 2.0f

    /**
     * Acceleration multiplier. How aggressively fast motions are boosted.
     * Typical range: 0.5 .. 5.0
     */
    var accelerationFactor: Float = 2.5f

    /**
     * Touch dead zone multiplier. When the user is touching the screen,
     * the dead zone is scaled by this factor to absorb tap-induced jitter.
     * Typical range: 1.5 .. 3.0
     */
    var touchDeadZoneMultiplier: Float = 2.0f

    // ── Internal state ────────────────────────────────────────────────────────
    private var prevPitch = 0f
    private var prevYaw   = 0f
    private var calibratedPitch = 0f
    private var calibratedYaw   = 0f
    private var isCalibrated = false

    /** Set to true while the user is touching the screen (suppresses jitter). */
    @Volatile var isTouching: Boolean = false

    // ── Calibration ──────────────────────────────────────────────────────────

    /**
     * Recenter: store current orientation as the reference baseline.
     * After calling this, the cursor will treat the current phone position as "home".
     */
    fun recenter(currentOrientation: FusedOrientation) {
        calibratedPitch = currentOrientation.pitch
        calibratedYaw   = currentOrientation.yaw
        prevPitch = currentOrientation.pitch
        prevYaw   = currentOrientation.yaw
        isCalibrated = true
    }

    /**
     * Process a new fused orientation reading into cursor pixel deltas.
     *
     * @return Pair(dx, dy) in pixels. Positive dx = right, positive dy = down.
     */
    fun process(orientation: FusedOrientation): Pair<Float, Float> {
        if (!isCalibrated) {
            // Auto-calibrate on first call
            recenter(orientation)
            return Pair(0f, 0f)
        }

        // ── Step 1: Frame-to-frame delta ──────────────────────────────────────
        val rawDeltaYaw   = orientation.yaw   - prevYaw
        val rawDeltaPitch = orientation.pitch  - prevPitch

        prevYaw   = orientation.yaw
        prevPitch = orientation.pitch

        // ── Step 2: Apply dead zone ───────────────────────────────────────────
        val effectiveDeadZone = if (isTouching) {
            deadZoneDegrees * touchDeadZoneMultiplier
        } else {
            deadZoneDegrees
        }

        val filteredDeltaYaw   = applyDeadZone(rawDeltaYaw,   effectiveDeadZone)
        val filteredDeltaPitch = applyDeadZone(rawDeltaPitch, effectiveDeadZone)

        // ── Step 3 & 4: Acceleration curve → pixel delta ─────────────────────
        val dx = applyAccelerationCurve(filteredDeltaYaw)
        val dy = applyAccelerationCurve(filteredDeltaPitch)

        return Pair(dx, dy)
    }

    /**
     * Dead zone with smooth transition (avoids snapping at the boundary).
     *
     * Instead of: value < deadZone → 0 (hard cutoff, causes visible "snap")
     * We do:      value - sign(value)*deadZone  (smooth: starts from 0, grows linearly)
     */
    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        return if (abs(value) < deadZone) 0f
        else (value - value.sign * deadZone)
    }

    /**
     * Non-linear sensitivity curve.
     *
     * Slow (< threshold):  pixels = value * baseSensitivity
     * Fast (>= threshold): pixels = value * baseSensitivity * (1 + accelerationFactor * excess)
     *
     * This gives fine control for slow moves and wide coverage for fast swipes.
     */
    private fun applyAccelerationCurve(valueDeg: Float): Float {
        val speed = abs(valueDeg)
        val multiplier = if (speed < accelerationThreshold) {
            baseSensitivity
        } else {
            val excess = speed - accelerationThreshold
            baseSensitivity * (1f + accelerationFactor * (excess / accelerationThreshold))
        }
        return valueDeg * multiplier
    }

    fun reset() {
        isCalibrated = false
        prevPitch = 0f
        prevYaw = 0f
    }
}
