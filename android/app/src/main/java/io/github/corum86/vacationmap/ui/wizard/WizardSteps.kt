package io.github.corum86.vacationmap.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.DateRangeSelection
import io.github.corum86.vacationmap.logic.Plan
import io.github.corum86.vacationmap.logic.RankedSuggestion
import io.github.corum86.vacationmap.logic.addDays
import io.github.corum86.vacationmap.logic.dayDriveMinutes
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.formatDuration
import io.github.corum86.vacationmap.logic.shortPlaceName
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import io.github.corum86.vacationmap.net.DestinationAiResult
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoImage
import io.github.corum86.vacationmap.ui.components.SectionLabel
import io.github.corum86.vacationmap.ui.components.Spinner
import io.github.corum86.vacationmap.ui.components.StripedBox
import io.github.corum86.vacationmap.ui.components.TextBtn
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.card
import io.github.corum86.vacationmap.ui.components.shadowSm
import io.github.corum86.vacationmap.ui.screens.DateRangeFields
import io.github.corum86.vacationmap.ui.screens.HomeBadge
import io.github.corum86.vacationmap.ui.screens.MonthCalendar
import io.github.corum86.vacationmap.ui.screens.bleed
import io.github.corum86.vacationmap.ui.screens.currentLang
import io.github.corum86.vacationmap.ui.theme.VmColors

// body text inside tinted cards: a shade softer than the main text
private val SoftText = VmColors.NavIcon

private fun placeCount(n: Int, t: Translator): String = if (n == 1) t("wizard.onePlace") else t("wizard.nPlaces", "n" to n)

/** Selected: teal border on a teal tint. */
private fun Modifier.optionSurface(on: Boolean, shape: Shape, onBackground: Color = VmColors.Accent2Tint): Modifier =
    this
        .clip(shape)
        .background(if (on) onBackground else VmColors.Surface)
        .border(1.5.dp, if (on) VmColors.Accent2 else VmColors.Border, shape)

/** A photo slot with the striped placeholder behind it. */
@Composable
private fun Thumb(url: String?, size: Dp, radius: Dp) {
    StripedBox(Modifier.size(size).clip(RoundedCornerShape(radius))) {
        if (url != null) PhotoImage(url, null, Modifier.fillMaxSize()) else Icon("landscape", size = 22.dp, tint = VmColors.TextMuted)
    }
}

@Composable
private fun DashedNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(VmColors.Surface, RoundedCornerShape(16.dp))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    VmColors.StarEmpty,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        VmText(text, size = 14.sp, color = VmColors.TextMuted, style = LocalTextStyle.current.copy(textAlign = TextAlign.Center))
    }
}

@Composable
private fun CheckBoxIcon(checked: Boolean) = Icon(
    if (checked) "check_box" else "check_box_outline_blank",
    size = 24.dp,
    filled = checked,
    tint = if (checked) VmColors.Accent2 else VmColors.TextFaint,
)

// ---------------------------------------------------------------- 1 dates

@Composable
internal fun DatesStep(
    home: MainLocation,
    range: DateRangeSelection,
    month: String,
    dayCount: Int,
    onMonthChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onLength: (Int) -> Unit,
    onChangeHome: () -> Unit,
) {
    val t = LocalTranslator.current
    Row(
        Modifier.fillMaxWidth().card().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeBadge(40.dp)
        Column(Modifier.weight(1f)) {
            VmText(
                t("form.homeBase").uppercase(),
                size = 11.sp,
                weight = FontWeight.Bold,
                color = VmColors.Accent2,
                style = LocalTextStyle.current.copy(letterSpacing = 0.06.em),
            )
            VmText(home.name, size = 14.sp, weight = FontWeight.SemiBold)
        }
        TextBtn(t("wizard.changeHome"), onChangeHome, Modifier.defaultMinSize(minHeight = 36.dp), fontSize = 13.sp)
    }

    DateRangeFields(range, horizontalPadding = 0.dp)

    Row(
        Modifier.bleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in LENGTH_OPTIONS) {
            val active = range.start != null && dayCount == option.days
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(if (active) VmColors.Accent2Tint else VmColors.Surface)
                    .border(1.dp, if (active) VmColors.Accent2 else VmColors.Border, shape)
                    .clickable(role = Role.Button) { onLength(option.days) }
                    .semantics { selected = active }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                VmText(t(option.labelKey), size = 13.sp, weight = FontWeight.SemiBold, color = if (active) VmColors.Accent2 else VmColors.Text, maxLines = 1)
            }
        }
    }

    MonthCalendar(
        month,
        onMonthChange,
        range,
        onPick,
        Modifier.widthIn(max = 420.dp).fillMaxWidth().card(radius = 20.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        compact = true,
    )
}

