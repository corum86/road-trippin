package io.github.corum86.vacationmap.logic

import io.github.corum86.vacationmap.i18n.Lang
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Calendar dates are plain ISO strings (YYYY-MM-DD), as in the web app, so the
// stored data is identical. All arithmetic is on calendar dates: nothing here
// depends on the device's time zone except "today".

private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

private fun localeOf(lang: Lang): Locale = when (lang) {
    Lang.En -> Locale.forLanguageTag("en-GB")
    Lang.El -> Locale.forLanguageTag("el-GR")
}

/** Today as a local ISO date (YYYY-MM-DD). */
fun todayIso(): String = LocalDate.now().toString()

fun isIsoDate(value: String?): Boolean = value != null && ISO_DATE.matches(value)

/**
 * Like the web app's Date.UTC parse, out-of-range months and days roll over
 * ("2026-02-31" is 3 March) instead of failing, so data that was accepted
 * there reads the same here.
 */
internal fun parseIso(iso: String): LocalDate {
    val parts = iso.take(10).split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 1970
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 1
    return LocalDate.of(year, 1, 1).plusMonths(month - 1L).plusDays(day - 1L)
}

fun addDays(iso: String, days: Int): String = parseIso(iso).plusDays(days.toLong()).toString()

/** Whole days from `a` to `b` (negative when `b` is earlier). */
fun diffDays(a: String, b: String): Int = ChronoUnit.DAYS.between(parseIso(a), parseIso(b)).toInt()

private fun parseMonth(month: String): YearMonth {
    val parts = month.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 1970
    val monthOfYear = parts.getOrNull(1)?.toIntOrNull() ?: 1
    return YearMonth.of(year, 1).plusMonths(monthOfYear - 1L)
}

/** Shift a month key (YYYY-MM) by `months`. */
fun addMonths(month: String, months: Int): String = parseMonth(month).plusMonths(months.toLong()).toString()

/** Monday-first weekday index (0 = Monday) of an ISO date. */
fun weekdayIndex(iso: String): Int = parseIso(iso).dayOfWeek.value - 1

fun daysInMonth(month: String): Int = parseMonth(month).lengthOfMonth()

/** Format an ISO date with a java.time pattern in the app language. */
fun formatDate(iso: String, lang: Lang, pattern: String): String {
    if (!isIsoDate(iso.take(10))) return iso
    return parseIso(iso).format(DateTimeFormatter.ofPattern(pattern, localeOf(lang)))
}

/** "Tue 14 Jul" */
fun formatDayLabel(iso: String, lang: Lang): String = formatDate(iso, lang, "EEE d MMM")

/** "14 Jul" */
fun formatShortDate(iso: String, lang: Lang): String = formatDate(iso, lang, "d MMM")

/** "July 2026" for a month key (YYYY-MM) */
fun formatMonthTitle(month: String, lang: Lang): String = formatDate("$month-01", lang, "LLLL yyyy")

/** "Jul 2026" */
fun formatMonthYear(iso: String, lang: Lang): String = formatDate(iso, lang, "MMM yyyy")

/** "13–18 Jul 2026", across months "30 Jun–4 Jul 2026", a single day "13 Jul 2026" */
fun formatDateRange(startIso: String, endIso: String, lang: Lang): String {
    val year = formatDate(endIso, lang, "yyyy")
    if (startIso == endIso) return "${formatShortDate(startIso, lang)} $year"
    val sameMonth = startIso.take(7) == endIso.take(7)
    val start = if (sameMonth) formatDate(startIso, lang, "d") else formatShortDate(startIso, lang)
    return "$start–${formatShortDate(endIso, lang)} $year"
}
