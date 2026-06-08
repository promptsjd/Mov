package com.example.blocker.sensor

/**
 * A PhoneGuard intercepts session flow when a physical condition makes
 * the challenge unsafe or impossible to continue (e.g. phone is flat).
 *
 * Each guard owns its detection logic and its messages.
 * The service holds a list of guards and queries them every sensor tick.
 *
 * To add a new guard (e.g. ShakeGuard):
 *   1. Implement this interface.
 *   2. Add it to the list in BlockerAccessibilityService.
 *   Nothing else changes.
 */
interface PhoneGuard {

    /** True while the guard condition is fully active (session must be paused). */
    val isActive: Boolean

    /**
     * Feed the latest smoothed sensor values.
     * Called every sensor tick — updates internal hysteresis state.
     */
    fun update(smoothX: Float, smoothY: Float, smoothZ: Float)

    /**
     * Returns 0f–1f opacity for the guard message at current sensor values.
     * May be non-zero before [isActive] is true (fade-in warning zone).
     */
    fun alpha(smoothX: Float, smoothY: Float, smoothZ: Float): Float

    /** Text to display. Phrase differs for pre-session vs mid-session context. */
    fun message(preSession: Boolean): String
}
