package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.StopRef
import io.github.corum86.vacationmap.logic.daysByDestination
import io.github.corum86.vacationmap.logic.formatDateRange
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.shortPlaceName
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.logic.tripDayDate
import io.github.corum86.vacationmap.logic.visiblePlan
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.StatusDot
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.boxShadow
import io.github.corum86.vacationmap.ui.components.shadowLg
import io.github.corum86.vacationmap.ui.components.shadowSm
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.isActive

/** Where a dragged visit would land if released now. */
private sealed interface DropZone {
    data object Tray : DropZone

    data class Day(val day: Int, val before: Int) : DropZone
}

// long-press to pick up, so a plain swipe still scrolls the list
private const val PICK_UP_DELAY_MS = 250L

/**
 * Drag-and-drop for the trip's stops: which visit is being dragged, where the
 * finger is, and what it is over. Cards, day rows and the tray report their
 * bounds here; positions are in root coordinates throughout.
 */
@Stable
private class TripDragState {
    /** the visit being dragged: a stop on a day, or a place from the tray */
    var dragging by mutableStateOf<StopRef?>(null)
        private set
    var zone by mutableStateOf<DropZone?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** where inside the dragged item the finger holds it, and the item's size */
    var grip = Offset.Zero
        private set
    var itemSize by mutableStateOf(IntSize.Zero)
        private set

    val stops = HashMap<Pair<Int, Int>, Rect>()

    /** each day row's bounds and how many stops it has */
    val days = HashMap<Int, Pair<Rect, Int>>()
    var tray: Rect? = null

    fun start(ref: StopRef, item: LayoutCoordinates, at: Offset) {
        grip = at
        itemSize = item.size
        pointer = item.localToRoot(at)
        dragging = ref
        zone = zoneAt(pointer)
    }

    fun moveTo(position: Offset) {
        pointer = position
        refresh()
    }

    /** Recompute the zone, e.g. after the list scrolled under a still finger. */
    fun refresh() {
        if (dragging != null) zone = zoneAt(pointer)
    }

    fun end() {
        dragging = null
        zone = null
    }

    // The tray lies over the bottom of the list, so it wins; then a stop card
    // (it gives an insertion point); then the day row around it.
    private fun zoneAt(at: Offset): DropZone? {
        if (tray?.contains(at) == true) return DropZone.Tray
        for ((key, rect) in stops) {
            // above its middle inserts before it, below inserts after
            if (rect.contains(at)) return DropZone.Day(key.first, if (at.y < rect.center.y) key.second else key.second + 1)
        }
        for ((day, entry) in days) if (entry.first.contains(at)) return DropZone.Day(day, entry.second)
        return null
    }
}

/**
 * Makes an element draggable after a long press. A press that a button inside
 * the element already took doesn't pick the element up.
 */
private fun Modifier.dragSource(state: TripDragState, ref: StopRef, coordinates: () -> LayoutCoordinates?, onDrop: (StopRef, DropZone?) -> Unit, onPickUp: () -> Unit): Modifier =
    pointerInput(ref) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val item = coordinates()?.takeIf { it.isAttached } ?: return@awaitEachGesture
            state.start(ref, item, pressed.position)
            onPickUp()
            val released = drag(pressed.id) { change ->
                coordinates()?.takeIf { it.isAttached }?.let { state.moveTo(it.localToRoot(change.position)) }
                change.consume()
            }
            if (released) {
                // the release must not also count as a tap on the card
                currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                onDrop(ref, state.zone)
            }
            state.end()
        }
    }

/** Trip-wide numbers shared by the header and the progress bar. */
private class TripSummary(val dayCount: Int, val total: Int, val visited: Int, val dateRange: String?)

