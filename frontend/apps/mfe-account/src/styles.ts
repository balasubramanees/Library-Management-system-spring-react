/** Styles are prefixed with `acc-` so they cannot collide with other remotes. */
export const styles = `
.acc { background: var(--paper-raised); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px 22px; box-shadow: var(--shadow); }
.acc-header { display: flex; align-items: center; justify-content: space-between; }
.acc-header h2 { margin: 0; font-size: 22px; }
.acc-sub { margin: 2px 0 14px; color: var(--ink-soft); font-size: 13px; }
.acc-muted { color: var(--ink-soft); font-style: italic; margin: 8px 0; }
.acc-muted-small { color: var(--ink-soft); font-size: 12px; margin: 4px 0 0; }
.acc-error { background: var(--danger-soft); color: var(--danger); padding: 12px 14px; border-radius: 8px; }
.acc-error p { margin: 0 0 8px; }
.acc-blocked { background: var(--danger-soft); color: var(--danger); padding: 10px 12px; border-radius: 8px; font-weight: 600; font-size: 14px; margin: 0 0 10px; }
.acc-heading { font-size: 14px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--ink-soft); margin: 16px 0 6px; }
.acc-list { list-style: none; margin: 0; padding: 0; }
.acc-item { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--line); }
.acc-item:last-child { border-bottom: none; }
.acc-loan-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.acc-line { font-family: var(--font-body); font-size: 16px; overflow-wrap: anywhere; }
.acc-due { font-size: 13px; color: var(--ink-soft); }
.acc-loan-overdue .acc-due { color: var(--danger); font-weight: 600; }
.acc-actions { display: flex; gap: 6px; flex-shrink: 0; }
.acc-deposit { margin-top: 6px; }
.acc-balance { margin: 0 0 8px; font-weight: 600; }
.acc-balance-low { color: var(--danger); }
.acc-topup input { max-width: 120px; }
.acc-ledger { margin-top: 10px; font-size: 13px; }
.acc-ledger summary { cursor: pointer; color: var(--accent); }
.acc-ledger ul { list-style: none; padding: 0; margin: 6px 0 0; }
.acc-ledger li { display: flex; justify-content: space-between; gap: 10px; padding: 4px 0; border-bottom: 1px dotted var(--line); }
.acc-negative { color: var(--danger); }
.acc-rules { margin: 16px 0 0; font-size: 12px; color: var(--ink-soft); border-top: 1px dashed var(--line); padding-top: 10px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
`;
