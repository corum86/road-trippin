package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.DestinationDraft
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.LinkItem
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.PickedLocation
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PlaceSearchField
import io.github.corum86.vacationmap.ui.components.SectionLabel
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.VmTextField
import io.github.corum86.vacationmap.ui.components.VmTextFieldSmall
import io.github.corum86.vacationmap.ui.components.card
import io.github.corum86.vacationmap.ui.theme.VmColors
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

/** What the form edits: a place to visit, or the home base. */
sealed interface LocationFormKind {
    class DestinationForm(val initial: Destination?, val onSave: (DestinationDraft) -> Unit) : LocationFormKind

    class HomeForm(val initial: MainLocation?, val onSave: (MainLocation) -> Unit) : LocationFormKind
}

/** Accepts "39.5", " 39.5 " and the decimal comma Greek keyboards produce. */
internal fun parseCoordinate(text: String, limit: Double): Double? {
    val trimmed = text.trim().replaceFirst(',', '.')
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() && abs(it) <= limit }
}

/** A stored coordinate as editable text: all its digits, no exponent. */
internal fun coordinateText(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

/** Add or edit a destination, or edit the home base: name, location and (for places) details. */
@Composable
fun LocationFormScreen(
    kind: LocationFormKind,
    pickedLocation: PickedLocation?,
    onConsumePickedLocation: () -> Unit,
    onStartPicking: () -> Unit,
    /** the name search lists places around here first (the trip's region) */
    searchNear: LatLng?,
    onClose: () -> Unit,
) {
    val t = LocalTranslator.current
    val dest = (kind as? LocationFormKind.DestinationForm)?.initial
    val home = (kind as? LocationFormKind.HomeForm)?.initial
    val isDestination = kind is LocationFormKind.DestinationForm
    val initialLocation = dest?.location ?: home?.location

    var name by remember { mutableStateOf(dest?.name ?: home?.name ?: "") }
    var lat by remember { mutableStateOf(initialLocation?.let { coordinateText(it.lat) } ?: "") }
    var lng by remember { mutableStateOf(initialLocation?.let { coordinateText(it.lng) } ?: "") }
    var attractions by remember { mutableStateOf(dest?.attractions?.joinToString("\n") ?: "") }
    var notes by remember { mutableStateOf(dest?.notes ?: "") }
    var photos by remember { mutableStateOf(dest?.photos ?: emptyList()) }
    var links by remember { mutableStateOf(dest?.links ?: emptyList()) }
    var locationError by remember { mutableStateOf(false) }

    LaunchedEffect(pickedLocation) {
        if (pickedLocation != null) {
            // a town picked by name names the place too; a bare map point keeps the name
            pickedLocation.name?.let { name = it }
            lat = String.format(Locale.ROOT, "%.5f", pickedLocation.lat)
            lng = String.format(Locale.ROOT, "%.5f", pickedLocation.lng)
            locationError = false
            onConsumePickedLocation()
        }
    }

    val title = when (kind) {
        is LocationFormKind.HomeForm -> if (kind.initial != null) t("form.editMainLocation") else t("form.setHomeBase")
        is LocationFormKind.DestinationForm -> if (kind.initial != null) t("form.editDestination") else t("form.addDestination")
    }

    fun save() {
        val latValue = parseCoordinate(lat, 90.0)
        val lngValue = parseCoordinate(lng, 180.0)
        if (latValue == null || lngValue == null) {
            locationError = true
            return
        }
        val location = LatLng(latValue, lngValue)
        when (kind) {
            is LocationFormKind.HomeForm -> kind.onSave(MainLocation(name.trim().ifEmpty { t("form.homeBase") }, location))
            is LocationFormKind.DestinationForm -> kind.onSave(
                DestinationDraft(
                    name = name.trim().ifEmpty { t("form.untitledDestination") },
                    location = location,
                    attractions = attractions.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                    photos = photos.filter { it.url.isNotBlank() }.map { it.copy(url = it.url.trim(), caption = it.caption?.trim()?.ifEmpty { null }) },
                    links = links.filter { it.url.isNotBlank() && it.label.isNotBlank() }.map { it.copy(url = it.url.trim(), label = it.label.trim()) },
                    notes = notes.trim().ifEmpty { null },
                    routeInfo = kind.initial?.routeInfo,
                ),
            )
        }
    }

    SlideUpLayer {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton("close", t("form.cancel"), onClose, size = 48.dp, iconSize = 24.dp)
                VmText(title, Modifier.weight(1f).semantics { heading() }, size = 18.sp, weight = FontWeight.Bold, maxLines = 1)
                VmButton(t("form.save"), ::save, height = 40.dp, horizontalPadding = 20.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VmColors.Border))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Field(t("form.name")) {
                    PlaceSearchField(
                        name,
                        { name = it },
                        // a place found by name fills in what a pick on the map would
                        onSelect = { place ->
                            name = place.name
                            lat = String.format(Locale.ROOT, "%.5f", place.location.lat)
                            lng = String.format(Locale.ROOT, "%.5f", place.location.lng)
                            locationError = false
                        },
                        placeholder = t("form.searchPlaceholder"),
                        near = searchNear,
                        onDone = ::save,
                    )
                }

                Column(
                    Modifier.fillMaxWidth().card(border = if (locationError) VmColors.Danger else VmColors.Border).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(t("form.location"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoordinateField(t("form.latitude"), lat, Modifier.weight(1f), ::save) {
                            lat = it
                            locationError = false
                        }
                        CoordinateField(t("form.longitude"), lng, Modifier.weight(1f), ::save) {
                            lng = it
                            locationError = false
                        }
                    }
                    val pickShape = RoundedCornerShape(22.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(pickShape)
                            .background(VmColors.Accent2Tint)
                            .border(1.dp, VmColors.Accent2.copy(alpha = 0.35f), pickShape)
                            .clickable(role = Role.Button, onClick = onStartPicking),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon("pin_drop", size = 20.dp, tint = VmColors.Accent2)
                        VmText(t("form.pickOnMapShort"), size = 14.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2)
                    }
                    if (locationError) FormError(t("form.needLocation"))
                }

                if (isDestination) {
                    Field(t("form.attractions")) {
                        VmTextField(attractions, { attractions = it }, placeholder = t("form.onePerLine"), lines = 3)
                    }
                    Field(t("form.notes")) { VmTextField(notes, { notes = it }, lines = 3) }

                    RowsCard(t("form.photos"), t("form.addPhoto"), onAdd = { photos = photos + Photo(newId(), "", "") }) {
                        photos.forEachIndexed { i, photo ->
                            RowFields(onRemove = { photos = photos.filterIndexed { index, _ -> index != i } }) {
                                VmTextFieldSmall(
                                    photo.url,
                                    { url -> photos = photos.mapIndexed { index, p -> if (index == i) p.copy(url = url) else p } },
                                    placeholder = "https://…",
                                    keyboardType = KeyboardType.Uri,
                                    capitalization = KeyboardCapitalization.None,
                                )
                                VmTextFieldSmall(
                                    photo.caption ?: "",
                                    { caption -> photos = photos.mapIndexed { index, p -> if (index == i) p.copy(caption = caption) else p } },
                                    placeholder = t("form.captionPlaceholder"),
                                )
                            }
                        }
                    }

                    RowsCard(t("form.links"), t("form.addLink"), onAdd = { links = links + LinkItem(newId(), "", "") }) {
                        links.forEachIndexed { i, link ->
                            RowFields(onRemove = { links = links.filterIndexed { index, _ -> index != i } }) {
                                VmTextFieldSmall(
                                    link.label,
                                    { label -> links = links.mapIndexed { index, l -> if (index == i) l.copy(label = label) else l } },
                                    placeholder = t("form.labelPlaceholder"),
                                )
                                VmTextFieldSmall(
                                    link.url,
                                    { url -> links = links.mapIndexed { index, l -> if (index == i) l.copy(url = url) else l } },
                                    placeholder = "https://…",
                                    keyboardType = KeyboardType.Uri,
                                    capitalization = KeyboardCapitalization.None,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted)
        content()
    }
}

@Composable
fun FormError(text: String, modifier: Modifier = Modifier) =
    VmText(text, modifier, size = 12.sp, weight = FontWeight.SemiBold, color = VmColors.Danger)

@Composable
private fun CoordinateField(label: String, value: String, modifier: Modifier, onDone: () -> Unit, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VmText(label, size = 12.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted, maxLines = 1)
        VmTextField(
            value,
            onChange,
            height = 44.dp,
            radius = 12.dp,
            horizontalPadding = 10.dp,
            fontSize = 14.sp,
            background = VmColors.Bg,
            mono = true,
            // a full keyboard: coordinates may need a minus sign or a decimal comma
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.None,
            onDone = onDone,
        )
    }
}

/** A card of editable rows (photos, links) with a button that adds one. */
@Composable
private fun RowsCard(label: String, addLabel: String, onAdd: () -> Unit, rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(label)
        rows()
        VmButton(addLabel, onAdd, tone = ButtonTone.TintCoral, height = 36.dp, fontSize = 13.sp)
    }
}

/** Two stacked inputs with one remove button beside them. */
@Composable
private fun RowFields(onRemove: () -> Unit, fields: @Composable () -> Unit) {
    val t = LocalTranslator.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { fields() }
        CircleIconButton("delete", t("form.remove"), onRemove, size = 36.dp, iconSize = 20.dp, tint = VmColors.Danger)
    }
}
