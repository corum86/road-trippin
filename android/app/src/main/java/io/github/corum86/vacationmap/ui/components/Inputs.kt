package io.github.corum86.vacationmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.ui.theme.VmColors

/**
 * The design's text input: white, hairline border, teal border and halo when
 * focused. `lines > 1` makes it a text area of at least that many lines.
 */
@Composable
fun VmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    height: Dp = 50.dp,
    radius: Dp = 14.dp,
    horizontalPadding: Dp = 14.dp,
    fontSize: TextUnit = 15.sp,
    background: Color = VmColors.Surface,
    mono: Boolean = false,
    lines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    /** the keyboard's action key on a single-line field */
    onDone: (() -> Unit)? = null,
    /** drawn inside a single-line field, before the text: an icon saying what the field does */
    leading: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    val multiline = lines > 1
    val style = LocalTextStyle.current.copy(
        fontSize = fontSize,
        color = VmColors.Text,
        fontFamily = if (mono) FontFamily.Monospace else LocalTextStyle.current.fontFamily,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = height)
            // the focus halo: 3dp of teal at 18%, drawn around the field without moving it
            .drawBehind {
                if (!focused) return@drawBehind
                val halo = 3.dp.toPx()
                drawRoundRect(
                    VmColors.Accent2.copy(alpha = 0.18f),
                    topLeft = Offset(-halo / 2, -halo / 2),
                    size = Size(size.width + halo, size.height + halo),
                    cornerRadius = CornerRadius(radius.toPx() + halo / 2),
                    style = Stroke(halo),
                )
            }
            .background(background, shape)
            .border(1.dp, if (focused) VmColors.Accent2 else VmColors.Border, shape)
            .onFocusChanged { focused = it.isFocused },
        textStyle = style,
        singleLine = !multiline,
        minLines = lines,
        cursorBrush = SolidColor(VmColors.Accent2),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            imeAction = if (multiline) ImeAction.Default else if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = onDone?.let { done -> { done() } }),
        decorationBox = { field ->
            val padding = Modifier.padding(horizontal = horizontalPadding, vertical = if (multiline) 12.dp else 0.dp)
            val text: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier, contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        VmText(placeholder, size = fontSize, color = VmColors.TextFaint, maxLines = if (multiline) 3 else 1, style = style)
                    }
                    field()
                }
            }
            if (leading == null) {
                text(padding)
            } else {
                Row(padding, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    leading()
                    text(Modifier.weight(1f))
                }
            }
        },
    )
}

/** The design's compact input (40 high), used in rows. */
@Composable
fun VmTextFieldSmall(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onDone: (() -> Unit)? = null,
) = VmTextField(
    value,
    onValueChange,
    modifier,
    placeholder,
    height = 40.dp,
    radius = 12.dp,
    horizontalPadding = 10.dp,
    fontSize = 14.sp,
    mono = mono,
    keyboardType = keyboardType,
    capitalization = capitalization,
    onDone = onDone,
)
