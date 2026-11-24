import { expect, test } from '@playwright/test';
import { LibraryApp } from '../pages/library-app.js';

/** Builds on whatever state earlier specs left; uses unique names/titles to stay independent. */
const unique = (prefix: string) => `${prefix} ${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

test.describe('login page', () => {
  test('requires a name', async ({ page }) => {
    const app = new LibraryApp(page);
    await app.open();
    await page.getByRole('button', { name: 'Log in' }).click();
    await expect(page.getByRole('alert')).toHaveText('Please enter your name.');
    await expect(page.getByLabel('Your name')).toHaveAttribute('aria-invalid', 'true');
  });

  test('user names are case-insensitive and keep their first spelling', async ({ page }) => {
    const app = new LibraryApp(page);
    const name = unique('Dora');
    await app.open();
    await app.login(name);
    await app.logout(name);
    await app.login(name.toUpperCase());
    await expect(page.getByTestId('current-user')).toHaveText(name);
  });

  test('session survives a page reload', async ({ page }) => {
    const app = new LibraryApp(page);
    const name = unique('Eve');
    await app.open();
    await app.login(name);
    await page.reload();
    await expect(page.getByTestId('current-user')).toHaveText(name);
    await expect(app.account.getByTestId('account-status')).toBeVisible();
  });

  test('a stale token (e.g. after a backend restart) returns the user to the login page', async ({ page }) => {
    const app = new LibraryApp(page);
    await app.open();
    await page.evaluate(() =>
      sessionStorage.setItem(
        'library.session',
        JSON.stringify({ token: 'stale-token', user: { id: 'ghost', name: 'Ghost', isAdmin: false } }),
      ),
    );
    await page.reload();
    await expect(page.getByLabel('Your name')).toBeVisible();
    await app.expectToast('Your session has expired. Please log in again.');
  });
});

test.describe('admin (librarian) view', () => {
  test('admin manages the catalogue but has no lending controls or account panel', async ({ page }) => {
    const app = new LibraryApp(page);
    const title = unique('Ivanhoe');
    await app.open();
    await app.login('admin');
    await app.addBook(title);

    await expect(app.row(title)).toContainText('(available)');
    await expect(app.row(title).getByRole('button')).toHaveCount(0);
    await expect(app.catalog.getByRole('form', { name: 'Find a book by title' })).toHaveCount(0);
    await expect(app.account).toHaveCount(0);
  });

  test('admin sees how many members are waiting for a book', async ({ page }) => {
    const app = new LibraryApp(page);
    const title = unique('Jane Eyre');
    await app.open();
    await app.login('admin');
    await app.addBook(title);
    await app.logout('admin');

    const holder = unique('Kim');
    await app.login(holder);
    await app.clickRowAction(title, 'Borrow');
    await app.logout(holder);
    const waiter = unique('Lee');
    await app.login(waiter);
    await app.clickRowAction(title, 'Join wait list');
    await app.logout(waiter);

    await app.login('admin');
    await expect(app.row(title).getByTestId('book-queue')).toHaveText('1 on wait list');
  });
});

test.describe('catalogue', () => {
  test('admin gets validation and duplicate feedback', async ({ page }) => {
    const app = new LibraryApp(page);
    const title = unique('Dune');
    await app.open();
    await app.login('admin');
    await expect(app.catalog.getByRole('button', { name: 'Add book' })).toBeDisabled();
    await app.addBook(title);
    await app.catalog.getByLabel('Add a book to the library').fill(`  ${title.toLowerCase()} `);
    await app.catalog.getByRole('button', { name: 'Add book' }).click();
    await app.expectToast(`Sorry, "${title}" is already registered in the library.`);
    await expect(app.row(title)).toContainText('(available)');
  });

  test('borrowing then returning from the catalogue row keeps both remotes in sync', async ({ page }) => {
    const app = new LibraryApp(page);
    const title = unique('Emma');
    await app.open();
    await app.login('admin');
    await app.addBook(title);
    await app.logout('admin');

    const reader = unique('Finn');
    await app.login(reader);
    await app.clickRowAction(title, 'Borrow');
    await expect(app.row(title)).toContainText('(borrowed)');
    await expect(app.account.getByTestId('loan-row').filter({ hasText: title })).toBeVisible();

    await app.clickRowAction(title, 'Return');
    await app.expectToast(`You returned "${title}".`);
    await expect(app.row(title)).toContainText('(available)');
    await expect(app.account.getByTestId('loan-row').filter({ hasText: title })).toHaveCount(0);
  });
});

test.describe('micro-frontend isolation', () => {
  test('if the account remote cannot be loaded, the catalogue still works and the slot offers a retry', async ({ page, baseURL }) => {
    const app = new LibraryApp(page);
    // The account remote is served from its own origin: shell port + 2.
    const accountPort = String(Number(new URL(baseURL ?? 'http://localhost:3000').port) + 2);
    let blockAccount = true;
    await page.route(
      (url) => url.port === accountPort,
      (route) => (blockAccount ? route.abort('connectionrefused') : route.continue()),
    );
    await app.open();
    await page.getByLabel('Your name').fill(unique('Gus'));
    await page.getByRole('button', { name: 'Log in' }).click();

    await expect(app.account.getByRole('alert')).toContainText('The account module could not be loaded.');
    await expect(app.catalog.getByRole('heading', { name: 'Catalogue' })).toBeVisible();

    blockAccount = false;
    await app.account.getByRole('button', { name: 'Retry' }).click();
    await expect(app.account.getByTestId('account-status')).toBeVisible();
  });
});
