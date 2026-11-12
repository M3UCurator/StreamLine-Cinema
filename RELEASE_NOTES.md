# StreamLine Cinema v12.81

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.81 — Quieter Live header

The Live tab keeps its diagnostics but stops putting them in the way of the channels.

- **ADD ONS removed** — the button duplicated Settings → Addons and made the action row long for no gain, so it is gone from the Live header.
- **DETAILS moved up** — the details button now sits in that top-right slot beside REFRESH and opens the same **Guide status** dialog. It stays disabled until a guide or playlist report exists, so it never opens an empty sheet.
- **Inline status removed** — the `Guide adjusted · Playlist · 1 malformed` sentence that ran under the action row is no longer drawn. The catalog chips now occupy that row alone.
- **Nothing lost** — every line the sentence summarised (the guide report, the `-1h` shift notice and the playlist parse report) is still listed in the DETAILS dialog.

## Verified on 12.81

- On the installed all-ABI release the Live header reads LIST · ARRANGE · GUIDE · REFRESH · DETAILS, with no ADD ONS button and no status text under the cards' header.
- Opening DETAILS shows `GUIDE · 6 channels`, `◷ Test Provider: guide is -1h off — adjusted` and `PLAYLIST · Test Provider · 6 channels · 1 malformed`.
- The all-ABI release installs as `versionCode 120081` / `versionName 12.81`.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema121126v12.81.apk` | `armeabi-v7a` (TV boxes) | 76,270,086 bytes | `e032e223778f9f733d461831f3d501654370c71b95e6d2097fda7f9ec295314a` |
| `Cinema121126v12.81-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,739 bytes | `cf38d590c9a581ddaf31208859a62fd8eaf8439d6bdcb4fba1ea250260c2d915` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema121126v12.81.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.80 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.80 — Edge-to-edge channel artwork

The Live cards now treat the logo as their fill instead of a picture inside a frame, so the glass treatment carries the card rather than a visible inset.

- **No more frame** — the 14 dp horizontal / 10 dp vertical artwork inset is gone. It exposed the card's panel fill as a band around every logo, which read as a border and cheapened the cards.
- **Artwork as fill** — the 16:9 logo paints edge to edge inside the 12 dp glass clip, with `ContentScale.Fit` still keeping the whole logo visible (no zoom or crop). Letterboxing only appears for artwork whose aspect ratio differs from the card, and it sits directly under the image.
- **Glass-only edge** — the card keeps just the glass vocabulary: a 1 px edge light and the gold focus underline; there is no solid frame, halo or inset.
- **Consistent list rows** — the 76 dp list thumbnails drop their 4 dp inset and fill their rounded clip the same way.

## Verified on 12.80

- On the installed all-ABI release the Live grid renders every logo to the card edge in both the grid and the LIST rows; no 14 dp band or panel frame is visible, only the glass edge light and focus underline.
- The all-ABI release installs as `versionCode 120080` / `versionName 12.80`.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema121126v12.80.apk` | `armeabi-v7a` (TV boxes) | 76,270,092 bytes | `f871a7da38482dc62e09b4476033c7ba0bf0d307e92aa5c4f36dde02d3a942fe` |
| `Cinema121126v12.80-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,731 bytes | `047310adca78043d8f9905091e85933a2d16becd0e067d5d3b358d37184c4465` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema121126v12.80.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.79 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.79 — Live artwork, a list view and a quieter header

The Live tab keeps its premium cards, but the artwork now fits, a compact list joins the grid, and guide/playlist diagnostics no longer push the channel rows down.

- **Fitted channel logos** — card artwork renders with `ContentScale.Fit` and a slim 14 dp / 10 dp inset over a panel background, so 16:9 channel logos sit whole inside the card instead of being zoomed and cropped. The bottom scrim was deepened so the NOW title and time span stay legible over bright artwork.
- **LIST / GRID toggle** — a new toggle next to ARRANGE switches between the artwork grid and 76 dp compact rows: fitted logo thumb, channel name with category and release info, the current programme with `start – stop` and its progress bar, and tiny LIVE / TUNING / DEAD badges. Long-press OK stars a channel and, while arranging, Up/Down reorders channels in the list exactly as in the grid. The choice is persisted as `listView` in `live_prefs.json`, and the row list keeps its own focus/scroll memory when toggling.
- **One-row status line** — catalog chips and the guide/playlist status share a single row: the chips scroll on the left, while a muted dot, one ellipsized sentence (`Guide adjusted · …`, `Playlist · N malformed`, `Guide update failed`) and a right-aligned **DETAILS** button sit on the right. **DETAILS** opens a wide (620 dp) **Guide status** dialog listing the EPG source, the guide line, the shift notice and the playlist report, with the platform width cap opted out so the text is no longer cramped.
- **Aligned header** — the meta line reads `N channels · 1 catalog` / `N catalogs` (no more "1 catalogs"), and tighter top padding and spacers leave room for the full card captions at 1080p.

