'use strict';

/**
 * StreamLine serverless stream proxy (`/api/p`).
 *
 * Deploy this file with any Vercel/Netlify-style Node runtime and point players
 * at https://your-host/api/p?url=<stream>. It exists to bridge the two things a
 * browser or one-off client cannot do on its own:
 *
 *   - CORS: every response carries Access-Control-Allow-Origin, so a web player
 *     can range-request segments cross-origin.
 *   - HTTP on HTTPS: the page can stay on https while the upstream playlist or
 *     segment is plain http, because the fetch happens server-side.
 *
 * HLS playlists are rewritten so variant playlists, encryption keys, init maps
 * and media segments all come back through the same proxy. Byte ranges are
 * forwarded untouched, so seeking still works.
 *
 * Set PROXY_TOKEN to require ?token=... (recommended for any public deploy);
 * the token is carried into rewritten playlist URLs automatically.
 */

const { Readable } = require('node:stream');

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
const MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;
const PASS_THROUGH_HEADERS = [
  'content-type',
  'content-length',
  'content-range',
  'accept-ranges',
  'etag',
  'last-modified',
  'cache-control',
  'expires'
];
const FORWARD_REQUEST_HEADERS = new Set([
  'user-agent',
  'referer',
  'origin',
  'cookie',
  'authorization',
  'range',
  'if-range',
  'accept',
  'accept-language'
]);
const URI_ATTRIBUTE = /URI="([^"]+)"/g;

// Explicit stream types. Native players pick their demuxer from Content-Type,
// so a provider that omits the header or answers application/octet-stream must
// not leave the app guessing.
const STREAM_TYPES = {
  m3u8: 'application/vnd.apple.mpegurl',
  m3u: 'application/vnd.apple.mpegurl',
  mpd: 'application/dash+xml',
  ts: 'video/mp2t',
  m2ts: 'video/mp2t',
  mts: 'video/mp2t',
  m4s: 'video/iso.segment',
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  fmp4: 'video/mp4',
  cmfv: 'video/mp4',
  mov: 'video/quicktime',
  webm: 'video/webm',
  mkv: 'video/x-matroska',
  aac: 'audio/aac',
  m4a: 'audio/mp4',
  mp3: 'audio/mpeg',
  ac3: 'audio/ac3',
  ec3: 'audio/eac3',
  vtt: 'text/vtt',
  webvtt: 'text/vtt',
  srt: 'application/x-subrip',
  ass: 'text/x-ssa',
  ssa: 'text/x-ssa',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  key: 'application/octet-stream'
};
const GENERIC_TYPES = new Set([
  'application/octet-stream',
  'binary/octet-stream',
  'application/unknown',
  'text/plain'
]);

function contentTypeFor(target, upstreamType) {
  let path = '';
  try {
    path = new URL(target).pathname.toLowerCase();
  } catch {
    path = String(target || '').toLowerCase().split('?')[0];
  }
  if (path.endsWith('.mpd.xml')) return 'application/dash+xml';
  const name = path.substring(path.lastIndexOf('/') + 1);
  const dot = name.lastIndexOf('.');
  const extension = dot >= 0 ? name.slice(dot + 1) : '';
  if (STREAM_TYPES[extension]) return STREAM_TYPES[extension];
  const offered = String(upstreamType || '')
    .split(';')[0]
    .trim()
    .toLowerCase();
  if (offered.includes('mpegurl')) return 'application/vnd.apple.mpegurl';
  if (!offered || GENERIC_TYPES.has(offered)) return 'application/octet-stream';
  return offered;
}

function applyCors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,HEAD,OPTIONS');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'Content-Type, Range, X-Proxy-Token, User-Agent, Referer'
  );
  res.setHeader(
    'Access-Control-Expose-Headers',
    'Content-Length, Content-Range, Accept-Ranges, ETag, Last-Modified'
  );
}

function sendError(res, status, message) {
  if (res.headersSent) {
    res.destroy();
    return;
  }
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ error: message }));
}

function requestUrl(req) {
  return new URL(req.url || '/', 'http://localhost');
}

function authorized(req, query) {
  const expected = process.env.PROXY_TOKEN;
  if (!expected) return true;
  const supplied = String(
    query.get('token') || req.headers['x-proxy-token'] || ''
  ).trim();
  return supplied === expected;
}

function decodeHeaderBlob(raw) {
  const source = String(raw || '').trim();
  if (!source) return {};
  try {
    const json = Buffer.from(source, 'base64url').toString('utf8');
    const parsed = JSON.parse(json);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const headers = {};
    for (const [key, value] of Object.entries(parsed)) {
      if (typeof value !== 'string') continue;
      const name = key.toLowerCase();
      if (!FORWARD_REQUEST_HEADERS.has(name)) continue;
      if (value.length > 4096) continue;
      headers[name] = value;
    }
    return headers;
  } catch {
    return {};
  }
}

