package tb.sw.vario

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Barometric variometer.
 *
 * Converts pressure readings (hPa) into vertical speed (m/s) for paragliding.
 * Pressure samples come asynchronously from the barometer flow at ~5–25 Hz
 * depending on the device.
 *
 * Pipeline:
 *   pressure (hPa) → altitude (m) via barometric formula
 *                  → noise filter (low-pass IIR)
 *                  → vertical speed = d(altitude)/dt
 *                  → output filter (low-pass on the derivative)
 *
 * Two-stage filtering matters for paraglider varios. Filtering altitude
 * heavily kills lift detection latency; filtering the derivative kills
 * jitter without delaying the actual climb signal much. We use a moderate
 * altitude filter (τ ≈ 200 ms) and a tighter speed filter (τ ≈ 400 ms),
 * which is standard for hobby varios.
 */
class Variometer {

    /** Standard atmospheric pressure at sea level, hPa. */
    private val p0: Double = 1013.25

    /** Time constants in seconds. Tunable for responsiveness vs. noise. */
    private val altitudeTauSec: Double = 0.20
    private val speedTauSec: Double = 0.40

    @Volatile private var initialized: Boolean = false
    @Volatile private var lastSampleNs: Long = 0
    @Volatile private var smoothedAltitudeM: Double = 0.0
    @Volatile private var smoothedVerticalMps: Double = 0.0
    @Volatile private var lastAltitudeM: Double = 0.0

    /** Latest filtered vertical speed in m/s. Positive = climbing. */
    @Volatile var verticalSpeedMps: Float = 0f
        private set

    /** Filtered altitude (smoothed pressure altitude) in metres. */
    @Volatile var altitudeM: Float = 0f
        private set

    /** Reset internal state — useful when sensor reconnects after a long gap. */
    fun reset() {
        initialized = false
        smoothedVerticalMps = 0.0
        verticalSpeedMps = 0f
    }

    /**
     * Feed a barometer sample. Should be called from the barometer flow.
     *
     * @param pressureHpa raw atmospheric pressure
     */
    fun onPressureSample(pressureHpa: Float) {
        // Pressure to altitude (hypsometric / barometric formula, ICAO troposphere).
        // h = 44330 × (1 − (p/p0)^(1/5.255))
        val rawAltitude = 44330.0 * (1.0 - (pressureHpa / p0).pow(1.0 / 5.255))

        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (!initialized) {
            smoothedAltitudeM = rawAltitude
            lastAltitudeM = rawAltitude
            lastSampleNs = nowNs
            altitudeM = rawAltitude.toFloat()
            initialized = true
            return
        }

        val dtSec = (nowNs - lastSampleNs).coerceAtLeast(1).toDouble() * 1e-9
        lastSampleNs = nowNs

        // Skip absurd dt jumps (e.g. after the sensor was off for a while).
        if (dtSec > 2.0) {
            smoothedAltitudeM = rawAltitude
            lastAltitudeM = rawAltitude
            altitudeM = rawAltitude.toFloat()
            return
        }

        // Stage 1: low-pass altitude (IIR, time-constant tuning).
        val altAlpha = 1.0 - kotlin.math.exp(-dtSec / altitudeTauSec)
        smoothedAltitudeM += altAlpha * (rawAltitude - smoothedAltitudeM)

        // Stage 2: derivative — instantaneous vertical speed since last sample.
        val rawVerticalMps = (smoothedAltitudeM - lastAltitudeM) / dtSec
        lastAltitudeM = smoothedAltitudeM

        // Stage 3: low-pass the derivative (smooths the hop without much lag).
        val spdAlpha = 1.0 - kotlin.math.exp(-dtSec / speedTauSec)
        smoothedVerticalMps += spdAlpha * (rawVerticalMps - smoothedVerticalMps)

        // Hard clamp tiny noise to zero (deadband ~5 cm/s).
        val out = if (abs(smoothedVerticalMps) < 0.05) 0.0 else smoothedVerticalMps

        verticalSpeedMps = out.toFloat()
        altitudeM = smoothedAltitudeM.toFloat()
    }
}
