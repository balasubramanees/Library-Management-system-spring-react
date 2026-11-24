import { expect, type Locator, type Page } from '@playwright/test';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * The library's date `days` from today, formatted like the app ("08 Dec 2026"). The library runs
 * on Asia/Kolkata calendar days. Only valid while the demo clock has not been moved.
 */
export function libraryDateIn(days: number): string {
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
  const date = new Date(Date.parse(`${today}T00:00:00Z`) + days * 86_400_000);
  const [y, m, d] = date.toISOString().slice(0, 10).split('-');
  return `${d} ${MONTHS[Number(m) - 1]} ${y}`;
}

/**
 * Page object for the composed application (shell + catalog + account remotes).
 * Selectors use roles, labels and visible text first, so tests also guard accessibility.
 */
export class LibraryApp {
  readonly catalog: Locator;
  readonly account: Locator;
  readonly welcome: Locator;
  readonly toasts: Locator;

  constructor(readonly page: Page) {
    this.catalog = page.locator('[data-remote="catalog"]');
    this.account = page.locator('[data-remote="account"]');
    this.welcome = page.getByTestId('welcome');
    this.toasts = page.getByTestId('toast');
  }

  async open(): Promise<void> {
    await this.page.goto('/');
  }

  async login(name: string): Promise<void> {
    await this.page.getByLabel('Your name').fill(name);
    await this.page.getByRole('button', { name: 'Log in' }).click();
    await expect(this.page.getByTestId('current-user')).toContainText(name.trim(), { ignoreCase: true });
    await expect(this.catalog.getByRole('heading', { name: 'Catalogue' })).toBeVisible();
    if (name.trim().toLowerCase() !== 'admin') {
      await expect(this.account.getByTestId('account-status')).toBeVisible();
    }
  }

  async logout(expectedName: string): Promise<void> {
    await this.page.getByRole('button', { name: 'Log out' }).click();
    await this.expectToast(`Goodbye, ${expectedName}!`);
    await expect(this.page.getByLabel('Your name')).toBeVisible();
    await this.dismissToasts();
  }

  async expectWelcome(lines: string[]): Promise<void> {
    const rendered = this.welcome.locator('p');
    await expect(rendered).toHaveText(lines);
  }

  async expectBooks(lines: string[]): Promise<void> {
    await expect(this.catalog.getByTestId('book-line')).toHaveText(lines);
  }

  async expectEmptyCatalogue(): Promise<void> {
    await expect(this.catalog.getByTestId('catalog-empty')).toHaveText('No books are registered');
  }

  async expectAccount(lines: string[]): Promise<void> {
    await expect(this.account.getByTestId('status-line')).toHaveText(lines);
  }

  async addBook(title: string): Promise<void> {
    await this.catalog.getByLabel('Add a book to the library').fill(title);
    await this.catalog.getByRole('button', { name: 'Add book' }).click();
    await this.expectToast(`Book "${title}" has been added to the library.`);
  }

  row(title: string): Locator {
    return this.catalog.locator(`[data-testid="book-row"][data-title="${title}"]`);
  }

  async clickRowAction(title: string, action: 'Borrow' | 'Join wait list' | 'Return'): Promise<void> {
    await this.row(title).getByRole('button', { name: action }).click();
  }

  /** Uses the "act by title" form, i.e. the CLI-style `borrow "<title>"` commands. */
  async byTitle(action: 'Borrow' | 'Wait list' | 'Return', title: string): Promise<void> {
    const form = this.catalog.getByRole('form', { name: 'Find a book by title' });
    await form.getByLabel('Know the title? Act on it directly').fill(title);
    await form.getByRole('button', { name: action, exact: true }).click();
  }

  async returnFromAccount(title: string): Promise<void> {
    await this.account.getByRole('button', { name: `Return ${title}` }).click();
  }

  async extendFromAccount(title: string): Promise<void> {
    await this.account.getByRole('button', { name: `Extend ${title}` }).click();
  }

  loanDue(title: string): Locator {
    return this.account.getByTestId('loan-row').filter({ hasText: title }).getByTestId('loan-due');
  }

  /** Admin only: moves the library date forward with the demo clock. */
  async advanceClock(days: 1 | 7 | 15): Promise<void> {
    await this.catalog.getByRole('group', { name: 'Demo clock' }).getByRole('button', { name: `+${days} day${days === 1 ? '' : 's'}` }).click();
    await this.expectToast('Library date moved forward');
  }

  async expectToast(text: string): Promise<void> {
    await expect(this.toasts.filter({ hasText: text }).first()).toBeVisible();
  }

  /** Clears toasts so later assertions cannot match a stale message. */
  async dismissToasts(): Promise<void> {
    const buttons = this.page.getByRole('button', { name: 'Dismiss notification' });
    for (let i = 0; i < 10 && (await buttons.count()) > 0; i++) {
      await buttons.first().click({ timeout: 2_000 }).catch(() => undefined);
    }
  }
}
