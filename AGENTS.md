# Shoulder Check (盲区回头) v0.2.0 — agent notes

Blind-spot training game (design package r5, gate B 2026-11-19): 8 rounds a game, 4 levels × 4 situations (LC lane
change, PO pull out, MG merge, DR door). Full Space Stage (driver's cabin + street) + ONE system Planar window between
games (TabBar 练习 · 成绩 · 设置). A round on screen has no 2D panel: its HUD is 3D (scene/Hud3D.kt).

## Domain (pure Kotlin, `domain/`, JVM tests in `app/src/test/.../game` and `app/src/test/...`)

- Frame: car frame of the driver's eye at the start of the round; −Z ahead, +X right, road y = −1.20, m / s / degrees,
  yaw + right. Left-hand traffic rebuilds every x × m (never a negative scale).
- `Situation` (LANE_LEFT/RIGHT, PULL_OUT, MERGE, DOOR) + `RoadLayout`; `Cabin`, `MirrorModel`, `BlindSpot`,
  `Visibility` (v0.1 geometry kept).
- `Game.kt`: `Level`/`Levels` (L1–L4, `daily()` = level-2 difficulty, all four situations), `RoundKind` CLEAR / HAZARD /
  TRAP, `Round` (actors = scripted f(t), t = 0 when the card appears, button lights at `GO_LIT` = 1 s, timeout at
  `window`), `Conflicts` (lane move 2 s ease + rear-car rule `closing × 2 s + 5 m`, door sweep 35° + 1 s watch, margin
  0.3 m), `free` table (0.1 s), `firstGap()`, `RoundGenerator` (seeded, honest by `RoundCheck`), `GamePlan` (8 rounds:
  traps per level, 1–2 clear, first-ever game starts with a clear LC_L; no door traps — see deviations).
- `Checks.kt`: `CheckJudge` (mirror: eye dwell 120 ms on the glass +50 % / head yaw in the mirror band 20° fwd 8° back,
  250 ms; shoulder: yaw ≥ T 150 ms; fresh 4 s / 3 s; sawHazard; 10 Hz trace), `Thresholds` (head 65/80, eye 80/95;
  floor mirror + 10°; honest near-miss −5°, 5 easy +5°), `lookingForward` ±35°.
- `Scoring.kt`: `Scoring.judge` → `RoundResult` (SAFE 100 / WAITED 120 + reaction max(0, 50 − 25 Δt), ★★ (+★ if Δt ≤ 1.5 s),
  LUCKY 30 ★, MISS / TIMEOUT 0; combo ×1.5 at 3, ×2 at 5), `Session` (8 results, `summary`), unlock at ≥ 2 stars.
- `Progress.kt`: settings, thresholds, best per level, unlock, today's run, last 50 games ⇄ `ProgressCodec` (v0.1 data
  is not carried over).
- `Targets.kt`: `Target` (btn:go, door:handle, pause:chip, ask:on, ask:off), top-level `targetOfName` (resolver),
  `HudLayout` (positions, hotspots, `gap` / `visualAngle`), `DoorTargets` (door click box, controller-ray fallback).
- `DoorModel`: time-based `angleAt(sinceTap)` 0 → 35° in 1 s; close call stops at 12°.

## App

- `app/GameController.kt` (`AppController`): all behaviour — states S00 … S54 (`Screen`), games / rounds / replays,
  taps (`Ev.Tap`), keys (A = 走 / 继续, Y = pause, B / BACK → `Ev.Back`), audio, persistence, what the scene draws
  (`displayRound`, `actorBoxes`, `egoOffset`, `scrollSpeed`, `doorAngle`, `tappable`).
- `ui/window/`: `WindowCtl` (open / close / recall the system window; no head-level gate), `MainWindow` (native TabBar,
  BackKeys on tabs), `GameWindow` (pages).
- `scene/CarScene.kt`: cabin, street (36 m period scroll), Tripo models (5 road users + roadside tree), mirrors,
  controller-ray fallback; `scene/Hud3D.kt` + `TextTex.kt`: the 3D HUD (text drawn on bitmaps).
- `platform/BackKeys.kt` + `SpatialApplication`: D60 BACK / B at the earliest layer; A / X / Y / ENTER eaten.

## Build, install, run (kj-alienware)

