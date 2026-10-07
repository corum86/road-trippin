package io.github.corum86.vacationmap.ui.wizard

import io.github.corum86.vacationmap.data.newId
import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.logic.formatDuration
import io.github.corum86.vacationmap.logic.nowIsoInstant
import io.github.corum86.vacationmap.model.Destination
import io.github.corum86.vacationmap.model.LinkItem
import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.PlannedPlace
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import io.github.corum86.vacationmap.net.AiFinding
import io.github.corum86.vacationmap.net.DestinationAiResult
import java.net.URI
import kotlin.math.roundToInt

/** Wizard steps in order. Research and per-place review share the "Review" segment. */
enum class WizardStep { Dates, Drive, Group, Style, Extras, Places, Research, Review, Plan }

internal val SEGMENTS = listOf(
    "wizard.seg.dates",
    "wizard.seg.drive",
    "wizard.seg.group",
    "wizard.seg.style",
    "wizard.seg.extras",
    "wizard.seg.places",
    "wizard.seg.review",
)

/** Progress segment lit for each step (the plan step fills them all). */
internal fun segmentOf(step: WizardStep): Int = when (step) {
    WizardStep.Review -> 6
    WizardStep.Plan -> SEGMENTS.size
    else -> minOf(step.ordinal, 6)
}

internal class LengthOption(val days: Int, val labelKey: String)

internal val LENGTH_OPTIONS = listOf(
    LengthOption(3, "wizard.length.weekend"),
    LengthOption(5, "wizard.length.5"),
    LengthOption(7, "wizard.length.week"),
    LengthOption(10, "wizard.length.10"),
)

/** One-way drive limits; null means distance doesn't matter. */
internal class DriveOption(val minutes: Double?, val labelKey: String)

internal val DRIVE_OPTIONS = listOf(
    DriveOption(30.0, "wizard.drive.30"),
    DriveOption(60.0, "wizard.drive.60"),
    DriveOption(90.0, "wizard.drive.90"),
    DriveOption(120.0, "wizard.drive.120"),
    DriveOption(null, "wizard.drive.any"),
)

internal val GROUP_ICONS = mapOf(
    TravelGroup.Couple to "favorite",
    TravelGroup.Family to "family_restroom",
    TravelGroup.Friends to "groups",
)

internal val STYLE_ICONS = mapOf(
    TravelStyle.Relaxed to "beach_access",
    TravelStyle.Active to "hiking",
    TravelStyle.Sightseeing to "photo_camera",
    TravelStyle.Culture to "account_balance",
    TravelStyle.Food to "restaurant",
    TravelStyle.Nature to "forest",
)

internal val MUST_HAVE_ICONS = mapOf(
    MustHave.Beach to "beach_access",
    MustHave.Food to "restaurant",
    MustHave.Kids to "child_care",
    MustHave.Nightlife to "nightlife",
)

internal val BUDGET_SYMBOLS = listOf(1 to "€", 2 to "€€", 3 to "€€€")

/** The answers so far. */
data class WizardAnswers(
    val start: String? = null,
    val end: String? = null,
    /** false until a drive limit is chosen (`drive` null then means nothing yet, not "no limit") */
    val driveChosen: Boolean = false,
    /** one-way minutes; null = distance doesn't matter */
    val drive: Double? = null,
    val ferry: Boolean = true,
    val group: TravelGroup? = null,
    val styles: List<TravelStyle> = emptyList(),
    val budget: Int? = null,
    val mustHaves: List<MustHave> = emptyList(),
)

sealed interface ResearchState {
    data object Queued : ResearchState

    data object Loading : ResearchState

    data class Done(val result: DestinationAiResult) : ResearchState

    data class Error(val message: String) : ResearchState
}

/** Every finding starts ticked: the review is for unticking what isn't wanted. */
internal fun defaultPicks(result: DestinationAiResult): List<Boolean> = List(result.findings.size) { true }

/** The findings of a researched place that are ticked to be saved. */
internal fun keptFindings(research: ResearchState?, picks: List<Boolean>?): List<AiFinding> =
    (research as? ResearchState.Done)?.result?.findings.orEmpty().filterIndexed { i, _ -> picks?.getOrNull(i) == true }

/** A finding as one line of a destination's things to do. */
internal fun attractionOf(finding: AiFinding): String =
    if (finding.name.isNotEmpty() && finding.text.isNotEmpty()) "${finding.name}: ${finding.text}" else finding.name.ifEmpty { finding.text }

/** How many photos, things to do and links the findings come to. */
internal class FoundTotals(val photos: Int, val facts: Int, val links: Int)

internal fun countFindings(findings: List<AiFinding>) =
    FoundTotals(photos = findings.count { it.photo != null }, facts = findings.size, links = findings.count { it.link != null })

/** The research service works on destinations; present a suggestion as one. */
internal fun suggestionAsDestination(s: PlaceSuggestion): Destination = Destination(id = s.id, name = s.name, location = s.location)

/**
 * What gets saved to Places for a picked suggestion: each ticked finding
 * adds its text, photo and link (a photo or link two findings share, once).
 */
internal fun toPlannedPlace(s: PlaceSuggestion, research: ResearchState?, picks: List<Boolean>?): PlannedPlace {
    val kept = keptFindings(research, picks)
    return PlannedPlace(
        id = s.id,
        name = s.name,
        location = s.location,
        notes = s.blurb.ifEmpty { null },
        routeInfo = RouteInfo(
            distanceMeters = s.distanceKm * 1000,
            durationSeconds = s.driveMinutes * 60,
            source = if (s.estimated) RouteSource.StraightLineEstimate else RouteSource.Osrm,
            fetchedAt = nowIsoInstant(),
        ),
        photos = kept.mapNotNull { it.photo }.distinctBy { it.imageUrl }.map { Photo(newId(), it.imageUrl, it.sourceTitle) },
        attractions = kept.map(::attractionOf).filter { it.isNotEmpty() }.distinct(),
        links = kept.mapNotNull { it.link }.distinctBy { it.url }.map { LinkItem(newId(), it.label, it.url) },
    )
}

/** "en.wikipedia.org" from a link URL: what a card shows for its link. */
internal fun domainOf(url: String): String = try {
    URI(url).host?.removePrefix("www.") ?: url
} catch (_: Exception) {
    url
}

/** "55 min drive · 50 km", or "… incl. ferry …" */
internal fun driveLine(s: PlaceSuggestion, t: Translator): String = t(
    if (s.ferry) "wizard.ferryFmt" else "wizard.driveFmt",
    "t" to formatDuration(s.driveMinutes * 60, t),
    "km" to s.distanceKm.roundToInt(),
)
