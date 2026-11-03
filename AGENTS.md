# Shoulder Check (盲区回头) — agent notes

## Domain

Pure Kotlin, `app/src/main/java/io/embodit/shouldercheck/domain/` (no Android imports; JVM tests in
`app/src/test/java/io/embodit/shouldercheck/`). Spec = design package `model` + `content`.

**Frame & units.** Car ("cabin") frame of the current seat: origin = eye E0, −Z = car front, +X right, +Y up, road
y = −1.20 (`Road.ROAD_Y`). Metres, seconds, degrees. **Yaw / azimuth: 0 = car front, + = RIGHT** (`Angles.dir`,
`Angles.yawOf`; a left shoulder check is negative, mirror MIR-L is at −48.5°) — opposite sign to rehearsal-hall's `Yaw`.
Pitch + = up. Traffic side `TrafficSide.RIGHT_HAND` (default, China) / `LEFT_HAND` regenerates every x with ×`m` = ±1
(never a negative scale); `Dir` is always the PHYSICAL left/right after that; `Situation.dir(side)` maps the design
direction (lane-left in LHT = a change to the right).

**Placement (D9).** `Placement.seatFrame(headPose)` → car pose (head position, yaw only). `Placement.placeUpright(head,
distance, dropDeg)` for any head-relative panel. `toWorld(car, p)` / `toCar(car, world)`; `headYawInCar(car, headRot)`
gives the judge's head yaw. `Pose.isValid()` rejects the (0,0,0)+identity first HMD sample.

**Geometry.** `Cabin(seat, side)`: body / window planes, pillars, `doorHandle`, `doorHinge`, `doorNormal` (outward),
`doorSide`, `farHand`, `stalk`, `wheelCentre`, `mirrors` (driver: INNER/LEFT/RIGHT `MirrorSpec`; rear seat: none),
`clearLine(from, to)` (cabin occlusion). `RoadLayout(situation, side)`: `strips` (lanes / parking / bike lane /
sidewalk, physical x), `dashedLines`, `solidLines`, `kerbX`, `egoX`, `hazardX(kind, dir)`. `ActorKind` sizes + GLB role
(`model`). `Box` = bottom-centre + L/W/H + heading (front −Z).

**Mirrors.** `MirrorModel.project(worldPoint, mirror, eye, aimDeg)` → `MirrorHit(u, v, distance)` or null; u 0→1 =
left→right as the viewer sees the glass, **v 0 = bottom** (flip for Canvas). `virtualEye`, `aimDirection`, `sees(box,
…)`, `hits(box, …)` (what to draw), `anyMirrorSees`. Aims: `MirrorAims(left, right)` 0…15°, default 5.
`BlindSpot.isInBlindSpot(box, dir, aims, cabin, headYaw = 0)`, `BlindSpot.length(dir, aim, cabin)` (lesson-1 output;
left ≈ 4.45 m at 5°). `Visibility.visibleParts(box, cabin, headYaw, halfFov = 50)`, `anyInView`, `inBand`,
`reachable(box, cabin, range, aims)`.

**Scenes / traffic.** `Scenario` = situation, side, seat, `cabin`, `road`, `actors` (each `Actor.box(t)` /
`position(t)` / `velocityZ(t)`, scripted f(t); t = 0 is the start of S06, S05 is t ∈ [−2, 0)), `hazard`,
`requiredSteps` (display order), `slot`, `hazardEntryTime`, `hazardPassTime`, `timeLimit`, `noHazardReason` (RANGE →
show lesson-4 `rear_note`). Build: `Scenes.build(situation, side, HazardSpec(kind, side, rearZ, speed))` (debug
`hazard=… z=<rear end, m behind> v=<+ overtaking>`), `Scenes.blindSpotTrial` (lesson 2), `Scenes.mirrorExitTime`,
`TrafficGenerator.lessonTrials(n, seed, side, range, aims)` and `.question(seed, index, level, side, range, aims)`,
`FreeModeSet(seed, side, range)` (`current()`, `record(result)`, `summary()`), `Difficulty`, `Solvability.check`.
Door: `DoorModel(cabin).update(dt, holdingHand, pushM)` → angle (3 cm → 8°, 10 cm → 60° in 0.5 s, execute > 12°),
`DoorModel.pushDistance`, `inReach`, `sweep`, `conflicts`. Lane: `LaneChangeModel.slot / predictConflict`,
`GhostRoute.pose(situation, side, road, t)` (0…3 s).

