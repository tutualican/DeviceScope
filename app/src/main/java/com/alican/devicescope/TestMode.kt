package com.alican.devicescope

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Phone-side "test mode".
 *
 * While a marathon runs the whole app is replaced by the visual stage: the
 * screen stays on, brightness goes to maximum and the device is kept out of Doze
 * by a foreground service. This object only holds observable state; the window
 * flags live in the Compose overlay so they are always restored.
 *
 * What this cannot do, and never claims to do: put *other* applications into a
 * test mode. A rootless app cannot suspend, clamp or reconfigure other packages.
 * The panel therefore also reports whether the screen stayed on and DeviceScope
 * stayed in the foreground, so a run with a phone call in the middle is visible
 * instead of being silently accepted.
 */
object TestMode {

    var active by mutableStateOf(false)
        private set
    var minutes by mutableStateOf(15)
        private set
    var startedAt by mutableStateOf(0L)
        private set
    var phase by mutableStateOf("Hazırlanıyor")
    var detail by mutableStateOf("")
    var progress by mutableStateOf(0f)

    val elapsedSeconds: Long
        get() = if (!active || startedAt == 0L) 0L
        else ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)

    val remainingSeconds: Long
        get() = (minutes * 60L - elapsedSeconds).coerceAtLeast(0L)

    fun begin(minutes: Int) {
        this.minutes = minutes.coerceAtLeast(15)
        startedAt = System.currentTimeMillis()
        phase = "Hazırlanıyor"
        detail = ""
        progress = 0f
        active = true
    }

    fun end() {
        active = false
        progress = 0f
        phase = ""
        detail = ""
        startedAt = 0L
    }
}
