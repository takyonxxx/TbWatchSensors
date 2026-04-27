package tb.sw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import tb.sw.shared.SensorProtocol
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT peripheral on the watch. Hosts the TbWatchSensors service, advertises it, and
 * pushes notifications when sensor data arrives.
 *
 * Per-characteristic subscriber set so we only spend BLE airtime on streams the
 * phone actually wants.
 */
@SuppressLint("MissingPermission")
class SensorGattServer(private val context: Context) {

    private companion object {
        const val TAG = "SensorGattServer"
        const val DEVICE_NAME = "TbWatchSensors"
    }

    private val btManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // CharacteristicUUID -> set of subscribed devices.
    private val subscribers = ConcurrentHashMap<java.util.UUID, MutableSet<BluetoothDevice>>()

    var commandListener: ((Byte) -> Unit)? = null

    fun start() {
        if (gattServer != null) return

        gattServer = btManager.openGattServer(context, gattCallback).apply {
            addService(buildService())
        }
        startAdvertising()
        Log.i(TAG, "GATT server started, advertising '$DEVICE_NAME'")
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        advertiser = null
        gattServer = null
        subscribers.clear()
    }

    // -- Notification senders ----------------------------------------------

    fun notifyPpg(bytes: ByteArray) = notify(SensorProtocol.CHAR_PPG_UUID, bytes)
    fun notifyHr(bytes: ByteArray) = notify(SensorProtocol.CHAR_HR_UUID, bytes)
    fun notifyAcc(bytes: ByteArray) = notify(SensorProtocol.CHAR_ACC_UUID, bytes)
    fun notifyTemp(bytes: ByteArray) = notify(SensorProtocol.CHAR_TEMP_UUID, bytes)
    fun notifyEda(bytes: ByteArray) = notify(SensorProtocol.CHAR_EDA_UUID, bytes)
    fun notifyEcg(bytes: ByteArray) = notify(SensorProtocol.CHAR_ECG_UUID, bytes)
    fun notifyBia(bytes: ByteArray) = notify(SensorProtocol.CHAR_BIA_UUID, bytes)
    fun notifyMfBia(bytes: ByteArray) = notify(SensorProtocol.CHAR_MFBIA_UUID, bytes)
    fun notifySpO2(bytes: ByteArray) = notify(SensorProtocol.CHAR_SPO2_UUID, bytes)
    fun notifySweat(bytes: ByteArray) = notify(SensorProtocol.CHAR_SWEAT_UUID, bytes)
    fun notifyGyro(bytes: ByteArray) = notify(SensorProtocol.CHAR_GYRO_UUID, bytes)
    fun notifyMag(bytes: ByteArray) = notify(SensorProtocol.CHAR_MAG_UUID, bytes)
    fun notifyBaro(bytes: ByteArray) = notify(SensorProtocol.CHAR_BARO_UUID, bytes)
    fun notifyLight(bytes: ByteArray) = notify(SensorProtocol.CHAR_LIGHT_UUID, bytes)
    fun notifyGps(bytes: ByteArray) = notify(SensorProtocol.CHAR_GPS_UUID, bytes)
    fun notifyStatus(bytes: ByteArray) = notify(SensorProtocol.CHAR_STATUS_UUID, bytes)

    private fun notify(charUuid: java.util.UUID, bytes: ByteArray) {
        val server = gattServer ?: return
        val service = server.getService(SensorProtocol.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(charUuid) ?: return
        val devices = subscribers[charUuid] ?: return
        char.value = bytes
        for (d in devices) {
            runCatching { server.notifyCharacteristicChanged(d, char, false) }
            WatchStats.addBytes(bytes.size + 3) // +3 for ATT NTF header overhead
        }
    }

    // -- Service definition -------------------------------------------------

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            SensorProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // Notify-only characteristics for sensor streams + status + on-demand results.
        for (uuid in listOf(
            // Continuous streams
            SensorProtocol.CHAR_PPG_UUID,
            SensorProtocol.CHAR_HR_UUID,
            SensorProtocol.CHAR_ACC_UUID,
            SensorProtocol.CHAR_TEMP_UUID,
            SensorProtocol.CHAR_EDA_UUID,
            // On-demand results
            SensorProtocol.CHAR_ECG_UUID,
            SensorProtocol.CHAR_BIA_UUID,
            SensorProtocol.CHAR_MFBIA_UUID,
            SensorProtocol.CHAR_SPO2_UUID,
            SensorProtocol.CHAR_SWEAT_UUID,
            // Standard Android sensors
            SensorProtocol.CHAR_GYRO_UUID,
            SensorProtocol.CHAR_MAG_UUID,
            SensorProtocol.CHAR_BARO_UUID,
            SensorProtocol.CHAR_LIGHT_UUID,
            SensorProtocol.CHAR_GPS_UUID,
            // Status
            SensorProtocol.CHAR_STATUS_UUID,
        )) {
            service.addCharacteristic(buildNotifyCharacteristic(uuid))
        }

        // Write-only control characteristic for phone -> watch commands.
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                SensorProtocol.CHAR_CONTROL_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        return service
    }

    private fun buildNotifyCharacteristic(uuid: java.util.UUID): BluetoothGattCharacteristic {
        val char = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        char.addDescriptor(
            BluetoothGattDescriptor(
                SensorProtocol.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        return char
    }

    // -- Advertising --------------------------------------------------------

    private fun startAdvertising() {
        val adv = btAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser not available")
            return
        }
        advertiser = adv

        // Setting a friendly device name helps debugging in nRF Connect.
        runCatching { btAdapter.name = DEVICE_NAME }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // 128-bit service UUID alone is 18 bytes of the 31-byte advertise PDU.
        // Including the device name on top of it overflows on some stacks
        // (Android returns ADVERTISE_FAILED_DATA_TOO_LARGE / error 1). Put the
        // name in the scan response packet instead — same visibility, no overflow.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SensorProtocol.SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started successfully")
            WatchStats.update { copy(gattAdvertising = true) }
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            WatchStats.update { copy(gattAdvertising = false) }
        }
    }

    // -- GATT server callback ----------------------------------------------

    private val gattCallback = object : BluetoothGattServerCallback() {

        @Volatile private var connectedCount = 0

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "conn change ${device.address} status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedCount++
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedCount = (connectedCount - 1).coerceAtLeast(0)
                subscribers.values.forEach { it.remove(device) }
                val totalSubs = subscribers.values.sumOf { it.size }
                WatchStats.update {
                    copy(gattConnections = connectedCount, gattSubscribers = totalSubs)
                }
                return
            }
            WatchStats.update { copy(gattConnections = connectedCount) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == SensorProtocol.CHAR_CONTROL_UUID && value.isNotEmpty()) {
                commandListener?.invoke(value[0])
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value,
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == SensorProtocol.CCCD_UUID) {
                val charUuid = descriptor.characteristic.uuid
                val enable = value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                val set = subscribers.getOrPut(charUuid) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
                if (enable) set.add(device) else set.remove(device)
                Log.i(TAG, "subscription $charUuid for ${device.address} enable=$enable")
                val totalSubs = subscribers.values.sumOf { it.size }
                WatchStats.update { copy(gattSubscribers = totalSubs) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value,
                )
            }
        }
    }
}
