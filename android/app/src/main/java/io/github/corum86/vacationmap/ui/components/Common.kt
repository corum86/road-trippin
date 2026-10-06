package io.github.corum86.vacationmap.ui.components

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.model.Photo
import io.github.corum86.vacationmap.model.TripStatus
import io.github.corum86.vacationmap.ui.theme.VmColors

/** Background and text colour of a pill button. */
enum class ButtonTone(val background: Color, val content: Color) {
    Primary(VmColors.Accent2, Color.White),
    Coral(VmColors.Accent, Color.White),
    Danger(VmColors.Danger, Color.White),
    TintCoral(VmColors.AccentTint, VmColors.Accent),
    TintTeal(VmColors.Accent2Tint, VmColors.Accent2),
    TintDanger(VmColors.DangerTint, VmColors.Danger),
    Disabled(VmColors.Surface3, VmColors.TextFaint),

    /** a primary action that isn't available yet: reads as switched off rather than faded */
    Faint(VmColors.TextFaint, Color.White),
}

/** The design's pill button: 46 high, 14/600, optional leading icon. */
@Composable
fun VmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Primary,
    height: Dp = 46.dp,
    icon: String? = null,
    trailingIcon: String? = null,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
    horizontalPadding: Dp = 16.dp,
    iconSize: Dp = 20.dp,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .defaultMinSize(minHeight = height)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(shape)
            .background(tone.background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, size = iconSize, tint = tone.content)
        VmText(text, size = fontSize, weight = FontWeight.SemiBold, color = tone.content, maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, size = iconSize, tint = tone.content)
    }
}

/** A plain text button: 40 high, teal 600. */
@Composable
fun TextBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = VmColors.Accent2,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        VmText(text, size = fontSize, weight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** A round icon button. */
@Composable
fun CircleIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = VmColors.Text,
    background: Color = Color.Transparent,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, size = iconSize, tint = tint, filled = filled)
    }
}

/** Small uppercase teal heading above a group of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = VmColors.Accent2) {
    VmText(
        text.uppercase(),
        modifier,
        size = 12.sp,
        weight = FontWeight.Bold,
        color = color,
        style = LocalTextStyle.current.copy(letterSpacing = 0.06.em),
    )
}

@Composable
fun StatusDot(status: TripStatus, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .background(if (status == TripStatus.Visited) VmColors.Accent2 else VmColors.Accent, CircleShape),
    )
}

@Composable
fun statusLabel(status: TripStatus): String =
    LocalTranslator.current(if (status == TripStatus.Visited) "status.visited" else "status.planned")

@Composable
fun StatusChip(status: TripStatus, modifier: Modifier = Modifier) {
    val visited = status == TripStatus.Visited
    Box(
        modifier
            .background(if (visited) VmColors.Accent2Tint else VmColors.AccentTint, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        VmText(
            statusLabel(status),
            size = 11.sp,
            weight = FontWeight.SemiBold,
            color = if (visited) VmColors.Accent2 else VmColors.Accent,
            maxLines = 1,
        )
    }
}

/** White card with the design's hairline border. */
fun Modifier.card(radius: Dp = 18.dp, background: Color = VmColors.Surface, border: Color = VmColors.Border): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.background(background, shape).border(1.dp, border, shape)
}

class SegmentOption<T>(val value: T, val label: String, val icon: String? = null, val activeColor: Color = VmColors.Accent2)

/** Segmented control: language, trip status. */
@Composable
fun <T> Segmented(options: List<SegmentOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(VmColors.Surface3, RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val active = option.value == selected
            val color = if (active) option.activeColor else VmColors.TextMuted
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (active) Modifier.shadowSm(12.dp).background(VmColors.Surface, shape) else Modifier)
                    .clip(shape)
                    .clickable(role = Role.RadioButton) { onSelect(option.value) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (option.icon != null) Icon(option.icon, size = 18.dp, tint = color, filled = active)
                VmText(option.label, size = 14.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1)
            }
        }
    }
}

/** A `data:` URL's bytes, or null if it isn't a base64 data URL. */
internal fun decodeDataUrl(url: String): ByteArray? {
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
    return try {
        Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * A photo by URL. The traveller's own photos are stored inside the data as
 * `data:` URLs (so they sync with it); those are decoded here.
 */
@Composable
fun PhotoImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val model: Any = remember(url) { decodeDataUrl(url) ?: url }
    AsyncImage(model = model, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}

/** The striped stand-in shown where a place has no photo: 6dp diagonal bands. */
@Composable
private fun stripesBrush(): Brush {
    val period = with(LocalDensity.current) { 12.dp.toPx() } / 1.41421356f
    return remember(period) {
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
}

/** Square photo slot: the destination's first photo, or a striped placeholder. */
@Composable
fun PhotoThumb(photo: Photo?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(modifier.clip(shape).background(stripesBrush()), contentAlignment = Alignment.Center) {
        if (photo != null) {
            PhotoImage(photo.url, photo.caption, Modifier.fillMaxSize())
        } else {
            Icon("landscape", size = 22.dp, tint = VmColors.TextMuted)
        }
    }
}

/** A striped box for photo areas of any shape (hero, tiles). */
@Composable
fun StripedBox(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Box(modifier.background(stripesBrush()), contentAlignment = Alignment.Center) { content() }
}

/** Runs `content` with the given content colour (for icons that default to it). */
@Composable
fun WithContentColor(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)
