# StreamLine Cinema v12.88

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.88 — One OPEN WITH window, one player at a time

Handing a stream to an external player no longer closes the app player before the transfer happens. **OPEN WITH** now opens a focused window that lists the ways out of the app, and the app player stays alive — paused at the same position — until a chosen player is actually on screen.

- **The window** — titled *OPEN WITH ANDROID PLAYER* with the stream name in the subtitle, it opens on the system program chooser (`ACTION_VIEW` badge, the default row) followed by every detected player on the device: JustPlayer (*Pure ExoPlayer v2 / Media3*), VLC (*LibVLC hardware decoder*), MX Player (*HW+ codec engine*), mpv (*GPU-accelerated libmpv*) and any other package the system reports, each with its package id and a monogram tile. A read-only **Raw stream URL** field with **COPY** serves players that take a pasted link, and **DONE (BACK TO PLAYER)** leaves the window without launching anything.
- **The player is not torn down** — opening the window pauses playback and keeps the same `Player` instance; no second instance is created, so the decoder, buffered queue and playhead all survive. **DONE**, BACK and ✕ resume exactly where the film was if it was playing before.
- **Only one player persists after the transfer** — picking a named player launches it with `ACTION_VIEW` (package-targeted, stream URL, `headers` extra) and keeps the app player alive until that app has actually covered the screen (`ON_STOP`); the app player then finishes, leaving the external player as the only instance running. The system chooser is not a player: choosing it never finishes the app player.
- **Switching back is a first-class option** — if the hand-off does not stick (the chosen app declines, or you come straight back), returning to the app resumes the same paused instance and the player shows *Back in the app player*. Before launching, **DONE (BACK TO PLAYER)** is the same escape. A launch that no app accepts reports *Nothing on this device accepted the stream. Install a video player or copy the URL.* inside the window, and a hand-off that never reaches the foreground reports *Hand-off to … did not start · still in the app player* after six seconds.
- **Headers and UA are unchanged** — the hand-off still carries the browser UA preset, addon proxy headers and pipe-syntax headers as an alternating key/value `headers` String[] extra.

## Verified on 12.88

- `./gradlew assembleRelease -PallAbis=true -PcinemaIteration=12.88` and `./gradlew assembleRelease -PcinemaIteration=12.88` — both BUILD SUCCESSFUL; the all-ABI release installs as `versionCode 120088` / `versionName 12.88` over 12.87 with app data kept.
- On emulator-5554, **⋮ → Play in external player** opens the *OPEN WITH ANDROID PLAYER* window over the paused app player: the system chooser row is highlighted with its `ACTION_VIEW` badge, Just Player and Photos are listed with package ids, the raw URL field copies to the clipboard via **COPY** (label flips to *COPIED*), and **DONE (BACK TO PLAYER)** is fully on screen at 411 dp tall — only the row list scrolls.
- **DONE** returns to the same paused player and resumes where it was. Choosing Just Player launches `act=VIEW … pkg=com.brouken.player` from `launchedFromPackage=com.streamline.cinema`, the app player finishes only after Just Player covers the screen, and only the Just Player task remains — BACK then lands on the app's Settings pane, never a second player.
- Choosing the default system chooser opens `com.android.intentresolver/.ChooserActivityLauncher` over the still-paused app player; BACK returns to the same `PlayerActivity` and the film keeps playing — one player instance throughout.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema131126v12.88.apk` | `armeabi-v7a` (TV boxes) | 76,302,852 bytes | `a41b7d49b205768ec85f5fc30fc5c3af7112d4761f6368ecd918b00ab2a1c64a` |
| `Cinema131126v12.88-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,501,496 bytes | `44e1b2882820fb856f08129e7d89aa9ecc2d4eef585ba4201ac904f3b26cc6f8` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema131126v12.88.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.87 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.87 — Kids and adult libraries are separate catalogs

A title approved for Kids no longer exists in the adult library, its favorites, Continue Watching or its discovery rails. Approval is the split.

