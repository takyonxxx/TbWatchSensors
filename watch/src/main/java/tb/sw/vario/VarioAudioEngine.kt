package tb.sw.vario

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Vario audio engine — direct Kotlin port of Türkay's iOS AudioEngine
 * with the same harmonic-rich "piezo buzzer" timbre and pitch/cadence
 * mapping. Plays a continuous synthesised tone whose pitch glides up
 * with climb rate and whose amplitude pulses at a cadence that also
 * scales with climb. Sink alarm: continuous low tone with FM vibrato,
 * same timbre.
 *
 * Configurable on the fly via [updateVario] (from the variometer).
 *
 * Audio characteristics (from analysis of a real reference recording):
 *   • Carrier is sin(φ) + 0.55·sin(3φ) + 0.25·sin(5φ) + 0.05·sin(2φ)
 *     — fundamental + odd harmonics dominate, that's the buzzer feel.
 *   • Climb pitch glides 700 → 1600 Hz over 0..6 m/s climb (configurable).
 *   • Cadence ramps 2.5 → 8 Hz with the same climb range.
 *   • Sink: 200 Hz drone, ±40 Hz FM at ~2.2 Hz vibrato.
 *   • Amplitude shape: 65 % "on" with cosine attack/release ramps,
 *     35 % silence — gives audible pulsation without click artefacts.
 *
 * Render thread pushes PCM frames into AudioTrack; smoothing happens
 * per-sample so parameter changes are buttery.
 */
class VarioAudioEngine {

    private companion object {
        const val TAG = "VarioAudio"
        const val SAMPLE_RATE = 44_100
        const val FRAMES_PER_BUFFER = 1024
    }

    // -- Configuration (matches Swift defaults) ---------------------------

    private var basePitchHz: Double = 700.0
    private var maxPitchHz: Double = 1600.0
    private val maxClimbRef: Double = 6.0
    private val minCadenceHz: Double = 2.5
    private val maxCadenceHz: Double = 8.0
    private val sinkPitchHz: Double = 200.0
    private val sinkVibratoHz: Double = 2.2
    private val sinkVibratoDepth: Double = 40.0
    private val amp1: Double = 1.00
    private val amp3: Double = 0.55
    private val amp5: Double = 0.25
    private val amp2: Double = 0.05
    private val smoothTauSec: Double = 0.10

    private var climbThreshold: Double = 0.1
    private var sinkThreshold: Double = -2.0
    private var volume: Double = 0.8
    private var enabled: Boolean = false

    // -- Atomic-ish state shared with render thread ------------------------
    //
    // `@Volatile` is enough here: each variable is written by the UI thread
    // and read by the audio thread, no compound updates. We accept tearing
    // on Double writes (it's audio, and a one-sample blip is inaudible).

    @Volatile private var targetVarioMps: Double = 0.0

    // Smoothed values (audio-thread-only)
    private var smoothVario: Double = 0.0
    private var smoothPitch: Double = 1100.0
    private var smoothCadence: Double = 6.0
    private var smoothVolume: Double = 0.8

    // Phase accumulators (audio-thread-only)
    private var carrierPhase: Double = 0.0
    private var cadencePhase: Double = 0.0   // shared: sink FM + climb AM

    private var audioTrack: AudioTrack? = null
    @Volatile private var renderRunning: Boolean = false
    private var renderThread: Thread? = null

    // -- Public API --------------------------------------------------------

