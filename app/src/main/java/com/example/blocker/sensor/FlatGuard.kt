package com.example.blocker.sensor

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Triggers when the phone is too flat to use the orientation sensor reliably.
 *
 * Uses the Z axis (perpendicular to screen):
 *   flatnessDeg = 0°  → phone fully upright  (Z ≈ 0)
 *   flatnessDeg = 90° → phone fully flat      (|Z| ≈ 9.8)
 *
 * Thresholds live in Thresholds.kt. To tune sensitivity, change them there.
 * To add face-up vs face-down messages, check sign of smoothZ here.
 */
class FlatGuard : PhoneGuard {

    private var _active = false
    override val isActive: Boolean get() = _active

    override fun update(smoothX: Float, smoothY: Float, smoothZ: Float) {
        val deg = flatnessDeg(smoothX, smoothY, smoothZ)
        if (!_active && deg > Thresholds.FLAT_ENTER_DEG) _active = true
        if ( _active && deg < Thresholds.FLAT_EXIT_DEG)  _active = false
    }

    override fun alpha(smoothX: Float, smoothY: Float, smoothZ: Float): Float =
        ramp(flatnessDeg(smoothX, smoothY, smoothZ),
             Thresholds.FLAT_EXIT_DEG, Thresholds.FLAT_ENTER_DEG, 0f, 1f)

    override fun message(preSession: Boolean): String =
        if (preSession) "Tilt your phone to start" else "Tilt your phone to continue"

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun flatnessDeg(x: Float, y: Float, z: Float): Float {
        val totalG = sqrt(x * x + y * y + z * z).toDouble()
        if (totalG < 1.0) return 0f
        return Math.toDegrees(asin((abs(z) / totalG).coerceIn(0.0, 1.0))).toFloat()
    }

    private fun ramp(v: Float, low: Float, high: Float, lo: Float, hi: Float): Float = when {
        v <= low  -> lo
        v >= high -> hi
        else      -> lo + (hi - lo) * (v - low) / (high - low)
    }
}
