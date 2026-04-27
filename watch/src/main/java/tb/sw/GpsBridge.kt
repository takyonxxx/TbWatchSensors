package tb.sw

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import tb.sw.shared.SensorProtocol.GpsPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * GNSS / GPS bridge using Android's LocationManager. Reads from
 * `GPS_PROVIDER` directly (not the fused provider) so we get raw
 * GNSS fixes and can observe the satellite count via GnssStatus.
 *
 * Reference-counted via [acquire]/[release] just like the Samsung
 * and Android sensor bridges. Idle = location updates disabled,
 * which matters for the GNSS receiver's substantial power draw.
 *
 * Galaxy Watch 8 supports dual-band GPS (L1 + L5), giving better
 * accuracy than older watches; the API is identical though, just
 * the underlying chip is more capable.
 */
class GpsBridge(private val context: Context) {

    private companion object {
        const val TAG = "GpsBridge"
        // Ask for an update every second; the chip may deliver less often
        // depending on satellite visibility.
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 0f
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val gpsCh = Channel<GpsPacket>(capacity = 64)
    val gpsFlow: Flow<GpsPacket> = gpsCh.receiveAsFlow()

    @Volatile var droppedPackets: Int = 0
        private set

    @Volatile private var satelliteCount: Int = 0
    @Volatile private var fixed: Boolean = false

    private val owners = mutableSetOf<String>()
    private val ownersLock = Any()

    private val locationListener = LocationListener { loc -> publish(loc) }

    private val gnssCallback: GnssStatus.Callback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satelliteCount = status.satelliteCount
                    // "Used in fix" count gives a better idea of the actual fix
                    // quality than total visible satellites.
                    var usedInFix = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedInFix++
                    }
                    WatchStats.update {
                        copy(gpsSatellitesVisible = status.satelliteCount,
                             gpsSatellitesUsed = usedInFix)
                    }
                }

                override fun onStarted() { Log.i(TAG, "GNSS started") }
                override fun onStopped() { Log.i(TAG, "GNSS stopped"); fixed = false }
                override fun onFirstFix(ttffMillis: Int) {
                    Log.i(TAG, "First fix in ${ttffMillis}ms")
                    fixed = true
                }
            }
        } else null

    fun acquire(tag: String) {
        synchronized(ownersLock) {
            val wasEmpty = owners.isEmpty()
            owners += tag
            if (wasEmpty) startUpdates()
        }
    }

    fun release(tag: String) {
        synchronized(ownersLock) {
            if (!owners.remove(tag)) return
            if (owners.isEmpty()) stopUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start GPS — ACCESS_FINE_LOCATION not granted")
            WatchStats.update { copy(gpsState = "no permission") }
            return
        }
        val gpsAvailable = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) { false }
        if (!gpsAvailable) {
            Log.w(TAG, "GPS provider not enabled on this device")
            WatchStats.update { copy(gpsState = "GPS disabled") }
            return
        }

        WatchStats.update { copy(gpsState = "searching…") }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                MIN_DISTANCE_M,
                locationListener,
            )
            // Last known fix is delivered immediately if available, gives
            // the user a stale-but-instant hint while the receiver warms up.
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.let { publish(it, stale = true) }

            val cb = gnssCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && cb != null) {
                locationManager.registerGnssStatusCallback(cb, null)
            }
            Log.i(TAG, "GPS updates started")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start GPS: ${t.message}")
            WatchStats.update { copy(gpsState = "error: ${t.message ?: "unknown"}") }
        }
    }

    private fun stopUpdates() {
        runCatching { locationManager.removeUpdates(locationListener) }
        val cb = gnssCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && cb != null) {
            runCatching { locationManager.unregisterGnssStatusCallback(cb) }
        }
        fixed = false
        WatchStats.update { copy(gpsState = "off") }
        Log.i(TAG, "GPS updates stopped")
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun publish(loc: Location, stale: Boolean = false) {
        val ts = SystemClock.elapsedRealtime().toInt()
        val pkt = GpsPacket(
            timestampMs = ts,
            latitudeDeg = loc.latitude,
            longitudeDeg = loc.longitude,
            altitudeM = if (loc.hasAltitude()) loc.altitude.toFloat() else 0f,
            speedMps = if (loc.hasSpeed()) loc.speed else 0f,
            bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else 0f,
            satellitesUsed = if (loc.extras?.getInt("satellites", 0) != null) {
                loc.extras!!.getInt("satellites", 0)
            } else satelliteCount,
            fixed = if (stale) false else true,
        )
        if (gpsCh.trySend(pkt).isSuccess) {
            fixed = !stale
            WatchStats.onGpsSample(pkt)
        } else droppedPackets++
    }
}
