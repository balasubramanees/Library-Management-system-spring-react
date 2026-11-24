/**
 * Serves each micro-frontend from its own origin (port), the way they would be deployed to
 * separate CDNs/buckets:
 *
 *   shell        :3000  index.html, vendor React, /mfe-manifest.json, and /api -> gateway proxy
 *   mfe-catalog  :3001  remoteEntry.js (CORS-enabled for the shell origin)
 *   mfe-account  :3002  remoteEntry.js
 *   mfe-admin    :3003  remoteEntry.js (admin only)
 *
 * Proxying /api through the shell origin keeps the browser same-origin (no CORS for API calls,
 * no third-party cookies) - the shell server acts as a thin BFF edge in front of the gateway.
 * Dependency-free: only node: built-ins.
 */
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import http from 'node:http';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

const BASE_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Cache-Control': 'no-cache',
};

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, { ...BASE_HEADERS, 'Content-Type': CONTENT_TYPES['.json'], ...headers });
  res.end(JSON.stringify(body));
}

/** Resolves a URL path inside `dir`, refusing anything that escapes it (path traversal). */
function safeResolve(dir, urlPath) {
  let decoded;
  try {
    decoded = decodeURIComponent(urlPath.split('?')[0]);
  } catch {
    return null;
  }
  const candidate = normalize(join(dir, decoded));
  return candidate === dir || candidate.startsWith(dir + sep) ? candidate : null;
}

async function serveFile(res, file, extraHeaders = {}) {
  const info = await stat(file).catch(() => null);
  if (!info?.isFile()) return false;
  res.writeHead(200, {
    ...BASE_HEADERS,
    'Content-Type': CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
    'Content-Length': info.size,
    ...extraHeaders,
  });
  createReadStream(file).pipe(res);
  return true;
}

function proxy(req, res, gatewayUrl, timeoutMs = 10_000) {
  const target = new URL(req.url, gatewayUrl);
  const forwardedFor = [req.headers['x-forwarded-for'], req.socket.remoteAddress].filter(Boolean).join(', ');
  const upstream = http.request(
    target,
    {
      method: req.method,
      headers: { ...req.headers, host: target.host, 'x-forwarded-for': forwardedFor },
      timeout: timeoutMs,
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );
  const fail = (code, message) => {
    if (res.headersSent) return res.destroy();
    sendJson(res, code === 'TIMEOUT' ? 504 : 502, {
      error: { code: 'SERVICE_UNAVAILABLE', message, requestId: req.headers['x-request-id'] ?? 'n/a' },
    });
  };
  upstream.on('timeout', () => {
    upstream.destroy();
    fail('TIMEOUT', 'The library is taking too long to answer. Please try again.');
  });
  upstream.on('error', () => fail('DOWN', 'The library service is not reachable. Is the backend running?'));
  req.pipe(upstream);
}

function cspFor(html) {
  // Allow exactly the inline import map (by hash) and scripts from the remotes' origins.
  const importMap = html.match(/<script type="importmap">([\s\S]*?)<\/script>/)?.[1] ?? '';
  const hash = createHash('sha256').update(importMap).digest('base64');
  return (remoteOrigins) =>
    [
      "default-src 'self'",
      `script-src 'self' 'sha256-${hash}' ${remoteOrigins.join(' ')}`,
      "style-src 'self' 'unsafe-inline'",
      "connect-src 'self'",
      "img-src 'self' data:",
      "base-uri 'none'",
      "frame-ancestors 'none'",
      "form-action 'self'",
    ].join('; ');
}

function listen(server, port, host) {
  return new Promise((resolvePromise, reject) => {
    server.once('error', (error) => {
      reject(error.code === 'EADDRINUSE' ? new Error(`Port ${port} is already in use. Stop the other process or set a different port.`) : error);
    });
    server.listen(port, host, () => resolvePromise(server));
  });
}

/** Static server for a remote: only its own dist folder, CORS for the shell origin. */
export async function startRemoteServer({ name, port, host = '127.0.0.1', shellOrigin }) {
  const dist = join(frontendRoot, 'apps', name, 'dist');
  const server = http.createServer(async (req, res) => {
    const cors = { 'Access-Control-Allow-Origin': shellOrigin, Vary: 'Origin' };
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { ...cors, 'Access-Control-Allow-Methods': 'GET' });
      return res.end();
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendJson(res, 405, { error: 'Method not allowed' });
    if (req.url === '/health') return sendJson(res, 200, { status: 'UP', remote: name }, cors);
    const file = safeResolve(dist, req.url === '/' ? '/remoteEntry.js' : req.url);
    if (!file || !(await serveFile(res, file, cors))) sendJson(res, 404, { error: 'Not found' }, cors);
  });
  return listen(server, port, host);
}

/** Shell server: static host app + runtime manifest + /api proxy to the gateway. */
export async function startShellServer({ port, host = '127.0.0.1', gatewayUrl, remotes }) {
  const dist = join(frontendRoot, 'apps/shell/dist');
  const indexHtml = await readFile(join(dist, 'index.html'), 'utf8');
  const csp = cspFor(indexHtml)(Object.values(remotes).map((r) => new URL(r.url).origin));
  const manifest = { remotes };

  const server = http.createServer(async (req, res) => {
    const path = (req.url ?? '/').split('?')[0];
    if (path.startsWith('/api/')) return proxy(req, res, gatewayUrl);
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendJson(res, 405, { error: 'Method not allowed' });
    if (path === '/health') return sendJson(res, 200, { status: 'UP', app: 'shell' });
    if (path === '/mfe-manifest.json') return sendJson(res, 200, manifest);

    const file = safeResolve(dist, path);
    if (file && path !== '/' && (await serveFile(res, file))) return;
    // SPA fallback: any other route renders the shell.
    res.writeHead(200, { ...BASE_HEADERS, 'Content-Type': CONTENT_TYPES['.html'], 'Content-Security-Policy': csp });
    res.end(indexHtml);
  });
  return listen(server, port, host);
}

/** Starts the shell and the three remotes. Returns a stop() function. */
export async function startFrontends({
  gatewayUrl,
  host = '127.0.0.1',
  shellPort = 3000,
  catalogPort = 3001,
  accountPort = 3002,
  adminPort = 3003,
  publicHost = 'localhost',
}) {
  const shellOrigin = `http://${publicHost}:${shellPort}`;
  // Admin first: for the librarian the dashboard sits above the catalogue. Members never see it.
  const remotes = {
    admin: { name: 'admin', url: `http://${publicHost}:${adminPort}/remoteEntry.js`, adminOnly: true },
    catalog: { name: 'catalog', url: `http://${publicHost}:${catalogPort}/remoteEntry.js` },
    account: { name: 'account', url: `http://${publicHost}:${accountPort}/remoteEntry.js`, memberOnly: true },
  };
  const servers = await Promise.all([
    startRemoteServer({ name: 'mfe-catalog', port: catalogPort, host, shellOrigin }),
    startRemoteServer({ name: 'mfe-account', port: accountPort, host, shellOrigin }),
    startRemoteServer({ name: 'mfe-admin', port: adminPort, host, shellOrigin }),
    startShellServer({ port: shellPort, host, gatewayUrl, remotes }),
  ]);
  return {
    url: shellOrigin,
    stop: () => Promise.all(servers.map((s) => new Promise((r) => { s.closeAllConnections?.(); s.close(() => r()); }))),
  };
}
