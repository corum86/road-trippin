package io.github.corum86.vacationmap.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.DateRangeSelection
import io.github.corum86.vacationmap.logic.PlanItem
import io.github.corum86.vacationmap.logic.RankingPreferences
import io.github.corum86.vacationmap.logic.addDays
import io.github.corum86.vacationmap.logic.diffDays
import io.github.corum86.vacationmap.logic.formatMonthYear
import io.github.corum86.vacationmap.logic.isReachable
import io.github.corum86.vacationmap.logic.pickRangeDate
import io.github.corum86.vacationmap.logic.planDays
import io.github.corum86.vacationmap.logic.rangeEndOf
import io.github.corum86.vacationmap.logic.rankSuggestions
import io.github.corum86.vacationmap.logic.recommendedPlaceCount
import io.github.corum86.vacationmap.logic.shortPlaceName
import io.github.corum86.vacationmap.logic.todayIso
import io.github.corum86.vacationmap.model.MainLocation
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.PlannedTrip
import io.github.corum86.vacationmap.model.TripPreferences
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.net.GeminiApiKeyMissingException
import io.github.corum86.vacationmap.net.loadSuggestions
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.TextBtn
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.screens.SlideUpLayer
import io.github.corum86.vacationmap.ui.screens.currentLang
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

// the free Gemini tier allows only a few requests a minute: research one
// place at a time, this far apart
private const val RESEARCH_SPACING_MS = 4000L

// thumbnails for the suggestion cards, fetched a few at a time
private const val THUMBNAIL_BATCH = 4

/**
 * Guided trip planning: dates, drive limit, group, style and extras, then
 * suggested places (Gemini, ranked on the device), research of the picked
 * places (Gemini facts and links, Wikimedia photos), a review of what to
 * keep, and a day-by-day plan by drive time.
 */
