# Musicbox addon contract

Musicbox is a client of the Stremio addon you already register under **Settings → Addons**. It adds no second plugin format: a music addon is an ordinary manifest that publishes `music` catalogs and answers `/stream/music/{id}.json` with a direct audio URL. This document is the contract the app relies on, plus a reference resolver you can host yourself.

## 1. Manifest

```json
{
  "id": "org.example.music",
  "name": "Example Music",
  "version": "1.0.0",
  "types": ["music"],
  "resources": ["catalog", "stream"],
  "catalogs": [
    {
      "type": "music",
      "id": "charts",
      "name": "Charts",
      "extra": [
        { "name": "genre", "options": ["Pop", "Jazz", "Classical"] },
        { "name": "search" }
      ]
    }
  ]
}
```

- The MUSIC tab appears when at least one enabled addon declares a catalog whose `type` is `music` (case-insensitive) **or** declares `contentType: "music"` in its manifest. Name heuristics are deliberately absent — a live addon's channel called "Music" is still a live catalog.
- A manifest that declares `contentType: "music"` may publish its index under other catalog types. Its `track`, `album`, `artist` and `playlist` catalogs join the tab, as does any catalog with a `search` group. A music-shaped catalog that returns no rows (a sponsor slot, an empty playlist) is probed once at load and dropped, so it does not become a dead chip.
- No music catalog means no tab; the tab's empty state points at Settings → Addons. A manifest with no `catalogs` at all — for example a search/stream helper addon such as LastWave (`resources: ["search", "stream"]`) — cannot fill the tab, because there is nothing to list and its `/catalog` paths answer 404.
- Extras with `options` become one-tap chips and are sent as catalog path segments. `skip` is paging and never becomes a chip.
- A catalog answers free-text search only if it declares an extra named `search`. Musicbox queries every such catalog, merges the results, de-duplicates by addon URL + id and keeps the first 200.
- Kids profiles never see the tab. Music catalogs carry no rating metadata to filter on, so the tab is removed rather than filtered, and there is no PIN route into it.

## 2. Catalog endpoint

```
GET {base}/catalog/music/{catalogId}.json
GET {base}/catalog/music/{catalogId}/genre=Jazz&search=blue%20note.json
GET {base}/catalog/music/{catalogId}/genre=Jazz&skip=100.json
```

Extra values are percent-encoded (spaces stay `%20`); `skip` is appended last. The body is the usual `{"metas":[...]}`. Musicbox also accepts the `{"items":[...]}` shape some music manifests use, with the same fields plus `durationMs` and `artworkURL`. At most 500 entries per response are read; duplicates by `id` inside one catalog are dropped.

Fields Musicbox reads from each entry:

| Field | Meaning |
| --- | --- |
| `id` | **Required.** Unique within the addon. Doubles as the stream id and, when it is a YouTube video id, the SponsorBlock lookup id. |
| `name` / `title` | **Required.** Displayed as the track title. |
| `artist` | String, or `artists` (array of strings or `{"name":…}` objects), or `performer` as a fallback. |
| `album` | Album title shown on the now-playing card. |
| `duration` | Seconds. A bare number is seconds; `"3:45"`, `"225"` and `"225s"` are also accepted. |
| `durationMs` | Milliseconds, used first when present (the `items` shape). |
| `runtime` | Fallback duration when `duration` is absent. Follows the Stremio convention: a bare number is **minutes**. |
| `poster` / `background` / `logo` / `artworkURL` | Artwork. The first value that starts with `http` is used. |

```json
{
  "metas": [
    {
      "id": "dQw4w9WgXcQ",
      "name": "Example Track",
      "artist": ["Example Artist"],
      "album": "Example Album",
      "duration": 213,
      "poster": "https://cdn.example/covers/example.jpg"
    }
  ]
}
```

Unknown fields are ignored. An entry without `id` and a title is discarded.

## 3. Stream endpoint

```
GET {base}/stream/music/{id}.json
```

```json
{
  "streams": [
    {
      "url": "https://cdn.example/audio/example.m4a",
      "name": "Example Music · 256 kbps",
      "title": "Example Track — Example Artist",
      "behaviorHints": {
        "proxyHeaders": { "request": { "User-Agent": "ExampleMusic/1.0", "Referer": "https://example.com/" } }
      }
    }
  ]
}
```

- Only `url` entries are playable. The first stream whose `url` starts with `http` wins; `ytId`, `infoHash` and `externalUrl`-only entries are skipped, so a music addon must resolve to a direct HTTP(S) audio file or manifest.
- A row in a `music` catalog streams under its declared type with the id as-is. Rows that a music manifest labels `track` in a non-`music` catalog are requested from the addon's video namespace with the manifest's declared `idPrefixes` prefix — PencariMusic row `nIU2gC` resolves at `/stream/movie/pm_nIU2gC.json` — and if that request yields no http URL, Musicbox retries once with the row's own type.
- Headers come from `behaviorHints.proxyHeaders.request` (string values or `{"value": …}`). The addon's `User-Agent` always wins; a browser UA is added only when none is supplied. The legacy `url|User-Agent=…&Referer=…` pipe form is also accepted.
- URLs are resolved lazily, per track, just before that track is read — never for the whole queue. Signing/expiry is therefore fine: return a fresh URL on every request.
- A non-2xx answer or timeout from `/stream` surfaces as the playback error. An empty list, or no http URL, reads "No playable stream for {title}."