## Verified on 12.79

- On the installed all-ABI release, the Live grid shows every channel logo whole inside its card with the NOW overlay and caption visible on the first two rows.
- The LIST toggle switches to compact rows (logo thumb, name/category, NOW title with `10:00 – 11:00` and a gold progress bar, LIVE badge) and back to the grid; `files/live_prefs.json` holds `"listView":true` while list mode is selected.
- The catalog/status row and its **DETAILS** dialog render at the widened 620 dp width.
- The all-ABI release installs as `versionCode 120079` / `versionName 12.79`; `testDebugUnitTest` passes with no failures.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema121126v12.79.apk` | `armeabi-v7a` (TV boxes) | 76,270,098 bytes | `1534300c8761b899e55afe554afdd3cb24ecc0cc724a830c4dd9e18f5ec65e18` |
| `Cinema121126v12.79-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,743 bytes | `a947699a30650beb13ae01207a3acb029c835a7f9e058850e99e96a85149b88b` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema121126v12.79.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.78 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.78 — Stream resilience when the provider goes quiet

The recovery paths added earlier are now complete: a provider that drops a socket, starves a segment or sends junk is retried in place when that can work, and failed fast when it cannot.

- **Fast-fail load policy** — a definitive 4xx (except 408 and 429), an unsupported manifest or container, and malformed manifest/container data no longer grind through the 0.5 / 1 / 2 / 4 / 8 s transport budget. Malformed data gets exactly one retry (usually a truncated transfer); everything else in that list fails on the spot, so failover or the error panel is not delayed by up to six pointless retries.
- **Trickle-safe stall detection** — the 15 s watchdog now compares anchors for both the playhead and the buffered position and counts the stream as frozen only when neither has moved 250 ms across the whole window, while the player is not paused, scrubbing or audio-suppressed. A provider that trickles in a few dozen milliseconds per second keeps playing instead of being misread as dead; a seek in either direction re-baselines the anchors.
- **Automatic on-demand resume** — a film that fails with a transient transport code reconnects the same source twice, after 3 s and 6 s, and re-seeks to the snapshotted playhead, so the retry costs no viewing position. Live keeps its three reconnects at 5 s, 10 s and 15 s.
- **Stall recovery before the panel** — a stall first re-prepares the same source after 2 s (twice as long on each further attempt, capped at 8 s): three attempts on live, two on a film, on the budget shared with error reconnects. Only when that budget is spent does the panel read **Playback stalled** / **Channel stalled** and hand over to source failover. The reconnect notice shows `Playback stalled · reconnecting in Ns` / `Connection lost · reconnecting in Ns`.
- **Re-armed after every reload** — the watchdog starts a fresh 15 s window on every re-prepare, so a reload is never mistaken for an immediate second stall.
- **Observable decisions** — each automatic retry logs the reason, attempt and delay on `StreamLine:player` (`stall · re-preparing the same source in 2000 ms (attempt 1/2)`, `transient · ERROR_CODE_IO_NETWORK_CONNECTION_FAILED · re-preparing …`).

## Verified on 12.78

