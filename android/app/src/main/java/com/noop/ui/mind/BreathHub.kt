package com.noop.ui.mind

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noop.analytics.BreathPacer
import com.noop.analytics.BreathPhase
import com.noop.analytics.BreathProtocolPlayer
import com.noop.analytics.BreathStage
import com.noop.analytics.HrDownPacer
import com.noop.analytics.ResonanceEngine
import com.noop.ui.AppViewModel
import com.noop.ui.BiofeedbackPrefs
import com.noop.ui.HapticPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// MARK: - The session in progress (twin of iOS BreathHub + the biofeedback controller's walks)
//
// Whichever mode runs it: the fixed-pace trainer (a catalog protocol, or the locked resonance pace), the
// one-minute resonance cue, the find-your-pace sweep, or Calm. Everything the session screen shows comes
// from here, so the cover, the running row and the summary never disagree. The strap both measures HRV
// (R-R) and buzzes, so the pace is felt as well as seen: one pulse on the inhale, two on the exhale, through
// the same gated `AppViewModel.buzz` every haptic uses.
//
// The walks run in the page's coroutine scope: leaving Mindfulness cancels them, and a session put away
// with ⌄ keeps running on the page.

/** What a session ends on: how long, the heart rate before and after, and what the session found. */
internal data class BreathSummary(
    val kind: BreathKind,
    val title: String,
    val seconds: Int,
    val hrStart: Int?,
    val hrEnd: Int?,
    /** The sweep's result, when one finished. */
    val sweep: ResonanceEngine.SweepResult? = null,
    /** Calm's outcome, when the pacer (not the user) ended it, or when it could not start. */
    val calm: CalmOutcome? = null,
)

