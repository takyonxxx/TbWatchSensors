package tb.sw

import tb.sw.shared.SensorProtocol.BiaPacket
import tb.sw.shared.SensorProtocol.MfBiaPacket
import tb.sw.shared.SensorProtocol.SpO2Packet
import tb.sw.shared.SensorProtocol.SweatPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live runtime stats for the watch dashboard.
 *
 * Holds the latest sample of every continuous stream plus the latest result
 * of every on-demand measurement. The Compose UI reads from a single
 * StateFlow snapshot.
 */
object WatchStats {

    data class Snapshot(
        // Connection state
        val samsungSdkState: String = "Initializing…",
        val gattAdvertising: Boolean = false,
        val gattSubscribers: Int = 0,
        val gattConnections: Int = 0,
        val streaming: Boolean = false,
        val streamOwners: Int = 0,
        val activeMeasurement: String = "—",

        // Continuous: PPG
        val ppgGreen: Int = 0, val ppgRed: Int = 0, val ppgIr: Int = 0,
        val ppgRateHz: Float = 0f,

        // Continuous: heart rate
        val bpm: Int = 0, val ibiMs: Int = 0, val hrRateHz: Float = 0f,

        // Continuous: motion
        val accMagnitude: Float = 0f, val accRateHz: Float = 0f,

        // Continuous: temperature
        val skinTempC: Float = 0f, val ambientTempC: Float = 0f,

        // Continuous: EDA
        val edaSkinConductanceUs: Float = 0f, val edaStatus: Int = 0,
        val edaRateHz: Float = 0f,

        // On-demand: ECG
        val ecgLatestMv: Float = 0f, val ecgLeadOff: Boolean = false,
        val ecgSampleCount: Int = 0,

        // On-demand: BIA
        val biaBodyFatRatio: Float = 0f,
        val biaSkeletalMuscleRatio: Float = 0f,
        val biaTotalBodyWaterKg: Float = 0f,
        val biaBmrKcal: Float = 0f,
        val biaImpedanceOhm: Float = 0f,
        val biaTimestampMs: Int = 0,

        // On-demand: MF-BIA
        val mfbiaMag5k: Float = 0f, val mfbiaMag50k: Float = 0f,
        val mfbiaMag250k: Float = 0f,
        val mfbiaTimestampMs: Int = 0,

        // On-demand: SpO2
        val spo2Percent: Int = 0, val spo2HeartRate: Int = 0,
        val spo2Status: Int = -1, val spo2HighAccuracy: Boolean = false,

        // On-demand: Sweat loss
        val sweatLossGrams: Float = 0f, val sweatStatus: Int = -1,

        // Bandwidth
        val bytesPerSec: Int = 0, val totalPackets: Long = 0,
        val droppedPackets: Int = 0,

        // Battery, session, uptime
        val batteryPct: Int = 0,
        val sessionId: Int = 0,
        val uptimeSec: Long = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // -- Counters -----------------------------------------------------------

    @Volatile private var ppgCount: Long = 0
    @Volatile private var hrCount: Long = 0
    @Volatile private var accCount: Long = 0
    @Volatile private var edaCount: Long = 0
    @Volatile private var bytesSent: Long = 0
    private var lastWindowMs: Long = System.currentTimeMillis()
    private var lastPpgCount: Long = 0
    private var lastHrCount: Long = 0
    private var lastAccCount: Long = 0
    private var lastEdaCount: Long = 0
    private var lastBytes: Long = 0

    fun update(transform: Snapshot.() -> Snapshot) {
        _state.value = _state.value.transform()
    }

    // -- Continuous-sample callbacks ---------------------------------------

    fun onPpgSample(green: Int, red: Int, ir: Int) {
        ppgCount++
        update { copy(ppgGreen = green, ppgRed = red, ppgIr = ir) }
    }

    fun onHrSample(bpm: Int, ibiMs: Int) {
        hrCount++
        update { copy(bpm = bpm, ibiMs = ibiMs) }
    }

    fun onAccSample(x: Float, y: Float, z: Float) {
        accCount++
        update { copy(accMagnitude = kotlin.math.sqrt(x * x + y * y + z * z)) }
    }

    fun onTempSample(skinC: Float, ambientC: Float) {
        update { copy(skinTempC = skinC, ambientTempC = ambientC) }
    }

    fun onEdaSample(skinConductance: Float, status: Int) {
        edaCount++
        update { copy(edaSkinConductanceUs = skinConductance, edaStatus = status) }
    }

    // -- On-demand result callbacks ----------------------------------------

    fun onEcgSample(mv: Float, leadOff: Boolean) {
        update {
            copy(ecgLatestMv = mv, ecgLeadOff = leadOff,
                ecgSampleCount = ecgSampleCount + 1)
        }
    }

    fun onBiaResult(p: BiaPacket) {
        update {
            copy(
                biaBodyFatRatio = p.bodyFatRatio,
                biaSkeletalMuscleRatio = p.skeletalMuscleRatio,
                biaTotalBodyWaterKg = p.totalBodyWaterKg,
                biaBmrKcal = p.basalMetabolicRateKcal,
                biaImpedanceOhm = p.impedanceMagnitudeOhm,
                biaTimestampMs = p.timestampMs,
            )
        }
    }

    fun onMfBiaResult(p: MfBiaPacket) {
        update {
            copy(
                mfbiaMag5k = p.mag5kOhm,
                mfbiaMag50k = p.mag50kOhm,
                mfbiaMag250k = p.mag250kOhm,
                mfbiaTimestampMs = p.timestampMs,
            )
        }
    }

    fun onSpO2Sample(p: SpO2Packet) {
        update {
            copy(
                spo2Percent = p.spo2Percent,
                spo2HeartRate = p.heartRateBpm,
                spo2Status = p.status,
                spo2HighAccuracy = p.accuracyFlag == 1,
            )
        }
    }

    fun onSweatResult(p: SweatPacket) {
        update { copy(sweatLossGrams = p.sweatLossGrams, sweatStatus = p.status) }
    }

    fun addBytes(n: Int) { bytesSent += n }

    /** Per-second rate computation. */
    fun computeRates(uptimeSec: Long) {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastWindowMs).coerceAtLeast(1)
        val dtSec = dtMs / 1000f

        val ppgPerSec = (ppgCount - lastPpgCount) / dtSec
        val hrPerSec = (hrCount - lastHrCount) / dtSec
        val accPerSec = (accCount - lastAccCount) / dtSec
        val edaPerSec = (edaCount - lastEdaCount) / dtSec
        val bps = ((bytesSent - lastBytes) / dtSec).toInt()

        lastWindowMs = now
        lastPpgCount = ppgCount; lastHrCount = hrCount
        lastAccCount = accCount; lastEdaCount = edaCount
        lastBytes = bytesSent

        update {
            copy(
                ppgRateHz = ppgPerSec, hrRateHz = hrPerSec,
                accRateHz = accPerSec, edaRateHz = edaPerSec,
                bytesPerSec = bps,
                totalPackets = ppgCount + hrCount + accCount + edaCount,
                uptimeSec = uptimeSec,
            )
        }
    }
}
