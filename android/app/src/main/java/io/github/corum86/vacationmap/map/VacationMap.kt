package io.github.corum86.vacationmap.map

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.net.MapPlace
import io.github.corum86.vacationmap.net.PhotonService
import io.github.corum86.vacationmap.net.placeKindsForZoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Loading state of the selectable town names. */
enum class TownNamesStatus { Idle, Loading, Failed }

// wait for the map to settle (fit animation, a run of pans) before asking
private const val TOWN_LOAD_DEBOUNCE_MS = 350L

/**
 * The map: OpenStreetMap tiles with the home base, the destinations and
 * their connections drawn on top. Drag to pan, pinch or double-tap to zoom.
 *
 * Taps land on the topmost thing under the finger: a town name (when
 * `onSelectPlace` is set), a pin, or else the map itself.
 */
@Composable
fun VacationMap(
    data: VacationMapData,
    selectedDestinationId: String?,
    displayMode: RouteDisplayMode,
    state: MapState,
    tiles: TileProvider,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    onSelectDestination: (String) -> Unit = {},
    onHomeClick: () -> Unit = {},
    /** any tap on the map background (pins don't pass taps on to it) */
    onMapClick: ((LatLng) -> Unit)? = null,
    /** when set, city and town names in view are shown as selectable pills */
    onSelectPlace: ((MapPlace) -> Unit)? = null,
    photon: PhotonService? = null,
    lang: Lang = Lang.En,
    onTownNamesStatus: (TownNamesStatus) -> Unit = {},
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val routes = remember { RouteGeometryCache() }
    val scope = rememberCoroutineScope()
    var towns by remember { mutableStateOf(emptyList<MapPlace>()) }

    val scene = MapScene(
        home = data.mainLocation,
        destinations = data.destinations,
        selectedId = selectedDestinationId,
        displayMode = displayMode,
        showLabels = showLabels,
        towns = towns,
    )
    val currentScene by rememberUpdatedState(scene)
    val currentOnSelectDestination by rememberUpdatedState(onSelectDestination)
    val currentOnHomeClick by rememberUpdatedState(onHomeClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnSelectPlace by rememberUpdatedState(onSelectPlace)
    val currentOnTownNamesStatus by rememberUpdatedState(onTownNamesStatus)

    SideEffect { state.density = density.density }

    // ask for the tiles the view needs whenever it changes
    LaunchedEffect(state, tiles) {
        snapshotFlow { visibleTiles(state, state.size.toSize()) }.collect { tiles.request(it) }
    }

    // selectable town names for wherever the map is looking
    val townSource = photon?.takeIf { onSelectPlace != null }
    LaunchedEffect(state, townSource, lang) {
        val photon = townSource
        if (photon == null) {
            towns = emptyList()
            return@LaunchedEffect
        }
        fun refresh() {
            if (state.size == IntSize.Zero) return
            towns = density.visibleTowns(state, state.size.toSize(), photon.cachedPlaces(lang), placeKindsForZoom(state.zoom), measurer)
        }
        snapshotFlow { Triple(state.centerX, state.centerY, state.zoom) to state.size }.collectLatest {
            if (state.size == IntSize.Zero) return@collectLatest
            // let a drag or animation come to rest first
            delay(120)
            refresh()
            val center = state.center
            val radiusKm = state.viewRadiusKm()
            if (!photon.needsPlaces(center, radiusKm, state.zoom, lang)) {
                currentOnTownNamesStatus(TownNamesStatus.Idle)
                return@collectLatest
            }
            delay(TOWN_LOAD_DEBOUNCE_MS - 120)
            currentOnTownNamesStatus(TownNamesStatus.Loading)
            try {
                photon.loadPlaces(center, radiusKm, state.zoom, lang)
                currentOnTownNamesStatus(TownNamesStatus.Idle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentOnTownNamesStatus(TownNamesStatus.Failed)
            }
            // one of the requests may still have brought places
            refresh()
        }
    }

    var fling by remember { mutableStateOf<Job?>(null) }

    Box(modifier.clipToBounds().onSizeChanged { state.size = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    detectTapGestures(
                        onDoubleTap = { at ->
                            scope.launch { state.animateZoomBy(1.0, at) }
                        },
                        onTap = { at ->
                            when (val hit = hitTest(state, currentScene, at, this, measurer)) {
                                is MapHit.Town -> currentOnSelectPlace?.invoke(hit.place)
                                is MapHit.DestinationPin -> currentOnSelectDestination(hit.id)
                                MapHit.HomePin -> currentOnHomeClick()
                                is MapHit.Ground -> currentOnMapClick?.invoke(hit.location)
                            }
                        },
                    )
                }
                .pointerInput(state) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val tracker = VelocityTracker()
                        awaitFirstDown(requireUnconsumed = false)
                        fling?.cancel()
                        scope.launch { state.stopAnimation() }
                        var moving = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        var pinched = false
                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!moving) {
                                    zoom *= zoomChange
                                    pan += panChange
                                    val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                                    if (zoomMotion > slop || pan.getDistance() > slop) moving = true
                                }
                                if (moving) {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (panChange != Offset.Zero) state.panBy(panChange)
                                    if (zoomChange != 1f) state.zoomBy(zoomChange, centroid)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size == 1 && !pinched) {
                                    tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
                                } else if (pressed.size > 1) {
                                    // lifting two fingers one after the other must not fling the map away
                                    pinched = true
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })

                        if (moving && !pinched) {
                            val velocity = tracker.calculateVelocity()
                            val speed = Offset(velocity.x, velocity.y)
                            if (speed.getDistance() > 300f * density.density) {
                                fling = scope.launch {
                                    var last = Offset.Zero
                                    AnimationState(Offset.VectorConverter, Offset.Zero, speed)
                                        .animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                            state.panBy(value - last)
                                            last = value
                                        }
                                }
                            }
                        }
                    }
                },
        ) {
            // read so the map redraws when a tile arrives
            tiles.generation
            drawTiles(state, tiles)
            drawMapOverlay(state, currentScene, measurer, routes)
        }
    }
}

