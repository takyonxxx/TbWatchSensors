package tb.sw

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import tb.sw.shared.SensorProtocol.AccPacket
import tb.sw.shared.SensorProtocol.BiaPacket
import tb.sw.shared.SensorProtocol.EcgPacket
import tb.sw.shared.SensorProtocol.EdaPacket
import tb.sw.shared.SensorProtocol.HrPacket
import tb.sw.shared.SensorProtocol.MfBiaPacket
import tb.sw.shared.SensorProtocol.PpgPacket
import tb.sw.shared.SensorProtocol.SpO2Packet
import tb.sw.shared.SensorProtocol.SweatPacket
import tb.sw.shared.SensorProtocol.TempPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Samsung Health Sensor SDK wrapper — exposes all ten data types.
 *
 *   Continuous trackers (start with [startContinuous] when phone requests
 *   streaming):
 *     - PPG raw (GREEN/RED/IR)
 *     - Heart rate continuous (with IBI list)
 *     - Accelerometer continuous
 *     - Skin temperature continuous
 *     - EDA continuous
 *
 *   On-demand trackers (started by explicit phone command, produce a single
 *   result then stop):
 *     - ECG on-demand
 *     - BIA on-demand
 *     - Multi-frequency BIA on-demand
 *     - SpO2 on-demand
 *     - Sweat loss (workout-based, toggle)
 *
 * Real-hardware only — connect() bails out cleanly if the Samsung Health
 * Tracking Service isn't installed (e.g., on the emulator). Trackers stay
 * off until explicitly started; idle = zero sensor draw.
 */
class SamsungSensorBridge(private val context: Context) {

    private companion object { const val TAG = "SamsungSensorBridge" }

    enum class Mode { Disconnected, Connecting, Connected }

    @Volatile var mode: Mode = Mode.Disconnected
        private set

    private var trackingService: HealthTrackingService? = null

    // Continuous trackers (active during streaming)
    private var ppgTracker: HealthTracker? = null
    private var hrTracker: HealthTracker? = null
    private var accTracker: HealthTracker? = null
    private var tempTracker: HealthTracker? = null
    private var edaTracker: HealthTracker? = null
    @Volatile private var continuousStarted: Boolean = false

    // On-demand trackers (active only during a measurement window)
    private var ecgTracker: HealthTracker? = null
    private var biaTracker: HealthTracker? = null
    private var mfbiaTracker: HealthTracker? = null
    private var spo2Tracker: HealthTracker? = null
    private var sweatTracker: HealthTracker? = null

    // -- Channels and flows ------------------------------------------------

    private val ppgCh    = Channel<PpgPacket>(capacity = 256)
    private val hrCh     = Channel<HrPacket>(capacity = 64)
    private val accCh    = Channel<AccPacket>(capacity = 256)
    private val tempCh   = Channel<TempPacket>(capacity = 16)
    private val edaCh    = Channel<EdaPacket>(capacity = 64)
    private val ecgCh    = Channel<EcgPacket>(capacity = 1024)
    private val biaCh    = Channel<BiaPacket>(capacity = 4)
    private val mfbiaCh  = Channel<MfBiaPacket>(capacity = 4)
    private val spo2Ch   = Channel<SpO2Packet>(capacity = 8)
    private val sweatCh  = Channel<SweatPacket>(capacity = 4)

    val ppgFlow:    Flow<PpgPacket>    = ppgCh.receiveAsFlow()
    val hrFlow:     Flow<HrPacket>     = hrCh.receiveAsFlow()
    val accFlow:    Flow<AccPacket>    = accCh.receiveAsFlow()
    val tempFlow:   Flow<TempPacket>   = tempCh.receiveAsFlow()
    val edaFlow:    Flow<EdaPacket>    = edaCh.receiveAsFlow()
    val ecgFlow:    Flow<EcgPacket>    = ecgCh.receiveAsFlow()
    val biaFlow:    Flow<BiaPacket>    = biaCh.receiveAsFlow()
    val mfbiaFlow:  Flow<MfBiaPacket>  = mfbiaCh.receiveAsFlow()
    val spo2Flow:   Flow<SpO2Packet>   = spo2Ch.receiveAsFlow()
    val sweatFlow:  Flow<SweatPacket>  = sweatCh.receiveAsFlow()

