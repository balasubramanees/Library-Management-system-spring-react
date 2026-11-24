/** Styles are prefixed with `adm-` so they cannot collide with other remotes. */
export const styles = `
.adm { background: var(--paper-raised); border: 1px solid var(--line); border-radius: var(--radius); padding: 20px 22px; box-shadow: var(--shadow); }
.adm-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.adm-header h2 { margin: 0; font-size: 22px; }
.adm-sub { margin: 2px 0 0; color: var(--ink-soft); font-size: 13px; }
.adm-muted { color: var(--ink-soft); font-style: italic; margin: 8px 0; }
.adm-error { background: var(--danger-soft); color: var(--danger); padding: 12px 14px; border-radius: 8px; margin-top: 12px; }
.adm-error p { margin: 0 0 8px; }

.adm-tiles { list-style: none; margin: 16px 0 4px; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }
.adm-tile { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--paper); }
.adm-tile-label { font-size: 12px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--ink-soft); font-weight: 600; }
.adm-tile-value { font-family: var(--font-body); font-size: 26px; font-weight: 600; line-height: 1.2; font-variant-numeric: tabular-nums; }
.adm-tile-detail { font-size: 12px; color: var(--ink-soft); }
.adm-tile-warn { background: var(--warn-soft); border-color: color-mix(in srgb, var(--warn) 30%, transparent); }
.adm-tile-warn .adm-tile-value { color: var(--warn); }
.adm-tile-danger { background: var(--danger-soft); border-color: color-mix(in srgb, var(--danger) 30%, transparent); }
.adm-tile-danger .adm-tile-value { color: var(--danger); }

.adm-section-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; margin-top: 18px; }
.adm-section-head .adm-heading { margin: 0; }
.adm-heading { font-size: 14px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--ink-soft); margin: 20px 0 6px; }
.adm-filters { display: flex; gap: 6px; flex-wrap: wrap; }
.adm-chip { font: inherit; font-size: 13px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--line); background: transparent; color: var(--ink); cursor: pointer; }
.adm-chip:hover { background: var(--accent-soft); }
.adm-chip-on { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.adm-chip-on:hover { background: var(--accent); }
.adm-chip-count { font-variant-numeric: tabular-nums; opacity: 0.8; margin-left: 2px; }

.adm-table-wrap { overflow-x: auto; margin-top: 8px; }
.adm-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.adm-table th { text-align: left; font-size: 12px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-soft); font-weight: 600; padding: 6px 8px; border-bottom: 1px solid var(--line); white-space: nowrap; }
.adm-table td { padding: 8px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.adm-table tbody tr:last-child td { border-bottom: none; }
.adm-title { font-family: var(--font-body); font-size: 15px; overflow-wrap: anywhere; }
/* Scoped to the table so it outranks the left-aligned \`th\` rule: headers and values line up on the right. */
.adm-table .adm-num { text-align: right; font-variant-numeric: tabular-nums; }
.adm-nowrap { white-space: nowrap; }
.adm-danger { color: var(--danger); font-weight: 600; }
.adm-warn { color: var(--warn); font-weight: 600; }
.adm-badge { display: inline-block; font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 999px; white-space: nowrap; }
.adm-badge-ok { background: var(--ok-soft); color: var(--ok); }
.adm-badge-warn { background: var(--warn-soft); color: var(--warn); }
.adm-badge-danger { background: var(--danger-soft); color: var(--danger); white-space: normal; }
`;
