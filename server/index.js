'use strict';

/**
 * StreamLine Cinema proxy layer.
 *
 * The Android client can talk to TMDB directly with a v3 key / v4 bearer token,
 * but every endpoint below can also be used through this server. Configure the
 * base URL (for example http://192.168.1.20:8787) in Settings -> TMDB Access.
 *
 * TMDB key resolution order: X-TMDB-Key header -> ?key= -> TMDB_API_KEY env.
 */

const express = require('express');
const cors = require('cors');

const app = express();
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '2mb' }));

const PORT = Number(process.env.PORT || 8787);
const TMDB_BASE = 'https://api.themoviedb.org/3';
const IMAGE_BASE = 'https://image.tmdb.org/t/p';
const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const TMDB_CATEGORIES = {
  'Popular Movies': { path: '/movie/popular', mediaType: 'movie' },
  'Top Rated Movies': { path: '/movie/top_rated', mediaType: 'movie' },
  'Upcoming Movies': { path: '/movie/upcoming', mediaType: 'movie' },
  'Now Playing Movies': { path: '/movie/now_playing', mediaType: 'movie' },
  'Popular TV Shows': { path: '/tv/popular', mediaType: 'tv' },
  'Top Rated TV Shows': { path: '/tv/top_rated', mediaType: 'tv' },
  'TV Shows Airing Today': { path: '/tv/airing_today', mediaType: 'tv' }
};

const wrap = (handler) => (req, res) => {
  Promise.resolve(handler(req, res)).catch((error) => {
    const status = error.status && error.status >= 400 ? error.status : 502;
    res.status(status).json({ error: error.message || 'Proxy request failed' });
  });
};

function fail(res, status, message) {
  res.status(status).json({ error: message });
}

function accessKey(req) {
  return String(
    req.get('x-tmdb-key') || req.query.key || process.env.TMDB_API_KEY || ''
  ).trim();
}

function absoluteImage(path, size = 'w500') {
  if (!path) return null;
  const value = String(path).trim();
  if (!value) return null;
  if (/^https?:\/\//i.test(value)) return value;
  return `${IMAGE_BASE}/${size}${value.startsWith('/') ? '' : '/'}${value}`;
}

function toCatalogItem(media, fallbackType) {
  if (!media || typeof media !== 'object') return null;
  const id = Number(media.id);
  if (!Number.isFinite(id) || id <= 0) return null;

  const looksLikeTv =
    media.media_type === 'tv' ||
    (media.media_type !== 'movie' && Boolean(media.first_air_date || media.name));
  const mediaType = fallbackType === 'tv'
    ? 'tv'
    : fallbackType === 'movie'
      ? 'movie'
      : looksLikeTv
        ? 'tv'
        : 'movie';

  const date = media.release_date || media.first_air_date || '';
  return {
    tmdb_id: id,
    media_type: mediaType,
    type: mediaType,
    title: media.title || media.name || media.original_title || media.original_name || 'Untitled',
    year: date ? String(date).slice(0, 4) : null,
    overview: media.overview || '',
    poster_path: absoluteImage(media.poster_path, 'w500'),
    backdrop_path: absoluteImage(media.backdrop_path, 'w1280'),
    rating: typeof media.vote_average === 'number' ? media.vote_average : null,
    vote_count: typeof media.vote_count === 'number' ? media.vote_count : null,
    genre_ids: Array.isArray(media.genre_ids) ? media.genre_ids : [],
    popularity: media.popularity ?? null
  };
}

async function tmdbGet(path, params, key) {
  if (!key) {
    throw Object.assign(
      new Error('Missing TMDB key. Send the X-TMDB-Key header or set TMDB_API_KEY.'),
      { status: 400 }
    );
  }
  const url = new URL(TMDB_BASE + (path.startsWith('/') ? path : `/${path}`));
  Object.entries(params || {}).forEach(([name, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(name, String(value));
    }
  });

  const headers = { accept: 'application/json' };
  if (key.startsWith('eyJ')) {
    headers.authorization = `Bearer ${key}`;
  } else {
    url.searchParams.set('api_key', key);
  }

  const response = await fetch(url, { headers, signal: AbortSignal.timeout(15000) });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = { raw: text };
  }
  if (!response.ok) {
    throw Object.assign(
      new Error(body.status_message || `TMDB responded ${response.status}`),
      { status: response.status }
    );
  }
  return body;
}

