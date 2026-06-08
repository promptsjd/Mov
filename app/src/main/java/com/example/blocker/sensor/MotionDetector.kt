package com.example.blocker.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.example.blocker.session.PhoneState
import kotlin.math.abs
import kotlin.math.asin

class MotionDetector(private val listener: Listener) : SensorEventListener {

    interface Listener {
        /** Fired on every sensor tick with current angles for alpha feedback. */
        fun onPosition(tiltDeg: Float, invertedDeg: Float)
        /** Fired when the phone transitions to a new orientation state.
         *  prevState is null on the very first classification after reset(). */
        fun onStateChanged(newState: PhoneState, prevState: PhoneState?)
    }

    private var smoothX = 0f
    private var smoothY = 0f
    private var smoothZ = 0f

    /** Smoothed X value — used to distinguish left vs right tilt. */
    val lastXSmooth: Float get() = smoothX

    /** Smoothed Y value — used by PhoneGuard implementations. */
    val lastYSmooth: Float get() = smoothY

    /** Smoothed Z value — used by PhoneGuard implementations (flat detection). */
    val lastZSmooth: Float get() = smoothZ

    /** Last classified orientation state. Null until the first stable reading after reset(). */
    val classifiedState: PhoneState? get() = currentState

    /** Null until the first sensor tick after reset(). Seeding it null forces
     *  smooth values to be initialised directly from raw on the first tick,
     *  so the opening-state classification is immediately accurate. */
    private var currentState: PhoneState? = null

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (currentState == null) {
            smoothX = x   // first tick: no warm-up lag
            smoothY = y
            smoothZ = z
        } else {
            smoothX = SMOOTH * x + (1f - SMOOTH) * smoothX
            smoothY = SMOOTH * y + (1f - SMOOTH) * smoothY
            smoothZ = SMOOTH * z + (1f - SMOOTH) * smoothZ
        }

        val tiltDeg     = toDeg(abs(smoothX))
        val invertedDeg = toDeg(maxOf(0f, -smoothY))

        listener.onPosition(tiltDeg, invertedDeg)

        val newState = classify(tiltDeg, invertedDeg)
        if (newState != currentState) {
            val prev     = currentState
            currentState = newState
            listener.onStateChanged(newState, prev)
        }
    }

    /**
     * Single-state classifier with per-zone hysteresis.
     *
     * Only one PhoneState is active at any moment. Exiting a state requires
     * crossing a lower threshold than entering it (10° gap), which prevents
     * oscillation when the phone is held near a boundary.
     *
     * Ring topology enforced: UPRIGHT ↔ LANDSCAPE ↔ INVERTED ↔ LANDSCAPE ↔ UPRIGHT.
     * INVERTED has no direct path to UPRIGHT.
     *
     * Transitions:
     *   UPRIGHT     → LANDSCAPE_LEFT/RIGHT  tiltDeg ≥ 50°
     *   UPRIGHT     → INVERTED              invertedDeg ≥ 50° (physical detection only)
     *   LANDSCAPE_* → UPRIGHT               tiltDeg < 40° AND invertedDeg < 15°
     *   LANDSCAPE_* → INVERTED              invertedDeg ≥ 50°
     *   INVERTED    → LANDSCAPE_LEFT/RIGHT  tiltDeg ≥ 50°
     */
    private fun classify(tiltDeg: Float, invertedDeg: Float): PhoneState {
        return when (val cur = currentState) {

            null,
            PhoneState.UPRIGHT -> when {
                invertedDeg >= Thresholds.INVERTED_ENTER_DEG  -> PhoneState.INVERTED
                tiltDeg     >= Thresholds.LANDSCAPE_ENTER_DEG -> landscapeSide()
                else                                           -> PhoneState.UPRIGHT
            }

            PhoneState.LANDSCAPE_LEFT,
            PhoneState.LANDSCAPE_RIGHT -> when {
                invertedDeg >= Thresholds.INVERTED_ENTER_DEG                           -> PhoneState.INVERTED
                tiltDeg     <  Thresholds.LANDSCAPE_EXIT_DEG
                    && invertedDeg < Thresholds.TRANSITION_GUARD_DEG                   -> PhoneState.UPRIGHT
                else                                                                   -> cur
            }

            // INVERTED exits only to LANDSCAPE — never directly to UPRIGHT.
            // The ring topology is: UPRIGHT ↔ LANDSCAPE ↔ INVERTED ↔ LANDSCAPE ↔ UPRIGHT.
            PhoneState.INVERTED -> when {
                tiltDeg >= Thresholds.LANDSCAPE_ENTER_DEG -> landscapeSide()
                else                                       -> PhoneState.INVERTED
            }
        }
    }

    private fun landscapeSide() =
        if (smoothX >= 0f) PhoneState.LANDSCAPE_LEFT else PhoneState.LANDSCAPE_RIGHT

    fun reset() {
        currentState = null
        smoothX      = 0f
        smoothY      = 0f
        smoothZ      = 0f
    }

    companion object { private const val SMOOTH = 0.15f }

    private fun toDeg(g: Float) =
        Math.toDegrees(asin((g / 9.8).coerceIn(-1.0, 1.0))).toFloat()

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