@Composable
fun TripWizard(
    data: VacationMapData,
    /** the planner plans around the home base, so it only opens once there is one */
    home: MainLocation,
    onClose: () -> Unit,
    onFinish: (PlannedTrip) -> Unit,
    /** open the home-base form */
    onChangeHome: () -> Unit,
) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val services = LocalServices.current
    val saved = data.trip.preferences

    var step by remember { mutableStateOf(WizardStep.Dates) }
    var month by remember { mutableStateOf(todayIso().take(7)) }
    var answers by remember {
        mutableStateOf(
            WizardAnswers(
                // a re-run starts from the last answers
                driveChosen = saved != null,
                drive = saved?.maxDriveMinutes,
                ferry = saved?.ferry ?: true,
                group = saved?.group,
                styles = saved?.styles ?: emptyList(),
                budget = saved?.budget,
                mustHaves = saved?.mustHaves ?: emptyList(),
            ),
        )
    }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var catalog by remember { mutableStateOf<Catalog>(Catalog.Loading) }
    val thumbnails = remember { mutableStateMapOf<String, String>() }
    val research = remember { mutableStateMapOf<String, ResearchState>() }
    // per place: which of its findings are ticked to be saved
    val picks = remember { mutableStateMapOf<String, List<Boolean>>() }
    var reviewIndex by remember { mutableIntStateOf(0) }
    var catalogAttempt by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val currentLanguage by rememberUpdatedState(lang)

    // ---- suggestions: asked once up front so the drive step can show counts.
    // The home base and saved places at open time are what gets planned around.
    val homeAtOpen = remember { home }
    val destinationsAtOpen = remember { data.destinations }
    LaunchedEffect(catalogAttempt) {
        catalog = Catalog.Loading
        try {
            val items = loadSuggestions(services.gemini, services.osrm, homeAtOpen, destinationsAtOpen, currentLanguage.code)
            catalog = Catalog.Ready(items)
            for (batch in items.chunked(THUMBNAIL_BATCH)) {
                val images = coroutineScope {
                    batch.map { async { services.wikimedia.fetchImagesForDestination(suggestionAsDestination(it)) } }.awaitAll()
                }
                batch.forEachIndexed { j, s -> images[j].firstOrNull()?.let { thumbnails[s.id] = it.imageUrl } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GeminiApiKeyMissingException) {
            catalog = Catalog.Failed(t("ai.unavailable"))
        } catch (e: Exception) {
            catalog = Catalog.Failed(e.message ?: "")
        }
    }

    // ---- derived answers
    val range = DateRangeSelection(answers.start, answers.end)
    val endDate = rangeEndOf(range)
    val dayCount = if (answers.start != null && endDate != null) diffDays(answers.start!!, endDate) + 1 else 0
    val items = (catalog as? Catalog.Ready)?.items ?: emptyList()
    val maxDrive = if (answers.driveChosen) answers.drive else null
    val ranked = remember(items, maxDrive, answers.ferry, answers.styles, answers.group, answers.mustHaves, answers.budget) {
        rankSuggestions(
            items.filter { isReachable(it, maxDrive, answers.ferry) },
            RankingPreferences(answers.styles, answers.group, answers.mustHaves, answers.budget),
        )
    }
    val recommended = recommendedPlaceCount(dayCount)
    val topIds = ranked.take(recommended).map { it.suggestion.id }
    // picked places, in the order they were picked, still within reach
    val reachableIds = ranked.mapTo(HashSet()) { it.suggestion.id }
    val selectedPlaces = selected.filter { it in reachableIds }.mapNotNull { id -> items.firstOrNull { it.id == id } }
    val savedIds = remember(data.destinations) { data.destinations.mapTo(HashSet()) { it.id } }
    val researchSettled = selectedPlaces.isNotEmpty() &&
        selectedPlaces.all { research[it.id] is ResearchState.Done || research[it.id] is ResearchState.Error }
    val researchAllDone = selectedPlaces.isNotEmpty() && selectedPlaces.all { research[it.id] is ResearchState.Done }

    // ---- research queue: one Gemini call at a time, spaced out
    val currentSelectedPlaces by rememberUpdatedState(selectedPlaces)
    LaunchedEffect(Unit) {
        var lastResearchAt = 0L
        while (true) {
            val next = snapshotFlow { currentSelectedPlaces.firstOrNull { research[it.id] == ResearchState.Queued } }
                .filterNotNull()
                .first()
            val wait = lastResearchAt + RESEARCH_SPACING_MS - System.currentTimeMillis()
            if (lastResearchAt > 0 && wait > 0) delay(wait)
            research[next.id] = ResearchState.Loading
            lastResearchAt = System.currentTimeMillis()
            try {
                val result = services.gemini.fetchAiFindingsForDestination(suggestionAsDestination(next), currentLanguage.code)
                if (result.error != null) {
                    research[next.id] = ResearchState.Error(result.error)
                } else {
                    research[next.id] = ResearchState.Done(result)
                    if (next.id !in picks) picks[next.id] = defaultPicks(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: GeminiApiKeyMissingException) {
                research[next.id] = ResearchState.Error(t("ai.unavailable"))
            } catch (e: Exception) {
                research[next.id] = ResearchState.Error(e.message ?: "")
            }
        }
    }

    /** Queue the picked places that haven't been researched yet (failed ones get another go). */
    fun queueResearch() {
        for (place in selectedPlaces) {
            val state = research[place.id]
            if (state !is ResearchState.Done && state != ResearchState.Loading) research[place.id] = ResearchState.Queued
        }
    }

    // ---- the plan of the last step
    val plan = remember(step, dayCount, selectedPlaces, homeAtOpen) {
        if (step == WizardStep.Plan && dayCount > 0) {
            planDays(selectedPlaces.map { PlanItem(it.id, it.location, it.driveMinutes) }, dayCount, home.location, null, 0)
        } else {
            emptyList()
        }
    }
    val found = countFindings(selectedPlaces.flatMap { keptFindings(research[it.id], picks[it.id]) })

    // ---- navigation
    LaunchedEffect(step, reviewIndex) { scroll.scrollTo(0) }

    fun goTo(next: WizardStep) {
        if (next == WizardStep.Research) queueResearch()
        if (next == WizardStep.Review) reviewIndex = 0
        step = next
    }

    fun goBack() {
        when {
            step == WizardStep.Dates -> onClose()
            step == WizardStep.Review && reviewIndex > 0 -> reviewIndex -= 1
            // research is automatic; going back skips straight to the picks
            step == WizardStep.Review -> step = WizardStep.Places
            else -> step = WizardStep.entries[step.ordinal - 1]
        }
    }

    // the back gesture steps back through the wizard; on the first step it closes it
    BackHandler(enabled = step != WizardStep.Dates) { goBack() }

    fun finish() {
        val start = answers.start ?: return
        val end = endDate ?: return
        val group = answers.group ?: return
        onFinish(
            PlannedTrip(
                name = t("wizard.tripName", "g" to t("wizard.group.${group.id}"), "m" to formatMonthYear(start, lang)),
                startDate = start,
                endDate = end,
                plan = plan,
                places = selectedPlaces.map { toPlannedPlace(it, research[it.id], picks[it.id]) },
                preferences = TripPreferences(
                    maxDriveMinutes = maxDrive,
                    ferry = answers.ferry,
                    group = group,
                    styles = answers.styles,
                    budget = answers.budget,
                    mustHaves = answers.mustHaves,
                ),
                suggestions = items,
            ),
        )
    }

    val lastReviewPlace = reviewIndex >= selectedPlaces.size - 1
    val valid = when (step) {
        WizardStep.Dates -> answers.start != null
        WizardStep.Drive -> answers.driveChosen
        WizardStep.Group -> answers.group != null
        WizardStep.Style, WizardStep.Extras, WizardStep.Plan -> true
        WizardStep.Places, WizardStep.Review -> selectedPlaces.isNotEmpty()
        WizardStep.Research -> researchSettled
    }

    fun next() {
        if (!valid) return
        when (step) {
            WizardStep.Review -> if (!lastReviewPlace) reviewIndex += 1 else goTo(WizardStep.Plan)
            WizardStep.Plan -> finish()
            else -> goTo(WizardStep.entries[step.ordinal + 1])
        }
    }

    val homeShort = shortPlaceName(home.name)
    val title = when (step) {
        WizardStep.Dates -> t("wizard.q.dates")
        WizardStep.Drive -> t("wizard.q.drive")
        WizardStep.Group -> t("wizard.q.group")
        WizardStep.Style -> t("wizard.q.style")
        WizardStep.Extras -> t("wizard.q.extras")
        WizardStep.Places -> t("wizard.q.places")
        WizardStep.Research -> if (researchAllDone) t("wizard.q.researchDone") else t("wizard.q.research")
        WizardStep.Review -> t("wizard.q.review")
        WizardStep.Plan -> t("wizard.q.plan", "n" to dayCount)
    }
    val subtitle = when (step) {
        WizardStep.Dates -> t("wizard.s.dates")
        WizardStep.Drive -> t("wizard.s.drive", "home" to homeShort)
        WizardStep.Group -> t("wizard.s.group")
        WizardStep.Style -> t("wizard.s.style")
        WizardStep.Extras -> t("wizard.s.extras")
        WizardStep.Places -> t("wizard.s.places", "n" to ranked.size)
        WizardStep.Research -> t("wizard.s.research")
        WizardStep.Review -> t("wizard.s.review")
        WizardStep.Plan -> t("wizard.s.plan", "home" to homeShort)
    }
    val primaryLabel = when (step) {
        WizardStep.Places -> t("wizard.find", "k" to selectedPlaces.size)
        WizardStep.Research -> t("wizard.review")
        WizardStep.Review -> if (lastReviewPlace) t("wizard.save", "k" to selectedPlaces.size) else t("wizard.nextPlace")
        WizardStep.Plan -> t("wizard.openTrip")
        else -> t("wizard.continue")
    }
    val primaryIcon = when (step) {
        WizardStep.Plan -> "check"
        WizardStep.Places -> "auto_awesome"
        else -> "arrow_forward"
    }
    val optional = step == WizardStep.Style || step == WizardStep.Extras
    val showPrevious = step == WizardStep.Review && reviewIndex > 0
    val segment = segmentOf(step)

    SlideUpLayer {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step != WizardStep.Dates) {
                    CircleIconButton("arrow_back", t("detail.back"), ::goBack, size = 44.dp, iconSize = 24.dp)
                } else {
                    Spacer(Modifier.size(44.dp))
                }
                VmText(
                    if (step != WizardStep.Plan) "${t("wizard.step", "n" to segment + 1, "m" to SEGMENTS.size)} · ${t(SEGMENTS[segment])}" else "",
                    Modifier.weight(1f),
                    size = 13.sp,
                    weight = FontWeight.SemiBold,
                    color = VmColors.TextMuted,
                    maxLines = 1,
                    style = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                )
                CircleIconButton("close", t("detail.close"), onClose, size = 44.dp, iconSize = 24.dp)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp).clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (i in SEGMENTS.indices) {
                    val color = if (i < segment) VmColors.Accent2 else if (i == segment) VmColors.Accent else VmColors.Border
                    Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VmText(title, Modifier.semantics { heading() }, size = 24.sp, weight = FontWeight.Bold, lineHeight = 1.2.em)
                    VmText(subtitle, size = 14.sp, color = VmColors.TextMuted)
                }

                when (step) {
                    WizardStep.Dates -> DatesStep(
                        home = home,
                        range = range,
                        month = month,
                        dayCount = dayCount,
                        onMonthChange = { month = it },
                        onPick = { date ->
                            val picked = pickRangeDate(range, date)
                            answers = answers.copy(start = picked.start, end = picked.end)
                        },
                        onLength = { days ->
                            // from the picked start, else from today (or the shown month if it's later)
                            val today = todayIso()
                            val base = answers.start ?: if (month <= today.take(7)) today else "$month-01"
                            answers = answers.copy(start = base, end = addDays(base, days - 1))
                            month = base.take(7)
                        },
                        onChangeHome = onChangeHome,
                    )

                    WizardStep.Drive -> DriveStep(
                        answers = answers,
                        countFor = { minutes ->
                            when (val current = catalog) {
                                is Catalog.Ready -> current.items.count { isReachable(it, minutes, answers.ferry) }
                                Catalog.Loading -> null
                                is Catalog.Failed -> UNAVAILABLE
                            }
                        },
                        onDrive = { minutes -> answers = answers.copy(driveChosen = true, drive = minutes) },
                        onToggleFerry = { answers = answers.copy(ferry = !answers.ferry) },
                    )

                    WizardStep.Group -> GroupStep(answers.group) { answers = answers.copy(group = it) }

                    WizardStep.Style -> StyleStep(answers.styles) { style ->
                        answers = answers.copy(styles = if (style in answers.styles) answers.styles - style else answers.styles + style)
                    }

                    WizardStep.Extras -> ExtrasStep(
                        budget = answers.budget,
                        mustHaves = answers.mustHaves,
                        // tapping the chosen budget again clears it
                        onBudget = { level -> answers = answers.copy(budget = if (answers.budget == level) null else level) },
                        onToggleMust = { must ->
                            answers = answers.copy(mustHaves = if (must in answers.mustHaves) answers.mustHaves - must else answers.mustHaves + must)
                        },
                    )

                    WizardStep.Places -> PlacesStep(
                        home = home,
                        catalog = catalog,
                        ranked = ranked,
                        selected = selected,
                        savedIds = savedIds,
                        topIds = topIds,
                        thumbnails = thumbnails,
                        dayCount = dayCount,
                        recommended = recommended,
                        onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                        onPickTop = { selected = topIds },
                        onRetry = { catalogAttempt += 1 },
                    )

                    WizardStep.Research -> ResearchStep(selectedPlaces, research) { id -> research[id] = ResearchState.Queued }

                    WizardStep.Review -> {
                        val index = reviewIndex.coerceAtMost(selectedPlaces.size - 1)
                        val place = selectedPlaces.getOrNull(index)
                        if (place != null) {
                            ReviewStep(
                                place = place,
                                index = index,
                                total = selectedPlaces.size,
                                result = (research[place.id] as? ResearchState.Done)?.result,
                                picks = picks[place.id] ?: emptyList(),
                                thumbnail = thumbnails[place.id],
                                onPicks = { picks[place.id] = it },
                            )
                        }
                    }

                    WizardStep.Plan -> answers.start?.let { start ->
                        PlanStep(
                            plan = plan,
                            places = selectedPlaces.associateBy { it.id },
                            startDate = start,
                            savedCount = selectedPlaces.size,
                            found = found,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VmColors.Bg)
                    .drawBehind { drawRect(VmColors.Border, size = Size(size.width, 1.dp.toPx())) }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (optional) TextBtn(t("wizard.skip"), { goTo(WizardStep.entries[step.ordinal + 1]) }, Modifier.height(48.dp))
                if (showPrevious) TextBtn(t("wizard.prevPlace"), ::goBack, Modifier.height(48.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val background = when {
                        !valid -> VmColors.TextFaint
                        step == WizardStep.Plan -> VmColors.Accent
                        else -> VmColors.Accent2
                    }
                    Row(
                        Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(background)
                            .clickable(enabled = valid, role = Role.Button, onClick = ::next),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VmText(primaryLabel, size = 15.sp, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                        Icon(primaryIcon, size = 20.dp, tint = Color.White)
                    }
                }
            }
        }
    }
}
