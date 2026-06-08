package com.example.blocker.sensor

/**
 * ═══════════════════════════════════════════════════════════════════
 *  MOTION ZONES — edit degrees here, nothing else needs to change
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Reference:   0° = phone perfectly upright
 *               90° = phone fully on its side or fully inverted
 *
 *  Zone map (identical for tilt and inverted axes):
 *
 *    0° ── 30° ── 40° ─────── 50° ── 60° ── 90°
 *    │FULL │FADE │  HYSTERESIS │FADE │FULL │
 *    │ α=1 │1→0  │  EXIT←  →ENTER  │0→1  │ α=1 │
 *
 *  All zones enter at 50°, exit at 40°, reach full alpha at 60°.
 *  This gives 10° hysteresis and 30° full-alpha windows everywhere.
 */
object Thresholds {

    // ── Upright zone ──────────────────────────────────────────────────────────
    /** Below this: upright text is at full alpha. */
    const val UPRIGHT_FULL_DEG     = 30f

    /** Above this: upright text is zero (dead zone begins). */
    const val UPRIGHT_FADE_END_DEG = 40f

    // ── Transition guard ─────────────────────────────────────────────────────
    /**
     * Blocks the LANDSCAPE→UPRIGHT exit when Y is already building toward inverted.
     * Physics: on any LANDSCAPE→INVERTED arc (X↓, Y↓), when tiltDeg first crosses
     * LANDSCAPE_EXIT_DEG the invertedDeg is always exactly 20.9° — no matter the
     * flip speed or SMOOTH factor. This constant must stay below 20.9° so the guard
     * catches every case. 15° gives a safe 6° margin.
     */
    const val TRANSITION_GUARD_DEG = 15f

    // ── Hysteresis gap: 40°–50° — nothing fires here ─────────────────────────

    // ── Landscape zone (tilt / X axis) ───────────────────────────────────────
    /** Crossing this threshold upward enters landscape state. */
    const val LANDSCAPE_ENTER_DEG  = 50f

    /** Must drop below this to exit landscape state (10° hysteresis). */
    const val LANDSCAPE_EXIT_DEG   = 40f

    /** Above this: landscape text is at full alpha. */
    const val LANDSCAPE_FULL_DEG   = 60f

    // ── Inverted zone (Y axis) ────────────────────────────────────────────────
    /** Crossing this threshold upward enters inverted state. */
    const val INVERTED_ENTER_DEG      = 50f

    /** Must drop below this to exit inverted state (10° hysteresis). */
    const val INVERTED_EXIT_DEG       = 40f

    /** Inverted text starts appearing here (= INVERTED_ENTER_DEG, alpha=0 at zone entry). */
    const val INVERTED_FADE_START_DEG = 50f

    /** Above this: inverted text is at full alpha. Matches LANDSCAPE_FULL_DEG. */
    const val INVERTED_FULL_DEG       = 60f

    // ── Flat guard (Z axis) ───────────────────────────────────────────────────
    //
    //  flatnessDeg: 0° = upright (Z≈0), 90° = fully flat (|Z|≈9.8)
    //
    //  Sensor reliability: Z becomes noisy below ~20° from flat (flatnessDeg > 70°).
    //  Thresholds are kept within the reliable zone (flatnessDeg < 70°).
    //
    //  0° ────────── 55° ────── 70° ──── 90°
    //                │ EXIT     │ ENTER  │
    //                │ alpha→0  │ alpha=1│ (fully flat, unreliable)
    //
    //  Hysteresis gap: 15° — phone must tilt meaningfully to clear the guard.

    /** Guard activates when flatnessDeg exceeds this (≈ 20° from flat). */
    const val FLAT_ENTER_DEG = 70f

    /** Guard clears when flatnessDeg drops below this (≈ 35° from flat). */
    const val FLAT_EXIT_DEG  = 55f
}