- **Approval means ownership** — `CatalogItem.visibleTo(profile, approved)` decides every list: a Kids profile sees exactly the parent-approved keys, and every other profile sees the rest of the hall (still filtered by its maturity rank). Approving a title — from **Settings → Parental → MANAGE KIDS LIBRARY** or **ADD TO KIDS** on a detail page — moves it out of the adult Movies / TV Shows libraries, Favorites and Continue Watching and into the kids library in the same moment. Revoking it moves it back.
- **Discovery is split too** — Home's hero, Top 10, Because-you-watched, New Releases and genre rails, the local Playroom fallback, the trending mix, `loadRelated` and the remote "because" results all run through the same predicate. An approved kids title can never appear in, or fuel, an adult recommendation, so letting a child watch something no longer skews the grown-ups' home page.
- **Kids have no acquisition surface** — the top-bar **SEARCH** was already hidden for Kids; the Movies / TV Shows toolbars now stop rendering **IMPORT** for them as well. An empty kids library explains *A parent can add approved movies and series to this profile.* with no action button. Playroom and Import stay hidden routes, PIN-gated behind the adult side.
- **Approval only where it can work** — **ADD TO LIBRARY** is hidden for a title that is already approved for Kids (approval already owns it), and **ADD TO KIDS** appears only for adults and only for hall titles; approving a TMDB result imports it first, as before. The curated title disappearing from the adult page is the immediate feedback.
- **Curation still sees everything** — **MANAGE KIDS LIBRARY** lists the unfiltered hall, so a parent can revoke an approval; the header now reads *N approved · these titles and channels live only in Kids profiles*, and **CLEAR APPROVED** explains that it moves every approved title back to the adult library.
- **No migration** — the split is derived from the persisted approved set, so titles approved before this build leave the adult surfaces on first launch with no library rewrite.

## Verified on 12.87

- `./gradlew testDebugUnitTest assembleRelease -PallAbis=true` — BUILD SUCCESSFUL. New `LibrarySplitTest` (5 tests): an approved movie is visible to Kids and hidden from adult and teen profiles; an unapproved movie stays out of the Kids library but remains in the adult one; un-approving returns a title to the adult library; a Teen profile still filters an R rating; catalog keys stay stable through a metadata refresh so approval survives re-enrichment.
- On emulator-5554 the all-ABI release installs as `versionCode 120087` / `versionName 12.87` over 12.86 with app data kept. A Kids profile created from the gate shows **Kids Mode** with *No approved titles yet…*, the top bar has no **SEARCH**, and the Movies tab shows *A parent can add approved movies and series to this profile.* with no **IMPORT** button; the adult Movies toolbar still shows **IMPORT**, and the profile gate reports `PROFILES LOCKED · rating lock uses your parental PIN` while Kids is active.
- The emulator had no seeded catalog at verification time, so the adult-side exclusion was exercised through the unit suite rather than a live poster grid.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema131126v12.87.apk` | `armeabi-v7a` (TV boxes) | 76,286,467 bytes | `0c45f0a6d1e31d474d0800de29b7d9217045b16526dcf4fe3172422563a6dcab` |
| `Cinema131126v12.87-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,724 bytes | `506ea9e3d7669e1b33514fb34a03bb7799b75e775270883d58c17618746ab2f9` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema131126v12.87.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.86 (previous)

Leanback Android TV client for TMDB discovery, Stremio addons and IPTV playlists, built for 32-bit TV boxes.

## New in 12.86 — Pipe headers that never fail, deep buffers on demand

Header injection now survives the malformed suffixes real playlists contain, and a film or episode can be told to buffer 50 s ahead instead of the tuned default.

