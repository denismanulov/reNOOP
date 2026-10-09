package com.noop.ui.workouts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: - The interval timer's clock (twin of iOS IntervalTimerRunner)
//
// Silent haptic HIIT: the strap buzzes every transition so the screen never needs a look — a strong triple
// buzz into each work block, a single one into rest, a 3-2-1 tick on the last seconds of every phase and a
// long 5-loop buzz at the end. The rules are the ones the old in-screen timer ran (same cues, same clamps),
// moved out of the screen into one app-wide runner so the timer keeps going — and shows in the mini-player —
// after its page is left, as iOS owns its runner at the app root.

internal class IntervalTimerRunner(private val scope: CoroutineScope) {
    enum class Phase { Work, Rest, Done }

    /** Strap buzz with a loop count; set by the shell (it knows whether a strap is bonded). */
    var buzz: (Int) -> Unit = {}

    var workSeconds by mutableIntStateOf(30)
        private set
    var restSeconds by mutableIntStateOf(15)
        private set
    var rounds by mutableIntStateOf(8)
        private set

    var phase by mutableStateOf(Phase.Work)
        private set
    var currentRound by mutableIntStateOf(1)
        private set
    /** Seconds left in the current phase. */
    var remaining by mutableIntStateOf(30)
        private set
    var running by mutableStateOf(false)
        private set
    /** Total elapsed seconds across the session. */
    var elapsed by mutableIntStateOf(0)
        private set

    private var ticker: Job? = null

    val isFinished: Boolean get() = phase == Phase.Done
    /** Started and not reset: the page offers to resume rather than start over. */
    val inProgress: Boolean get() = !isFinished && (running || elapsed > 0)
    /** Started, not running, not done: the pause the panel names. */
    val isPaused: Boolean get() = !running && elapsed > 0 && !isFinished

    val phaseDuration: Int
        get() = when (phase) {
            Phase.Work -> maxOf(1, workSeconds)
            Phase.Rest -> maxOf(1, restSeconds)
            Phase.Done -> 1
        }

    val phaseProgress: Float
        get() = ((phaseDuration - remaining).toFloat() / phaseDuration).coerceIn(0f, 1f)

    val totalPlanned: Int
        get() = if (rounds <= 0) 0 else workSeconds * rounds + restSeconds * maxOf(0, rounds - 1)

    // MARK: Setup (locked while running)

    fun updateWork(seconds: Int) {
        if (running) return
        workSeconds = seconds.coerceIn(MIN_PHASE, MAX_PHASE)
        resetToStart()
    }

    fun updateRest(seconds: Int) {
        if (running) return
        restSeconds = seconds.coerceIn(MIN_PHASE, MAX_PHASE)
        resetToStart()
    }

    fun updateRounds(value: Int) {
        if (running) return
        rounds = value.coerceIn(1, MAX_ROUNDS)
        if (currentRound > rounds) currentRound = rounds
        resetToStart()
    }

    // MARK: Rules

    fun start() {
        if (isFinished) resetToStart()
        if (!running) toggleRunning()
    }

    fun toggleRunning() {
        if (isFinished) return
        if (running) {
            running = false
            ticker?.cancel()
            ticker = null
        } else {
            // Starting fresh from a clean reset fires the opening work cue.
            val startingFresh = phase == Phase.Work && currentRound == 1 && remaining == maxOf(1, workSeconds) && elapsed == 0
            running = true
            if (startingFresh) buzz(3)
            ticker?.cancel()
            ticker = scope.launch {
                while (isActive && running) {
                    delay(1_000)
                    tick()
                }
            }
        }
    }

    /** One second of the session. Public for the unit tests; the ticker calls it while running. */
    fun tick() {
        if (!running || isFinished) return
        // 3-2-1 tick on the last seconds of the current phase.
        if (remaining in 1..3) buzz(1)
        if (remaining > 1) {
            remaining -= 1
            elapsed += 1
            return
        }
        elapsed += 1
        advancePhase()
    }

    /** Ends the current phase now, as its countdown reaching zero would. */
    fun skipPhase() {
        if (isFinished) return
        advancePhase()
    }

    private fun advancePhase() {
        when (phase) {
            Phase.Work -> if (currentRound >= rounds) {
                finishSession()
            } else {
                phase = Phase.Rest
                remaining = maxOf(1, restSeconds)
                buzz(1)
            }
            Phase.Rest -> {
                currentRound += 1
                phase = Phase.Work
                remaining = maxOf(1, workSeconds)
                buzz(3)
            }
            Phase.Done -> Unit
        }
    }

    private fun finishSession() {
        phase = Phase.Done
        remaining = 0
        running = false
        ticker?.cancel()
        ticker = null
        buzz(5)
    }

    fun stopAndReset() {
        running = false
        ticker?.cancel()
        ticker = null
        resetToStart()
    }

    /** Back to round 1 / start of work, with the current setup. */
    fun resetToStart() {
        phase = Phase.Work
        currentRound = 1
        remaining = maxOf(1, workSeconds)
        elapsed = 0
    }

    companion object {
        /** A phase's shortest and longest length: 0:05 and 59:55. */
        const val MIN_PHASE = 5
        const val MAX_PHASE = 59 * 60 + 55
        const val MAX_ROUNDS = 30

        /** The app's one runner, so its page, its recording screen and the mini-player share one clock. */
        val shared: IntervalTimerRunner by lazy {
            IntervalTimerRunner(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
    }
}