- The full unit suite passes: 211 tests, 0 failures. `StreamLoadPolicyTest` drives the load policy with real `ParserException` / `InvalidResponseCodeException` instances (definitive 4xx final, 408/429 retried, unsupported final, malformed one retry then final), and `PlaybackWatchdogTest` adds the trickle, backward-seek and shared-budget cases to the existing live/on-demand watchdog matrix.
- **Stall path, end to end on the API 37 emulator:** a local HLS fixture that trickles a 188-byte TS packet every second without ever completing the segment produced `stall · re-preparing the same source in 2000 ms (attempt 1/2)` on logcat and the gold pill `Playback stalled · reconnecting in 2s` over the frozen frame; the player re-prepared the same URL automatically, the fixture served the next request in full, playback continued, and neither the `Playback failed` nor a second stall line appeared — recovery finished before the panel.
- **On-demand transient path, end to end:** a fixture that cut the first six requests of the second segment produced `transient · ERROR_CODE_IO_NETWORK_CONNECTION_FAILED · re-preparing the same source in 3000 ms (attempt 1/2)`, the pill `Connection lost · reconnecting in 3s`, and the seventh request served the real segment; the saved playhead was restored and playback continued without the error panel.
- `adb emu network delay` reported success but had no measurable effect on the guest (an HTTPS fetch still returned in 0.07 s), so both paths were exercised with a loopback fixture on `10.0.2.2:8099` instead of shaped networking.
- The all-ABI release installs as `versionCode 120078` / `versionName 12.78`; **Settings → About** reads `v12.78` and the updater shows `NO RELEASE` while the repository has no published release, as on 12.77.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema111126v12.78.apk` | `armeabi-v7a` (TV boxes) | 76,253,699 bytes | `b0961cd2ab28e123611684e12ad0c836fe2f2c37cd75f0cf6a484ddc676c0fc3` |
| `Cinema111126v12.78-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,452,330 bytes | `8c87cfec4bc56e43d0fa1866f18ec366080fe735c25c16c1be330065220cc6d7` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema111126v12.78.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.77 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.77 — Update sync with GitHub releases

The app now reads this repository's GitHub Releases and can install a newer build without leaving the couch.

- **Startup sync** — four seconds after launch, when **Settings → About → Check GitHub releases** is on (the default), the app calls `/repos/M3UCurator/StreamLine-Cinema/releases/latest`. A newer tag opens an **Update available** prompt with **INSTALL UPDATE** / **LATER**; the prompt stays dismissed per tag.
- **Manual check** — **Settings → About → Updates** shows the sync toggle, the current state chip and a status line, with **CHECK NOW**. The same group carries **DOWNLOAD & INSTALL** when a release is available.
- **Numeric version comparison** — `v12.100` counts as newer than `v12.99`, and `12.7` equals `12.70`. The installed version is `BuildConfig.ITERATION`.
- **Monotonic versionCode** — `versionCode` is now derived from the iteration (`major * 10000 + minor`, so 12.77 → 120077) and `versionName` is the iteration, so the system installer accepts the update over the previous build.
- **Flavor-aware asset pick** — an all-ABI install downloads the `-allabis` APK; a 32-bit TV install downloads the smaller `armeabi-v7a` APK. If only one APK is attached, that one is used.
- **Streamed download** — the APK is fetched with a 64 KB buffer into `cacheDir/updates` with live percentage, a free-space pre-flight and a byte-count check; partial files are deleted on failure or cancellation.
- **System installer hand-off** — `PackageInstaller` opens a full-install session for this package and the system shows its own confirmation. `UpdateInstallReceiver` forwards `STATUS_PENDING_USER_ACTION`, logs success and toasts failures. `REQUEST_INSTALL_PACKAGES` is declared; nothing installs silently.
- **Honest states** — `UP TO DATE · v12.77`, `NO RELEASE` (repository reachable, nothing published yet), `OFFLINE` (network/API failure), and `DOWNLOADING %`.

The updater reads only the latest non-draft, non-prerelease entry, so the release must be published and marked **Latest** with both APKs attached.

> **First update:** builds before 12.77 have no updater. Install 12.77 once by hand (`adb install -r Cinema111126v12.77.apk` or the all-ABI build); later releases can then sync from inside the app.

## Verified on 12.77

- `UpdaterTest` passes: tag parsing (`v` prefix, suffixes), numeric comparison (`12.100 > 12.99`, `12.7 == 12.70`), all-ABI/ARM asset selection and single-asset fallback.
- The all-ABI release installs over v12.76 on the API 37 landscape emulator as `versionCode 120077`, `versionName 12.77`, and launches.
- The startup check runs on schedule; logcat records `StreamLine:update: check failed: No published release on GitHub yet` while the repository has no published release, without disturbing the UI.
- **Settings → About** renders the **Updates** group with the toggle on, the status line `No release published yet · publish one at github.com/M3UCurator/StreamLine-Cinema/releases`, and **CHECK NOW**; tapping it issues a second request (two `check failed` entries in logcat). The **Application** group reads `v12.77`.
- A repository with no release previously produced an `OFFLINE` chip; the state is now split so a missing release is informational, while real network failures keep the offline styling.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema111126v12.77.apk` | `armeabi-v7a` (TV boxes) | 76,253,712 bytes | `c1c62832b1cd9b13d6e195466863876b7672a9947edbf89d281b3eff4df05b22` |
| `Cinema111126v12.77-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,452,353 bytes | `aa33ef5c7048d8419694d7cc55c1b26b965dd4ca7fa334813738d1f5cb41ba92` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema111126v12.77.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.76 (previous)

