interface Props {
  lines: string[];
  onDismiss: () => void;
}

/** Shows the login greeting exactly as the CLI in the brief prints it. */
export function WelcomePanel({ lines, onDismiss }: Props) {
  if (lines.length === 0) return null;
  return (
    <section className="welcome" aria-label="Welcome" data-testid="welcome">
      <div className="welcome-lines">
        {lines.map((line, i) => (
          <p key={i} className={i === 0 ? 'welcome-hello' : undefined}>
            {line}
          </p>
        ))}
      </div>
      <button type="button" className="btn btn-ghost btn-small" onClick={onDismiss} aria-label="Dismiss welcome message">
        ✕
      </button>
    </section>
  );
}
