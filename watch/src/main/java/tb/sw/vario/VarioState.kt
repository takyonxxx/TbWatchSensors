package tb.sw.vario

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state for the variometer page. Service writes vario readings,
 * UI observes via StateFlow. Audio engine is also held here so its lifecycle
 * (Start/Stop button on the page) is independent of any one component.
 */
object VarioState {

    data class Snapshot(
        val verticalSpeedMps: Float = 0f,
        val altitudeM: Float = 0f,
        val pressureHpa: Float = 0f,
        val audioOn: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    val variometer = Variometer()
    val audio = VarioAudioEngine()

    fun onPressureSample(pressureHpa: Float) {
        variometer.onPressureSample(pressureHpa)
        _state.value = _state.value.copy(
            verticalSpeedMps = variometer.verticalSpeedMps,
            altitudeM = variometer.altitudeM,
            pressureHpa = pressureHpa,
        )
        audio.updateVario(variometer.verticalSpeedMps)
    }

    fun setAudioOn(on: Boolean) {
        audio.setEnabled(on)
        _state.value = _state.value.copy(audioOn = on)
    }
}
