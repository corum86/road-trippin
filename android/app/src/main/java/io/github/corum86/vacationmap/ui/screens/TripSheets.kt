package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.DateRangeSelection
import io.github.corum86.vacationmap.logic.daysByDestination
import io.github.corum86.vacationmap.logic.destinationDriveMinutes
import io.github.corum86.vacationmap.logic.diffDays
import io.github.corum86.vacationmap.logic.firstOpenDay
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.formatDuration
import io.github.corum86.vacationmap.logic.formatShortDate
import io.github.corum86.vacationmap.logic.haversineDistanceMeters
import io.github.corum86.vacationmap.logic.pickRangeDate
import io.github.corum86.vacationmap.logic.rangeEndOf
import io.github.corum86.vacationmap.logic.replanPool
import io.github.corum86.vacationmap.logic.todayIso
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.logic.tripDayDate
import io.github.corum86.vacationmap.logic.unscheduledDestinations
import io.github.corum86.vacationmap.logic.visiblePlan
import io.github.corum86.vacationmap.model.DestinationDraft
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.BottomSheet
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoThumb
import io.github.corum86.vacationmap.ui.components.SectionLabel
import io.github.corum86.vacationmap.ui.components.SheetHint
import io.github.corum86.vacationmap.ui.components.SheetTitle
import io.github.corum86.vacationmap.ui.components.TextBtn
import io.github.corum86.vacationmap.ui.components.ToastAction
import io.github.corum86.vacationmap.ui.components.ToastController
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlin.math.min
import kotlin.math.roundToInt

/** "6 days" / "1 day" */
fun dayCountLabel(count: Int, t: Translator): String = if (count == 1) t("trip.oneDay") else t("trip.nDays", "n" to count)

/** Bottom sheet for picking the trip's first and last day on a calendar. */
@Composable
fun TripDatesPicker(trip: Trip, onSave: (startDate: String, endDate: String) -> Unit, onClose: () -> Unit) {
    val t = LocalTranslator.current
    var month by remember { mutableStateOf((trip.startDate ?: todayIso()).take(7)) }
    var range by remember { mutableStateOf(DateRangeSelection(trip.startDate, trip.endDate)) }

    val start = range.start
    val end = rangeEndOf(range)
    val dayCount = if (start != null && end != null) diffDays(start, end) + 1 else 0

    BottomSheet(t("trip.datesTitle"), onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle(t("trip.datesTitle"), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp))
            SheetHint(t("trip.tapFirstLast"), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp))

            DateRangeFields(range)
            MonthCalendar(month, { month = it }, range, onPick = { range = pickRangeDate(range, it) })

            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VmText(
                    if (dayCount > 0) dayCountLabel(dayCount, t) else "",
                    Modifier.weight(1f),
                    size = 14.sp,
                    weight = FontWeight.SemiBold,
                )
                TextBtn(t("form.cancel"), onClose, Modifier.defaultMinSize(minHeight = 44.dp))
                VmButton(
                    t("form.save"),
                    onClick = { if (start != null && end != null) onSave(start, end) },
                    // nothing picked yet: reads as switched off rather than faded
                    tone = if (start != null) ButtonTone.Primary else ButtonTone.Faint,
                    height = 44.dp,
                    horizontalPadding = 24.dp,
                )
            }
        }
    }
}

