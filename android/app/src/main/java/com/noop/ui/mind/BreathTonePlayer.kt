package com.noop.ui.mind

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

// MARK: - Audio pacer (opt-in soft phase tones)

/** NoopPrefs key of the opt-in audio pacer (mirrors iOS `@AppStorage("breathe.audioCues")`). */
internal const val KEY_BREATHE_AUDIO_CUES = "breathe.audioCues"

enum class BreathTone(val frequencyHz: Double) {
    Inhale(440.0),   // A4, brighter for "in"
    Exhale(330.0),   // E4, lower for "out"
}

/**
 * The Android twin of [BreathTonePlayer] (iOS) — a tiny on-device tone player for the opt-in audio pacer.
 * It synthesises a short, soft sine "ding" per phase (a higher note on the inhale, a lower one on the
 * exhale) into an [AudioTrack].
 *
 * iOS uses an *ambient* audio session so the silent switch mutes it; Android has no silent switch, so the
 * honest equivalent is to honour the **ringer mode** — when the phone is on silent or vibrate we simply
 * don't play, the same "quiet means quiet" promise. The track is tagged as a sonification assistance cue
 * (not media), so it ducks politely and won't hijack the music stream. Buffers are generated once and
 * reused; [release] frees the track when the screen goes away or the pacer is switched off.
 */
class BreathTonePlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val sampleRate = 44_100
    private val toneSeconds = 0.45
    private val tracks = HashMap<BreathTone, AudioTrack?>()

    /** Play the phase tone, unless the phone is on silent/vibrate (the "honours silent mode" promise). */
    fun play(tone: BreathTone) {
        val am = audioManager ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val track = tracks.getOrPut(tone) { buildTrack(tone) } ?: return
        try {
            // Restart from the top each phase so a fresh tone fires even if the last one is still tailing.
            track.pause()
            track.flush()
            writeTone(track, tone)
            track.play()
        } catch (_: IllegalStateException) {
            // Audio is a nicety, never load-bearing — if the track is in a bad state we just stay silent.
        }
    }

    /** Release the underlying tracks. Idempotent. */
    fun release() {
        tracks.values.forEach { runCatching { it?.release() } }
        tracks.clear()
    }

    private fun buildTrack(tone: BreathTone): AudioTrack? {
        val samples = sampleData(tone)
        val sizeBytes = samples.size * 2  // 16-bit PCM
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(sizeBytes, 1))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            writeTone(track, tone)
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTone(track: AudioTrack, tone: BreathTone) {
        val samples = sampleData(tone)
        track.write(samples, 0, samples.size)
    }

    /**
     * Build a single soft sine tone with a short attack and a longer release envelope, so it fades in and
     * out rather than clicking. Cached per tone so we synthesise it once.
     */
    private val cache = HashMap<BreathTone, ShortArray>()
    private fun sampleData(tone: BreathTone): ShortArray = cache.getOrPut(tone) {
        val total = (toneSeconds * sampleRate).toInt()
        val attack = (0.02 * sampleRate).toInt()
        val release = (0.18 * sampleRate).toInt()
        val peak = 0.28  // kept quiet — a gentle cue, not a beep
        ShortArray(total) { i ->
            val t = i.toDouble() / sampleRate
            val s = sin(2.0 * PI * tone.frequencyHz * t)
            val env = when {
                i < attack -> i.toDouble() / maxOf(attack, 1)
                i > total - release -> (total - i).toDouble() / maxOf(release, 1)
                else -> 1.0
            }
            (s * env * peak * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