function stripTags(html) {
  return String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ');
}

function parseMdbList(html) {
  const items = [];
  const seen = new Set();

  const anchorRegex =
    /<a\b[^>]*href=["']([^"']*themoviedb\.org\/(movie|tv)\/(\d+)[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = anchorRegex.exec(html)) !== null) {
    const [, , kind, rawId, inner] = match;
    const id = Number(rawId);
    if (!Number.isFinite(id) || id <= 0) continue;
    const key = `${kind}-${id}`;
    if (seen.has(key)) continue;
    seen.add(key);

    const alt = /alt=["']([^"']+)["']/i.exec(inner);
    const imgSrc = /src=["']([^"']+)["']/i.exec(inner);
    const titleAttr = /title=["']([^"']+)["']/i.exec(inner);
    let title = (alt?.[1] || titleAttr?.[1] || stripTags(inner)).replace(/\s+/g, ' ').trim();

    const anchorIndex = match.index || 0;
    const context = stripTags(
      html.slice(Math.max(0, anchorIndex - 1200), Math.min(html.length, anchorIndex + 1200))
    ).replace(/\s+/g, ' ');
    const year = /(?:19|20)\d{2}/.exec(context)?.[0] || null;
    const ratingMatch =
      /(?:★|\brating\b|\bscore\b)[^0-9]{0,10}(\d(?:\.\d)?)/i.exec(context) ||
      /\b(\d\.\d)\s*\/\s*10\b/.exec(context);
    const rating = ratingMatch ? Number(ratingMatch[1]) : null;

    items.push({
      tmdb_id: id,
      media_type: kind,
      type: kind,
      title: title || `TMDB ${kind} ${id}`,
      year,
      rating,
      poster_url: imgSrc?.[1] || null,
      source: 'MDBList'
    });
  }

  if (items.length === 0) {
    const bareRegex = /themoviedb\.org\/(movie|tv)\/(\d+)/gi;
    while ((match = bareRegex.exec(html)) !== null) {
      const kind = match[1];
      const id = Number(match[2]);
      const key = `${kind}-${id}`;
      if (!Number.isFinite(id) || id <= 0 || seen.has(key)) continue;
      seen.add(key);
      items.push({
        tmdb_id: id,
        media_type: kind,
        type: kind,
        title: `TMDB ${kind} ${id}`,
        poster_url: null,
        source: 'MDBList'
      });
    }
  }

  return items;
}

app.get('/api/health', (req, res) => {
  res.json({
    ok: true,
    service: 'streamline-cinema-proxy',
    hasServerKey: Boolean(process.env.TMDB_API_KEY),
    categories: Object.keys(TMDB_CATEGORIES),
    time: new Date().toISOString()
  });
});

app.get('/api/tmdb', wrap(async (req, res) => {
  const path = String(req.query.path || '').replace(/^\/+/, '');
  if (!path) return fail(res, 400, 'Provide ?path=movie/popular');
  const params = { ...req.query };
  delete params.path;
  delete params.key;
  const body = await tmdbGet(path, params, accessKey(req));
  res.json(body);
}));

