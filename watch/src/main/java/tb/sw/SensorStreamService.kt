package tb.sw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import tb.sw.shared.SensorProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Long-running foreground service. Owns the Samsung sensor bridge and the GATT
 * server; routes SDK callbacks to BLE notifications.
 *
 * Continuous streams (PPG/HR/ACC/Temp/EDA) flow whenever at least one owner
 * holds a streaming reference. Two owners exist:
 *   - `"ui"` — held while the dashboard Activity is in the foreground.
 *   - `"ble"` — held between BLE central START_STREAM and STOP_STREAM commands.
 *
 * Either is sufficient — sensors run while the user is looking, while a phone
 * is subscribed, or both. They stop only when both have released.
 *
 * On-demand measurements (ECG/BIA/MF-BIA/SpO2/SweatLoss) are independent of
 * this reference count: they fire when their command arrives, regardless of
 * UI state, and produce a one-shot result.
 */
class SensorStreamService : Service() {

    private companion object {
        const val TAG = "SensorStreamService"
        const val CHANNEL_ID = "tbwatchsensors_stream"
        const val NOTIFICATION_ID = 42
        const val OWNER_BLE = "ble"
        const val OWNER_UI = "ui"
    }

    /** Returned to the Activity when it binds. UI uses this to acquire/release. */
    inner class LocalBinder : Binder() {
        fun acquireUi() {
            bridge.acquire(OWNER_UI)
            androidBridge.acquire(OWNER_UI)
            gpsBridge.acquire(OWNER_UI)
        }
        fun releaseUi() {
            bridge.release(OWNER_UI)
            androidBridge.release(OWNER_UI)
            gpsBridge.release(OWNER_UI)
        }
    }

    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bridge: SamsungSensorBridge
    private lateinit var androidBridge: AndroidSensorBridge
    private lateinit var gpsBridge: GpsBridge
    private lateinit var gatt: SensorGattServer
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var sessionId: Int = 0
    private var continuousJob: Job? = null
    private var ondemandJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TbWatchSensors:StreamWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        bridge = SamsungSensorBridge(this)
        androidBridge = AndroidSensorBridge(this)
        gpsBridge = GpsBridge(this)
        gatt = SensorGattServer(this).apply {
            commandListener = ::handleCommand
        }
        bridge.connect()
        gatt.start()

        // Continuous fanout always runs — it's a no-op when nothing is in
        // the flow channels, and starts delivering as soon as a tracker
        // activates (which happens when someone calls acquire()).
        startContinuousFanout()
        startOnDemandFanout()

        // Periodic status push.
        scope.launch {
            while (true) {
                delay(2_000)
                pushStatus()
            }
        }

