import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests of the React micro-frontends.
 *
 *   npm run e2e         full stack: starts ../start.sh (Spring services + frontends), or reuses a
 *                       stack already running on :3000
 *   npm run e2e:mock    frontends against the in-memory mock gateway (no JVM needed)
 *   E2E_BASE_URL=...    run against any already-running deployment
 */
const mock = process.env.E2E_BACKEND === 'mock';
const external = process.env.E2E_BASE_URL;
const baseURL = external ?? (mock ? 'http://localhost:3100' : 'http://localhost:3000');

export default defineConfig({
  testDir: './e2e/tests',
  // Scenarios share one backend whose state they build on: run serially, in file order.
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 7_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: external
    ? undefined
    : mock
      ? {
          command: 'node scripts/dev-mock.mjs',
          env: { SHELL_PORT: '3100', MOCK_GATEWAY_PORT: '18180' },
          url: `${baseURL}/health`,
          reuseExistingServer: false,
          timeout: 30_000,
        }
      : {
          command: 'bash ../start.sh',
          url: `${baseURL}/health`,
          reuseExistingServer: true,
          timeout: 600_000,
          stdout: 'pipe',
        },
});

export const isMockBackend = mock;