- **Fail-free pipe parser** — `https://cdn/stream.m3u8|User-Agent=…&Referer=…` is parsed without throwing and without ever failing the request. `&` and `|` both separate fields (whitespace allowed around separators and `=`), values may be quoted, `%XX` runs are decoded while a literal `+` is preserved, and names are matched case-insensitively so `User-Agent`/`user-agent` collapse to one header. Invalid names, non-printable values and HTTP framing headers (`Host`, `Connection`, `Keep-Alive`, `Content-Length`, `Transfer-Encoding`, `Trailer`, `TE`, …) are dropped instead of reaching OkHttp, where one bad name fails the whole load. A pipe that yields no valid header stays part of the URL, so a URL that legitimately contains `|` still works.
- **One parser for every surface** — live channel tuning, film and episode playback, IPTV playlist and guide downloads, Stremio addon sources, the native HLS proxy, the source probe and the external `ACTION_VIEW` hand-off all go through the same `parse`/`mergeHeaders`, so a header that works on one path works on all of them. `withHeaders` rebuilds the syntax for persisted sources and round-trips through `parse`.
- **High-capacity buffering (MP4 / MKV)** — Settings → Playback → **Bandwidth & buffering** gains a toggle that pins the progressive video window to **15 s fill · 50 s forward · 2.5 s start · 5 s re-buffer**, the deep profile meant to absorb a line that dips mid-film. It wins over the video buffer tier for MP4/MKV only: adaptive HLS/DASH keeps its segment-sized window (ABR gains nothing from a 50 s queue), and low-RAM boxes keep enforcing their byte budget, so the wider window can never grow past the RAM the device reports. Turning it on also deepens the VLC/MPV caches to 5 s / 30 s.
- **Visible state, persisted choice** — the group hint reads `HIGH CAPACITY`, the **MP4 / MKV load control** row prints the resolved `min 15 s · max 50 s · start 2.5 s · re-buffer 5 s`, the flag rides along in the settings backup (`highCapacityBuffer`), and the player logs the active profile on every tune.

## Verified on 12.86

- A loopback fixture (port 8099) logged the headers it actually received. A progressive MP4 played from `http://10.0.2.2:8099/oceans.mp4|User-Agent=PipeTest/9.9&Referer=https://pipe.test/` arrived as `ua=PipeTest/9.9 referer=https://pipe.test/`, and an HLS master at `…/live.m3u8|User-Agent=LivePipe/1.0&Referer=https://live.test/` arrived as `ua=LivePipe/1.0 referer=https://live.test/`, with playback continuing through the remote variant.
- The tolerant syntax `…oceans.mp4| User-Agent = %22Quoted%20UA/3.0%22 & Host = evil.test & Referer=https://pct.test/a+b` reached the server as `ua=Quoted UA/3.0 referer=https://pct.test/a+b`: quoting and `%XX` decoded, `+` kept literal, the reserved `Host` header dropped, and the request succeeded.
- With the toggle on, an MP4 tuned with `Load control · progressive · 15000/50000 ms · start 2500 ms · re-buffer 5000 ms · back 120000 ms · high capacity`, and Settings showed **MP4 / MKV load control: min 15 s · max 50 s · start 2.5 s · re-buffer 5 s** with **VLC / MPV cache: 30 s MPV · 5 s VLC**. Turning it off returned the progressive row to the device's tier window, and an HLS tune stayed `Load control · adaptive · 8000/8000 ms …` with no high-capacity suffix.
- `StreamSourceTest` (16 tests, seven new: separators, quoted and percent-encoded values, invalid/reserved names, case-insensitive names, headerless suffixes, merge sanitising, round-trip) and `BufferTuningTest` (8 tests, including the pinned window on every tier and low-RAM, plus the deepened native caches) pass with the rest of the unit suite.
- The all-ABI release installs as `versionCode 120086` / `versionName 12.86` and boots to the profile picker.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema131126v12.86.apk` | `armeabi-v7a` (TV boxes) | 76,270,101 bytes | `f580d5aec02ed63640c4956b9555edb7b5784a8aa3c2a664537a50715e84225e` |
| `Cinema131126v12.86-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,743 bytes | `7d1c37f116054b287bf66efbdd44d2c5e41c89d6de7a1d9edd6439f848439ae8` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema131126v12.86.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.85 (previous)

## New in 12.85 — See the buffer, start with captions off

The player's timeline now shows what has actually been downloaded, and captions no longer switch themselves on for a profile that asked for a subtitle language.