// ---------------------------------------------------------------- 2 drive

@Composable
internal fun DriveStep(
    answers: WizardAnswers,
    /** reachable suggestions per drive limit; null while they load, -1 if unavailable */
    countFor: (Double?) -> Int?,
    onDrive: (Double?) -> Unit,
    onToggleFerry: () -> Unit,
) {
    val t = LocalTranslator.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in DRIVE_OPTIONS) {
            val active = answers.driveChosen && answers.drive == option.minutes
            val count = countFor(option.minutes)
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .optionSurface(active, RoundedCornerShape(16.dp))
                    .clickable(role = Role.RadioButton) { onDrive(option.minutes) }
                    .semantics { selected = active }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (active) "radio_button_checked" else "radio_button_unchecked",
                    size = 24.dp,
                    filled = active,
                    tint = if (active) VmColors.Accent2 else VmColors.TextMuted,
                )
                Column(Modifier.weight(1f)) {
                    VmText(t(option.labelKey), size = 15.sp, weight = FontWeight.SemiBold)
                    if (count != UNAVAILABLE) VmText(if (count == null) "…" else placeCount(count, t), size = 12.sp, color = VmColors.TextMuted)
                }
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .card(radius = 16.dp)
            .clickable(role = Role.Switch, onClick = onToggleFerry)
            .semantics { selected = answers.ferry }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon("directions_boat", size = 24.dp, tint = VmColors.Accent2)
        Column(Modifier.weight(1f)) {
            VmText(t("wizard.ferry"), size = 14.sp, weight = FontWeight.SemiBold)
            VmText(t("wizard.ferrySub"), size = 12.sp, color = VmColors.TextMuted)
        }
        // on/off switch, 52×32
        Box(
            Modifier
                .size(52.dp, 32.dp)
                .background(if (answers.ferry) VmColors.Accent2 else VmColors.StarEmpty, RoundedCornerShape(16.dp))
                .padding(4.dp),
            contentAlignment = if (answers.ferry) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(24.dp).shadowSm(12.dp).background(Color.White, CircleShape))
        }
    }
}

/** What [DriveStep]'s `countFor` answers when there are no suggestions to count. */
internal const val UNAVAILABLE = -1

// ---------------------------------------------------------------- 3 group

@Composable
internal fun GroupStep(group: TravelGroup?, onGroup: (TravelGroup) -> Unit) {
    val t = LocalTranslator.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (option in TravelGroup.entries) {
            val active = group == option
            Row(
                Modifier
                    .fillMaxWidth()
                    .optionSurface(active, RoundedCornerShape(18.dp))
                    .clickable(role = Role.RadioButton) { onGroup(option) }
                    .semantics { selected = active }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).background(if (active) VmColors.Accent2 else VmColors.Surface3, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GROUP_ICONS.getValue(option), size = 26.dp, tint = if (active) Color.White else VmColors.TextMuted)
                }
                Column(Modifier.weight(1f)) {
                    VmText(t("wizard.group.${option.id}"), size = 16.sp, weight = FontWeight.Bold)
                    VmText(t("wizard.group.${option.id}Desc"), size = 12.sp, color = VmColors.TextMuted)
                }
                Icon("check_circle", size = 24.dp, filled = true, tint = if (active) VmColors.Accent2 else Color.Transparent)
            }
        }
    }
}

// ---------------------------------------------------------------- 4 style

@Composable
internal fun StyleStep(styles: List<TravelStyle>, onToggle: (TravelStyle) -> Unit) {
    val t = LocalTranslator.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in TravelStyle.entries.chunked(2)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in pair) {
                    val active = option in styles
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .defaultMinSize(minHeight = 104.dp)
                            .optionSurface(active, RoundedCornerShape(18.dp))
                            .clickable(role = Role.Checkbox) { onToggle(option) }
                            .semantics { selected = active }
                            .padding(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(STYLE_ICONS.getValue(option), size = 28.dp, tint = if (active) VmColors.Accent2 else VmColors.TextMuted)
                            Column {
                                VmText(t("wizard.style.${option.id}"), size = 15.sp, weight = FontWeight.Bold)
                                VmText(t("wizard.style.${option.id}Desc"), size = 12.sp, color = VmColors.TextMuted, lineHeight = 1.35.em)
                            }
                        }
                        Icon(
                            "check_circle",
                            // 12dp from the tile's corner, a little inside its padding
                            Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                            size = 22.dp,
                            filled = true,
                            tint = if (active) VmColors.Accent2 else Color.Transparent,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 5 extras

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtrasStep(budget: Int?, mustHaves: List<MustHave>, onBudget: (Int) -> Unit, onToggleMust: (MustHave) -> Unit) {
    val t = LocalTranslator.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(t("wizard.budget"))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((level, symbol) in BUDGET_SYMBOLS) {
                val active = budget == level
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .optionSurface(active, RoundedCornerShape(16.dp))
                        .clickable(role = Role.RadioButton) { onBudget(level) }
                        .semantics { selected = active }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    VmText(symbol, size = 18.sp, weight = FontWeight.ExtraBold, color = if (active) VmColors.Accent2 else VmColors.Text)
                    VmText(t("wizard.budget.$level"), size = 12.sp, weight = FontWeight.SemiBold, lineHeight = 1.3.em)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(t("wizard.mustHaves"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in MustHave.entries) {
                val active = option in mustHaves
                val content = if (active) VmColors.Accent2 else VmColors.Text
                Row(
                    Modifier
                        .height(44.dp)
                        .optionSurface(active, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Checkbox) { onToggleMust(option) }
                        .semantics { selected = active }
                        .padding(start = 12.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MUST_HAVE_ICONS.getValue(option), size = 20.dp, tint = content)
                    VmText(t("wizard.must.${option.id}"), size = 14.sp, weight = FontWeight.SemiBold, color = content, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 6 places

/** Where the list of suggestions stands. */
internal sealed interface Catalog {
    data object Loading : Catalog

    data class Ready(val items: List<PlaceSuggestion>) : Catalog

    data class Failed(val error: String) : Catalog
}

@Composable
private fun WizardTag(text: String, background: Color, color: Color, weight: FontWeight = FontWeight.SemiBold) {
    Box(Modifier.background(background, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        VmText(text, size = 11.sp, weight = weight, color = color, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlacesStep(
    home: MainLocation,
    catalog: Catalog,
    ranked: List<RankedSuggestion>,
    selected: List<String>,
    savedIds: Set<String>,
    topIds: List<String>,
    thumbnails: Map<String, String>,
    dayCount: Int,
    recommended: Int,
    onToggle: (String) -> Unit,
    onPickTop: () -> Unit,
    onRetry: () -> Unit,
) {
    val t = LocalTranslator.current
    if (catalog is Catalog.Loading) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(28.dp)
            VmText(
                t("wizard.loadingSugg", "home" to shortPlaceName(home.name)),
                size = 14.sp,
                color = VmColors.TextMuted,
                style = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            )
        }
        return
    }
    if (catalog is Catalog.Failed) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VmText(
                t("wizard.suggFailed", "error" to catalog.error),
                size = 14.sp,
                color = VmColors.Danger,
                style = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            )
            TextBtn(t("common.retry"), onRetry)
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(VmColors.Surface3, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VmText(
                if (selected.isNotEmpty()) t("wizard.selLine", "k" to selected.size) else t("wizard.selNone"),
                size = 14.sp,
                weight = FontWeight.Bold,
            )
            VmText(t("wizard.recLine", "n" to dayCount, "r" to recommended), size = 12.sp, color = VmColors.TextMuted)
        }
        val canPick = ranked.isNotEmpty()
        Row(
            Modifier
                .height(40.dp)
                .alpha(if (canPick) 1f else 0.6f)
                .shadowSm(20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(VmColors.Surface)
                .clickable(enabled = canPick, role = Role.Button, onClick = onPickTop)
                .padding(start = 10.dp, end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("auto_awesome", size = 20.dp, tint = VmColors.Accent2)
            VmText(
                t("wizard.pickTop", "r" to minOf(recommended, ranked.size)),
                size = 13.sp,
                weight = FontWeight.SemiBold,
                color = VmColors.Accent2,
                maxLines = 1,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (entry in ranked) {
            val s = entry.suggestion
            val active = s.id in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .optionSurface(active, RoundedCornerShape(18.dp), onBackground = Color(0xFFF1F8F7))
                    .clickable(role = Role.Checkbox) { onToggle(s.id) }
                    .semantics { this.selected = active }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Thumb(thumbnails[s.id], 76.dp, 12.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VmText(s.name, Modifier.weight(1f), size = 15.sp, weight = FontWeight.Bold, lineHeight = 1.3.em)
                        CheckBoxIcon(active)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (s.ferry) "directions_boat" else "directions_car", size = 16.dp, tint = VmColors.TextMuted)
                        VmText(driveLine(s, t), size = 12.sp, color = VmColors.TextMuted)
                    }
                    if (s.blurb.isNotEmpty()) VmText(s.blurb, size = 12.sp, color = SoftText, lineHeight = 1.4.em)
                    FlowRow(
                        Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (s.id in topIds && entry.reasons.isNotEmpty()) {
                            WizardTag(t("wizard.topPick"), VmColors.AccentTint, VmColors.Accent, FontWeight.Bold)
                        }
                        if (s.id in savedIds) WizardTag(t("trip.savedTag"), VmColors.Surface3, VmColors.TextMuted)
                        for (reason in entry.reasons.take(3)) WizardTag(t(reason.labelKey), VmColors.Accent2Tint, VmColors.Accent2)
                    }
                }
            }
        }
    }
    if (ranked.isEmpty()) DashedNote(t("wizard.noSugg"))
}

// ---------------------------------------------------------------- 7 research

@Composable
internal fun ResearchStep(places: List<PlaceSuggestion>, research: Map<String, ResearchState>, onRetry: (String) -> Unit) {
    val t = LocalTranslator.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (place in places) {
            val state = research[place.id] ?: ResearchState.Queued
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (state == ResearchState.Queued) 0.6f else 1f)
                    .card(radius = 16.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        ResearchState.Loading -> Spinner(24.dp)
                        is ResearchState.Done -> Icon("check_circle", size = 24.dp, filled = true, tint = VmColors.Accent2)
                        is ResearchState.Error -> Icon("error", size = 24.dp, tint = VmColors.Danger)
                        ResearchState.Queued -> Icon("schedule", size = 22.dp, tint = VmColors.TextFaint)
                    }
                }
                Column(Modifier.weight(1f)) {
                    VmText(place.name, size = 14.sp, weight = FontWeight.SemiBold)
                    VmText(
                        when (state) {
                            is ResearchState.Done -> countFindings(state.result.findings).let { found ->
                                t("wizard.foundFmt", "i" to found.photos, "t" to found.facts, "l" to found.links)
                            }
                            ResearchState.Loading -> t("wizard.searching")
                            is ResearchState.Error -> t("wizard.researchFailed", "error" to state.message)
                            ResearchState.Queued -> t("wizard.queued")
                        },
                        size = 12.sp,
                        color = if (state is ResearchState.Error) VmColors.Danger else VmColors.TextMuted,
                    )
                }
                if (state is ResearchState.Error) TextBtn(t("common.retry"), { onRetry(place.id) })
            }
        }
    }
}

// ---------------------------------------------------------------- 8 review

@Composable
internal fun ReviewStep(
    place: PlaceSuggestion,
    index: Int,
    total: Int,
    result: DestinationAiResult?,
    /** which findings are ticked to be saved, by position */
    picks: List<Boolean>,
    thumbnail: String?,
    onPicks: (List<Boolean>) -> Unit,
) {
    val t = LocalTranslator.current
    val uriHandler = LocalUriHandler.current
    val findings = result?.findings.orEmpty()
    val flags = findings.indices.map { picks.getOrElse(it) { false } }
    val allOn = flags.isNotEmpty() && flags.all { it }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(thumbnail ?: findings.firstNotNullOfOrNull { it.photo }?.imageUrl, 64.dp, 14.dp)
        Column(Modifier.weight(1f)) {
            VmText(
                t("wizard.placeOf", "i" to index + 1, "n" to total).uppercase(),
                size = 12.sp,
                weight = FontWeight.SemiBold,
                color = VmColors.Accent,
                style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            )
            VmText(place.name, size = 20.sp, weight = FontWeight.Bold, lineHeight = 1.25.em)
            VmText(driveLine(place, t), size = 12.sp, color = VmColors.TextMuted)
        }
    }

    if (findings.isEmpty()) {
        DashedNote(t("wizard.nothingFound"))
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        VmText(t("wizard.tickedFmt", "k" to flags.count { it }, "n" to flags.size), size = 12.sp, color = VmColors.TextMuted)
        TextBtn(
            if (allOn) t("wizard.clearAll") else t("wizard.selectAll"),
            onClick = { onPicks(flags.map { !allOn }) },
            fontSize = 13.sp,
        )
    }

    // one card per thing found: photo, text and link together
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        findings.forEachIndexed { i, finding ->
            val on = flags[i]
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (on) Color(0xFFF1F8F7) else VmColors.Surface)
                    .border(1.5.dp, if (on) VmColors.Accent2 else VmColors.Border, shape),
            ) {
                // the link sits below the tick area, not inside it, so tapping it opens the page
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { onPicks(flags.mapIndexed { j, flag -> if (j == i) !flag else flag }) }
                        .semantics { selected = on },
                ) {
                    StripedBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val photo = finding.photo
                            if (photo != null) {
                                PhotoImage(photo.imageUrl, photo.sourceTitle, Modifier.fillMaxSize())
                            } else {
                                Icon("landscape", size = 32.dp, tint = VmColors.TextFaint)
                            }
                            // unticked: stays readable, but visibly set aside
                            if (!on) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(28.dp)
                                    .shadowSm(14.dp)
                                    .background(if (on) VmColors.Accent2 else VmColors.Text.copy(alpha = 0.35f), CircleShape)
                                    .border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(if (on) "check" else "add", size = 18.dp, tint = Color.White)
                            }
                        }
                    }
                    Column(
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val textColor = if (on) VmColors.Text else VmColors.TextMuted
                        if (finding.name.isNotEmpty()) {
                            VmText(finding.name, size = 15.sp, weight = FontWeight.Bold, color = textColor, lineHeight = 1.3.em)
                        }
                        if (finding.text.isNotEmpty()) VmText(finding.text, size = 13.sp, color = textColor, lineHeight = 1.45.em)
                    }
                }
                finding.link?.let { link ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .drawBehind { drawRect(VmColors.Border, size = Size(size.width, 1.dp.toPx())) }
                            .clickable(role = Role.Button) {
                                try {
                                    uriHandler.openUri(link.url)
                                } catch (_: Exception) {
                                    // no app can open this link
                                }
                            }
                            .defaultMinSize(minHeight = 44.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VmText(
                            domainOf(link.url),
                            Modifier.weight(1f),
                            size = 13.sp,
                            weight = FontWeight.SemiBold,
                            color = VmColors.Accent2,
                            maxLines = 1,
                        )
                        Icon("open_in_new", size = 16.dp, tint = VmColors.Accent2)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 9 plan

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanStep(plan: Plan, places: Map<String, PlaceSuggestion>, startDate: String, savedCount: Int, found: FoundTotals) {
    val t = LocalTranslator.current
    val lang = currentLang()

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.height(32.dp).background(VmColors.Accent2Tint, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("bookmark_added", size = 18.dp, tint = VmColors.Accent2)
            VmText(t("wizard.savedLine", "n" to savedCount), size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2, maxLines = 1)
        }
        Box(
            Modifier.height(32.dp).background(VmColors.Surface3, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            VmText(
                t("wizard.foundLine", "i" to found.photos, "t" to found.facts, "l" to found.links),
                size = 13.sp,
                weight = FontWeight.SemiBold,
                color = SoftText,
                maxLines = 1,
            )
        }
    }

    Column {
        plan.forEachIndexed { day, ids ->
            val stops = ids.mapNotNull { places[it] }
            val drive = dayDriveMinutes(stops.map { it.driveMinutes })
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(
                        Modifier
                            .size(44.dp)
                            .background(if (stops.isNotEmpty()) VmColors.Surface else VmColors.Surface3, CircleShape)
                            .then(if (stops.isNotEmpty()) Modifier.border(1.dp, VmColors.Border, CircleShape) else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        VmText(t("trip.day").uppercase(), size = 9.sp, weight = FontWeight.SemiBold, lineHeight = 1.em, maxLines = 1)
                        VmText((day + 1).toString(), size = 16.sp, weight = FontWeight.Bold, lineHeight = 1.em)
                    }
                    Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).defaultMinSize(minHeight = 12.dp).background(VmColors.Border))
                }
                Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VmText(formatDayLabel(addDays(startDate, day), lang), Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        Icon(if (drive > 0) "directions_car" else "beach_access", size = 16.dp, tint = VmColors.TextMuted)
                        VmText(
                            if (drive > 0) t("wizard.drivesFmt", "t" to formatDuration(drive * 60, t)) else t("wizard.noDrive"),
                            size = 12.sp,
                            color = VmColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                    for (stop in stops) {
                        Row(
                            Modifier.fillMaxWidth().card(radius = 14.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon("location_on", size = 20.dp, filled = true, tint = VmColors.Accent)
                            Column(Modifier.weight(1f)) {
                                VmText(stop.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                VmText(driveLine(stop, t), size = 12.sp, color = VmColors.TextMuted)
                            }
                        }
                    }
                    if (stops.isEmpty()) {
                        VmText(
                            if (day == 0) t("wizard.arrival") else t("wizard.freeDay"),
                            Modifier.padding(vertical = 2.dp),
                            size = 13.sp,
                            color = VmColors.TextMuted,
                        )
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth().card(radius = 16.dp).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon("tune", size = 22.dp, tint = VmColors.Accent2)
        VmText(t("wizard.adjustNote"), Modifier.weight(1f), size = 13.sp, color = SoftText, lineHeight = 1.5.em)
    }
}