    @Volatile var droppedPackets: Int = 0
        private set

    // -- Connection lifecycle ----------------------------------------------

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Health Tracking Service connected")
            mode = Mode.Connected
            WatchStats.update { copy(samsungSdkState = "Connected") }
        }

        override fun onConnectionEnded() {
            Log.i(TAG, "Health Tracking Service disconnected")
            mode = Mode.Disconnected
            continuousStarted = false
            WatchStats.update { copy(samsungSdkState = "Disconnected") }
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Health Tracking Service connection failed: ${e.errorCode} ${e.message}")
            mode = Mode.Disconnected
            WatchStats.update { copy(samsungSdkState = "SDK error ${e.errorCode}") }
        }
    }

    fun connect() {
        if (mode != Mode.Disconnected) return
        mode = Mode.Connecting
        WatchStats.update { copy(samsungSdkState = "Connecting…") }

        val installed = try {
            context.packageManager.getPackageInfo("com.samsung.android.service.health", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            Log.w(TAG, "Samsung Health Service not installed; bridge stays disconnected")
            mode = Mode.Disconnected
            WatchStats.update { copy(samsungSdkState = "Service not installed") }
            return
        }

        try {
            trackingService = HealthTrackingService(connectionListener, context).also {
                it.connectService()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Health Tracking Service init failed: ${t.message}")
            mode = Mode.Disconnected
            WatchStats.update {
                copy(samsungSdkState = "Init error: ${t.message ?: "unknown"}")
            }
        }
    }

    fun disconnect() {
        stopContinuous()
        stopAllOnDemand()
        runCatching { trackingService?.disconnectService() }
        trackingService = null
        mode = Mode.Disconnected
    }

    // -- Continuous trackers -----------------------------------------------

    fun startContinuous() {
        if (continuousStarted) return
        val service = trackingService ?: return
        if (mode != Mode.Connected) return

        ppgTracker = attachContinuous(
            service, HealthTrackerType.PPG_CONTINUOUS,
            ppgArg = setOf(PpgType.GREEN, PpgType.RED, PpgType.IR),
            onData = { onPpg(it) }, name = "PPG",
        )
        hrTracker = attachContinuous(
            service, HealthTrackerType.HEART_RATE_CONTINUOUS,
            onData = { onHr(it) }, name = "HR",
        )
        accTracker = attachContinuous(
            service, HealthTrackerType.ACCELEROMETER_CONTINUOUS,
            onData = { onAcc(it) }, name = "ACC",
        )
        tempTracker = attachContinuous(
            service, HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
            onData = { onTemp(it) }, name = "TEMP",
        )
        edaTracker = attachContinuous(
            service, HealthTrackerType.EDA_CONTINUOUS,
            onData = { onEda(it) }, name = "EDA",
        )

        continuousStarted = true
        Log.i(TAG, "Continuous trackers started")
    }

    fun stopContinuous() {
        if (!continuousStarted) return
        listOf(ppgTracker, hrTracker, accTracker, tempTracker, edaTracker)
            .forEach { runCatching { it?.unsetEventListener() } }
        ppgTracker = null; hrTracker = null
        accTracker = null; tempTracker = null
        edaTracker = null
        continuousStarted = false
        Log.i(TAG, "Continuous trackers stopped")
    }

    // -- On-demand trackers ------------------------------------------------

    /**
     * Generic helper. Wraps `service.getHealthTracker(...)` with try/catch
     * because trackers may not be available on every device (e.g. some
     * watches lack BIA hardware).
     */
    private fun attachContinuous(
        service: HealthTrackingService,
        type: HealthTrackerType,
        ppgArg: Set<PpgType>? = null,
        onData: (DataPoint) -> Unit,
        name: String,
    ): HealthTracker? {
        return runCatching {
            val tracker = if (ppgArg != null) {
                service.getHealthTracker(type, ppgArg)
            } else service.getHealthTracker(type)
            tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(list: MutableList<DataPoint>) {
                    for (dp in list) onData(dp)
                }
                override fun onFlushCompleted() = Unit
                override fun onError(error: HealthTracker.TrackerError) {
                    Log.w(TAG, "$name tracker error: $error")
                }
            })
            tracker
        }.onFailure {
            Log.w(TAG, "$name tracker not available: ${it.message}")
        }.getOrNull()
    }

    private fun attachOnDemand(
        service: HealthTrackingService,
        type: HealthTrackerType,
        onData: (DataPoint) -> Unit,
        name: String,
    ): HealthTracker? = attachContinuous(service, type, null, onData, name)

    fun startEcgMeasurement(): Boolean {
        if (mode != Mode.Connected) return false
        val service = trackingService ?: return false
        stopOnDemand(ecgTracker); ecgTracker = null
        ecgTracker = attachOnDemand(service, HealthTrackerType.ECG_ON_DEMAND,
            onData = { onEcg(it) }, name = "ECG")
        if (ecgTracker != null) {
            Log.i(TAG, "ECG measurement started")
            WatchStats.update { copy(activeMeasurement = "ECG") }
            return true
        }
        return false
    }

    fun startBiaMeasurement(): Boolean {
        if (mode != Mode.Connected) return false
        val service = trackingService ?: return false
        stopOnDemand(biaTracker); biaTracker = null
        biaTracker = attachOnDemand(service, HealthTrackerType.BIA_ON_DEMAND,
            onData = { onBia(it) }, name = "BIA")
        if (biaTracker != null) {
            Log.i(TAG, "BIA measurement started")
            WatchStats.update { copy(activeMeasurement = "BIA") }
            return true
        }
        return false
    }

    fun startMfBiaMeasurement(): Boolean {
        if (mode != Mode.Connected) return false
        val service = trackingService ?: return false
        stopOnDemand(mfbiaTracker); mfbiaTracker = null
        mfbiaTracker = attachOnDemand(service, HealthTrackerType.MF_BIA_ON_DEMAND,
            onData = { onMfBia(it) }, name = "MFBIA")
        if (mfbiaTracker != null) {
            Log.i(TAG, "MF-BIA measurement started")
            WatchStats.update { copy(activeMeasurement = "MF-BIA") }
            return true
        }
        return false
    }

    fun startSpO2Measurement(): Boolean {
        if (mode != Mode.Connected) return false
        val service = trackingService ?: return false
        stopOnDemand(spo2Tracker); spo2Tracker = null
        spo2Tracker = attachOnDemand(service, HealthTrackerType.SPO2_ON_DEMAND,
            onData = { onSpO2(it) }, name = "SpO2")
        if (spo2Tracker != null) {
            Log.i(TAG, "SpO2 measurement started")
            WatchStats.update { copy(activeMeasurement = "SpO2") }
            return true
        }
        return false
    }

    fun toggleSweatMeasurement(): Boolean {
        if (mode != Mode.Connected) return false
        if (sweatTracker != null) {
            stopOnDemand(sweatTracker); sweatTracker = null
            WatchStats.update { copy(activeMeasurement = "—") }
            Log.i(TAG, "Sweat-loss tracker stopped")
            return true
        }
        val service = trackingService ?: return false
        sweatTracker = attachOnDemand(service, HealthTrackerType.SWEAT_LOSS,
            onData = { onSweat(it) }, name = "SWEAT")
        if (sweatTracker != null) {
            Log.i(TAG, "Sweat-loss tracker started (workout)")
            WatchStats.update { copy(activeMeasurement = "Sweat") }
            return true
        }
        return false
    }

    fun cancelOnDemand() {
        stopAllOnDemand()
        WatchStats.update { copy(activeMeasurement = "—") }
        Log.i(TAG, "On-demand measurements cancelled")
    }

    private fun stopAllOnDemand() {
        stopOnDemand(ecgTracker); ecgTracker = null
        stopOnDemand(biaTracker); biaTracker = null
        stopOnDemand(mfbiaTracker); mfbiaTracker = null
        stopOnDemand(spo2Tracker); spo2Tracker = null
        stopOnDemand(sweatTracker); sweatTracker = null
    }

    private fun stopOnDemand(t: HealthTracker?) {
        runCatching { t?.unsetEventListener() }
    }

    // -- DataPoint decoders ------------------------------------------------

    private fun nowTs(): Int = SystemClock.elapsedRealtime().toInt()

    private fun onPpg(dp: DataPoint) {
        val ts = nowTs()
        val green = dp.getValue(ValueKey.PpgSet.PPG_GREEN) ?: 0
        val red = dp.getValue(ValueKey.PpgSet.PPG_RED) ?: 0
        val ir = dp.getValue(ValueKey.PpgSet.PPG_IR) ?: 0
        if (ppgCh.trySend(PpgPacket(ts, green, red, ir, 0)).isSuccess) {
            WatchStats.onPpgSample(green, red, ir)
        } else droppedPackets++
    }

    private fun onHr(dp: DataPoint) {
        val ts = nowTs()
        val bpm = (dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0).toShort()
        val ibi = (dp.getValue(ValueKey.HeartRateSet.IBI_LIST)?.firstOrNull() ?: 0).toShort()
        if (hrCh.trySend(HrPacket(ts, bpm, ibi)).isSuccess) {
            WatchStats.onHrSample(bpm.toInt(), ibi.toInt())
        } else droppedPackets++
    }

    private fun onAcc(dp: DataPoint) {
        val ts = nowTs()
        // Galaxy Watch reports raw counts; SDK doc says 1 g ~= 4096 counts.
        val rawX = (dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) ?: 0).toFloat()
        val rawY = (dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) ?: 0).toFloat()
        val rawZ = (dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) ?: 0).toFloat()
        val k = 9.80665f / 4096f
        val x = rawX * k; val y = rawY * k; val z = rawZ * k
        if (accCh.trySend(AccPacket(ts, x, y, z)).isSuccess) {
            WatchStats.onAccSample(x, y, z)
        } else droppedPackets++
    }

    private fun onTemp(dp: DataPoint) {
        val ts = nowTs()
        val skin = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE) ?: 0f
        val ambient = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE) ?: 0f
        if (tempCh.trySend(TempPacket(ts, skin, ambient)).isSuccess) {
            WatchStats.onTempSample(skin, ambient)
        } else droppedPackets++
    }

    private fun onEda(dp: DataPoint) {
        val ts = nowTs()
        val sc = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) ?: 0f
        val st = dp.getValue(ValueKey.EdaSet.STATUS) ?: 0
        if (edaCh.trySend(EdaPacket(ts, sc, st)).isSuccess) {
            WatchStats.onEdaSample(sc, st)
        } else droppedPackets++
    }

    private fun onEcg(dp: DataPoint) {
        val ts = nowTs()
        val mv = dp.getValue(ValueKey.EcgSet.ECG_MV) ?: 0f
        val mn = dp.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV) ?: 0f
        val mx = dp.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV) ?: 0f
        val lead = dp.getValue(ValueKey.EcgSet.LEAD_OFF) ?: 0
        val seq = dp.getValue(ValueKey.EcgSet.SEQUENCE) ?: 0
        val ppgGreen = dp.getValue(ValueKey.EcgSet.PPG_GREEN) ?: 0
        if (ecgCh.trySend(EcgPacket(ts, mv, mn, mx, lead, seq, ppgGreen)).isSuccess) {
            WatchStats.onEcgSample(mv, lead != 0)
        } else droppedPackets++
    }

    private fun onBia(dp: DataPoint) {
        val ts = nowTs()
        val pkt = BiaPacket(
            timestampMs = ts,
            status = dp.getValue(ValueKey.BiaSet.STATUS) ?: -1,
            bodyFatRatio = dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO) ?: 0f,
            bodyFatMassKg = dp.getValue(ValueKey.BiaSet.BODY_FAT_MASS) ?: 0f,
            totalBodyWaterKg = dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER) ?: 0f,
            skeletalMuscleRatio = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_RATIO) ?: 0f,
            skeletalMuscleMassKg = dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS) ?: 0f,
            basalMetabolicRateKcal = dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE) ?: 0f,
            fatFreeRatio = dp.getValue(ValueKey.BiaSet.FAT_FREE_RATIO) ?: 0f,
            fatFreeMassKg = dp.getValue(ValueKey.BiaSet.FAT_FREE_MASS) ?: 0f,
            impedanceMagnitudeOhm = dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_MAGNITUDE) ?: 0f,
            impedancePhaseDeg = dp.getValue(ValueKey.BiaSet.BODY_IMPEDANCE_DEGREE) ?: 0f,
        )
        if (biaCh.trySend(pkt).isSuccess) {
            WatchStats.onBiaResult(pkt)
            WatchStats.update { copy(activeMeasurement = "—") }
            stopOnDemand(biaTracker); biaTracker = null
        } else droppedPackets++
    }

    private fun onMfBia(dp: DataPoint) {
        val ts = nowTs()
        val pkt = MfBiaPacket(
            timestampMs = ts,
            status = dp.getValue(ValueKey.MfBiaSet.STATUS) ?: -1,
            mag5kOhm = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K) ?: 0f,
            phase5kDeg = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K) ?: 0f,
            mag10kOhm = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K) ?: 0f,
            phase10kDeg = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K) ?: 0f,
            mag50kOhm = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K) ?: 0f,
            phase50kDeg = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K) ?: 0f,
            mag250kOhm = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K) ?: 0f,
            phase250kDeg = dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K) ?: 0f,
        )
        if (mfbiaCh.trySend(pkt).isSuccess) {
            WatchStats.onMfBiaResult(pkt)
            WatchStats.update { copy(activeMeasurement = "—") }
            stopOnDemand(mfbiaTracker); mfbiaTracker = null
        } else droppedPackets++
    }

    private fun onSpO2(dp: DataPoint) {
        val ts = nowTs()
        val pkt = SpO2Packet(
            timestampMs = ts,
            status = dp.getValue(ValueKey.SpO2Set.STATUS) ?: -1,
            spo2Percent = dp.getValue(ValueKey.SpO2Set.SPO2) ?: 0,
            heartRateBpm = dp.getValue(ValueKey.SpO2Set.HEART_RATE) ?: 0,
            accuracyFlag = dp.getValue(ValueKey.SpO2Set.ACCURACY_FLAG) ?: 0,
        )
        if (spo2Ch.trySend(pkt).isSuccess) {
            WatchStats.onSpO2Sample(pkt)
            // status==2 (success) per Samsung docs — stop after a complete reading
            if (pkt.status == 2) {
                WatchStats.update { copy(activeMeasurement = "—") }
                stopOnDemand(spo2Tracker); spo2Tracker = null
            }
        } else droppedPackets++
    }

    private fun onSweat(dp: DataPoint) {
        val ts = nowTs()
        val pkt = SweatPacket(
            timestampMs = ts,
            status = dp.getValue(ValueKey.SweatLossSet.STATUS) ?: -1,
            sweatLossGrams = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS) ?: 0f,
        )
        if (sweatCh.trySend(pkt).isSuccess) {
            WatchStats.onSweatResult(pkt)
        } else droppedPackets++
    }
}
