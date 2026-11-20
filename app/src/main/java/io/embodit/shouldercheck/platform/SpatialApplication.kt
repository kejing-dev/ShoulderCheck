package io.embodit.shouldercheck.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.Ev
import io.embodit.shouldercheck.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        guardBack()
        launch(::mainApp)
    }

    /**
     * D60 补 (gate back-key; 2026-11-18 露珠瓶 / 2026-11-19 八音盒): controller B = system BACK. With the main window
     * closed (a round on screen) or after a tap on the window / its TabBar, BACK used to send the whole Stage to the
     * background. BACK / B is caught at the earliest layer of every window ([BackKeys]) and goes to one handler
     * ([Ev.Back]: round → pause card, window closed → open it again, how-to → settings); the OnBackPressedCallback
     * stays as the last fallback. It never quits.
     */
    private fun guardBack() {
        BackKeys.handler = { where -> AppController.onEvent(Ev.Back(where)) }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    val ca = a as? ComponentActivity
                    if (ca == null) {
                        ScLog.w("BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity")
                        return
                    }
                    ca.onBackPressedDispatcher.addCallback(
                        ca,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() = BackKeys.handler("dispatcher:${a.javaClass.simpleName}")
                        },
                    )
                    ScLog.i("BACK guard on ${a.javaClass.simpleName}")
                }

                override fun onActivityPostCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("created")
                }

                override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")

                override fun onActivityResumed(a: Activity) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("resumed")
                }

                override fun onActivityPostResumed(a: Activity) = BackKeys.sweep("post-resumed")

                override fun onActivityPaused(a: Activity) = Unit

                override fun onActivityStopped(a: Activity) = Unit

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

                override fun onActivityDestroyed(a: Activity) = Unit
            },
        )
    }
}
