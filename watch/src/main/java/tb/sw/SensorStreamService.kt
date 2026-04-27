package tb.sw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Continuous streams (PPG/HR/ACC/Temp/EDA) flow whenever streaming is on. The
 * five on-demand measurements (ECG/BIA/MF-BIA/SpO2/SweatLoss) flow whenever
 * the corresponding measurement is active — they don't depend on streaming
 * state, so the user can fire an SpO2 reading without enabling continuous
 * streaming first.
 */
class SensorStreamService : Service() {

    private companion object {
        const val TAG = "SensorStreamService"
        const val CHANNEL_ID = "tbwatchsensors_stream"
        const val NOTIFICATION_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bridge: SamsungSensorBridge
    private lateinit var gatt: SensorGattServer
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var streaming = false
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
        gatt = SensorGattServer(this).apply {
            commandListener = ::handleCommand
        }
        bridge.connect()
        gatt.start()

        // On-demand results always flow (independently of streaming flag) so a
        // measurement triggered while streaming is off still reaches the central.
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
                val isStreaming = streaming
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
                buildNotification(if (streaming) "Streaming" else "Idle"), type)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousJob?.cancel()
        ondemandJob?.cancel()
        bridge.disconnect()
        gatt.stop()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- Command handling ---------------------------------------------------

    private fun handleCommand(cmd: Byte) {
        when (cmd) {
            SensorProtocol.CMD_START_STREAM -> startStreaming()
            SensorProtocol.CMD_STOP_STREAM -> stopStreaming()
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

    // -- Continuous streams -------------------------------------------------

    private fun startStreaming() {
        if (streaming) return
        streaming = true
        sessionId++

        bridge.startContinuous()

        continuousJob = scope.launch {
            launch { bridge.ppgFlow.collectLatest { gatt.notifyPpg(it.toBytes()) } }
            launch { bridge.hrFlow.collectLatest { gatt.notifyHr(it.toBytes()) } }
            launch { bridge.accFlow.collectLatest { gatt.notifyAcc(it.toBytes()) } }
            launch { bridge.tempFlow.collectLatest { gatt.notifyTemp(it.toBytes()) } }
            launch { bridge.edaFlow.collectLatest { gatt.notifyEda(it.toBytes()) } }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Streaming"))
        Log.i(TAG, "streaming started, session=$sessionId")
    }

    private fun stopStreaming() {
        if (!streaming) return
        streaming = false
        continuousJob?.cancel()
        continuousJob = null
        bridge.stopContinuous()
        startForeground(NOTIFICATION_ID, buildNotification("Idle"))
        Log.i(TAG, "streaming stopped")
    }

    // -- On-demand result fanout (always on) -------------------------------

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
            streaming = streaming,
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
