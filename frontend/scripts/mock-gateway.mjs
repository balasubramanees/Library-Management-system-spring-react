/**
 * In-memory stand-in for the Spring gateway, implementing the same HTTP contract
 * (see packages/contracts/src/api.ts). It exists so the frontends can be developed and
 * end-to-end tested without the JVM services (`npm run dev:mock`, `npm run e2e:mock`).
 *
 * It is NOT the system of record: the business rules are owned and unit-tested in
 * library-service. When the contract or a rule changes, update both.
 *
 * Test hook: POST /__mock/outage {"requests": n} makes the next n API calls answer 503,
 * to exercise the client-side circuit breaker.
 */
import { randomUUID } from 'node:crypto';
import http from 'node:http';

const POLICY = { loanPeriodDays: 14, extensionDays: 7, maxExtensions: 2, lateFeePerDay: 10, initialDeposit: 500, minimumTopUp: 100 };
const DAY = 86_400_000;
const DUE_SOON_DAYS = 3;
const ADMIN_CANNOT_LEND = 'Sorry, the admin account manages the library and cannot borrow, return or join wait lists.';

export function createMockBackend() {
  const users = new Map(); // key -> { id, name }
  const sessions = new Map(); // token -> userId
  const books = []; // { id, title, key, loan: {memberId, memberName, borrowedOn, dueDate, extensions} | null, waitlist: [{id,name}] }
  const inbox = new Map(); // userId -> notification[]
  const accounts = new Map(); // userId -> { balance, ledger[] }
  let offsetDays = 0;
  let outage = 0;

  // --- dates (calendar days in Asia/Kolkata, like the real service) ---
  const today = () => new Date(Date.now() + offsetDays * DAY).toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
  const addDays = (iso, days) => new Date(Date.parse(`${iso}T00:00:00Z`) + days * DAY).toISOString().slice(0, 10);
  const daysBetween = (from, to) => Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY);
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  /** "08 Dec 2026", identical to the Java service's "dd MMM yyyy". */
  const fmt = (iso) => {
    const [y, m, d] = iso.split('-');
    return `${d} ${MONTHS[Number(m) - 1]} ${y}`;
  };
  const rs = (n) => (n < 0 ? `-Rs. ${Math.abs(n)}` : `Rs. ${n}`);
  const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
  const overdueDays = (loan) => Math.max(0, daysBetween(loan.dueDate, today()));

  const norm = (raw) => (typeof raw === 'string' ? raw.normalize('NFC').trim().replace(/\s+/g, ' ') : '');
  const fail = (status, code, message) => ({ status, body: { error: { code, message, requestId: randomUUID() } } });
  const ok = (body, status = 200) => ({ status, body });
  const notify = (userId, type, message, book = null) => {
    const queue = inbox.get(userId) ?? [];
    queue.push({ id: randomUUID(), type, bookId: book?.id ?? null, title: book?.title ?? null, message, createdAt: new Date().toISOString() });
    inbox.set(userId, queue);
  };

  function accountOf(user) {
    if (!accounts.has(user.id)) {
      accounts.set(user.id, { balance: POLICY.initialDeposit, ledger: [{ date: today(), description: 'Deposit received in cash', amount: POLICY.initialDeposit }] });
      notify(user.id, 'DEPOSIT_RECEIVED', `${rs(POLICY.initialDeposit)} received as deposit in cash.`);
    }
    return accounts.get(user.id);
  }

  function blockedReason(user, account) {
    const overdue = books.filter((b) => b.loan?.memberId === user.id && overdueDays(b.loan) > 0).length;
    if (overdue > 0) {
      return `Sorry, you have ${plural(overdue, 'overdue book')}. Please return ${overdue === 1 ? 'it' : 'them'} before borrowing or joining a wait list.`;
    }
    if (account.balance <= 0) {
      return `Sorry, your deposit balance is ${rs(account.balance)}. Please top up your deposit before borrowing or joining a wait list.`;
    }
    return null;
  }

  const bookDto = (b, user) => {
    const mine = b.loan?.memberId === user.id;
    const admin = user.id === 'admin';
    const show = b.loan && (mine || admin);
    return {
      id: b.id,
      title: b.title,
      status: b.loan ? 'borrowed' : 'available',
      borrowedByMe: mine,
      myWaitlistPosition: b.waitlist.some((m) => m.id === user.id) ? b.waitlist.findIndex((m) => m.id === user.id) + 1 : null,
      waitlistLength: b.waitlist.length,
      borrowerName: show && admin ? b.loan.memberName : null,
      dueDate: show ? b.loan.dueDate : null,
      overdueDays: show ? overdueDays(b.loan) : null,
    };
  };

  function title(body) {
    const value = norm(body?.title);
    if (!value) return { error: fail(400, 'VALIDATION_ERROR', 'Book title must not be empty.') };
    if (value.length > 200) return { error: fail(400, 'VALIDATION_ERROR', 'Book title must be at most 200 characters.') };
    return { value, book: books.find((b) => b.key === value.toLowerCase()) };
  }

  function status(user) {
    const admin = user.id === 'admin';
    const account = admin ? null : accountOf(user);
    const notifications = inbox.get(user.id) ?? [];
    inbox.delete(user.id);
    const borrowed = books
      .filter((b) => b.loan?.memberId === user.id)
      .map((b) => ({
        bookId: b.id,
        title: b.title,
        borrowedOn: b.loan.borrowedOn,
        dueDate: b.loan.dueDate,
        extensionsUsed: b.loan.extensions,
        extensionsLeft: POLICY.maxExtensions - b.loan.extensions,
        overdueDays: overdueDays(b.loan),
        lateFeeSoFar: overdueDays(b.loan) * POLICY.lateFeePerDay,
      }));
    const waitlists = books
      .filter((b) => b.waitlist.some((m) => m.id === user.id))
      .map((b) => ({ bookId: b.id, title: b.title, position: b.waitlist.findIndex((m) => m.id === user.id) + 1 }));
    const reason = account ? blockedReason(user, account) : null;
    const lines = notifications.map((n) => n.message);
    if (borrowed.length === 0) lines.push("You don't have any books borrowed yet.");
    else {
      lines.push('Your borrowed books:');
      borrowed.forEach((l, i) => {
        let line = `${i + 1}. ${l.title} - due ${fmt(l.dueDate)}`;
        if (l.overdueDays > 0) line += ` (OVERDUE by ${plural(l.overdueDays, 'day')}, late fee so far ${rs(l.lateFeeSoFar)})`;
        lines.push(line);
      });
    }
    if (waitlists.length) lines.push('Your wait lists:', ...waitlists.map((w, i) => `${i + 1}. ${w.title} - position ${w.position}`));
    if (account) {
      lines.push(`Your deposit balance: ${rs(account.balance)}`);
      if (reason) lines.push(reason);
    }
    return {
      today: today(),
      borrowed,
      waitlists,
      notifications,
      account: account && { balance: account.balance, blocked: reason !== null, blockedReason: reason, ledger: account.ledger },
      policy: POLICY,
      lines,
    };
  }

  /** GET /admin/dashboard, mirroring LibraryService#dashboard. */
  function dashboard() {
    const now = today();
    const loans = books
      .filter((b) => b.loan)
      .map((b) => {
        const daysUntilDue = daysBetween(now, b.loan.dueDate);
        const state = daysUntilDue < 0 ? 'OVERDUE' : daysUntilDue <= DUE_SOON_DAYS ? 'DUE_SOON' : 'ON_TIME';
        return {
          bookId: b.id,
          title: b.title,
          memberId: b.loan.memberId,
          memberName: b.loan.memberName,
          borrowedOn: b.loan.borrowedOn,
          dueDate: b.loan.dueDate,
          daysUntilDue,
          state,
          extensionsUsed: b.loan.extensions,
          waitlistLength: b.waitlist.length,
          lateFeeSoFar: overdueDays(b.loan) * POLICY.lateFeePerDay,
        };
      })
      .sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.title.localeCompare(b.title));
    const members = [...accounts.entries()]
      .map(([id, account]) => {
        const own = loans.filter((l) => l.memberId === id);
        const overdue = own.filter((l) => l.state === 'OVERDUE').length;
        return {
          id,
          name: users.get(id)?.name ?? id,
          booksBorrowed: own.length,
          overdue,
          dueSoon: own.filter((l) => l.state === 'DUE_SOON').length,
          waitlists: books.filter((b) => b.waitlist.some((m) => m.id === id)).length,
          balance: account.balance,
          blockedReason: overdue > 0 ? plural(overdue, 'overdue book') : account.balance <= 0 ? `Deposit balance is ${rs(account.balance)}` : null,
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
    return {
      today: now,
      dueSoonDays: DUE_SOON_DAYS,
      summary: {
        totalBooks: books.length,
        availableBooks: books.length - loans.length,
        borrowedBooks: loans.length,
        members: members.length,
        membersWithBooks: members.filter((m) => m.booksBorrowed > 0).length,
        overdueLoans: loans.filter((l) => l.state === 'OVERDUE').length,
        dueSoonLoans: loans.filter((l) => l.state === 'DUE_SOON').length,
        waitlistEntries: books.reduce((n, b) => n + b.waitlist.length, 0),
        blockedMembers: members.filter((m) => m.blockedReason).length,
        depositsHeld: members.reduce((n, m) => n + m.balance, 0),
        lateFeesOutstanding: loans.reduce((n, l) => n + l.lateFeeSoFar, 0),
      },
      members,
      loans,
    };
  }

  function handle(method, path, headers, body) {
    if (method === 'POST' && path === '/__mock/outage') {
      outage = Number(body?.requests ?? 0);
      return ok({ outage });
    }
    if (outage > 0) {
      outage--;
      return fail(503, 'SERVICE_UNAVAILABLE', 'The library service is temporarily unavailable. Please try again in a few seconds.');
    }

    if (method === 'POST' && path === '/api/sessions') {
      const name = norm(body?.name);
      if (!name) return fail(400, 'VALIDATION_ERROR', 'User name must not be empty.');
      if (name.length > 50) return fail(400, 'VALIDATION_ERROR', 'User name must be at most 50 characters.');
      const key = name.toLowerCase();
      const created = !users.has(key);
      if (created) users.set(key, { id: key, name });
      const user = users.get(key);
      const token = randomUUID();
      sessions.set(token, key);
      const isAdmin = key === 'admin';
      const messages = [`Hello, ${user.name}!`, ...(isAdmin ? ['You have access to library management.'] : [])];
      return ok({ token, user: { ...user, isAdmin }, created, messages }, created ? 201 : 200);
    }

    const token = (headers.authorization ?? '').replace(/^Bearer\s+/i, '');
    const me = sessions.get(token);
    if (!me) return fail(401, 'UNAUTHENTICATED', 'Please log in first.');
    const user = users.get(me);
    const admin = me === 'admin';

    if (path === '/api/sessions/current' && method === 'GET') return ok({ ...user, isAdmin: admin });
    if (path === '/api/sessions/current' && method === 'DELETE') {
      sessions.delete(token);
      return ok({ message: `Goodbye, ${user.name}!` });
    }
    if (path === '/api/me/status' && method === 'GET') return ok(status(user));
    if (path === '/api/me/deposit' && method === 'POST') {
      if (admin) return fail(403, 'FORBIDDEN', ADMIN_CANNOT_LEND);
      const amount = body?.amount;
      if (!Number.isInteger(amount) || amount < POLICY.minimumTopUp || amount > 100_000) {
        return fail(400, 'VALIDATION_ERROR', `Deposit must be between ${rs(POLICY.minimumTopUp)} and Rs. 100000.`);
      }
      const account = accountOf(user);
      account.balance += amount;
      account.ledger.push({ date: today(), description: 'Deposit received in cash', amount });
      return ok({ message: `${rs(amount)} received as deposit in cash. Your deposit balance is ${rs(account.balance)}.`, balance: account.balance });
    }
    if (path === '/api/admin/clock' || path === '/api/admin/clock/advance') {
      if (!admin) return fail(403, 'FORBIDDEN', 'Sorry, only the admin can change the library date.');
      if (method === 'GET') return ok({ today: today(), message: `Library date: ${fmt(today())}.` });
      const days = body?.days;
      if (!Number.isInteger(days) || days < 1 || days > 365) return fail(400, 'VALIDATION_ERROR', 'Days must be between 1 and 365.');
      offsetDays += days;
      return ok({ today: today(), message: `Library date moved forward ${plural(days, 'day')} to ${fmt(today())}.` });
    }
    if (path === '/api/admin/dashboard' && method === 'GET') {
      if (!admin) return fail(403, 'FORBIDDEN', 'Sorry, only the admin can see the library dashboard.');
      return ok(dashboard());
    }
    if (path === '/api/books' && method === 'GET') {
      return ok({ books: books.map((b) => bookDto(b, user)), message: books.length ? null : 'No books are registered' });
    }
    if (path === '/api/books' && method === 'POST') {
      if (!admin) return fail(403, 'FORBIDDEN', 'Sorry, only the admin can add books to the library.');
      const t = title(body);
      if (t.error) return t.error;
      if (t.book) return fail(409, 'BOOK_ALREADY_EXISTS', `Sorry, "${t.book.title}" is already registered in the library.`);
      const book = { id: randomUUID(), title: t.value, key: t.value.toLowerCase(), loan: null, waitlist: [] };
      books.push(book);
      return ok({ message: `Book "${book.title}" has been added to the library.`, book: bookDto(book, user) }, 201);
    }
    const commands = ['/api/books/borrow', '/api/books/return', '/api/books/waitlist', '/api/books/extend'];
    if (method === 'POST' && commands.includes(path)) {
      if (admin) return fail(403, 'FORBIDDEN', ADMIN_CANNOT_LEND);
      const t = title(body);
      if (t.error) return t.error;
      const b = t.book;
      if (!b) return fail(404, 'BOOK_NOT_FOUND', `Sorry, "${t.value}" is not registered.`);
      const account = accountOf(user);
      const mine = b.loan?.memberId === me;

      if (path.endsWith('/borrow')) {
        if (mine) return fail(409, 'ALREADY_BORROWED', `You have already borrowed "${b.title}".`);
        if (b.loan) return fail(409, 'BOOK_NOT_AVAILABLE', `Sorry, "${b.title}" is currently not available.`);
        const reason = blockedReason(user, account);
        if (reason) return fail(409, 'BORROWING_BLOCKED', reason);
        b.loan = { memberId: me, memberName: user.name, borrowedOn: today(), dueDate: addDays(today(), POLICY.loanPeriodDays), extensions: 0 };
        return ok({ message: `You borrowed "${b.title}". Please return it by ${fmt(b.loan.dueDate)}.`, dueDate: b.loan.dueDate });
      }
      if (path.endsWith('/extend')) {
        if (!mine) return fail(409, 'NOT_BORROWED_BY_USER', `Sorry, you didn't borrow "${b.title}".`);
        if (overdueDays(b.loan) > 0) {
          return fail(409, 'EXTENSION_NOT_ALLOWED', `Sorry, "${b.title}" is already overdue (due ${fmt(b.loan.dueDate)}) and cannot be extended. Please return it.`);
        }
        if (b.loan.extensions >= POLICY.maxExtensions) {
          return fail(409, 'EXTENSION_NOT_ALLOWED', `Sorry, the return date of "${b.title}" has already been moved ${POLICY.maxExtensions} times, which is the maximum. Please return it by ${fmt(b.loan.dueDate)}.`);
        }
        b.loan.extensions++;
        b.loan.dueDate = addDays(b.loan.dueDate, POLICY.extensionDays);
        const left = POLICY.maxExtensions - b.loan.extensions;
        return ok({
          message: `The return date of "${b.title}" is now ${fmt(b.loan.dueDate)}. ${left === 0 ? 'This was the last extension.' : `${plural(left, 'extension')} left.`}`,
          dueDate: b.loan.dueDate,
          extensionsLeft: left,
        });
      }
      if (path.endsWith('/return')) {
        if (!mine) return fail(409, 'NOT_BORROWED_BY_USER', `Sorry, you didn't borrow "${b.title}".`);
        const late = overdueDays(b.loan);
        const fee = late * POLICY.lateFeePerDay;
        if (fee > 0) {
          account.balance -= fee;
          account.ledger.push({ date: today(), description: `Late fee for "${b.title}" (${plural(late, 'day')})`, amount: -fee });
        }
        const next = b.waitlist.shift();
        b.loan = next
          ? { memberId: next.id, memberName: next.name, borrowedOn: today(), dueDate: addDays(today(), POLICY.loanPeriodDays), extensions: 0 }
          : null;
        if (next) notify(next.id, 'WAITLIST_ALLOCATED', `You got the book "${b.title}" from your waitlist. Please return it by ${fmt(b.loan.dueDate)}.`, b);
        const message = `You returned "${b.title}".${fee > 0 ? ` It was ${plural(late, 'day')} late: ${rs(fee)} late fee deducted from your deposit (balance ${rs(account.balance)}).` : ''}`;
        return ok({ message, lateFee: fee, balance: account.balance });
      }
      // waitlist
      if (!b.loan) return fail(409, 'BOOK_AVAILABLE', `"${b.title}" is available right now - borrow it instead of joining the wait list.`);
      if (mine) return fail(409, 'ALREADY_BORROWED', `You have already borrowed "${b.title}".`);
      const position = b.waitlist.findIndex((m) => m.id === me);
      if (position >= 0) return fail(409, 'ALREADY_WAITLISTED', `You are already on the wait list of "${b.title}", your position is ${position + 1}.`);
      const reason = blockedReason(user, account);
      if (reason) return fail(409, 'BORROWING_BLOCKED', reason);
      b.waitlist.push({ id: me, name: user.name });
      return ok({ message: `You are added to the wait list of "${b.title}", your position is ${b.waitlist.length}.`, position: b.waitlist.length });
    }
    return fail(404, 'NOT_FOUND', 'Resource not found.');
  }

  return { handle };
}

export function startMockGateway({ port = 8080, host = '127.0.0.1' } = {}) {
  const backend = createMockBackend();
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => (raw += chunk));
    req.on('end', () => {
      let body;
      try {
        body = raw ? JSON.parse(raw) : undefined;
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: { code: 'VALIDATION_ERROR', message: 'Request body is missing or is not valid JSON.', requestId: 'mock' } }));
      }
      const path = (req.url ?? '/').split('?')[0];
      const { status, body: out } = backend.handle(req.method ?? 'GET', path, req.headers, body);
      res.writeHead(status, { 'Content-Type': 'application/json', 'X-Request-Id': req.headers['x-request-id'] ?? randomUUID() });
      res.end(JSON.stringify(out));
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve(server));
  });
}
