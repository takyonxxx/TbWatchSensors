package tb.sw.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * TbWatchSensors BLE GATT protocol.
 *
 * Covers all ten data types exposed by Samsung Health Sensor SDK 1.4.1:
 * Accelerometer, Heart rate (+IBI), PPG (G/R/IR), Skin temperature, EDA,
 * SpO2, ECG, BIA, Multi-frequency BIA, Sweat loss.
 *
 * Continuous trackers (PPG/HR/ACC/Temp/EDA) flow whenever streaming is
 * enabled. On-demand trackers (ECG/BIA/MF-BIA/SpO2/SweatLoss) are started
 * via dedicated control commands and produce a result packet when the
 * measurement completes.
 *
 * Single primary service, many characteristics. Watch is the GATT
 * peripheral. All values little-endian. No SLIP, no CRC; ATT layer
 * handles integrity. Timestamps are SystemClock.elapsedRealtime() ms
 * truncated to Int (rolls over after ~24 days uptime — fine).
 */
object SensorProtocol {

    // -- Service ------------------------------------------------------------

    val SERVICE_UUID: UUID = UUID.fromString("7b1d0001-1234-4567-8900-1a2b3c4d5e6f")

    // -- Continuous-stream characteristics (NOTIFY) -------------------------

    val CHAR_PPG_UUID:     UUID = uuid("7b1d0002")
    val CHAR_HR_UUID:      UUID = uuid("7b1d0003")
    val CHAR_ACC_UUID:     UUID = uuid("7b1d0004")
    val CHAR_TEMP_UUID:    UUID = uuid("7b1d0005")
    val CHAR_EDA_UUID:     UUID = uuid("7b1d0008")

    // -- Control + status ---------------------------------------------------

    val CHAR_CONTROL_UUID: UUID = uuid("7b1d0006")
    val CHAR_STATUS_UUID:  UUID = uuid("7b1d0007")

    // -- On-demand result characteristics (NOTIFY) --------------------------

    val CHAR_ECG_UUID:        UUID = uuid("7b1d0009")
    val CHAR_BIA_UUID:        UUID = uuid("7b1d000a")
    val CHAR_MFBIA_UUID:      UUID = uuid("7b1d000b")
    val CHAR_SPO2_UUID:       UUID = uuid("7b1d000c")
    val CHAR_SWEAT_UUID:      UUID = uuid("7b1d000d")

    // -- Standard Android sensors (not in Samsung SDK) ---------------------

    val CHAR_GYRO_UUID:       UUID = uuid("7b1d000e")
    val CHAR_MAG_UUID:        UUID = uuid("7b1d000f")
    val CHAR_BARO_UUID:       UUID = uuid("7b1d0010")
    val CHAR_LIGHT_UUID:      UUID = uuid("7b1d0011")
    val CHAR_GPS_UUID:        UUID = uuid("7b1d0012")

    /** Standard CCCD; clients write 0x01 0x00 to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun uuid(prefix8hex: String): UUID =
        UUID.fromString("$prefix8hex-1234-4567-8900-1a2b3c4d5e6f")

    // -- Control commands ---------------------------------------------------

    /** Start the continuous trackers (PPG/HR/ACC/Temp/EDA). */
    const val CMD_START_STREAM:   Byte = 0x01

    /** Stop the continuous trackers. On-demand measurements are NOT cancelled. */
    const val CMD_STOP_STREAM:    Byte = 0x02

    /** Trigger a one-shot ECG measurement. Result arrives on CHAR_ECG. */
    const val CMD_MEASURE_ECG:    Byte = 0x10

    /** Trigger a single-frequency BIA (body composition) measurement. */
    const val CMD_MEASURE_BIA:    Byte = 0x11

    /** Trigger a multi-frequency BIA measurement (5/10/50/250 kHz). */
    const val CMD_MEASURE_MFBIA:  Byte = 0x12

    /** Trigger an on-demand SpO2 measurement. */
    const val CMD_MEASURE_SPO2:   Byte = 0x13

    /** Begin or end a sweat-loss workout (toggle). */
    const val CMD_TOGGLE_SWEAT:   Byte = 0x14

    /** Cancel any ongoing on-demand measurement. */
    const val CMD_CANCEL_MEASURE: Byte = 0x1F

    /** Force the watch to push a status packet now. */
    const val CMD_REQUEST_BATTERY: Byte = 0x20

    /** Reset the running session counter (resets dropped-packet counter too). */
    const val CMD_RESET_SESSION:   Byte = 0xF0.toByte()

    // -- Packet sizes (bytes) ----------------------------------------------

