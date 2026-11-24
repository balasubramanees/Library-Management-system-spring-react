import type { LoginResponse, MessageResponse, RemoteManifest, StatusResponse, UserDto } from '@library/contracts';
import { CircuitBreaker, type CircuitState } from '@library/resilience';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { LoginPage } from './components/LoginPage.js';
import { RemoteSlot } from './components/RemoteSlot.js';
import { ServiceBanner } from './components/ServiceBanner.js';
import { Toasts, type Toast } from './components/Toasts.js';
import { WelcomePanel } from './components/WelcomePanel.js';
import { ApiError, createApiClient, isBreakerFailure } from './platform/api-client.js';
import { createEventBus } from './platform/event-bus.js';
import { createRemoteLoader } from './platform/remote-loader.js';
import { sessionStore, type StoredSession } from './session.js';

const loadRemote = createRemoteLoader();

/**
 * The shell (host) owns everything cross-cutting: the session, the resilient API client, the event
 * bus, toasts and layout. Business screens live in independently deployed micro-frontends.
 */
export function App() {
  const [session, setSession] = useState<StoredSession | null>(() => sessionStore.load());
  const [welcome, setWelcome] = useState<string[]>([]);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [circuit, setCircuit] = useState<CircuitState>('CLOSED');
  const [manifest, setManifest] = useState<RemoteManifest | null>(null);
  const [manifestError, setManifestError] = useState<string | null>(null);
  const sessionRef = useRef(session);
  sessionRef.current = session;

  const bus = useMemo(() => createEventBus(), []);

  const endSession = useCallback(
    (reason: 'logout' | 'expired') => {
      sessionStore.clear();
      setSession(null);
      setWelcome([]);
      bus.emit('session:ended', { reason });
    },
    [bus],
  );

  const api = useMemo(() => {
    const breaker = new CircuitBreaker({
      name: 'library-api',
      failureThreshold: 3,
      resetTimeoutMs: 10_000,
      isFailure: isBreakerFailure,
      onStateChange: (_from, to) => setCircuit(to),
    });
    return createApiClient({
      baseUrl: '/api',
      breaker,
      getToken: () => sessionRef.current?.token ?? null,
      onUnauthorized: () => {
        if (!sessionRef.current) return;
        endSession('expired');
        bus.emit('toast:show', { kind: 'info', message: 'Your session has expired. Please log in again.' });
      },
    });
  }, [bus, endSession]);

  const pushToast = useCallback((toast: Omit<Toast, 'id'>) => {
    const id = crypto.randomUUID();
    setToasts((current) => [...current.slice(-3), { ...toast, id }]);
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 5_000);
  }, []);

  useEffect(() => bus.on('toast:show', (t) => pushToast(t)), [bus, pushToast]);

  // Keep the welcome panel's status lines in step with the account panel after borrows, returns,
  // extensions and deposits. The account remote shares what it fetched rather than the shell calling
  // `/me/status` again, because that call consumes wait list notifications. A dismissed panel stays closed.
  const greetingRef = useRef<string[]>([]);
  useEffect(
    () =>
      bus.on('account:status', ({ lines }) =>
        setWelcome((current) => (current.length === 0 ? current : [...greetingRef.current, ...lines])),
      ),
    [bus],
  );

  // Remote locations are resolved at runtime, so a remote can be redeployed without rebuilding the shell.
  useEffect(() => {
    fetch('/mfe-manifest.json')
      .then((r) => (r.ok ? (r.json() as Promise<RemoteManifest>) : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then(setManifest)
      .catch(() => setManifestError('Could not load the application modules list.'));
  }, []);

  // A stored session may belong to a previous backend run: validate it once on start-up.
  useEffect(() => {
    if (!session) return;
    api.get<UserDto>('/sessions/current').catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = useCallback(
    async (name: string) => {
      const response = await api.post<LoginResponse>('/sessions', { name });
      const next = { token: response.token, user: response.user };
      // Make the token available to the API client first, but only render the remotes after the
      // greeting's status call: that call consumes pending wait list notifications.
      sessionRef.current = next;
      greetingRef.current = response.messages;
      let lines = response.messages;
      // Members get their status, as `login` prints it in the brief; the admin (librarian) has none.
      if (!response.user.isAdmin) {
        try {
          const status = await api.get<StatusResponse>('/me/status');
          lines = [...lines, ...status.lines];
          // Notifications delivered at login (they lead the status lines) stay in the greeting; only
          // the live status part below them is replaced as the account panel refreshes.
          greetingRef.current = [...response.messages, ...status.lines.slice(0, status.notifications.length)];
        } catch (error) {
          if (error instanceof ApiError) pushToast({ kind: 'error', message: error.message });
        }
      }
      sessionStore.save(next);
      setSession(next);
      setWelcome(lines);
    },
    [api, pushToast],
  );

  const logout = useCallback(async () => {
    try {
      const response = await api.delete<MessageResponse>('/sessions/current');
      pushToast({ kind: 'info', message: response.message });
    } catch {
      // Even if the server is unreachable the user must be able to leave.
      pushToast({ kind: 'info', message: `Goodbye, ${session?.user.name ?? ''}!` });
    }
    endSession('logout');
  }, [api, endSession, pushToast, session]);

  return (
    <div className="shell">
      <header className="shell-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            ❦
          </span>
          <span className="brand-name">Library</span>
        </div>
        {session && (
          <div className="user-area">
            <span className="user-name" data-testid="current-user">
              {session.user.name}
              {session.user.isAdmin && <span className="role-badge">admin</span>}
            </span>
            <button type="button" className="btn btn-ghost" onClick={logout}>
              Log out
            </button>
          </div>
        )}
      </header>

      <ServiceBanner state={circuit} />

      <main className="shell-main">
        {!session ? (
          <LoginPage onLogin={login} />
        ) : (
          <>
            <WelcomePanel lines={welcome} onDismiss={() => setWelcome([])} />
            {manifestError && (
              <div className="remote-error" role="alert">
                {manifestError}
              </div>
            )}
            {manifest && (
              <div className={`workspace${session.user.isAdmin ? ' workspace-single' : ''}`} key={session.user.id}>
                {Object.entries(manifest.remotes)
                  .filter(([, remote]) => (session.user.isAdmin ? !remote.memberOnly : !remote.adminOnly))
                  .map(([key, remote]) => (
                    <RemoteSlot
                      key={key}
                      name={remote.name}
                      url={remote.url}
                      load={loadRemote}
                      props={{ user: session.user, api, bus }}
                    />
                  ))}
              </div>
            )}
          </>
        )}
      </main>

      <Toasts toasts={toasts} onDismiss={(id) => setToasts((current) => current.filter((t) => t.id !== id))} />
    </div>
  );
}
