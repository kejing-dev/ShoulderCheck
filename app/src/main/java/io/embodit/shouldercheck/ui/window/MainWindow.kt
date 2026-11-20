package io.embodit.shouldercheck.ui.window

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.shouldercheck.platform.BackKeys
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.backKeys
import io.embodit.shouldercheck.ui.components.glassRefresh

/*
 * 主窗口模板（taste D8「合成一个窗口模板」+ D21 拖动交给系统 + D26 设置里退出应用），拷自产线
 * prompts/snippets/MainWindowTemplate.kt（经 Filter Cam v0.1.6 的受控页签 + BackKeys 版本）。本应用改动：
 *  - 页签受控：current / onSelect 由 AppController 管（调试广播、3D 「菜单」钮、局末都走同一套状态）；
 *  - 设置页由应用自己画（两栏 + 底部固定 [回到面前] [退出应用]，同窗二次确认），模板不再另画设置页；
 *  - 页签文字由调用方按应用内语言取好再传进来（TabBar 是独立系统小窗，不跟应用内语言走）。
 * 规矩：
 *  - 应用全部 2D 内容都在这一个系统窗口里；页面用系统 TabBar 切，不用 SegmentControl / 一排 Button 切页面。
 *  - 拖动 / 关闭 / 最小化是系统窗口控件（窗口下方的小圆点），这里不写任何拖动代码。
 */

/** One page of the main window. [icon]: a Material Symbols vector (taste D23). */
class MainWindowPage(val id: String, val label: String, @DrawableRes val icon: Int, val content: @Composable () -> Unit)

/** The app's single main window: [pages] switched by the native TabBar; [current] / [onSelect] owned by the app. */
@Composable
fun MainWindow(pages: List<MainWindowPage>, current: String, onSelect: (String) -> Unit) {
    require(pages.size in 1..7) { "TabBar holds at most 7 items" }
    val page = pages.firstOrNull { it.id == current } ?: pages.first()
    // glassRefresh: on the emulator a window frame drawn before its backdrop is ready stays half black (GlassRefresh, A4)
    Box(Modifier.fillMaxSize().then(glassRefresh()).backKeys("window").padding(horizontal = 32.dp, vertical = 24.dp)) { page.content() }
    // system TabBar: its own small window outside this one (emulator 6.1.0, seen from the front: centred above the window)
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        pages.forEach { p ->
            item(
                selected = page.id == p.id,
                // D60 补: the TabBar is its own dialog window; a tapped item keeps the focus and takes BACK first
                modifier = TabKeys,
                onClick = {
                    ScLog.i("WINDOW tab ${p.id}")
                    BackKeys.sweep("tab")
                    onSelect(p.id)
                },
                mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                supportContent = { Text(p.label) },
            )
        }
    }
}

/** TabBar items: not focusable, and BACK / controller B go to the one back handler (circuit-lab v0.1.3 fix). */
private val TabKeys: Modifier = Modifier.focusProperties { canFocus = false }.backKeys("tab")