/** A row of a sheet's checklist or pick list. */
@Composable
private fun SheetRow(onClick: () -> Unit, modifier: Modifier = Modifier, minHeight: Int = 56, content: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun Tag(text: String, suggestion: Boolean = false, weight: FontWeight = FontWeight.SemiBold) {
    Box(
        Modifier
            .background(if (suggestion) VmColors.AccentTint else VmColors.Surface3, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        VmText(text, size = 11.sp, weight = weight, color = if (suggestion) VmColors.Accent else VmColors.TextMuted, maxLines = 1)
    }
}

@Composable
private fun CheckBoxIcon(checked: Boolean) = Icon(
    if (checked) "check_box" else "check_box_outline_blank",
    size = 24.dp,
    filled = checked,
    tint = if (checked) VmColors.Accent2 else VmColors.TextFaint,
)

/**
 * Checklist of every destination for one trip day. Ticking applies at once:
 * it adds a visit on this day (a revisit if the place is on other days too),
 * and unticking removes only this day's visit.
 */
@Composable
fun AddToDaySheet(data: VacationMapData, dayIndex: Int, onClose: () -> Unit) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val store = LocalServices.current.store
    val daysOf = daysByDestination(data.trip)
    val title = t("trip.addToDay", "n" to dayIndex + 1)

    BottomSheet(title, onClose) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)) {
            SheetTitle(title)
            SheetHint(formatDayLabel(tripDayDate(data.trip, dayIndex), lang))
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon("replay", size = 16.dp, tint = VmColors.Amber)
                VmText(t("trip.sheetHint"), size = 12.sp, color = VmColors.TextMuted)
            }
        }

        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (dest in data.destinations) {
                val days = daysOf[dest.id] ?: emptyList()
                val onThisDay = dayIndex in days
                val otherDays = days.filter { it != dayIndex }
                SheetRow(
                    onClick = { store.toggleTripDay(dest.id, dayIndex) },
                    modifier = Modifier.semantics { selected = onThisDay },
                ) {
                    CheckBoxIcon(onThisDay)
                    Column(Modifier.weight(1f)) {
                        VmText(dest.name, size = 15.sp, weight = FontWeight.SemiBold)
                        VmText(routeText(dest, data.mainLocation, t), size = 12.sp, color = VmColors.TextMuted)
                    }
                    if (otherDays.isNotEmpty()) Tag(t("trip.alsoDay", "n" to otherDays.joinToString(", ") { (it + 1).toString() }))
                }
            }
            if (data.destinations.isEmpty()) EmptyNote(t("places.empty"))
        }

        VmButton(
            t("common.done"),
            onClose,
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            height = 48.dp,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
        VmText(text, size = 14.sp, color = VmColors.TextMuted)
    }
}

private class SwapCandidate(
    val id: String,
    val name: String,
    val location: LatLng,
    val photo: Photo?,
    /** null while there is no home base to drive from */
    val driveMinutes: Double?,
    /** an unsaved planner suggestion (saved to Places when picked) */
    val isSuggestion: Boolean,
    val blurb: String?,
    val distanceKm: Double,
)

/**
 * Replace one stop with another place: saved places not already on that day,
 * plus the planner's unsaved suggestions, nearest to the replaced place first.
 */
@Composable
fun SwapSheet(data: VacationMapData, dayIndex: Int, index: Int, toast: ToastController, onClose: () -> Unit) {
    val t = LocalTranslator.current
    val store = LocalServices.current.store
    val home = data.mainLocation
    val dayIds = visiblePlan(data.trip).getOrNull(dayIndex) ?: emptyList()
    val current = data.destinations.firstOrNull { it.id == dayIds.getOrNull(index) } ?: return

    val savedIds = data.destinations.mapTo(HashSet()) { it.id }
    fun kmFromCurrent(location: LatLng) = haversineDistanceMeters(current.location, location) / 1000
    val candidates = (
        data.destinations
            .filter { it.id != current.id && it.id !in dayIds }
            .map { d ->
                SwapCandidate(d.id, d.name, d.location, d.photos.firstOrNull(), home?.let { destinationDriveMinutes(d, it) }, false, null, kmFromCurrent(d.location))
            } +
            data.trip.suggestions.orEmpty()
                .filter { it.id !in savedIds }
                .map { s -> SwapCandidate(s.id, s.name, s.location, null, s.driveMinutes, true, s.blurb, kmFromCurrent(s.location)) }
        ).sortedBy { it.distanceKm }

    fun pick(candidate: SwapCandidate) {
        val previousPlan = data.trip.plan
        if (candidate.isSuggestion) {
            store.addDestination(
                DestinationDraft(name = candidate.name, location = candidate.location, notes = candidate.blurb?.ifEmpty { null }),
                id = candidate.id,
            )
        }
        store.swapStop(dayIndex, index, candidate.id)
        onClose()
        toast.show(
            t("trip.swapped", "a" to current.name, "b" to candidate.name),
            action = ToastAction(t("common.undo")) {
                if (candidate.isSuggestion) store.removeDestination(candidate.id)
                store.setPlan(previousPlan)
            },
        )
    }

    val title = t("trip.swapTitle", "name" to current.name)
    BottomSheet(title, onClose) {
        VmText(
            title,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            size = 20.sp,
            weight = FontWeight.Bold,
            lineHeight = 1.3.em,
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (candidate in candidates) {
                SheetRow(onClick = { pick(candidate) }, minHeight = 60) {
                    PhotoThumb(candidate.photo, Modifier.size(48.dp), RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f)) {
                        VmText(candidate.name, size = 15.sp, weight = FontWeight.SemiBold)
                        val km = candidate.distanceKm.roundToInt()
                        VmText(
                            if (candidate.driveMinutes == null) {
                                t("trip.swapDistNoHome", "km" to km, "name" to current.name)
                            } else {
                                t("trip.swapDist", "km" to km, "name" to current.name, "t" to formatDuration(candidate.driveMinutes * 60, t))
                            },
                            size = 12.sp,
                            color = VmColors.TextMuted,
                        )
                    }
                    Tag(
                        if (candidate.isSuggestion) t("trip.newSuggestion") else t("trip.savedTag"),
                        suggestion = candidate.isSuggestion,
                        weight = FontWeight.Bold,
                    )
                }
            }
            if (candidates.isEmpty()) EmptyNote(t("trip.swapEmpty"))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextBtn(t("form.cancel"), onClose, Modifier.defaultMinSize(minHeight = 44.dp))
        }
    }
}