**Judging.** One `QuestionJudge(scenario, mode, profile, aims)` per question; `mode` (`JudgeMode.EYE`/`HEAD`) locked in
S05. Each frame: `update(JudgeSample(t, headYaw, headPitch, headPos, gaze: GazeRay?, signal, grab: Hand?, push, exec))`
→ `LiveStatus` (steps `StepLive(state PENDING/DONE/STALE/WRONG, judgedByHead, lowConfidence, progress)`, `phase`,
`checkTime`, peaks/thresholds, `saw`, `doorAngle`, `eyeDropout` (≥ 5 s → S17), drained `events`: `StepLit`,
`StepStale`, `TurnReached` (play 嗒), `TurnEnded(Excursion.NearMiss.missing → "再往左转 8°")`, `HazardSeen`, `Executed`,
`SignalAutoCancel`, `Conflict`, `Finished`). gaze = null on samples without eye data. Keep feeding samples after
execute until `phase == DONE` (3 s ghost / door watch), then `result()` → `QuestionResult` (`record(atMs)` →
`QuestionRecord`). Lesson 1: `setAim`, `confirmMirror(dir, aim, t)`. `skip(t)`. `ghost(t)` for the ghost car.
`TurnProfile(TurnRange)` is shared by the session (near-miss relaxing carries over); `TurnRange.SKIPPED`.

