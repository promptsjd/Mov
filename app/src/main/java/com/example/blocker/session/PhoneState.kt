package com.example.blocker.session

// Ring order (clockwise): UPRIGHT(0) → LANDSCAPE_RIGHT(1) → INVERTED(2) → LANDSCAPE_LEFT(3) → …
enum class PhoneState(val ringIndex: Int) {
    UPRIGHT(0),
    LANDSCAPE_RIGHT(1),
    INVERTED(2),
    LANDSCAPE_LEFT(3);

    fun step(clockwise: Boolean): PhoneState {
        val next = (ringIndex + if (clockwise) 1 else 3) % 4
        return entries.first { it.ringIndex == next }
    }
}