/** The Trip tab: the trip's days as a timeline of stops that can be dragged between days. */
@Composable
fun TripScreen(
    data: VacationMapData,
    onOpenDetail: (String) -> Unit,
    onOpenDates: () -> Unit,
    onAddToDay: (Int) -> Unit,
    onSwap: (day: Int, index: Int) -> Unit,
    onReplan: () -> Unit,
    onOpenPlanner: () -> Unit,
    onClearTrip: () -> Unit,
) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val store = LocalServices.current.store
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val drag = remember { TripDragState() }
    val scroll = rememberScrollState()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }

    val trip = data.trip
    val home = data.mainLocation
    val byId = data.destinations.associateBy { it.id }
    val days = visiblePlan(trip).map { ids -> ids.mapNotNull { byId[it] } }
    val daysOf = daysByDestination(trip)
    val unscheduled = data.destinations.filter { it.id !in daysOf }
    val summary = TripSummary(
        dayCount = tripDayCount(trip),
        total = data.destinations.size,
        visited = data.destinations.count { it.status == TripStatus.Visited },
        dateRange = if (!trip.startDate.isNullOrEmpty() && !trip.endDate.isNullOrEmpty()) formatDateRange(trip.startDate, trip.endDate, lang) else null,
    )
    val dragging = drag.dragging
    val zone = drag.zone
    val trayVisible = unscheduled.isNotEmpty() || dragging != null
    val noPlaces = data.destinations.isEmpty()
    val noDates = summary.dayCount == 0
    // an empty trip has nothing to clear
    val blankTrip = trip.name.isEmpty() && noDates && trip.plan.all { it.isEmpty() } &&
        trip.preferences == null && trip.suggestions.isNullOrEmpty()

    fun drop(ref: StopRef, target: DropZone?) {
        when (target) {
            null -> Unit
            DropZone.Tray -> if (ref.day != null) store.moveStop(ref, null)
            is DropZone.Day -> store.moveStop(ref, target.day, target.before)
        }
    }

    // while a stop is held near the top or bottom edge, the list scrolls under it
    LaunchedEffect(dragging != null) {
        if (dragging == null) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val step = with(density) { 10.dp.toPx() }
        while (isActive) {
            withFrameNanos { }
            val y = drag.pointer.y
            val delta = when {
                y < viewport.top + edge -> -step
                y > viewport.bottom - edge -> step
                else -> 0f
            }
            if (delta != 0f && scroll.scrollBy(delta) != 0f) drag.refresh()
        }
    }

    val viewConfiguration = LocalViewConfiguration.current
    val pickUpConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long get() = PICK_UP_DELAY_MS
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides pickUpConfiguration) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .onGloballyPositioned { viewport = it.boundsInRoot() }
                    .verticalScroll(scroll)
                    // room for the not-scheduled tray pinned over the bottom of the list
                    .padding(bottom = if (trayVisible) 116.dp else 110.dp),
            ) {
                Row(
                    Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TripNameInput(trip.name, t("tabs.trip"), t("trip.name"), store::setTripName, Modifier.weight(1f))
                    NewTripButton(t("trip.newTrip"), onOpenPlanner)
                }

                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatesButton(summary.dateRange ?: t("trip.setDates"), onOpenDates, Modifier.weight(1f))
                    // re-planning spreads places over the days by drive time from the home base
                    ReplanButton(t("trip.replan"), enabled = !noPlaces && !noDates && home != null, onClick = onReplan)
                }

                VmText(
                    (if (noDates) "" else "${dayCountLabel(summary.dayCount, t)} · ") + t("trip.visitedOf", "v" to summary.visited, "n" to summary.total),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                    size = 13.sp,
                    color = VmColors.TextMuted,
                )
                val progress = if (summary.total > 0) summary.visited.toFloat() / summary.total else 0f
                Box(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(VmColors.Border)
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(VmColors.Accent2, RoundedCornerShape(3.dp)))
                }

                if (noPlaces) {
                    // no places yet: invite to the trip planner
                    EmptyCard(
                        icon = "auto_awesome",
                        title = if (home != null) t("trip.planEmptyTitle", "home" to shortPlaceName(home.name)) else t("trip.planEmptyTitleNoHome"),
                        body = t("trip.planEmptyBody"),
                        actionIcon = "travel_explore",
                        action = t("trip.startPlanner"),
                        onAction = onOpenPlanner,
                    )
                } else if (noDates) {
                    EmptyCard(
                        icon = "edit_calendar",
                        title = t("trip.noDatesTitle"),
                        body = t("trip.noDatesBody"),
                        actionIcon = "edit_calendar",
                        action = t("trip.setDates"),
                        onAction = onOpenDates,
                    )
                }

                Column(Modifier.padding(horizontal = 16.dp)) {
                    days.forEachIndexed { day, stops ->
                        DayRow(
                            day = day,
                            dateLabel = formatDayLabel(tripDayDate(trip, day), lang),
                            stops = stops,
                            daysOf = daysOf,
                            home = home,
                            zone = (zone as? DropZone.Day)?.takeIf { it.day == day },
                            drag = drag,
                            onAdd = { onAddToDay(day) },
                            onOpenDetail = onOpenDetail,
                            onSwap = { index -> onSwap(day, index) },
                            onRemove = { ref -> store.moveStop(ref, null) },
                            onDrop = ::drop,
                            onPickUp = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        )
                    }
                }

                if (!blankTrip) {
                    // clearing the trip is rare and destructive: it sits below the last day
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), contentAlignment = Alignment.Center) {
                        VmButton(t("trip.clear"), onClearTrip, tone = ButtonTone.TintDanger, height = 36.dp, icon = "delete_sweep", fontSize = 13.sp, iconSize = 18.dp)
                    }
                }
            }

            if (trayVisible) {
                Tray(
                    places = unscheduled,
                    hot = zone == DropZone.Tray,
                    drag = drag,
                    onOpenDetail = onOpenDetail,
                    onDrop = ::drop,
                    onPickUp = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // the copy that follows the finger; the original stays put, dimmed
            val draggedPlace = dragging?.let { byId[it.id] }
            if (dragging != null && draggedPlace != null) {
                val position = drag.pointer - drag.grip - origin
                Box(Modifier.offset { IntOffset(position.x.toInt(), position.y.toInt()) }) {
                    if (dragging.day != null) {
                        Row(
                            Modifier
                                .width(with(density) { drag.itemSize.width.toDp() })
                                .shadowLg(14.dp)
                                .stopCardSurface(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StopCardContent(draggedPlace, home, revisitOf = null, Modifier.weight(1f))
                        }
                    } else {
                        Row(Modifier.shadowLg(22.dp).chipSurface(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChipContent(draggedPlace)
                        }
                    }
                }
            }
        }
    }
}

/** Trip title, editable in place: looks like a heading, saves when it loses focus. */
@Composable
private fun TripNameInput(name: String, placeholder: String, label: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var value by remember(name) { mutableStateOf(name) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val style = LocalTextStyle.current.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 1.3.em, color = VmColors.Text)
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = modifier
            .onFocusChanged { state ->
                if (focused && !state.isFocused && value.trim() != name) onCommit(value.trim())
                focused = state.isFocused
            }
            .semantics {
                contentDescription = label
                heading()
            },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(VmColors.Accent2),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) VmText(placeholder, size = 24.sp, weight = FontWeight.Bold, lineHeight = 1.3.em, maxLines = 1)
                field()
            }
        },
    )
}

