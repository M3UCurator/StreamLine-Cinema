# StreamLine Cinema v12.76

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

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

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema111126v12.76.apk` | `armeabi-v7a` (TV boxes) | 76,237,257 bytes | `3d5c119ed00797002acdef562e99c40275b801b352e09c3019a5f18f0a11bc07` |
| `Cinema111126v12.76-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,435,905 bytes | `4029bb3d9e7bcf7fc5b4cd0c63c6e7f3d3c24c9d34372cef004d741b22b57d65` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema111126v12.76.apk
```

To update, install the newer APK from Releases over the existing app — settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one. There is no in-app updater yet; updates are manual downloads from the Releases page.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources
