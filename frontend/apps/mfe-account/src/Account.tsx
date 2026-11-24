import {
  formatDate,
  formatRupees,
  plural,
  type DepositResponse,
  type ExtendResponse,
  type LoanDto,
  type MountProps,
  type ReturnResponse,
  type StatusResponse,
} from '@library/contracts';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useAsyncAction } from './useAsyncAction.js';

type LoadState = { phase: 'loading' } | { phase: 'ready'; status: StatusResponse } | { phase: 'error'; message: string };

/** `status`: the member's loans (with due dates), wait lists and deposit account. */
export function Account({ user, api, bus }: MountProps) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' });
  const { pending, run } = useAsyncAction(bus, 'account');

  const refresh = useCallback(async () => {
    try {
      const status = await api.get<StatusResponse>('/me/status');
      setState({ phase: 'ready', status });
      // Lines open with one line per notification; those are shown as toasts, so share only the status itself.
      bus.emit('account:status', { lines: status.lines.slice(status.notifications.length) });
      // Things that happened while the user was logged in (e.g. another member returned a book).
      for (const notification of status.notifications) {
        bus.emit('toast:show', { kind: 'success', message: notification.message });
      }
    } catch (error) {
      setState({ phase: 'error', message: error instanceof Error ? error.message : 'Could not load your account.' });
    }
  }, [api, bus]);

  useEffect(() => {
    void refresh();
    return bus.on('library:changed', () => void refresh());
  }, [bus, refresh]);

  const giveBack = (title: string) => run(`return:${title}`, () => api.post<ReturnResponse>('/books/return', { title }));
  const extend = (title: string) => run(`extend:${title}`, () => api.post<ExtendResponse>('/books/extend', { title }));
  const deposit = (amount: number) => run(`deposit:${amount}`, () => api.post<DepositResponse>('/me/deposit', { amount }));

  return (
    <section className="acc" aria-labelledby="acc-title">
      <header className="acc-header">
        <h2 id="acc-title">My account</h2>
        <button type="button" className="btn btn-ghost btn-small" onClick={() => void refresh()} aria-label="Refresh my account">
          ↻
        </button>
      </header>
      <p className="acc-sub">Signed in as {user.name}</p>

      {state.phase === 'loading' && <p className="acc-muted">Loading…</p>}
      {state.phase === 'error' && (
        <div className="acc-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="btn btn-ghost btn-small" onClick={() => void refresh()}>
            Try again
          </button>
        </div>
      )}
      {state.phase === 'ready' && (
        <div data-testid="account-status">
          {state.status.account?.blocked && (
            <p className="acc-blocked" role="alert" data-testid="account-blocked">
              {state.status.account.blockedReason}
            </p>
          )}

          {state.status.borrowed.length === 0 ? (
            <p className="acc-muted" data-testid="status-line">
              You don't have any books borrowed yet.
            </p>
          ) : (
            <>
              <h3 className="acc-heading" data-testid="status-line">
                Your borrowed books:
              </h3>
              <ol className="acc-list" aria-label="Your borrowed books">
                {state.status.borrowed.map((loan, index) => (
                  <LoanRow
                    key={loan.bookId}
                    index={index}
                    loan={loan}
                    busy={pending !== null}
                    onReturn={() => giveBack(loan.title)}
                    onExtend={() => extend(loan.title)}
                    extensionDays={state.status.policy.extensionDays}
                  />
                ))}
              </ol>
            </>
          )}

          {state.status.waitlists.length > 0 && (
            <>
              <h3 className="acc-heading" data-testid="status-line">
                Your wait lists:
              </h3>
              <ol className="acc-list" aria-label="Your wait lists">
                {state.status.waitlists.map((entry, index) => (
                  <li key={entry.bookId} className="acc-item" data-testid="waitlist-row">
                    <span className="acc-line" data-testid="status-line">
                      {index + 1}. {entry.title} - position {entry.position}
                    </span>
                  </li>
                ))}
              </ol>
            </>
          )}

          {state.status.account && (
            <DepositSection
              balance={state.status.account.balance}
              ledger={state.status.account.ledger}
              minimum={state.status.policy.minimumTopUp}
              initial={state.status.policy.initialDeposit}
              busy={pending !== null}
              onDeposit={deposit}
            />
          )}

          <p className="acc-rules">
            Loans last {plural(state.status.policy.loanPeriodDays, 'day')}. You can move a return date{' '}
            {state.status.policy.maxExtensions} times by {plural(state.status.policy.extensionDays, 'day')}. Late returns cost{' '}
            {formatRupees(state.status.policy.lateFeePerDay)} per day, taken from your deposit.
          </p>
        </div>
      )}
    </section>
  );
}

