package com.example.blocker.session

enum class Complexity(val moveCount: Int, val label: String, val description: String) {
    LIGHT (moveCount = 6,  label = "Light Friction",  description = "6 moves — quick challenge"),
    MEDIUM(moveCount = 8,  label = "Medium Friction", description = "8 moves — moderate challenge"),
    STRONG(moveCount = 12, label = "Strong Friction",  description = "12 moves — serious commitment"),
}
