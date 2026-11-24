import {
  formatDate,
  formatRupees,
  plural,
  type AdminDashboardResponse,
  type AdminLoanDto,
  type MountProps,
} from '@library/contracts';
import { useCallback, useEffect, useState } from 'react';

type LoadState =
  | { phase: 'loading' }
  | { phase: 'ready'; data: AdminDashboardResponse }
  | { phase: 'error'; message: string; last: AdminDashboardResponse | null };

type LoanFilter = 'all' | 'attention' | 'OVERDUE' | 'DUE_SOON';

/** Members act from their own sessions, so the librarian's view is also refreshed periodically. */
const POLL_MS = 30_000;

/** The librarian's overview: members, current loans, and which loans are overdue or due soon. */
export function Dashboard({ api, bus }: MountProps) {
  const [state, setState] = useState<LoadState>({ phase: 'loading' });
  const [filter, setFilter] = useState<LoanFilter>('attention');

  const refresh = useCallback(async () => {
    try {
      const data = await api.get<AdminDashboardResponse>('/admin/dashboard');
      setState({ phase: 'ready', data });
    } catch (error) {
      setState((current) => ({
        phase: 'error',
        message: error instanceof Error ? error.message : 'Could not load the dashboard.',
        last: current.phase === 'ready' ? current.data : current.phase === 'error' ? current.last : null,
      }));
    }
  }, [api]);

  useEffect(() => {
    void refresh();
    const off = bus.on('library:changed', () => void refresh());
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh();
    }, POLL_MS);
    return () => {
      off();
      clearInterval(timer);
    };
  }, [bus, refresh]);

  const data = state.phase === 'ready' ? state.data : state.phase === 'error' ? state.last : null;

  return (
    <section className="adm" aria-labelledby="adm-title" data-testid="admin-dashboard">
      <header className="adm-header">
        <div>
          <h2 id="adm-title">Library dashboard</h2>
          {data && <p className="adm-sub">As of {formatDate(data.today)}</p>}
        </div>
        <button type="button" className="btn btn-ghost btn-small" onClick={() => void refresh()} aria-label="Refresh dashboard">
          ↻
        </button>
      </header>

      {state.phase === 'loading' && <p className="adm-muted">Loading…</p>}
      {state.phase === 'error' && (
        <div className="adm-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="btn btn-ghost btn-small" onClick={() => void refresh()}>
            Try again
          </button>
        </div>
      )}

      {data && (
        <>
          <Summary data={data} />
          <Loans data={data} filter={filter} onFilter={setFilter} />
          <Members data={data} />
        </>
      )}
    </section>
  );
}

function Summary({ data }: { data: AdminDashboardResponse }) {
  const s = data.summary;
  return (
    <ul className="adm-tiles" aria-label="Summary">
      <Tile label="Members" value={s.members} detail={`${s.membersWithBooks} with books out`} testId="tile-members" />
      <Tile label="Books out" value={`${s.borrowedBooks} / ${s.totalBooks}`} detail={`${s.availableBooks} available`} testId="tile-books" />
      <Tile
        label="Overdue"
        value={s.overdueLoans}
        detail={s.lateFeesOutstanding > 0 ? `${formatRupees(s.lateFeesOutstanding)} in late fees so far` : 'None late'}
        tone={s.overdueLoans > 0 ? 'danger' : undefined}
        testId="tile-overdue"
      />
      <Tile
        label="Due soon"
        value={s.dueSoonLoans}
        detail={`Within ${plural(data.dueSoonDays, 'day')}`}
        tone={s.dueSoonLoans > 0 ? 'warn' : undefined}
        testId="tile-due-soon"
      />
      <Tile label="Wait lists" value={s.waitlistEntries} detail={plural(s.waitlistEntries, 'request')} testId="tile-waitlists" />
      <Tile
        label="Deposits held"
        value={formatRupees(s.depositsHeld)}
        detail={s.blockedMembers > 0 ? `${plural(s.blockedMembers, 'member')} blocked` : 'No one blocked'}
        tone={s.blockedMembers > 0 ? 'warn' : undefined}
        testId="tile-deposits"
      />
    </ul>
  );
}

function Tile(props: { label: string; value: number | string; detail: string; tone?: 'warn' | 'danger' | undefined; testId: string }) {
  return (
    <li className={`adm-tile${props.tone ? ` adm-tile-${props.tone}` : ''}`} data-testid={props.testId}>
      <span className="adm-tile-label">{props.label}</span>
      <span className="adm-tile-value">{props.value}</span>
      <span className="adm-tile-detail">{props.detail}</span>
    </li>
  );
}

