package io.embodit.shouldercheck.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-package `palette`). This is the only file with colour literals. The 16
 * ColorScheme roles stay PicoTheme defaults (palette.rule); the `ui` colours below are state colours only (check
 * contrast >= 4.5:1 on glass); `cabin` / `street` feed the procedural scene materials.
 */
object ScColors {
    // ---- ui state colours
    val Done = Color(0xFF2E8B57) // design-style: fixed-figma-color ui.done
    val Missing = Color(0xFF8A9099) // design-style: fixed-figma-color ui.missing
    val Stale = Color(0xFFE0A030) // design-style: fixed-figma-color ui.stale
    val Miss = Color(0xFFD9480F) // design-style: fixed-figma-color ui.miss (near miss)
    val Lucky = Color(0xFFE0A030) // design-style: fixed-figma-color ui.lucky
    val Gaze = Color(0xFF7FD4FF) // design-style: fixed-figma-color ui.gaze
    val Guide = Color(0xFFFFFFFF) // design-style: fixed-figma-color ui.guide

    // ---- cabin (palette A, dark grey)
    val Dash = Color(0xFF2B2E33) // design-style: fixed-figma-color cabin.dash
    val Trim = Color(0xFF3A3E44) // design-style: fixed-figma-color cabin.trim
    val Seat = Color(0xFF4A4F57) // design-style: fixed-figma-color cabin.seat
    val Headliner = Color(0xFFC9C6BF) // design-style: fixed-figma-color cabin.headliner
    val GlassTint = Color(0x1F0E1A22) // design-style: fixed-figma-color cabin.glassTint (12 %)
    val MirrorFrame = Color(0xFF1E2125) // design-style: fixed-figma-color cabin.mirrorFrame

    // ---- street
    val Road = Color(0xFF5B5F63) // design-style: fixed-figma-color street.road
    val LaneLine = Color(0xFFF2F2EE) // design-style: fixed-figma-color street.laneLine
    val BikeLane = Color(0xFF4F8F5A) // design-style: fixed-figma-color street.bikeLane
    val Kerb = Color(0xFFB9B6AE) // design-style: fixed-figma-color street.kerb
    val Sidewalk = Color(0xFFCFCAC0) // design-style: fixed-figma-color street.sidewalk
    val TreeLeaf = Color(0xFF4C7A3C) // design-style: fixed-figma-color street.tree
    val TreeTrunk = Color(0xFF6B4E32) // design-style: fixed-figma-color street.tree
    val BuildingNear = Color(0xFF8FA3B5) // design-style: fixed-figma-color street.buildings
    val BuildingFar = Color(0xFFC9D3DC) // design-style: fixed-figma-color street.buildings
    val SkyLow = Color(0xFFBFD9EE) // design-style: fixed-figma-color street.sky
    val SkyHigh = Color(0xFFEAF3FA) // design-style: fixed-figma-color street.sky

    // ---- scene / panel extras
    val Bus = Color(0xFF2F7D5B) // design-style: fixed-figma-color bus body (procedural, lesson 5 context)
    val IconDisc = Color(0xFF1C1E21) // design-style: fixed-figma-color dark disc behind status icons (amber 7.9:1, green 3.9:1, red 4.0:1, grey 5.2:1; a white disc gave amber 2.2:1)
    val Ink = Color(0xFF000000) // design-style: fixed-figma-color text on the interaction-blue primary button (5.2:1; #14181C was 4.46:1)
}
