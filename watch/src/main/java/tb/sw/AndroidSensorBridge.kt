package tb.sw

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import tb.sw.shared.SensorProtocol.BarometerPacket
import tb.sw.shared.SensorProtocol.GyroPacket
import tb.sw.shared.SensorProtocol.LightPacket
import tb.sw.shared.SensorProtocol.MagnetometerPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Standard Android sensors that aren't in the Samsung Health Sensor SDK:
 * gyroscope, magnetometer (geomagnetic), barometer, ambient light. These
 * read through the regular Android `SensorManager` API and require no
 * partner approval — they work on every Wear OS device that has the
 * underlying hardware.
 *
 * Reference-counted just like SamsungSensorBridge so UI/BLE owners can
 * acquire and release independently. While idle, no events are delivered
 * and no power is drawn.
 */
class AndroidSensorBridge(context: Context) {

    private companion object {
        const val TAG = "AndroidSensorBridge"
        // SENSOR_DELAY_UI ≈ 60 ms (~16 Hz) — enough for an on-screen dashboard
        // without flooding the BLE link.
        const val SAMPLING_DELAY = SensorManager.SENSOR_DELAY_UI
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val mag: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val baro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val light: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val gyroCh = Channel<GyroPacket>(capacity = 128)
    private val magCh = Channel<MagnetometerPacket>(capacity = 128)
    private val baroCh = Channel<BarometerPacket>(capacity = 16)
    private val lightCh = Channel<LightPacket>(capacity = 16)

    val gyroFlow: Flow<GyroPacket> = gyroCh.receiveAsFlow()
    val magFlow: Flow<MagnetometerPacket> = magCh.receiveAsFlow()
    val baroFlow: Flow<BarometerPacket> = baroCh.receiveAsFlow()
    val lightFlow: Flow<LightPacket> = lightCh.receiveAsFlow()

    @Volatile var droppedPackets: Int = 0
        private set

    private val owners = mutableSetOf<String>()
    private val ownersLock = Any()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ts = SystemClock.elapsedRealtime().toInt()
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val pkt = GyroPacket(ts, event.values[0], event.values[1], event.values[2])
                    if (gyroCh.trySend(pkt).isSuccess) {
                        WatchStats.onGyroSample(pkt.x, pkt.y, pkt.z)
                    } else droppedPackets++
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val pkt = MagnetometerPacket(ts, event.values[0], event.values[1], event.values[2])
                    if (magCh.trySend(pkt).isSuccess) {
                        WatchStats.onMagSample(pkt.x, pkt.y, pkt.z)
                    } else droppedPackets++
                }
                Sensor.TYPE_PRESSURE -> {
                    // hPa from Android. Approx altitude using ICAO atmosphere.
                    val hpa = event.values[0]
                    val altitudeM = SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa)
                    val pkt = BarometerPacket(ts, hpa, altitudeM)
                    if (baroCh.trySend(pkt).isSuccess) {
                        WatchStats.onBaroSample(hpa, altitudeM)
                    } else droppedPackets++
                }
                Sensor.TYPE_LIGHT -> {
                    val lux = event.values[0]
                    val pkt = LightPacket(ts, lux)
                    if (lightCh.trySend(pkt).isSuccess) {
                        WatchStats.onLightSample(lux)
                    } else droppedPackets++
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun acquire(tag: String) {
        synchronized(ownersLock) {
            val wasEmpty = owners.isEmpty()
            owners += tag
            if (wasEmpty) startSensors()
        }
    }

    fun release(tag: String) {
        synchronized(ownersLock) {
            if (!owners.remove(tag)) return
            if (owners.isEmpty()) stopSensors()
        }
    }

    private fun startSensors() {
        listOfNotNull(gyro, mag, baro, light).forEach {
            sensorManager.registerListener(listener, it, SAMPLING_DELAY)
        }
        Log.i(TAG, "Standard sensors started " +
            "(gyro=${gyro != null}, mag=${mag != null}, baro=${baro != null}, light=${light != null})")
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(listener)
        Log.i(TAG, "Standard sensors stopped")
    }
}
