package io.embodit.shouldercheck.scene

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat as SdkQuat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.Base
import io.embodit.shouldercheck.app.CtrlIn
import io.embodit.shouldercheck.app.DebugCommands
import io.embodit.shouldercheck.app.FrameInput
import io.embodit.shouldercheck.app.HeadIn
import io.embodit.shouldercheck.app.P1Page
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.CabinLayout
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Part
import io.embodit.shouldercheck.domain.PartMat
import io.embodit.shouldercheck.domain.PartMesh
import io.embodit.shouldercheck.domain.Placement
import io.embodit.shouldercheck.domain.Pose
import io.embodit.shouldercheck.domain.Quat
import io.embodit.shouldercheck.domain.Road
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.input.ControllerInput
import io.embodit.shouldercheck.platform.tracking.EyeTracker
import io.embodit.shouldercheck.platform.tracking.HandTracker
import io.embodit.shouldercheck.platform.tracking.HeadTracker
import io.embodit.shouldercheck.platform.tracking.TrackerForce
import io.embodit.shouldercheck.ui.theme.ScColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The scene of the open Stage (the frame system and the debug hooks talk to it). Main thread only. */
object ActiveScene {
    var current: CarScene? = null
}

/** The one registered ECS system of the app: every per-frame 3D job happens in [CarScene.onFrame]. */
class ScFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.current?.onFrame(context.deltaTime)
    }
}

/** Pooled panel ids and sizes (dp). */
object PanelIds {
    const val P1 = "p1"
    const val P1_TALL = "p1tall"
    const val P2 = "p2"
    const val ANG_L = "angL"
    const val ANG_R = "angR"
    const val MIR_I = "mirI"
    const val MIR_L = "mirL"
    const val MIR_R = "mirR"

    val SIZES: Map<String, Pair<Int, Int>> =
        linkedMapOf(
            P1 to (800 to 520),
            P1_TALL to (800 to 600),
            P2 to (760 to 176),
            ANG_L to (200 to 80),
            ANG_R to (200 to 80),
            MIR_I to (520 to 140),
            MIR_L to (360 to 220),
            MIR_R to (360 to 220),
        )

    /** Mirror panels: 1 dp = 0.5 mm, so the glass is exactly the design size (MIR-I 0.26 × 0.07, sides 0.18 × 0.11). */
    const val MIRROR_SCALE = 0.625

    fun mirrorPanel(id: MirrorId): String = when (id) {
        MirrorId.INNER -> MIR_I
        MirrorId.LEFT -> MIR_L
        MirrorId.RIGHT -> MIR_R
    }
}

/**
 * The car and the street (design `entities3d`, `ecs`, `budget`): programmatic cabin for both seats (doors on hinges,
 * mirror housings, hood), street (textured road, kerb, sidewalk, bike lane, trees, building blocks, bus stop,
 * intersection for lesson 8), sky dome; GLB road users per question; ghost car, gaze dot + trail, guide rings, demo hand,
 * alert frame, turn arcs; the panel pool; the mirror pictures (~20 Hz). Resource creation is queued (≤ 2 per frame while
 * frames are fast, one per 1.2 s while the idle emulator renders slowly: factory ANR finding).
 *
 * Car frame: the car root sits at E0 with yaw only ([Placement.seatFrame], D9); every car-fixed entity is its child, in
 * the current seat's eye frame. Seat placement happens only on 坐好了 / 回正 (before that the car keeps itself in front
 * of the head, so S00 / S01 are seen at once).
 */
