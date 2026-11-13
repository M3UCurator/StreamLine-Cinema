package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streamline.cinema.BuildConfig
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.BandwidthCap
import com.streamline.cinema.data.BufferTier
import com.streamline.cinema.data.CacheBreakdown
import com.streamline.cinema.data.CacheCleaner
import com.streamline.cinema.data.CinemaDns
import com.streamline.cinema.data.ContentCountry
import com.streamline.cinema.data.ContentLanguage
import com.streamline.cinema.data.DecoderType
import com.streamline.cinema.data.DeviceProfile
import com.streamline.cinema.data.DnsMode
import com.streamline.cinema.data.IptvSource
import com.streamline.cinema.data.JsonBackup
import com.streamline.cinema.data.LanguageRule
import com.streamline.cinema.data.ParentalPin
import com.streamline.cinema.data.PlaybackEngine
import com.streamline.cinema.data.PlayerUaPreset
import com.streamline.cinema.data.PosterOrientation
import com.streamline.cinema.data.PosterSize
import com.streamline.cinema.data.PowerPolicy
import com.streamline.cinema.data.QualityLimit
import com.streamline.cinema.data.SortMode
import com.streamline.cinema.data.StreamBadgeCatalog
import com.streamline.cinema.data.StremioAddons
import com.streamline.cinema.data.SyncCadence
import com.streamline.cinema.data.SyncTarget
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Divider
import com.streamline.cinema.ui.FieldLabel
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalScreenState
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PinDisplay
import com.streamline.cinema.ui.PinKeyCapture
import com.streamline.cinema.ui.PinPad
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.player.describeBuffer
import com.streamline.cinema.ui.player.describeNativeCache
import com.streamline.cinema.ui.rememberScreenScrollState
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val HLS_SAMPLE = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
private const val DASH_SAMPLE = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
private const val BADGE_SAMPLE =
    "https://raw.githubusercontent.com/farizmohd70/FR-Badges/main/badges.json"

private enum class SettingsSection(val label: String, val subtitle: String) {
    ACCESS("Access", "TMDB key, proxy routing and search data"),
    ADDONS("Addons", "Stremio stream and subtitle providers"),
    IPTV("IPTV & EPG", "Playlists and the XMLTV guide"),
    PARENTAL("Parental", "PIN, Kids Mode and the 18+ gate"),
    APPEARANCE("Appearance", "Artwork layout, library order and interface"),
    DISCOVERY("Discovery", "Smart search, content language and country"),
    PLAYBACK("Playback", "Engine, buffering, audio and captions"),
    NETWORK("Network", "DNS resolver and connectivity"),
    SYNC("App Sync", "Refresh schedule per source type"),
    BACKUP("Backup", "Settings JSON export and restore"),
    ABOUT("About", "Build and application details")
}

