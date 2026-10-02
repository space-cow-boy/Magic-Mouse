package com.magicmouse.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicmouse.android.controller.MouseController
import com.magicmouse.android.network.BluetoothDeviceItem

/**
 * ViewModel that owns the MouseController lifecycle.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller = MouseController(application)

    // Expose controller state flows directly
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

    fun getPairedDevices(): List<BluetoothDeviceItem> = controller.getPairedDevices()

    fun connect(deviceAddress: String, deviceName: String = "PC") =
        controller.connect(deviceAddress, deviceName)

    fun disconnect() = controller.disconnect()

    fun recenter() = controller.recenter()

    override fun onCleared() {
        controller.stop()
        super.onCleared()
    }
}
