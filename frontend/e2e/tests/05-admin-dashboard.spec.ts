import { expect, test } from '@playwright/test';
import { LibraryApp } from '../pages/library-app.js';

/**
 * The librarian's dashboard: members, who has which book, and loans that are due soon or overdue.
 * Other specs share the same backend, so assertions target this test's own book and member rows.
 * Moves the demo clock, so it runs after the other specs.
 */
const unique = (prefix: string) => `${prefix} ${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

test('the admin dashboard lists members and loans and flags overdue books', async ({ page }) => {
  const app = new LibraryApp(page);
  const dashboard = page.locator('[data-remote="admin"]');
  const title = unique('Ivanhoe');
  const reader = unique('Omar');

  await app.open();
  await app.login('admin');
  await expect(dashboard.getByRole('heading', { name: 'Library dashboard' })).toBeVisible();
  await app.addBook(title);
  await app.logout('admin');

  // Members never see the dashboard.
  await app.login(reader);
  await expect(dashboard).toHaveCount(0);
  await app.clickRowAction(title, 'Borrow');
  await app.expectToast(`You borrowed "${title}"`);
  await app.logout(reader);

  await app.login('admin');
  const loanRow = dashboard.getByTestId('admin-loans').getByRole('row').filter({ hasText: title });
  const memberRow = dashboard.getByTestId('admin-members').getByRole('row').filter({ hasText: reader });

  await dashboard.getByRole('button', { name: /^All loans/ }).click();
  await expect(loanRow).toContainText(reader);
  await expect(loanRow).toContainText('Due in 14 days');
  await expect(memberRow.getByRole('cell').nth(1)).toHaveText('1');
  await expect(memberRow).toContainText('Can borrow');

  // 15 days later the loan is 1 day overdue; the dashboard refreshes after the clock moves.
  await app.advanceClock(15);
  await dashboard.getByRole('button', { name: /^Overdue/ }).click();
  await expect(loanRow).toContainText('Overdue 1 day · Rs. 10');
  await expect(memberRow).toContainText('Blocked: 1 overdue book');
});
