package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.i18n.Translator
import io.github.corum86.vacationmap.model.RouteInfo
import io.github.corum86.vacationmap.model.RouteSource
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One decimal, rounded from the number's exact value like JavaScript's
 * toFixed (String.format rounds its shortest decimal form instead, which
 * turns 0.95 into "1.0" where the web app shows "0.9").
 */
private fun toFixed1(value: Double): String = BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()

fun formatDistance(meters: Double, t: Translator): String = "${toFixed1(meters / 1000)} ${t("units.km")}"

fun formatDuration(seconds: Double, t: Translator): String {
    val totalMinutes = Math.round(seconds / 60)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (hours == 0L) return "$minutes ${t("units.minutes")}"
    if (minutes == 0L) return "$hours ${t("units.hours")}"
    return "$hours ${t("units.hours")} $minutes ${t("units.minutes")}"
}

fun isEstimate(info: RouteInfo): Boolean = info.source == RouteSource.StraightLineEstimate

/** Short " (est.)" suffix for straight-line estimates, empty for real routes. */
fun estimateSuffix(info: RouteInfo, t: Translator): String =
    if (isEstimate(info)) " (${t("routes.estimatedShort")})" else ""

/** "24.9 km · 30 min", plus " (est.)" for straight-line estimates. */
fun formatRouteSummary(info: RouteInfo, t: Translator): String =
    "${formatDistance(info.distanceMeters, t)} · ${formatDuration(info.durationSeconds, t)}${estimateSuffix(info, t)}"

// ---------------------------------------------------------------------------
// Map image export options

enum class AspectRatioId(val label: String, val ratio: Double?) {
    Free("free", null),
    Wide("16:9", 16.0 / 9),
    Classic("4:3", 4.0 / 3),
    Square("1:1", 1.0),
}

enum class MapOrientation { Landscape, Portrait }

data class ExportOptions(
    val aspect: AspectRatioId = AspectRatioId.Wide,
    val orientation: MapOrientation = MapOrientation.Landscape,
    /** output pixels per layout unit: 2, 3 or 4 */
    val quality: Int = 2,
)

/** width / height for the aspect and orientation, or null for "free" */
fun exportRatio(aspect: AspectRatioId, orientation: MapOrientation): Double? {
    val ratio = aspect.ratio ?: return null
    return if (orientation == MapOrientation.Portrait) 1 / ratio else ratio
}

/** "16:9 · Landscape", "1:1", "Free" — orientation only where it matters. */
fun exportAspectLabel(
    aspect: AspectRatioId,
    orientation: MapOrientation,
    t: Translator,
    withOrientation: Boolean,
): String {
    if (aspect == AspectRatioId.Free) return t("export.free")
    if (!withOrientation || aspect == AspectRatioId.Square) return aspect.label
    val side = if (orientation == MapOrientation.Portrait) t("export.portrait") else t("export.landscape")
    return "${aspect.label} · $side"
}

/** How home → destination connections are drawn on the map. */
enum class RouteDisplayMode { Arrows, Routes, Points }

// ---------------------------------------------------------------------------
// Calendar range picking

/** A date range being picked on the calendar; `end` is null until the second tap. */
data class DateRangeSelection(val start: String?, val end: String?)

/**
 * The calendar's tap logic: the first tap sets the start, the second the end
 * (a date before the start replaces the start instead), and a tap after both
 * are set starts over.
 */
fun pickRangeDate(range: DateRangeSelection, date: String): DateRangeSelection {
    if (range.start == null || range.end != null) return DateRangeSelection(date, null)
    if (date < range.start) return DateRangeSelection(date, null)
    return DateRangeSelection(range.start, date)
}

/** The effective last day: a lone start is a one-day trip; capped at the maximum length. */
fun rangeEndOf(range: DateRangeSelection): String? =
    range.start?.let { clampTripEnd(it, range.end ?: it) }