## New in 12.76 — Hardware & display

Settings → **Playback** → **Hardware & display** adds three switches for boxes and panels that need help with frame parsing, refresh-rate matching and background limits:

| Switch | What it does |
| --- | --- |
| **Amlogic fix (tunneling)** | Forces the direct MediaCodec path for streams that do not follow the standard MediaCodec specs. |
| **Auto frame rate matching (AFR)** | Switches the display to the stream's rate (23.976 / 24 / 50 / 60 Hz) and restores the previous mode when playback ends. |
| **Unrestricted battery usage** | Opens the system Doze/battery exemption for the player so background limits cannot cut sockets or clear buffers. |

The fixes reach every engine, not only the built-in player:

| Engine | Amlogic fix | AFR |
| --- | --- | --- |
| Built-in (Media3 / ExoPlayer) | `forceEnableMediaCodecAsynchronousQueueing()` + `setTunnelingEnabled(true)` | `preferredDisplayModeId` from `ExoPlayer.videoFormat.frameRate` |
| VLC (libVLC 3.7.7) | `--avcodec-hw=mediacodec --mediacodec-dr` | display mode matched from the published video track |
| MPV (libmpv) | `hwdec=mediacodec` | display mode matched from the published video track |

AFR only considers display modes of the same resolution as the current one and tolerates ±0.75 Hz, so a 60.00 Hz panel is not switched to 59.94 Hz for a 23.976 Hz file. The previous mode is restored when playback ends.

The battery row is live: it reads `PowerManager.isIgnoringBatteryOptimizations` on resume, flips the system dialog on and off, and reports `No battery panel on this device · wake locks still hold` where no panel exists.

## Verified on 12.76

- VLC with the Amlogic fix logged `StreamLine:engine: vlc caching=1500ms amlogic=true hw=mediacodec+mediacodec-dr`.
- MPV with the Amlogic fix logged `mpv cache-secs=10 amlogic=true hwdec=mediacodec` and, during playback, `StreamLine:afr: 60.00 Hz source · 60.00 Hz display kept (1 modes)`.
- The battery switch followed the system exemption state across a force-stop, and all three switches kept their values.
- On the test emulator the panel exposes a single 60 Hz mode, so an actual refresh-rate change could not be shown; with the Amlogic fix on, VLC's forced `mediacodec` path produced no video on the emulator's graphics stack, so AFR was verified on MPV. Both behaviours are expected on that hardware and were exercised on real decoder paths where possible.

## Application highlights

- **Three playback engines** — Media3, VLC and MPV, selectable per device, with external-player hand-off through the system chooser.
- **IPTV** — M3U/M3U8 and XSPF playlists, XMLTV EPG, a TV guide overlay, channel health memory, group filters and per-channel favourites.
- **Stremio addons** — catalogs, stream resolution, a source picker with provider grouping and quality/stream badges, and online subtitles.
- **TMDB discovery** — Playroom search, genre browsing, recommendations, locale and origin-country preferences, plus MDBList and JSON imports.
- **Profiles and Kids Mode** — per-profile libraries and progress, parent-approved whitelists, PIN-gated restricted actions.
- **Playback hardening** — HLS loopback proxy, redirect-chain resolution, DRM (ClearKey/Widevine through Media3), buffering tiers, weak-network preset, stall watchdog and automatic mirror failover.

## Assets (12.76)

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema111126v12.76.apk` | `armeabi-v7a` (TV boxes) | 76,237,257 bytes | `3d5c119ed00797002acdef562e99c40275b801b352e09c3019a5f18f0a11bc07` |
| `Cinema111126v12.76-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,435,905 bytes | `4029bb3d9e7bcf7fc5b4cd0c63c6e7f3d3c24c9d34372cef004d741b22b57d65` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. 12.76 has no in-app updater; updating from it is a manual download.