@Composable
private fun NewTripButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(VmColors.Accent2Tint)
            .border(1.dp, Color(0x590FA9A0), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon("travel_explore", size = 20.dp, tint = VmColors.Accent2)
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2, maxLines = 1)
    }
}

@Composable
private fun DatesButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(VmColors.Surface)
            .border(1.dp, VmColors.Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon("edit_calendar", size = 20.dp, tint = VmColors.Accent2)
        VmText(label, Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
        Icon("expand_more", size = 20.dp, tint = VmColors.TextMuted)
    }
}

@Composable
private fun ReplanButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    // nothing to place: reads as switched off rather than faded
    val content = if (enabled) VmColors.Accent else VmColors.TextFaint
    Row(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) VmColors.AccentTint else VmColors.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon("autorenew", size = 20.dp, tint = content)
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}

@Composable
private fun EmptyCard(icon: String, title: String, body: String, actionIcon: String, action: String, onAction: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .fillMaxWidth()
            .background(VmColors.Accent2Tint, shape)
            .border(1.dp, Color(0x590FA9A0), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, size = 28.dp, tint = VmColors.Accent2)
        VmText(title, Modifier.semantics { heading() }, size = 18.sp, weight = FontWeight.Bold, lineHeight = 1.3.em)
        VmText(body, size = 13.sp, color = VmColors.NavIcon)
        VmButton(action, onAction, Modifier.padding(top = 4.dp).fillMaxWidth(), icon = actionIcon)
    }
}

