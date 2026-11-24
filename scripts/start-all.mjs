#!/usr/bin/env node
/**
 * Process orchestrator for local runs (what docker-compose / Kubernetes would do in production):
 *
 *   1. start user-service and library-service, then the gateway, each as its own JVM
 *   2. wait for every service's readiness probe
 *   3. serve the shell and the three remotes, each on its own origin
 *   4. on Ctrl+C (or if a service dies during start-up) stop everything gracefully
 *
 * Service logs go to logs/<service>.log so the console stays readable.
 * Ports are configurable through environment variables (see README).
 */
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startFrontends } from '../frontend/scripts/serve-frontends.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const env = process.env;
const port = (name, fallback) => Number(env[name] ?? fallback);

const PORTS = {
  gateway: port('GATEWAY_PORT', 8080),
  user: port('USER_SERVICE_PORT', 8081),
  library: port('LIBRARY_SERVICE_PORT', 8082),
  shell: port('SHELL_PORT', 3000),
  catalog: port('CATALOG_PORT', 3001),
  account: port('ACCOUNT_PORT', 3002),
  admin: port('ADMIN_PORT', 3003),
};
const JAVA_OPTS = (env.JAVA_OPTS ?? '-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -Xmx256m').split(/\s+/).filter(Boolean);
const STARTUP_TIMEOUT_MS = Number(env.STARTUP_TIMEOUT_MS ?? 180_000);

const services = [
  {
    name: 'user-service',
    jar: 'backend/user-service/target/user-service.jar',
    port: PORTS.user,
    env: { USER_SERVICE_PORT: String(PORTS.user) },
  },
  {
    name: 'library-service',
    jar: 'backend/library-service/target/library-service.jar',
    port: PORTS.library,
    env: { LIBRARY_SERVICE_PORT: String(PORTS.library), USER_SERVICE_URL: `http://127.0.0.1:${PORTS.user}` },
  },
  {
    name: 'gateway',
    jar: 'backend/gateway/target/gateway.jar',
    port: PORTS.gateway,
    env: {
      GATEWAY_PORT: String(PORTS.gateway),
      USER_SERVICE_URL: `http://127.0.0.1:${PORTS.user}`,
      LIBRARY_SERVICE_URL: `http://127.0.0.1:${PORTS.library}`,
    },
  },
];

const children = new Map();
let frontends = null;
let shuttingDown = false;

const log = (msg) => console.log(`\x1b[1;36m[start]\x1b[0m ${msg}`);
const fail = (msg) => console.error(`\x1b[1;31m[start] ${msg}\x1b[0m`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function tail(file, lines = 25) {
  try {
    return readFileSync(file, 'utf8').trimEnd().split('\n').slice(-lines).join('\n');
  } catch {
    return '(no log)';
  }
}

function startService(service) {
  const jar = join(root, service.jar);
  if (!existsSync(jar)) throw new Error(`${service.jar} not found - run ./start.sh (it builds the services first).`);
  const logFile = join(root, 'logs', `${service.name}.log`);
  const out = createWriteStream(logFile, { flags: 'w' });
  const child = spawn('java', [...JAVA_OPTS, '-Dspring.output.ansi.enabled=never', '-jar', jar], {
    cwd: root,
    env: { ...env, ...service.env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.pipe(out);
  child.stderr.pipe(out);
  child.on('exit', (code, signal) => {
    children.delete(service.name);
    if (shuttingDown) return;
    fail(`${service.name} exited unexpectedly (code ${code ?? signal}). Last log lines:\n${tail(logFile)}`);
    if (service.name !== 'gateway') {
      log('The UI stays up; the gateway circuit breaker will answer 503 for this service until it is restarted.');
    } else {
      void shutdown(1);
    }
  });
  children.set(service.name, { child, logFile });
  return child;
}

async function waitUntilReady(service) {
  const url = `http://127.0.0.1:${service.port}/actuator/health/readiness`;
  const deadline = Date.now() + STARTUP_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (!children.has(service.name)) throw new Error(`${service.name} stopped during start-up (see logs/${service.name}.log)`);
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1_000) });
      if (res.ok) return;
    } catch {
      /* not up yet */
    }
    await sleep(500);
  }
  throw new Error(`${service.name} did not become ready within ${STARTUP_TIMEOUT_MS / 1000}s (see logs/${service.name}.log)`);
}

async function shutdown(exitCode = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  log('Shutting down...');
  await frontends?.stop().catch(() => undefined);
  const exits = [...children.values()].map(
    ({ child }) =>
      new Promise((resolveExit) => {
        child.once('exit', resolveExit);
        child.kill('SIGTERM'); // Spring graceful shutdown
        setTimeout(() => child.kill('SIGKILL'), 15_000).unref();
      }),
  );
  await Promise.all(exits);
  log('Stopped.');
  process.exit(exitCode);
}

process.on('SIGINT', () => void shutdown(0));
process.on('SIGTERM', () => void shutdown(0));

try {
  const started = Date.now();
  const [userService, libraryService, gateway] = services;
  log(`Starting user-service (:${PORTS.user}) and library-service (:${PORTS.library})...`);
  startService(userService);
  startService(libraryService);
  await Promise.all([waitUntilReady(userService), waitUntilReady(libraryService)]);
  log(`Starting gateway (:${PORTS.gateway})...`);
  startService(gateway);
  await waitUntilReady(gateway);

  frontends = await startFrontends({
    gatewayUrl: `http://127.0.0.1:${PORTS.gateway}`,
    shellPort: PORTS.shell,
    catalogPort: PORTS.catalog,
    accountPort: PORTS.account,
    adminPort: PORTS.admin,
  });

  const seconds = ((Date.now() - started) / 1000).toFixed(1);
  console.log(`
\x1b[1;32m  Library is running (started in ${seconds}s)\x1b[0m

    Open           ${frontends.url}
    Gateway        http://localhost:${PORTS.gateway}/actuator/health
    Breakers       http://localhost:${PORTS.gateway}/actuator/circuitbreakers
    Logs           logs/*.log

  Log in as "admin" to add books and see the library dashboard. Press Ctrl+C to stop.
`);
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
  await shutdown(1);
}
