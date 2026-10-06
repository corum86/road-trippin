package io.github.corum86.vacationmap.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.data.readPhotoAsDataUrl
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.daysByDestination
import io.github.corum86.vacationmap.logic.estimateSuffix
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.formatDistance
import io.github.corum86.vacationmap.logic.formatDuration
import io.github.corum86.vacationmap.logic.formatShortDate
import io.github.corum86.vacationmap.logic.tripDayCount
import io.github.corum86.vacationmap.logic.tripDayDate
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.Trip
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.net.GeminiApiKeyMissingException
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.EmphasizedEasing
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoImage
import io.github.corum86.vacationmap.ui.components.Scrim
import io.github.corum86.vacationmap.ui.components.SectionLabel
import io.github.corum86.vacationmap.ui.components.SegmentOption
import io.github.corum86.vacationmap.ui.components.Segmented
import io.github.corum86.vacationmap.ui.components.ToastController
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.boxShadow
import io.github.corum86.vacationmap.ui.components.card
import io.github.corum86.vacationmap.ui.components.consumeTaps
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A full-screen layer that slides up into place (the web app's vm-slide-up). */
@Composable
fun SlideUpLayer(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(250, easing = EmphasizedEasing)) }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1 - enter.value) * 24.dp.toPx()
            }
            .background(VmColors.Bg)
            // nothing underneath may be tapped through the layer
            .consumeTaps(),
        content = content,
    )
}

/**
 * Fetches the OSRM route when the destination has none cached; returns
 * whether it is loading.
 */
@Composable
private fun ensureRoute(dest: Destination, home: MainLocation?): Boolean {
    val services = LocalServices.current
    var loading by remember(dest.id) { mutableStateOf(false) }
    val needsRoute = home != null && dest.routeInfo == null
    // refetch only when the route has been invalidated, not on every edit
    LaunchedEffect(dest.id, needsRoute) {
        if (home == null || !needsRoute) return@LaunchedEffect
        loading = true
        try {
            services.store.setRouteInfo(dest.id, services.osrm.fetchRoute(home.location, dest.location))
        } finally {
            loading = false
        }
    }
    return loading
}