function upstreamHeaders(req, query) {
  const headers = { 'user-agent': BROWSER_UA, accept: '*/*' };
  Object.assign(headers, decodeHeaderBlob(query.get('h')));
  const range = req.headers.range;
  if (range) headers.range = String(range);
  const ifRange = req.headers['if-range'];
  if (ifRange) headers['if-range'] = String(ifRange);
  return headers;
}

function proxyBase(req) {
  const proto = String(req.headers['x-forwarded-proto'] || 'https')
    .split(',')[0]
    .trim();
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '')
    .split(',')[0]
    .trim();
  return `${proto}://${host}/api/p`;
}

function proxiedLink(absolute, base, headerBlob, token) {
  const params = new URLSearchParams({ url: absolute });
  if (headerBlob) params.set('h', headerBlob);
  if (token) params.set('token', token);
  return `${base}?${params.toString()}`;
}

function rewritePlaylist(text, playlistUrl, base, headerBlob, token) {
  return text
    .split('\n')
    .map((raw) => {
      const line = raw.replace(/\r$/, '');
      const trimmed = line.trim();
      if (!trimmed) return '';
      if (trimmed.startsWith('#')) {
        return trimmed.replace(URI_ATTRIBUTE, (match, uri) => {
          const absolute = resolve(uri, playlistUrl);
          return absolute ? `URI="${proxiedLink(absolute, base, headerBlob, token)}"` : match;
        });
      }
      const absolute = resolve(trimmed, playlistUrl);
      return absolute ? proxiedLink(absolute, base, headerBlob, token) : trimmed;
    })
    .join('\n');
}

function resolve(value, base) {
  try {
    const url = new URL(value, base);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
    return url.toString();
  } catch {
    return null;
  }
}

function isPlaylist(response, target) {
  const contentType = String(response.headers.get('content-type') || '').toLowerCase();
  if (contentType.includes('mpegurl')) return true;
  try {
    const path = new URL(target).pathname.toLowerCase();
    return path.endsWith('.m3u8') && !contentType.includes('html');
  } catch {
    return false;
  }
}

async function handler(req, res) {
  applyCors(res);
  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    res.end();
    return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return sendError(res, 405, 'Only GET and HEAD are proxied');
  }

  const query = requestUrl(req).searchParams;
  if (!authorized(req, query)) {
    return sendError(res, 401, 'Invalid proxy token');
  }

  const target = String(query.get('url') || '').trim();
  let parsed;
  try {
    parsed = new URL(target);
  } catch {
    return sendError(res, 400, 'Provide ?url=http(s)://…');
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return sendError(res, 400, 'Only http and https targets are supported');
  }

  const headerBlob = query.get('h') || '';
  const token = query.get('token') || '';

  let upstream;
  try {
    upstream = await fetch(parsed, {
      method: req.method === 'HEAD' ? 'HEAD' : 'GET',
      headers: upstreamHeaders(req, query),
      redirect: 'follow'
    });
  } catch (error) {
    return sendError(res, 502, `Upstream unreachable: ${error.message}`);
  }

  try {
    if (isPlaylist(upstream, parsed.toString())) {
      const buffer = Buffer.from(await upstream.arrayBuffer());
      if (buffer.length > MAX_PLAYLIST_BYTES) {
        return sendError(res, 502, 'Playlist is too large to rewrite');
      }
      const text = buffer.toString('utf8');
      const finalUrl = upstream.url || parsed.toString();
      const rewritten = rewritePlaylist(
        text,
        finalUrl,
        proxyBase(req),
        headerBlob,
        token
      );
      res.statusCode = upstream.status;
      res.setHeader(
        'Content-Type',
        contentTypeFor(finalUrl, upstream.headers.get('content-type'))
      );
      res.setHeader('Cache-Control', 'no-cache, no-store');
      res.setHeader('Content-Length', Buffer.byteLength(rewritten));
      res.end(req.method === 'HEAD' ? undefined : rewritten);
      return;
    }

    res.statusCode = upstream.status;
    for (const name of PASS_THROUGH_HEADERS) {
      if (name === 'content-type') continue;
      const value = upstream.headers.get(name);
      if (value) res.setHeader(name, value);
    }
    res.setHeader(
      'Content-Type',
      contentTypeFor(
        upstream.url || parsed.toString(),
        upstream.headers.get('content-type')
      )
    );
    if (!res.getHeader('accept-ranges')) res.setHeader('accept-ranges', 'bytes');
    if (req.method === 'HEAD' || !upstream.body) {
      res.end();
      return;
    }

    const stream = Readable.fromWeb(upstream.body);
    stream.on('error', () => res.destroy());
    res.on('close', () => {
      if (!stream.destroyed) stream.destroy();
    });
    stream.pipe(res);
  } catch (error) {
    sendError(res, 502, `Proxy failed: ${error.message}`);
  }
}

module.exports = handler;
module.exports.config = { maxDuration: 300 };