```bash
export JAVA_HOME="C:/Users/Admin/.pico/primer-cli/jdk/jdk-21.0.12.1+1" JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
./gradlew --no-daemon testDebugUnitTest assembleDebug
ADB=/c/Users/Admin/Android/Sdk/platform-tools/adb.exe
$ADB -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5556 shell am start -n io.embodit.shouldercheck/.platform.LaunchActivity
$ADB -s emulator-5556 logcat -s ShoulderCheck:V      # STATE / ROUND / CHECK / JUDGE / TAP / WINDOW / PERF
DEV=emulator-5556 AVD=PICO_6_1_kj-alienware_2 bash tools/harness/emu.sh fast on   # 60 fps for ~10 s
DEV=emulator-5556 AVD=PICO_6_1_kj-alienware_2 bash tools/harness/emu.sh shot NAME # full-frame console screenshot
```
`pico-cli capture screenshot` on the idle emulator often catches a half-drawn frame; use `emu.sh shot` after `fast on`.
`emu.sh click X Y` takes framebuffer pixels = screenshot pixels × 0.84375 (2160 / 2560).
Strings: edit `tools/strings.tsv`, run `python -X utf8 tools/gen_strings.py`, then
`python -X utf8 tools/subset_cjk_font.py --source <NotoSansSC .ttf>` (a full copy: `../feel-number/app/src/main/res/font/noto_sans_sc.ttf`).
Sounds: `tools/make_sounds.py` (copies CC0 recordings from other factory apps); loudness: `tools/render_loudness.py`.

## Debug broadcast (debug builds)

```bash
adb shell "am broadcast -a io.embodit.shouldercheck.DEBUG -p io.embodit.shouldercheck --es cmd 'goto S11 scene=LC side=L'"
```
Every hook changes input (head / gaze overrides, keys) or sends the same event a button / 3D tap sends; 3D taps replay a
hit on the REAL entity through the board-input listener.

| command | does |
|---|---|
| `goto <S>` (+ `scene=LC\|PO\|MG\|DR side=L\|R hazard=none\|car\|suv\|ebike\|bike\|moto z=<rear m> v=<m/s> trap=0\|1 level=n`) | reach a state by its normal entry; S13 / S14 / S15 / S16 = a scripted round judged by the real judge; S20 / S21 = a scripted 8-round game; S00 = relaunch |
| `level=n` · `daily` · `seed=<n> [level=n]` | start a game |
| `scene=…` / `hazard=…` | one hand-made round in the current game |
| `head=<yaw> [pitch=]` · `head=off` · `gaze=<yaw>,<pitch>` · `gaze=off` · `eye=off\|on` | input overrides |
| `tap btn:go\|door:handle\|pause:chip\|ask:on\|ask:off [src=controller\|hand-ray\|eye-hand]` | real-entity 3D tap |
| `tap <buttonName>` · `probes` | press a window button (same callback) / list names |
| `exec` / `key a\|y\|b` | A key / Y (pause) / BACK |
| `result outcome=safe\|waited\|lucky\|miss\|timeout [combo=n]` · `game outcomes=…` | scripted results through the real judge |
| `seed-scores` · `lost=1\|0` · `comfort=1\|0` · `lang zh\|en` · `traffic L\|R` · `window open\|close\|recall` · `blocked=<reason>\|off` · `state` · `sceneinfo instances` | misc |

**Preconditions**: `goto S15` needs nothing (it builds a trap round and taps 「走」 into it); `goto S21` plays a scripted
game first (misses included); `goto S50` starts a round if none; `goto S54` only shows the card (the real path: first
game, after round 1, eye permission not asked).

## QA screenshot list

S00 (launch 0.5 s) · S10 cold start (card + first hint) · S11 (lights: `head=-50` → 镜; `head=-66` → 回头) · S12 (0.5 s after
go) · S13 `result outcome=safe combo=3` · S14 `result outcome=lucky` · S15 `goto S15` (1 s in) · S16 `result outcome=timeout` ·
S20 `goto S20` · S21 `goto S21` · S30 / S32 (`seed-scores`) / S40 / S41 / S42 / S43 zh + en · S50 + restart confirm ·
S53 `blocked=not-in-full-space` · S54 `goto S54` · DR round door open · L4 dusk (`level=4` after `seed-scores`).