/** Everything about one place: route, status, trip days, visit log, sights, photos and links. */
@Composable
fun DestinationDetailScreen(
    destination: Destination,
    home: MainLocation?,
    trip: Trip,
    toast: ToastController,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTranslator.current
    val services = LocalServices.current
    val store = services.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang by services.language.lang.collectAsState()
    val routeLoading = ensureRoute(destination, home)
    var translating by remember { mutableStateOf(false) }
    // which photo list the lightbox shows, and where it opens
    var lightbox by remember { mutableStateOf<Pair<List<Photo>, Int>?>(null) }

    val routeInfo = destination.routeInfo
    val visit = destination.visit
    val visited = destination.status == TripStatus.Visited
    val plannedDays = daysByDestination(trip)[destination.id] ?: emptyList()
    val photos = allPhotos(destination)
    val dayCount = tripDayCount(trip)

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                store.addVisitPhoto(destination.id, Photo(id = newId(), url = readPhotoAsDataUrl(context, uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast.show(t("visit.photoFailed"), isError = true)
            }
        }
    }

    fun translate() {
        translating = true
        scope.launch {
            try {
                val translated = services.gemini.translateDestinationContent(destination, lang.code)
                store.updateDestination(destination.id) {
                    it.copy(
                        name = translated.name,
                        attractions = translated.attractions,
                        notes = translated.notes,
                        links = translated.links,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: GeminiApiKeyMissingException) {
                toast.show(t("ai.unavailable"), isError = true)
            } catch (e: Exception) {
                toast.show(e.message ?: t("detail.translateFailed"), isError = true)
            } finally {
                translating = false
            }
        }
    }

    SlideUpLayer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hero(
                photos = photos,
                favorite = destination.favorite,
                onBack = onBack,
                onToggleFavorite = { store.toggleFavorite(destination.id) },
            )

            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column {
                    VmText(
                        destination.name,
                        Modifier.semantics { heading() },
                        size = 26.sp,
                        weight = FontWeight.ExtraBold,
                        lineHeight = 1.15.em,
                    )
                    if (home != null) {
                        VmText(
                            t("detail.fromHome", "home" to home.name),
                            Modifier.padding(top = 4.dp),
                            size = 13.sp,
                            color = VmColors.TextMuted,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = t("detail.distance"),
                        value = routeInfo?.let { formatDistance(it.distanceMeters, t) + estimateSuffix(it, t) },
                        loading = routeLoading,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = t("detail.drive"),
                        value = routeInfo?.let { formatDuration(it.durationSeconds, t) + estimateSuffix(it, t) },
                        loading = routeLoading,
                        modifier = Modifier.weight(1f),
                    )
                }

                Segmented(
                    options = listOf(
                        SegmentOption(TripStatus.Planned, t("status.planned"), "event", VmColors.Accent),
                        SegmentOption(TripStatus.Visited, t("status.visited"), "check_circle", VmColors.Accent2),
                    ),
                    selected = destination.status,
                    onSelect = { store.setStatus(destination.id, it) },
                    modifier = Modifier.semantics { contentDescription = t("detail.tripStatus") },
                )

                Section(t("detail.tripDay")) {
                    // each day toggles on its own: a place can be visited on several days
                    if (dayCount > 0) {
                        Row(
                            // the chips scroll out to the screen edges
                            Modifier
                                .bleed(20.dp)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (day in 0 until dayCount) {
                                DayChip(
                                    label = t("trip.onDay", "n" to day + 1),
                                    date = formatShortDate(tripDayDate(trip, day), lang),
                                    active = day in plannedDays,
                                    onClick = { store.toggleTripDay(destination.id, day) },
                                )
                            }
                        }
                    } else {
                        VmText(t("detail.noTripDates"), size = 13.sp, color = VmColors.TextMuted)
                    }
                }

                if (visited) {
                    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(t("visit.log"))
                            visit?.visitedOn?.let { VmText(formatDayLabel(it, lang), size = 13.sp, color = VmColors.TextMuted) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (n in 1..5) {
                                val on = visit?.rating != null && n <= visit.rating
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable(role = Role.RadioButton) { store.setRating(destination.id, n) }
                                        .semantics { contentDescription = t("visit.rating", "n" to n) },
                                ) {
                                    Icon("star", size = 28.dp, filled = on, tint = if (on) VmColors.Star else VmColors.StarEmpty)
                                }
                            }
                        }
                        VisitNote(
                            key = destination.id,
                            initial = visit?.note ?: "",
                            placeholder = t("visit.notePlaceholder"),
                            onCommit = { note -> store.updateVisit(destination.id) { it.copy(note = note.ifEmpty { null }) } },
                        )
                        val own = visit?.photos ?: emptyList()
                        PhotoGrid(columns = 4, gap = 6.dp, count = own.size + 1) { index ->
                            if (index < own.size) {
                                PhotoImage(
                                    own[index].url,
                                    own[index].caption ?: t("gallery.enlarge"),
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).clickable { lightbox = own to index },
                                )
                            } else {
                                AddPhotoTile(t("visit.addPhoto")) {
                                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }
                        }
                    }
                }

                Section(t("detail.attractions")) {
                    for (attraction in destination.attractions) {
                        Row(
                            Modifier.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon("location_on", size = 18.dp, tint = VmColors.Accent)
                            VmText(attraction, size = 14.sp)
                        }
                    }
                    if (destination.attractions.isEmpty()) {
                        VmText(t("detail.noAttractions"), size = 13.sp, color = VmColors.TextMuted)
                    }
                }

                if (!destination.notes.isNullOrEmpty()) {
                    Section(t("detail.notes")) { VmText(destination.notes, size = 14.sp, lineHeight = 1.5.em) }
                }

                if (destination.photos.isNotEmpty()) {
                    Section(t("detail.photos")) {
                        PhotoGrid(columns = 3, gap = 8.dp, count = destination.photos.size) { index ->
                            val photo = destination.photos[index]
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(shape)
                                    .border(1.dp, VmColors.Border, shape)
                                    .clickable { lightbox = destination.photos to index },
                            ) {
                                PhotoImage(photo.url, photo.caption ?: t("gallery.enlarge"), Modifier.fillMaxSize())
                                if (!photo.caption.isNullOrEmpty()) {
                                    VmText(
                                        photo.caption,
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                                            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 5.dp),
                                        size = 8.sp,
                                        weight = FontWeight.Medium,
                                        color = Color.White,
                                        maxLines = 1,
                                        lineHeight = 1.3.em,
                                        style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                    )
                                }
                            }
                        }
                    }
                }

                if (destination.links.isNotEmpty()) {
                    val uriHandler = LocalUriHandler.current
                    Section(t("detail.links")) {
                        for (link in destination.links) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) {
                                        try {
                                            uriHandler.openUri(link.url)
                                        } catch (_: Exception) {
                                            // no app can open this link
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon("open_in_new", size = 18.dp, tint = VmColors.Accent2)
                                VmText(link.label, size = 14.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VmButton(t("detail.edit"), onEdit, Modifier.weight(1f), ButtonTone.TintCoral, height = 44.dp, horizontalPadding = 10.dp)
                    if (services.gemini.isConfigured) {
                        VmButton(
                            if (translating) t("detail.translating") else t("detail.translate"),
                            ::translate,
                            Modifier.weight(1f),
                            ButtonTone.TintTeal,
                            height = 44.dp,
                            enabled = !translating,
                            horizontalPadding = 10.dp,
                        )
                    }
                    VmButton(t("detail.delete"), onDelete, Modifier.weight(1f), ButtonTone.TintDanger, height = 44.dp, horizontalPadding = 10.dp)
                }
            }
        }

        lightbox?.let { (list, start) -> Lightbox(list, start, onClose = { lightbox = null }) }
    }
}

