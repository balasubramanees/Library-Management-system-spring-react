import {
  formatDate,
  plural,
  type AddBookResponse,
  type BookDto,
  type BookListResponse,
  type BorrowResponse,
  type ClockResponse,
  type MessageResponse,
  type MountProps,
  type WaitlistResponse,
} from '@library/contracts';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useAsyncAction } from './useAsyncAction.js';

type LoadState = { phase: 'loading' } | { phase: 'ready'; books: BookDto[]; message: string | null } | { phase: 'error'; message: string };

export function Catalog({ user, api, bus }: MountProps) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' });
  const { pending, run } = useAsyncAction(bus, 'catalog');

  const refresh = useCallback(async () => {
    try {
      const response = await api.get<BookListResponse>('/books');
      setState({ phase: 'ready', books: response.books, message: response.message });
    } catch (error) {
      setState({ phase: 'error', message: error instanceof Error ? error.message : 'Could not load books.' });
    }
  }, [api]);

  useEffect(() => {
    void refresh();
    return bus.on('library:changed', () => void refresh());
  }, [bus, refresh]);

  const borrow = (title: string) => run(`borrow:${title}`, () => api.post<BorrowResponse>('/books/borrow', { title }));
  const waitlist = (title: string) => run(`waitlist:${title}`, () => api.post<WaitlistResponse>('/books/waitlist', { title }));
  const giveBack = (title: string) => run(`return:${title}`, () => api.post<MessageResponse>('/books/return', { title }));

  return (
    <section className="cat" aria-labelledby="cat-title">
      <header className="cat-header">
        <h2 id="cat-title">Catalogue</h2>
        {state.phase === 'ready' && state.books.length > 0 && (
          <span className="cat-count">
            {state.books.filter((b) => b.status === 'available').length} of {state.books.length} available
          </span>
        )}
      </header>

      {user.isAdmin && <AddBookForm disabled={pending !== null} onAdd={(title) => run(`add:${title}`, () => api.post<AddBookResponse>('/books', { title }))} />}
      {user.isAdmin && <DemoClock api={api} busy={pending !== null} run={run} />}

      {state.phase === 'loading' && <p className="cat-muted">Loading books…</p>}
      {state.phase === 'error' && (
        <div className="cat-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="btn btn-ghost btn-small" onClick={() => void refresh()}>
            Try again
          </button>
        </div>
      )}
      {state.phase === 'ready' && state.books.length === 0 && (
        <p className="cat-empty" data-testid="catalog-empty">
          {state.message ?? 'No books are registered'}
        </p>
      )}
      {state.phase === 'ready' && state.books.length > 0 && (
        <ol className="cat-list" aria-label="Books">
          {state.books.map((book, index) => (
            <li key={book.id} className="cat-item" data-testid="book-row" data-title={book.title}>
              <span className="cat-main">
                <span className="cat-line" data-testid="book-line">
                  {index + 1}. {book.title} <span className={`cat-badge cat-badge-${book.status}`}>({book.status})</span>
                </span>
                <LoanDetails book={book} isAdmin={user.isAdmin} />
              </span>
              <span className="cat-actions">
                {user.isAdmin ? (
                  <span className="cat-waiting" data-testid="book-queue">
                    {book.waitlistLength > 0 ? `${book.waitlistLength} on wait list` : ''}
                  </span>
                ) : (
                  <BookAction
                    book={book}
                    busy={pending !== null}
                    onBorrow={() => borrow(book.title)}
                    onWaitlist={() => waitlist(book.title)}
                    onReturn={() => giveBack(book.title)}
                  />
                )}
              </span>
            </li>
          ))}
        </ol>
      )}

      {/* The admin is the librarian: catalogue management only, no lending actions. */}
      {!user.isAdmin && (
        <ByTitleForm disabled={pending !== null} onBorrow={borrow} onWaitlist={waitlist} onReturn={giveBack} />
      )}
    </section>
  );
}

/** Due date for the borrower; borrower, due date and overdue state for the admin. */
function LoanDetails({ book, isAdmin }: { book: BookDto; isAdmin: boolean }) {
  if (!book.dueDate) return null;
  const overdue = (book.overdueDays ?? 0) > 0;
  return (
    <span className={`cat-loan${overdue ? ' cat-loan-overdue' : ''}`} data-testid="loan-details">
      {isAdmin && book.borrowerName ? `${book.borrowerName} · ` : ''}
      {book.borrowedByMe ? 'You have it · ' : ''}due {formatDate(book.dueDate)}
      {overdue && ` · OVERDUE by ${plural(book.overdueDays ?? 0, 'day')}`}
    </span>
  );
}

