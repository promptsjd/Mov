package com.example.blocker.session

import kotlin.random.Random

data class SessionStep(val from: PhoneState, val turn: Turn, val to: PhoneState)

class SessionEngine(val homeState: PhoneState, moveCount: Int) {

    // moveCount is always even (Complexity values: 6, 8, 12)
    val steps: List<SessionStep> = buildPath(homeState, moveCount)

    private var index = 0
    val current: SessionStep? get() = steps.getOrNull(index)
    val isComplete: Boolean   get() = index >= steps.size

    fun advance() { if (!isComplete) index++ }

    companion object {

        // Maps a clockwise ring step to the Turn label from the user's perspective.
        // Ring: UPRIGHT(0) → LANDSCAPE_RIGHT(1) → INVERTED(2) → LANDSCAPE_LEFT(3) → …
        private fun deriveTurn(from: PhoneState, to: PhoneState): Turn {
            val cw = ((to.ringIndex - from.ringIndex + 4) % 4) == 1
            return when (from) {
                PhoneState.UPRIGHT         -> if (cw) Turn.RIGHT else Turn.LEFT
                PhoneState.INVERTED        -> if (cw) Turn.LEFT  else Turn.RIGHT  // user perspective flips
                PhoneState.LANDSCAPE_RIGHT -> if (cw) Turn.DOWN  else Turn.UP
                PhoneState.LANDSCAPE_LEFT  -> if (cw) Turn.UP    else Turn.DOWN
            }
        }

        private fun buildPath(home: PhoneState, count: Int): List<SessionStep> {
            // N-2 free steps (always even), then exactly 2 closing steps.
            // After any even number of steps on Z_4, distance from home is always 0 or 2.
            // dist=2 → 2 same-direction steps close the ring back to home.
            // dist=0 → retry (would produce a boring go-then-reverse close).
            while (true) {
                val steps = mutableListOf<SessionStep>()
                var state = home

                repeat(count - 2) {
                    val next = state.step(Random.nextBoolean())
                    steps.add(SessionStep(state, deriveTurn(state, next), next))
                    state = next
                }

                val dist = (home.ringIndex - state.ringIndex + 4) % 4
                if (dist != 2) continue  // dist=0: retry to avoid boring close

                // Two steps in the same direction cross the intermediate state and land at home.
                // Both CW and CCW work when dist=2, so pick randomly for variety.
                val closeCW = Random.nextBoolean()
                repeat(2) {
                    val next = state.step(closeCW)
                    steps.add(SessionStep(state, deriveTurn(state, next), next))
                    state = next
                }

                if (state == home) return steps
            }
        }
    }
}
