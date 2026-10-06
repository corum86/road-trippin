package io.github.corum86.vacationmap.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.estimateFromStraightLine
import io.github.corum86.vacationmap.logic.formatRouteSummary
import io.github.corum86.vacationmap.logic.mapPoints
import io.github.corum86.vacationmap.map.FitToPoints
import io.github.corum86.vacationmap.map.KeepInView
import io.github.corum86.vacationmap.map.MapPadding
import io.github.corum86.vacationmap.map.OSM_ATTRIBUTION
import io.github.corum86.vacationmap.map.VacationMap
import io.github.corum86.vacationmap.map.rememberMapState
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.EmphasizedEasing
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoThumb
import io.github.corum86.vacationmap.ui.components.StatusDot
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.shadowLg
import io.github.corum86.vacationmap.ui.components.shadowMd
import io.github.corum86.vacationmap.ui.components.statusLabel
import io.github.corum86.vacationmap.ui.theme.VmColors

/**
 * Route line for list rows and cards. Falls back to a straight-line estimate
 * until OSRM has been asked (on first detail open). Empty while there is no
 * home base to measure from.
 */
fun routeText(dest: Destination, home: MainLocation?, t: Translator): String {
    val route = dest.routeInfo ?: home?.let { estimateFromStraightLine(it.location, dest.location) }
    return route?.let { formatRouteSummary(it, t) } ?: ""
}

/** Reference photos first, then the traveller's own. */
fun allPhotos(dest: Destination): List<Photo> = dest.photos + (dest.visit?.photos ?: emptyList())

private class DisplayModeOption(val mode: RouteDisplayMode, val icon: String, val titleKey: String)

private val DISPLAY_MODES = listOf(
    DisplayModeOption(RouteDisplayMode.Arrows, "conversion_path", "routes.arrows"),
    DisplayModeOption(RouteDisplayMode.Routes, "route", "routes.actual"),
    DisplayModeOption(RouteDisplayMode.Points, "scatter_plot", "routes.points"),
)