const FILTERS: { key: LoanFilter; label: string; matches: (loan: AdminLoanDto) => boolean }[] = [
  { key: 'attention', label: 'Needs attention', matches: (l) => l.state !== 'ON_TIME' },
  { key: 'OVERDUE', label: 'Overdue', matches: (l) => l.state === 'OVERDUE' },
  { key: 'DUE_SOON', label: 'Due soon', matches: (l) => l.state === 'DUE_SOON' },
  { key: 'all', label: 'All loans', matches: () => true },
];

function Loans({ data, filter, onFilter }: { data: AdminDashboardResponse; filter: LoanFilter; onFilter: (f: LoanFilter) => void }) {
  const active = FILTERS.find((f) => f.key === filter) ?? FILTERS[0]!;
  const loans = data.loans.filter(active.matches);
  return (
    <>
      <div className="adm-section-head">
        <h3 className="adm-heading">Books out</h3>
        <div className="adm-filters" role="group" aria-label="Filter loans">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              className={`adm-chip${f.key === filter ? ' adm-chip-on' : ''}`}
              aria-pressed={f.key === filter}
              onClick={() => onFilter(f.key)}
            >
              {f.label} <span className="adm-chip-count">{data.loans.filter(f.matches).length}</span>
            </button>
          ))}
        </div>
      </div>
      {loans.length === 0 ? (
        <p className="adm-muted">
          {data.loans.length === 0
            ? 'No books are borrowed right now.'
            : filter === 'attention'
              ? `Nothing overdue or due in the next ${plural(data.dueSoonDays, 'day')}.`
              : 'No loans match this filter.'}
        </p>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table" data-testid="admin-loans">
            <thead>
              <tr>
                <th scope="col">Book</th>
                <th scope="col">Borrowed by</th>
                <th scope="col">Due</th>
                <th scope="col">Status</th>
                <th scope="col" className="adm-num">Extensions</th>
                <th scope="col" className="adm-num">Waiting</th>
              </tr>
            </thead>
            <tbody>
              {loans.map((loan) => (
                <tr key={loan.bookId}>
                  <td className="adm-title">{loan.title}</td>
                  <td>{loan.memberName}</td>
                  <td className="adm-nowrap">{formatDate(loan.dueDate)}</td>
                  <td>
                    <LoanBadge loan={loan} />
                  </td>
                  <td className="adm-num">{loan.extensionsUsed}</td>
                  <td className="adm-num">{loan.waitlistLength}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function LoanBadge({ loan }: { loan: AdminLoanDto }) {
  if (loan.state === 'OVERDUE') {
    return (
      <span className="adm-badge adm-badge-danger">
        Overdue {plural(-loan.daysUntilDue, 'day')} · {formatRupees(loan.lateFeeSoFar)}
      </span>
    );
  }
  const when = loan.daysUntilDue === 0 ? 'Due today' : loan.daysUntilDue === 1 ? 'Due tomorrow' : `Due in ${loan.daysUntilDue} days`;
  return <span className={`adm-badge ${loan.state === 'DUE_SOON' ? 'adm-badge-warn' : 'adm-badge-ok'}`}>{when}</span>;
}

function Members({ data }: { data: AdminDashboardResponse }) {
  return (
    <>
      <h3 className="adm-heading">Members</h3>
      {data.members.length === 0 ? (
        <p className="adm-muted">No members have registered yet. Members register the first time they log in.</p>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table" data-testid="admin-members">
            <thead>
              <tr>
                <th scope="col">Member</th>
                <th scope="col" className="adm-num">Books</th>
                <th scope="col" className="adm-num">Overdue</th>
                <th scope="col" className="adm-num">Due soon</th>
                <th scope="col" className="adm-num">Wait lists</th>
                <th scope="col" className="adm-num">Deposit</th>
                <th scope="col">Standing</th>
              </tr>
            </thead>
            <tbody>
              {data.members.map((m) => (
                <tr key={m.id}>
                  <td className="adm-title">{m.name}</td>
                  <td className="adm-num">{m.booksBorrowed}</td>
                  <td className={`adm-num${m.overdue > 0 ? ' adm-danger' : ''}`}>{m.overdue}</td>
                  <td className={`adm-num${m.dueSoon > 0 ? ' adm-warn' : ''}`}>{m.dueSoon}</td>
                  <td className="adm-num">{m.waitlists}</td>
                  <td className={`adm-num adm-nowrap${m.balance <= 0 ? ' adm-danger' : ''}`}>{formatRupees(m.balance)}</td>
                  <td>
                    {m.blockedReason ? (
                      <span className="adm-badge adm-badge-danger">Blocked: {m.blockedReason}</span>
                    ) : (
                      <span className="adm-badge adm-badge-ok">Can borrow</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
