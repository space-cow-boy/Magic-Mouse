package com.magicmouse.android.touch

import android.view.MotionEvent
import com.magicmouse.android.network.Protocol

/**
 * Gesture types emitted by the GestureDetector.
 */
sealed class GestureEvent {
    object LeftClick        : GestureEvent()
    object DoubleClick      : GestureEvent()
    object RightClick       : GestureEvent()
    data class DragStart(val x: Float, val y: Float) : GestureEvent()
    data class DragMove(val dx: Float, val dy: Float) : GestureEvent()
    object DragEnd          : GestureEvent()
    data class TrackpadMove(val dx: Float, val dy: Float) : GestureEvent()
    data class ScrollV(val amount: Float) : GestureEvent()
    data class ScrollH(val amount: Float) : GestureEvent()
}

/**
 * State machine gesture recognizer for Magic Mouse-like gestures.
 *
 * Gesture map:
 *   1-finger tap           → LeftClick
 *   1-finger double-tap    → DoubleClick
 *   1-finger long-press    → DragStart/DragMove/DragEnd
 *   2-finger tap           → RightClick
 *   2-finger vertical drag → ScrollV
 *   2-finger horiz drag    → ScrollH
 *
 * Anti-accidental-click design:
 *   - Tap requires finger-up within TAP_MAX_MS
 *   - Movement > MOVE_THRESHOLD_PX cancels a tap, starts drag instead
 *   - Double-tap requires second tap within DOUBLE_TAP_MAX_MS
 *   - Drag requires DRAG_MIN_MS hold OR movement > DRAG_THRESHOLD_PX
 */
class GestureDetector(private val onGesture: (GestureEvent) -> Unit) {

    companion object {
        private const val TAP_MAX_MS          = 200L    // Max duration for a tap
        private const val DOUBLE_TAP_MAX_MS   = 300L    // Max gap between two taps
        private const val DRAG_MIN_MS         = 250L    // Min hold time to start drag
        private const val MOVE_THRESHOLD_PX   = 12f     // Cancel tap if moved more than this
        private const val SCROLL_THRESHOLD_PX = 15f     // Minimum move to start scroll
        private const val SCROLL_SENSITIVITY  = 0.2f    // Scroll speed multiplier
        private const val TOUCH_DRAG_SENSITIVITY = 2.5f // Touch pad movement multiplier
    }

    private enum class State {
        IDLE,
        POSSIBLE_TAP,         // 1 finger down, waiting to see if it's tap/drag
        WAIT_DOUBLE_TAP,      // 1st tap done, waiting for 2nd
        TRACKPAD_MOVING,      // 1-finger move without click
        DRAGGING,             // 1-finger drag in progress
        POSSIBLE_RIGHT_CLICK, // 2 fingers down, waiting
        SCROLLING_V,          // 2-finger vertical scroll
        SCROLLING_H           // 2-finger horizontal scroll
    }

    private var state = State.IDLE
    private var downTimeMs = 0L
    private var firstTapTimeMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var prevTwoFingerY = 0f
    private var prevTwoFingerX = 0f

    /** Called by the touch overlay view's onTouchEvent */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked
        val nowMs = System.currentTimeMillis()

