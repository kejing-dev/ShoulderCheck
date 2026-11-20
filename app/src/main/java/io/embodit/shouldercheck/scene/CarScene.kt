package io.embodit.shouldercheck.scene

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat as SdkQuat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.shouldercheck.app.AppController
import io.embodit.shouldercheck.app.CtrlIn
import io.embodit.shouldercheck.app.DebugCommands
import io.embodit.shouldercheck.app.FrameInput
import io.embodit.shouldercheck.app.HeadIn
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Cabin
import io.embodit.shouldercheck.domain.CabinLayout
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.DoorTargets
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.HudLayout
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.MirrorAims
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
import io.embodit.shouldercheck.domain.StripType
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.hotspotBox
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.input.ControllerInput
import io.embodit.shouldercheck.platform.tracking.EyeTracker
import io.embodit.shouldercheck.platform.tracking.HeadTracker
import io.embodit.shouldercheck.platform.tracking.TrackerForce
import io.embodit.shouldercheck.ui.components.localizedResources
import io.embodit.shouldercheck.ui.theme.ScColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
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

/** Pooled panel ids and sizes (dp): only the three mirrors are attachment panels (design `container.notes`). */
object PanelIds {
    const val MIR_I = "mirI"
    const val MIR_L = "mirL"
    const val MIR_R = "mirR"

    val SIZES: Map<String, Pair<Int, Int>> = linkedMapOf(MIR_I to (520 to 140), MIR_L to (360 to 220), MIR_R to (360 to 220))

    /** Mirror panels: 1 dp = 0.5 mm, so the glass is exactly the design size (MIR-I 0.26 × 0.07, sides 0.18 × 0.11). */
    const val MIRROR_SCALE = 0.625

    fun mirrorPanel(id: MirrorId): String = when (id) {
        MirrorId.INNER -> MIR_I
        MirrorId.LEFT -> MIR_L
        MirrorId.RIGHT -> MIR_R
    }
}

/**
 * The car and the street (design `assets`, `model`): programmatic driver's cabin (doors on hinges, mirror housings, hood),
 * the street of the round's situation (textured road, kerb, sidewalk, bike lane, Tripo trees, building blocks), sky dome;
 * GLB road users; the 3D HUD ([Hud3D]); the three mirror pictures (~20 Hz). Resource creation is queued (≤ 2 per frame
 * while frames are fast: design `container.lifecycle`).
 *
 * Car frame: the car root sits at E0 with yaw only ([Placement.seatFrame], D9), placed automatically once the head pose
 * is valid and steady (design `container.placement`); every car-fixed entity is its child. Your car "moving" is the
 * street and the traffic shifting the other way (sideways move, pull-out) and the road scrolling at 36 km/h while
 * cruising (comfort mode: no scroll).
 */