app.get('/api/tmdb-search', wrap(async (req, res) => {
  const query = String(req.query.query || req.query.q || '').trim();
  if (!query) return fail(res, 400, 'Provide ?query=title');
  const requestedType = String(req.query.type || 'multi').toLowerCase();
  const type = ['multi', 'movie', 'tv'].includes(requestedType) ? requestedType : 'multi';
  const adult = String(req.query.include_adult || req.query.adult || 'false') === 'true' ? 'true' : 'false';
  const body = await tmdbGet(
    `/search/${type}`,
    {
      query,
      include_adult: adult,
      language: req.query.language || 'en-US',
      page: req.query.page || 1
    },
    accessKey(req)
  );
  res.json({
    type,
    page: body.page || 1,
    total_pages: body.total_pages || 1,
    total_results: body.total_results || 0,
    results: (body.results || []).map((entry) => toCatalogItem(entry)).filter(Boolean)
  });
}));

app.get('/api/tmdb-presets', wrap(async (req, res) => {
  const category = String(req.query.category || '').trim();
  const preset = TMDB_CATEGORIES[category];
  if (!preset) {
    return fail(
      res,
      400,
      `Unknown category "${category}". Use one of: ${Object.keys(TMDB_CATEGORIES).join(', ')}`
    );
  }
  const body = await tmdbGet(
    preset.path,
    {
      page: req.query.page || 1,
      language: req.query.language || 'en-US',
      region: req.query.region || 'US'
    },
    accessKey(req)
  );
  const results = (body.results || [])
    .map((entry) => toCatalogItem(entry, preset.mediaType))
    .filter(Boolean);
  res.json({
    category,
    media_type: preset.mediaType,
    page: body.page || 1,
    total_pages: body.total_pages || 1,
    total_results: body.total_results || results.length,
    results
  });
}));

app.get('/api/mdblist/parse', wrap(async (req, res) => {
  const target = String(req.query.url || '').trim();
  if (!/^https?:\/\//i.test(target)) {
    return fail(res, 400, 'Provide ?url=https://mdblist.com/lists/owner/list-name');
  }
  const response = await fetch(target, {
    redirect: 'follow',
    headers: {
      'user-agent': BROWSER_UA,
      accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'accept-language': 'en-US,en;q=0.9'
    },
    signal: AbortSignal.timeout(15000)
  });
  if (!response.ok) {
    return fail(res, response.status, `MDBList responded ${response.status}`);
  }
  const html = await response.text();
  const head = html.slice(0, 5000);
  if (/just a moment|cf-challenge|attention required|enable javascript and cookies|checking your browser/i.test(head)) {
    return fail(res, 502, 'MDBList returned a browser challenge page; retry later or use a TMDB preset');
  }
  const items = parseMdbList(html);
  if (items.length === 0) {
    return fail(res, 404, 'No TMDB references were found on that list page');
  }
  res.json({ source: target, count: items.length, items });
}));

app.get('/api/proxy', wrap(async (req, res) => {
  const target = String(req.query.url || '').trim();
  if (!/^https?:\/\//i.test(target)) {
    return fail(res, 400, 'Provide ?url=https://example.com/catalog.json');
  }
  const response = await fetch(target, {
    redirect: 'follow',
    headers: {
      'user-agent': BROWSER_UA,
      accept: 'application/json, text/html;q=0.9, */*;q=0.8'
    },
    signal: AbortSignal.timeout(15000)
  });
  const text = await response.text();
  if (!response.ok) {
    return fail(res, response.status, `Upstream responded ${response.status}`);
  }
  res.type(response.headers.get('content-type') || 'application/json').send(text);
}));

app.use((req, res) => fail(res, 404, `Unknown endpoint ${req.method} ${req.path}`));

app.listen(PORT, () => {
  console.log(`StreamLine Cinema proxy listening on http://0.0.0.0:${PORT}`);
  console.log('Endpoints: /api/tmdb, /api/tmdb-search, /api/tmdb-presets, /api/mdblist/parse, /api/proxy');
  if (!process.env.TMDB_API_KEY) {
    console.log('No TMDB_API_KEY env set; clients must send X-TMDB-Key.');
  }
});