        when {
            // ── One-finger gestures ──────────────────────────────────────────
            pointerCount == 1 -> handleOneFinger(action, event, nowMs)

            // ── Two-finger gestures ──────────────────────────────────────────
            pointerCount == 2 -> handleTwoFinger(action, event, nowMs)

            // ── 3+ fingers: ignore for now ────────────────────────────────────
            else -> {}
        }
        return true
    }

    private fun handleOneFinger(action: Int, event: MotionEvent, nowMs: Long) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any in-progress two-finger state
                if (state == State.DRAGGING) {
                    onGesture(GestureEvent.DragEnd)
                }
                state = State.POSSIBLE_TAP
                downTimeMs = nowMs
                startX = event.x
                startY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                when (state) {
                    State.POSSIBLE_TAP -> {
                        if (dist > MOVE_THRESHOLD_PX) {
                            // Moved too much to be a tap — start regular trackpad move (no click)
                            state = State.TRACKPAD_MOVING
                            onGesture(GestureEvent.TrackpadMove(dx, dy))
                            startX = event.x
                            startY = event.y
                        } else if (nowMs - downTimeMs > DRAG_MIN_MS) {
                            // Held long enough — start drag (long press with click down)
                            state = State.DRAGGING
                            onGesture(GestureEvent.DragStart(startX, startY))
                        }
                    }
                    State.TRACKPAD_MOVING -> {
                        onGesture(GestureEvent.TrackpadMove(event.x - startX, event.y - startY))
                        startX = event.x
                        startY = event.y
                    }
                    State.DRAGGING -> {
                        onGesture(GestureEvent.DragMove(event.x - startX, event.y - startY))
                        startX = event.x
                        startY = event.y
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val holdMs = nowMs - downTimeMs
                when (state) {
                    State.POSSIBLE_TAP -> {
                        if (holdMs <= TAP_MAX_MS) {
                            // Could be single or double tap
                            if (state != State.WAIT_DOUBLE_TAP &&
                                nowMs - firstTapTimeMs < DOUBLE_TAP_MAX_MS &&
                                firstTapTimeMs > 0L) {
                                onGesture(GestureEvent.DoubleClick)
                                firstTapTimeMs = 0L
                                state = State.IDLE
                            } else {
                                // First tap — wait to see if double
                                firstTapTimeMs = nowMs
                                state = State.WAIT_DOUBLE_TAP
                                // Schedule single click delivery after DOUBLE_TAP_MAX_MS
                                // (handled in ACTION_DOWN check above)
                            }
                        } else {
                            state = State.IDLE
                        }
                    }
                    State.WAIT_DOUBLE_TAP -> {
                        // Second tap arrived quickly
                        onGesture(GestureEvent.DoubleClick)
                        firstTapTimeMs = 0L
                        state = State.IDLE
                    }
                    State.DRAGGING -> {
                        onGesture(GestureEvent.DragEnd)
                        state = State.IDLE
                    }
                    State.TRACKPAD_MOVING -> {
                        state = State.IDLE
                    }
                    else -> state = State.IDLE
                }
            }
        }

        // Flush pending single-click if double-tap window expired
        if (state == State.WAIT_DOUBLE_TAP && firstTapTimeMs > 0L &&
            nowMs - firstTapTimeMs >= DOUBLE_TAP_MAX_MS) {
            onGesture(GestureEvent.LeftClick)
            firstTapTimeMs = 0L
            state = State.IDLE
        }
    }

    private fun handleTwoFinger(action: Int, event: MotionEvent, nowMs: Long) {
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Cancel any one-finger state cleanly
                if (state == State.DRAGGING) onGesture(GestureEvent.DragEnd)
                state = State.POSSIBLE_RIGHT_CLICK
                downTimeMs = nowMs
                startX = midX
                startY = midY
                prevTwoFingerX = midX
                prevTwoFingerY = midY
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = midX - startX
                val totalDy = midY - startY

                when (state) {
                    State.POSSIBLE_RIGHT_CLICK -> {
                        when {
                            kotlin.math.abs(totalDy) > SCROLL_THRESHOLD_PX -> {
                                state = State.SCROLLING_V
                            }
                            kotlin.math.abs(totalDx) > SCROLL_THRESHOLD_PX -> {
                                state = State.SCROLLING_H
                            }
                        }
                    }
                    State.SCROLLING_V -> {
                        val delta = (midY - prevTwoFingerY) * SCROLL_SENSITIVITY
                        if (kotlin.math.abs(delta) > 0.001f) {
                            onGesture(GestureEvent.ScrollV(delta))
                        }
                    }
                    State.SCROLLING_H -> {
                        val delta = (midX - prevTwoFingerX) * SCROLL_SENSITIVITY
                        if (kotlin.math.abs(delta) > 0.001f) {
                            onGesture(GestureEvent.ScrollH(delta))
                        }
                    }
                    else -> {}
                }
                prevTwoFingerX = midX
                prevTwoFingerY = midY
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (state == State.POSSIBLE_RIGHT_CLICK) {
                    val holdMs = nowMs - downTimeMs
                    if (holdMs <= TAP_MAX_MS) {
                        onGesture(GestureEvent.RightClick)
                    }
                }
                state = State.IDLE
            }
        }
    }

    /** Convert a GestureEvent to the appropriate Protocol packet bytes. */
    fun gestureToPacket(event: GestureEvent): ByteArray? = when (event) {
        is GestureEvent.LeftClick  ->
            Protocol.buildClickPacket(Protocol.ClickType.SINGLE_CLICK, Protocol.Button.LEFT)
        is GestureEvent.DoubleClick ->
            Protocol.buildClickPacket(Protocol.ClickType.DOUBLE_CLICK, Protocol.Button.LEFT)
        is GestureEvent.RightClick ->
            Protocol.buildClickPacket(Protocol.ClickType.SINGLE_CLICK, Protocol.Button.RIGHT)
        is GestureEvent.DragStart  ->
            Protocol.buildClickPacket(Protocol.ClickType.PRESS,   Protocol.Button.LEFT)
        is GestureEvent.DragEnd    ->
            Protocol.buildClickPacket(Protocol.ClickType.RELEASE, Protocol.Button.LEFT)
        is GestureEvent.DragMove   ->
            Protocol.buildMotionPacket(event.dx * TOUCH_DRAG_SENSITIVITY, event.dy * TOUCH_DRAG_SENSITIVITY)
        is GestureEvent.TrackpadMove ->
            Protocol.buildMotionPacket(event.dx * TOUCH_DRAG_SENSITIVITY, event.dy * TOUCH_DRAG_SENSITIVITY)
        is GestureEvent.ScrollV    ->
            Protocol.buildScrollPacket(event.amount)
        is GestureEvent.ScrollH    ->
            Protocol.buildScrollPacket(0f, event.amount)
    }
}
