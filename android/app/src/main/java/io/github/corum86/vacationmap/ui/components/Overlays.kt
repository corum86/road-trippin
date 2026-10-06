package io.github.corum86.vacationmap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.delay

/** Material 3 "emphasized" easing for screens and sheets sliding in. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// ---------------------------------------------------------------------------
// Toast

private const val TOAST_MS = 2600L

// long enough to read the message and reach for the action
private const val TOAST_WITH_ACTION_MS = 5000L

class ToastAction(val label: String, val onAction: () -> Unit)

class ToastData(val id: Long, val message: String, val isError: Boolean, val action: ToastAction?)

/** One transient message at a time; a new one replaces the current. */
@Stable
class ToastController {
    var toast by mutableStateOf<ToastData?>(null)
        private set
    private var nextId = 0L

    fun show(message: String, isError: Boolean = false, action: ToastAction? = null) {
        toast = ToastData(nextId++, message, isError, action)
    }

    fun dismiss() {
        toast = null
    }
}

/** The dark pill at the bottom of the screen. Hides itself after a few seconds. */
@Composable
fun ToastView(controller: ToastController, modifier: Modifier = Modifier) {
    val toast = controller.toast ?: return
    val rise = remember(toast.id) { Animatable(0f) }
    LaunchedEffect(toast.id) {
        rise.animateTo(1f, tween(250, easing = EmphasizedEasing))
        delay(if (toast.action != null) TOAST_WITH_ACTION_MS else TOAST_MS)
        controller.dismiss()
    }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value
                translationY = (1 - rise.value) * 16.dp.toPx()
            }
            .boxShadow(Color.Black.copy(alpha = 0.25f), blur = 24.dp, offsetY = 8.dp, cornerRadius = 14.dp)
            .background(VmColors.Text, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (toast.isError) "error" else "check_circle",
            size = 20.dp,
            tint = if (toast.isError) Color(0xFFFF8A93) else VmColors.Accent2Bright,
        )
        VmText(toast.message, Modifier.weight(1f), size = 13.sp, color = Color.White)
        toast.action?.let { action ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        action.onAction()
                        controller.dismiss()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                VmText(action.label, size = 13.sp, weight = FontWeight.Bold, color = VmColors.Accent2Bright)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sheets and dialogs

/**
 * A scrim that closes on tap, with `content` over it. The scrim is a sibling
 * of the content, not its parent, so screen readers see the content's own
 * elements rather than one merged button.
 */
@Composable
internal fun Scrim(alpha: Float, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(150)) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .background(VmColors.Scrim.copy(alpha = alpha * fade.value))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        )
        content()
    }
}

/** Swallows taps so they don't fall through to whatever lies behind. */
internal fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * Modal surface for short tasks, sliding up from the bottom edge over a
 * scrim. Closes on a scrim tap (and on back, through the layer stack).
 */
@Composable
fun BottomSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(250, easing = EmphasizedEasing)) }
    Scrim(alpha = 0.45f, onClick = onClose) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .graphicsLayer { translationY = slide.value * size.height },
            verticalArrangement = Arrangement.Bottom,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(VmColors.Bg)
                    .consumeTaps()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .semantics { paneTitle = title },
            ) {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(VmColors.StarEmpty, RoundedCornerShape(2.dp)),
                )
                content()
            }
        }
    }
}

/** Asks before something that can't be undone. Closes on a backdrop tap. */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    /** what confirming does, when the title alone doesn't say */
    body: String? = null,
) {
    val t = LocalTranslator.current
    Scrim(alpha = 0.5f, onClick = onCancel) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(VmColors.Bg)
                .consumeTaps()
                .padding(24.dp)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VmText(title, size = 20.sp, weight = FontWeight.Bold)
            if (body != null) VmText(body, Modifier.offset(y = (-8).dp), size = 14.sp, color = VmColors.TextMuted)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextBtn(t("form.cancel"), onCancel)
                VmButton(confirmLabel, onConfirm, tone = ButtonTone.Danger, height = 40.dp, horizontalPadding = 18.dp)
            }
        }
    }
}

@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) = VmText(text, modifier, size = 20.sp, weight = FontWeight.Bold)

@Composable
fun SheetHint(text: String, modifier: Modifier = Modifier) = VmText(text, modifier, size = 13.sp, color = VmColors.TextMuted)
