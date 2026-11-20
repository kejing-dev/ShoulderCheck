package io.embodit.shouldercheck.ui.window

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.shouldercheck.platform.ScLog

/**
 * The main window (profile D21 / D8, prompts/snippets/native-window-move.md): ONE system Planar window holds every 2D
 * page (game over, practice, records, settings, pause). It is open only between rounds: a round on screen has no 2D
 * panel (design `windowModel`, mustPass M7), so a game start closes it and the game end / pause opens it again. Moving,
 * closing and minimising are the system's caption bar under the window; the app has no drag code.
 *
 * Opening is immediate — no head-level gate (profile D10, 2026-11-19: 「一个按钮打开面板」): the 3D 「暂停 / 菜单」 chip
 * above the steering wheel opens it any time. An open right after a close is swallowed (§3.5), so an open asked while
 * the window is closing waits for ON_DESTROY.
 */
object WindowCtl {
    const val ID = "ShoulderMain"
    const val TAG = "main"

    /** The window's lifecycle as it reports it ("never" until it first opens). */
    var state by mutableStateOf("never")
        private set

    /** Set by the Stage composition. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var wanted = false
    private var closing = false
    private var openPending = false
    private var recallPending = false
    private var lastOpenMs = 0L
    private var userClosed = false

    val isOpen: Boolean
        get() = state == "ON_CREATE" || state == "ON_START" || state == "ON_RESUME" || state == "ON_PAUSE"

    /** The user closed it with the system ×: do not force it back; the 3D chip reads 「菜单」. */
    val closedByUser: Boolean
        get() = userClosed

    fun open(why: String) {
        wanted = true
        userClosed = false
        val n = navigator ?: return
        if (state == "ON_STOP") {
            ScLog.i("WINDOW restore why=$why")
            if (runCatching { n.restoreWindowContainer(ID, TAG) }.getOrDefault(false)) return
        }
        if (isOpen && !closing) return
        if (closing) {
            openPending = true
            ScLog.i("WINDOW open queued (closing) why=$why")
            return
        }
        lastOpenMs = android.os.SystemClock.elapsedRealtime()
        ScLog.i("WINDOW open why=$why state=$state")
        runCatching { n.openWindowContainer(ID, TAG, null) }.onFailure { ScLog.e("WINDOW open failed: $it") }
    }

    fun close(why: String) {
        wanted = false
        openPending = false
        val n = navigator ?: return
        if (!isOpen && state != "ON_STOP") return
        closing = true
        ScLog.i("WINDOW close why=$why state=$state")
        runCatching { n.closeWindowContainer(ID, TAG) }.onFailure { ScLog.e("WINDOW close failed: $it") }
    }

    /** 「窗口回到面前」 (D44): an open window cannot be moved by code, so close it and open it again in front of you. */
    fun recall() {
        if (!isOpen) {
            open("recall")
            return
        }
        recallPending = true
        close("recall")
        wanted = true
    }

    fun onLifecycle(event: String) {
        val before = state
        state = event
        ScLog.i("WINDOW lifecycle=$event (was $before)")
        if (event == "ON_DESTROY") {
            val asked = closing
            closing = false
            if (!asked && wanted) {
                // the system × : leave it closed, the chip can bring it back
                userClosed = true
                wanted = false
                ScLog.i("WINDOW closed by the user")
            }
            if (openPending || recallPending) {
                openPending = false
                recallPending = false
                main.postDelayed({ open("after close") }, 300)
            }
        }
    }

    /** Every frame: an open that the system swallowed (no lifecycle within 8 s) is asked once more. */
    fun onFrame(nowMs: Long) {
        if (!wanted || isOpen || closing || userClosed) return
        if (state == "never" || state == "ON_DESTROY") {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastOpenMs > 0 && now - lastOpenMs > 8000) {
                ScLog.i("WINDOW open retry")
                lastOpenMs = now
                runCatching { navigator?.openWindowContainer(ID, TAG, null) }
            }
        }
    }

    /** Quit: no pending opens. */
    fun cancel() {
        wanted = false
        openPending = false
        recallPending = false
        main.removeCallbacksAndMessages(null)
    }
}