        // Stats refresh loop.
        val startMs = System.currentTimeMillis()
        scope.launch {
            while (true) {
                delay(1_000)
                val uptime = (System.currentTimeMillis() - startMs) / 1000
                val isStreaming = bridge.isStreaming
                val currentSession = sessionId
                val dropped = bridge.droppedPackets
                val battery = readBatteryPercent().toInt() and 0xFF
                WatchStats.computeRates(uptime)
                WatchStats.update {
                    copy(
                        streaming = isStreaming,
                        sessionId = currentSession,
                        droppedPackets = dropped,
                        batteryPct = battery,
                    )
                }
                // Update the notification text so the OS shows the current state.
                val notifText = if (isStreaming) "Streaming" else "Idle"
                runCatching { startForeground(NOTIFICATION_ID, buildNotification(notifText)) }
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sensor stream", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(state: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TbWatchSensors")
            .setContentText(state)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        if (type != 0) {
            startForeground(NOTIFICATION_ID,
                buildNotification(if (bridge.isStreaming) "Streaming" else "Idle"), type)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousJob?.cancel()
        ondemandJob?.cancel()
        runCatching { tb.sw.vario.VarioState.audio.release() }
        bridge.disconnect()
        gatt.stop()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -- Command handling ---------------------------------------------------

    private fun handleCommand(cmd: Byte) {
        when (cmd) {
            SensorProtocol.CMD_START_STREAM -> bleStart()
            SensorProtocol.CMD_STOP_STREAM  -> bleStop()
            SensorProtocol.CMD_MEASURE_ECG -> bridge.startEcgMeasurement()
            SensorProtocol.CMD_MEASURE_BIA -> bridge.startBiaMeasurement()
            SensorProtocol.CMD_MEASURE_MFBIA -> bridge.startMfBiaMeasurement()
            SensorProtocol.CMD_MEASURE_SPO2 -> bridge.startSpO2Measurement()
            SensorProtocol.CMD_TOGGLE_SWEAT -> bridge.toggleSweatMeasurement()
            SensorProtocol.CMD_CANCEL_MEASURE -> bridge.cancelOnDemand()
            SensorProtocol.CMD_RESET_SESSION -> {
                sessionId++
                Log.i(TAG, "session reset -> $sessionId")
            }
            SensorProtocol.CMD_REQUEST_BATTERY -> pushStatus()
            else -> Log.d(TAG, "unhandled cmd ${cmd.toInt() and 0xFF}")
        }
    }

    private fun bleStart() {
        sessionId++
        bridge.acquire(OWNER_BLE)
        androidBridge.acquire(OWNER_BLE)
        gpsBridge.acquire(OWNER_BLE)
        Log.i(TAG, "BLE central started streaming, session=$sessionId")
    }

    private fun bleStop() {
        bridge.release(OWNER_BLE)
        androidBridge.release(OWNER_BLE)
        gpsBridge.release(OWNER_BLE)
        Log.i(TAG, "BLE central stopped streaming")
    }

    // -- Fanout: bridge → BLE notifications --------------------------------

    private fun startContinuousFanout() {
        continuousJob = scope.launch {
            launch { bridge.ppgFlow.collectLatest { gatt.notifyPpg(it.toBytes()) } }
            launch { bridge.hrFlow.collectLatest { gatt.notifyHr(it.toBytes()) } }
            launch { bridge.accFlow.collectLatest { gatt.notifyAcc(it.toBytes()) } }
            launch { bridge.tempFlow.collectLatest { gatt.notifyTemp(it.toBytes()) } }
            launch { bridge.edaFlow.collectLatest { gatt.notifyEda(it.toBytes()) } }
            launch { androidBridge.gyroFlow.collectLatest { gatt.notifyGyro(it.toBytes()) } }
            launch { androidBridge.magFlow.collectLatest { gatt.notifyMag(it.toBytes()) } }
            launch {
                androidBridge.baroFlow.collectLatest { pkt ->
                    gatt.notifyBaro(pkt.toBytes())
                    tb.sw.vario.VarioState.onPressureSample(pkt.pressureHpa)
                }
            }
            launch { androidBridge.lightFlow.collectLatest { gatt.notifyLight(it.toBytes()) } }
            launch { gpsBridge.gpsFlow.collectLatest { gatt.notifyGps(it.toBytes()) } }
        }
    }

    private fun startOnDemandFanout() {
        ondemandJob = scope.launch {
            launch { bridge.ecgFlow.collectLatest { gatt.notifyEcg(it.toBytes()) } }
            launch { bridge.biaFlow.collectLatest { gatt.notifyBia(it.toBytes()) } }
            launch { bridge.mfbiaFlow.collectLatest { gatt.notifyMfBia(it.toBytes()) } }
            launch { bridge.spo2Flow.collectLatest { gatt.notifySpO2(it.toBytes()) } }
            launch { bridge.sweatFlow.collectLatest { gatt.notifySweat(it.toBytes()) } }
        }
    }

    // -- Status -------------------------------------------------------------

    private fun pushStatus() {
        val battery = readBatteryPercent()
        val active = when (WatchStats.state.value.activeMeasurement) {
            "ECG" -> SensorProtocol.MEASURE_ECG
            "BIA" -> SensorProtocol.MEASURE_BIA
            "MF-BIA" -> SensorProtocol.MEASURE_MFBIA
            "SpO2" -> SensorProtocol.MEASURE_SPO2
            "Sweat" -> SensorProtocol.MEASURE_SWEAT
            else -> SensorProtocol.MEASURE_NONE
        }
        val pkt = SensorProtocol.StatusPacket(
            sessionId = sessionId,
            droppedPackets = bridge.droppedPackets.coerceAtMost(Short.MAX_VALUE.toInt()).toShort(),
            batteryPercent = battery,
            streaming = bridge.isStreaming,
            sampleRateHz = 25,
            activeMeasurement = active,
        )
        gatt.notifyStatus(pkt.toBytes())
    }

    private fun readBatteryPercent(): Byte {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toByte()
        } catch (_: Throwable) { 0 }
    }
}
