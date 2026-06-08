package com.example.blocker.session

enum class Turn {
    LEFT, RIGHT, UP, DOWN;

    val label: String get() = when (this) {
        LEFT  -> "← Turn left"
        RIGHT -> "→ Turn right"
        UP    -> "↑ Turn up"
        DOWN  -> "↓ Turn down"
    }
}
