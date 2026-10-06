package io.github.corum86.vacationmap.ui.screens

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.corum86.vacationmap.data.ConnectResult
import io.github.corum86.vacationmap.data.LocalServices
import io.github.corum86.vacationmap.data.SyncStatus
import io.github.corum86.vacationmap.data.readTextFile
import io.github.corum86.vacationmap.data.writeTextFile
import io.github.corum86.vacationmap.i18n.Lang
import io.github.corum86.vacationmap.i18n.LocalTranslator
import io.github.corum86.vacationmap.logic.AspectRatioId
import io.github.corum86.vacationmap.logic.ExportOptions
import io.github.corum86.vacationmap.logic.InvalidVacationDataException
import io.github.corum86.vacationmap.logic.exportAspectLabel
import io.github.corum86.vacationmap.model.AppJson
import io.github.corum86.vacationmap.model.VacationMapData
import io.github.corum86.vacationmap.ui.components.ButtonTone
import io.github.corum86.vacationmap.ui.components.Icon
import io.github.corum86.vacationmap.ui.components.SectionLabel
import io.github.corum86.vacationmap.ui.components.SegmentOption
import io.github.corum86.vacationmap.ui.components.Segmented
import io.github.corum86.vacationmap.ui.components.VmButton
import io.github.corum86.vacationmap.ui.components.VmText
import io.github.corum86.vacationmap.ui.components.VmTextFieldSmall
import io.github.corum86.vacationmap.ui.components.card
import io.github.corum86.vacationmap.ui.theme.VmColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.Locale

private val QUALITIES = listOf(2, 3, 4)
private const val COPIED_MS = 2000L

// exported files are meant to be read (and edited) by people too
private val PrettyJson = Json(AppJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** A question the shell asks in a dialog before running `onConfirm`. */
class ConfirmRequest(val title: String, val body: String?, val confirmLabel: String, val onConfirm: () -> Unit)

/** The Settings tab: home base, language, map image export, cloud sync and data. */
@Composable
fun SettingsScreen(
    data: VacationMapData,
    exportOptions: ExportOptions,
    onExportOptionsChange: (ExportOptions) -> Unit,
    exporting: Boolean,
    onExport: () -> Unit,
    onEditHome: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (ConfirmRequest) -> Unit,
) {
    val t = LocalTranslator.current
    val services = LocalServices.current
    val lang by services.language.lang.collectAsState()
    val syncStatus by services.cloudSync.status.collectAsState()
    val cloudSync = syncStatus != SyncStatus.Unavailable
    val home = data.mainLocation

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // the sections' gap follows the title, so it brings no bottom padding of its own
        ScreenTitle(t("tabs.settings"), bottomPadding = 0.dp)

        SettingsSection(t("form.homeBase")) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .card()
                    .clickable(role = Role.Button, onClick = onEditHome)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeBadge(40.dp)
                Column(Modifier.weight(1f)) {
                    if (home != null) {
                        VmText(home.name, size = 14.sp, weight = FontWeight.SemiBold)
                        VmText(
                            String.format(Locale.ROOT, "%.5f, %.5f", home.location.lat, home.location.lng),
                            size = 12.sp,
                            color = VmColors.TextMuted,
                            style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        )
                    } else {
                        VmText(t("settings.homeUnset"), size = 14.sp, weight = FontWeight.SemiBold)
                        VmText(t("settings.homeUnsetHint"), size = 12.sp, color = VmColors.TextMuted)
                    }
                }
                Icon(if (home != null) "edit" else "add_location_alt", size = 20.dp, tint = VmColors.TextMuted)
            }
        }

        SettingsSection(t("settings.language")) {
            Segmented(
                options = listOf(SegmentOption(Lang.En, t("lang.english")), SegmentOption(Lang.El, t("lang.greek"))),
                selected = lang,
                onSelect = { services.language.set(it) },
            )
        }

        SettingsSection(t("export.button")) {
            Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OptionGroup(t("export.aspect")) {
                    for (aspect in AspectRatioId.entries) {
                        OptionButton(
                            exportAspectLabel(aspect, exportOptions.orientation, t, withOrientation = false),
                            active = exportOptions.aspect == aspect,
                            onClick = { onExportOptionsChange(exportOptions.copy(aspect = aspect)) },
                        )
                    }
                }
                OptionGroup(t("export.qualityLabel")) {
                    for (quality in QUALITIES) {
                        OptionButton(
                            "${quality}x",
                            active = exportOptions.quality == quality,
                            onClick = { onExportOptionsChange(exportOptions.copy(quality = quality)) },
                        )
                    }
                }
                VmButton(
                    if (exporting) t("export.exporting") else t("export.short"),
                    onExport,
                    Modifier.fillMaxWidth(),
                    ButtonTone.Coral,
                    icon = "download",
                    enabled = !exporting,
                )
            }
        }

        if (cloudSync) {
            SettingsSection(t("sync.title")) {
                CloudSyncCard(syncStatus, onConfirm)
                SettingsHint(t("sync.hint"))
            }
        }

        SettingsSection(t("settings.data")) {
            DataControls(data, onReset)
            if (!cloudSync) SettingsHint(t("settings.dataHint"))
        }
    }
}