/**
 * Admin-only demo control: moves the library's date forward so due dates, overdue blocks and late
 * fees can be demonstrated. Hidden when the backend has time travel disabled (404).
 */
function DemoClock(props: {
  api: MountProps['api'];
  busy: boolean;
  run: (key: string, action: () => Promise<{ message: string }>) => Promise<boolean>;
}) {
  const { api, busy, run } = props;
  const [today, setToday] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .get<ClockResponse>('/admin/clock')
      .then((clock) => setToday(clock.today))
      .catch(() => setToday(null));
  }, [api]);

  useEffect(() => load(), [load]);

  if (!today) return null;
  const advance = async (days: number) => {
    await run(`clock:${days}`, () => api.post<ClockResponse>('/admin/clock/advance', { days }));
    load();
  };
  return (
    <div className="cat-clock" role="group" aria-label="Demo clock">
      <span>
        Library date: <strong data-testid="library-date">{formatDate(today)}</strong>
        <span className="cat-muted-inline"> (demo: move time forward to see due dates and late fees)</span>
      </span>
      <span className="cat-clock-buttons">
        {[1, 7, 15].map((days) => (
          <button key={days} type="button" className="btn btn-ghost btn-small" disabled={busy} onClick={() => advance(days)}>
            +{plural(days, 'day')}
          </button>
        ))}
      </span>
    </div>
  );
}

function BookAction(props: { book: BookDto; busy: boolean; onBorrow: () => void; onWaitlist: () => void; onReturn: () => void }) {
  const { book, busy } = props;
  if (book.borrowedByMe) {
    return (
      <button type="button" className="btn btn-ghost btn-small" disabled={busy} onClick={props.onReturn}>
        Return
      </button>
    );
  }
  if (book.status === 'available') {
    return (
      <button type="button" className="btn btn-primary btn-small" disabled={busy} onClick={props.onBorrow}>
        Borrow
      </button>
    );
  }
  if (book.myWaitlistPosition !== null) {
    return <span className="cat-waiting">Wait list #{book.myWaitlistPosition}</span>;
  }
  return (
    <button type="button" className="btn btn-ghost btn-small" disabled={busy} onClick={props.onWaitlist}>
      Join wait list
    </button>
  );
}

function AddBookForm({ onAdd, disabled }: { onAdd: (title: string) => Promise<boolean>; disabled: boolean }) {
  const [title, setTitle] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!title.trim()) return;
    if (await onAdd(title)) setTitle('');
  }
  return (
    <form className="cat-form cat-admin" onSubmit={submit} aria-label="Add a book">
      <label htmlFor="cat-add-title">Add a book to the library</label>
      <div className="row">
        <input id="cat-add-title" value={title} maxLength={200} placeholder="Title" onChange={(e) => setTitle(e.target.value)} />
        <button type="submit" className="btn btn-primary" disabled={disabled || !title.trim()}>
          Add book
        </button>
      </div>
    </form>
  );
}

/** Command-style access by title, mirroring `borrow "..."` etc. from the CLI brief. */
function ByTitleForm(props: {
  disabled: boolean;
  onBorrow: (title: string) => Promise<boolean>;
  onWaitlist: (title: string) => Promise<boolean>;
  onReturn: (title: string) => Promise<boolean>;
}) {
  const [title, setTitle] = useState('');
  const blocked = props.disabled || !title.trim();
  const act = async (action: (t: string) => Promise<boolean>) => {
    if (await action(title)) setTitle('');
  };
  return (
    <form className="cat-form" onSubmit={(e) => e.preventDefault()} aria-label="Find a book by title">
      <label htmlFor="cat-by-title">Know the title? Act on it directly</label>
      <div className="row">
        <input id="cat-by-title" value={title} maxLength={200} placeholder="e.g. 1984" onChange={(e) => setTitle(e.target.value)} />
        <button type="button" className="btn btn-ghost" disabled={blocked} onClick={() => act(props.onBorrow)}>
          Borrow
        </button>
        <button type="button" className="btn btn-ghost" disabled={blocked} onClick={() => act(props.onWaitlist)}>
          Wait list
        </button>
        <button type="button" className="btn btn-ghost" disabled={blocked} onClick={() => act(props.onReturn)}>
          Return
        </button>
      </div>
    </form>
  );
}
