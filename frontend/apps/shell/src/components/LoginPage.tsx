import { useState, type FormEvent } from 'react';
import { ApiError } from '../platform/api-client.js';

interface Props {
  onLogin: (name: string) => Promise<void>;
}

export function LoginPage({ onLogin }: Props) {
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) {
      setError('Please enter your name.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onLogin(name);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Login failed. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="login-card" aria-labelledby="login-title">
      <h1 id="login-title">Welcome to the library</h1>
      <p className="muted">
        Enter your name to log in. New names are registered automatically; log in as <code>admin</code> to manage books.
      </p>
      <form onSubmit={submit} noValidate>
        <label htmlFor="login-name">Your name</label>
        <div className="row">
          <input
            id="login-name"
            name="name"
            autoComplete="username"
            autoFocus
            maxLength={50}
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? 'login-error' : undefined}
          />
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Logging in…' : 'Log in'}
          </button>
        </div>
        {error && (
          <p id="login-error" className="field-error" role="alert">
            {error}
          </p>
        )}
      </form>
    </section>
  );
}
