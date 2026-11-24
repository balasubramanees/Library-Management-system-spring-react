/** Styles are prefixed with `cat-` so they cannot collide with other remotes. */
export const styles = `
.cat { background: var(--paper-raised); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px 22px; box-shadow: var(--shadow); }
.cat-header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.cat-header h2 { margin: 0 0 12px; font-size: 22px; }
.cat-count { color: var(--ink-soft); font-size: 13px; }
.cat-muted, .cat-empty { color: var(--ink-soft); padding: 18px 0; margin: 0; font-style: italic; }
.cat-error { background: var(--danger-soft); color: var(--danger); padding: 12px 14px; border-radius: 8px; }
.cat-error p { margin: 0 0 8px; }
.cat-list { list-style: none; margin: 0; padding: 0; }
.cat-item { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 12px; padding: 11px 2px; border-bottom: 1px solid var(--line); }
.cat-item:last-child { border-bottom: none; }
.cat-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cat-line { font-family: var(--font-body); font-size: 17px; overflow-wrap: anywhere; }
.cat-loan { font-size: 13px; color: var(--ink-soft); }
.cat-loan-overdue { color: var(--danger); font-weight: 600; }
.cat-clock { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin: 0 0 14px; padding: 10px 14px; border: 1px dashed var(--line); border-radius: 8px; font-size: 14px; }
.cat-clock-buttons { display: flex; gap: 6px; }
.cat-muted-inline { color: var(--ink-soft); font-size: 12px; }
.cat-badge { font-family: var(--font-ui); vertical-align: 2px; margin-left: 4px; font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; padding: 2px 8px; border-radius: 999px; }
.cat-badge-available { background: var(--ok-soft); color: var(--ok); }
.cat-badge-borrowed { background: var(--warn-soft); color: var(--warn); }
.cat-actions { min-width: 118px; text-align: right; }
.cat-waiting { font-size: 13px; color: var(--ink-soft); font-weight: 600; }
.cat-form { margin-top: 18px; padding-top: 14px; border-top: 1px dashed var(--line); }
.cat-form label { font-size: 13px; color: var(--ink-soft); }
.cat-admin { margin: 0 0 14px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--accent-soft); }
.cat-admin label { color: var(--accent); }
@media (max-width: 560px) { .cat-form .row { flex-wrap: wrap; } }
`;
