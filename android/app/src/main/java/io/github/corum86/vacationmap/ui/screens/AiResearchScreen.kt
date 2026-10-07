package io.github.corum86.vacationmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.net.AiFinding
import io.github.corum86.vacationmap.net.DestinationAiResult
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.CircleIconButton
import io.github.corum86.vacationmap.ui.components.GroundedChip
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.PhotoImage
import io.github.corum86.vacationmap.ui.components.Spinner
import io.github.corum86.vacationmap.ui.components.StripedBox
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.boxShadow
import io.github.corum86.vacationmap.ui.theme.VmColors
import io.github.corum86.vacationmap.ui.wizard.domainOf
import io.github.corum86.vacationmap.ui.wizard.withFinding

/** How far a research of every destination has got. */
class ResearchProgress(val completed: Int, val total: Int)

/**
 * The button beside "Add destination" that researches every destination with
 * AI; while it runs it counts the places done.
 */
@Composable
fun AiSearchButton(progress: ResearchProgress?, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTranslator.current
    val shape = RoundedCornerShape(18.dp)
    val label = if (progress != null) {
        t("ai.searchingProgress", "completed" to progress.completed, "total" to progress.total)
    } else {
        t("ai.search")
    }
    Row(
        Modifier
            .height(56.dp)
            .defaultMinSize(minWidth = 56.dp)
            .alpha(if (enabled || progress != null) 1f else 0.5f)
            .boxShadow(VmColors.ShadowTint.copy(alpha = 0.2f), blur = 20.dp, offsetY = 6.dp, cornerRadius = 18.dp)
            .clip(shape)
            .background(VmColors.Surface)
            .border(1.dp, VmColors.Border, shape)
            .clickable(enabled = enabled && progress == null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress != null) {
            Spinner(22.dp)
            VmText("${progress.completed}/${progress.total}", size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2)
        } else {
            Icon("auto_awesome", size = 24.dp, tint = VmColors.Accent2)
        }
    }
}

/**
 * What the AI research found, one destination at a time: a card per sight
 * with its photo, text and link, and a button that saves the three to the
 * destination.
 */
@Composable
fun AiResearchScreen(results: List<DestinationAiResult>, onClose: () -> Unit) {
    val t = LocalTranslator.current
    val store = LocalServices.current.store
    val uriHandler = LocalUriHandler.current
    var index by remember { mutableIntStateOf(0) }
    // findings saved from here, by id
    val added = remember { mutableStateMapOf<String, Boolean>() }
    val scroll = rememberScrollState()
    LaunchedEffect(index) { scroll.scrollTo(0) }
    val step = results.getOrNull(index) ?: return

    SlideUpLayer {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VmText(
                        t("stepper.counter", "current" to index + 1, "total" to results.size).uppercase(),
                        size = 12.sp,
                        weight = FontWeight.Bold,
                        color = VmColors.Accent2,
                    )
                    VmText(step.destinationName, size = 22.sp, weight = FontWeight.Bold, lineHeight = 1.25.em)
                    if (step.grounded) GroundedChip()
                }
                CircleIconButton("close", t("detail.close"), onClose, size = 44.dp, iconSize = 24.dp)
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    step.error != null -> VmText(t("stepper.error", "error" to step.error), size = 14.sp, color = VmColors.Danger)
                    step.findings.isEmpty() -> VmText(t("stepper.nothing"), size = 14.sp, color = VmColors.TextMuted)
                }
                for (finding in step.findings) {
                    FindingCard(
                        finding = finding,
                        added = added[finding.id] == true,
                        onAdd = {
                            store.updateDestination(step.destinationId) { it.withFinding(finding) }
                            added[finding.id] = true
                        },
                        onOpenLink = { url ->
                            try {
                                uriHandler.openUri(url)
                            } catch (_: Exception) {
                                // no app can open this link
                            }
                        },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VmColors.Bg)
                    .drawBehind { drawRect(VmColors.Border, size = Size(size.width, 1.dp.toPx())) }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                VmButton(t("stepper.back"), { index -= 1 }, tone = ButtonTone.TintTeal, enabled = index > 0)
                VmButton(t("stepper.next"), { index += 1 }, tone = ButtonTone.TintTeal, enabled = index < results.size - 1)
            }
        }
    }
}

@Composable
private fun FindingCard(finding: AiFinding, added: Boolean, onAdd: () -> Unit, onOpenLink: (String) -> Unit) {
    val t = LocalTranslator.current
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(VmColors.Surface).border(1.dp, VmColors.Border, shape)) {
        finding.photo?.let { photo ->
            StripedBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                PhotoImage(photo.imageUrl, photo.sourceTitle, Modifier.fillMaxSize())
            }
        }
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (finding.name.isNotEmpty()) VmText(finding.name, size = 15.sp, weight = FontWeight.Bold, lineHeight = 1.3.em)
            if (finding.text.isNotEmpty()) VmText(finding.text, size = 13.sp, color = VmColors.TextMuted, lineHeight = 1.45.em)
            finding.link?.let { link ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onOpenLink(link.url) }
                        .defaultMinSize(minHeight = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VmText(domainOf(link.url), size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.Accent2, maxLines = 1)
                    Icon("open_in_new", size = 16.dp, tint = VmColors.Accent2)
                }
            }
            Box(Modifier.padding(top = 6.dp)) {
                val name = finding.name.ifEmpty { finding.text }
                VmButton(
                    if (added) t("stepper.added") else t("stepper.add"),
                    onAdd,
                    Modifier.semantics { contentDescription = t("stepper.addFinding", "name" to name) },
                    height = 40.dp,
                    enabled = !added,
                )
            }
        }
    }
}