- **Buffer band on the seek bar** — the track now carries three layers: the dim rail, a solid grey band for the span the player has already loaded, and the gold playhead on top. `Player.bufferedPosition` is read on the same 1 s tick as the playhead, so the band grows and shrinks with the real download and shows how much headroom is left before a slow source stalls; it is clamped to start no earlier than the playhead, so it can never paint behind the gold. On a fully loaded file the band reaches the right edge while the gold stays with the viewer.
- **Native engines draw no band** — VLC and MPV publish only a playhead (buffered = position), so the band simply has no width there and the bar looks exactly as before. Live is untouched: it keeps the guide-based programme timeline, where a download buffer has no meaning.
- **Captions start off everywhere** — the text track type is disabled (`setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`) from the first frame for every title, whatever the profile, so Media3 no longer auto-selects the first embedded track and burns it over the opening shot. The dock control reads `Subtitles · Off` until a track is chosen, and enabling captions later still works from the same panel.
- **The profile still decides the language** — a profile's subtitle language is applied as `setPreferredTextLanguage` with `setSelectUndeterminedTextLanguage(false)`, so the first manual caption switch lands on the chosen language (or the audio language for "auto") instead of the first track in the file. "Off" in the profile no longer has to fight the player's own default.
- **One less race in track setup** — the per-track callback now only re-applies the audio override; it no longer flips the text track on when a profile names a language, which is what used to re-enable captions after the player was built.

## Verified on 12.85

- On the installed build, a 10:34 HLS title paused at 00:59 showed a solid grey band running from the gold playhead to the loaded edge, with the dim rail continuing past it to the right; the band tracked `bufferedPosition` across the 1 s tick and disappeared when the playhead reached the end of the loaded span.
- The same session's dock showed the captions button focused with the hint `Subtitles · Off` and no burned-in text over the opening frames, while the rest of the OSD (transport, display `Fit`, sources, `AUTO` decoder, `2.0`, more) stayed unchanged.
- No band is drawn without a duration (live), and the seek bar's scrub, ±10 s and focus behaviour are untouched.
- `compileDebugKotlin` and `assembleDebug -PallAbis=true` pass, and the release build installs as `versionCode 120085` / `versionName 12.85` (`x86_64` on the emulator).

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema131126v12.85.apk` | `armeabi-v7a` (TV boxes) | 76,270,090 bytes | `649ec829720d48e082b388ef3fae796f10c2fb5935b172dd328467e46e8644a2` |
| `Cinema131126v12.85-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,723 bytes | `e77155fe43dd7a0928db561c0975560c41d46132b5d5fe9e0d83bb70552a17d1` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema131126v12.85.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.84 (previous)

## New in 12.84 — Every source reaches the right extractor

Three ways an addon stream used to die before its first frame are fixed in one pass: headers that never reached the player, short links handed over unresolved, and manifests with no file extension left to progressive extraction.

- **One header merge for every path** — pipe headers (`url|Referer=…&User-Agent=…`), `behaviorHints.proxyHeaders.request` and the settings UA merge through a case-insensitive map, so the same set is used by Media3 (`OkHttpDataSource`), the native HLS proxy and the external `ACTION_VIEW` `headers` extra. A source can no longer put both `User-Agent` and `user-agent` on the wire — the duplicate pair is what makes CDNs answer 400 — and an addon-supplied UA wins over the app default.
- **Short links unwrapped before playback** — an addon URL without a media extension walks its 301/302/303/307/308 chain while the source list is built (four sources per addon, so a slow redirect cannot stall the search) and the player gets the final absolute manifest URL. That is what lets a playlist's relative `segment.ts` resolve at the CDN instead of at the shortener, and credentials are still stripped when a hop changes host or drops HTTPS.
- **Explicit stream kind, not a guess** — `StreamUrls.kindOf` classifies each source from its extension, then the response `Content-Type`, then the first 4 KB of the body (`#EXTM3U`, `<MPD`, `<SmoothStreamingMedia`), and the result becomes `MediaItem.setMimeType(APPLICATION_M3U8 / APPLICATION_MPD / APPLICATION_SS)`. A token endpoint with no `.m3u8` suffix now gets the HLS extractor instead of `UnrecognizedInputFormatException`, and the same kind picks the HLS proxy and the external intent's MIME.
- **Live and bare links too** — live channels and direct URLs that skipped the source search run the same resolution at load time, so the fix is not limited to addon VOD results.