/** The round sun-coloured badge that stands for the home base. */
@Composable
fun HomeBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Brush.linearGradient(listOf(VmColors.HomeGradientStart, VmColors.AccentSoft)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon("star", size = size * 0.5f, filled = true, tint = Color.White)
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label, Modifier.padding(horizontal = 4.dp))
        content()
    }
}

@Composable
private fun SettingsHint(text: String) =
    VmText(text, Modifier.padding(horizontal = 4.dp), size = 12.sp, color = VmColors.TextMuted)

@Composable
private fun OptionGroup(label: String, options: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = options)
    }
}

/** A Settings option: one of a row of equal buttons, or (with `wrap`) one sized to its label. */
@Composable
private fun RowScope.OptionButton(
    label: String,
    active: Boolean = false,
    color: Color = VmColors.Text,
    enabled: Boolean = true,
    wrap: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .then(if (wrap) Modifier else Modifier.weight(1f))
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(shape)
            .background(if (active) VmColors.Accent2Tint else VmColors.Surface)
            .border(1.dp, if (active) Color(0x800FA9A0) else VmColors.Border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = if (wrap) 14.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        VmText(label, size = 13.sp, weight = FontWeight.SemiBold, color = if (active) VmColors.Accent2 else color, maxLines = 1)
    }
}

/** Cloud save status, this device's sync code, and switching to another device's code. */
@Composable
private fun CloudSyncCard(status: SyncStatus, onConfirm: (ConfirmRequest) -> Unit) {
    val t = LocalTranslator.current
    val sync = LocalServices.current.cloudSync
    val syncCode by sync.syncCode.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }

    val tone = when (status) {
        SyncStatus.Synced -> Color(0xFF16A34A)
        SyncStatus.Connecting, SyncStatus.Saving -> VmColors.TextMuted
        else -> VmColors.Amber
    }

    fun connect() {
        connectError = null
        onConfirm(
            ConfirmRequest(title = t("sync.connect"), body = t("sync.confirmConnect"), confirmLabel = t("sync.connect")) {
                connecting = true
                scope.launch {
                    try {
                        when (sync.connect(code)) {
                            ConnectResult.Ok -> code = ""
                            ConnectResult.Invalid -> connectError = "sync.invalidCode"
                            ConnectResult.NotFound -> connectError = "sync.notFound"
                            ConnectResult.Outdated -> connectError = "sync.status.outdated"
                            ConnectResult.Failed -> connectError = "sync.connectFailed"
                        }
                    } finally {
                        connecting = false
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            VmText(t("sync.status.${status.id}"), size = 13.sp, weight = FontWeight.SemiBold, color = tone)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VmText(t("sync.code"), size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                VmText(
                    syncCode,
                    Modifier.weight(1f),
                    size = 12.sp,
                    style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                )
                OptionButton(if (copied) t("sync.copied") else t("sync.copy"), wrap = true) {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sync code", syncCode)))
                        copied = true
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VmText(t("sync.connectLabel"), size = 13.sp, weight = FontWeight.SemiBold, color = VmColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                VmTextFieldSmall(
                    code,
                    { code = it },
                    Modifier.weight(1f),
                    placeholder = t("sync.connectPlaceholder"),
                    mono = true,
                    capitalization = KeyboardCapitalization.None,
                    onDone = { if (code.isNotBlank() && !connecting) connect() },
                )
                OptionButton(t("sync.connect"), enabled = !connecting && code.isNotBlank(), wrap = true, onClick = ::connect)
            }
        }
        connectError?.let { FormError(t(it)) }
    }
}

/** Export the data as a JSON file, import one, or clear the app. */
@Composable
private fun DataControls(data: VacationMapData, onReset: () -> Unit) {
    val t = LocalTranslator.current
    val context = LocalContext.current
    val store = LocalServices.current.store
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                writeTextFile(context, uri, PrettyJson.encodeToString(VacationMapData.serializer(), data))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                importError = t("data.readFailed")
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importError = try {
                val text = readTextFile(context, uri)
                try {
                    store.replaceAllData(AppJson.parseToJsonElement(text))
                    null
                } catch (_: InvalidVacationDataException) {
                    t("data.invalidFormat")
                } catch (_: Exception) {
                    t("data.importFailed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                t("data.readFailed")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionButton(t("data.export")) { exportFile.launch("vacation-data-${System.currentTimeMillis()}.json") }
            OptionButton(t("data.import")) {
                importError = null
                // some file managers label JSON as plain text or as "any file"
                importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            }
            OptionButton(t("data.reset"), color = VmColors.Danger, onClick = onReset)
        }
        importError?.let { FormError(it) }
    }
}
