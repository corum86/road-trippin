package io.github.corum86.vacationmap.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.RouteDisplayMode
import io.github.corum86.vacationmap.logic.mapPoints
import io.github.corum86.vacationmap.map.FitToPoints
import io.github.corum86.vacationmap.map.MapPadding
import io.github.corum86.vacationmap.map.TownNamesStatus
import io.github.corum86.vacationmap.map.VacationMap
import io.github.corum86.vacationmap.map.rememberMapState
import io.github.corum86.vacationmap.model.PickedLocation
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.boxShadow
import io.github.corum86.vacationmap.ui.components.consumeTaps
import io.github.corum86.vacationmap.ui.theme.VmColors

/**
 * Full-screen map for choosing a location: tap a town's name to take its
 * name and coordinates, or any other spot for just the coordinates.
 */
@Composable
fun PickOnMapScreen(
    data: VacationMapData,
    displayMode: RouteDisplayMode,
    onPick: (PickedLocation) -> Unit,
    onCancel: () -> Unit,
) {
    val t = LocalTranslator.current
    val services = LocalServices.current
    val lang by services.language.lang.collectAsState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val mapState = rememberMapState(data)
    val points = remember(data.mainLocation, data.destinations) { mapPoints(data.mainLocation, data.destinations) }
    var townStatus by remember { mutableStateOf(TownNamesStatus.Idle) }

    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(200)) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(VmColors.Bg)
            .consumeTaps(),
    ) {
        VacationMap(
            data = data,
            selectedDestinationId = null,
            displayMode = displayMode,
            state = mapState,
            tiles = services.tiles,
            modifier = Modifier.fillMaxSize(),
            onMapClick = { onPick(PickedLocation(it.lat, it.lng)) },
            onSelectPlace = { onPick(PickedLocation(it.location.lat, it.location.lng, it.name)) },
            photon = services.photon,
            lang = lang,
            onTownNamesStatus = { townStatus = it },
        )
        FitToPoints(mapState, points, MapPadding(top = 110f + statusBar.value, right = 40f, bottom = 60f, left = 40f), fitKey = "pick")

        MapAttribution(Modifier.align(Alignment.BottomEnd).navigationBarsPadding())

        if (townStatus != TownNamesStatus.Idle) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 30.dp)
                    .background(VmColors.Text.copy(alpha = 0.82f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                VmText(
                    t(if (townStatus == TownNamesStatus.Loading) "map.placesLoading" else "map.placesFailed"),
                    size = 11.sp,
                    weight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }

        Row(
            Modifier
                .padding(top = 12.dp + statusBar, start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .boxShadow(VmColors.Accent2.copy(alpha = 0.3f), blur = 20.dp, offsetY = 6.dp, cornerRadius = 18.dp)
                .background(VmColors.Accent2, RoundedCornerShape(18.dp))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("touch_app", size = 22.dp, tint = Color.White)
            VmText(t("shell.pickingBannerTap"), Modifier.weight(1f), size = 13.sp, weight = FontWeight.SemiBold, color = Color.White)
            CircleIconButton("close", t("form.cancel"), onCancel, size = 36.dp, tint = Color.White)
        }
    }
}
