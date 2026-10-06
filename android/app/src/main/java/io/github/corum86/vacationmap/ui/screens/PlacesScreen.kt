package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoThumb
import io.github.corum86.vacationmap.ui.components.StatusChip
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.boxShadow
import io.github.corum86.vacationmap.ui.components.card
import io.github.corum86.vacationmap.ui.theme.VmColors

private enum class PlacesFilter(val labelKey: String, val test: (Destination) -> Boolean) {
    All("filter.all", { true }),
    Planned("status.planned", { it.status == TripStatus.Planned }),
    Visited("status.visited", { it.status == TripStatus.Visited }),
    Favorites("filter.favorites", { it.favorite }),
}

/** Title of a tab screen: 24/700. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, bottomPadding: Dp = 12.dp) {
    VmText(
        text,
        modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomPadding).semantics { heading() },
        size = 24.sp,
        weight = FontWeight.Bold,
        lineHeight = 1.3.em,
    )
}

/** The Places tab: every saved place, filterable, with a button to add one. */
@Composable
fun PlacesScreen(data: VacationMapData, onOpenDetail: (String) -> Unit, onAdd: () -> Unit) {
    val t = LocalTranslator.current
    val store = LocalServices.current.store
    var filter by rememberSaveable { mutableStateOf(PlacesFilter.All) }
    val visible = data.destinations.filter(filter.test)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            item { ScreenTitle(t("tabs.places")) }
            item {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in PlacesFilter.entries) {
                        FilterChip(
                            label = t(option.labelKey),
                            count = data.destinations.count(option.test),
                            active = option == filter,
                            onClick = { filter = option },
                        )
                    }
                }
            }
            items(visible, key = { it.id }) { dest ->
                PlaceCard(
                    dest = dest,
                    route = routeText(dest, data.mainLocation, t),
                    onClick = { onOpenDetail(dest.id) },
                    onToggleFavorite = { store.toggleFavorite(dest.id) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }
            if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
                        VmText(t("places.empty"), size = 14.sp, color = VmColors.TextMuted)
                    }
                }
            }
        }

        // extended FAB
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .height(56.dp)
                .boxShadow(VmColors.Accent.copy(alpha = 0.35f), blur = 20.dp, offsetY = 6.dp, cornerRadius = 18.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(VmColors.Accent)
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(start = 16.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("add", size = 24.dp, tint = Color.White)
            VmText(t("form.addDestination"), size = 14.sp, weight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    val content = if (active) Color.White else VmColors.Text
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (active) VmColors.Text else VmColors.Surface)
            .border(1.dp, if (active) VmColors.Text else VmColors.Border, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = content, maxLines = 1)
        VmText(count.toString(), size = 13.sp, weight = FontWeight.SemiBold, color = content.copy(alpha = 0.6f))
    }
}

@Composable
private fun PlaceCard(
    dest: Destination,
    route: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslator.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .card()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoThumb(allPhotos(dest).firstOrNull(), Modifier.size(68.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VmText(dest.name, size = 15.sp, weight = FontWeight.Bold, lineHeight = 1.3.em)
            VmText(route, size = 12.sp, color = VmColors.TextMuted)
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(dest.status)
                val rating = dest.visit?.rating
                if (dest.status == TripStatus.Visited && rating != null) {
                    VmText("★".repeat(rating), size = 11.sp, color = VmColors.TextMuted)
                }
            }
        }
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(role = Role.Switch, onClick = onToggleFavorite)
                .semantics { contentDescription = t("detail.favorite") }
                .padding(6.dp),
        ) {
            Icon("favorite", size = 22.dp, filled = dest.favorite, tint = if (dest.favorite) VmColors.Danger else VmColors.TextFaint)
        }
    }
}
