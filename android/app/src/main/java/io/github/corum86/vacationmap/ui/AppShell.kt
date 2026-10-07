package io.github.corum86.vacationmap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.AppServices
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.data.SyncStatus
import io.github.corum86.vacationmap.data.saveImageToPictures
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.ExportOptions
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.exportAspectLabel
import io.github.corum86.vacationmap.logic.unscheduledDestinations
import io.github.corum86.vacationmap.map.exportLayoutSize
import io.github.corum86.vacationmap.map.renderMapImage
import io.github.corum86.vacationmap.model.PickedLocation
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.ConfirmDialog
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.ToastController
import io.github.corum86.vacationmap.ui.components.ToastView
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.net.DELAY_BETWEEN_DESTINATIONS_MS
import io.github.corum86.vacationmap.net.DestinationAiResult
import io.github.corum86.vacationmap.net.GeminiApiKeyMissingException
import io.github.corum86.vacationmap.ui.screens.AddToDaySheet
import io.github.corum86.vacationmap.ui.screens.AiResearchScreen
import io.github.corum86.vacationmap.ui.screens.AiSearchButton
import io.github.corum86.vacationmap.ui.screens.ConfirmRequest
import io.github.corum86.vacationmap.ui.screens.DestinationDetailScreen
import io.github.corum86.vacationmap.ui.screens.LocationFormKind
import io.github.corum86.vacationmap.ui.screens.LocationFormScreen
import io.github.corum86.vacationmap.ui.screens.MapScreen
import io.github.corum86.vacationmap.ui.screens.PickOnMapScreen
import io.github.corum86.vacationmap.ui.screens.PlacesScreen
import io.github.corum86.vacationmap.ui.screens.ResearchProgress
import io.github.corum86.vacationmap.ui.screens.ReplanSheet
import io.github.corum86.vacationmap.ui.screens.SettingsScreen
import io.github.corum86.vacationmap.ui.screens.SwapSheet
import io.github.corum86.vacationmap.ui.screens.TripDatesPicker
import io.github.corum86.vacationmap.ui.screens.TripScreen
import io.github.corum86.vacationmap.ui.theme.VacationMapTheme
import io.github.corum86.vacationmap.ui.theme.VmColors
import io.github.corum86.vacationmap.ui.wizard.TripWizard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val icon: String, val labelKey: String) {
    Map("map", "tabs.map"),
    Places("pin_drop", "tabs.places"),
    Trip("luggage", "tabs.trip"),
    Settings("settings", "tabs.settings"),
}

/**
 * Something open on top of the current tab. Sheets (and the dialogs a tab
 * opens itself) sit over the tab, bottom navigation included; everything
 * else is a full-screen layer that replaces the navigation.
 */
sealed interface Layer {
    val isSheet: Boolean get() = false

    data class Detail(val id: String) : Layer

    /** `id` null adds a destination */
    data class DestinationForm(val id: String?) : Layer

    data object HomeForm : Layer

    data object Pick : Layer

    data class ConfirmDelete(val id: String) : Layer

    data object Planner : Layer

    /** what the AI research of every destination found, to go through */
    class AiResearch(val results: List<DestinationAiResult>) : Layer

    class Confirm(val request: ConfirmRequest) : Layer {
        override val isSheet get() = true
    }

    data object TripDates : Layer {
        override val isSheet get() = true
    }

    data class AddToDay(val day: Int) : Layer {
        override val isSheet get() = true
    }

    data class Swap(val day: Int, val index: Int) : Layer {
        override val isSheet get() = true
    }

    data object Replan : Layer {
        override val isSheet get() = true
    }
}

/** The app: provides the services, language and theme, then shows the shell. */
@Composable
fun VacationMapRoot(services: AppServices) {
    val lang by services.language.lang.collectAsState()
    CompositionLocalProvider(LocalServices provides services, LocalTranslator provides remember(lang) { Translator(lang) }) {
        VacationMapTheme { AppShell() }
    }
}

@Composable
private fun StatusScreen(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxSize().background(VmColors.Bg).systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        VmText(text, size = 18.sp, weight = FontWeight.Medium, color = if (error) VmColors.Danger else VmColors.TextMuted)
    }
}

