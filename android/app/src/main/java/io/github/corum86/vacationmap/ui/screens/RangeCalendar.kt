package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.DateRangeSelection
import io.github.corum86.vacationmap.logic.addDays
import io.github.corum86.vacationmap.logic.addMonths
import io.github.corum86.vacationmap.logic.daysInMonth
import io.github.corum86.vacationmap.logic.formatDate
import io.github.corum86.vacationmap.logic.formatDayLabel
import io.github.corum86.vacationmap.logic.formatMonthTitle
import io.github.corum86.vacationmap.logic.rangeEndOf
import io.github.corum86.vacationmap.logic.todayIso
import io.github.corum86.vacationmap.logic.weekdayIndex
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.theme.VmColors

// any Monday: seeds the weekday header so it follows the language's letters
private const val A_MONDAY = "2026-07-13"

@Composable
internal fun currentLang(): Lang {
    val lang by LocalServices.current.language.lang.collectAsState()
    return lang
}

/** Start / End boxes; the one the next tap will set is highlighted. */
@Composable
fun DateRangeFields(range: DateRangeSelection, modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val nextIsStart = range.start == null || range.end != null
    Row(modifier.fillMaxWidth().padding(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField(t("trip.start"), range.start?.let { formatDayLabel(it, lang) }, nextIsStart, Modifier.weight(1f))
        DateField(t("trip.end"), range.end?.let { formatDayLabel(it, lang) }, !nextIsStart, Modifier.weight(1f))
    }
}

@Composable
private fun DateField(label: String, value: String?, next: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .background(VmColors.Surface, shape)
            .border(1.5.dp, if (next) VmColors.Accent2 else VmColors.Border, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        VmText(
            label.uppercase(),
            size = 11.sp,
            weight = FontWeight.SemiBold,
            color = VmColors.TextMuted,
            style = LocalTextStyle.current.copy(letterSpacing = 0.05.em),
        )
        VmText(value ?: "—", size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** One month, weeks starting Monday, with the picked range drawn as a band. */
@Composable
fun MonthCalendar(
    /** YYYY-MM */
    month: String,
    onMonthChange: (String) -> Unit,
    range: DateRangeSelection,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** inside a card that brings its own padding */
    compact: Boolean = false,
) {
    val t = LocalTranslator.current
    val lang = currentLang()
    val start = range.start
    val end = rangeEndOf(range)
    val today = todayIso()

    val cells = buildList<String?> {
        repeat(weekdayIndex("$month-01")) { add(null) }
        for (day in 1..daysInMonth(month)) add("$month-${day.toString().padStart(2, '0')}")
        while (size % 7 != 0) add(null)
    }

    Column(modifier) {
        Row(
            if (compact) Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 4.dp) else Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VmText(
                formatMonthTitle(month, lang).replaceFirstChar { it.uppercase() },
                Modifier.weight(1f),
                size = 15.sp,
                weight = FontWeight.Bold,
            )
            CircleIconButton("chevron_left", t("trip.prevMonth"), { onMonthChange(addMonths(month, -1)) })
            CircleIconButton("chevron_right", t("trip.nextMonth"), { onMonthChange(addMonths(month, 1)) })
        }

        val gridPadding = if (compact) 0.dp else 12.dp
        Row(Modifier.padding(horizontal = gridPadding).clearAndSetSemantics {}) {
            for (i in 0 until 7) {
                Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                    VmText(formatDate(addDays(A_MONDAY, i), lang, "EEEEE"), size = 12.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted)
                }
            }
        }
        Column(Modifier.padding(horizontal = gridPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (week in cells.chunked(7)) {
                Row {
                    for (date in week) {
                        if (date == null) {
                            Box(Modifier.weight(1f).height(44.dp))
                            continue
                        }
                        val isStart = date == start
                        val isEnd = date == end
                        val inRange = start != null && end != null && date >= start && date <= end && start != end
                        // the selected range is one continuous band, rounded at both ends
                        val band = when {
                            !inRange -> null
                            isStart -> RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
                            isEnd -> RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
                        val endpoint = isStart || isEnd
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .then(if (band != null) Modifier.background(VmColors.Accent2Tint, band) else Modifier)
                                .clickable(role = Role.Button) { onPick(date) }
                                .semantics {
                                    contentDescription = formatDate(date, lang, "EEEE d MMMM")
                                    selected = endpoint
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (endpoint) VmColors.Accent2 else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (endpoint) VmColors.Accent2 else if (date == today) VmColors.TextFaint else Color.Transparent,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                VmText(
                                    date.substring(8).toInt().toString(),
                                    size = 14.sp,
                                    weight = if (endpoint) FontWeight.Bold else FontWeight.Medium,
                                    color = if (endpoint) Color.White else VmColors.Text,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