## Verified on 12.84

- On the installed build, a fixture addon short link (`/short/hls` → 302 → `/api/token/abc123`) whose token endpoint answers 403 unless the Referer, UA and hint header all arrive loaded through the HLS extractor with no 403. The fixture logged one resolver hit on `/short/hls`, then only `/api/token/abc123` requests, and logcat showed `HlsMediaPeriod` / `HlsSampleStreamWrapper` — the explicit `application/x-mpegURL` and not progressive extraction.
- **OPEN WITH** on the same source opened the chooser with `dat=http://10.0.2.2:8099/api/token/abc123 typ=application/x-mpegURL headers=Referer,https://fixture.test/,User-Agent,FixturePlayer/1.0,X-Fixture-Token,abc` — one UA, pipe and behaviour-hint headers merged, and the resolved URL rather than the short link.
- A pipe-only source (`…|Referer=https://pipe.test/&User-Agent=PipePlayer/2.0`, no behaviour hints) cleared its own header gate on Media3 and handed off with `headers=Referer,https://pipe.test/,User-Agent,PipePlayer/2.0`.
- The fixture serves placeholder TS bytes, so preparation ends with `Stream data is malformed`; that error itself confirms the HLS extractor was selected and the playlist was read with the forwarded headers.
- `StreamSourceTest` (9 tests: kind precedence, prefix sniffing, `needsKindDiscovery`, redirect resolution over a loopback server, pipe-header precedence, unique header pairs, `mimeFor`) and the full unit suite pass.
- The all-ABI release installs as `versionCode 120084` / `versionName 12.84`.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema131126v12.84.apk` | `armeabi-v7a` (TV boxes) | 76,270,082 bytes | `6f80d6547bd9a44044b4e016fb1c9f96ade61b2acfb090e9c232b91a743d9a13` |
| `Cinema131126v12.84-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,726 bytes | `78b60416868e726f9ee4da498389147bd2d93b64104c04c588a232c9061df1b1` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema131126v12.84.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.83 (previous)

## New in 12.83 — Type the PIN

The parental PIN can now be typed on a physical or remote keyboard, not only tapped on the keypad. Digits land as dots, so the PIN is never shown.

- **Direct digits** — 0–9 on the number row or the numpad fill the active PIN field; DEL/Backspace removes the last digit.
- **No IME** — the key events are captured as PIN input, so no soft keyboard or text field opens and nothing is echoed on screen.
- **Every PIN surface** — the shared PIN dialog (Kids Mode lockout, leaving a Kids profile, 18+ gate) and Settings → Parental (current / new / confirm PIN and the adult gate) all take typed digits.
- **Auto-advance** — in Settings the target moves current → new → confirm as each field fills, so changing the PIN is three groups of four digits and SAVE PIN.
- **Focus-proof** — the Parental pane drains typed digits from the activity's key dispatch, so they land even while focus sits on the top bar, and the on-screen keypad keeps working for pointer-only boxes.
- **Scoped** — capture only exists while a PIN surface is showing, so the TMDB key, playlist URL and other text fields keep their normal typing.

## Verified on 12.83