/**
 * Tab screens, bottom navigation and the stack of layers over them. The
 * system back gesture pops the top layer, like every close button does.
 */
@Composable
fun AppShell() {
    val t = LocalTranslator.current
    val services = LocalServices.current
    val store = services.store
    val state by store.state.collectAsState()
    val syncStatus by services.cloudSync.status.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.Map) }
    var displayMode by rememberSaveable { mutableStateOf(RouteDisplayMode.Arrows) }
    var pickedLocation by remember { mutableStateOf<PickedLocation?>(null) }
    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    // the options of the export in flight, captured when it started
    var exportJob by remember { mutableStateOf<ExportOptions?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val toast = remember { ToastController() }
    val stack = remember { mutableStateListOf<Layer>() }
    // a research of every destination in flight; it carries on across tabs
    var researchProgress by remember { mutableStateOf<ResearchProgress?>(null) }
    val lang by services.language.lang.collectAsState()

    fun push(layer: Layer) {
        stack.add(layer)
    }

    /** Pop `count` layers. */
    fun back(count: Int = 1) {
        repeat(minOf(count, stack.size)) { stack.removeAt(stack.lastIndex) }
    }

    BackHandler(enabled = stack.isNotEmpty()) { back() }

    val data = state.data
    if (!state.isLoaded) {
        StatusScreen(t("app.loading"))
        return
    }
    if (data == null) {
        StatusScreen(t("app.loadFailed") + (state.loadError?.let { ": $it" } ?: "."), error = true)
        return
    }

    // first launch with no places: start with the trip planner
    var plannerAutoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!plannerAutoOpened) {
            plannerAutoOpened = true
            if (data.destinations.isEmpty() && data.mainLocation != null) push(Layer.Planner)
        }
    }

    // Routes mode needs road geometry for every destination; older cached
    // routes have distance and time but no path, so fetch those too. The
    // in-flight guard keeps the public OSRM server from being asked twice.
    val routesInFlight = remember { HashSet<String>() }
    LaunchedEffect(displayMode, data) {
        val home = data.mainLocation ?: return@LaunchedEffect
        if (displayMode != RouteDisplayMode.Routes) return@LaunchedEffect
        for (dest in data.destinations) {
            if (dest.routeInfo?.geometry != null || !routesInFlight.add(dest.id)) continue
            scope.launch {
                try {
                    store.setRouteInfo(dest.id, services.osrm.fetchRoute(home.location, dest.location))
                } finally {
                    routesInFlight.remove(dest.id)
                }
            }
        }
    }

    // Phones have no map on screen while Settings is open, so the export
    // renders one at the chosen size, then saves it to the device's Pictures.
    LaunchedEffect(exportJob) {
        val job = exportJob ?: return@LaunchedEffect
        try {
            val freeSize = with(density) { Size(contentSize.width.toDp().value, contentSize.height.toDp().value) }
            val image = renderMapImage(data, displayMode, exportLayoutSize(job, freeSize), job.quality, services.tiles, fontFamilyResolver)
            saveImageToPictures(context, image, "vacation-map-${System.currentTimeMillis()}.png")
            val aspect = exportAspectLabel(job.aspect, job.orientation, t, withOrientation = false)
            toast.show(t("export.saved", "a" to aspect, "q" to "${job.quality}x"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            toast.show(t("export.failed"), isError = true)
        } finally {
            exportJob = null
        }
    }

    fun openDetail(id: String) {
        store.setSelectedDestination(id)
        push(Layer.Detail(id))
    }

    fun openAdd() {
        pickedLocation = null
        push(Layer.DestinationForm(null))
    }

    fun openHomeForm() {
        pickedLocation = null
        push(Layer.HomeForm)
    }

    fun openPlanner() {
        // the planner plans around the home base: ask for that first
        if (data.mainLocation == null) {
            toast.show(t("trip.needsHome"))
            openHomeForm()
            return
        }
        push(Layer.Planner)
    }

    /** Research every destination with AI, one after the other, then open the review of what was found. */
    fun researchAll() {
        val destinations = data.destinations
        if (researchProgress != null || destinations.isEmpty()) return
        researchProgress = ResearchProgress(0, destinations.size)
        scope.launch {
            try {
                val results = ArrayList<DestinationAiResult>()
                for ((i, dest) in destinations.withIndex()) {
                    // the free Gemini tier allows only a few requests a minute
                    if (i > 0) delay(DELAY_BETWEEN_DESTINATIONS_MS)
                    results += services.gemini.fetchAiFindingsForDestination(dest, lang.code)
                    researchProgress = ResearchProgress(i + 1, destinations.size)
                }
                push(Layer.AiResearch(results))
            } catch (e: CancellationException) {
                throw e
            } catch (_: GeminiApiKeyMissingException) {
                toast.show(t("ai.unavailable"), isError = true)
            } catch (e: Exception) {
                toast.show(e.message ?: t("ai.failed"), isError = true)
            } finally {
                researchProgress = null
            }
        }
    }

    @Composable
    fun LayerContent(layer: Layer) {
        // the trip's region: the place search in the forms looks there first
        val searchNear = data.mainLocation?.location ?: data.destinations.firstOrNull()?.location
        when (layer) {
            is Layer.Detail -> {
                // briefly absent between a delete and the pop that closes it
                val destination = data.destinations.firstOrNull { it.id == layer.id } ?: return
                DestinationDetailScreen(
                    destination = destination,
                    home = data.mainLocation,
                    trip = data.trip,
                    toast = toast,
                    onBack = { back() },
                    onEdit = {
                        pickedLocation = null
                        push(Layer.DestinationForm(layer.id))
                    },
                    onDelete = { push(Layer.ConfirmDelete(layer.id)) },
                )
            }

            is Layer.DestinationForm -> {
                val initial = layer.id?.let { id -> data.destinations.firstOrNull { it.id == id } }
                LocationFormScreen(
                    kind = LocationFormKind.DestinationForm(initial) { draft ->
                        if (initial != null) {
                            store.updateDestination(initial.id, draft)
                            back()
                        } else {
                            val id = store.addDestination(draft)
                            store.setSelectedDestination(id)
                            // the new place's detail takes the form's place
                            stack[stack.lastIndex] = Layer.Detail(id)
                        }
                        toast.show(t("toast.saved"))
                    },
                    pickedLocation = pickedLocation,
                    onConsumePickedLocation = { pickedLocation = null },
                    onStartPicking = { push(Layer.Pick) },
                    searchNear = searchNear,
                    onClose = { back() },
                )
            }

            Layer.HomeForm -> LocationFormScreen(
                kind = LocationFormKind.HomeForm(data.mainLocation) { home ->
                    store.setMainLocation(home)
                    back()
                    toast.show(t("toast.saved"))
                },
                pickedLocation = pickedLocation,
                onConsumePickedLocation = { pickedLocation = null },
                onStartPicking = { push(Layer.Pick) },
                searchNear = searchNear,
                onClose = { back() },
            )

            Layer.Pick -> PickOnMapScreen(
                data = data,
                displayMode = displayMode,
                onPick = { picked ->
                    pickedLocation = picked
                    back()
                },
                onCancel = { back() },
            )

            is Layer.ConfirmDelete -> {
                val destination = data.destinations.firstOrNull { it.id == layer.id } ?: return
                ConfirmDialog(
                    title = t("shell.confirmDelete", "name" to destination.name),
                    confirmLabel = t("detail.delete"),
                    onCancel = { back() },
                    onConfirm = {
                        store.removeDestination(destination.id)
                        // close the dialog and the detail screen underneath it
                        back(2)
                        toast.show(t("toast.deleted"))
                    },
                )
            }

            Layer.Planner -> {
                // gone if another device cleared the map meanwhile
                val home = data.mainLocation ?: return
                TripWizard(
                    data = data,
                    home = home,
                    onClose = { back() },
                    onChangeHome = ::openHomeForm,
                    onFinish = { planned ->
                        store.applyPlannedTrip(planned)
                        back()
                        tab = Tab.Trip
                        toast.show(t("trip.wizSaved", "n" to planned.places.size))
                    },
                )
            }

            is Layer.AiResearch -> AiResearchScreen(layer.results, onClose = { back() })

            is Layer.Confirm -> ConfirmDialog(
                title = layer.request.title,
                body = layer.request.body,
                confirmLabel = layer.request.confirmLabel,
                onCancel = { back() },
                onConfirm = {
                    back()
                    layer.request.onConfirm()
                },
            )

            Layer.TripDates -> TripDatesPicker(
                trip = data.trip,
                onClose = { back() },
                onSave = { start, end ->
                    store.setTripDates(start, end)
                    back()
                    toast.show(t("trip.datesSaved"))
                },
            )

            is Layer.AddToDay -> AddToDaySheet(data, layer.day, onClose = { back() })

            is Layer.Swap -> SwapSheet(data, layer.day, layer.index, toast, onClose = { back() })

            Layer.Replan -> ReplanSheet(data, toast, onClose = { back() })
        }
    }

    val onMainScreen = stack.all { it.isSheet }
    // the Trip screen's not-scheduled tray sits where the toast would
    val trayVisible = tab == Tab.Trip && onMainScreen && unscheduledDestinations(data.trip, data.destinations).isNotEmpty()

    Box(Modifier.fillMaxSize().background(VmColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { contentSize = it }) {
                when (tab) {
                    Tab.Map -> MapScreen(data, displayMode, { displayMode = it }, ::openDetail, ::openAdd)
                    Tab.Places -> PlacesScreen(data, ::openDetail, ::openAdd) {
                        // without a key there is nothing to research with
                        if (services.gemini.isConfigured) {
                            AiSearchButton(researchProgress, enabled = data.destinations.isNotEmpty(), onClick = ::researchAll)
                        }
                    }
                    Tab.Trip -> TripScreen(
                        data = data,
                        onOpenDetail = ::openDetail,
                        onOpenDates = { push(Layer.TripDates) },
                        onAddToDay = { day -> push(Layer.AddToDay(day)) },
                        onSwap = { day, index -> push(Layer.Swap(day, index)) },
                        onReplan = { push(Layer.Replan) },
                        onOpenPlanner = ::openPlanner,
                        onClearTrip = {
                            push(
                                Layer.Confirm(
                                    ConfirmRequest(t("trip.clearConfirmTitle"), t("trip.clearConfirmBody"), t("trip.clear")) {
                                        store.clearTrip()
                                        toast.show(t("trip.cleared"))
                                    },
                                ),
                            )
                        },
                    )
                    Tab.Settings -> SettingsScreen(
                        data = data,
                        exportOptions = exportOptions,
                        onExportOptionsChange = { exportOptions = it },
                        exporting = exportJob != null,
                        onExport = { exportJob = exportOptions },
                        onEditHome = ::openHomeForm,
                        onReset = {
                            val synced = if (syncStatus != SyncStatus.Unavailable) " " + t("data.confirmResetSynced") else ""
                            push(
                                Layer.Confirm(
                                    ConfirmRequest(t("data.confirmResetTitle"), t("data.confirmReset") + synced, t("data.confirmResetAction")) {
                                        store.clearAllData()
                                        toast.show(t("data.cleared"))
                                    },
                                ),
                            )
                        },
                        onConfirm = { request -> push(Layer.Confirm(request)) },
                    )
                }

                stack.forEachIndexed { index, layer ->
                    if (!layer.isSheet) key(index, layer::class) { LayerContent(layer) }
                }

                ToastView(
                    toast,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (onMainScreen) Modifier else Modifier.navigationBarsPadding())
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = if (!onMainScreen) 24.dp else if (trayVisible) 108.dp else 16.dp,
                        ),
                )
            }

            if (onMainScreen) BottomNav(tab, onSelect = { tab = it })
        }

        stack.forEachIndexed { index, layer ->
            if (layer.isSheet) key(index, layer::class) { LayerContent(layer) }
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, onSelect: (Tab) -> Unit) {
    val t = LocalTranslator.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(VmColors.Surface3)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(80.dp)
            .padding(horizontal = 8.dp),
    ) {
        for (item in Tab.entries) {
            val active = item == tab
            val indicator by animateColorAsState(if (active) VmColors.Accent2Indicator else VmColors.Surface3, tween(200), label = "nav")
            Column(
                Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(item) }
                    .semantics { selected = active },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(64.dp, 32.dp).background(indicator, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    Icon(item.icon, size = 24.dp, filled = active, tint = if (active) VmColors.Accent2Dark else VmColors.NavIcon)
                }
                VmText(t(item.labelKey), size = 12.sp, weight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
