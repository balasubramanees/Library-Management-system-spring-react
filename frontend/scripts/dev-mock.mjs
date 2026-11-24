#!/usr/bin/env node
/**
 * Frontend-only mode: mock gateway + the four frontend servers. No JVM needed.
 * Used by `npm run dev:mock` and by Playwright when E2E_BACKEND=mock.
 */
import { startMockGateway } from './mock-gateway.mjs';
import { startFrontends } from './serve-frontends.mjs';

const gatewayPort = Number(process.env.MOCK_GATEWAY_PORT ?? 18080);
const shellPort = Number(process.env.SHELL_PORT ?? 3000);

const gateway = await startMockGateway({ port: gatewayPort });
const frontends = await startFrontends({
  gatewayUrl: `http://127.0.0.1:${gatewayPort}`,
  shellPort,
  catalogPort: Number(process.env.CATALOG_PORT ?? shellPort + 1),
  accountPort: Number(process.env.ACCOUNT_PORT ?? shellPort + 2),
  adminPort: Number(process.env.ADMIN_PORT ?? shellPort + 3),
});
console.log(`[dev-mock] Library (mock backend) running at ${frontends.url}`);

const shutdown = async () => {
  await frontends.stop();
  gateway.close();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