@Composable
private fun DayRow(
    day: Int,
    dateLabel: String,
    stops: List<Destination>,
    daysOf: Map<String, List<Int>>,
    home: MainLocation?,
    /** the drop zone, when it is on this day */
    zone: DropZone.Day?,
    drag: TripDragState,
    onAdd: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onSwap: (Int) -> Unit,
    onRemove: (StopRef) -> Unit,
    onDrop: (StopRef, DropZone?) -> Unit,
    onPickUp: () -> Unit,
) {
    val t = LocalTranslator.current
    val count = stops.size
    val allVisited = count > 0 && stops.all { it.status == TripStatus.Visited }
    val hot = zone != null

    DisposableEffect(day) { onDispose { drag.days.remove(day) } }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onGloballyPositioned { drag.days[day] = it.boundsInRoot() to count },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            val circleText = if (allVisited) Color.White else VmColors.Text
            Column(
                Modifier
                    .size(44.dp)
                    .background(if (allVisited) VmColors.Accent2 else if (count > 0) VmColors.Surface else VmColors.Surface3, CircleShape)
                    .then(if (count > 0 && !allVisited) Modifier.border(1.dp, VmColors.Border, CircleShape) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VmText(t("trip.day").uppercase(), size = 9.sp, weight = FontWeight.SemiBold, color = circleText, lineHeight = 1.em, maxLines = 1)
                VmText((day + 1).toString(), size = 16.sp, weight = FontWeight.Bold, color = circleText, lineHeight = 1.em)
            }
            Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).defaultMinSize(minHeight = 12.dp).background(VmColors.Border))
        }

        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VmText(dateLabel, Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                if (count > 0) {
                    VmText(if (count == 1) t("trip.oneStop") else t("trip.nStops", "n" to count), size = 12.sp, color = VmColors.TextMuted)
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(VmColors.Surface)
                        .border(1.dp, VmColors.Border, CircleShape)
                        .clickable(role = Role.Button, onClick = onAdd)
                        .semantics { contentDescription = t("trip.addToDay", "n" to day + 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon("add", size = 20.dp, tint = VmColors.Accent2)
                }
            }

            Column(
                // the day a dragged place is hovering over
                Modifier.background(if (hot) VmColors.Accent2Tint else Color.Transparent, RoundedCornerShape(16.dp)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stops.forEachIndexed { index, stop ->
                    // a later visit of a place already planned for an earlier day
                    val earlier = daysOf[stop.id].orEmpty().filter { it < day }
                    StopCard(
                        dest = stop,
                        ref = StopRef(stop.id, day, index),
                        home = home,
                        revisitOf = earlier.firstOrNull(),
                        // 3dp teal line where the dragged place would be inserted
                        insertion = when {
                            zone?.before == index -> Insertion.Before
                            zone?.before == count && index == count - 1 -> Insertion.After
                            else -> null
                        },
                        drag = drag,
                        onOpen = { onOpenDetail(stop.id) },
                        onSwap = { onSwap(index) },
                        onRemove = { onRemove(StopRef(stop.id, day, index)) },
                        onDrop = onDrop,
                        onPickUp = onPickUp,
                    )
                }
                if (count == 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .dashedBorder(if (hot) VmColors.Accent2 else VmColors.StarEmpty, 14f)
                            .clickable(role = Role.Button, onClick = onAdd)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        VmText(t("trip.dropHere"), size = 13.sp, color = VmColors.TextMuted)
                    }
                }
            }
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radiusDp: Float): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radiusDp.dp.toPx() - stroke / 2),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

private enum class Insertion { Before, After }

private fun Modifier.stopCardSurface(): Modifier {
    val shape = RoundedCornerShape(14.dp)
    return this.background(VmColors.Surface, shape).border(1.dp, VmColors.Border, shape).padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
}

@Composable
private fun StopCard(
    dest: Destination,
    ref: StopRef,
    home: MainLocation?,
    /** index of the earlier day this place is first visited on, for a revisit */
    revisitOf: Int?,
    insertion: Insertion?,
    drag: TripDragState,
    onOpen: () -> Unit,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
    onDrop: (StopRef, DropZone?) -> Unit,
    onPickUp: () -> Unit,
) {
    val t = LocalTranslator.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val key = (ref.day ?: -1) to ref.index
    val isDragged = drag.dragging == ref

    DisposableEffect(key) { onDispose { drag.stops.remove(key) } }

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                coordinates = it
                drag.stops[key] = it.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                if (insertion != null) {
                    val line = 3.dp.toPx()
                    val y = if (insertion == Insertion.Before) -line else size.height
                    drawRect(VmColors.Accent2, Offset(0f, y), Size(size.width, line))
                }
            }
            .alpha(if (isDragged) 0.4f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .dragSource(drag, ref, { coordinates }, onDrop, onPickUp)
            .stopCardSurface(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StopCardContent(dest, home, revisitOf, Modifier.weight(1f))
        CircleIconButton("swap_horiz", t("trip.swap"), onSwap, size = 36.dp, iconSize = 20.dp, tint = VmColors.TextFaint)
        CircleIconButton("close", t("trip.removeFromDay"), onRemove, size = 36.dp, iconSize = 18.dp, tint = VmColors.TextFaint)
    }
}