/** Lets a child ignore `amount` of its parent's horizontal padding on both sides. */
internal fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = 0))
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-amount.roundToPx(), 0) }
}

@Composable
private fun Hero(photos: List<Photo>, favorite: Boolean, onBack: () -> Unit, onToggleFavorite: () -> Unit) {
    val t = LocalTranslator.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val period = with(LocalDensity.current) { 16.dp.toPx() } / 1.41421356f
    val stripes = remember(period) {
        Brush.linearGradient(
            0f to Color(0xFFEFE6D8),
            0.5f to Color(0xFFEFE6D8),
            0.5f to VmColors.Surface2,
            1f to VmColors.Surface2,
            start = Offset.Zero,
            end = Offset(period, period),
            tileMode = TileMode.Repeated,
        )
    }
    Box(Modifier.fillMaxWidth().height(260.dp).background(stripes), contentAlignment = Alignment.Center) {
        val hero = photos.firstOrNull()
        if (hero != null) {
            PhotoImage(hero.url, hero.caption, Modifier.fillMaxSize())
        } else {
            Icon("landscape", size = 40.dp, tint = VmColors.TextFaint)
        }
        HeroButton("arrow_back", t("detail.back"), VmColors.Text, false, onBack, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp + statusBar))
        HeroButton(
            "favorite",
            t("detail.favorite"),
            if (favorite) VmColors.Danger else VmColors.TextFaint,
            favorite,
            onToggleFavorite,
            Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp + statusBar),
        )
        if (photos.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(VmColors.Text.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                VmText(
                    if (photos.size == 1) t("photos.one") else t("photos.many", "n" to photos.size),
                    size = 11.sp,
                    weight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun HeroButton(icon: String, label: String, tint: Color, filled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(42.dp)
            .boxShadow(Color.Black.copy(alpha = 0.12f), blur = 8.dp, offsetY = 2.dp, cornerRadius = 21.dp)
            .clip(CircleShape)
            .background(VmColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, size = 22.dp, tint = tint, filled = filled)
    }
}

@Composable
private fun StatTile(label: String, value: String?, loading: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTranslator.current
    Column(modifier.background(VmColors.Surface2, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        VmText(label, size = 12.sp, color = VmColors.TextMuted)
        when {
            value != null -> VmText(value, size = 18.sp, weight = FontWeight.Bold)
            loading -> VmText(
                t("detail.calculatingRoute"),
                size = 13.sp,
                weight = FontWeight.Medium,
                color = VmColors.TextMuted,
                // as tall as a value, so the tile doesn't jump when the route arrives
                lineHeight = 2.em,
                style = LocalTextStyle.current.copy(fontStyle = FontStyle.Italic),
            )
            else -> VmText("—", size = 18.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        content()
    }
}

/** "Day 2 14 Jul": a trip day as a pill that can be ticked. */
@Composable
internal fun DayChip(label: String, date: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val content = if (active) Color.White else VmColors.Text
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (active) VmColors.Accent2 else VmColors.Surface)
            .border(1.dp, if (active) VmColors.Accent2 else VmColors.Border, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VmText(label, size = 13.sp, weight = FontWeight.Bold, color = content, maxLines = 1)
        VmText(date, size = 13.sp, weight = FontWeight.Medium, color = content.copy(alpha = 0.8f), maxLines = 1)
    }
}

/** Inline-editable visit note: plain text look, saved when it loses focus. */
@Composable
private fun VisitNote(key: String, initial: String, placeholder: String, onCommit: (String) -> Unit) {
    var value by remember(key) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val style = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 1.5.em)
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused && value.trim() != initial) onCommit(value.trim())
            focused = state.isFocused
        },
        textStyle = style,
        cursorBrush = SolidColor(VmColors.Accent2),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) VmText(placeholder, size = 14.sp, color = VmColors.TextFaint, lineHeight = 1.5.em)
                field()
            }
        },
    )
}

