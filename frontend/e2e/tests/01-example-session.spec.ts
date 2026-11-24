import { expect, test } from '@playwright/test';
import { LibraryApp, libraryDateIn } from '../pages/library-app.js';

/**
 * The example session from the problem statement, performed through the browser UI, with the
 * lending extensions (cash deposit at registration, due dates, deposit balance).
 * Runs first, against a freshly started backend (clean state is part of the requirement).
 */
test('example session from the brief', async ({ page }) => {
  const app = new LibraryApp(page);
  const due = libraryDateIn(14);
  const balance = 'Your deposit balance: Rs. 500';
  await app.open();

  await test.step('admin manages the catalogue', async () => {
    await app.login('admin');
    await app.expectWelcome(['Hello, admin!', 'You have access to library management.']);
    await app.expectEmptyCatalogue();
    await app.addBook('The Great Gatsby');
    await app.addBook('1984');
    await app.expectBooks(['1. The Great Gatsby (available)', '2. 1984 (available)']);
    await app.logout('admin');
  });

  await test.step('Alice borrows 1984', async () => {
    await app.login('Alice');
    await app.expectWelcome(['Hello, Alice!', 'Rs. 500 received as deposit in cash.', "You don't have any books borrowed yet.", balance]);
    await expect(app.catalog.getByLabel('Add a book to the library')).toHaveCount(0);
    await app.expectBooks(['1. The Great Gatsby (available)', '2. 1984 (available)']);
    await app.clickRowAction('1984', 'Borrow');
    await app.expectToast(`You borrowed "1984". Please return it by ${due}.`);
    await app.expectBooks(['1. The Great Gatsby (available)', '2. 1984 (borrowed)']);
    await app.expectAccount(['Your borrowed books:', '1. 1984']);
    await expect(app.loanDue('1984')).toHaveText(`Due ${due} · 2 extensions left`);
    await app.logout('Alice');
  });

  await test.step('Bob hits the error cases, joins the wait list and borrows Gatsby', async () => {
    await app.login('Bob');
    await app.expectWelcome(['Hello, Bob!', 'Rs. 500 received as deposit in cash.', "You don't have any books borrowed yet.", balance]);
    await app.byTitle('Borrow', '1985');
    await app.expectToast('Sorry, "1985" is not registered.');
    await app.byTitle('Borrow', '1984');
    await app.expectToast('Sorry, "1984" is currently not available.');
    await app.byTitle('Wait list', '1985');
    await app.expectToast('Sorry, "1985" is not registered.');
    await app.clickRowAction('1984', 'Join wait list');
    await app.expectToast('You are added to the wait list of "1984", your position is 1.');
    await expect(app.row('1984')).toContainText('Wait list #1');
    await app.clickRowAction('The Great Gatsby', 'Borrow');
    await app.expectToast('You borrowed "The Great Gatsby".');
    await app.expectAccount(['Your borrowed books:', '1. The Great Gatsby', 'Your wait lists:', '1. 1984 - position 1']);
    await app.logout('Bob');
  });

  await test.step('Carol queues behind Bob', async () => {
    await app.login('Carol');
    await app.clickRowAction('1984', 'Join wait list');
    await app.expectToast('You are added to the wait list of "1984", your position is 2.');
    await app.expectAccount(["You don't have any books borrowed yet.", 'Your wait lists:', '1. 1984 - position 2']);
    await app.logout('Carol');
  });

  await test.step('Alice returns 1984, which goes straight to Bob', async () => {
    await app.login('Alice');
    await app.expectWelcome(['Hello, Alice!', 'Your borrowed books:', `1. 1984 - due ${due}`, balance]);
    await app.returnFromAccount('1984');
    await app.expectToast('You returned "1984".');
    await app.expectBooks(['1. The Great Gatsby (borrowed)', '2. 1984 (borrowed)']);
    await app.expectAccount(["You don't have any books borrowed yet."]);
    await app.logout('Alice');
  });

  await test.step('Bob is told he got the book', async () => {
    await app.login('Bob');
    await app.expectWelcome([
      'Hello, Bob!',
      `You got the book "1984" from your waitlist. Please return it by ${due}.`,
      'Your borrowed books:',
      `1. The Great Gatsby - due ${due}`,
      `2. 1984 - due ${due}`,
      balance,
    ]);
    await app.logout('Bob');
  });

  await test.step('Carol moved up and cannot return what she did not borrow', async () => {
    await app.login('Carol');
    await app.expectWelcome(['Hello, Carol!', "You don't have any books borrowed yet.", 'Your wait lists:', '1. 1984 - position 1', balance]);
    await app.expectBooks(['1. The Great Gatsby (borrowed)', '2. 1984 (borrowed)']);
    await app.byTitle('Return', 'The Great Gatsby');
    await app.expectToast('Sorry, you didn\'t borrow "The Great Gatsby".');
    await app.logout('Carol');
  });
});