**Content & storage.** `Lessons.ALL`, `Scorecard.rows / overall / header / recommendedLesson`, `Stats.median`.
`Progress` (settings, range, aims, blind spots, demoSeen, passed, ≤ 300 records) ⇄ `ProgressCodec.encode/decode`
(JSON string for SharedPreferences, never throws). `ModelCalibration.parseAll(calibration.json)` → per role
`apply(p)`, `rotation`, `scale`, `translation` (TRS of the calibration node; `yawDeg` is the SDK's right-handed Ry).
Small additions by the scene build: `MirrorModel.projectRaw` (no glass-rect test, for road polygons),
`FreeModeSet(startIndex)` (debug `seed= q=`), `QuestionJudge.closeDoor()` (B / 关门), `ModelCalibration.withScale`
(calibration.json `scaleBalanced`), `Layout.kt` (cabin parts of both seats with x × m, door specs, panel placements,
guide targets, D20 sight-cone test, gaze point).

## Build, install, run (kj-home-pc)

```powershell
# PowerShell (Git Bash ./gradlew fails on CRLF); one Gradle build at a time on this machine
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest assembleDebug --no-daemon --offline
```
```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5560 shell am force-stop io.embodit.shouldercheck      # a Stage left open blocks a relaunch
"$ADB" -s emulator-5560 shell am start -n io.embodit.shouldercheck/.platform.LaunchActivity
"$ADB" -s emulator-5560 logcat -s ShoulderCheck:V                          # STATE / EVENT / STEP / JUDGE / DOOR / N3 …
bash tools/harness/emu.sh fast on; bash tools/harness/emu.sh shot NAME     # 60 fps for ~10 s, console screenshot
```
Gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` → 0.
After changing zh strings: `python -X utf8 tools/subset_cjk_font.py --source <NotoSansSC-VF.ttf>` (not in
C:\Windows\Fonts here; copies sit in older session scratchpads `…/scratchpad/font/NotoSansSC-VF.ttf`).
Another factory app left in Full Space on the emulator refuses the launch ("current activity is being kept"):
force-stop it first.

## Structure

```
app/src/main/java/io/embodit/shouldercheck/
  Main.kt                DefaultStage (Full) -> ScTheme -> ShoulderScreen
  domain/                pure Kotlin (see "Domain"), Layout.kt
  app/
    AppState.kt          Base S00..S19 + S01B, P1 pages, HUD / ANG / range / result UI state
    AppController.kt     ALL behaviour: state machine, lesson / set / debug courses, question runtime (judge fed
                         from FrameInput), sounds, persistence, what the scene draws (actors, ghost, door, guides)
    QuestionRun.kt       FrameInput (car frame), Course, QuestionRun, ReplayState, DemoState, Ambient home traffic
    DebugCommands.kt     the debug hooks (main source set, reached only through the debug receiver)
    DebugScripts.kt      scripted samples fed into the REAL judge (result outcome=…, goto S06 done=…, goto S08)
  platform/              ScLog, Store (SharedPreferences ⇄ ProgressCodec), Audio (SoundPool, 1.5 Hz indicator,
                         engine bed, panned near-miss cues), tracking/ (Head, Eye, Hand trackers, TrackerForce),
                         input/ControllerInput, EyePermissionActivity, LaunchActivity, SpatialApplication, DebugHooks
  scene/
    CarScene.kt          the one ECS system (ScFrameSystem): build queue, seat placement, input -> controller,
                         cabin / street / actors / ghost / gaze dot / guides / demo hand / alert / arcs, panels,
                         D20 fade, mirror frames, startup logs at 1 / 3 / 5 s, N3 log every 5 s
    SceneKit.kt          SDK conversions, MeshBuilder (auto winding), unit cube, sphere, ring, disc, road mesh,
                         textures (road per layout, F↑ card, sky, gradient, radial), materials, setFade (removes
                         the opacity component at 1.0)
    PanelPool.kt         pre-built panel pool under the car root (park / stow + disable, settle frames)
    Models.kt            GLB templates + calibration node (calibration.json scaleBalanced), opacity audit
    MirrorFrames.kt      mirror pictures via MirrorModel.projectRaw (road, lines, kerb, buildings, silhouettes,
                         own flank, rear window), MirrorStore (Compose state + N3 counters)
  ui/
    ShoulderScreen.kt    SpatialView + attachments (p1, p1tall, p2, angL, angR, mirI, mirL, mirR), lifecycle, quit
    panels/              P1Pages (all P1 pages), HudPanels (P2 + ANG), MirrorPanel (mirror Canvas + silhouettes)
    components/          Common (Glass, WordButton, Segments, StatusLabel, DebugProbe), Words, Strings, GlassRefresh
app/src/debug/           DebugCommandReceiver -> DebugHooks.handler = DebugCommands::handle
tools/                   subset_cjk_font.py, harness/ (emu.sh pinned to emulator-5560 / Pico_Emulator_6_1_fresh)
```
Data flow: panel buttons / debug hooks -> `AppController.onEvent(Ev)`; ECS frame -> `AppController.onFrame(FrameInput)`
-> judge samples -> `UiState` (StateFlow) -> panels; the scene reads the controller every frame. Debug overrides
(`head=`, `gaze=`, `door=`, `stick=`, `exec`, `lost=`, `eye=off`) replace tracker values inside that same input path.

## Platform facts found here (emulator-5560, SDK 6.1.9)

- HMD pose SUPPORTED at once (eye 1.65 m); controllers `DEVICE_NOT_SUPPORTED` (no stick / A / grip injection: debug
  hooks); hand tracking SUPPORTED (no pinch data); eye tracking `WITHOUT_PERMISSION`, and the system eye-tracking
  permission dialog DOES show on the emulator (first S01; it was dismissed, not granted).
- Mirrors at 20 Hz: 18.7 pictures/s, the 3 Canvases ~56 draws/s, average frame 17.4 ms at 60 fps (N3 ok). The idle
  emulator renders ~1 fps: the build takes ~22 s (S00); capture only after `emu.sh fast on`.
- Textured MeshModel quads sample v = 0 at the bitmap's bottom row: meshes put v = 0 on the near / bottom edge,
  bitmaps are drawn upright, no flip (`testtex 1`: the upright F↑ card reads correctly, the road F↑ points ahead).
- PBR in Full space: sun 2600 lux + fill 700 lux + 30 % emissive ambient keeps palette A dark grey (9000 lux washed
  it out to light grey).
- The system pointer works on the panels (emu.sh pinch-tap on 「设置」 → S15).
- `navigator.closeStage()` is a suspend function (quit launches it before finishAndRemoveTask).

## Debug broadcast (debug builds)

`adb -s emulator-5560 shell "am broadcast -a io.embodit.shouldercheck.DEBUG -p io.embodit.shouldercheck --es cmd '<cmd>'"`
Each answers `CMD <name> -> <result>` (tag ShoulderCheck). Any command except state / hold / panels / pose / sceneinfo /
tap releases a previous `goto` hold.

| command | effect (same path as user input) |
|---|---|
| `goto <S01…S19, S01B> [args]` | reach the state through its normal entry (see the screenshot list); timed states held for capture; `goto S19` = `blocked=not-in-full-space`; `goto S00` only answers with the relaunch command (S00 is entered by launching) |
| `lesson=<n>` [`step=<k>`] | choose lesson n (as the S03 row); with step: the demo held at step k |
| `scene=<lane-left/lane-right/pull-out/right-turn/door-driver/door-rear/mirrors/blind-spot>` [hazard …] | start that question at S05 |
| `hazard=<none/car/suv/ebike/bike/moto> side=L/R z=<rear end, m behind> v=<m/s, + overtaking>` | restart the current scene with that hazard, S05 held |
| `gaze=<yaw>,<pitch>` / `gaze=off` | eye-gaze override (car frame); set before S05 for an eye-mode question |
| `eye=off` / `eye=on` | the eye tracker reports no data (S17) |
| `head=<yaw>` [`pitch=<p>`] / `head=off` | head override (yaw + = right) for judge, range, gaze dot, ANG |
| `range=<L>,<R>` | finish the turn-range measurement with these maxima (S01B exit) |
| `mirrorAim=L/R,<deg>` [`z=<m>` `hazard=…`] | side-mirror aim (lesson 1: the live judge aim; else the saved aim); with z: a held car there |
| `signal=L/R/off` | stalk to that state (same toggle as the stick) |
| `stick=L/R` [`hand=R`] | one thumbstick push through the controller edge detector (lesson 1: ±1° mirror aim) |
| `exec` / `a` | one A press (execute; lesson 1 confirm) |
| `b` | one B press (close the door) |
| `door=left/right push=<m>` / `door=off` | that hand grips the handle with this outward push (3 cm → 8°, 10 cm → 60°) |
| `result outcome=clear/waited/lucky/miss/timeout` [`mode=eye/head`] | a scripted question through a real judge, then S10 |
| `replay t=<s>` | S10 → S11 at t (held), or seek inside S11 |
| `seed=<n> q=<n>` | random-traffic set with that seed from question n |
| `seed-scores=1` | add sample records + range / blind spots (scorecard) |
| `lost=1` / `lost=0` | the head tracker reports lost (S18; 10 s → S16) |
| `blocked=<reason>` / `blocked=off` | the head tracker's block reason (not-in-full-space): S19 after 2 s, `off` = the real exit to S01 |
| `speed=<x>` | run-clock factor (0.05 … 8) |
| `testtex 1` / `testtex 0` | F↑ test texture on the road + an upright F↑ card 3 m ahead (D10) |
| `lang=zh/en/system`, `traffic=left/right`, `mute`, `volume=low/mid/high` | settings events |
| `tap <button>` | press a composed button by its probe name (`sit`, `settings`, `next`, `confirmExit`, …; `tap` alone lists them) |
| `sit`, `pause`, `hold 0/1`, `mirrorHz=<hz>` | 坐好了, pause, freeze timed states, mirror redraw rate |
| `state` / `panels` / `pose` / `sceneinfo` | dumps |
| `sceneinfo instances` | every 3D entity: model, world height, opacity component, blending (ui-audit e) |
| `lineup front/side/off` | the 5 calibrated models on start markers ahead (ui-audit f) |
| `quit` | the settings exit path (finishAndRemoveTask, trackers + audio released) |

## Screenshot list (design states[].qaCapture)

Level the camera at yaw 0 first (`tools/harness/uia_cam.ps1 set camera_rotation_button on`, right-drags ≈ 0.038° per
px; `state` prints `head=yaw/pitch`). Then `emu.sh fast on`, wait 6 s, `emu.sh shot NAME`.

| state | command(s) |
|---|---|
| S00 | relaunch (`am force-stop` + `am start`), shot during the build (~0.5 s with `fast on`, ~20 s idle) |
| S01 | `goto S01` (D9 cold starts: pitch the emulator camera −30° / +30° before launching) |
| S01B | `goto S01B`, then turn the camera left 75° (or `head=-75`), hold 1 s |
| S02 | `goto S02` (no scores: `tap clearScores` + `tap confirmClear` on S12 first); with scores: `seed-scores=1`, `goto S02` |
| S03 | `goto S03` |
| S04 | `goto S04 lesson=5 step=3` |
| S05 | `goto S05 scene=lane-left` |
| S06 | `goto S06 scene=lane-left done=I,L`; then `head=-64` (look-back lit; 5 s freshness runs again) |
| S07 | `goto S07 scene=lane-left t=1.5`; `goto S07 scene=door-driver push=0.10` |
| S08 | `goto S08` |
| S09 | `goto S09 hazard=ebike side=L` |
| S10 | `result outcome=clear` / `waited` / `lucky` / `miss` / `timeout`; `result outcome=clear mode=head` |
| S11 | `goto S11 t=2.0` (or `replay t=2.0` on S10) |
| S12 | `goto S12 seed-scores=1` |
| S13 | `goto S13 seed=42 q=3`, `hold 1` |
| S14 | `goto S14` |
| S15 | `goto S15`; `lang=en` + `goto S15`; `traffic=left` + `goto S02` |
| S16 | `goto S16` |
| S17 | `eye=off` + `goto S02`; `eye=off` + `goto S06 scene=lane-left` |
| S18 | `goto S06 scene=lane-left`, `lost=1` (then `lost=0`) |
| S19 | `goto S19` (= `blocked=not-in-full-space`, 2 s), then `blocked=off` |
| S20 | `lang=en`, then the S02 / S06 / S10 / S12 commands |
| S21 | `traffic=left`, then `goto S02`; `goto S06 scene=door-driver` (lesson 3 mirrored) |
| rear seat | `goto S05 scene=door-rear` (seat-back screen) |
| D10 | `testtex 1` (road + upright F↑ card), `testtex 0` |

Captured files (2026-10-30 19:1x–19:5x, HD 2560, all after the upright-panel + colour fixes): `../spatial-app-factory/apps/shoulder-check/build/shots/` —
S01, S01_pitch-30 / +30, S01B, S02, S03, S04, S05, S05_rear, S06, S06_head, S07, S07_door, S08, S09, S10_clear / waited /
lucky / miss / timeout / head, S11, S12, S13, S14, S15, S15_en, S16, S17_S02, S17_S06, S18, S19, S20_S02 / S06 / S10 /
S12, S21_S02, S21_S06, D10_testtex, mirL, mirR; `build/ui-audit/` (occlusion turns,
contrast / height shots, model lineup); `build/first-screen.png`, `build/launcher.png` (this AVD never draws the
launcher grid: the HOME "Back to Home" dialog shows the olive icon), `build/coldstart.txt`. Audits: `build/ui-audit.md`,
`build/headpose-audit.md`, `build/reality-audit.md`. Camera helpers used: scratch `turn.sh` / `pitch.sh` logic =
right-drags of ≤ 420 px at 0.0386°/px, read back with `state` (`head=yaw/pitch`).