/** A grid of square cells, `columns` wide. */
@Composable
private fun PhotoGrid(columns: Int, gap: Dp, count: Int, cell: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0 until (count + columns - 1) / columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (index < count) cell(index)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPhotoTile(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    VmColors.TextFaint,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon("add_a_photo", size = 22.dp, tint = VmColors.TextMuted)
    }
}

/** Full-screen photo viewer: swipe (or use the arrows) to move through the photos. */
@Composable
private fun Lightbox(photos: List<Photo>, start: Int, onClose: () -> Unit) {
    val t = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
    BackHandler(onBack = onClose)
    Scrim(alpha = 0.82f, onClick = onClose) {
        Column(
            Modifier.align(Alignment.Center).systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalPager(pager, Modifier.fillMaxWidth().fillMaxHeight(0.7f), pageSpacing = 16.dp) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PhotoImage(
                        photos[page].url,
                        photos[page].caption,
                        Modifier.clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            val photo = photos[pager.currentPage]
            val counter = if (photos.size > 1) "${pager.currentPage + 1} / ${photos.size}" else ""
            val caption = listOf(photo.caption ?: "", counter).filter { it.isNotEmpty() }.joinToString(" · ")
            if (caption.isNotEmpty()) VmText(caption, size = 14.sp, color = Color.White)
            VmButton(t("gallery.close"), onClose, tone = ButtonTone.Coral, height = 36.dp, horizontalPadding = 18.dp)
        }
        if (photos.size > 1) {
            LightboxArrow("chevron_left", t("gallery.prev"), Modifier.align(Alignment.CenterStart).systemBarsPadding().padding(start = 16.dp)) {
                scope.launch { pager.animateScrollToPage((pager.currentPage - 1 + photos.size) % photos.size) }
            }
            LightboxArrow("chevron_right", t("gallery.next"), Modifier.align(Alignment.CenterEnd).systemBarsPadding().padding(end = 16.dp)) {
                scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % photos.size) }
            }
        }
    }
}

@Composable
private fun LightboxArrow(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, size = 24.dp, tint = Color.White)
    }
}
