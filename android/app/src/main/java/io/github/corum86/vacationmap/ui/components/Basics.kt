package io.github.corum86.vacationmap.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.map.blurRadiusForSigma
import io.github.corum86.vacationmap.ui.theme.MaterialSymbols
import io.github.corum86.vacationmap.ui.theme.MaterialSymbolsFilled
import io.github.corum86.vacationmap.ui.theme.VmColors

/**
 * A Material Symbols Rounded icon by its ligature name, e.g. "arrow_back".
 * Sized in dp: icons don't grow with the system font scale.
 */
@Composable
fun Icon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    filled: Boolean = false,
    tint: Color = LocalContentColor.current,
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        BasicText(
            text = name,
            style = TextStyle(
                fontFamily = if (filled) MaterialSymbolsFilled else MaterialSymbols,
                fontSize = fontSize,
                lineHeight = 1.em,
                color = tint,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The spinning "working on it" icon. */
@Composable
fun Spinner(size: Dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Icon("progress_activity", Modifier.rotate(angle), size = size, tint = VmColors.Accent2)
}

/** Text in the app's type scale: size and weight as the design names them ("15/700"). */
@Composable
fun VmText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = VmColors.Text,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
    )
}

/**
 * A CSS-style box shadow (offset + blur + colour) behind a rounded box:
 * the design's shadows are tinted and soft, which elevation shadows aren't.
 */
fun Modifier.boxShadow(
    color: Color,
    blur: Dp,
    offsetY: Dp = 0.dp,
    cornerRadius: Dp = 0.dp,
): Modifier = drawBehind {
    val blurPx = blur.toPx()
    val paint = AndroidPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        // a CSS blur radius is twice the Gaussian's standard deviation
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurRadiusForSigma(blurPx / 2f), BlurMaskFilter.Blur.NORMAL)
    }
    val dy = offsetY.toPx()
    val radius = cornerRadius.toPx().coerceAtMost(size.minDimension / 2)
    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, radius, radius, paint) }
}

fun Modifier.shadowSm(cornerRadius: Dp) = boxShadow(VmColors.ShadowTint.copy(alpha = 0.12f), 3.dp, 1.dp, cornerRadius)

fun Modifier.shadowMd(cornerRadius: Dp) = boxShadow(VmColors.ShadowTint.copy(alpha = 0.14f), 16.dp, 4.dp, cornerRadius)

fun Modifier.shadowLg(cornerRadius: Dp) = boxShadow(VmColors.ShadowTint.copy(alpha = 0.18f), 28.dp, 8.dp, cornerRadius)