    /** Enable/disable playback. While disabled the audio track is paused. */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        if (on) start() else stop()
    }

    /** Push the latest vertical speed (m/s) to the engine. */
    fun updateVario(verticalSpeedMps: Float) {
        targetVarioMps = verticalSpeedMps.toDouble()
    }

    /**
     * Tweak runtime audio parameters. Pitch limits map to climb rate; the
     * thresholds decide when climb beep / sink alarm engage. Volume 0..1.
     */
    fun configure(
        basePitchHz: Double = this.basePitchHz,
        maxPitchHz: Double = this.maxPitchHz,
        climbThreshold: Double = this.climbThreshold,
        sinkThreshold: Double = this.sinkThreshold,
        volume: Double = this.volume,
    ) {
        this.basePitchHz = basePitchHz
        this.maxPitchHz = maxPitchHz
        this.climbThreshold = climbThreshold
        this.sinkThreshold = sinkThreshold
        this.volume = volume.coerceIn(0.0, 1.0)
    }

    fun release() {
        stop()
    }

    // -- Audio track lifecycle --------------------------------------------

    private fun start() {
        if (audioTrack != null) return

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufSize = maxOf(minBuf, FRAMES_PER_BUFFER * 4 * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also {
                it.play()
            }

        renderRunning = true
        renderThread = thread(name = "VarioAudioRender", priority = Thread.MAX_PRIORITY) {
            renderLoop()
        }
        Log.i(TAG, "Vario audio started (buf=$bufSize bytes)")
    }

    private fun stop() {
        renderRunning = false
        runCatching { renderThread?.join(500) }
        renderThread = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        Log.i(TAG, "Vario audio stopped")
    }

    // -- Render loop -------------------------------------------------------

    private fun renderLoop() {
        val buf = FloatArray(FRAMES_PER_BUFFER)
        val track = audioTrack ?: return

        val dt = 1.0 / SAMPLE_RATE
        val twoPi = 2.0 * PI

        while (renderRunning) {
            // Snapshot config once per buffer. Doubles can tear; acceptable.
            val target = targetVarioMps
            val climbThr = climbThreshold
            val sinkThr = sinkThreshold
            val basePitch = basePitchHz
            val maxPitch = maxPitchHz
            val targetVolume = volume

            val alpha = 1.0 - exp(-dt / smoothTauSec)

            for (i in 0 until FRAMES_PER_BUFFER) {
                smoothVario += (target - smoothVario) * alpha
                smoothVolume += (targetVolume - smoothVolume) * alpha

                var sample = 0.0

                when {
                    smoothVario < sinkThr -> {
                        // Sink drone: 200 Hz with FM vibrato.
                        cadencePhase += dt * sinkVibratoHz * twoPi
                        if (cadencePhase > twoPi) cadencePhase -= twoPi
                        val instFreq = sinkPitchHz + sinkVibratoDepth * sin(cadencePhase)
                        carrierPhase += dt * instFreq * twoPi
                        if (carrierPhase > twoPi * 5) carrierPhase -= twoPi * 5
                        sample = harmonicWave(carrierPhase) * 0.6
                    }

                    smoothVario > climbThr -> {
                        val norm = ((smoothVario - climbThr) / maxClimbRef).coerceIn(0.0, 1.0)
                        val pitchTarget = basePitch + norm * (maxPitch - basePitch)
                        val cadenceTarget = minCadenceHz + norm * (maxCadenceHz - minCadenceHz)
                        smoothPitch += (pitchTarget - smoothPitch) * alpha
                        smoothCadence += (cadenceTarget - smoothCadence) * alpha

                        cadencePhase += dt * smoothCadence * twoPi
                        if (cadencePhase > twoPi) cadencePhase -= twoPi
                        val cyc = cadencePhase / twoPi   // 0..1

                        // Amplitude envelope: 65 % "on" with cosine ramps.
                        val onFrac = 0.65
                        val attack = 0.20
                        val release = 0.35
                        var amp = 0.0
                        if (cyc < onFrac) {
                            val t = cyc / onFrac
                            amp = when {
                                t < attack -> 0.5 * (1.0 - cos(PI * t / attack))
                                t > 1.0 - release -> {
                                    val r = (t - (1.0 - release)) / release
                                    0.5 * (1.0 + cos(PI * r))
                                }
                                else -> 1.0
                            }
                        }

                        carrierPhase += dt * smoothPitch * twoPi
                        if (carrierPhase > twoPi * 5) carrierPhase -= twoPi * 5
                        sample = harmonicWave(carrierPhase) * amp
                    }
                    // else: dead band between thresholds → silence
                }

                sample *= smoothVolume * 0.85
                if (sample > 1.0) sample = 1.0
                if (sample < -1.0) sample = -1.0
                buf[i] = sample.toFloat()
            }

            track.write(buf, 0, FRAMES_PER_BUFFER, AudioTrack.WRITE_BLOCKING)
        }
    }

    /** Harmonic-rich tone matching a real vario buzzer (1 + 3 + 5 + 2). */
    private fun harmonicWave(phase: Double): Double {
        val s1 = sin(phase)
        val s2 = sin(2 * phase)
        val s3 = sin(3 * phase)
        val s5 = sin(5 * phase)
        val s = amp1 * s1 + amp2 * s2 + amp3 * s3 + amp5 * s5
        return s / (amp1 + amp2 + amp3 + amp5)
    }
}
