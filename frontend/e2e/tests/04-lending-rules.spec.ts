import { expect, test } from '@playwright/test';
import { LibraryApp, libraryDateIn } from '../pages/library-app.js';

/**
 * Due dates, extensions, overdue blocking, late fees and deposits through the UI. Uses the admin's
 * demo clock, so this spec runs last (later dates would affect other specs' expectations).
 */
const unique = (prefix: string) => `${prefix} ${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

test('a loan can be extended twice by 7 days, then no more', async ({ page }) => {
  const app = new LibraryApp(page);
  const title = unique('Persuasion');
  const reader = unique('Maya');
  await app.open();
  await app.login('admin');
  await app.addBook(title);
  await app.logout('admin');

  await app.login(reader);
  await app.clickRowAction(title, 'Borrow');
  await expect(app.loanDue(title)).toHaveText(`Due ${libraryDateIn(14)} · 2 extensions left`);

  await app.extendFromAccount(title);
  await app.expectToast(`The return date of "${title}" is now ${libraryDateIn(21)}. 1 extension left.`);
  await app.extendFromAccount(title);
  await app.expectToast(`The return date of "${title}" is now ${libraryDateIn(28)}. This was the last extension.`);
  await expect(app.loanDue(title)).toHaveText(`Due ${libraryDateIn(28)} · 0 extensions left`);
  await expect(app.account.getByRole('button', { name: `Extend ${title}` })).toBeDisabled();
  await expect(app.row(title).getByTestId('loan-details')).toContainText(`due ${libraryDateIn(28)}`);
});

test('an overdue book blocks borrowing, costs Rs. 10 a day, and a top-up restores the balance', async ({ page }) => {
  const app = new LibraryApp(page);
  const late = unique('Middlemarch');
  const other = unique('Walden');
  const reader = unique('Nina');
  await app.open();
  await app.login('admin');
  await app.addBook(late);
  await app.addBook(other);
  await app.logout('admin');

  await app.login(reader);
  await app.clickRowAction(late, 'Borrow');
  await app.logout(reader);

  // 15 days later the 14-day loan is 1 day overdue.
  await app.login('admin');
  await app.advanceClock(15);
  await expect(app.row(late).getByTestId('loan-details')).toContainText(`${reader} · due`);
  await expect(app.row(late).getByTestId('loan-details')).toContainText('OVERDUE by 1 day');
  await app.logout('admin');

  await app.login(reader);
  await expect(app.welcome).toContainText('OVERDUE by 1 day, late fee so far Rs. 10');
  await expect(app.account.getByTestId('account-blocked')).toHaveText(
    'Sorry, you have 1 overdue book. Please return it before borrowing or joining a wait list.',
  );
  await expect(app.loanDue(late)).toContainText('OVERDUE by 1 day · late fee so far Rs. 10');
  await expect(app.account.getByRole('button', { name: `Extend ${late}` })).toBeDisabled();

  await app.clickRowAction(other, 'Borrow');
  await app.expectToast('Sorry, you have 1 overdue book.');

  await app.returnFromAccount(late);
  await app.expectToast(`You returned "${late}". It was 1 day late: Rs. 10 late fee deducted from your deposit (balance Rs. 490).`);
  await expect(app.account.getByTestId('deposit-balance')).toHaveText('Your deposit balance: Rs. 490');
  await expect(app.account.getByTestId('account-blocked')).toHaveCount(0);

  const form = app.account.getByRole('form', { name: 'Add deposit' });
  await form.getByLabel('Deposit amount in rupees').fill('200');
  await form.getByRole('button', { name: 'Add deposit (cash)' }).click();
  await app.expectToast('Rs. 200 received as deposit in cash. Your deposit balance is Rs. 690.');
  await expect(app.account.getByTestId('deposit-balance')).toHaveText('Your deposit balance: Rs. 690');

  await app.clickRowAction(other, 'Borrow');
  await app.expectToast(`You borrowed "${other}".`);
});
