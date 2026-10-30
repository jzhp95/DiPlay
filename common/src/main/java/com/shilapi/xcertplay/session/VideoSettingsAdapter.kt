package com.shilapi.xcertplay.session

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.carPlayVideoOutput

/** Read persisted output switches once at the UI/config boundary, preserving preference keys. */
internal object VideoSettingsAdapter {
    fun loadOutput(context: Context) = carPlayVideoOutput(
        AirPlayPersistence.loadSoftwareDirectVideoOutput(context),
        AirPlayPersistence.loadSmoothVideo(context),
        AirPlayPersistence.loadDirectVideoOutput(context),
        AirPlayPersistence.loadSoftwareYuvColor(context).takeIf { AirPlayPersistence.loadSoftwareYuvEnabled(context) },
        hardwareYuv = AirPlayPersistence.loadHardwareYuvEnabled(context),
    )
}
