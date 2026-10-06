package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.model.MustHave
import io.github.corum86.vacationmap.model.PlaceSuggestion
import io.github.corum86.vacationmap.model.TravelGroup
import io.github.corum86.vacationmap.model.TravelStyle
import kotlin.math.max

/** Within the one-way drive limit (null = any), and not by ferry unless allowed. */
fun isReachable(s: PlaceSuggestion, maxDriveMinutes: Double?, ferry: Boolean): Boolean =
    (maxDriveMinutes == null || s.driveMinutes <= maxDriveMinutes) && (ferry || !s.ferry)

/** An answer a suggestion matched, shown as a "why" tag. */
sealed interface MatchReason {
    /** translation key of the matched answer's label */
    val labelKey: String

    data class Style(val value: TravelStyle) : MatchReason {
        override val labelKey get() = "wizard.style.${value.id}"
    }

    data class Group(val value: TravelGroup) : MatchReason {
        override val labelKey get() = "wizard.group.${value.id}"
    }

    data class Must(val value: MustHave) : MatchReason {
        override val labelKey get() = "wizard.must.${value.id}"
    }
}

data class RankedSuggestion(val suggestion: PlaceSuggestion, val score: Int, val reasons: List<MatchReason>)

data class RankingPreferences(
    val styles: List<TravelStyle>,
    val group: TravelGroup?,
    val mustHaves: List<MustHave>,
    val budget: Int?,
)

/**
 * Best matches first: +2 per matched style, +2 if the group fits, +2 per
 * matched must-have, +1 if within budget. Ties go to the shorter drive.
 */
fun rankSuggestions(suggestions: List<PlaceSuggestion>, prefs: RankingPreferences): List<RankedSuggestion> =
    suggestions
        .map { suggestion ->
            val reasons = mutableListOf<MatchReason>()
            var score = 0
            for (style in prefs.styles) {
                if (style in suggestion.styles) {
                    score += 2
                    reasons += MatchReason.Style(style)
                }
            }
            if (prefs.group != null && prefs.group in suggestion.groups) {
                score += 2
                reasons += MatchReason.Group(prefs.group)
            }
            for (must in prefs.mustHaves) {
                if (must in suggestion.mustHaves) {
                    score += 2
                    reasons += MatchReason.Must(must)
                }
            }
            if (prefs.budget != null && suggestion.budget <= prefs.budget) score += 1
            RankedSuggestion(suggestion, score, reasons)
        }
        .sortedWith(compareByDescending<RankedSuggestion> { it.score }.thenBy { it.suggestion.driveMinutes })

/** How many places fit a trip of `days` days comfortably. */
fun recommendedPlaceCount(days: Int): Int = max(1, Math.round(max(days, 1) * 0.67).toInt())
