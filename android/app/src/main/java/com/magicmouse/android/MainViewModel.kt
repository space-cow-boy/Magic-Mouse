package com.magicmouse.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicmouse.android.controller.MouseController
import com.magicmouse.android.network.Protocol
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel that owns the MouseController lifecycle.
 *
 * Using AndroidViewModel (needs Application for SensorManager context).
 * The viewModelScope lives as long as the ViewModel — which survives
 * configuration changes (screen rotation etc), preventing sensor restart.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = MouseController(application)

    // Expose controller state flows directly (the ViewModel is the bridge)
    val connectionState = controller.connectionState
    val sensorSnapshot  = controller.sensorSnapshot

    // Settings state (mirrored from MotionProcessor)
    var sensitivity: Float
        get()  = controller.motionProcessor.baseSensitivity
        set(v) { controller.motionProcessor.baseSensitivity = v }

    var deadZone: Float
        get()  = controller.motionProcessor.deadZoneDegrees
        set(v) { controller.motionProcessor.deadZoneDegrees = v }

    init {
        controller.start(viewModelScope)
    }

    fun connect(host: String, port: Int = Protocol.DEFAULT_PORT) =
        controller.connect(host, port)

    fun disconnect() = controller.disconnect()

    fun recenter() = controller.recenter()

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }
}