function LoanRow(props: {
  index: number;
  loan: LoanDto;
  busy: boolean;
  extensionDays: number;
  onReturn: () => void;
  onExtend: () => void;
}) {
  const { index, loan, busy } = props;
  const overdue = loan.overdueDays > 0;
  const canExtend = !overdue && loan.extensionsLeft > 0;
  const extendHint = overdue
    ? 'Overdue books cannot be extended'
    : loan.extensionsLeft === 0
      ? 'The return date has already been moved twice'
      : `Move the return date by ${plural(props.extensionDays, 'day')}`;
  return (
    <li className={`acc-item acc-loan${overdue ? ' acc-loan-overdue' : ''}`} data-testid="loan-row">
      <span className="acc-loan-main">
        <span className="acc-line" data-testid="status-line">
          {index + 1}. {loan.title}
        </span>
        <span className="acc-due" data-testid="loan-due">
          Due {formatDate(loan.dueDate)}
          {overdue
            ? ` · OVERDUE by ${plural(loan.overdueDays, 'day')} · late fee so far ${formatRupees(loan.lateFeeSoFar)}`
            : ` · ${plural(loan.extensionsLeft, 'extension')} left`}
        </span>
      </span>
      <span className="acc-actions">
        <button
          type="button"
          className="btn btn-ghost btn-small"
          disabled={busy || !canExtend}
          title={extendHint}
          onClick={props.onExtend}
          aria-label={`Extend ${loan.title}`}
        >
          Extend
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-small"
          disabled={busy}
          onClick={props.onReturn}
          aria-label={`Return ${loan.title}`}
        >
          Return
        </button>
      </span>
    </li>
  );
}

function DepositSection(props: {
  balance: number;
  ledger: { date: string; description: string; amount: number }[];
  minimum: number;
  initial: number;
  busy: boolean;
  onDeposit: (amount: number) => Promise<boolean>;
}) {
  const [amount, setAmount] = useState(String(props.initial));
  const value = Number(amount);
  const valid = Number.isInteger(value) && value >= props.minimum;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (valid) await props.onDeposit(value);
  }

  return (
    <div className="acc-deposit">
      <h3 className="acc-heading">Deposit</h3>
      <p className={`acc-balance${props.balance <= 0 ? ' acc-balance-low' : ''}`} data-testid="deposit-balance">
        Your deposit balance: {formatRupees(props.balance)}
      </p>
      <form className="row acc-topup" onSubmit={submit} aria-label="Add deposit">
        <label htmlFor="acc-deposit-amount" className="sr-only">
          Deposit amount in rupees
        </label>
        <input
          id="acc-deposit-amount"
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
          aria-describedby="acc-deposit-hint"
        />
        <button type="submit" className="btn btn-ghost btn-small" disabled={props.busy || !valid}>
          Add deposit (cash)
        </button>
      </form>
      <p id="acc-deposit-hint" className="acc-muted-small">
        Minimum {formatRupees(props.minimum)}, paid in cash at the desk.
      </p>
      {props.ledger.length > 0 && (
        <details className="acc-ledger">
          <summary>Deposit history</summary>
          <ul>
            {props.ledger.map((entry, i) => (
              <li key={i}>
                <span>
                  {formatDate(entry.date)} · {entry.description}
                </span>
                <span className={entry.amount < 0 ? 'acc-negative' : undefined}>{formatRupees(entry.amount)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