    const val PPG_PACKET_SIZE      = 20    // ts + 4 ints (G/R/IR/aux)
    const val HR_PACKET_SIZE       = 8     // ts + bpm + ibi
    const val ACC_PACKET_SIZE      = 16    // ts + xyz floats
    const val TEMP_PACKET_SIZE     = 12    // ts + skin + ambient (floats)
    const val EDA_PACKET_SIZE      = 12    // ts + conductance + status_int
    const val STATUS_PACKET_SIZE   = 16
    const val ECG_PACKET_SIZE      = 24    // ts + ecg_mv + min/max + lead_off + seq + green
    const val BIA_PACKET_SIZE      = 48
    const val MFBIA_PACKET_SIZE    = 44
    const val SPO2_PACKET_SIZE     = 16    // ts + status + spo2 + hr
    const val SWEAT_PACKET_SIZE    = 12

    // Standard Android sensors
    const val GYRO_PACKET_SIZE     = 16    // ts + xyz floats (rad/s)
    const val MAG_PACKET_SIZE      = 16    // ts + xyz floats (µT)
    const val BARO_PACKET_SIZE     = 12    // ts + pressure_hpa + altitude_m
    const val LIGHT_PACKET_SIZE    = 8     // ts + lux float
    const val GPS_PACKET_SIZE      = 40    // ts + lat/lon (double) + alt/speed/bearing/acc + satellites + fixed

    // -- Packets: continuous streams ---------------------------------------