internal class BreathHub(
    private val vm: AppViewModel,
    private val scope: CoroutineScope,
    private val context: Context,
    private val tones: BreathTonePlayer,
) {
    var kind by mutableStateOf<BreathKind?>(null)
        private set
    /** Whether the full-screen session (or its summary) is showing; false while put away with ⌄. */
    var presented by mutableStateOf(false)
    var summary by mutableStateOf<BreathSummary?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var phase by mutableStateOf(BreathPhase.INHALE)
        private set
    /** A stage's own label ("Hold full", a guided protocol's name), shown instead of the phase word. */
    var phaseLabel by mutableStateOf<String?>(null)
        private set
    /** The flower: 0 folded, 1 open; the session screen animates to it over [flowerMs]. */
    var flower by mutableFloatStateOf(0f)
        private set
    var flowerMs by mutableIntStateOf(800)
        private set
    /** Calm's beat under reduced motion, which pulses the flower's light instead of its size. */
    var flowerAlpha by mutableFloatStateOf(1f)
        private set
    var seconds by mutableIntStateOf(0)
        private set
    var targetSeconds by mutableStateOf<Int?>(null)
        private set
    /** RMSSD over the latest R-R window, for the live readout only. */
    var rmssd by mutableStateOf<Double?>(null)
        private set
    /** The pace the sweep is testing now, and how far through its paces it is. */
    var sweepBpm by mutableStateOf<Double?>(null)
        private set
    var sweepProgress by mutableFloatStateOf(0f)
        private set
    /** The tempo Calm's metronome settled on this beat. */
    var calmTargetBpm by mutableStateOf<Double?>(null)
        private set

    private var job: Job? = null
    private var reduceMotion = false
    private var hrStart: Int? = null
    private var lastSweep: ResonanceEngine.SweepResult? = null
    private var calmResult: CalmOutcome? = null
    private val rrWindow = 30
    private var rrBuffer: List<Int> = emptyList()

    /** The session clock as the readouts print it: "2:05 / 10:00" against a target, else "2:05". */
    val clockLine: String
        get() {
            val target = targetSeconds
            return if (kind == BreathKind.Paced && target != null) "${breathClock(seconds)} / ${breathClock(target)}"
            else breathClock(seconds)
        }

    // MARK: Starting

    /**
     * A fixed-pace session: the catalog protocol's stages (or the locked resonance pace, 40:60), paced by
     * flower, buzz and optional tone; a guided protocol shows its name and runs the clock only.
     */
    fun startPaced(title: String, plan: PacedPlan, targetSeconds: Int?, reduceMotion: Boolean, audio: Boolean) {
        stopRunning()
        this.reduceMotion = reduceMotion
        this.targetSeconds = targetSeconds
        begin(BreathKind.Paced, title)
        job = scope.launch {
            launch { runClock(targetSeconds) }
            if (plan.guided || plan.stages.isEmpty()) {
                phase = BreathPhase.TEXT_ONLY
                phaseLabel = title
                if (!reduceMotion) setFlower(0.5f, 800)
                return@launch
            }
            var index = 0
            while (isActive) {
                val stage = plan.stages[index % plan.stages.size]
                arm(stage, audio)
                delay(stage.durationMs.toLong())
                index += 1
            }
        }
    }

    /** One minute at a resonance pace: the stress check-in's "Breathe now". */
    fun startResonanceCue(title: String, bpm: Double, cycles: Int, reduceMotion: Boolean) {
        stopRunning()
        this.reduceMotion = reduceMotion
        begin(BreathKind.Resonance, title)
        job = scope.launch {
            launch { runClock(null) }
            runCues(bpm, cycles) { }
            delay(cueTailMs(bpm))
            finish()
        }
    }

    /** The find-your-pace sweep: 3 paces (~7 min) or 6 (~13 min); a confident result is locked and kept. */
    fun startSweep(title: String, quick: Boolean, reduceMotion: Boolean) {
        stopRunning()
        this.reduceMotion = reduceMotion
        begin(BreathKind.Sweep, title)
        lastSweep = null
        sweepProgress = 0f
        job = scope.launch {
            launch { runClock(null) }
            val paces = if (quick) ResonanceEngine.QUICK_SWEEP_PACES else ResonanceEngine.FULL_SWEEP_PACES
            val samples = ArrayList<ResonanceEngine.PaceSample>()
            for ((index, bpm) in paces.withIndex()) {
                sweepBpm = bpm
                val startTs = (System.currentTimeMillis() / 1000).toInt()
                val bucket = ArrayList<ResonanceEngine.RrBeat>()
                // Collect this pace's R-R while it is paced (the latest live packet at each cue).
                runCues(bpm, maxOf(1, (SECONDS_PER_PACE * bpm / 60.0).roundToInt())) {
                    val now = (System.currentTimeMillis() / 1000).toInt()
                    for (ms in vm.live.value.rr) if (ms in 301..1999) bucket.add(ResonanceEngine.RrBeat(now, ms))
                }
                delay(4000) // let the last exhale finish before closing the window
                val endTs = (System.currentTimeMillis() / 1000).toInt()
                samples.add(ResonanceEngine.PaceSample(bpm, bucket, startTs, endTs))
                sweepProgress = (index + 1).toFloat() / paces.size
            }
            val swept = ResonanceEngine.sweep(samples)
            lastSweep = swept
            if (swept.didLock) BiofeedbackPrefs.saveLockedPace(context, swept.lockedBpm, System.currentTimeMillis())
            finish()
        }
    }

    /**
     * The below-HR metronome: one light pulse per target beat, recomputed each step from the live heart
     * rate so the cue trails the heart down. Without a bond and a resting-band HR it ends at once and the
     * summary says why. Each beat also shows on the flower, so the rhythm does not live on the wrist alone
     * (CR-8).
     */
    fun startCalm(title: String, reduceMotion: Boolean) {
        stopRunning()
        this.reduceMotion = reduceMotion
        begin(BreathKind.Calm, title)
        setFlower(0.35f, 0)
        calmTargetBpm = null
        val h0 = vm.bpm.value
        if (!canBuzz() || h0 == null || h0 !in 55..120) {
            calmResult = CalmOutcome.CouldNotStart
            finish(strapRan = false)
            return
        }
        job = scope.launch {
            launch { runClock(null) }
            val config = HrDownPacer.Config.DEFAULT
            while (isActive) {
                val liveHr = vm.bpm.value
                val step = HrDownPacer.next((liveHr ?: 0).toDouble(), seconds.toDouble(), config)
                if (step.stop) {
                    calmResult = calmOutcome(step.stopReason, hrStart, liveHr, seconds)
                    finish()
                    return@launch
                }
                calmTargetBpm = step.targetBpm
                if (canBuzz()) vm.buzz(loops = 1, gate = HapticPrefs.BREATHING)
                launch { pulseCalm() }
                delay((step.intervalMs ?: 1000).toLong())
            }
        }
    }

    private fun begin(kind: BreathKind, title: String) {
        summary = null
        calmResult = null
        this.kind = kind
        this.title = title
        phase = BreathPhase.INHALE
        phaseLabel = null
        seconds = 0
        flowerAlpha = 1f
        sweepBpm = null
        hrStart = vm.bpm.value
        presented = true
    }

    // MARK: Ending

    /** ✕: end whatever runs and show its summary. */
    fun end() {
        if (kind != null) finish()
    }

    /** Leave without a summary (the page went away): stop everything, quietly. */
    fun stopAll() {
        stopRunning()
        presented = false
        summary = null
    }

    fun closeSummary() {
        summary = null
        presented = false
    }

    /** Ends a running session without a summary (a new one is starting, or the page is leaving). */
    private fun stopRunning() {
        val was = kind
        job?.cancel()
        job = null
        kind = null
        if (was != null) halt()
    }

    /** Ends the session on its summary. [strapRan] false when nothing was ever sent to the strap. */
    private fun finish(strapRan: Boolean = true) {
        val was = kind ?: return
        val result = BreathSummary(
            kind = was,
            title = title,
            seconds = seconds,
            hrStart = hrStart,
            hrEnd = vm.bpm.value,
            sweep = if (was == BreathKind.Sweep) lastSweep else null,
            calm = if (was == BreathKind.Calm) calmResult else null,
        )
        val running = job
        job = null
        kind = null
        halt(strapRan)
        summary = result
        presented = true
        // Last: this may be called from inside the walk it cancels.
        running?.cancel()
    }

    /** Stops the strap pattern and folds the flower (#769: halt a pattern the strap may be mid-way through). */
    private fun halt(strapRan: Boolean = true) {
        phaseLabel = null
        sweepBpm = null
        if (strapRan) vm.stopHaptics()
        setFlower(0f, if (reduceMotion) 0 else 800)
        flowerAlpha = 1f
    }

    // MARK: Pacing

    private suspend fun runClock(target: Int?) {
        while (true) {
            delay(1000)
            seconds += 1
            if (target != null && seconds >= target) {
                finish()
                return
            }
        }
    }

    /** One stage of the fixed-pace trainer: the word, the flower, the buzz and the tone. */
    private fun arm(stage: BreathStage, audio: Boolean) {
        phase = stage.type
        phaseLabel = stage.label
        when (stage.type) {
            BreathPhase.INHALE -> animate(1f, stage.durationMs)
            BreathPhase.EXHALE -> animate(0f, stage.durationMs)
            BreathPhase.HOLD, BreathPhase.TEXT_ONLY -> if (reduceMotion) setFlower(0.5f, 0)
        }
        val loops = BreathProtocolPlayer.loops(stage.type)
        if (loops > 0) vm.buzz(loops = loops, gate = HapticPrefs.BREATHING)
        if (audio) {
            when (stage.type) {
                BreathPhase.INHALE -> tones.play(BreathTone.Inhale)
                BreathPhase.EXHALE -> tones.play(BreathTone.Exhale)
                else -> Unit
            }
        }
    }

    /** Walks [cycles] breaths at [bpm] (40:60): each cue sets the phase, moves the flower and buzzes. */
    private suspend fun runCues(bpm: Double, cycles: Int, onCue: () -> Unit) {
        val cues = BreathPacer.schedule(bpm = bpm, cycles = cycles)
        val cycleMs = 60_000.0 / maxOf(bpm, 1.0)
        val inhaleMs = (cycleMs * BreathPacer.DEFAULT_INHALE_FRACTION).roundToInt()
        var elapsedMs = 0
        for (cue in cues) {
            delay((cue.offsetMs - elapsedMs).toLong().coerceAtLeast(0))
            elapsedMs = cue.offsetMs
            phase = cue.phase
            if (cue.phase == BreathPhase.INHALE) animate(1f, inhaleMs) else animate(0f, (cycleMs - inhaleMs).roundToInt())
            // The strap pattern needs the encrypted channel; the flower carries the pace either way.
            if (vm.live.value.encryptedBond) vm.buzz(loops = cue.loops, gate = HapticPrefs.BREATHING)
            onCue()
        }
    }

    /** How long the last exhale of a cue walk lasts, so the session ends after it and not mid-breath. */
    private fun cueTailMs(bpm: Double): Long {
        val cycleMs = 60_000.0 / maxOf(bpm, 1.0)
        return (cycleMs * (1 - BreathPacer.DEFAULT_INHALE_FRACTION)).toLong()
    }

    /** Calm: each metronome beat shows on the flower too. */
    private suspend fun pulseCalm() {
        if (kind != BreathKind.Calm) return
        if (reduceMotion) flowerAlpha = 0.6f else setFlower(0.45f, 150)
        delay(150)
        if (reduceMotion) flowerAlpha = 1f else if (kind == BreathKind.Calm) setFlower(0.35f, 250)
    }

    private fun animate(to: Float, overMs: Int) {
        if (reduceMotion) setFlower(0.5f, 0) else setFlower(to, overMs)
    }

    private fun setFlower(value: Float, overMs: Int) {
        flowerMs = overMs
        flower = value
    }

    /** A strap that can buzz: bonded on the encrypted channel. */
    fun canBuzz(): Boolean = vm.live.value.let { it.bonded && it.encryptedBond }

    // MARK: HRV

    /** Feeds a live R-R packet into the rolling window behind the HRV readout. */
    fun ingest(rr: List<Int>) {
        if (rr.isEmpty()) return
        rrBuffer = (rrBuffer + rr).takeLast(rrWindow)
        rmssd = breathRmssd(rrBuffer)
    }

    private companion object {
        /** How long each swept pace is held. */
        const val SECONDS_PER_PACE = 120
    }
}