/** The Map tab: the full-bleed map under a floating title bar, with the places as cards along the bottom. */
@Composable
fun MapScreen(
    data: VacationMapData,
    displayMode: RouteDisplayMode,
    onDisplayModeChange: (RouteDisplayMode) -> Unit,
    onOpenDetail: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val t = LocalTranslator.current
    val services = LocalServices.current
    val store = services.store
    val storeState by store.state.collectAsState()
    val selected = data.destinations.firstOrNull { it.id == storeState.selectedDestinationId }
    val lang by services.language.lang.collectAsState()

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val points = remember(data.mainLocation, data.destinations) { mapPoints(data.mainLocation, data.destinations) }
    // keep pins clear of the floating top bar and the bottom carousel / card
    val fitPadding = MapPadding(top = 100f + statusBar.value, right = 40f, bottom = 150f, left = 40f)
    val mapState = rememberMapState(data)

    Box(Modifier.fillMaxSize()) {
        VacationMap(
            data = data,
            selectedDestinationId = selected?.id,
            displayMode = displayMode,
            state = mapState,
            tiles = services.tiles,
            modifier = Modifier.fillMaxSize(),
            onSelectDestination = store::setSelectedDestination,
            onHomeClick = { store.setSelectedDestination(null) },
            onMapClick = { store.setSelectedDestination(null) },
        )
        FitToPoints(mapState, points, fitPadding, fitKey = points)
        KeepInView(mapState, selected?.location, fitPadding.copy(bottom = 200f))

        // the map's credit stays visible just above the carousel / selected card
        val attributionBottom by animateDpAsState(if (selected != null) 168.dp else 110.dp, tween(200), label = "attribution")
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(bottom = attributionBottom))

        TopBar(
            lang = lang,
            mode = DISPLAY_MODES.first { it.mode == displayMode },
            onNextMode = {
                val index = DISPLAY_MODES.indexOfFirst { it.mode == displayMode }
                onDisplayModeChange(DISPLAY_MODES[(index + 1) % DISPLAY_MODES.size].mode)
            },
            onToggleLanguage = { services.language.set(if (lang == Lang.En) Lang.El else Lang.En) },
            modifier = Modifier.padding(top = 12.dp + statusBar, start = 12.dp, end = 12.dp),
        )

        if (selected != null) {
            SelectedCard(
                dest = selected,
                home = data.mainLocation,
                onClose = { store.setSelectedDestination(null) },
                onDetails = { onOpenDetail(selected.id) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else {
            Carousel(
                data = data,
                onSelect = store::setSelectedDestination,
                onAdd = onAdd,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    }
}

/** "© OpenStreetMap contributors", linking to the licence as the tile usage policy asks. */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier
            .background(Color.White.copy(alpha = 0.8f))
            .clickable(role = Role.Button) { uriHandler.openUri("https://www.openstreetmap.org/copyright") }
            .padding(horizontal = 5.dp),
    ) {
        VmText(OSM_ATTRIBUTION, size = 11.sp, color = Color(0xFF0078A8), lineHeight = 1.4.em)
    }
}

@Composable
private fun TopBar(
    lang: Lang,
    mode: DisplayModeOption,
    onNextMode: () -> Unit,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslator.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadowMd(26.dp)
            .background(VmColors.Surface, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmText(t("app.title"), Modifier.weight(1f), size = 16.sp, weight = FontWeight.Bold, maxLines = 1)
        CircleIconButton(
            icon = mode.icon,
            contentDescription = t(mode.titleKey),
            onClick = onNextMode,
            iconSize = 21.dp,
            background = VmColors.Surface3,
        )
        Box(
            Modifier
                .height(40.dp)
                .defaultMinSize(minWidth = 44.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(VmColors.Surface3)
                .clickable(role = Role.Button, onClick = onToggleLanguage)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = t("map.toggleLanguage") },
            contentAlignment = Alignment.Center,
        ) {
            VmText(if (lang == Lang.En) "EN" else "ΕΛ", size = 12.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SelectedCard(
    dest: Destination,
    home: MainLocation?,
    onClose: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslator.current
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(250, easing = EmphasizedEasing)) }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value
                translationY = (1 - rise.value) * 16.dp.toPx()
            }
            .shadowLg(22.dp)
            .background(VmColors.Surface, RoundedCornerShape(22.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoThumb(allPhotos(dest).firstOrNull(), Modifier.size(64.dp), RoundedCornerShape(14.dp))
            Column(Modifier.weight(1f)) {
                VmText(dest.name, size = 17.sp, weight = FontWeight.Bold, lineHeight = 1.25.em)
                VmText(routeText(dest, home, t), size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2)
                VmText(statusLabel(dest.status), size = 12.sp, color = VmColors.TextMuted)
            }
            CircleIconButton(
                icon = "close",
                contentDescription = t("detail.close"),
                onClick = onClose,
                modifier = Modifier.align(Alignment.Top),
                size = 36.dp,
                tint = VmColors.TextMuted,
            )
        }
        VmButton(t("detail.details"), onDetails, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Carousel(
    data: VacationMapData,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslator.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (dest in data.destinations) {
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .width(190.dp)
                    .shadowMd(18.dp)
                    .clip(shape)
                    .background(VmColors.Surface)
                    .clickable(role = Role.Button) { onSelect(dest.id) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(dest.status)
                    VmText(
                        statusLabel(dest.status).uppercase(),
                        size = 11.sp,
                        weight = FontWeight.SemiBold,
                        color = VmColors.TextMuted,
                        maxLines = 1,
                        style = LocalTextStyle.current.copy(letterSpacing = 0.05.em),
                    )
                }
                VmText(dest.name, size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
                VmText(routeText(dest, data.mainLocation, t), size = 12.sp, color = VmColors.TextMuted, maxLines = 1)
            }
        }
        Box(
            Modifier
                .width(72.dp)
                .fillMaxHeight()
                .defaultMinSize(minHeight = 72.dp)
                .shadowMd(18.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(VmColors.Accent)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = t("form.addDestination") },
            contentAlignment = Alignment.Center,
        ) {
            Icon("add", size = 28.dp, tint = Color.White)
        }
    }
}