/**
 * Re-plan the rest of the trip by drive time: earlier days and visited stops
 * stay put. Also offers the plain "schedule only the unscheduled" action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplanSheet(data: VacationMapData, toast: ToastController, onClose: () -> Unit) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val store = LocalServices.current.store
    val trip = data.trip
    val destinations = data.destinations
    val dayCount = tripDayCount(trip)
    var fromDay by remember { mutableIntStateOf(min(firstOpenDay(trip, destinations), dayCount - 1).coerceAtLeast(0)) }
    var includeUnscheduled by remember { mutableStateOf(true) }

    val unscheduledCount = unscheduledDestinations(trip, destinations).size
    val poolSize = replanPool(trip, destinations, fromDay, includeUnscheduled).size
    val keepText = when (fromDay) {
        0 -> ""
        1 -> t("trip.keep1")
        else -> t("trip.keepN", "k" to fromDay)
    }
    val summary = listOf(keepText, t("trip.spread", "n" to poolSize, "a" to fromDay + 1, "b" to dayCount))
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    // both actions report what they did with an Undo that restores the plan
    fun run(action: () -> Int, message: (Int) -> String) {
        val previousPlan = trip.plan
        val count = action()
        onClose()
        if (count > 0) toast.show(message(count), action = ToastAction(t("common.undo")) { store.setPlan(previousPlan) })
    }

    BottomSheet(t("trip.replanTitle"), onClose) {
        SheetTitle(t("trip.replanTitle"), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(t("trip.replanFrom"))
                Row(
                    Modifier.bleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (day in 0 until dayCount) {
                        DayChip(
                            label = t("trip.onDay", "n" to day + 1),
                            date = formatShortDate(tripDayDate(trip, day), lang),
                            active = day == fromDay,
                            onClick = { fromDay = day },
                        )
                    }
                }
            }
            val shape = RoundedCornerShape(14.dp)
            VmText(
                summary,
                Modifier
                    .fillMaxWidth()
                    .background(VmColors.Surface, shape)
                    .border(1.dp, VmColors.Border, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                size = 14.sp,
                lineHeight = 1.5.em,
            )
            if (unscheduledCount > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Checkbox) { includeUnscheduled = !includeUnscheduled }
                        .semantics { selected = includeUnscheduled },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBoxIcon(includeUnscheduled)
                    VmText(t("trip.inclUnsched", "n" to unscheduledCount), size = 14.sp, weight = FontWeight.Medium)
                }
            }
        }
        // the footer wraps when the labels are long; Cancel and Re-plan stay together
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unscheduledCount > 0) {
                TextBtn(
                    t("trip.scheduleOnly", "n" to unscheduledCount),
                    onClick = { run({ store.autoPlan() }) { count -> t("trip.autoPlanned", "n" to count) } },
                    Modifier.defaultMinSize(minHeight = 44.dp),
                    fontSize = 13.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn(t("form.cancel"), onClose, Modifier.defaultMinSize(minHeight = 44.dp))
                VmButton(
                    t("trip.replan"),
                    onClick = {
                        if (poolSize > 0) {
                            run({ store.replan(fromDay, includeUnscheduled) }) { t("trip.replanned", "a" to fromDay + 1, "b" to dayCount) }
                        }
                    },
                    tone = if (poolSize > 0) ButtonTone.Primary else ButtonTone.Faint,
                    height = 44.dp,
                    icon = "autorenew",
                    horizontalPadding = 22.dp,
                )
            }
        }
    }
}
