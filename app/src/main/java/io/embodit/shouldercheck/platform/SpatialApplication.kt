package io.embodit.shouldercheck.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.shouldercheck.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
