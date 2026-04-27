@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tb.sw.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import tb.sw.SensorStreamService
import tb.sw.WatchStats

/**
 * Eight-page horizontal pager dashboard surfacing every Samsung Health
 * Sensor SDK data type:
 *
 *   0 — Status (link, session, battery)
 *   1 — PPG raw (GREEN/RED/IR)
 *   2 — Heart rate + IBI
 *   3 — Motion (ACC, skin temperature)
 *   4 — EDA + SpO2
 *   5 — ECG + Sweat loss
 *   6 — BIA + MF-BIA
 *   7 — BLE bandwidth + counters
 *
 * Auto-scaling layout works on Galaxy Watch 8 40 mm (432 dp), 44 mm (480 dp),
 * and any other round Wear OS screen.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startStreamService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        setContent { Dashboard() }
        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        needed += Manifest.permission.BODY_SENSORS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startStreamService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startStreamService() {
        val intent = Intent(this, SensorStreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else startService(intent)
    }
}

// -- Scaling -----------------------------------------------------------------

private data class Scale(val factor: Float) {
    val pagePadding: Dp = (28f * factor).dp
    val cardSpacing: Dp = (3f * factor).dp
    val sectionSpacing: Dp = (8f * factor).dp

    val titleSize: TextUnit = (15f * factor).sp
    val bodySize: TextUnit = (12f * factor).sp
    val labelSize: TextUnit = (10f * factor).sp
    val mutedSize: TextUnit = (9f * factor).sp
    val statusSize: TextUnit = (11f * factor).sp
    val bigNumberSize: TextUnit = (38f * factor).sp
    val mediumNumberSize: TextUnit = (24f * factor).sp
    val smallNumberSize: TextUnit = (16f * factor).sp
    val unitSize: TextUnit = (11f * factor).sp
    val channelLabelSize: TextUnit = (10f * factor).sp
    val channelValueSize: TextUnit = (11f * factor).sp

    val dotActive: Dp = (7f * factor).dp
    val dotInactive: Dp = (4f * factor).dp
    val dotSpacing: Dp = (5f * factor).dp
    val indicatorBottomInset: Dp = (20f * factor).dp
}

private val LocalScale = staticCompositionLocalOf { Scale(factor = 1f) }

@Composable
private fun Dashboard() {
    val stats by WatchStats.state.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val edgeDp = minOf(maxWidth, maxHeight)
        val scale = Scale(factor = (edgeDp.value / 432f).coerceIn(0.7f, 1.4f))

        CompositionLocalProvider(LocalScale provides scale) {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val pagerState = rememberPagerState(pageCount = { 8 })
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> StatusPage(stats)
                            1 -> PpgPage(stats)
                            2 -> HeartPage(stats)
                            3 -> MotionPage(stats)
                            4 -> EdaSpO2Page(stats)
                            5 -> EcgSweatPage(stats)
                            6 -> BiaPage(stats)
                            7 -> BlePage(stats)
                        }
                    }
                    PageIndicator(pagerState.currentPage, total = 8)
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(current: Int, total: Int) {
    val s = LocalScale.current
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = s.indicatorBottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.dotSpacing)) {
            repeat(total) { i ->
                val active = current == i
                Box(
                    modifier = Modifier
                        .size(if (active) s.dotActive else s.dotInactive)
                        .clip(CircleShape)
                        .background(if (active) Color.White else Color(0xFF555555))
                )
            }
        }
    }
}

// -- Page 0: Status ----------------------------------------------------------

@Composable
private fun StatusPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("TbWatchSensors", color = Color.White,
            fontSize = sc.titleSize, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(sc.cardSpacing))

        val sdkColor = when (s.samsungSdkState) {
            "Connected" -> Color(0xFF66BB6A)
            "Connecting…" -> Color(0xFFFFA726)
            else -> Color(0xFFEF5350)
        }
        Text(s.samsungSdkState, color = sdkColor,
            fontSize = sc.statusSize, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(sc.sectionSpacing))

        StatRow("ADV", if (s.gattAdvertising) "ON" else "off",
            if (s.gattAdvertising) Color(0xFF66BB6A) else Color.Gray)
        StatRow("CONN", "${s.gattConnections}",
            if (s.gattConnections > 0) Color(0xFF66BB6A) else Color.Gray)
        StatRow("SUBS", "${s.gattSubscribers}",
            if (s.gattSubscribers > 0) Color(0xFF66BB6A) else Color.Gray)
        StatRow("STREAM", if (s.streaming) "ON" else "off",
            if (s.streaming) Color(0xFF66BB6A) else Color.Gray)
        StatRow("MEASURE", s.activeMeasurement,
            if (s.activeMeasurement != "—") Color(0xFFFFA726) else Color.Gray)

        Spacer(Modifier.height(sc.cardSpacing))
        Text("S${s.sessionId}  ${s.uptimeSec}s  ${s.batteryPct}%",
            color = Color.Gray, fontSize = sc.mutedSize)
    }
}

// -- Page 1: PPG -------------------------------------------------------------

@Composable
private fun PpgPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("PPG", color = Color.White,
            fontSize = sc.titleSize, fontWeight = FontWeight.SemiBold)
        Text("${s.ppgRateHz.format1()} Hz", color = Color.Gray, fontSize = sc.mutedSize)

        Spacer(Modifier.height(sc.sectionSpacing))

        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            ChannelChip("GREEN", s.ppgGreen, Color(0xFF66BB6A), sc.smallNumberSize)
            ChannelChip("RED",   s.ppgRed,   Color(0xFFEF5350), sc.smallNumberSize)
            ChannelChip("IR",    s.ppgIr,    Color(0xFF42A5F5), sc.smallNumberSize)
        }
    }
}

// -- Page 2: Heart rate ------------------------------------------------------

@Composable
private fun HeartPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("HEART", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.bpm}", color = Color.White,
                fontSize = sc.bigNumberSize, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(sc.cardSpacing))
            Text("bpm", color = Color.Gray, fontSize = sc.unitSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        Text("IBI ${s.ibiMs}ms · ${s.hrRateHz.format1()} Hz",
            color = Color.Gray, fontSize = sc.mutedSize)
    }
}

// -- Page 3: Motion + Temperature -------------------------------------------

@Composable
private fun MotionPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("MOTION", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.accMagnitude.format2(), color = Color.White,
                fontSize = sc.mediumNumberSize, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text(" m/s²", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        Text("${s.accRateHz.format1()} Hz", color = Color.Gray, fontSize = sc.mutedSize)

        Spacer(Modifier.height(sc.sectionSpacing))

        Text("SKIN TEMP", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.skinTempC > 0f) s.skinTempC.format1() else "—",
                color = Color.White,
                fontSize = sc.mediumNumberSize, fontWeight = FontWeight.Bold)
            Text(" °C", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        if (s.ambientTempC > 0f) {
            Text("Ambient ${s.ambientTempC.format1()}°C",
                color = Color.Gray, fontSize = sc.mutedSize)
        }
    }
}

// -- Page 4: EDA + SpO2 -----------------------------------------------------

@Composable
private fun EdaSpO2Page(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("EDA", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(s.edaSkinConductanceUs.format2(),
                color = Color.White,
                fontSize = sc.smallNumberSize, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text(" µS", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        Text("status ${s.edaStatus} · ${s.edaRateHz.format1()} Hz",
            color = Color.Gray, fontSize = sc.mutedSize)

        Spacer(Modifier.height(sc.sectionSpacing))

        Text("SpO2", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.spo2Percent > 0) "${s.spo2Percent}" else "—",
                color = Color.White,
                fontSize = sc.mediumNumberSize, fontWeight = FontWeight.Bold)
            Text(" %", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        if (s.spo2HeartRate > 0) {
            Text("HR ${s.spo2HeartRate} · ${if (s.spo2HighAccuracy) "high" else "low"} acc",
                color = Color.Gray, fontSize = sc.mutedSize)
        }
    }
}

// -- Page 5: ECG + Sweat ----------------------------------------------------

@Composable
private fun EcgSweatPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("ECG", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.ecgSampleCount > 0) "%.2f".format(s.ecgLatestMv) else "—",
                color = if (s.ecgLeadOff) Color(0xFFEF5350) else Color.White,
                fontSize = sc.smallNumberSize, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Text(" mV", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
        val ecgStatus = when {
            s.ecgLeadOff -> "lead off"
            s.ecgSampleCount == 0 -> "not measured"
            else -> "${s.ecgSampleCount} samples"
        }
        Text(ecgStatus, color = Color.Gray, fontSize = sc.mutedSize)

        Spacer(Modifier.height(sc.sectionSpacing))

        Text("SWEAT LOSS", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.sweatLossGrams > 0f) s.sweatLossGrams.format1() else "—",
                color = Color.White,
                fontSize = sc.mediumNumberSize, fontWeight = FontWeight.Bold)
            Text(" g", color = Color.Gray, fontSize = sc.mutedSize,
                modifier = Modifier.padding(bottom = sc.cardSpacing))
        }
    }
}

// -- Page 6: BIA -----------------------------------------------------------

@Composable
private fun BiaPage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("BIA", color = Color.White,
            fontSize = sc.titleSize, fontWeight = FontWeight.SemiBold)

        if (s.biaTimestampMs == 0) {
            Spacer(Modifier.height(sc.sectionSpacing))
            Text("not measured", color = Color.Gray, fontSize = sc.mutedSize)
            Spacer(Modifier.height(sc.sectionSpacing))
        } else {
            Spacer(Modifier.height(sc.cardSpacing))
            StatRow("Body fat", "${s.biaBodyFatRatio.format1()}%", Color.White)
            StatRow("Muscle", "${s.biaSkeletalMuscleRatio.format1()}%", Color.White)
            StatRow("Water", "${s.biaTotalBodyWaterKg.format1()}kg", Color.White)
            StatRow("BMR", "${s.biaBmrKcal.toInt()}kcal", Color.White)
            StatRow("Z", "${s.biaImpedanceOhm.toInt()}Ω", Color.White)
        }

        Spacer(Modifier.height(sc.sectionSpacing))

        Text("MF-BIA (Ω)", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        if (s.mfbiaTimestampMs == 0) {
            Text("not measured", color = Color.Gray, fontSize = sc.mutedSize)
        } else {
            StatRow("5kHz", s.mfbiaMag5k.toInt().toString(), Color.White)
            StatRow("50kHz", s.mfbiaMag50k.toInt().toString(), Color.White)
            StatRow("250kHz", s.mfbiaMag250k.toInt().toString(), Color.White)
        }
    }
}

// -- Page 7: BLE bandwidth + counters ---------------------------------------

@Composable
private fun BlePage(s: WatchStats.Snapshot) {
    val sc = LocalScale.current
    PageColumn {
        Text("BLE", color = Color.White,
            fontSize = sc.titleSize, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(sc.cardSpacing))

        StatRow("BPS", formatBytesPerSec(s.bytesPerSec), Color.White)
        StatRow("PKTS", "${s.totalPackets}", Color.White)
        StatRow("DROP", "${s.droppedPackets}",
            if (s.droppedPackets > 0) Color(0xFFEF5350) else Color.Gray)

        Spacer(Modifier.height(sc.sectionSpacing))

        Text("RATES (Hz)", color = Color.Gray,
            fontSize = sc.labelSize, fontWeight = FontWeight.SemiBold)
        StatRow("PPG", s.ppgRateHz.format1(), Color.White)
        StatRow("HR", s.hrRateHz.format1(), Color.White)
        StatRow("ACC", s.accRateHz.format1(), Color.White)
        StatRow("EDA", s.edaRateHz.format1(), Color.White)
    }
}

// -- Building blocks --------------------------------------------------------

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    val sc = LocalScale.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = sc.pagePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color) {
    val sc = LocalScale.current
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = sc.labelSize)
        Text(value, color = color, fontSize = sc.bodySize,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ChannelChip(label: String, value: Int, color: Color, valueSize: TextUnit) {
    val sc = LocalScale.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color,
            fontSize = sc.channelLabelSize, fontWeight = FontWeight.Bold)
        Text("$value", color = Color.White,
            fontSize = valueSize, fontFamily = FontFamily.Monospace)
    }
}

private fun Float.format1(): String = "%.1f".format(this)
private fun Float.format2(): String = "%.2f".format(this)

private fun formatBytesPerSec(bps: Int): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "%.1f kB/s".format(bps / 1024f)
    else -> "%.2f MB/s".format(bps / (1024f * 1024f))
}