## 4. YouTube-style ids and SponsorBlock

If a meta `id` is a YouTube video id — bare 11 characters (`[A-Za-z0-9_-]`), or prefixed `yt:` / `youtube:` — Musicbox asks SponsorBlock whether the video has community-marked non-music sections. Only the `music_offtopic` category and `skip` actions are requested, through the privacy-prefix API: the device sends the first 16 bits of `SHA-256(videoId)`, never the id or any watch history. When **Settings → Musicbox → Skip non-music sections** is on (default), playback seeks past those ranges; the song itself is never cut.

Ids that are not YouTube ids never trigger a lookup. Everything else in the tab works the same for any id format.

## 5. Playback, caching and profiles

- Playback is audio-only Media3 behind a `MediaSessionService`: notification, media keys and Bluetooth controls, and the queue's next/previous. Video tracks are disabled for music.
- **Audio cache** (`Settings → Musicbox → Audio cache`, OFF / 256 MB / 512 MB / 1 GB, LRU): cached bytes are keyed by `{addonUrl}|{id}`, not by the resolved URL, so a track keeps playing from disk after the source link rotates. It is a replay cache, not a download — entries are evicted oldest-first and a track that falls out is resolved again. A budget change applies the next time Musicbox starts. The Storage panel measures and clears it separately.
- Recents (50), the last queue and the playhead are stored per profile in the app's private files, so a cache wipe does not lose them. Switching profile ends the music session, because the queue belongs to the profile that started it.
- Playback options that apply to video (quality cap, size filter, external player, transport policy) do not apply to music streams.

## 6. Reference self-hosted resolver

The app deliberately ships no yt-dlp, Python or JavaScript runtime. On-device extraction needs a JS runtime for YouTube's signature cipher, adds a per-ABI payload, and changes weekly; the resolution risk belongs with the addon operator, on infrastructure they control. Run the reference below on a machine you own and register `https://your-host/manifest.json` in Settings → Addons.

**Content rights are yours.** YouTube's terms forbid stream extraction, so use this only with sources you are authorized to serve; many operators point the same contract at their own licensed catalogue instead (replace `streamFor` with a lookup of your own files).

```js
// Reference music addon: one file, no npm dependencies, yt-dlp on PATH.
const http = require("node:http");
const { execFile } = require("node:child_process");

const MANIFEST = {
  id: "org.example.music-ref",
  name: "Reference Music",
  version: "1.0.0",
  types: ["music"],
  resources: ["catalog", "stream"],
  catalogs: [{ type: "music", id: "library", name: "Library" }]
};

// Replace with tracks you may stream. `id` must be unique within the addon.
const TRACKS = [{
  id: "dQw4w9WgXcQ",
  name: "Example Track",
  artist: ["Example Artist"],
  album: "Example Album",
  duration: 213
}];

function streamFor(id) {
  return new Promise((resolve, reject) => {
    // -g prints the direct media URL; -f bestaudio keeps it audio-only.
    execFile(
      "yt-dlp",
      ["-g", "-f", "bestaudio", "--no-playlist",
       `https://www.youtube.com/watch?v=${id}`],
      { timeout: 15000 },
      (err, stdout) => err ? reject(err) : resolve(stdout.trim().split("\n")[0])
    );
  });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const send = (body) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
  };
  if (url.pathname === "/manifest.json") return send(MANIFEST);
  if (url.pathname.startsWith("/catalog/music/")) return send({ metas: TRACKS });
  const match = url.pathname.match(/^\/stream\/music\/(.+)\.json$/);
  if (match) {
    try {
      const url = await streamFor(decodeURIComponent(match[1]));
      return send({ streams: [{ url, name: MANIFEST.name }] });
    } catch {
      return send({ streams: [] });
    }
  }
  res.writeHead(404);
  res.end();
}).listen(process.env.PORT || 7001);
```

Operational notes:

- The direct URL from `yt-dlp -g` is short-lived (hours, often minutes for some formats). That is expected: Musicbox re-resolves on each play and caches only the bytes it already read.
- `-g` returns one URL per request; with `bestaudio` there is normally a single line. Keep `--no-playlist` so a video id cannot expand into an album.
- If the resolver needs headers or cookies, return them through `behaviorHints.proxyHeaders.request` rather than baking them into the URL.
- A resolver that is down or rate-limited shows the addon's own failure in the tab; the other addons stay usable.
