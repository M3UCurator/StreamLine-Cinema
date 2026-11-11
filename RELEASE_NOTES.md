# StreamLine Cinema v12.77

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