/** Drag handle, status icon, name and route: the part of a stop card that is also its drag image. */
@Composable
private fun StopCardContent(dest: Destination, home: MainLocation?, revisitOf: Int?, modifier: Modifier = Modifier) {
    val t = LocalTranslator.current
    val visited = dest.status == TripStatus.Visited
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon("drag_indicator", size = 20.dp, tint = VmColors.TextFaint)
        Icon(if (visited) "check" else "location_on", size = 20.dp, filled = true, tint = if (visited) VmColors.Accent2 else VmColors.Accent)
        Column(Modifier.weight(1f)) {
            VmText(dest.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (revisitOf != null) {
                    Row(
                        Modifier.background(VmColors.AmberTint, RoundedCornerShape(9.dp)).padding(start = 5.dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon("replay", size = 14.dp, tint = VmColors.Amber)
                        VmText(t("trip.revisitOf", "n" to revisitOf + 1), size = 11.sp, weight = FontWeight.Bold, color = VmColors.Amber, maxLines = 1)
                    }
                }
                VmText(routeText(dest, home, t), Modifier.weight(1f, fill = false), size = 12.sp, color = VmColors.TextMuted, maxLines = 1)
            }
        }
    }
}

private fun Modifier.chipSurface(): Modifier {
    val shape = RoundedCornerShape(22.dp)
    return this.height(44.dp).background(VmColors.Surface, shape).border(1.dp, VmColors.Border, shape).padding(start = 8.dp, end = 14.dp)
}

@Composable
private fun ChipContent(dest: Destination) {
    Icon("drag_indicator", size = 18.dp, tint = VmColors.TextFaint)
    StatusDot(dest.status)
    VmText(dest.name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
}

/** Places on no day. Also the drop target that takes a visit off its day. */
@Composable
private fun Tray(
    places: List<Destination>,
    hot: Boolean,
    drag: TripDragState,
    onOpenDetail: (String) -> Unit,
    onDrop: (StopRef, DropZone?) -> Unit,
    onPickUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTranslator.current
    DisposableEffect(Unit) { onDispose { drag.tray = null } }
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { drag.tray = it.boundsInRoot() }
            .boxShadow(VmColors.ShadowTint.copy(alpha = 0.08f), blur = 20.dp, offsetY = (-6).dp)
            .background(if (hot) VmColors.Accent2Tint else VmColors.Bg)
            .drawBehind { drawRect(VmColors.Border, size = Size(size.width, 1.dp.toPx())) }
            // taps between the chips don't reach the list underneath
            .pointerInput(Unit) {}
            .padding(top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon("inventory_2", size = 18.dp, tint = VmColors.TextMuted)
            VmText(t("trip.unschedTray", "n" to places.size), size = 12.sp, weight = FontWeight.Bold, color = VmColors.TextMuted)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (dest in places) {
                val ref = StopRef(dest.id, null)
                var coordinates by remember(dest.id) { mutableStateOf<LayoutCoordinates?>(null) }
                Row(
                    Modifier
                        .onGloballyPositioned { coordinates = it }
                        .alpha(if (drag.dragging == ref) 0.4f else 1f)
                        .shadowSm(22.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button) { onOpenDetail(dest.id) }
                        .dragSource(drag, ref, { coordinates }, onDrop, onPickUp)
                        .chipSurface(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChipContent(dest)
                }
            }
            if (places.isEmpty()) {
                Box(Modifier.height(44.dp), contentAlignment = Alignment.CenterStart) {
                    VmText(t("trip.dropToUnschedule"), size = 13.sp, color = VmColors.TextMuted)
                }
            }
        }
    }
}