class CarScene(private val context: Context) {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)
    private val c = AppController

    private val head = HeadTracker()
    private val eyes = EyeTracker()
    private val controllers = ControllerInput()
    private val hands = HandTracker()

    private var scope: CoroutineScope? = null
    private var attachments: SpatialViewAttachments? = null
    private var stageRoot: Entity? = null
    private var carRoot: Entity? = null
    private var worldRoot: Entity? = null
    private var pool: PanelPool? = null
    private val shared = SharedResources()
    private var models: Models? = null
    private var attached = false
    private var paused = false
    private var attachMs = 0L
    private var frame = 0L

    // build queue
    private val heavy = ArrayDeque<Pair<String, () -> Unit>>()
    private val light = ArrayDeque<Pair<String, () -> Unit>>()
    private var lastHeavyMs = 0L
    private var builtReported = false

    // resources
    private var cube: MeshResource? = null
    private var ball: MeshResource? = null
    private var ringMesh: MeshResource? = null
    private var discMesh: MeshResource? = null
    private var roadMesh: MeshResource? = null
    private var quadMesh: MeshResource? = null
    private val mats = HashMap<String, Material>()
    private var roadMat: UnlitMaterial? = null
    private var testTexture: TextureResource? = null
    private val roadTextures = HashMap<String, TextureResource>()
    private var ghostMat: UnlitMaterial? = null
    private var ghostRed: UnlitMaterial? = null
    private var alertMat: UnlitMaterial? = null

    // groups
    private var cabinGroups = HashMap<Seat, Entity>()
    private var cabinParts = HashMap<Seat, List<Pair<Entity, Part>>>()
    private var doorPivots = HashMap<Seat, Pair<Entity, Dir>>()
    private var cabinSide: TrafficSide? = null
    private var streetGroup: Entity? = null
    private var streetKey: String? = null
    private var roadEntity: Entity? = null
    private var testQuad: Entity? = null
    private var testRoadCard: Entity? = null
    private var testMat: UnlitMaterial? = null
    private val faded = HashSet<Entity>()
    private var lastFadeMs = 0L

    // dynamic
    private val actorInstances = HashMap<String, Entity>()
    private val instancePool = HashMap<String, ArrayList<Entity>>()
    private var bus: Entity? = null
    private var ghost: Entity? = null
    private val ghostParts = ArrayList<Entity>()
    private var gazeDot: Entity? = null
    private val trail = ArrayList<Entity>()
    private val trailPts = ArrayDeque<Pair<Long, Vec3>>()
    private val guides = ArrayList<Entity>()
    private var hand: Entity? = null
    private var alert: Entity? = null
    private val arcDots = HashMap<Dir, List<Entity>>()
    private val replayDots = ArrayList<Entity>()
    private var lightEntity: Entity? = null
    private var fillEntity: Entity? = null

    // placement
    private var car: Pose? = null
    private var appliedEpoch = -1
    private var firstValidMs = -1L
    private var lastProvisionalMs = 0L
    private var reseatDone = false
    private var startupLogs = 0

    // mirrors
    private var lastMirrorMs = 0L
    private var mirrorFrames = 0
    private var mirrorWindowMs = 0L
    private var frameTimeAcc = 0.0
    private var frameCount = 0

    // N5 spike
    private var lastStick = 0f
    private var stickLogged = false

    // ======================================================================================== lifecycle

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached) detach()
        attachMs = SystemClock.elapsedRealtime()
        this.attachments = attachments
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin: move the root once so its space = Stage space
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(SdkQuat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val carE = Entity().named("car")
        root.addChild(carE)
        carRoot = carE
        val world = Entity().named("world")
        root.addChild(world)
        worldRoot = world
        val p = PanelPool(carE, root) { id, shown -> panelShown[id] = shown }
        for ((id, size) in PanelIds.SIZES) p.slot(id, size.first.toDouble(), size.second.toDouble())
        pool = p
        // light: the sun 45° from the car's right front, a weak fill from the left back (budget.lights)
        lightEntity = Entity().named("sun").also {
            it.components.set(DirectionalLightComponent(Color4(1f, 0.97f, 0.92f, 1f), SUN_LUX, false))
            carE.addChild(it)
            it.setLocal(rotation = Layout.facing(Vec3.ZERO, Vec3(0.6, 0.75, -0.6)))
        }
        fillEntity = Entity().named("fill").also {
            it.components.set(DirectionalLightComponent(Color4(0.85f, 0.9f, 1f, 1f), FILL_LUX, false))
            carE.addChild(it)
            it.setLocal(rotation = Layout.facing(Vec3.ZERO, Vec3(-0.5, 0.5, 0.7)))
        }
        head.start()
        eyes.start()
        controllers.start()
        hands.start()
        models = runCatching { Models(context) }.onFailure { ScLog.e("MODEL calibration failed: $it", it) }.getOrNull()
        queueBuild()
        bindPanels(attachments)
        ActiveScene.current = this
        DebugCommands.sceneDump = { what -> dump(what) }
        attached = true
        ScLog.i("SCENE attached queued=${heavy.size}+${light.size} root=${ScLog.pos(offset.domain())}")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        this.attachments = attachments
        val complete = pool?.bind(attachments, SystemClock.elapsedRealtime()) ?: return
        if (!complete) bindTick.intValue++
    }

    fun detach() {
        ActiveScene.current = null
        DebugCommands.sceneDump = null
        attached = false
        heavy.clear()
        light.clear()
        scope?.cancel()
        scope = null
        head.stop()
        eyes.stop()
        controllers.stop()
        hands.stop()
        pool?.release()
        pool = null
        panelShown.clear()
        runCatching { stageRoot?.destroy(true) }
        stageRoot = null
        carRoot = null
        models?.close()
        models = null
        shared.close()
        ScLog.i("SCENE detached (trackers stopped)")
    }

    fun onBackground() {
        paused = true
        head.stop()
        eyes.stop()
        controllers.stop()
        hands.stop()
        ScLog.i("SCENE paused")
    }

    fun onForeground() {
        if (!attached) return
        head.start()
        eyes.start()
        controllers.start()
        hands.start()
        paused = false
        ScLog.i("SCENE resumed")
    }

    // ======================================================================================== build

    private fun mat(key: String): Material = mats.getValue(key)

    private fun queueBuild() {
        heavy += "cube" to { cube = shared.keep(Meshes.unitCube()) }
        heavy += "ball" to { ball = shared.keep(Meshes.sphere("ball")) }
        heavy += "sky" to {
            val tex = shared.keep(TextureResource.create(Textures.sky()))
            val m = unlit(shared, Color.White, texture = tex, twoSided = true)
            val dome = ModelEntity(shared.keep(Meshes.sphere("sky", inward = true, rings = 18, sectors = 24)), m).named("sky")
            dome.setLocal(position = Vec3(0.0, 0.0, 0.0), scale = Vec3(SKY_D, SKY_D, SKY_D))
            carRoot?.addChild(dome)
        }
        heavy += "roadMesh" to { roadMesh = shared.keep(Meshes.road("road", -ROAD_HALF, ROAD_HALF, Textures.DASH_CYCLE_M)) }
        light += "materials" to { makeMaterials() }
        heavy += "roadTexture" to { roadTexture(c.displayScenario().road) }
        light += "ground" to {
            ModelEntity(cube!!, unlit(shared, ScColors.Sidewalk.shade(0.78f))).named("ground").also {
                it.setLocal(position = Vec3(0.0, Road.ROAD_Y - 0.06, 0.0), scale = Vec3(900.0, 0.05, 900.0))
                carRoot?.addChild(it)
            }
        }
        light += "street" to { ensureStreet() }
        heavy += "ring" to { ringMesh = shared.keep(Meshes.ring("wheel", tube = 0.05)) }
        light += "cabin" to { ensureCabin() }
        heavy += "disc" to { discMesh = shared.keep(Meshes.disc("disc")) }
        heavy += "glowTex" to {
            val glow = shared.keep(TextureResource.create(Textures.radial(64)))
            val ring = shared.keep(TextureResource.create(Textures.radial(96, ring = true)))
            mats["glow"] = unlit(shared, ScColors.Gaze, BlendingMode.TRANSPARENT, opacity = 0.9f, twoSided = true, depthWrite = false, texture = glow)
            mats["guide"] = unlit(shared, ScColors.Guide, BlendingMode.TRANSPARENT, opacity = 0.95f, twoSided = true, depthWrite = false, texture = ring)
        }
        light += "dynamic" to { makeDynamic() }
        heavy += "quad" to { quadMesh = shared.keep(Meshes.quad("quad")) }
        heavy += "testTex" to { testTexture = shared.keep(TextureResource.create(Textures.orientationTest())) }
        light += "glb" to { loadModels() }
    }

    private fun makeMaterials() {
        mats[PartMat.DASH.name] = pbr(shared, ScColors.Dash)
        mats[PartMat.TRIM.name] = pbr(shared, ScColors.Trim)
        mats[PartMat.SEAT.name] = pbr(shared, ScColors.Seat, roughness = 0.95f)
        mats[PartMat.HEADLINER.name] = pbr(shared, ScColors.Headliner, roughness = 0.95f)
        mats[PartMat.FRAME.name] = pbr(shared, ScColors.MirrorFrame)
        mats[PartMat.DARK.name] = pbr(shared, ScColors.MirrorFrame, roughness = 0.6f)
        mats[PartMat.HANDLE.name] = pbr(shared, ScColors.Kerb.shade(0.8f), roughness = 0.35f)
        mats[PartMat.MIRROR_BACK.name] = pbr(shared, ScColors.MirrorFrame, roughness = 0.5f)
        mats[PartMat.HOOD.name] = pbr(shared, argbColor(c.hoodColour), roughness = 0.4f)
        mats["kerb"] = pbr(shared, ScColors.Kerb)
        mats["sidewalk"] = pbr(shared, ScColors.Sidewalk)
        mats["asphalt"] = unlit(shared, ScColors.Road.shade(0.9f))
        mats["leaf"] = pbr(shared, ScColors.TreeLeaf)
        mats["trunk"] = pbr(shared, ScColors.TreeTrunk)
        mats["fence"] = pbr(shared, ScColors.Kerb.shade(0.9f), roughness = 0.5f)
        mats["shelter"] = pbr(shared, ScColors.BuildingNear.shade(0.8f), roughness = 0.5f)
        mats["line"] = unlit(shared, ScColors.LaneLine)
        mats["bus"] = pbr(shared, ScColors.Bus, roughness = 0.5f)
        mats["busGlass"] = pbr(shared, ScColors.Dash, roughness = 0.3f)
        val tex = shared.keep(TextureResource.create(Textures.gradient(ScColors.BuildingNear, ScColors.BuildingFar)))
        mats["building"] = unlit(shared, Color.White, texture = tex)
        mats["buildingFar"] = unlit(shared, ScColors.BuildingFar.shade(0.95f), texture = tex)
        mats["arc"] = unlit(shared, ScColors.Gaze)
        mats["trace"] = unlit(shared, ScColors.Gaze.shade(0.9f))
        val hood = argbColor(c.hoodColour)
        ghostMat = unlit(shared, hood, BlendingMode.TRANSPARENT, opacity = 0.3f, depthWrite = false).also { mats["ghost"] = it }
        ghostRed = unlit(shared, ScColors.Miss, BlendingMode.TRANSPARENT, opacity = 0.45f, depthWrite = false).also { mats["ghostRed"] = it }
        alertMat = unlit(shared, ScColors.Miss, BlendingMode.TRANSPARENT, opacity = 0.0f, depthWrite = false).also { mats["alert"] = it }
        roadMat = unlit(shared, Color.White)
    }

    private fun roadKey(road: RoadLayout): String {
        val g =
            when (road.situation) {
                Situation.PULL_OUT, Situation.RIGHT_TURN, Situation.DOOR_DRIVER, Situation.DOOR_REAR -> road.situation.id
                else -> "lanes"
            }
        return "$g:${road.side}"
    }

    private fun roadTexture(road: RoadLayout): TextureResource {
        val key = roadKey(road)
        roadTextures[key]?.let { return it }
        val (x0, x1) = roadExtent(road)
        val t = shared.keep(TextureResource.create(Textures.road(road, x0, x1)))
        roadTextures[key] = t
        return t
    }

    private fun roadExtent(road: RoadLayout): Pair<Double, Double> {
        val drive = road.strips.filter { it.type != io.embodit.shouldercheck.domain.StripType.SIDEWALK }
        return drive.minOf { it.xMin } to drive.maxOf { it.xMax }
    }

    private fun model(mesh: MeshResource, material: Material, name: String, parent: Entity?): ModelEntity = ModelEntity(mesh, material).named(name).also { parent?.addChild(it) } as ModelEntity

    /** Unit-cube box of [size] at [centre]. */
    private fun box(parent: Entity?, name: String, centre: Vec3, size: Vec3, material: Material, rotation: Quat = Quat.IDENTITY): Entity =
        model(cube!!, material, name, parent).also { it.setLocal(position = centre, rotation = rotation, scale = size) }

    // ---------------------------------------------------------------- cabin

    private fun ensureCabin() {
        val side = c.progress.settings.traffic
        if (cabinSide == side && cabinGroups.isNotEmpty()) return
        if (cube == null || ringMesh == null || mats.isEmpty()) return
        for (g in cabinGroups.values) runCatching { g.destroy(true) }
        cabinGroups.clear()
        cabinParts.clear()
        doorPivots.clear()
        faded.clear()
        for (seat in Seat.values()) {
            val layout = Layout.cabin(seat, side)
            val group = Entity().named("cabin:$seat")
            carRoot?.addChild(group)
            cabinGroups[seat] = group
            cabinParts[seat] = buildCabin(group, layout, seat)
            group.enabled = false
        }
        cabinSide = side
        ScLog.i("CABIN built side=$side parts=${cabinParts.mapValues { it.value.size }}")
    }

    private fun buildCabin(group: Entity, layout: CabinLayout, seat: Seat): List<Pair<Entity, Part>> {
        val out = ArrayList<Pair<Entity, Part>>()
        for (p in layout.parts) {
            val mesh = if (p.mesh == PartMesh.RING) ringMesh!! else cube!!
            val e = model(mesh, mat(p.mat.name), p.name, group)
            e.setLocal(position = p.centre, rotation = p.rotation, scale = p.size)
            out += e to p
        }
        for (d in layout.doors) {
            val pivot = Entity().named(d.name)
            group.addChild(pivot)
            pivot.setLocal(position = d.hinge)
            for (p in d.parts) {
                val e = model(cube!!, mat(p.mat.name), p.name, pivot)
                e.setLocal(position = p.centre - d.hinge, rotation = p.rotation, scale = p.size)
                // a closed door is part of the cabin for the D20 test
                out += e to p
            }
            if (d.opens) doorPivots[seat] = pivot to d.side
        }
        return out
    }

    // ---------------------------------------------------------------- street

    private fun ensureStreet() {
        if (cube == null || roadMesh == null || mats.isEmpty()) return
        val sc = c.displayScenario()
        val key = roadKey(sc.road) + ":" + sc.seat
        if (key == streetKey) return
        streetGroup?.let { runCatching { it.destroy(true) } }
        val g = Entity().named("street")
        carRoot?.addChild(g)
        streetGroup = g
        streetKey = key
        buildStreet(g, sc.road)
        ScLog.i("STREET built $key")
    }

    private fun buildStreet(g: Entity, road: RoadLayout) {
        val y = Road.ROAD_Y
        val (x0, x1) = roadExtent(road)
        val mat = roadMat!!
        mat.setBaseColorTexture(if (c.testTex) testTexture ?: roadTexture(road) else roadTexture(road))
        roadEntity = model(roadMesh!!, mat, "road", g).also { it.setLocal(position = Vec3(x0, y, 0.0), scale = Vec3(x1 - x0, 1.0, 1.0)) }
        val kerbSide = if (road.kerbX > road.egoX) 1.0 else -1.0
        val farEdge = if (kerbSide > 0) x0 else x1
        val turn = road.situation == Situation.RIGHT_TURN
        val gapFrom = -27.0
        val gapTo = -13.0
        // z spans of street furniture (split at the intersection for lesson 8)
        val spans = if (turn) listOf(-ROAD_HALF to gapFrom, gapTo to ROAD_HALF) else listOf(-ROAD_HALF to ROAD_HALF)
        for ((za, zb) in spans) {
            val zc = (za + zb) / 2
            val len = zb - za
            box(g, "kerb", Vec3(road.kerbX + kerbSide * 0.1, y + 0.075, zc), Vec3(0.2, 0.15, len), mat("kerb"))
            box(g, "sidewalk", Vec3(road.kerbX + kerbSide * 2.1, y + 0.07, zc), Vec3(3.8, 0.14, len), mat("sidewalk"))
            // far side: median fence, opposite carriageway, far sidewalk
            box(g, "fence", Vec3(farEdge - kerbSide * 0.3, y + 0.5, zc), Vec3(0.08, 1.0, len), mat("fence"))
            box(g, "fenceBase", Vec3(farEdge - kerbSide * 0.3, y + 0.05, zc), Vec3(0.35, 0.1, len), mat("kerb"))
            box(g, "opposite", Vec3(farEdge - kerbSide * 5.6, y + 0.002, zc), Vec3(10.0, 0.004, len), mat("asphalt"))
            box(g, "farSidewalk", Vec3(farEdge - kerbSide * 12.6, y + 0.07, zc), Vec3(4.0, 0.14, len), mat("sidewalk"))
        }
        // trees on the sidewalk, buildings behind both sidewalks
        var z = -96.0
        while (z < 96.0) {
            if (!(turn && z > gapFrom - 2 && z < gapTo + 2)) {
                val tx = road.kerbX + kerbSide * 1.2
                box(g, "trunk", Vec3(tx, y + 1.2, z), Vec3(0.22, 2.4, 0.22), mat("trunk"))
                model(ball!!, mat("leaf"), "crown", g).setLocal(position = Vec3(tx, y + 3.3, z), scale = Vec3(2.6, 2.4, 2.6))
            }
            z += 12.0
        }
        var bz = -120.0
        var i = 0
        while (bz < 120.0) {
            val len = 18.0 + (i * 7) % 13
            val h = 9.0 + (i * 11) % 16
            val zc = bz + len / 2
            if (!(turn && zc + len / 2 > gapFrom - 1 && zc - len / 2 < gapTo + 1)) {
                box(g, "building", Vec3(road.kerbX + kerbSide * (4.2 + 7.0), y + h / 2, zc), Vec3(14.0, h, len), mat("building"))
                box(g, "buildingFar", Vec3(farEdge - kerbSide * (14.8 + 7.0), y + h * 0.6, zc + 5), Vec3(14.0, h * 1.2, len), mat("buildingFar"))
            }
            bz += len + 2.5
            i++
        }
        // bus stop (lane scenes: the bus of lesson 5 stops at z ≈ −14)
        if (road.situation == Situation.LANE_LEFT || road.situation == Situation.LANE_RIGHT) {
            val sx = road.kerbX + kerbSide * 2.6
            box(g, "shelterRoof", Vec3(sx, y + 2.5, -16.0), Vec3(1.8, 0.08, 4.5), mat("shelter"))
            box(g, "shelterBack", Vec3(sx + kerbSide * 0.85, y + 1.3, -16.0), Vec3(0.06, 2.4, 4.5), mat("shelter"))
            box(g, "shelterPostA", Vec3(sx - kerbSide * 0.8, y + 1.25, -13.9), Vec3(0.08, 2.5, 0.08), mat("fence"))
            box(g, "shelterPostB", Vec3(sx - kerbSide * 0.8, y + 1.25, -18.1), Vec3(0.08, 2.5, 0.08), mat("fence"))
        }
        // intersection (lesson 8): the cross street, its stop line
        if (turn) {
            box(g, "crossRoad", Vec3(road.egoX, y + 0.004, (gapFrom + gapTo) / 2), Vec3(80.0, 0.004, gapTo - gapFrom), mat("asphalt"))
            val lanes = road.strips.filter { it.type != io.embodit.shouldercheck.domain.StripType.SIDEWALK && it.type != io.embodit.shouldercheck.domain.StripType.BIKE_LANE }
            val sx0 = lanes.minOf { it.xMin }
            val sx1 = lanes.maxOf { it.xMax }
            box(g, "stopLine", Vec3((sx0 + sx1) / 2, y + 0.008, gapTo + 0.3), Vec3(sx1 - sx0, 0.004, 0.4), mat("line"))
        }
    }

    // ---------------------------------------------------------------- dynamic entities

    private fun makeDynamic() {
        val root = carRoot ?: return
        // ghost car: your car's shell, 30 % (entities3d ghostCar, 3 meshes)
        val gr = Entity().named("ghost")
        root.addChild(gr)
        ghostParts += box(gr, "ghostBody", Vec3(0.0, 0.62, 0.0), Vec3(1.8, 0.75, 4.6), ghostMat!!)
        ghostParts += box(gr, "ghostCabin", Vec3(0.0, 1.18, 0.25), Vec3(1.6, 0.42, 2.5), ghostMat!!)
        ghostParts += box(gr, "ghostWheels", Vec3(0.0, 0.3, 0.0), Vec3(1.7, 0.3, 3.6), ghostMat!!)
        gr.enabled = false
        ghost = gr
        // gaze dot and its 0.6 s trail
        gazeDot = model(discMesh!!, mat("glow"), "gazeDot", root).also { it.enabled = false }
        repeat(6) { trail += model(discMesh!!, mat("glow"), "trail$it", root).also { e -> e.enabled = false } }
        repeat(2) { guides += model(discMesh!!, mat("guide"), "guide$it", root).also { e -> e.enabled = false } }
        // demo hand: palm and fingers, guide material
        val hr = Entity().named("demoHand")
        root.addChild(hr)
        box(hr, "palm", Vec3.ZERO, Vec3(0.08, 0.025, 0.09), mat("guide"))
        for (k in 0 until 4) box(hr, "finger$k", Vec3(-0.03 + k * 0.02, 0.0, -0.075), Vec3(0.015, 0.018, 0.06), mat("guide"))
        box(hr, "thumb", Vec3(0.05, 0.0, -0.01), Vec3(0.015, 0.018, 0.05), mat("guide"), Layout.rotY(-35.0))
        hr.enabled = false
        hand = hr
        // alert frame (head-locked, the only head-locked element): four bars round the view, 0.5 m ahead
        val al = Entity().named("alert")
        worldRoot?.addChild(al)
        box(al, "alertTop", Vec3(0.0, 0.42, -0.5), Vec3(1.1, 0.07, 0.01), alertMat!!)
        box(al, "alertBottom", Vec3(0.0, -0.42, -0.5), Vec3(1.1, 0.07, 0.01), alertMat!!)
        box(al, "alertLeft", Vec3(-0.52, 0.0, -0.5), Vec3(0.07, 0.8, 0.01), alertMat!!)
        box(al, "alertRight", Vec3(0.52, 0.0, -0.5), Vec3(0.07, 0.8, 0.01), alertMat!!)
        al.enabled = false
        alert = al
        for (d in Dir.values()) arcDots[d] = (0 until 16).map { model(ball!!, mat("arc"), "arc$d$it", root).also { e -> e.enabled = false } }
        repeat(40) { replayDots += model(ball!!, mat("trace"), "trace$it", root).also { e -> e.enabled = false } }
        // the bus of lesson 5 (no GLB): body + window band
        val b = Entity().named("bus")
        root.addChild(b)
        box(b, "busBody", Vec3(0.0, 1.55, 0.0), Vec3(2.5, 2.9, 12.0), mat("bus"))
        box(b, "busGlass", Vec3(0.0, 2.2, 0.0), Vec3(2.54, 0.9, 12.04), mat("busGlass"))
        b.enabled = false
        bus = b
        testMat = unlit(shared, Color.White, twoSided = true)
        testQuad = model(quadMesh ?: cube!!, testMat!!, "testQuad", root).also { it.enabled = false }
        // a 2.5 m card lying on the road ahead, built by the SAME road-mesh function (v grows towards −Z)
        testRoadCard = model(shared.keep(Meshes.road("roadCard", -1.0, 0.0, 1.0)), testMat!!, "testRoadCard", root).also { it.enabled = false }
    }

    private fun loadModels() {
        val m = models ?: return
        scope?.launch {
            for (role in listOf("sedan", "suv", "ebike", "bike", "moto")) m.load(role)
            ScLog.i("MODEL all loaded")
        }
    }

    private fun runWork(dt: Float, now: Long) {
        // light items (entities, materials): a few per frame; heavy (mesh / texture): 2 per frame when fast, else 1 per 1.2 s
        var budget = 3
        while (light.isNotEmpty() && budget-- > 0) {
            val (name, work) = light.first()
            val ready = when (name) {
                "street", "cabin" -> cube != null && roadMesh != null && mats.isNotEmpty() && (name != "cabin" || ringMesh != null)
                "dynamic" -> discMesh != null && mats.containsKey("glow") && ball != null
                "ground", "materials" -> cube != null
                "glb" -> true
                else -> true
            }
            if (!ready) break
            light.removeFirst()
            val t0 = SystemClock.elapsedRealtime()
            runCatching { work() }.onFailure { ScLog.e("SCENE build '$name' failed: $it", it) }
            ScLog.i("SCENE built $name ms=${SystemClock.elapsedRealtime() - t0}")
        }
        if (heavy.isNotEmpty()) {
            val n = if (dt < FAST_FRAME_SEC) 2 else if (now - lastHeavyMs >= SLOW_GAP_MS) 1 else 0
            repeat(n) {
                val (name, work) = heavy.removeFirstOrNull() ?: return@repeat
                val t0 = SystemClock.elapsedRealtime()
                runCatching { work() }.onFailure { ScLog.e("SCENE build '$name' failed: $it", it) }
                lastHeavyMs = SystemClock.elapsedRealtime()
                ScLog.i("SCENE built $name ms=${lastHeavyMs - t0} left=${heavy.size}+${light.size}")
            }
        }
        if (!builtReported && heavy.isEmpty() && light.isEmpty()) {
            builtReported = true
            ScLog.i("SCENE build done ms=${now - attachMs}")
            c.onBuilt()
        }
    }

    // ======================================================================================== frame

    fun onFrame(deltaTime: Float) {
        if (!attached) return
        val now = SystemClock.elapsedRealtime()
        val dtRaw = if (deltaTime.isFinite() && deltaTime > 0f) deltaTime else 0.016f
        val dt = dtRaw.coerceAtMost(0.1f).toDouble()
        frame++
        frameTimeAcc += dtRaw
        frameCount++
        runWork(dtRaw, now)
        pool?.onFrame(now)
        attachments?.let { a -> if (pool?.all?.any { it.panel == null } == true) bindPanels(a) }
        if (paused) return

        head.forceLost = TrackerForce.headLost
        eyes.forceOff = TrackerForce.eyeOff
        val hp = head.pose()
        placeCar(hp, now)
        val carPose = car
        val input = readInput(hp, carPose, now, dt)
        c.onFrame(input)

        if (builtReported || cabinGroups.isNotEmpty()) {
            ensureCabin()
            ensureStreet()
        }
        updateCabin()
        updateActors()
        updateGhost(now)
        updateGaze(now)
        updateGuides(now)
        updateAlert(hp, now)
        updateArcs()
        updateTestTex()
        updatePanels(now)
        updateMirrors(now)
        startupLog(hp, now)
    }

    /** Seat placement (D9 placeUpright, yaw only): only on 坐好了 / 回正; before that the car keeps itself in front of you. */
    private fun placeCar(hp: Pose?, now: Long) {
        val pose = hp ?: return
        val root = carRoot ?: return
        var place = false
        var why = ""
        if (c.placeEpoch != appliedEpoch) {
            place = true
            why = "seat epoch=${c.placeEpoch}"
            appliedEpoch = c.placeEpoch
        } else if (!c.seated) {
            if (firstValidMs < 0) {
                firstValidMs = now
                place = true
                why = "first valid head pose"
            } else if (now - firstValidMs < 2000 && now - lastProvisionalMs > 250) {
                place = true
                why = "settling"
            } else if (!reseatDone && car != null) {
                // P1 more than 70° out of view, or too near / far: put the car back in front once (factory rule)
                val p1 = Placement.toWorld(car!!, Layout.p1(Seat.DRIVER, c.progress.settings.traffic).centre)
                val to = p1 - pose.position
                val off = Angles.between(to, pose.forward)
                if (off > 70.0 || to.length < 0.5 || to.length > 2.0) {
                    place = true
                    reseatDone = true
                    why = "P1 out of view ${off.toInt()}°"
                }
            }
        }
        if (!place) return
        val seat = Placement.seatFrame(pose)
        car = seat
        lastProvisionalMs = now
        root.setLocal(position = seat.position, rotation = seat.rotation)
        ScLog.i(String.format(Locale.US, "PLACE car=%s yaw=%.1f head=%s why=%s", ScLog.pos(seat.position), Angles.yawOfRotation(seat.rotation), ScLog.pos(pose.position), why))
    }

    private fun readInput(hp: Pose?, carPose: Pose?, now: Long, dt: Double): FrameInput {
        val headIn =
            if (hp != null && carPose != null) {
                val rel = carPose.rotation.conjugate() * hp.rotation
                HeadIn(Placement.headYawInCar(carPose, hp.rotation), Angles.pitchOfRotation(rel), Placement.toCar(carPose, hp.position))
            } else null
        val eyePose = eyes.pose(now)
        val gaze =
            if (eyePose != null && carPose != null) GazeRay(Placement.toCar(carPose, eyePose.position), carPose.rotation.conjugate().rotate(eyePose.forward).normalized())
            else null
        val ctrl = controllers.sample()
        val list = ArrayList<CtrlIn>()
        var tracked = false
        if (ctrl != null) {
            for (s in listOf(ctrl.first, ctrl.second)) {
                val pos = s.pose?.let { p -> carPose?.let { Placement.toCar(it, p.position) } }
                if (s.pose != null) tracked = true
                list += CtrlIn(s.hand, pos, s.gripPressed, s.stickX)
                if (s.hand == Hand.LEFT && abs(s.stickX - lastStick) > 0.2f) {
                    lastStick = s.stickX
                    if (!stickLogged || abs(s.stickX) > 0.7f) ScLog.i("N5 stick left x=${"%.2f".format(Locale.US, s.stickX)} (thumbstick injection works)")
                    stickLogged = true
                }
            }
        }
        val pinches = if (carPose != null) hands.pinches().map { Triple(it.hand, Placement.toCar(carPose, it.position), it.value) } else emptyList()
        return FrameInput(
            nowMs = now,
            dt = dt,
            head = headIn,
            headLost = head.lost(now),
            blockReason = TrackerForce.blocked ?: head.blockReason(),
            gaze = gaze,
            eyeReason = eyes.unavailableReason(now),
            controllers = list,
            controllersTracked = tracked,
            pinches = pinches,
            aPresses = controllers.takeA(),
            bPresses = controllers.takeB(),
            yPresses = controllers.takeY(),
        )
    }

    private fun seat(): Seat = c.displayScenario().seat

    private fun updateCabin() {
        val seat = seat()
        for ((s, g) in cabinGroups) {
            val want = s == seat
            if (g.enabled != want) g.enabled = want
        }
        doorPivots[seat]?.let { (pivot, side) ->
            val a = c.doorAngle()
            pivot.setLocal(rotation = Layout.rotY(side.sign * a))
        }
    }

    private val lineupInst = HashMap<String, Entity>()
    private val lineupMarks = ArrayList<Entity>()

    /**
     * Debug `lineup front|side`: the five calibrated models parked on start markers (white crosses at each model's
     * origin) in front of the car, for the ui-audit model views. Off = the normal actors again.
     */
    private fun updateLineup(): Boolean {
        val mode = c.dbgLineup
        val m = models
        if (mode == null || m == null || cube == null) {
            lineupInst.values.forEach { if (it.enabled) it.enabled = false }
            lineupMarks.forEach { if (it.enabled) it.enabled = false }
            return false
        }
        val roles = listOf("sedan", "suv", "ebike", "bike", "moto")
        val side = mode == "side"
        val spacing = if (side) 5.2 else 2.7
        val z = if (side) -15.0 else -11.0
        if (lineupMarks.isEmpty()) {
            for (i in roles.indices) {
                lineupMarks += box(carRoot, "mark$i-a", Vec3.ZERO, Vec3(0.9, 0.01, 0.06), mat("line"))
                lineupMarks += box(carRoot, "mark$i-b", Vec3.ZERO, Vec3(0.06, 0.01, 0.9), mat("line"))
            }
        }
        for ((i, role) in roles.withIndex()) {
            val x = (i - 2) * spacing
            val p = Vec3(x, Road.ROAD_Y, z)
            lineupMarks[2 * i].let { it.enabled = true; it.setLocal(position = p + Vec3(0.0, 0.006, 0.0)) }
            lineupMarks[2 * i + 1].let { it.enabled = true; it.setLocal(position = p + Vec3(0.0, 0.006, 0.0)) }
            var e = lineupInst[role]
            if (e == null && m.loaded(role)) e = m.instance(role)?.also { carRoot?.addChild(it); lineupInst[role] = it }
            e ?: continue
            if (!e.enabled) e.enabled = true
            // front view: the models face you (front = +Z, heading 180); side: front to your right (+90)
            e.setLocal(position = p, rotation = Quat.yaw(if (side) 90.0 else 180.0))
        }
        actorInstances.values.forEach { if (it.enabled) it.enabled = false }
        bus?.let { if (it.enabled) it.enabled = false }
        return true
    }

    private fun updateActors() {
        if (updateLineup()) return
        val m = models
        val boxes = c.actorBoxes()
        val used = HashSet<String>()
        var busUsed = false
        for ((a, box) in boxes) {
            if (a.kind == ActorKind.BUS) {
                bus?.let {
                    if (!it.enabled) it.enabled = true
                    it.setLocal(position = box.centre, rotation = Quat.yaw(box.headingDeg))
                }
                busUsed = true
                continue
            }
            val role = a.kind.model ?: continue
            var e = actorInstances[a.id]
            if (e == null && m != null && m.loaded(role)) {
                val free = instancePool[role]?.removeLastOrNull()
                e = free ?: m.instance(role)?.also { inst -> carRoot?.addChild(inst); ScLog.i("MODEL instance $role for ${a.id} opacityComponent=${inst.hasOpacityComponent()}") }
                if (e != null) actorInstances[a.id] = e
            }
            if (e == null) continue
            used += a.id
            if (!e.enabled) e.enabled = true
            e.setLocal(position = box.centre, rotation = Quat.yaw(box.headingDeg))
        }
        if (!busUsed) bus?.let { if (it.enabled) it.enabled = false }
        val it = actorInstances.entries.iterator()
        while (it.hasNext()) {
            val (id, e) = it.next()
            if (id in used) continue
            e.enabled = false
            val role = e.getName().removePrefix("actor:")
            instancePool.getOrPut(role) { ArrayList() } += e
            it.remove()
        }
    }

    private fun updateGhost(now: Long) {
        val g = ghost ?: return
        val pose = c.ghost()
        if (pose == null) {
            if (g.enabled) g.enabled = false
            return
        }
        if (!g.enabled) g.enabled = true
        val (gp, red) = pose
        g.setLocal(position = gp.position, rotation = Quat.yaw(gp.headingDeg))
        val flash = red && (now / 250) % 2 == 0L
        val want = if (flash) ghostRed else ghostMat
        for (p in ghostParts) want?.let { p.setMaterial(it) }
    }

    private fun updateGaze(now: Long) {
        val dot = gazeDot ?: return
        val ray = c.gazeDot()
        if (ray == null) {
            if (dot.enabled) dot.enabled = false
            trail.forEach { if (it.enabled) it.enabled = false }
            trailPts.clear()
            return
        }
        val cabin = c.displayScenario().cabin
        val p = Layout.gazePoint(cabin, ray.origin, ray.dir)
        val eye = c.effHead?.pos ?: Vec3.ZERO
        val k = 0.03 * (p - eye).length / 1.0
        if (!dot.enabled) dot.enabled = true
        dot.setLocal(position = p, rotation = Layout.facing(p, eye), scale = Vec3(k.coerceIn(0.03, 0.18), k.coerceIn(0.03, 0.18), 1.0))
        trailPts.addLast(now to p)
        while (trailPts.isNotEmpty() && now - trailPts.first().first > 600) trailPts.removeFirst()
        for ((i, e) in trail.withIndex()) {
            val target = now - (i + 1) * 100L
            val pt = trailPts.lastOrNull { it.first <= target }?.second
            if (pt == null) {
                if (e.enabled) e.enabled = false
                continue
            }
            if (!e.enabled) e.enabled = true
            val kk = (k * (1.0 - (i + 1) * 0.13)).coerceIn(0.012, 0.15)
            e.setLocal(position = pt, rotation = Layout.facing(pt, eye), scale = Vec3(kk, kk, 1.0))
        }
    }

    private fun updateGuides(now: Long) {
        val targets = c.guideTargets()
        val eye = c.effHead?.pos ?: Vec3.ZERO
        val pulse = 1.0 + 0.15 * sin(now / 1000.0 * 2 * Math.PI * 1.2)
        for ((i, g) in guides.withIndex()) {
            val t = targets.getOrNull(i)
            if (t == null) {
                if (g.enabled) g.enabled = false
                continue
            }
            if (!g.enabled) g.enabled = true
            val dist = (t - eye).length
            val size = (0.10 * dist / 0.6).coerceIn(0.08, 1.4) * pulse
            g.setLocal(position = t, rotation = Layout.facing(t, eye), scale = Vec3(size, size, 1.0))
        }
        val h = hand ?: return
        val hp = c.demoHand()
        if (hp == null) {
            if (h.enabled) h.enabled = false
        } else {
            if (!h.enabled) h.enabled = true
            h.setLocal(position = hp, rotation = Layout.rotY(if (c.displayScenario().cabin.doorSide == Dir.LEFT) 60.0 else -60.0))
        }
    }

    private fun updateAlert(hp: Pose?, now: Long) {
        val a = alert ?: return
        val on = now < c.alertUntilMs && hp != null
        if (!on) {
            if (a.enabled) a.enabled = false
            return
        }
        if (!a.enabled) a.enabled = true
        a.setLocal(position = hp!!.position, rotation = hp.rotation)
        // two pulses in 1.2 s
        val u = ((now - c.alertStartMs) / 600.0) % 1.0
        alertMat?.setOpacity((0.55 * sin(u * Math.PI)).toFloat().coerceIn(0f, 1f))
    }

    private fun updateArcs() {
        val arcs = c.arcs().toMap()
        val y = -0.14
        for ((d, dots) in arcDots) {
            val deg = arcs[d] ?: 0.0
            for ((i, e) in dots.withIndex()) {
                val a = deg * (i + 1) / dots.size
                val on = deg > 2.0
                if (!on) {
                    if (e.enabled) e.enabled = false
                    continue
                }
                if (!e.enabled) e.enabled = true
                val dir = Angles.dir(d.sign * a)
                e.setLocal(position = Vec3(dir.x * 0.42, y, dir.z * 0.42), scale = Vec3(0.008, 0.008, 0.008))
            }
        }
        if (replayDots.isEmpty()) return
        val trailDirs = c.replayTrail()
        val step = maxOf(1, trailDirs.size / replayDots.size + 1)
        val shown = trailDirs.filterIndexed { i, _ -> i % step == 0 }.takeLast(replayDots.size)
        for ((i, e) in replayDots.withIndex()) {
            val d = shown.getOrNull(i)
            if (d == null) {
                if (e.enabled) e.enabled = false
                continue
            }
            if (!e.enabled) e.enabled = true
            val p = Angles.dir(d.first, d.second) * 1.2
            val k = 0.008 + 0.014 * (i + 1) / shown.size
            e.setLocal(position = p, scale = Vec3(k, k, k))
        }
    }

    private var testShown = false

    private fun updateTestTex() {
        if (c.testTex == testShown) return
        testShown = c.testTex
        val road = c.displayScenario().road
        roadMat?.setBaseColorTexture(if (testShown) testTexture ?: roadTexture(road) else roadTexture(road))
        testTexture?.let { testMat?.setBaseColorTexture(it) }
        testRoadCard?.let {
            it.enabled = testShown
            it.setLocal(position = Vec3(-1.25, Road.ROAD_Y + 0.01, -4.0), scale = Vec3(2.5, 1.0, 2.5))
        }
        testQuad?.let {
            it.enabled = testShown
            it.setLocal(position = Vec3(0.0, 0.25, -3.0), rotation = Layout.facing(Vec3(0.0, 0.25, -3.0), Vec3.ZERO), scale = Vec3(0.8, 0.8, 1.0))
        }
        ScLog.i("TESTTEX on=$testShown (road + upright card 3 m ahead)")
    }

    // ---------------------------------------------------------------- panels

    private fun updatePanels(now: Long) {
        val pool = pool ?: return
        val s = c.state.value
        val base = s.base
        val seat = seat()
        val side = c.progress.settings.traffic
        val eye = c.effHead?.pos ?: Vec3.ZERO
        val p1Visible = base in P1_STATES || (base == Base.S13)
        val tall = s.page == P1Page.RESULT || s.page == P1Page.SCORECARD
        val p1 = if (tall) Layout.p1Tall(seat, side) else Layout.p1(seat, side)
        val p2 = Layout.p2(seat, side)
        fun showFacing(id: String, place: io.embodit.shouldercheck.domain.PanelPlace) {
            // yaw-only, upright (placeUpright): no pitch towards the eye
            pool[id]?.place(place.centre, place.rotation, place.scale)
        }
        if (p1Visible && !tall) showFacing(PanelIds.P1, p1) else pool[PanelIds.P1]?.hide()
        if (p1Visible && tall) showFacing(PanelIds.P1_TALL, p1) else pool[PanelIds.P1_TALL]?.hide()
        val p2Visible = (base.practising || base == Base.S11) && !p1Visible
        if (p2Visible) showFacing(PanelIds.P2, p2) else pool[PanelIds.P2]?.hide()
        val angVisible = base == Base.S01B || base.practising
        for ((id, d) in listOf(PanelIds.ANG_L to Dir.LEFT, PanelIds.ANG_R to Dir.RIGHT)) {
            if (!angVisible) {
                pool[id]?.hide()
                continue
            }
            val pl = Layout.ang(seat, side, d)
            pool[id]?.place(pl.centre, pl.rotation, pl.scale)
        }
        val cabin = Cabin(seat, side)
        val mirrorsOn = seat == Seat.DRIVER && base != Base.S00
        for (id in MirrorId.values()) {
            val slot = pool[PanelIds.mirrorPanel(id)] ?: continue
            val m = cabin.mirror(id)
            if (!mirrorsOn || m == null) {
                slot.hide()
                continue
            }
            slot.place(m.pos, Layout.facing(m.pos, Vec3.ZERO), PanelIds.MIRROR_SCALE)
        }
        // D20: cabin parts inside the eye → P1 / P2 sight cone fade to 25 %
        if (now - lastFadeMs > 200) {
            lastFadeMs = now
            val shownSlots = listOf(PanelIds.P1, PanelIds.P1_TALL, PanelIds.P2).mapNotNull { pool[it] }.filter { it.shown }
            val parts = cabinParts[seat] ?: emptyList()
            for ((e, part) in parts) {
                if (part.mat == PartMat.HOOD) continue
                val inCone = shownSlots.any { sl -> Layout.inSightCone(part, eye, sl.position, sl.rotation, sl.widthM, sl.heightM) }
                if (inCone && e !in faded) {
                    e.setFade(0.25f)
                    faded += e
                    ScLog.i("FADE ${part.name} 25% (D20)")
                } else if (!inCone && e in faded) {
                    e.setFade(1f)
                    faded -= e
                }
            }
        }
    }

    // ---------------------------------------------------------------- mirrors

    private fun updateMirrors(now: Long) {
        val seat = seat()
        if (seat != Seat.DRIVER) return
        val interval = (1000.0 / c.mirrorHz).toLong()
        if (now - lastMirrorMs < interval) return
        lastMirrorMs = now
        val sc = c.displayScenario()
        val cabin = sc.cabin
        val eye = c.effHead?.pos ?: Vec3.ZERO
        val aims = c.aims()
        val boxes = c.actorBoxes()
        val hl = c.mirrorHighlight()
        val hood = argbColor(c.hoodColour)
        for (m in cabin.mirrors) {
            if (panelShown[PanelIds.mirrorPanel(m.id)] != true) continue
            MirrorStore.frames.getValue(m.id).value = MirrorFrames.build(m, cabin, sc.road, eye, aims.of(m.id), boxes, hl, hood)
        }
        mirrorFrames++
        if (mirrorWindowMs == 0L) mirrorWindowMs = now
        if (now - mirrorWindowMs >= 5000) {
            val secs = (now - mirrorWindowMs) / 1000.0
            val avgFrameMs = if (frameCount > 0) frameTimeAcc / frameCount * 1000 else 0.0
            ScLog.i(String.format(Locale.US, "N3 mirrors target=%.0fHz built=%.1f/s canvasDraws=%.1f/s frame=%.1fms", c.mirrorHz, mirrorFrames / secs, MirrorStore.draws / secs, avgFrameMs))
            mirrorFrames = 0
            MirrorStore.draws = 0
            mirrorWindowMs = now
            frameTimeAcc = 0.0
            frameCount = 0
        }
    }

    // ---------------------------------------------------------------- logs / dump

    private fun startupLog(hp: Pose?, now: Long) {
        val t = now - attachMs
        val due = when (startupLogs) {
            0 -> 1000L
            1 -> 3000L
            2 -> 5000L
            else -> return
        }
        if (t < due) return
        startupLogs++
        ScLog.i("STARTUP ${due / 1000}s base=${c.state.value.base.code} head=${hp?.let { ScLog.pos(it.position) }} car=${car?.let { ScLog.pos(it.position) }} carYaw=${car?.let { "%.1f".format(Locale.US, Angles.yawOfRotation(it.rotation)) }} eye=${eyes.supportState()} ctrl=${controllers.status()} hands=${hands.supportState()}")
        for (s in pool?.all ?: emptyList()) {
            val w = s.enginePosition()
            val rel = if (w != null && hp != null) {
                val d = w - hp.position
                String.format(Locale.US, "dist=%.2f yaw=%.1f pitch=%.1f off=%.1f", d.length, Angles.yawOf(d), Angles.pitchOf(d), Angles.between(d, hp.forward))
            } else "dist=?"
            ScLog.i("STARTUP ${due / 1000}s ${s.id} shown=${s.shown} visible=${panelShown[s.id] == true} rest=${s.rest} world=${ScLog.pos(w)} $rel")
        }
        ScLog.i("STARTUP ${due / 1000}s N4 eye supportState=${eyes.supportState()} reason=${eyes.unavailableReason(now)}")
    }

    /** `sceneinfo instances`: every 3D entity with its model, world height, opacity component and blending (ui-audit e). */
    private fun instancesDump(): String {
        val root = stageRoot ?: return "no scene"
        val lines = ArrayList<String>()
        fun opacityUp(e: Entity): Boolean {
            var x: Entity? = e
            while (x != null) {
                if (x.hasOpacityComponent()) return true
                x = runCatching { x!!.getParent() }.getOrNull()
            }
            return false
        }
        fun blend(e: Entity): Set<String> {
            val out = HashSet<String>()
            fun walk(x: Entity) {
                x.components[com.pico.spatial.core.ecs.ModelComponent::class.java]?.materials?.forEach { mt ->
                    out += when (mt) {
                        is UnlitMaterial -> mt.getBlendingMode().name
                        is com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial -> mt.getBlendingMode().name
                        else -> mt.javaClass.simpleName
                    }
                }
                if (x.hasOpacityComponent()) out += "OPACITY_COMPONENT"
                runCatching { x.getChildren() }.getOrNull()?.forEach { walk(it) }
            }
            walk(e)
            return out
        }
        fun height(e: Entity): Double = runCatching { e.getVisualBounds(root, true, false) }.getOrNull()?.takeIf { !it.isEmpty() }?.size?.y?.toDouble() ?: 0.0
        fun visit(e: Entity) {
            val name = runCatching { e.getName() }.getOrNull() ?: ""
            if (name.startsWith("actor:")) {
                val role = name.removePrefix("actor:")
                val scale = models?.calibrations?.get(role)?.scale ?: 0.0
                lines += String.format(Locale.US, "INSTANCE glb model=%s.glb name=%s enabled=%s height=%.3f calibScale=%.4f opacityComponent=%s blending=%s", role, name, e.enabled, height(e), scale, opacityUp(e), blend(e))
                return
            }
            if (e.components[com.pico.spatial.core.ecs.ModelComponent::class.java] != null) {
                lines += String.format(Locale.US, "INSTANCE mesh name=%s enabled=%s height=%.3f opacityComponent=%s blending=%s", name, e.enabled, height(e), opacityUp(e), blend(e))
            }
            runCatching { e.getChildren() }.getOrNull()?.forEach { visit(it) }
        }
        visit(root)
        for (pool in instancePool.values) for (e in pool) if (e.getParent() == null) visit(e)
        lines.forEach { ScLog.i(it) }
        return "instances=${lines.size} (see INSTANCE lines)"
    }

    private fun dump(what: String): String =
        when (what) {
            "instances" -> instancesDump()
            "panels" -> (pool?.dump() ?: emptyList()).joinToString("\n").also { it.lines().forEach { l -> ScLog.i(l) } }
            "pose" -> {
                val hp = head.pose()
                "head=${hp?.let { ScLog.pos(it.position) + " " + ScLog.quat(it.rotation) }} car=${car?.let { ScLog.pos(it.position) + " yaw=" + Angles.yawOfRotation(it.rotation) }} inCar=${c.effHead}"
            }
            else -> "frames=$frame street=$streetKey cabin=${cabinSide} seat=${seat()} actors=${actorInstances.keys} faded=${faded.size} work=${heavy.size}+${light.size} models=${listOf("sedan", "suv", "ebike", "bike", "moto").map { it to (models?.loaded(it) == true) }}"
        }

    companion object {
        private const val FAST_FRAME_SEC = 0.04f
        private const val SLOW_GAP_MS = 1200L
        private const val SKY_D = 900.0
        private const val ROAD_HALF = 162.0
        private const val SUN_LUX = 2600f
        private const val FILL_LUX = 700f
        private val P1_STATES = setOf(Base.S00, Base.S01, Base.S01B, Base.S02, Base.S03, Base.S10, Base.S12, Base.S14, Base.S15, Base.S16, Base.S19)
    }
}