- On the installed all-ABI release, hardware digit key events filled Settings → Parental with dots, no soft keyboard appeared, DEL removed a digit, typing advanced current → new → confirm on its own, and SAVE PIN stored the change (`CUSTOM PIN`); the same digits landed with focus on the top bar.
- The shared Kids lockout dialog (`Settings are locked`) took four typed digits, enabled UNLOCK and accepted the saved PIN; the typed current PIN also removed it afterwards (`Parental PIN removed · 18+ hidden`).
- The all-ABI release installs as `versionCode 120083` / `versionName 12.83`.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema121126v12.83.apk` | `armeabi-v7a` (TV boxes) | 76,270,088 bytes | `69431491c691567475b0133229222ab5ba73d97f9e710925fdea79039deb5d23` |
| `Cinema121126v12.83-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,730 bytes | `dc60a4fec81463c3df4f9d79f8169f6ccf3a1ef40dd5ea98b091bb9f991aa07e` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema121126v12.83.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.82 (previous)

## New in 12.82 — Accordion playlist rows

Settings → IPTV & EPG used to give every playlist a fully expanded card, so a second playlist pushed the rest (and its own EDIT button) below the pane. Each playlist is now a compact row that expands on demand.

- **One row per playlist** — the header shows the name, `M3U / M3U8 · N channels · EPG linked / no EPG`, an ACTIVE or PAUSED pill and a ▾/▴ chevron. Every saved playlist stays visible and reachable no matter how many are loaded.
- **Accordion, not a wall** — opening one row closes the other, so the pane never grows past a single open panel.
- **Details on expand** — the panel holds the playlist address, the `Parsed · N channels · N malformed` report, the EPG SHIFT chips (`AUTO -1H`, `-1H`, `-30M`, `+30M`, `+1H`, plus `AUTO` when a manual shift is active) or `No EPG guide linked · add one with EDIT`, and the EDIT · RELOAD · PAUSE/RESUME · REMOVE actions.
- **EDIT unchanged** — the same edit dialog (name, playlist URL, guide override) opens from the expanded row, and closing it returns focus to that row's EDIT button.

## Verified on 12.82

- On the installed all-ABI release, two host fixtures (`10.0.2.2:8099/playlist.m3u` and `/playlist2.m3u`) render as two compact rows: `SecondList · M3U / M3U8 · 3 channels · no EPG` and `Test Provider · M3U / M3U8 · 6 channels · EPG linked`, each with its ACTIVE pill and a ▾ chevron.
- Expanding `SecondList` shows its URL, `Parsed · 3 channels`, `No EPG guide linked · add one with EDIT` and EDIT · RELOAD · PAUSE · REMOVE, while `Test Provider` collapses to its header.
- Expanding `Test Provider` shows `Parsed · 6 channels · 1 malformed` and `EPG SHIFT AUTO -1H` with the stepper chips; EDIT opens the edit dialog with the name, URL and guide populated, and CANCEL restores focus to that row.
- The all-ABI release installs as `versionCode 120082` / `versionName 12.82`.

## Assets

| File | ABI | Size | SHA-256 |
| --- | --- | --- | --- |
| `Cinema121126v12.82.apk` | `armeabi-v7a` (TV boxes) | 76,270,077 bytes | `6fdc74a8cbcfdac56a0306a4329c2554a4c1e029200ca40d11e5bb9c612c5a3e` |
| `Cinema121126v12.82-allabis.apk` | all ABIs (arm64-v8a, x86, x86_64) | 329,468,725 bytes | `d8163c1fa4bf7ef55856f25547424042d74a0044eaea8b56899728381fa50048` |

Install the `armeabi-v7a` build on Android TV boxes. The all-ABI build is for emulators and 64-bit devices that cannot install the 32-bit package. The in-app updater picks the matching file automatically.

## Install and update

```bash
adb connect <tv-box-ip>:5555
adb install -r Cinema121126v12.82.apk
```

To update, install the newer APK from Releases over the existing app, or use **Settings → About → DOWNLOAD & INSTALL** on 12.77 and later. Settings, library, profiles, playlists and watch progress are kept. Builds are signed with the Android debug key, so always update from this repository's releases; a build signed with a different key cannot install over this one.

## Requirements

- Android 6.0 (API 23) or newer, landscape TV or touchscreen device
- D-pad remote supported everywhere; no touchscreen required
- A TMDB key/bearer token or proxy, or your own catalogs/addons/playlists — the app ships no content sources

---

# StreamLine Cinema v12.81 (previous)

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
