import { expect, test } from '@playwright/test';
import { isMockBackend } from '../../playwright.config.js';
import { LibraryApp } from '../pages/library-app.js';

/**
 * Client-side circuit breaker. Needs a controllable outage, which the mock gateway provides
 * (POST /__mock/outage). Against the real stack, stop library-service by hand to see the same banner.
 */
test.skip(!isMockBackend, 'requires the mock gateway (npm run e2e:mock)');

test('repeated 503s open the circuit, show a banner, and the app recovers', async ({ page, request }) => {
  const app = new LibraryApp(page);
  await app.open();
  await app.login(`Hana ${Date.now()}`);

  // Enough failing responses to exhaust GET retries and trip the breaker (threshold 3).
  await request.post('http://127.0.0.1:18180/__mock/outage', { data: { requests: 12 } });
  const refresh = app.account.getByRole('button', { name: 'Refresh my account' });
  for (let i = 0; i < 3; i++) {
    await refresh.click();
    await expect(app.account.getByRole('alert')).toBeVisible();
  }

  await expect(page.getByTestId('service-banner')).toContainText('unavailable');
  await expect(app.account.getByRole('alert')).toContainText(/temporarily unavailable/);

  // Outage over: after the reset timeout a trial request closes the circuit again.
  await request.post('http://127.0.0.1:18180/__mock/outage', { data: { requests: 0 } });
  await expect(async () => {
    await refresh.click();
    await expect(page.getByTestId('service-banner')).toHaveCount(0, { timeout: 1_000 });
  }).toPass({ timeout: 20_000, intervals: [1_000] });
  await expect(app.account.getByTestId('account-status')).toBeVisible();
});
