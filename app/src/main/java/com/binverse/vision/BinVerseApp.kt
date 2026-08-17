package com.binverse.vision

import android.app.Application
import com.binverse.vision.detection.DetectionController
import com.binverse.vision.detection.DetectionHistory
import com.binverse.vision.network.Esp32Service
import com.binverse.vision.network.GroqVisionService
import com.binverse.vision.settings.SettingsManager

class BinVerseApp : Application() {

    lateinit var settingsManager: SettingsManager
        private set
    lateinit var detectionHistory: DetectionHistory
        private set
    lateinit var detectionController: DetectionController
        private set

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        detectionHistory = DetectionHistory()
        detectionController = DetectionController(
            groqService = GroqVisionService(),
            esp32Service = Esp32Service(),
            history = detectionHistory
        )
    }
}