/** What a tap on the map landed on. */
internal sealed interface MapHit {
    data class Town(val place: MapPlace) : MapHit

    data class DestinationPin(val id: String) : MapHit

    data object HomePin : MapHit

    data class Ground(val location: LatLng) : MapHit
}

internal fun hitTest(state: MapState, scene: MapScene, at: Offset, density: Density, measurer: TextMeasurer): MapHit {
    val u = density.density
    // a finger-sized target around the small pill
    scene.towns
        .lastOrNull { density.townPillRect(state, it, measurer).first.inflate(7 * u).contains(at) }
        ?.let { return MapHit.Town(it) }
    // the topmost pin whose head is under the finger (heads are small: allow some slack)
    val pin = pinsOf(state, scene).lastOrNull { pin ->
        val reach = max(pin.kind.size / 2 + 6, 20f) * u
        (pinHeadCenter(pin, u) - at).getDistance() <= reach || (pin.tip - at).getDistance() <= 8 * u
    }
    if (pin != null) return if (pin.id == null) MapHit.HomePin else MapHit.DestinationPin(pin.id)
    return MapHit.Ground(state.fromScreen(at))
}

/** Fits the view to the given points once per `fitKey`, leaving room for overlaid UI. */
@Composable
fun FitToPoints(state: MapState, points: List<LatLng>, padding: MapPadding, fitKey: Any) {
    var fitted by remember(state) { mutableStateOf(false) }
    val laidOut = state.size != IntSize.Zero
    LaunchedEffect(state, fitKey, laidOut) {
        if (!laidOut || points.isEmpty()) return@LaunchedEffect
        // jump into place the first time, glide on later refits
        state.fitToPoints(points, padding, animate = fitted)
        fitted = true
    }
}

/** Pans just enough to keep `point` clear of overlaid UI whenever it changes. */
@Composable
fun KeepInView(state: MapState, point: LatLng?, padding: MapPadding) {
    LaunchedEffect(state, point) {
        if (point != null) state.panInside(point, padding)
    }
}

// where the map opens with no home base and no place to centre on
private val WORLD_CENTER = LatLng(30.0, 10.0)
private const val WORLD_ZOOM = 2.0

/** A camera that starts on the home base (or the first place, or else the whole world). */
@Composable
fun rememberMapState(data: VacationMapData): MapState = remember {
    val anchor = data.mainLocation?.location ?: data.destinations.firstOrNull()?.location
    if (anchor != null) MapState(anchor) else MapState(WORLD_CENTER, WORLD_ZOOM)
}