class CarScene(private val context: Context) {
    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)
    private val c = AppController

    private val head = HeadTracker()
    private val eyes = EyeTracker()
    private val controllers = ControllerInput()

    private var scope: CoroutineScope? = null
    private var attachments: SpatialViewAttachments? = null
    private var stageRoot: Entity? = null
    private var carRoot: Entity? = null
    private var worldRoot: Entity? = null
    private var pool: PanelPool? = null
    private val shared = SharedResources()
    private var models: Models? = null
    private var hud: Hud3D? = null
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
    private val mats = HashMap<String, Material>()
    private var roadMat: UnlitMaterial? = null
    private var skyMat: UnlitMaterial? = null
    private var skyDay: TextureResource? = null
    private var skyDusk: TextureResource? = null
    private val roadTextures = HashMap<String, TextureResource>()
    private var physicsMat: PhysicsMaterialResource? = null

    // groups
    private var cabinGroup: Entity? = null
    private var cabinParts: List<Pair<Entity, Part>> = emptyList()
    private var doorPivot: Pair<Entity, Dir>? = null
    private var doorHolder: Entity? = null
    private var doorShape: ShapeResource? = null
    private var doorTapOn = false
    private var cabinSide: TrafficSide? = null
    private var streetGroup: Entity? = null
    private var streetMover: Entity? = null
    private var streetKey: String? = null
    private val trees = ArrayList<Entity>()
    private val lamps = ArrayList<Entity>()
    private var scroll = 0.0

    // dynamic
    private val actorInstances = HashMap<String, Entity>()
    private val instancePool = HashMap<String, ArrayList<Entity>>()
    private val replayDots = ArrayList<Entity>()
    private var lightEntity: Entity? = null
    private var fillEntity: Entity? = null
    private var duskOn: Boolean? = null

    // placement
    private var car: Pose? = null
    private var appliedEpoch = -1
    private var settleStartMs = -1L
    private var settleYaw = 0.0
    private var firstValidMs = -1L
    private var startupLogs = 0

    // mirrors
    private var lastMirrorMs = 0L
    private var mirrorFrames = 0
    private var mirrorWindowMs = 0L
    private var frameTimeAcc = 0.0
    private var frameCount = 0

    // controller-ray fallback
    private val triggerDown = HashMap<Hand, Boolean>()

    private var resources: Resources = context.resources
    private var resLang: Any? = null

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
        // light: the sun 45° from the car's right front, a weak fill from the left back (design assets.lighting)
        lightEntity = Entity().named("sun").also {
            carE.addChild(it)
            it.setLocal(rotation = Layout.facing(Vec3.ZERO, Vec3(0.6, 0.75, -0.6)))
        }
        fillEntity = Entity().named("fill").also {
            carE.addChild(it)
            it.setLocal(rotation = Layout.facing(Vec3.ZERO, Vec3(-0.5, 0.5, 0.7)))
        }
        setLights(false)
        head.start()
        eyes.start()
        controllers.start()
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
        pool?.release()
        pool = null
        panelShown.clear()
        hud?.close()
        hud = null
        runCatching { stageRoot?.destroy(true) }
        stageRoot = null
        carRoot = null
        models?.close()
        models = null
        doorHolder = null
        doorShape = null
        physicsMat = null
        trees.clear()
        lamps.clear()
        shared.close()
        ScLog.i("SCENE detached (trackers stopped)")
    }

    fun onBackground() {
        paused = true
        head.stop()
        eyes.stop()
        controllers.stop()
        ScLog.i("SCENE paused")
    }

    fun onForeground() {
        if (!attached) return
        head.start()
        eyes.start()
        controllers.start()
        paused = false
        ScLog.i("SCENE resumed")
    }

    private fun setLights(dusk: Boolean) {
        if (duskOn == dusk) return
        duskOn = dusk
        // lux scale (factory finding 2026-11-12); dusk: ambient −60 %, warm low sun (design scoring.levels[3].env)
        lightEntity?.components?.set(DirectionalLightComponent(if (dusk) Color4(1f, 0.78f, 0.6f, 1f) else Color4(1f, 0.97f, 0.92f, 1f), if (dusk) SUN_LUX * 0.4f else SUN_LUX, false))
        fillEntity?.components?.set(DirectionalLightComponent(Color4(0.85f, 0.9f, 1f, 1f), if (dusk) FILL_LUX * 0.4f else FILL_LUX, false))
        val tex = if (dusk) skyDusk else skyDay
        if (tex != null) skyMat?.setBaseColorTexture(tex)
        for (l in lamps) l.enabled = dusk
        // unlit street surfaces do not see the lights: dim them with the dusk (road, blocks, far carriageway)
        val k = if (dusk) DUSK_DIM else 1f
        roadMat?.setBaseColor(Color4(k, k, k, 1f))
        (mats["building"] as? UnlitMaterial)?.setBaseColor(Color4(k, k, k, 1f))
        (mats["buildingFar"] as? UnlitMaterial)?.setBaseColor(ScColors.BuildingFar.shade(0.95f * k).color4())
        (mats["asphalt"] as? UnlitMaterial)?.setBaseColor(ScColors.Road.shade(0.9f * k).color4())
        ScLog.i("LIGHT dusk=$dusk")
    }

    // ======================================================================================== build

    private fun mat(key: String): Material = mats.getValue(key)

    private fun physics(): PhysicsMaterialResource = physicsMat ?: shared.keep(PhysicsMaterialResource()).also { physicsMat = it }

    private fun queueBuild() {
        heavy += "cube" to { cube = shared.keep(Meshes.unitCube()) }
        heavy += "ball" to { ball = shared.keep(Meshes.sphere("ball")) }
        heavy += "sky" to {
            skyDay = shared.keep(TextureResource.create(Textures.sky()))
            skyDusk = shared.keep(TextureResource.create(Textures.duskSky()))
            val m = unlit(shared, Color.White, texture = skyDay, twoSided = true)
            skyMat = m
            val dome = ModelEntity(shared.keep(Meshes.sphere("skyDome", inward = true, rings = 18, sectors = 24)), m).named("skyDome")
            dome.setLocal(position = Vec3(0.0, 0.0, 0.0), scale = Vec3(SKY_D, SKY_D, SKY_D))
            carRoot?.addChild(dome)
        }
        heavy += "roadMesh" to { roadMesh = shared.keep(Meshes.road("road", -ROAD_HALF, ROAD_HALF, Textures.DASH_CYCLE_M)) }
        light += "materials" to { makeMaterials() }
        heavy += "roadTexture" to { roadTexture(c.displayRound().road) }
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
        light += "hud" to {
            val car = carRoot
            val world = worldRoot
            if (car != null && world != null) hud = Hud3D(shared, car, world, physics())
        }
        light += "dynamic" to { makeDynamic() }
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
        mats["fence"] = pbr(shared, ScColors.Kerb.shade(0.9f), roughness = 0.5f)
        mats["line"] = unlit(shared, ScColors.LaneLine)
        val tex = shared.keep(TextureResource.create(Textures.gradient(ScColors.BuildingNear, ScColors.BuildingFar)))
        mats["building"] = unlit(shared, Color.White, texture = tex)
        mats["buildingFar"] = unlit(shared, ScColors.BuildingFar.shade(0.95f), texture = tex)
        mats["trace"] = unlit(shared, ScColors.Gaze.shade(0.9f))
        val glow = shared.keep(TextureResource.create(Textures.radial(64)))
        mats["lamp"] = unlit(shared, ScColors.StreetLight, BlendingMode.TRANSPARENT, opacity = 0.95f, twoSided = true, depthWrite = false, texture = glow)
        roadMat = unlit(shared, Color.White)
    }

    private fun roadKey(road: RoadLayout): String = "${road.situation.family}:${road.side}"

    private fun roadTexture(road: RoadLayout): TextureResource {
        val key = roadKey(road)
        roadTextures[key]?.let { return it }
        val (x0, x1) = roadExtent(road)
        val t = shared.keep(TextureResource.create(Textures.road(road, x0, x1)))
        roadTextures[key] = t
        return t
    }

    private fun roadExtent(road: RoadLayout): Pair<Double, Double> {
        val drive = road.strips.filter { it.type != StripType.SIDEWALK }
        return drive.minOf { it.xMin } to drive.maxOf { it.xMax }
    }

    private fun model(mesh: MeshResource, material: Material, name: String, parent: Entity?): ModelEntity = ModelEntity(mesh, material).named(name).also { parent?.addChild(it) } as ModelEntity

    /** Unit-cube slab of [size] at [centre] (road furniture, building blocks, cabin trim). */
    private fun slab(parent: Entity?, name: String, centre: Vec3, size: Vec3, material: Material, rotation: Quat = Quat.IDENTITY): Entity =
        model(cube!!, material, name, parent).also { it.setLocal(position = centre, rotation = rotation, scale = size) }

    // ---------------------------------------------------------------- cabin

    private fun ensureCabin() {
        val side = c.progress.settings.traffic
        if (cabinSide == side && cabinGroup != null) return
        if (cube == null || ringMesh == null || mats.isEmpty()) return
        cabinGroup?.let { runCatching { it.destroy(true) } }
        doorTapOn = false
        val layout = Layout.cabin(Seat.DRIVER, side)
        val group = Entity().named("cabin")
        carRoot?.addChild(group)
        cabinGroup = group
        cabinParts = buildCabin(group, layout)
        cabinSide = side
        ScLog.i("CABIN built side=$side parts=${cabinParts.size}")
    }

    private fun buildCabin(group: Entity, layout: CabinLayout): List<Pair<Entity, Part>> {
        val out = ArrayList<Pair<Entity, Part>>()
        for (p in layout.parts) {
            val mesh = if (p.mesh == PartMesh.RING) ringMesh!! else cube!!
            val e = model(mesh, mat(p.mat.name), p.name, group)
            e.setLocal(position = p.centre, rotation = p.rotation, scale = p.size)
            out += e to p
        }
        val tapBox = DoorTargets.box(layout)
        for (d in layout.doors) {
            val pivot = Entity().named(d.name)
            group.addChild(pivot)
            pivot.setLocal(position = d.hinge)
            // the opening door hangs under a tap holder named door:handle, centred on its click box, so the system hover
            // lights the door's own meshes and a hit on any of them resolves up to the holder (D50)
            val box = if (d.opens) tapBox else null
            val centre = box?.let { (it.min + it.max) * 0.5 } ?: d.hinge
            val parent =
                if (box != null) {
                    Entity().named(Target.DOOR.id).also {
                        pivot.addChild(it)
                        it.setLocal(position = centre - d.hinge)
                        doorHolder = it
                        // D59: cached across frames → global
                        doorShape = shared.keep(ShapeResource.createBox((box.max - box.min).sdk()))
                    }
                } else pivot
            for (p in d.parts) {
                val e = model(cube!!, mat(p.mat.name), if (box != null) "${Target.DOOR.id}/${p.name}" else p.name, parent)
                e.setLocal(position = p.centre - centre, rotation = p.rotation, scale = p.size)
                out += e to p
            }
            if (d.opens) doorPivot = pivot to d.side
        }
        return out
    }

    // ---------------------------------------------------------------- street

    private fun ensureStreet() {
        if (cube == null || roadMesh == null || mats.isEmpty()) return
        val r = c.displayRound()
        val key = roadKey(r.road)
        if (key == streetKey) return
        streetGroup?.let { runCatching { it.destroy(true) } }
        trees.clear()
        lamps.clear()
        val g = Entity().named("street")
        carRoot?.addChild(g)
        streetGroup = g
        val mover = Entity().named("streetMover")
        g.addChild(mover)
        streetMover = mover
        streetKey = key
        buildStreet(mover, r.road)
        duskOn = null
        ScLog.i("STREET built $key")
    }

    private fun buildStreet(g: Entity, road: RoadLayout) {
        val y = Road.ROAD_Y
        val (x0, x1) = roadExtent(road)
        val mat = roadMat!!
        mat.setBaseColorTexture(roadTexture(road))
        model(roadMesh!!, mat, "road", g).setLocal(position = Vec3(x0, y, 0.0), scale = Vec3(x1 - x0, 1.0, 1.0))
        val kerbSide = if (road.kerbX > road.egoX) 1.0 else -1.0
        val farEdge = if (kerbSide > 0) x0 else x1
        val len = 2 * ROAD_HALF
        slab(g, "kerb", Vec3(road.kerbX + kerbSide * 0.1, y + 0.075, 0.0), Vec3(0.2, 0.15, len), mat("kerb"))
        slab(g, "sidewalk", Vec3(road.kerbX + kerbSide * 2.1, y + 0.07, 0.0), Vec3(3.8, 0.14, len), mat("sidewalk"))
        // far side: median fence, opposite carriageway, far sidewalk
        slab(g, "fence", Vec3(farEdge - kerbSide * 0.3, y + 0.5, 0.0), Vec3(0.08, 1.0, len), mat("fence"))
        slab(g, "fenceBase", Vec3(farEdge - kerbSide * 0.3, y + 0.05, 0.0), Vec3(0.35, 0.1, len), mat("kerb"))
        slab(g, "opposite", Vec3(farEdge - kerbSide * 5.6, y + 0.002, 0.0), Vec3(10.0, 0.004, len), mat("asphalt"))
        slab(g, "farSidewalk", Vec3(farEdge - kerbSide * 12.6, y + 0.07, 0.0), Vec3(4.0, 0.14, len), mat("sidewalk"))
        // trees (Tripo, B12) on the sidewalk every 18 m and street lamps every 36 m: both repeat every 36 m, so the road
        // scroll wraps every 36 m without a jump
        var z = -TREE_RANGE
        while (z <= TREE_RANGE + 1e-6) {
            val tx = road.kerbX + kerbSide * 1.3
            val holder = Entity().named("treeSpot")
            g.addChild(holder)
            holder.setLocal(position = Vec3(tx, y + 0.14, z), rotation = Quat.yaw((z * 37) % 360))
            trees += holder
            if (abs(z % 36.0) < 1e-6) {
                val lamp = model(discMesh ?: cube!!, mat("lamp"), "lamp", g)
                lamp.setLocal(position = Vec3(road.kerbX + kerbSide * 0.6, y + 5.0, z + 9.0), rotation = Layout.rotX(-90.0), scale = Vec3(1.2, 1.2, 1.0))
                lamp.components.set(PointLightComponent(Color4(1f, 0.85f, 0.62f, 1f), LAMP_LUX, 14f))
                lamp.enabled = false
                lamps += lamp
            }
            z += 18.0
        }
        attachTrees()
        // building blocks behind both sidewalks: 33.5 m long + 2.5 m gap = the 36 m period
        var bz = -144.0
        var i = 0
        while (bz < 144.0) {
            val h = 9.0 + (i * 11) % 16
            val zc = bz + 33.5 / 2
            slab(g, "building", Vec3(road.kerbX + kerbSide * (4.2 + 7.0), y + h / 2, zc), Vec3(14.0, h, 33.5), mat("building"))
            slab(g, "buildingFar", Vec3(farEdge - kerbSide * (14.8 + 7.0), y + h * 0.6, zc + 5), Vec3(14.0, h * 1.2, 33.5), mat("buildingFar"))
            bz += 36.0
            i++
        }
    }

    /** Puts a tree instance on every tree spot once the model is loaded. */
    private fun attachTrees() {
        val m = models ?: return
        if (!m.loaded("tree")) return
        for (spot in trees) {
            if (runCatching { spot.getChildren() }.getOrNull()?.isNotEmpty() == true) continue
            m.instance("tree")?.let { spot.addChild(it) }
        }
    }

    // ---------------------------------------------------------------- dynamic entities

    private fun makeDynamic() {
        val root = carRoot ?: return
        repeat(40) { replayDots += model(ball!!, mat("trace"), "trace$it", root).also { e -> e.enabled = false } }
    }

    private fun loadModels() {
        val m = models ?: return
        scope?.launch {
            for (role in listOf("sedan", "suv", "ebike", "bike", "moto", "tree")) m.load(role)
            attachTrees()
            ScLog.i("MODEL all loaded")
        }
    }

    private fun runWork(dt: Float, now: Long) {
        var budget = 3
        while (light.isNotEmpty() && budget-- > 0) {
            val (name, work) = light.first()
            val ready = when (name) {
                "street", "cabin" -> cube != null && roadMesh != null && mats.isNotEmpty() && (name != "cabin" || ringMesh != null)
                "dynamic" -> ball != null && mats.isNotEmpty()
                "ground", "materials" -> cube != null
                "hud" -> discMesh != null
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
        // D55: the system may drop the container to 10 fps; the round clock follows the wall clock up to 0.25 s a frame
        val dt = dtRaw.coerceAtMost(0.25f).toDouble()
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

        if (builtReported || cabinGroup != null) {
            ensureCabin()
            ensureStreet()
        }
        val lang = c.state.value.language
        if (lang != resLang) {
            resLang = lang
            resources = localizedResources(context, lang)
        }
        setLights(c.dusk())
        updateCabin()
        updateStreet(dt)
        updateActors()
        updateTrail()
        val headPos = c.effHead?.pos ?: Vec3.ZERO
        hud?.update(resources, c.state.value.hud, Cabin(Seat.DRIVER, c.progress.settings.traffic), headPos, c.effHead?.yaw ?: 0.0, now, frame)
        updatePanels()
        updateMirrors(now)
        startupLog(hp, now)
    }

    /**
     * Automatic seating (design `container.placement`): once the head pose is valid and has turned < 5° in 0.5 s (at
     * most 1.5 s after the first valid sample), the car is placed at the head position with its yaw (pitch / roll 0,
     * D9). 回正 places it again. Until seated the car keeps itself in front of you.
     */
    private fun placeCar(hp: Pose?, now: Long) {
        val pose = hp ?: return
        val root = carRoot ?: return
        val yaw = Angles.yawOfRotation(pose.rotation)
        var place = false
        var why = ""
        if (c.placeEpoch != appliedEpoch && c.seated) {
            place = true
            why = "recentre epoch=${c.placeEpoch}"
            appliedEpoch = c.placeEpoch
        } else if (!c.seated) {
            if (firstValidMs < 0) firstValidMs = now
            if (settleStartMs < 0 || abs(Angles.wrap(yaw - settleYaw)) > 5.0) {
                settleStartMs = now
                settleYaw = yaw
            }
            val steady = now - settleStartMs >= 500
            val timeout = now - firstValidMs >= 1500
            place = true
            why = "settling"
            if (steady || timeout) {
                why = if (steady) "steady 0.5 s" else "1.5 s timeout"
                c.onSeated(why)
                appliedEpoch = c.placeEpoch
            }
        }
        if (!place) return
        val seat = Placement.seatFrame(pose)
        car = seat
        root.setLocal(position = seat.position, rotation = seat.rotation)
        if (why != "settling") ScLog.i(String.format(Locale.US, "PLACE car=%s yaw=%.1f head=%s why=%s", ScLog.pos(seat.position), Angles.yawOfRotation(seat.rotation), ScLog.pos(pose.position), why))
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
        if (ctrl != null) {
            for (s in listOf(ctrl.first, ctrl.second)) {
                val pos = s.pose?.let { p -> carPose?.let { Placement.toCar(it, p.position) } }
                val aim = s.pose?.let { p -> carPose?.rotation?.conjugate()?.rotate(p.forward)?.normalized() }
                list += CtrlIn(s.hand, pos, s.triggerPressed, aim)
            }
        }
        controllerRayTaps(list)
        return FrameInput(
            nowMs = now,
            dt = dt,
            head = headIn,
            headLost = head.lost(now),
            blockReason = TrackerForce.blocked ?: head.blockReason(),
            gaze = gaze,
            eyeReason = eyes.unavailableReason(now),
            controllers = list,
            aPresses = controllers.takeA(),
            bPresses = controllers.takeB(),
            yPresses = controllers.takeY(),
            headPitchWorld = hp?.let { Angles.pitchOfRotation(it.rotation) },
        )
    }

    /**
     * Controller-ray fallback (D50, v0.1.1 door-input-rootcause.md): no factory headset log has yet shown the controller's
     * trigger reaching a SpatialView as a `kind=POINTER` tap in Full Space, so a trigger press whose aim ray (controller
     * −Z) crosses a tappable target's click box is a tap too. 800 ms dedupe with the system tap in [AppController.tap].
     * No ray is drawn (D41: the system draws its own).
     */
    private fun controllerRayTaps(list: List<CtrlIn>) {
        val side = c.progress.settings.traffic
        val cabin = Cabin(Seat.DRIVER, side)
        for (s in list) {
            val was = triggerDown[s.hand] == true
            triggerDown[s.hand] = s.trigger
            if (!s.trigger || was) continue
            val pos = s.pos ?: continue
            val aim = s.aim ?: continue
            val spots = HudLayout.hotspots(cabin, Layout.cabin(Seat.DRIVER, side)) + HudLayout.askHotspots(side)
            var best: Pair<Target, Double>? = null
            for (h in spots) {
                if (!c.tappable(h.target)) continue
                val d = DoorTargets.rayHit(pos, aim, hotspotBox(h)) ?: continue
                if (best == null || d < best.second) best = h.target to d
            }
            val hit = best ?: continue
            ScLog.i(String.format(Locale.US, "RAY hand=%s hit=%s dist=%.2f", s.hand, hit.first.id, hit.second))
            c.tap(hit.first, "controller-ray")
        }
    }

    /** Board-input hover (supplement to the system HoverEffect). */
    fun hover(t: Target?) {
        hud?.onHover(t, SystemClock.elapsedRealtime())
    }

    /** The real holder of a target (debug `tap` replays a hit on its deepest child through the board-input listener). */
    fun targetEntity(t: Target): Entity? = if (t == Target.DOOR) doorHolder else hud?.holderOf(t)

    private fun updateCabin() {
        doorPivot?.let { (pivot, side) -> pivot.setLocal(rotation = Layout.rotY(side.sign * c.doorAngle())) }
        val on = c.doorTappable()
        val holder = doorHolder ?: return
        val shape = doorShape ?: return
        if (doorTapOn == on) return
        doorTapOn = on
        if (on) {
            holder.components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = physics()))
            holder.components.set(InteractableComponent())
            holder.components.set(HoverEffectComponent())
        } else {
            holder.components.remove(HoverEffectComponent::class.java)
            holder.components.remove(InteractableComponent::class.java)
            holder.components.remove(CollisionComponent::class.java)
        }
        ScLog.i("DOOR tappable=$on")
    }

    /** The street slides back under your car: sideways move / pull-out offset plus the 36 km/h scroll (mod 36 m). */
    private fun updateStreet(dt: Double) {
        val mover = streetMover ?: return
        scroll = (scroll + c.scrollSpeed() * dt) % 36.0
        val (dx, dz) = c.egoOffset()
        mover.setLocal(position = Vec3(-dx, 0.0, -dz + scroll))
    }

    private fun updateActors() {
        val m = models
        val boxes = c.actorBoxes()
        val (dx, dz) = c.egoOffset()
        val used = HashSet<String>()
        for ((a, box) in boxes) {
            val role = a.kind.model
            var e = actorInstances[a.id]
            if (e != null && e.getName() != "actor:$role") {
                e.enabled = false
                instancePool.getOrPut(e.getName().removePrefix("actor:")) { ArrayList() } += e
                actorInstances.remove(a.id)
                e = null
            }
            if (e == null && m != null && m.loaded(role)) {
                val free = instancePool[role]?.removeLastOrNull()
                e = free ?: m.instance(role)?.also { inst -> carRoot?.addChild(inst); ScLog.i("MODEL instance $role for ${a.id} opacityComponent=${inst.hasOpacityComponent()}") }
                if (e != null) actorInstances[a.id] = e
            }
            if (e == null) continue
            used += a.id
            if (!e.enabled) e.enabled = true
            e.setLocal(position = box.centre - Vec3(dx, 0.0, dz), rotation = Quat.yaw(box.headingDeg))
        }
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

    /** Replay: where your eyes / head pointed (design S15: 「你的目光 / 头朝向轨迹（小点）」), 1.2 m out. */
    private fun updateTrail() {
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

    // ---------------------------------------------------------------- panels (the three mirrors)

    private fun updatePanels() {
        val pool = pool ?: return
        val cabin = Cabin(Seat.DRIVER, c.progress.settings.traffic)
        val mirrorsOn = c.seated
        for (id in MirrorId.values()) {
            val slot = pool[PanelIds.mirrorPanel(id)] ?: continue
            val m = cabin.mirror(id)
            if (!mirrorsOn || m == null) {
                slot.hide()
                continue
            }
            slot.place(m.pos, Layout.facing(m.pos, Vec3.ZERO), PanelIds.MIRROR_SCALE)
        }
    }

    private fun updateMirrors(now: Long) {
        val interval = (1000.0 / c.mirrorHz).toLong()
        if (now - lastMirrorMs < interval) return
        lastMirrorMs = now
        val r = c.displayRound()
        val cabin = r.cabin
        val eye = c.effHead?.pos ?: Vec3.ZERO
        val aims = MirrorAims()
        val (dx, dz) = c.egoOffset()
        val boxes = c.actorBoxes().map { (a, b) -> a to b.copy(centre = b.centre - Vec3(dx, 0.0, dz)) }
        val hl = c.highlightId()
        val hood = argbColor(c.hoodColour)
        val dash = ((scroll - dz) % 9.0 + 9.0) % 9.0
        for (m in cabin.mirrors) {
            if (panelShown[PanelIds.mirrorPanel(m.id)] != true) continue
            MirrorStore.frames.getValue(m.id).value = MirrorFrames.build(m, cabin, r.road, eye, aims.of(m.id), boxes, hl, hood, dx, dash)
        }
        mirrorFrames++
        if (mirrorWindowMs == 0L) mirrorWindowMs = now
        if (now - mirrorWindowMs >= 5000) {
            val secs = (now - mirrorWindowMs) / 1000.0
            val avgFrameMs = if (frameCount > 0) frameTimeAcc / frameCount * 1000 else 0.0
            ScLog.i(String.format(Locale.US, "PERF mirrors target=%.0fHz built=%.1f/s canvasDraws=%.1f/s frame=%.1fms fps=%.1f", c.mirrorHz, mirrorFrames / secs, MirrorStore.draws / secs, avgFrameMs, frameCount / secs))
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
        ScLog.i("STARTUP ${due / 1000}s screen=${c.state.value.screen.code} head=${hp?.let { ScLog.pos(it.position) }} car=${car?.let { ScLog.pos(it.position) }} carYaw=${car?.let { "%.1f".format(Locale.US, Angles.yawOfRotation(it.rotation)) }} eye=${eyes.supportState()} ctrl=${controllers.status()}")
        val g = hud?.holderOf(Target.GO)
        val w = runCatching { g?.convertPositionTo(Vector3.ZERO, stageRoot)?.domain() }.getOrNull()
        if (w != null && hp != null) {
            val d = w - hp.position
            ScLog.i(String.format(Locale.US, "STARTUP %ds btn:go world=%s dist=%.2f yaw=%.1f pitch=%.1f off=%.1f", due / 1000, ScLog.pos(w), d.length, Angles.yawOf(d), Angles.pitchOf(d), Angles.between(d, hp.forward)))
        }
    }

    /** `sceneinfo instances`: every 3D entity with its model, world height, opacity component and blending (ui-audit). */
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
            runCatching { e.getChildren() }.getOrNull()?.forEach { visit(it) }
        }
        visit(root)
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
            else -> "frames=$frame street=$streetKey cabin=$cabinSide actors=${actorInstances.keys} trees=${trees.size} work=${heavy.size}+${light.size} scroll=${"%.1f".format(Locale.US, scroll)} models=${listOf("sedan", "suv", "ebike", "bike", "moto", "tree").map { it to (models?.loaded(it) == true) }}"
        }

    companion object {
        private const val FAST_FRAME_SEC = 0.04f
        private const val SLOW_GAP_MS = 1200L
        private const val SKY_D = 900.0
        private const val ROAD_HALF = 162.0
        private const val TREE_RANGE = 72.0
        private const val SUN_LUX = 2600f
        private const val FILL_LUX = 700f
        private const val LAMP_LUX = 2500f
        private const val DUSK_DIM = 0.5f
    }
}