@Composable
fun SettingsScreen(
    vm: CinemaViewModel,
    onPreviewStream: (String, String) -> Unit,
    onCurateKids: () -> Unit,
    onKidsModeEnabled: () -> Unit,
    focusSignal: Int = 0
) {
    val settings by vm.settings.collectAsState()
    val update by vm.update.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val kidsApproved by vm.kidsMode.approved.collectAsState()
    val test by vm.tmdbTest.collectAsState()
    val dnsTest by vm.dnsTest.collectAsState()
    val players by vm.externalPlayers.collectAsState()

    var keyDraft by remember { mutableStateOf("") }
    var keyHidden by remember { mutableStateOf(true) }
    var proxyDraft by remember { mutableStateOf("") }
    var dnsDraft by remember { mutableStateOf("") }
    var dnsError by remember { mutableStateOf<String?>(null) }
    var addonName by remember { mutableStateOf("") }
    var addonUrl by remember { mutableStateOf("") }
    var addonError by remember { mutableStateOf<String?>(null) }
    var addonNotice by remember { mutableStateOf<String?>(null) }
    var addonBusy by remember { mutableStateOf(false) }
    val badgeCatalog by vm.badges.collectAsState()
    val badgeState by vm.badgeState.collectAsState()
    var badgeDraft by remember { mutableStateOf("") }
    var badgeError by remember { mutableStateOf<String?>(null) }
    val iptvSources by vm.iptvSources.collectAsState()
    val epgState by vm.epg.collectAsState()
    val syncState by vm.sync.collectAsState()
    val importSources by vm.sources.collectAsState()
    val channelHealth by vm.channelHealth.collectAsState()
    val deadChannels = remember(channelHealth) {
        channelHealth.filterValues { it.dead }.keys
    }
    var confirmClearApproved by remember { mutableStateOf(false) }
    var iptvUrl by remember { mutableStateOf("") }
    var iptvName by remember { mutableStateOf("") }
    var iptvEpgUrl by remember { mutableStateOf("") }
    var iptvError by remember { mutableStateOf<String?>(null) }
    var iptvNotice by remember { mutableStateOf<String?>(null) }
    var iptvBusy by remember { mutableStateOf(false) }
    var iptvBusyId by remember { mutableStateOf<String?>(null) }
    var expandedIptvId by remember { mutableStateOf<String?>(null) }
    var iptvEditSource by remember { mutableStateOf<IptvSource?>(null) }
    var iptvEditName by remember { mutableStateOf("") }
    var iptvEditUrl by remember { mutableStateOf("") }
    var iptvEditEpgUrl by remember { mutableStateOf("") }
    var iptvEditError by remember { mutableStateOf<String?>(null) }
    var iptvEditBusy by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            iptvUrl = uri.toString()
            iptvError = null
            iptvNotice = "Local file selected · press ADD SOURCE to load it"
        }
    }
    val editFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            iptvEditUrl = uri.toString()
            iptvEditError = null
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Battery exemptions can only be granted or revoked in system UI, so the
    // switch mirrors PowerManager instead of storing a claim in settings.
    var batteryExempt by remember { mutableStateOf(PowerPolicy.isExempt(context)) }
    var batteryNotice by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = PowerPolicy.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val lowRamDevice = remember { DeviceProfile.isLowRam(context) }
    var cacheBreakdown by remember { mutableStateOf<CacheBreakdown?>(null) }
    var cacheNotice by remember { mutableStateOf<String?>(null) }

    fun refreshCache() {
        scope.launch {
            cacheBreakdown = withContext(Dispatchers.IO) { CacheCleaner.breakdown(context) }
        }
    }

    fun clearCachePart(label: String, action: () -> Long) {
        cacheNotice = null
        scope.launch {
            val freed = withContext(Dispatchers.IO) { runCatching(action).getOrDefault(0L) }
            cacheBreakdown = withContext(Dispatchers.IO) { CacheCleaner.breakdown(context) }
            cacheNotice = "$label · freed ${formatCacheSize(freed)}"
        }
    }

    var backupNotice by remember { mutableStateOf<String?>(null) }
    var backupFailed by remember { mutableStateOf(false) }
    val screenState = LocalScreenState.current
    val view = LocalView.current
    // Section, rail scroll and pane scroll survive tab switches; the store keeps
    // the actual state objects for the life of the process.
    var section by remember {
        mutableStateOf(
            screenState.value("settings.section")
                ?.let { name -> SettingsSection.entries.firstOrNull { it.name == name } }
                ?: SettingsSection.ACCESS
        )
    }
    LaunchedEffect(section) { screenState.setValue("settings.section", section.name) }
    val railScroll = rememberScreenScrollState("settings.rail")
    // A scrollable that comes back from tab disposal can report offset 0 before it
    // re-attaches, so the offset is mirrored into the store and re-applied on entry.
    LaunchedEffect(railScroll) {
        snapshotFlow { railScroll.value }.collect {
            screenState.setValue("settings.rail.offset", it.toString())
        }
    }
    val railResumeOffset = remember { screenState.value("settings.rail.offset")?.toIntOrNull() }
    val railFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        // Entering the tab should land on the section the viewer last used, with
        // the rail where it was. Focusing the row makes Compose bring it into view,
        // and that shuffle can drag the rail, so the resume offset is pinned after.
        val keep = railResumeOffset ?: railScroll.value
        runCatching { railScroll.scrollTo(keep) }
        delay(170)
        repeat(8) { attempt ->
            if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
            runCatching { railFocus.getValue(section).requestFocus() }
            runCatching { railScroll.scrollTo(keep) }
            if (attempt < 7) delay(80)
        }
    }
    val manageKidsFocus = remember { FocusRequester() }
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0 && section == SettingsSection.PARENTAL) {
            delay(180)
            if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
            runCatching { manageKidsFocus.requestFocus() }
        }
    }
    var currentPinDraft by remember { mutableStateOf("") }
    var newPinDraft by remember { mutableStateOf("") }
    var confirmPinDraft by remember { mutableStateOf("") }
    var adultGateOpen by remember { mutableStateOf(false) }
    var adultPinDraft by remember { mutableStateOf("") }
    var pinTarget by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinNotice by remember { mutableStateOf<String?>(null) }
    var kidsNotice by remember { mutableStateOf<String?>(null) }
    // The pane keeps its own offset per section instead of jumping to the top.
    val sectionScroll = rememberScreenScrollState("settings.pane.${section.name}")
    val iptvEditFocus = remember { mutableMapOf<String, FocusRequester>() }
    var iptvFocusReturnId by remember { mutableStateOf<String?>(null) }
    var iptvFocusTick by remember { mutableIntStateOf(0) }
    // Offsets captured when the edit dialog opens. Re-focusing the EDIT button
    // after the dialog closes makes Compose bring the focused control into view,
    // and that focus shuffle can drag the rail or the pane with it. Pinning the
    // captured offsets for a few frames keeps both columns where the viewer left them.
    var railReturnOffset by remember { mutableIntStateOf(0) }
    var paneReturnOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(iptvFocusTick) {
        if (iptvFocusTick == 0) return@LaunchedEffect
        // The window can take a moment to win focus back after the dialog window
        // closes, so the focus request is retried until it sticks.
        val target = iptvFocusReturnId?.let { iptvEditFocus[it] }
        delay(170)
        repeat(10) { attempt ->
            if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
            target?.let { runCatching { it.requestFocus() } }
            runCatching { railScroll.scrollTo(railReturnOffset) }
            runCatching { sectionScroll.scrollTo(paneReturnOffset) }
            if (attempt < 9) delay(80)
        }
    }
    val settingsImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        parseSettingsJson(JsonBackup.readText(context, uri))
                    }
                }
                result.onSuccess { imported ->
                    vm.saveSettings { imported }
                    vm.onTmdbSettingsChanged()
                    keyDraft = imported.tmdbApiKey
                    keyHidden = imported.tmdbApiKey.isNotBlank()
                    proxyDraft = imported.proxyBaseUrl
                    backupFailed = false
                    badgeDraft = imported.badgeSourceUrl
                    if (imported.badgeSourceUrl.isNotBlank()) vm.refreshBadges()
                    backupNotice = "Settings restored from ${uri.lastPathSegment ?: "file"}"
                }.onFailure { error ->
                    backupFailed = true
                    backupNotice = "Import failed: ${error.message ?: "invalid settings JSON"}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        cacheBreakdown = withContext(Dispatchers.IO) { CacheCleaner.breakdown(context) }
    }

    LaunchedEffect(Unit) {
        keyDraft = vm.settings.value.tmdbApiKey
        keyHidden = vm.settings.value.tmdbApiKey.isNotBlank()
        proxyDraft = vm.settings.value.proxyBaseUrl
        badgeDraft = vm.settings.value.badgeSourceUrl
    }

    // Digits typed on a physical keyboard fill the PIN fields directly, with no IME,
    // and the on-screen keypad keeps working. The activity drains the sink so the
    // digits land no matter where focus sits, and it is scoped to the Parental
    // section so the other sections' text fields keep their normal typing.
    DisposableEffect(section, settings.hasParentalPin) {
        if (section != SettingsSection.PARENTAL) {
            onDispose { }
        } else {
            PinKeyCapture.attach(
                onDigit = { digit ->
                    pinError = null
                    val hasPin = settings.hasParentalPin
                    val target = when {
                        adultGateOpen || pinTarget == "adult" -> "adult"
                        pinTarget == "current" && hasPin && currentPinDraft.length < 4 -> "current"
                        pinTarget == "confirm" && confirmPinDraft.length < 4 -> "confirm"
                        pinTarget == "new" && newPinDraft.length < 4 -> "new"
                        hasPin && currentPinDraft.length < 4 -> "current"
                        newPinDraft.length < 4 -> "new"
                        else -> "confirm"
                    }
                    pinTarget = target
                    when (target) {
                        "current" -> if (currentPinDraft.length < 4) currentPinDraft += digit
                        "confirm" -> if (confirmPinDraft.length < 4) confirmPinDraft += digit
                        "adult" -> if (adultPinDraft.length < 4) adultPinDraft += digit
                        else -> if (newPinDraft.length < 4) newPinDraft += digit
                    }
                },
                onBackspace = {
                    when {
                        (adultGateOpen || pinTarget == "adult") && adultPinDraft.isNotEmpty() ->
                            adultPinDraft = adultPinDraft.dropLast(1)
                        pinTarget == "confirm" && confirmPinDraft.isNotEmpty() ->
                            confirmPinDraft = confirmPinDraft.dropLast(1)
                        pinTarget == "new" && newPinDraft.isNotEmpty() ->
                            newPinDraft = newPinDraft.dropLast(1)
                        pinTarget == "current" && currentPinDraft.isNotEmpty() ->
                            currentPinDraft = currentPinDraft.dropLast(1)
                        confirmPinDraft.isNotEmpty() -> confirmPinDraft = confirmPinDraft.dropLast(1)
                        newPinDraft.isNotEmpty() -> newPinDraft = newPinDraft.dropLast(1)
                        currentPinDraft.isNotEmpty() -> currentPinDraft = currentPinDraft.dropLast(1)
                        else -> adultPinDraft = adultPinDraft.dropLast(1)
                    }
                }
            )
            onDispose { PinKeyCapture.detach() }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 44.dp, end = 40.dp, top = 10.dp, bottom = 8.dp)
    ) {
        Column(Modifier.width(220.dp).fillMaxHeight()) {
            Text(
                "Settings",
                color = Cinema.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (profile.isKids) "Kids profile · parental controls apply" else
                    "Preferences for this TV",
                color = Cinema.TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(railScroll)
            ) {
                SettingsSection.entries.forEach { item ->
                    SettingsNavRow(
                        section = item,
                        selected = section == item,
                        focusRequester = railFocus.getValue(item),
                        onClick = { section = item }
                    )
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    section.label,
                    color = Cinema.TextPrimary,
                    style = CinemaType.PageTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                val statusPills: List<Pair<String, Color>> = when (section) {
                    SettingsSection.ACCESS -> listOf(
                        if (settings.hasTmdbAccess) "ACCESS READY" to Cinema.Online
                        else "NOT CONFIGURED" to Cinema.Pinging
                    )
                    SettingsSection.ADDONS -> listOf(
                        "${settings.addons.count { it.enabled }} ACTIVE" to Cinema.Gold,
                        "${settings.addons.size} SAVED" to Cinema.TextSecondary
                    )
                    SettingsSection.IPTV -> listOf(
                        "${iptvSources.count { it.enabled }} ACTIVE" to Cinema.Gold,
                        "${iptvSources.size} SAVED" to Cinema.TextSecondary
                    )
                    SettingsSection.PARENTAL -> listOf(
                        if (settings.hasParentalPin) "CUSTOM PIN" to Cinema.Online
                        else "DEFAULT PIN" to Cinema.Pinging,
                        if (profile.isKids) "KIDS ON" to Cinema.Gold
                        else "KIDS OFF" to Cinema.TextSecondary
                    )
                    SettingsSection.APPEARANCE -> listOf(
                        settings.posterSize.label.uppercase() to Cinema.Gold,
                        settings.posterOrientation.label.uppercase() to Cinema.TextSecondary
                    )
                    SettingsSection.DISCOVERY -> listOf(
                        ContentLanguage.from(settings.contentLanguage).label.uppercase() to Cinema.Gold,
                        settings.languageRule.label.uppercase() to Cinema.TextSecondary
                    )
                    SettingsSection.PLAYBACK -> listOf(
                        settings.playbackEngine.settingLabel() to Cinema.Gold,
                        (if (settings.qualityLimit == QualityLimit.OFF) "NO LIMIT"
                        else settings.qualityLimit.label) to Cinema.TextSecondary
                    )
                    SettingsSection.NETWORK -> listOf(
                        settings.dnsMode.label.uppercase() to Cinema.Gold
                    )
                    SettingsSection.SYNC -> listOf(
                        if (listOf(settings.importsSync, settings.iptvSync, settings.addonsSync)
                                .all { it == SyncCadence.MANUAL }
                        ) "MANUAL ONLY" to Cinema.Pinging else "AUTOMATIC" to Cinema.Online
                    )
                    SettingsSection.BACKUP -> listOf("SETTINGS ONLY" to Cinema.TextSecondary)
                    SettingsSection.ABOUT -> listOf("v${BuildConfig.VERSION_NAME}" to Cinema.Gold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    statusPills.forEach { (text, color) ->
                        InfoPill(text, color, Cinema.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                section.subtitle,
                color = Cinema.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(sectionScroll)
            ) {
                when (section) {
                    SettingsSection.ACCESS -> {
                        SettingsGroup(
                            "TMDB access",
                            hint = if (settings.hasTmdbAccess) "READY" else "REQUIRED"
                        ) {
                            val keyStored = settings.tmdbApiKey.isNotBlank()
                            val keyMasked = keyStored && keyHidden
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("TMDB API key (v3 or v4 bearer)")
                                    TvTextField(
                                        value = if (keyMasked) "*****" else keyDraft,
                                        onValueChange = { if (!keyMasked) keyDraft = it },
                                        placeholder = "e.g. 8f4c1d… or eyJhbGciOi…"
                                    )
                                    if (keyStored) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TvButton(
                                                label = if (keyHidden) "REVEAL" else "HIDE",
                                                glyph = if (keyHidden) "◉" else "◌",
                                                style = TvButtonStyle.GHOST,
                                                compact = true,
                                                onClick = { keyHidden = !keyHidden }
                                            )
                                            TvButton(
                                                label = "CLEAR KEY",
                                                glyph = "✕",
                                                style = TvButtonStyle.DANGER,
                                                compact = true,
                                                onClick = {
                                                    keyDraft = ""
                                                    keyHidden = false
                                                    vm.saveSettings { it.copy(tmdbApiKey = "") }
                                                    vm.onTmdbSettingsChanged()
                                                }
                                            )
                                        }
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("Express proxy base URL")
                                    TvTextField(
                                        value = proxyDraft,
                                        onValueChange = { proxyDraft = it },
                                        placeholder = "http://192.168.1.20:8787"
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Routes TMDB requests through this proxy",
                                        color = Cinema.TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        SettingsGroup("Routing & diagnostics") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvButton(
                                    label = "SAVE ACCESS",
                                    glyph = "✓",
                                    style = TvButtonStyle.PRIMARY,
                                    onClick = {
                                        vm.saveSettings {
                                            it.copy(
                                                tmdbApiKey = keyDraft.trim(),
                                                proxyBaseUrl = proxyDraft.trim().trimEnd('/')
                                            )
                                        }
                                        keyHidden = keyDraft.isNotBlank()
                                        vm.onTmdbSettingsChanged()
                                    }
                                )
                                TvButton(
                                    label = if (test.busy) "TESTING" else "TEST CONNECTION",
                                    glyph = "⌁",
                                    style = TvButtonStyle.SECONDARY,
                                    enabled = !test.busy,
                                    onClick = {
                                        vm.saveSettings {
                                            it.copy(
                                                tmdbApiKey = keyDraft.trim(),
                                                proxyBaseUrl = proxyDraft.trim().trimEnd('/')
                                            )
                                        }
                                        keyHidden = keyDraft.isNotBlank()
                                        vm.testTmdb()
                                    }
                                )
                                Spacer(Modifier.weight(1f))
                                test.message?.let {
                                    InfoPill(
                                        it,
                                        if (test.success) Cinema.Online else Cinema.Offline,
                                        Cinema.InkSoft
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The key is stored on this device and sent only to the configured proxy " +
                                    "or TMDB. 18+ filtering lives in the Parental section.",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 15.sp
                            )
                        }
                        SettingsGroup(
                            "Search",
                            hint = "${settings.recentSearches.size} RECENT"
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Terms appear as one-tap chips under Playroom search",
                                    color = Cinema.TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TvButton(
                                    label = "CLEAR HISTORY",
                                    glyph = "✕",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = settings.recentSearches.isNotEmpty(),
                                    onClick = { vm.clearRecentSearches() }
                                )
                            }
                        }
                    }
                    SettingsSection.PARENTAL -> {
                        val hasPin = settings.hasParentalPin
                        SettingsGroup(
                            "Parental PIN",
                            hint = if (hasPin) "CUSTOM" else "DEFAULT 1234"
                        ) {
                        Text(
                            if (hasPin) {
                                "Change or remove the 4-digit PIN that unlocks 18+ titles and " +
                                    "guards Kids Mode. Removing it restores the default PIN 1234."
                            } else {
                                "Set a 4-digit PIN to guard Kids Mode and unlock 18+ titles. " +
                                    "Until then the default PIN 1234 applies."
                            },
                            color = Cinema.TextSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (hasPin) {
                                PinDisplay(
                                    label = "Current PIN",
                                    value = currentPinDraft,
                                    active = pinTarget == "current",
                                    onClick = { pinTarget = "current" },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            PinDisplay(
                                label = if (hasPin) "New PIN" else "PIN",
                                value = newPinDraft,
                                active = pinTarget == "new",
                                onClick = { pinTarget = "new" },
                                modifier = Modifier.weight(1f)
                            )
                            PinDisplay(
                                label = "Confirm PIN",
                                value = confirmPinDraft,
                                active = pinTarget == "confirm",
                                onClick = { pinTarget = "confirm" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pinTarget == "current" || pinTarget == "new" || pinTarget == "confirm") {
                            Spacer(Modifier.height(10.dp))
                            PinPad(
                                value = when (pinTarget) {
                                    "current" -> currentPinDraft
                                    "confirm" -> confirmPinDraft
                                    else -> newPinDraft
                                },
                                onValueChange = { next ->
                                    pinError = null
                                    when (pinTarget) {
                                        "current" -> currentPinDraft = next
                                        "confirm" -> confirmPinDraft = next
                                        else -> newPinDraft = next
                                    }
                                },
                                modifier = Modifier.width(420.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = "SAVE PIN",
                                glyph = "✓",
                                style = TvButtonStyle.PRIMARY,
                                enabled = ParentalPin.isValid(newPinDraft) &&
                                    newPinDraft == confirmPinDraft,
                                onClick = {
                                    pinError = when {
                                        !ParentalPin.isValid(newPinDraft) -> "PIN must be 4 digits"
                                        newPinDraft != confirmPinDraft -> "PINs do not match"
                                        hasPin && !ParentalPin.matches(
                                            currentPinDraft,
                                            settings.parentalPinHash
                                        ) -> "Current PIN is incorrect"
                                        else -> null
                                    }
                                    if (pinError == null) {
                                        vm.saveSettings {
                                            it.copy(parentalPinHash = ParentalPin.hash(newPinDraft))
                                        }
                                        pinNotice = "Parental PIN saved"
                                        currentPinDraft = ""
                                        newPinDraft = ""
                                        confirmPinDraft = ""
                                        pinTarget = ""
                                    } else {
                                        pinNotice = null
                                    }
                                }
                            )
                            if (hasPin) {
                                TvButton(
                                    label = "REMOVE PIN",
                                    glyph = "✕",
                                    style = TvButtonStyle.DANGER,
                                    compact = true,
                                    onClick = {
                                        if (!ParentalPin.matches(
                                                currentPinDraft,
                                                settings.parentalPinHash
                                            )
                                        ) {
                                            pinNotice = null
                                            pinError = "Enter the current PIN to remove it"
                                        } else {
                                            if (settings.includeAdult) vm.setIncludeAdult(false)
                                            vm.saveSettings { it.copy(parentalPinHash = "") }
                                            currentPinDraft = ""
                                            newPinDraft = ""
                                            confirmPinDraft = ""
                                            pinTarget = ""
                                            adultGateOpen = false
                                            pinError = null
                                            pinNotice = "Parental PIN removed · 18+ hidden"
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            pinError?.let { InfoPill(it, Cinema.Offline, Cinema.InkSoft) }
                            pinNotice?.let { InfoPill(it, Cinema.Online, Cinema.InkSoft) }
                        }
                        }
                        SettingsGroup(
                            "Kids mode",
                            hint = if (profile.isKids) "ON · ${profile.name}" else "OFF"
                        ) {
                        SettingsToggleRow(
                            label = "Kids Mode",
                            checked = profile.isKids,
                            onChange = { next ->
                                pinError = null
                                kidsNotice = null
                                if (next) {
                                    val kids = vm.enableKidsMode()
                                    kidsNotice = "Kids Mode on · ${kids.name}"
                                    onKidsModeEnabled()
                                } else if (vm.disableKidsMode()) {
                                    kidsNotice = "Kids Mode off · full catalog restored"
                                } else {
                                    pinError = "Create an adult profile first"
                                }
                            },
                            description = "Approved titles only; Search, Imports and Settings ask for the PIN"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Whitelist shared by every Kids profile",
                                color = Cinema.TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            InfoPill("${kidsApproved.size} APPROVED", Cinema.Gold, Cinema.InkSoft)
                            TvButton(
                                label = "MANAGE KIDS LIBRARY",
                                glyph = "⛨",
                                style = TvButtonStyle.SECONDARY,
                                compact = true,
                                focusRequester = manageKidsFocus,
                                onClick = onCurateKids
                            )
                        }
                        kidsNotice?.let {
                            Spacer(Modifier.height(10.dp))
                            InfoPill(it, Cinema.Online, Cinema.InkSoft)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (kidsApproved.isEmpty()) "No titles approved yet"
                                else "Clearing hides every approved title",
                                color = Cinema.TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TvButton(
                                label = if (confirmClearApproved) "CONFIRM CLEAR" else "CLEAR APPROVED",
                                glyph = "✕",
                                style = TvButtonStyle.DANGER,
                                compact = true,
                                enabled = kidsApproved.isNotEmpty(),
                                onClick = {
                                    if (confirmClearApproved) {
                                        vm.kidsMode.clearApproved()
                                        confirmClearApproved = false
                                        kidsNotice = "Approved Kids library cleared"
                                    } else {
                                        confirmClearApproved = true
                                    }
                                }
                            )
                        }
                        }
                        SettingsGroup("Maturity") {
                        SettingsToggleRow(
                            label = "Include adult content (18+)",
                            checked = settings.includeAdult,
                            onChange = { next ->
                                pinError = null
                                pinNotice = null
                                if (!next) {
                                    adultGateOpen = false
                                    adultPinDraft = ""
                                    if (pinTarget == "adult") pinTarget = ""
                                    vm.setIncludeAdult(false)
                                } else if (!settings.hasParentalPin) {
                                    pinError = "Set a parental PIN first"
                                } else {
                                    adultGateOpen = true
                                    adultPinDraft = ""
                                    pinTarget = "adult"
                                }
                            },
                            description = "Enabling asks for the parental PIN; disabling never does"
                        )
                        if (adultGateOpen) {
                            Spacer(Modifier.height(10.dp))
                            PinDisplay(
                                label = "Enter parental PIN to enable 18+",
                                value = adultPinDraft,
                                active = pinTarget == "adult",
                                onClick = { pinTarget = "adult" },
                                modifier = Modifier.width(420.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            PinPad(
                                value = adultPinDraft,
                                onValueChange = {
                                    adultPinDraft = it
                                    pinError = null
                                },
                                modifier = Modifier.width(420.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvButton(
                                    label = "UNLOCK",
                                    glyph = "◆",
                                    style = TvButtonStyle.PRIMARY,
                                    enabled = adultPinDraft.length == 4,
                                    onClick = {
                                        if (ParentalPin.matches(
                                                adultPinDraft,
                                                settings.parentalPinHash
                                            )
                                        ) {
                                            adultGateOpen = false
                                            adultPinDraft = ""
                                            if (pinTarget == "adult") pinTarget = ""
                                            vm.setIncludeAdult(true)
                                            pinNotice = "18+ content enabled"
                                        } else {
                                            adultPinDraft = ""
                                            pinError = "Incorrect PIN"
                                        }
                                    }
                                )
                                TvButton(
                                    label = "CANCEL",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    onClick = {
                                        adultGateOpen = false
                                        adultPinDraft = ""
                                        if (pinTarget == "adult") pinTarget = ""
                                    }
                                )
                            }
                        }
                        }
                    }
                    SettingsSection.APPEARANCE -> {
                        SettingsGroup(
                            "Artwork layout",
                            hint = "${settings.posterOrientation.label.uppercase()} · " +
                                settings.posterSize.label.uppercase()
                        ) {
                        SettingsSelectRow(
                            title = "Orientation",
                            description = "Landscape uses 16:9 backdrops, portrait keeps 2:3 posters",
                            selected = settings.posterOrientation,
                            options = PosterOrientation.entries,
                            optionLabel = { it.label.uppercase() },
                            onSelect = { orientation ->
                                vm.saveSettings { it.copy(posterOrientation = orientation) }
                            }
                        )
                        SettingsSelectRow(
                            title = "Poster size",
                            description = "Rescales every grid, rail and result list",
                            selected = settings.posterSize,
                            options = PosterSize.entries,
                            optionLabel = { it.label.uppercase() },
                            onSelect = { size ->
                                vm.saveSettings { it.copy(posterSize = size) }
                            }
                        )
                        }
                        SettingsGroup(
                            "Library order",
                            hint = settings.sortMode.label
                        ) {
                            SettingsSelectRow(
                                title = "Sort titles by",
                                description = "Applies to My Movies, My TV Shows, Favorites and Playroom",
                                selected = settings.sortMode,
                                options = SortMode.entries,
                                optionLabel = { it.label },
                                onSelect = { mode ->
                                    vm.saveSettings { it.copy(sortMode = mode) }
                                }
                            )
                        }
                        SettingsGroup("Interface") {
                            SettingsToggleRow(
                                label = "UI sound effects",
                                checked = settings.uiSounds,
                                onChange = { vm.saveSettings { s -> s.copy(uiSounds = it) } },
                                description = "Ticks on focus and confirm; follows the TV sound-effects volume"
                            )
                        }
                    }
                    SettingsSection.DISCOVERY -> {
                        SettingsGroup(
                            "Smart search & play",
                            hint = if (settings.contentLanguage.isBlank() && settings.contentCountry.isBlank()) "OFF"
                            else "ON"
                        ) {
                            SettingsSelectRow(
                                title = "Content language",
                                description = "TMDB metadata language and the language matches move up for",
                                selected = ContentLanguage.from(settings.contentLanguage),
                                options = ContentLanguage.entries.toList(),
                                optionLabel = { it.label.uppercase() },
                                onSelect = { language ->
                                    vm.saveSettings { it.copy(contentLanguage = language.code) }
                                    vm.onContentPreferencesChanged()
                                }
                            )
                            SettingsSelectRow(
                                title = "Preferred country",
                                description = "Filters discovery by origin country and picks that market's release dates and ratings",
                                selected = ContentCountry.from(settings.contentCountry),
                                options = ContentCountry.entries.toList(),
                                optionLabel = { it.label.uppercase() },
                                onSelect = { country ->
                                    vm.saveSettings { it.copy(contentCountry = country.code) }
                                    vm.onContentPreferencesChanged()
                                }
                            )
                            SettingsSelectRow(
                                title = "Language rule",
                                description = if (settings.languageRule == LanguageRule.ONLY) {
                                    "Hides every known title in another language; unknown-language titles stay"
                                } else {
                                    "Moves language matches to the top and keeps everything else listed"
                                },
                                selected = settings.languageRule,
                                options = LanguageRule.entries.toList(),
                                optionLabel = { it.label.uppercase() },
                                onSelect = { rule ->
                                    vm.saveSettings { it.copy(languageRule = rule) }
                                    vm.onContentPreferencesChanged()
                                }
                            )
                        }
                        SettingsGroup(
                            "Source order",
                            hint = if (settings.preferLanguageSources) "LANGUAGE FIRST" else "OFF"
                        ) {
                            SettingsToggleRow(
                                label = "Prefer language-tagged sources",
                                checked = settings.preferLanguageSources,
                                onChange = { enabled ->
                                    vm.saveSettings { s -> s.copy(preferLanguageSources = enabled) }
                                },
                                description = "Moves addon streams tagged with the content language, or MULTI, above untagged and foreign ones"
                            )
                        }
                        SettingsInfoRow(
                            title = "Ranking scope",
                            value = "Library, TMDB lists and search; the catalog's own stream always stays first"
                        )
                    }
                    SettingsSection.PLAYBACK -> {
                        SettingsGroup("Playback engine", hint = settings.playbackEngine.settingLabel()) {
                        SettingsSelectRow(
                            title = "Preferred engine",
                            description = "MPV needs Android 8+; DRM feeds fall back to built-in",
                            selected = settings.playbackEngine,
                            options = PlaybackEngine.entries,
                            optionLabel = { it.settingLabel() },
                            onSelect = { engine ->
                                vm.saveSettings { it.copy(playbackEngine = engine) }
                            }
                        )
                        }
                        SettingsGroup(
                            "Stream quality",
                            hint = if (settings.qualityLimit == QualityLimit.OFF) "NO LIMIT"
                            else "BELOW ${settings.qualityLimit.label}"
                        ) {
                        SettingsSelectRow(
                            title = "Quality exclusion",
                            description = if (settings.qualityLimit == QualityLimit.OFF) {
                                "Every source plays, including 4K and 8K"
                            } else {
                                "Skips ${settings.qualityLimit.label} and above; lower qualities play"
                            },
                            selected = settings.qualityLimit,
                            options = QualityLimit.entries,
                            optionLabel = { it.label },
                            onSelect = { limit ->
                                vm.saveSettings { it.copy(qualityLimit = limit) }
                            }
                        )
                        }
                        SettingsGroup(
                            "Source selection",
                            hint = if (settings.autoPlaySearchedStream) "AUTO" else "MANUAL"
                        ) {
                        SettingsToggleRow(
                            label = "Auto-play searched streams",
                            checked = settings.autoPlaySearchedStream,
                            onChange = { vm.saveSettings { s -> s.copy(autoPlaySearchedStream = it) } },
                            description = if (settings.autoPlaySearchedStream) {
                                "Starts the first retrieved source when the search ends"
                            } else {
                                "Waits for a pick from the retrieved source list"
                            } + " · live tunes directly"
                        )
                        }
                        SettingsGroup(
                            "Bandwidth & buffering",
                            hint = when {
                                settings.weakNetworkPreset -> "WEAK NETWORK"
                                settings.highCapacityBuffer -> "HIGH CAPACITY"
                                else -> settings.bandwidthCap.display
                            }
                        ) {
                        SettingsToggleRow(
                            label = "Weak network",
                            checked = settings.weakNetworkPreset,
                            onChange = { vm.saveSettings { s -> s.withWeakNetworkPreset(it) } },
                            description = if (settings.weakNetworkPreset) {
                                "LEAN buffer · 2 Mbps ceiling · no fast start — all set in one tap"
                            } else {
                                "One tap sets the LEAN buffer, a 2 Mbps cap and no fast start"
                            }
                        )
                        SettingsToggleRow(
                            label = "Fast-start buffering",
                            checked = settings.lowLatency,
                            onChange = { vm.saveSettings { s -> s.copy(lowLatency = it) } },
                            description = "Starts 2–3 s in and re-buffers at 2.5 s; no LL-HLS/LL-DASH part loading"
                        )
                        SettingsToggleRow(
                            label = "Adaptive bitrate (ABR)",
                            checked = settings.abrEnabled,
                            onChange = { vm.saveSettings { s -> s.copy(abrEnabled = it) } },
                            description = "ExoPlayer steps renditions down as bandwidth shifts"
                        )
                        SettingsSelectRow(
                            title = "Bandwidth cap",
                            description = settings.bandwidthCap.detail,
                            selected = settings.bandwidthCap,
                            options = BandwidthCap.entries,
                            optionLabel = { it.display },
                            onSelect = { cap ->
                                vm.saveSettings { it.copy(bandwidthCap = cap) }
                            }
                        )
                        SettingsSelectRow(
                            title = "Video buffer size",
                            description = settings.bufferTier.detail,
                            selected = settings.bufferTier,
                            options = BufferTier.entries,
                            optionLabel = { it.label },
                            onSelect = { tier ->
                                vm.saveSettings { it.copy(bufferTier = tier) }
                            }
                        )
                        SettingsToggleRow(
                            label = "High-capacity buffering (MP4 / MKV)",
                            checked = settings.highCapacityBuffer,
                            onChange = { vm.saveSettings { s -> s.copy(highCapacityBuffer = it) } },
                            description = "15 s fill · 50 s forward · 2.5 s start · 5 s re-buffer; " +
                                "wins over the buffer tier for MP4/MKV, low-RAM boxes still cap memory by bytes"
                        )
                        SettingsInfoRow(
                            title = "MP4 / MKV load control",
                            value = describeBuffer(
                                settings.bufferTier,
                                adaptive = false,
                                lowLatency = settings.lowLatency,
                                lowRam = lowRamDevice,
                                highCapacityVod = settings.highCapacityBuffer
                            )
                        )
                        SettingsInfoRow(
                            title = "HLS / MPEG-DASH load control",
                            value = describeBuffer(
                                settings.bufferTier,
                                adaptive = true,
                                lowLatency = settings.lowLatency,
                                lowRam = lowRamDevice
                            )
                        )
                        SettingsInfoRow(
                            title = "VLC / MPV cache",
                            value = describeNativeCache(
                                settings.bufferTier,
                                highCapacityVod = settings.highCapacityBuffer
                            )
                        )
                        SettingsToggleRow(
                            label = "Next up follows furthest watched",
                            checked = settings.nextUpFurthestWatched,
                            onChange = { vm.saveSettings { s -> s.copy(nextUpFurthestWatched = it) } },
                            description = "Suggests the episode after the furthest one watched"
                        )
                        }
                        SettingsGroup(
                            "Storage",
                            hint = cacheBreakdown?.let { formatCacheSize(it.totalBytes) }
                        ) {
                        SettingsToggleRow(
                            label = "Clear caches on exit",
                            checked = settings.clearCacheOnExit,
                            onChange = { vm.saveSettings { s -> s.copy(clearCacheOnExit = it) } },
                            description = "Drops the poster image cache and the app cache directory when you leave the home screen."
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = "CLEAR CACHE NOW",
                                glyph = "✕",
                                style = TvButtonStyle.SECONDARY,
                                compact = true,
                                onClick = {
                                    cacheNotice = null
                                    scope.launch {
                                        val freed = withContext(Dispatchers.IO) { CacheCleaner.clear(context) }
                                        cacheBreakdown =
                                            withContext(Dispatchers.IO) { CacheCleaner.breakdown(context) }
                                        cacheNotice = "Freed ${formatCacheSize(freed)}"
                                    }
                                }
                            )
                            Text(
                                cacheBreakdown?.let { "Cache ${formatCacheSize(it.totalBytes)}" }
                                    ?: "Measuring cache…",
                                color = Cinema.TextSecondary,
                                fontSize = 11.sp
                            )
                            cacheNotice?.let {
                                Text(it, color = Cinema.Online, fontSize = 11.sp)
                            }
                        }
                        cacheBreakdown?.let { breakdown ->
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvButton(
                                    label = "ARTWORK ${formatCacheSize(breakdown.imagesBytes)}",
                                    glyph = "▤",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = breakdown.imagesBytes > 0,
                                    onClick = {
                                        clearCachePart("Artwork cleared") {
                                            CacheCleaner.clearImages(context)
                                        }
                                    }
                                )
                                TvButton(
                                    label = "CHANNEL LISTS ${formatCacheSize(breakdown.channelBytes)}",
                                    glyph = "≡",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = breakdown.channelBytes > 0,
                                    onClick = {
                                        clearCachePart("Channel lists cleared") {
                                            CacheCleaner.clearIptvChannelCaches(context)
                                        }
                                    }
                                )
                                TvButton(
                                    label = "GUIDE INDEX ${formatCacheSize(breakdown.guideIndexBytes)}",
                                    glyph = "◷",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = breakdown.guideIndexBytes > 0,
                                    onClick = {
                                        clearCachePart("Guide index cleared") {
                                            CacheCleaner.clearGuideIndex(context)
                                        }
                                    }
                                )
                            }
                        }
                        }
                        SettingsGroup(
                            "Decoder & audio",
                            hint = settings.decoderType.label.uppercase()
                        ) {
                        SettingsSelectRow(
                            title = "Decoder type",
                            description = settings.decoderType.detail,
                            selected = settings.decoderType,
                            options = DecoderType.entries,
                            optionLabel = { it.label },
                            onSelect = { type ->
                                vm.saveSettings { it.copy(decoderType = type) }
                            }
                        )
                        SettingsToggleRow(
                            label = "Normalize volume",
                            checked = settings.normalizeVolume,
                            onChange = { vm.saveSettings { s -> s.copy(normalizeVolume = it) } },
                            description = "Even loudness curve (−18 dB, 4:1); disables passthrough"
                        )
                        SettingsToggleRow(
                            label = "Force stereo downmix",
                            checked = settings.forceStereoDownmix,
                            onChange = { vm.saveSettings { s -> s.copy(forceStereoDownmix = it) } },
                            description = "Downmixes multichannel audio for TV speakers and soundbars"
                        )
                        }
                        SettingsGroup(
                            "Hardware & display",
                            hint = when {
                                settings.amlogicFix && settings.frameRateMatching -> "FIX · AFR"
                                settings.amlogicFix -> "AMLOGIC FIX"
                                settings.frameRateMatching -> "AFR"
                                else -> "STOCK"
                            }
                        ) {
                        SettingsToggleRow(
                            label = "Amlogic fix (tunneling)",
                            checked = settings.amlogicFix,
                            onChange = { vm.saveSettings { s -> s.copy(amlogicFix = it) } },
                            description = "Forces the MediaCodec direct path in the built-in, VLC and MPV engines"
                        )
                        SettingsToggleRow(
                            label = "Auto frame rate matching (AFR)",
                            checked = settings.frameRateMatching,
                            onChange = { vm.saveSettings { s -> s.copy(frameRateMatching = it) } },
                            description = "Built-in, VLC and MPV match the display to the source rate (23.976 / 24 / 50 / 60 Hz)"
                        )
                        SettingsToggleRow(
                            label = "Unrestricted battery usage",
                            checked = batteryExempt,
                            onChange = { exempt ->
                                batteryNotice = null
                                if (exempt) {
                                    if (!PowerPolicy.requestExemption(context)) {
                                        batteryNotice =
                                            "No battery panel on this device · wake locks still hold"
                                    }
                                } else {
                                    PowerPolicy.openList(context)
                                }
                            },
                            description = batteryNotice ?: if (batteryExempt) {
                                "Doze exemption granted · background limits cannot cut sockets or clear buffers"
                            } else {
                                "Asks Android to exempt the player from Doze and background app limits"
                            }
                        )
                        }
                        SettingsGroup(
                            "Player signature",
                            hint = settings.playerUserAgent.label
                        ) {
                        SettingsSelectRow(
                            title = "HTTP User-Agent",
                            description = settings.userAgentString.ifBlank { "Stock ExoPlayer signature" },
                            selected = settings.playerUserAgent,
                            options = PlayerUaPreset.entries,
                            optionLabel = { it.label },
                            onSelect = { preset ->
                                vm.saveSettings { it.copy(playerUserAgent = preset) }
                            }
                        )
                        }
                        SettingsGroup(
                            "Subtitles",
                            hint = when (settings.subtitleScale) {
                                0.85f -> "SMALL"
                                1.2f -> "LARGE"
                                else -> "STANDARD"
                            }
                        ) {
                            SettingsSelectRow(
                                title = "Caption size",
                                selected = settings.subtitleScale,
                                options = listOf(0.85f, 1f, 1.2f),
                                optionLabel = {
                                    when (it) {
                                        0.85f -> "SMALL"
                                        1.2f -> "LARGE"
                                        else -> "STANDARD"
                                    }
                                },
                                onSelect = { scale ->
                                    vm.saveSettings { it.copy(subtitleScale = scale) }
                                }
                            )
                            SettingsSelectRow(
                                title = "Caption backdrop",
                                selected = settings.subtitleBackdrop,
                                options = listOf(0, 1, 2),
                                optionLabel = {
                                    when (it) {
                                        0 -> "NONE"
                                        1 -> "DIM"
                                        else -> "BLACK"
                                    }
                                },
                                onSelect = { backdrop ->
                                    vm.saveSettings { it.copy(subtitleBackdrop = backdrop) }
                                }
                            )
                        }
                        SettingsGroup(
                            "Diagnostics",
                            hint = "${players.size} PLAYERS DETECTED"
                        ) {
                        FieldLabel("External player hand-off")
                        Text(
                            if (players.isEmpty()) {
                                "No external video players detected. Install any video player " +
                                    "(Just Player, VLC, MPV, MX Player, …); it will appear in the system chooser."
                            } else {
                                "Hand-off always opens the system chooser, so any installed player can be " +
                                    "picked: " + players.joinToString { it.label } + "."
                            },
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvButton(
                                label = "PREVIEW HLS",
                                glyph = "▶",
                                style = TvButtonStyle.SECONDARY,
                                onClick = { onPreviewStream(HLS_SAMPLE, "HLS Sample Stream") }
                            )
                            TvButton(
                                label = "PREVIEW DASH",
                                glyph = "▶",
                                style = TvButtonStyle.SECONDARY,
                                onClick = { onPreviewStream(DASH_SAMPLE, "MPEG-DASH Sample Stream") }
                            )
                        }
                        }
                    }
                    SettingsSection.NETWORK -> {
                        SettingsGroup("Resolver", hint = settings.dnsMode.label.uppercase()) {
                        SettingsSelectRow(
                            title = "DNS preset",
                            description = CinemaDns.describe(settings),
                            selected = settings.dnsMode,
                            options = DnsMode.entries,
                            optionLabel = { it.label },
                            onSelect = { mode -> vm.setDnsMode(mode) }
                        )
                        if (settings.dnsMode != DnsMode.SYSTEM) {
                            settings.activeDnsServers.forEach { server ->
                                SettingsInfoRow(title = "Resolver", value = server)
                            }
                        }
                        }
                        SettingsGroup(
                            "Custom servers",
                            hint = "${settings.customDnsServers.size} SAVED"
                        ) {
                        if (settings.dnsMode == DnsMode.CUSTOM) {
                            FieldLabel("Your DNS servers")
                            if (settings.customDnsServers.isEmpty()) {
                                Text(
                                    "No custom servers yet. Add an IP for plain DNS/UDP or an https:// endpoint for DoH.",
                                    color = Cinema.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                settings.customDnsServers.forEach { server ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .glassSurface(
                                                shape = RoundedCornerShape(10.dp),
                                                base = Glass.Control,
                                                sheen = 0.5f
                                            )
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            server,
                                            color = Cinema.TextPrimary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TvButton(
                                            label = "✕",
                                            style = TvButtonStyle.DANGER,
                                            compact = true,
                                            onClick = { vm.removeCustomDnsServer(server) }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.weight(1f)) {
                                    TvTextField(
                                        value = dnsDraft,
                                        onValueChange = { dnsDraft = it },
                                        placeholder = "1.1.1.1 or https://dns.example/dns-query"
                                    )
                                }
                                TvButton(
                                    label = "ADD SERVER",
                                    glyph = "＋",
                                    style = TvButtonStyle.PRIMARY,
                                    onClick = {
                                        val error = vm.addCustomDnsServer(dnsDraft)
                                        dnsError = error
                                        if (error == null) dnsDraft = ""
                                    }
                                )
                            }
                            dnsError?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, color = Cinema.Offline, fontSize = 11.sp)
                            }
                        } else {
                            Text(
                                "Pick CUSTOM above to add your own resolvers. Preset resolvers " +
                                    "already use DNS-over-HTTPS and need no extra setup.",
                                color = Cinema.TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                        }
                        SettingsGroup("Connectivity") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = if (dnsTest.busy) "TESTING" else "TEST DNS",
                                glyph = "⌁",
                                style = TvButtonStyle.SECONDARY,
                                enabled = !dnsTest.busy,
                                onClick = { vm.testDns() }
                            )
                            dnsTest.message?.let {
                                InfoPill(it, if (dnsTest.success) Cinema.Online else Cinema.Offline, Cinema.InkSoft)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "DNS applies to TMDB and native playback. " +
                                "Plain entries use UDP port 53 unless you add :port; https:// entries use DNS-over-HTTPS " +
                                "to bypass ISP filtering. Falls back to the system resolver if a server fails.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        }
                    }
                    SettingsSection.ADDONS -> {
                        SettingsGroup(
                            "Registered addons",
                            hint = "${settings.addons.count { it.enabled }} ACTIVE"
                        ) {
                        Text(
                            "Addons publish direct streams through /stream/{type}/{id}.json. Enabled " +
                                "addons are queried for every title and their links appear in the player " +
                                "source picker.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        if (settings.addons.isEmpty()) {
                            Text(
                                "No addons registered yet. Paste a manifest URL such as " +
                                    "https://addon.example/manifest.json to start.",
                                color = Cinema.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            settings.addons.forEachIndexed { index, addon ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Cinema.Panel.copy(alpha = 0.55f))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = Cinema.Gold,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            addon.name.ifBlank { "Unnamed addon" },
                                            color = Cinema.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            addon.manifestUrl,
                                            color = Cinema.TextMuted,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TvButton(
                                        label = "▲",
                                        style = TvButtonStyle.GHOST,
                                        compact = true,
                                        enabled = index > 0,
                                        onClick = { vm.moveAddon(addon.id, -1) }
                                    )
                                    TvButton(
                                        label = "▼",
                                        style = TvButtonStyle.GHOST,
                                        compact = true,
                                        enabled = index < settings.addons.lastIndex,
                                        onClick = { vm.moveAddon(addon.id, 1) }
                                    )
                                    TvButton(
                                        label = if (addon.enabled) "ACTIVE" else "PAUSED",
                                        style = if (addon.enabled) {
                                            TvButtonStyle.SECONDARY
                                        } else {
                                            TvButtonStyle.GHOST
                                        },
                                        compact = true,
                                        onClick = { vm.toggleAddon(addon.id) }
                                    )
                                    TvButton(
                                        label = "REMOVE",
                                        glyph = "✕",
                                        style = TvButtonStyle.GHOST,
                                        compact = true,
                                        onClick = { vm.deleteAddon(addon.id) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Order is search priority: the addon at the top answers first when the player " +
                                "builds its source list. Use ▲ / ▼ to move an addon.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )

                        }
                        SettingsGroup("Register an addon", hint = "MANIFEST URL") {
                        FieldLabel("Stremio addon")
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(0.8f)) {
                                FieldLabel("Display name (optional)")
                                TvTextField(
                                    value = addonName,
                                    onValueChange = { addonName = it },
                                    placeholder = "Community addon"
                                )
                            }
                            Column(Modifier.weight(1.6f)) {
                                FieldLabel("Manifest URL")
                                TvTextField(
                                    value = addonUrl,
                                    onValueChange = { addonUrl = it },
                                    placeholder = "https://addon.example/manifest.json"
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = if (addonBusy) "CHECKING…" else "VALIDATE + ADD",
                                glyph = "＋",
                                style = TvButtonStyle.PRIMARY,
                                enabled = !addonBusy,
                                onClick = {
                                    val entered = addonUrl.trim()
                                    addonError = null
                                    addonNotice = null
                                    if (entered.isBlank()) {
                                        addonError = "Enter the addon manifest URL"
                                    } else {
                                        addonBusy = true
                                        scope.launch {
                                            val outcome = runCatching {
                                                withTimeoutOrNull(12_000L) {
                                                    StremioAddons.loadManifest(entered)
                                                } ?: throw IllegalStateException("Addon did not answer in time")
                                            }
                                            addonBusy = false
                                            outcome.onSuccess { manifest ->
                                                if (!manifest.supportsStreams && !manifest.supportsSubtitles) {
                                                    addonError =
                                                        "Manifest has no stream or subtitle resource · nothing to use"
                                                } else {
                                                    val error = vm.addAddon(
                                                        manifest.name.ifBlank { addonName },
                                                        entered,
                                                        manifest.logo.orEmpty()
                                                    )
                                                    if (error == null) {
                                                        addonNotice = "${manifest.name} " +
                                                            "${manifest.version} registered"
                                                        addonName = ""
                                                        addonUrl = ""
                                                    } else {
                                                        addonError = error
                                                    }
                                                }
                                            }.onFailure {
                                                addonError = it.message ?: "Could not reach the addon manifest"
                                            }
                                        }
                                    }
                                }
                            )
                            addonError?.let {
                                Text(it, color = Cinema.Offline, fontSize = 11.sp)
                            }
                            addonNotice?.let {
                                Text(it, color = Cinema.Online, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The manifest answers with name, version, resources, types and idPrefixes. " +
                                "Addon ids follow Stremio's convention (tmdb:550, tmdb:1399:1:1), so no extra " +
                                "configuration is needed per title.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        }
                        SettingsGroup(
                            "Source badges",
                            hint = if (badgeCatalog.isEmpty) "OPTIONAL"
                            else "${badgeCatalog.badges.size} BADGES"
                        ) {
                        Text(
                            "Badges are the wide pill images pinned to each source card (WEB-DL, HDR, " +
                                "Atmos…). Point the app at a JSON document listing a pattern and image " +
                                "per badge; patterns are matched against the stream name and description.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        FieldLabel("Badge document URL")
                        TvTextField(
                            value = badgeDraft,
                            onValueChange = {
                                badgeDraft = it
                                badgeError = null
                            },
                            placeholder = BADGE_SAMPLE,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = if (badgeState.busy) "LOADING…" else "LOAD BADGES",
                                glyph = "◈",
                                style = TvButtonStyle.PRIMARY,
                                enabled = !badgeState.busy,
                                onClick = { badgeError = vm.saveBadgeSource(badgeDraft) }
                            )
                            TvButton(
                                label = "EXAMPLE",
                                style = TvButtonStyle.GHOST,
                                compact = true,
                                enabled = !badgeState.busy,
                                onClick = {
                                    badgeDraft = BADGE_SAMPLE
                                    badgeError = vm.saveBadgeSource(BADGE_SAMPLE)
                                }
                            )
                            if (badgeCatalog.badges.isNotEmpty()) {
                                TvButton(
                                    label = "REFRESH",
                                    glyph = "⟳",
                                    style = TvButtonStyle.SECONDARY,
                                    compact = true,
                                    enabled = !badgeState.busy,
                                    onClick = { vm.refreshBadges() }
                                )
                            }
                            if (badgeDraft.isNotBlank() || badgeCatalog.badges.isNotEmpty()) {
                                TvButton(
                                    label = "CLEAR",
                                    glyph = "✕",
                                    style = TvButtonStyle.DANGER,
                                    compact = true,
                                    enabled = !badgeState.busy,
                                    onClick = {
                                        badgeDraft = ""
                                        badgeError = vm.saveBadgeSource("")
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val badgeMessage = badgeError ?: badgeState.message
                        if (badgeMessage != null) {
                            Text(
                                badgeMessage,
                                color = if (badgeError != null || badgeState.failed) Cinema.Offline
                                else Cinema.Online,
                                fontSize = 11.sp
                            )
                        } else if (badgeDraft.isNotBlank()) {
                            Text(
                                "Press LOAD BADGES to fetch the document and preview its images.",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp
                            )
                        } else {
                            Text(
                                "No badge source set. Source cards fall back to plain text.",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        if (badgeCatalog.badges.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            badgeCatalog.sections().take(3).forEach { (group, entries) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        group.name.uppercase(),
                                        color = Cinema.TextSecondary,
                                        fontSize = 9.sp,
                                        letterSpacing = 1.2.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(110.dp)
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        entries.take(8).forEach { badge ->
                                            AsyncImage(
                                                model = badge.imageUrl,
                                                contentDescription = badge.name,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.height(22.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                "Matched live against stream text; up to " +
                                    "${StreamBadgeCatalog.BADGES_PER_CARD} badges per card.",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        }
                    }
                    SettingsSection.IPTV -> {
                        SettingsGroup(
                            "Playlists",
                            hint = "${iptvSources.count { it.enabled }} ACTIVE"
                        ) {
                        Text(
                            "Load remote M3U/M3U8 and XSPF playlists, local files or single UDP/RTP/RTSP streams. " +
                                "tvg-id, tvg-name, tvg-logo, group-title and tvg-chno are read from extended M3U; " +
                                "groups become the Live filter chips. XMLTV guides (.xml / .xml.gz) supply now/next.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        if (iptvSources.isEmpty()) {
                            Text(
                                "No IPTV sources yet. Paste a playlist URL, or open a local .m3u/.m3u8/.xspf file.",
                                color = Cinema.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            iptvSources.forEach { source ->
                                val guide = source.epgUrl.ifBlank { source.extractedEpgUrl }
                                IptvSourceCard(
                                    source = source,
                                    guide = guide,
                                    expanded = expandedIptvId == source.id,
                                    autoShift = epgState.autoShifts[source.id],
                                    iptvBusy = iptvBusy,
                                    iptvBusyId = iptvBusyId,
                                    editFocus = iptvEditFocus.getOrPut(source.id) {
                                        FocusRequester()
                                    },
                                    onToggle = {
                                        expandedIptvId =
                                            if (expandedIptvId == source.id) null else source.id
                                    },
                                    onShift = { step ->
                                        val effective = if (source.epgShiftAuto) {
                                            epgState.autoShifts[source.id] ?: 0
                                        } else {
                                            source.epgShiftMinutes
                                        }
                                        vm.setIptvEpgShift(source.id, effective + step)
                                    },
                                    onResetShift = { vm.resetIptvEpgShift(source.id) },
                                    onEdit = {
                                        iptvEditSource = source
                                        iptvFocusReturnId = source.id
                                        railReturnOffset = railScroll.value
                                        paneReturnOffset = sectionScroll.value
                                        iptvEditName = source.name
                                        iptvEditUrl = source.location
                                        iptvEditEpgUrl = source.epgUrl
                                        iptvEditError = null
                                    },
                                    onReload = {
                                        iptvBusyId = source.id
                                        iptvError = null
                                        iptvNotice = null
                                        scope.launch {
                                            val error = vm.refreshIptvSourceNow(source.id)
                                            iptvBusyId = null
                                            if (error == null) {
                                                iptvNotice = "${source.name} reloaded"
                                            } else {
                                                iptvError = error
                                            }
                                        }
                                    },
                                    onToggleEnabled = {
                                        vm.setIptvEnabled(source.id, !source.enabled)
                                    },
                                    onRemove = { vm.removeIptvSource(source.id) }
                                )
                            }
                        }
                        }
                        SettingsGroup(
                            "Channel health",
                            hint = if (deadChannels.isEmpty()) "ALL RESPONDING" else "${deadChannels.size} DEAD"
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (deadChannels.isEmpty()) {
                                        "Every cached channel answered its last probe. Failed channels " +
                                            "stay listed until you forget them."
                                    } else {
                                        "${deadChannels.size} channel(s) failed repeated probes and are hidden " +
                                            "from Live. Forgetting them drops their entries and logos."
                                    },
                                    color = Cinema.TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TvButton(
                                    label = "FORGET DEAD",
                                    glyph = "⌫",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = deadChannels.isNotEmpty(),
                                    onClick = { vm.forgetDeadChannels(deadChannels) }
                                )
                            }
                        }
                        SettingsGroup("Add a playlist", hint = "M3U · XSPF · STREAM") {
                        FieldLabel("Add an IPTV source")
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(0.8f)) {
                                FieldLabel("Display name (optional)")
                                TvTextField(
                                    value = iptvName,
                                    onValueChange = { iptvName = it },
                                    placeholder = "My provider"
                                )
                            }
                            Column(Modifier.weight(1.6f)) {
                                FieldLabel("Playlist URL or local path")
                                TvTextField(
                                    value = iptvUrl,
                                    onValueChange = { iptvUrl = it },
                                    placeholder = "https://provider.example/playlist.m3u"
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FieldLabel("EPG URL override (optional · XMLTV .xml / .xml.gz)")
                        TvTextField(
                            value = iptvEpgUrl,
                            onValueChange = { iptvEpgUrl = it },
                            placeholder = "https://provider.example/guide.xml.gz"
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = "OPEN LOCAL FILE",
                                glyph = "▤",
                                style = TvButtonStyle.SECONDARY,
                                enabled = !iptvBusy,
                                onClick = { filePicker.launch(arrayOf("*/*")) }
                            )
                            TvButton(
                                label = if (iptvBusy) "LOADING PLAYLIST…" else "ADD SOURCE",
                                glyph = "＋",
                                style = TvButtonStyle.PRIMARY,
                                enabled = !iptvBusy,
                                onClick = {
                                    val entered = iptvUrl.trim()
                                    iptvError = null
                                    iptvNotice = null
                                    if (entered.isBlank()) {
                                        iptvError = "Enter a playlist URL or pick a local file"
                                    } else {
                                        iptvBusy = true
                                        scope.launch {
                                            val error = vm.addIptvSource(entered, iptvName, iptvEpgUrl)
                                            iptvBusy = false
                                            if (error == null) {
                                                iptvNotice = "Playlist loaded · channels appear on the Live tab"
                                                iptvUrl = ""
                                                iptvName = ""
                                                iptvEpgUrl = ""
                                            } else {
                                                iptvError = error
                                            }
                                        }
                                    }
                                }
                            )
                            TvButton(
                                label = if (epgState.loading) "EPG LOADING…" else "REFRESH EPG",
                                glyph = "⌁",
                                style = TvButtonStyle.GHOST,
                                enabled = !epgState.loading && iptvSources.any { it.enabled },
                                onClick = { vm.refreshEpg(force = true) }
                            )
                            iptvError?.let {
                                Text(it, color = Cinema.Offline, fontSize = 11.sp)
                            }
                            iptvNotice?.let {
                                Text(it, color = Cinema.Online, fontSize = 11.sp)
                            }
                        }
                        epgState.message?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Cinema.TextMuted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pipes inject headers per channel: " +
                                "http://host/live.m3u8|User-Agent=VLC/3.0&Referer=https://site. " +
                                "Playlists may declare url-tvg / x-tvg-url / tvg-url to supply the guide " +
                                "automatically. EPG SHIFT detects a guide whose provider stamps every programme " +
                                "with the wrong UTC offset and corrects it; the steppers make that correction " +
                                "manual and AUTO hands the source back to detection. " +
                                "UDP/RTP/RTSP channels open in an external player.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        }
                    }
                    SettingsSection.SYNC -> {
                        SettingsGroup("Refresh schedules", hint = "PER SOURCE TYPE") {
                        Text(
                            "Schedules run per source type at launch and every 10 minutes while open.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(14.dp))
                        SyncCadenceRow(
                            title = "Imported lists",
                            summary = "${importSources.size} saved source(s) · MDBList, TMDB lists and JSON manifests",
                            cadence = settings.importsSync,
                            running = SyncTarget.IMPORTS in syncState.running,
                            lastRun = syncState.lastRun[SyncTarget.IMPORTS] ?: 0L,
                            error = syncState.errors[SyncTarget.IMPORTS],
                            onSelect = { vm.setSyncCadence(SyncTarget.IMPORTS, it) },
                            onSyncNow = { vm.syncNow(SyncTarget.IMPORTS) }
                        )
                        Spacer(Modifier.height(10.dp))
                        SyncCadenceRow(
                            title = "IPTV playlists",
                            summary = "${iptvSources.count { it.enabled }} enabled playlist(s) · " +
                                "playlists and their XMLTV guide",
                            cadence = settings.iptvSync,
                            running = SyncTarget.IPTV in syncState.running,
                            lastRun = syncState.lastRun[SyncTarget.IPTV] ?: 0L,
                            error = syncState.errors[SyncTarget.IPTV],
                            onSelect = { vm.setSyncCadence(SyncTarget.IPTV, it) },
                            onSyncNow = { vm.syncNow(SyncTarget.IPTV) }
                        )
                        Spacer(Modifier.height(10.dp))
                        SyncCadenceRow(
                            title = "Stremio addons",
                            summary = "${settings.addons.count { it.enabled }} enabled addon(s) · " +
                                "manifests and published catalogs",
                            cadence = settings.addonsSync,
                            running = SyncTarget.ADDONS in syncState.running,
                            lastRun = syncState.lastRun[SyncTarget.ADDONS] ?: 0L,
                            error = syncState.errors[SyncTarget.ADDONS],
                            onSelect = { vm.setSyncCadence(SyncTarget.ADDONS, it) },
                            onSyncNow = { vm.syncNow(SyncTarget.ADDONS) }
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Timestamps are stored per type and survive restarts; a source that fails " +
                                "keeps the titles and playlists it already cached.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        }
                    }
                    SettingsSection.BACKUP -> {
                        SettingsGroup("Settings transfer", hint = "JSON FILE") {
                        Text(
                            "Export the full app configuration — access, appearance, playback, " +
                                "network, decoder and addons — as a JSON file, or restore a previous export.",
                            color = Cinema.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvButton(
                                label = "EXPORT SETTINGS .JSON",
                                glyph = "⇩",
                                style = TvButtonStyle.SECONDARY,
                                onClick = {
                                    val json = settingsJson(settings)
                                    scope.launch {
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                JsonBackup.writeToDownloads(
                                                    context,
                                                    "cinema-settings-${JsonBackup.timestamp()}.json",
                                                    json
                                                )
                                            }
                                        }
                                        result.onSuccess {
                                            backupFailed = false
                                            backupNotice = "Settings exported → $it"
                                        }.onFailure {
                                            backupFailed = true
                                            backupNotice = "Export failed: ${it.message ?: "unknown error"}"
                                        }
                                    }
                                }
                            )
                            TvButton(
                                label = "IMPORT SETTINGS .JSON",
                                glyph = "⇧",
                                style = TvButtonStyle.PRIMARY,
                                onClick = {
                                    settingsImport.launch(
                                        arrayOf("application/json", "text/plain", "*/*")
                                    )
                                }
                            )
                        }
                        backupNotice?.let { notice ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                notice,
                                color = if (backupFailed) Cinema.Offline else Cinema.Online,
                                fontSize = 11.sp
                            )
                        }
                        }
                        SettingsGroup("Scope & restore") {
                        Text(
                            "The export contains your TMDB key, proxy URL and addon list. " +
                                "Keep the file private.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Restoring replaces the current settings immediately. Favorites and " +
                                "the library are not part of this file — use EXPORT .JSON / " +
                                "IMPORT .JSON on the Favorites tab for those.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            lineHeight = 15.sp
                        )
                        }
                    }
                    SettingsSection.ABOUT -> {
                        val release = update.release
                        SettingsGroup(
                            "Updates",
                            hint = when {
                                update.downloading -> "${(update.progress * 100).toInt()}%"
                                release != null -> "v${release.tag.removePrefix("v")}"
                                update.checking -> "CHECKING"
                                update.upToDate -> "UP TO DATE"
                                update.noRelease -> "NO RELEASE"
                                update.error != null -> "OFFLINE"
                                else -> "GITHUB"
                            }
                        ) {
                            SettingsToggleRow(
                                label = "Check GitHub releases",
                                checked = settings.autoUpdateCheck,
                                onChange = { vm.saveSettings { s -> s.copy(autoUpdateCheck = it) } },
                                description = "Syncs with github.com/M3UCurator/StreamLine-Cinema at startup"
                            )
                            Text(
                                when {
                                    update.downloading ->
                                        "Downloading v${release?.tag?.removePrefix("v")} · " +
                                            "${(update.progress * 100).toInt()}%"
                                    release != null ->
                                        "v${release.tag.removePrefix("v")} is available · " +
                                            formatCacheSize(release.asset?.size ?: 0)
                                    update.checking -> "Checking the latest published release…"
                                    update.upToDate -> "Up to date · v${BuildConfig.ITERATION}"
                                    update.noRelease ->
                                        "No release published yet · publish one at " +
                                            "github.com/M3UCurator/StreamLine-Cinema/releases"
                                    update.error != null -> update.error.orEmpty()
                                    else -> "Check now to compare with the latest GitHub release."
                                },
                                color = if (update.error != null) Cinema.Offline else Cinema.TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            if (update.installStarted) {
                                Text(
                                    "Installer opened · confirm the update in the system dialog",
                                    color = Cinema.Online,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvButton(
                                    label = "CHECK NOW",
                                    glyph = "↻",
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    enabled = !update.checking && !update.downloading,
                                    onClick = { vm.checkForUpdates() }
                                )
                                if (release != null && !update.downloading) {
                                    TvButton(
                                        label = "DOWNLOAD & INSTALL",
                                        glyph = "↓",
                                        style = TvButtonStyle.PRIMARY,
                                        compact = true,
                                        onClick = { vm.downloadAndInstallUpdate() }
                                    )
                                }
                            }
                            if (update.downloading) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White.copy(alpha = 0.10f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(update.progress.coerceIn(0f, 1f))
                                            .height(3.dp)
                                            .background(Cinema.Gold)
                                    )
                                }
                            }
                        }
                        SettingsGroup("Application", hint = "v${BuildConfig.VERSION_NAME}") {
                            Text(
                                "StreamLine Cinema · a leanback TV client for TMDB discovery, " +
                                    "Stremio addons and IPTV playlists.",
                                color = Cinema.TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Version ${BuildConfig.VERSION_NAME} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "android"}",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        SettingsGroup("Capabilities") {
                            Text(
                                "ExoPlayer, VLC and MPV handle HLS, MPEG-DASH and progressive files, " +
                                    "with an FFmpeg decoder option and external player hand-off through " +
                                    "the system chooser. Every control is focusable for D-pad remotes; " +
                                    "the left rail switches settings sections.",
                                color = Cinema.TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    iptvEditSource?.let { source ->
        IptvEditDialog(
            source = source,
            name = iptvEditName,
            location = iptvEditUrl,
            epgUrl = iptvEditEpgUrl,
            busy = iptvEditBusy,
            error = iptvEditError,
            onNameChange = { iptvEditName = it },
            onLocationChange = {
                iptvEditUrl = it
                iptvEditError = null
            },
            onEpgChange = { iptvEditEpgUrl = it },
            onPickFile = { editFilePicker.launch(arrayOf("*/*")) },
            onDismiss = {
                if (!iptvEditBusy) {
                    iptvFocusReturnId = source.id
                    iptvEditSource = null
                    iptvFocusTick++
                }
            },
            onSave = {
                val entered = iptvEditUrl.trim()
                iptvError = null
                iptvNotice = null
                if (entered.isBlank()) {
                    iptvEditError = "Enter a playlist URL or pick a local file"
                } else {
                    iptvEditError = null
                    iptvEditBusy = true
                    scope.launch {
                        val error = vm.updateIptvSource(source.id, entered, iptvEditName, iptvEditEpgUrl)
                        iptvEditBusy = false
                        if (error == null) {
                            val saved = vm.iptvSources.value.firstOrNull { it.id == source.id }
                            val label = saved?.name?.takeIf { it.isNotBlank() } ?: "Playlist"
                            iptvNotice = "$label saved · ${saved?.channelCount ?: source.channelCount} channels"
                            iptvFocusReturnId = source.id
                            iptvEditSource = null
                            iptvFocusTick++
                        } else {
                            iptvEditError = error
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun IptvEditDialog(
    source: IptvSource,
    name: String,
    location: String,
    epgUrl: String,
    busy: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onLocationChange: (String) -> Unit,
    onEpgChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(180)
        runCatching { firstField.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The window wraps this column instead of painting a full-screen overlay:
        // taps and the D-pad land on the controls the viewer can actually see.
        Column(
            Modifier
                .width(720.dp)
                .heightIn(max = 560.dp)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
                Text(
                    "Edit IPTV source",
                    color = Cinema.TextPrimary,
                    style = CinemaType.SectionTitle
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${source.kind.label} · ${source.channelCount} channels · a new address reloads first.",
                    color = Cinema.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    FieldLabel("Display name (optional)")
                    TvTextField(
                        value = name,
                        onValueChange = onNameChange,
                        placeholder = "My provider",
                        focusRequester = firstField
                    )
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("Playlist URL or local path")
                    TvTextField(
                        value = location,
                        onValueChange = onLocationChange,
                        placeholder = "https://provider.example/playlist.m3u"
                    )
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("EPG URL override (optional · XMLTV .xml / .xml.gz)")
                    TvTextField(
                        value = epgUrl,
                        onValueChange = onEpgChange,
                        placeholder = "https://provider.example/guide.xml.gz"
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Cinema.Offline, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(
                        label = if (busy) "SAVING…" else "SAVE CHANGES",
                        onClick = onSave,
                        style = TvButtonStyle.PRIMARY,
                        enabled = !busy,
                        glyph = "✓"
                    )
                    TvButton(
                        label = "OPEN LOCAL FILE",
                        onClick = onPickFile,
                        style = TvButtonStyle.SECONDARY,
                        enabled = !busy,
                        glyph = "▤"
                    )
                    TvButton(
                        label = "CANCEL",
                        onClick = onDismiss,
                        style = TvButtonStyle.GHOST,
                        enabled = !busy
                    )
                }
            }
    }
}

private fun settingsJson(settings: AppSettings): String {
    val root = JSONObject()
    root.put("app", "StreamLine Cinema")
    root.put("version", 1)
    root.put("exportedAt", JsonBackup.isoTimestamp())
    root.put("settings", settings.toJson())
    return root.toString(2)
}

private fun parseSettingsJson(text: String): AppSettings {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) error("The selected file is empty")
    val root = JSONObject(trimmed)
    val settingsJson = root.optJSONObject("settings")
    if (settingsJson == null) {
        val known = listOf(
            "tmdbApiKey", "proxyBaseUrl", "playbackEngine", "dnsMode", "addons", "posterSize"
        ).any { root.has(it) }
        require(known) { "No settings object found in that file" }
    }
    return AppSettings.fromJson(settingsJson ?: root)
}

private fun PlaybackEngine.settingLabel(): String = when (this) {
    PlaybackEngine.AUTO -> "AUTO"
    PlaybackEngine.BUILT_IN -> "BUILT-IN"
    PlaybackEngine.EXTERNAL -> "EXTERNAL"
    PlaybackEngine.VLC -> "VLC"
    PlaybackEngine.MPV -> "MPV"
}

private fun epgShiftLabel(minutes: Int): String {
    if (minutes == 0) return "OFF"
    val sign = if (minutes > 0) "+" else "-"
    val total = kotlin.math.abs(minutes)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "$sign${rest}M"
        rest == 0 -> "$sign${hours}H"
        else -> "$sign${hours}H${rest}M"
    }
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1_024.0)
}

private fun syncAgo(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at).coerceAtLeast(0L)) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

@Composable
private fun SyncCadenceRow(
    title: String,
    summary: String,
    cadence: SyncCadence,
    running: Boolean,
    lastRun: Long,
    error: String?,
    onSelect: (SyncCadence) -> Unit,
    onSyncNow: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Cinema.Panel.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    color = Cinema.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(summary, color = Cinema.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                Text(
                    text = when {
                        running -> "Refreshing now…"
                        error != null -> "Last attempt failed · $error"
                        lastRun <= 0L -> "Never refreshed"
                        else -> "Last refresh ${syncAgo(lastRun)}"
                    },
                    color = if (error != null) Cinema.Offline else Cinema.TextMuted,
                    fontSize = 10.sp
                )
            }
            TvButton(
                label = if (running) "REFRESHING…" else "SYNC NOW",
                glyph = "⟳",
                style = TvButtonStyle.GHOST,
                compact = true,
                enabled = !running,
                onClick = onSyncNow
            )
        }
        SettingsSelectRow(
            title = "Schedule",
            selected = cadence,
            options = SyncCadence.entries,
            optionLabel = { it.label },
            onSelect = onSelect
        )
    }
}

@Composable
private fun SettingsNavRow(
    section: SettingsSection,
    selected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(9.dp)
    val highlight = selected || focused
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .focusRequester(focusRequester)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = when {
                    selected -> Cinema.Gold.copy(alpha = if (focused) 0.24f else 0.15f)
                    focused -> Glass.Control
                    else -> Color.Transparent
                },
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.45f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    sounds.select()
                    onClick()
                }
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            section.label,
            color = if (highlight) Cinema.TextPrimary else Cinema.TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(Cinema.Gold, RoundedCornerShape(2.dp))
            )
        }
    }
}

private val SettingsRowShape = RoundedCornerShape(10.dp)

@Composable
private fun SettingsRowFrame(
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .glassSurface(
                shape = SettingsRowShape,
                focused = focused,
                base = if (focused) Glass.Control else Cinema.Panel.copy(alpha = 0.42f),
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.5f
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SettingsSwitch(checked: Boolean) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) Cinema.Gold else Cinema.Border)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) Cinema.Ink else Cinema.TextSecondary)
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    SettingsRowFrame(
        focused = focused,
        modifier = Modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    sounds.select()
                    onChange(it)
                }
            )
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = Cinema.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    description,
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SettingsSwitch(checked)
    }
}

@Composable
private fun IptvSourceCard(
    source: IptvSource,
    guide: String,
    expanded: Boolean,
    autoShift: Int?,
    iptvBusy: Boolean,
    iptvBusyId: String?,
    editFocus: FocusRequester,
    onToggle: () -> Unit,
    onShift: (Int) -> Unit,
    onResetShift: () -> Unit,
    onEdit: () -> Unit,
    onReload: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val effectiveShift = if (source.epgShiftAuto) autoShift ?: 0 else source.epgShiftMinutes
    val shiftLabel = if (source.epgShiftAuto) {
        if (effectiveShift == 0) "AUTO" else "AUTO ${epgShiftLabel(effectiveShift)}"
    } else {
        epgShiftLabel(effectiveShift)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsRowFrame(
            focused = focused,
            modifier = Modifier
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) sounds.focus()
                }
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    contentDescription = buildString {
                        append(source.name.ifBlank { "Unnamed playlist" })
                        append(if (source.enabled) ", active" else ", paused")
                        append(", ${source.kind.label}, ${source.channelCount} channels")
                        append(if (guide.isBlank()) ", no EPG" else ", EPG linked")
                        append(if (expanded) ", expanded" else ", collapsed")
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    sounds.select()
                    onToggle()
                }
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    source.name.ifBlank { "Unnamed playlist" },
                    color = Cinema.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${source.kind.label} · ${source.channelCount} channels · " +
                        if (guide.isBlank()) "no EPG" else "EPG linked",
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            InfoPill(
                if (source.enabled) "ACTIVE" else "PAUSED",
                if (source.enabled) Cinema.Online else Cinema.Pinging,
                Cinema.InkSoft
            )
            Text(
                if (expanded) "▴" else "▾",
                color = Cinema.TextMuted,
                fontSize = 10.sp
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    source.cleanLocation,
                    color = Cinema.TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (source.lastReport.isNotBlank()) {
                    Text(
                        "Parsed · ${source.lastReport}",
                        color = if (source.lastReport.contains("malformed")) {
                            Cinema.Pinging
                        } else {
                            Cinema.TextMuted
                        },
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (guide.isBlank()) {
                    Text(
                        "No EPG guide linked · add one with EDIT",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "EPG SHIFT $shiftLabel",
                            color = if (effectiveShift == 0) Cinema.TextMuted else Cinema.Gold,
                            fontSize = 9.sp,
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf(-60 to "-1H", -30 to "-30M", 30 to "+30M", 60 to "+1H")
                            .forEach { (step, label) ->
                                TvButton(
                                    label = label,
                                    style = TvButtonStyle.GHOST,
                                    compact = true,
                                    onClick = { onShift(step) }
                                )
                            }
                        if (!source.epgShiftAuto) {
                            TvButton(
                                label = "AUTO",
                                style = TvButtonStyle.GHOST,
                                compact = true,
                                onClick = onResetShift
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvButton(
                        label = "EDIT",
                        glyph = "✎",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        enabled = iptvBusyId == null && !iptvBusy,
                        focusRequester = editFocus,
                        onClick = onEdit
                    )
                    TvButton(
                        label = if (iptvBusyId == source.id) "LOADING…" else "RELOAD",
                        glyph = "↻",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        enabled = iptvBusyId == null && !iptvBusy,
                        onClick = onReload
                    )
                    Spacer(Modifier.weight(1f))
                    TvButton(
                        label = if (source.enabled) "PAUSE" else "RESUME",
                        glyph = if (source.enabled) "❙❙" else "▶",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        onClick = onToggleEnabled
                    )
                    TvButton(
                        label = "REMOVE",
                        glyph = "✕",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        onClick = onRemove
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SettingsSelectRow(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var groupFocused by remember { mutableStateOf(false) }
    var leaveTick by remember { mutableIntStateOf(0) }
    var restoreTick by remember { mutableIntStateOf(0) }
    val sounds = LocalUiSounds.current
    val triggerFocus = remember { FocusRequester() }
    val optionFocus = remember(options) { List(options.size) { FocusRequester() } }
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(60)
            runCatching { optionFocus.getOrNull(selectedIndex)?.requestFocus() }
        }
    }
    // Moving focus into the option list clears the trigger's focus for a frame, which would
    // look like "focus left" and close the menu. Only close when focus stays outside the group.
    LaunchedEffect(leaveTick) {
        if (leaveTick == 0) return@LaunchedEffect
        delay(160)
        if (!groupFocused && expanded) expanded = false
    }
    LaunchedEffect(restoreTick) {
        if (restoreTick == 0) return@LaunchedEffect
        delay(80)
        runCatching { triggerFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged {
                groupFocused = it.hasFocus
                if (!it.hasFocus && expanded) leaveTick++
            },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SettingsRowFrame(
            focused = focused,
            modifier = Modifier
                .focusRequester(triggerFocus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) sounds.focus()
                }
                .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                sounds.select()
                expanded = !expanded
            }
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Cinema.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    Text(
                        description,
                        color = Cinema.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                optionLabel(selected),
                color = Cinema.Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (expanded) "▴" else "▾",
                color = Cinema.TextMuted,
                fontSize = 10.sp
            )
        }
        if (expanded) {
            options.forEachIndexed { index, option ->
                SettingsOptionRow(
                    label = optionLabel(option),
                    selected = option == selected,
                    focusRequester = optionFocus.getOrNull(index),
                    onClick = {
                        sounds.select()
                        onSelect(option)
                        expanded = false
                        restoreTick++
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsOptionRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .glassSurface(
                shape = SettingsRowShape,
                focused = focused,
                base = when {
                    selected -> Cinema.Gold.copy(alpha = 0.16f)
                    focused -> Glass.Control
                    else -> Cinema.Panel.copy(alpha = 0.28f)
                },
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.4f
            )
            .padding(start = 28.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = when {
                selected -> Cinema.Gold
                focused -> Cinema.TextPrimary
                else -> Cinema.TextSecondary
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = Cinema.Gold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    SettingsRowFrame(focused = false) {
        Text(
            title,
            color = Cinema.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = Cinema.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title.uppercase(),
                color = Cinema.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            hint?.let { InfoPill(it, Cinema.Gold, Cinema.InkSoft) }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
        Spacer(Modifier.height(18.dp))
    }
}
