package io.github.corum86.vacationmap.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.model.LatLng
import io.github.corum86.vacationmap.net.PlaceMatch
import io.github.corum86.vacationmap.net.PlaceMatchKind
import io.github.corum86.vacationmap.net.SEARCH_MIN_LENGTH
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// wait for a pause in typing before asking
private const val SEARCH_DEBOUNCE_MS = 250L

private enum class SearchStatus {
    Idle,
    Loading,

    /** the matches answer the current query */
    Done,
    Failed,
}

private fun kindIcon(kind: PlaceMatchKind): String = when (kind) {
    PlaceMatchKind.Area -> "location_city"
    PlaceMatchKind.Landmark -> "landscape"
    PlaceMatchKind.Address -> "location_on"
}

/**
 * A text field that looks up what is typed as a place name and lists the
 * matches underneath. The text stays free: ignoring the matches keeps
 * whatever was typed.
 */
@Composable
fun PlaceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    /** a match was chosen; the caller decides what it fills in */
    onSelect: (PlaceMatch) -> Unit,
    placeholder: String,
    /** places around here are listed first */
    near: LatLng?,
    onDone: () -> Unit,
) {
    val t = LocalTranslator.current
    val photon = LocalServices.current.photon
    // what the user typed and wants matches for; text set from outside is not searched
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<PlaceMatch>()) }
    var status by remember { mutableStateOf(SearchStatus.Idle) }

    LaunchedEffect(query, t.lang, near) {
        if (query.length < SEARCH_MIN_LENGTH) {
            matches = emptyList()
            status = SearchStatus.Idle
            return@LaunchedEffect
        }
        // the matches of the previous letters stay up while the next ones load
        status = SearchStatus.Loading
        delay(SEARCH_DEBOUNCE_MS)
        try {
            matches = photon.searchPlaces(query, t.lang, near)
            status = SearchStatus.Done
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            matches = emptyList()
            status = SearchStatus.Failed
        }
    }

    val message = when {
        matches.isNotEmpty() -> null
        status == SearchStatus.Done -> t("form.searchEmpty")
        status == SearchStatus.Failed -> t("form.searchFailed")
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VmTextField(
            value,
            {
                onValueChange(it)
                query = it.trim()
            },
            Modifier.onFocusChanged { focused = it.isFocused },
            placeholder = placeholder,
            capitalization = KeyboardCapitalization.Words,
            onDone = onDone,
            leading = {
                if (status == SearchStatus.Loading) Spinner(20.dp) else Icon("search", size = 20.dp, tint = VmColors.TextMuted)
            },
        )
        if (focused && (matches.isNotEmpty() || message != null)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .card(radius = 14.dp)
                    // four and a half matches: the cut-off one shows there are more
                    .heightIn(max = 296.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
            ) {
                for (match in matches) {
                    MatchRow(match) {
                        onSelect(match)
                        query = ""
                    }
                }
                if (message != null) VmText(message, Modifier.padding(8.dp), size = 13.sp, color = VmColors.TextMuted)
            }
        }
    }
}

@Composable
private fun MatchRow(match: PlaceMatch, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(match.kind), size = 20.dp, tint = VmColors.Accent2)
        Column(Modifier.weight(1f)) {
            VmText(match.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (match.detail.isNotEmpty()) {
                VmText(match.detail, size = 12.sp, color = VmColors.TextMuted, maxLines = 2, lineHeight = 16.sp)
            }
        }
    }
}