    /** Multi-channel PPG sample. ch0=GREEN, ch1=RED, ch2=IR, ch3=reserved/aux. */
    data class PpgPacket(
        val timestampMs: Int,
        val ch0: Int, val ch1: Int, val ch2: Int, val ch3: Int,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(PPG_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs)
                .putInt(ch0).putInt(ch1).putInt(ch2).putInt(ch3)
                .array()

        companion object {
            fun fromBytes(b: ByteArray): PpgPacket {
                require(b.size >= PPG_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return PpgPacket(bb.int, bb.int, bb.int, bb.int, bb.int)
            }
        }
    }

    data class HrPacket(
        val timestampMs: Int,
        val bpm: Short,
        val ibiMs: Short,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(HR_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putShort(bpm).putShort(ibiMs).array()

        companion object {
            fun fromBytes(b: ByteArray): HrPacket {
                require(b.size >= HR_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return HrPacket(bb.int, bb.short, bb.short)
            }
        }
    }

    /**
     * Accelerometer raw sample. SDK delivers integer counts; we forward as
     * Float in m/s² after scaling. Conversion done on the bridge side, so
     * over the wire this is m/s² in SI units.
     */
    data class AccPacket(
        val timestampMs: Int,
        val x: Float, val y: Float, val z: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(ACC_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(x).putFloat(y).putFloat(z).array()

        companion object {
            fun fromBytes(b: ByteArray): AccPacket {
                require(b.size >= ACC_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return AccPacket(bb.int, bb.float, bb.float, bb.float)
            }
        }
    }

    /** Skin (object) and ambient temperatures, both in °C. */
    data class TempPacket(
        val timestampMs: Int,
        val skinC: Float,
        val ambientC: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(TEMP_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(skinC).putFloat(ambientC).array()

        companion object {
            fun fromBytes(b: ByteArray): TempPacket {
                require(b.size >= TEMP_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return TempPacket(bb.int, bb.float, bb.float)
            }
        }
    }

    /**
     * Electrodermal activity. Skin conductance in microsiemens (µS).
     * Status: 0 = ok, non-zero = device-specific error/unstable.
     */
    data class EdaPacket(
        val timestampMs: Int,
        val skinConductanceUs: Float,
        val status: Int,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(EDA_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(skinConductanceUs).putInt(status).array()

        companion object {
            fun fromBytes(b: ByteArray): EdaPacket {
                require(b.size >= EDA_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return EdaPacket(bb.int, bb.float, bb.int)
            }
        }
    }

    // -- Packets: on-demand results ----------------------------------------

    /**
     * One ECG sample point. ECG_ON_DEMAND streams ~500 Hz samples during
     * the ~30 s measurement, plus auxiliary fields.
     */
    data class EcgPacket(
        val timestampMs: Int,
        val ecgMv: Float,            // raw ECG in mV
        val minThresholdMv: Float,
        val maxThresholdMv: Float,
        val leadOff: Int,            // 0 = lead on, non-zero = off
        val sequence: Int,
        val ppgGreenSync: Int,       // co-acquired PPG GREEN for alignment
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(ECG_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs)
                .putFloat(ecgMv)
                .putFloat(minThresholdMv).putFloat(maxThresholdMv)
                .putInt(leadOff).putInt(sequence).putInt(ppgGreenSync)
                .array()

        companion object {
            fun fromBytes(b: ByteArray): EcgPacket {
                require(b.size >= ECG_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return EcgPacket(bb.int, bb.float, bb.float, bb.float,
                    bb.int, bb.int, bb.int)
            }
        }
    }

    /** Single-frequency body impedance (50 kHz fixed) + computed body composition. */
    data class BiaPacket(
        val timestampMs: Int,
        val status: Int,                  // 0 = ok
        val bodyFatRatio: Float,          // %
        val bodyFatMassKg: Float,
        val totalBodyWaterKg: Float,
        val skeletalMuscleRatio: Float,   // %
        val skeletalMuscleMassKg: Float,
        val basalMetabolicRateKcal: Float,
        val fatFreeRatio: Float,          // %
        val fatFreeMassKg: Float,
        val impedanceMagnitudeOhm: Float,
        val impedancePhaseDeg: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(BIA_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putInt(status)
                .putFloat(bodyFatRatio).putFloat(bodyFatMassKg)
                .putFloat(totalBodyWaterKg)
                .putFloat(skeletalMuscleRatio).putFloat(skeletalMuscleMassKg)
                .putFloat(basalMetabolicRateKcal)
                .putFloat(fatFreeRatio).putFloat(fatFreeMassKg)
                .putFloat(impedanceMagnitudeOhm).putFloat(impedancePhaseDeg)
                .array()

        companion object {
            fun fromBytes(b: ByteArray): BiaPacket {
                require(b.size >= BIA_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return BiaPacket(
                    bb.int, bb.int,
                    bb.float, bb.float, bb.float,
                    bb.float, bb.float,
                    bb.float,
                    bb.float, bb.float,
                    bb.float, bb.float,
                )
            }
        }
    }

    /** Multi-frequency BIA (5/10/50/250 kHz magnitude+phase). */
    data class MfBiaPacket(
        val timestampMs: Int,
        val status: Int,
        val mag5kOhm: Float,   val phase5kDeg: Float,
        val mag10kOhm: Float,  val phase10kDeg: Float,
        val mag50kOhm: Float,  val phase50kDeg: Float,
        val mag250kOhm: Float, val phase250kDeg: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(MFBIA_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putInt(status)
                .putFloat(mag5kOhm).putFloat(phase5kDeg)
                .putFloat(mag10kOhm).putFloat(phase10kDeg)
                .putFloat(mag50kOhm).putFloat(phase50kDeg)
                .putFloat(mag250kOhm).putFloat(phase250kDeg)
                .array()

        companion object {
            fun fromBytes(b: ByteArray): MfBiaPacket {
                require(b.size >= MFBIA_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return MfBiaPacket(
                    bb.int, bb.int,
                    bb.float, bb.float, bb.float, bb.float,
                    bb.float, bb.float, bb.float, bb.float,
                )
            }
        }
    }

    /** SpO2 result — blood oxygen %, heart rate, accuracy. */
    data class SpO2Packet(
        val timestampMs: Int,
        val status: Int,        // 0 = measuring, 2 = complete
        val spo2Percent: Int,
        val heartRateBpm: Int,
        val accuracyFlag: Int,  // 0 = low, 1 = high
        val pad: Int = 0,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(SPO2_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putInt(status)
                .putInt(spo2Percent).putInt(heartRateBpm)
                .array()

        companion object {
            fun fromBytes(b: ByteArray): SpO2Packet {
                require(b.size >= SPO2_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                val ts = bb.int
                val st = bb.int
                val sp = bb.int
                val hr = bb.int
                return SpO2Packet(ts, st, sp, hr, accuracyFlag = 0)
            }
        }
    }

    /** Sweat loss after a workout. Lost water mass in grams. */
    data class SweatPacket(
        val timestampMs: Int,
        val status: Int,
        val sweatLossGrams: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(SWEAT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putInt(status).putFloat(sweatLossGrams).array()

        companion object {
            fun fromBytes(b: ByteArray): SweatPacket {
                require(b.size >= SWEAT_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return SweatPacket(bb.int, bb.int, bb.float)
            }
        }
    }

    // -- Packets: standard Android sensors ---------------------------------

    /** Gyroscope angular rate, rad/s on each axis. */
    data class GyroPacket(
        val timestampMs: Int,
        val x: Float, val y: Float, val z: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(GYRO_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(x).putFloat(y).putFloat(z).array()

        companion object {
            fun fromBytes(b: ByteArray): GyroPacket {
                require(b.size >= GYRO_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return GyroPacket(bb.int, bb.float, bb.float, bb.float)
            }
        }
    }

    /** Geomagnetic field strength, µT on each axis. */
    data class MagnetometerPacket(
        val timestampMs: Int,
        val x: Float, val y: Float, val z: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(MAG_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(x).putFloat(y).putFloat(z).array()

        companion object {
            fun fromBytes(b: ByteArray): MagnetometerPacket {
                require(b.size >= MAG_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return MagnetometerPacket(bb.int, bb.float, bb.float, bb.float)
            }
        }
    }

    /**
     * Barometric pressure (hPa) plus altitude derived from standard
     * atmosphere (m). Altitude is approximate — for higher accuracy
     * the consumer should calibrate against a known reference pressure.
     */
    data class BarometerPacket(
        val timestampMs: Int,
        val pressureHpa: Float,
        val altitudeM: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(BARO_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(pressureHpa).putFloat(altitudeM).array()

        companion object {
            fun fromBytes(b: ByteArray): BarometerPacket {
                require(b.size >= BARO_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return BarometerPacket(bb.int, bb.float, bb.float)
            }
        }
    }

    /** Ambient light level in lux. */
    data class LightPacket(
        val timestampMs: Int,
        val lux: Float,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(LIGHT_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(timestampMs).putFloat(lux).array()

        companion object {
            fun fromBytes(b: ByteArray): LightPacket {
                require(b.size >= LIGHT_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return LightPacket(bb.int, bb.float)
            }
        }
    }

    /**
     * GNSS / GPS fix. Coordinates in degrees as doubles for cm-level precision.
     * Speed in m/s (multiply by 3.6 for km/h), bearing in degrees clockwise from
     * magnetic north (0–360), accuracy in metres (1σ horizontal radius).
     */
    data class GpsPacket(
        val timestampMs: Int,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeM: Float,
        val speedMps: Float,
        val bearingDeg: Float,
        val accuracyM: Float,
        val satellitesUsed: Int,
        val fixed: Boolean,
    ) {
        fun toBytes(): ByteArray {
            val bb = ByteBuffer.allocate(GPS_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(timestampMs)
            bb.putDouble(latitudeDeg)
            bb.putDouble(longitudeDeg)
            bb.putFloat(altitudeM)
            bb.putFloat(speedMps)
            bb.putFloat(bearingDeg)
            bb.putFloat(accuracyM)
            bb.putShort(satellitesUsed.coerceAtMost(Short.MAX_VALUE.toInt()).toShort())
            bb.put(if (fixed) 1.toByte() else 0.toByte())
            bb.put(0.toByte()) // pad
            return bb.array()
        }

        companion object {
            fun fromBytes(b: ByteArray): GpsPacket {
                require(b.size >= GPS_PACKET_SIZE)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                return GpsPacket(
                    timestampMs = bb.int,
                    latitudeDeg = bb.double,
                    longitudeDeg = bb.double,
                    altitudeM = bb.float,
                    speedMps = bb.float,
                    bearingDeg = bb.float,
                    accuracyM = bb.float,
                    satellitesUsed = bb.short.toInt() and 0xFFFF,
                    fixed = bb.get() == 1.toByte(),
                )
            }
        }
    }

    // -- Status -------------------------------------------------------------

    /**
     * Watch-side runtime status. Sent every couple seconds and on demand.
     */
    data class StatusPacket(
        val sessionId: Int,
        val droppedPackets: Short,
        val batteryPercent: Byte,
        val streaming: Boolean,
        val sampleRateHz: Byte,
        val activeMeasurement: Byte,    // 0=none, 1=ECG, 2=BIA, 3=MFBIA, 4=SpO2, 5=Sweat
        val pad: Byte = 0,
        val pad2: Int = 0,
    ) {
        fun toBytes(): ByteArray =
            ByteBuffer.allocate(STATUS_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sessionId)
                .putShort(droppedPackets)
                .put(batteryPercent)
                .put(if (streaming) 1.toByte() else 0.toByte())
                .put(sampleRateHz)
                .put(activeMeasurement)
                .putShort(0)        // pad
                .putInt(0)          // pad2
                .array()

        companion object {
            fun fromBytes(b: ByteArray): StatusPacket {
                require(b.size >= 10)
                val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                val sid = bb.int
                val drop = bb.short
                val batt = bb.get()
                val streaming = bb.get() == 1.toByte()
                val rate = bb.get()
                val active = if (bb.remaining() > 0) bb.get() else 0
                return StatusPacket(sid, drop, batt, streaming, rate, active)
            }
        }
    }

    // Active measurement constants (used in StatusPacket)
    const val MEASURE_NONE: Byte = 0
    const val MEASURE_ECG:  Byte = 1
    const val MEASURE_BIA:  Byte = 2
    const val MEASURE_MFBIA: Byte = 3
    const val MEASURE_SPO2: Byte = 4
    const val MEASURE_SWEAT: Byte = 5
}
