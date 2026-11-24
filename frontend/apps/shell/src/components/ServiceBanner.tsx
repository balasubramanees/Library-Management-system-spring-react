import type { CircuitState } from '@library/resilience';

/** Visible state of the client-side circuit breaker. */
export function ServiceBanner({ state }: { state: CircuitState }) {
  if (state === 'CLOSED') return null;
  return (
    <div className={`service-banner ${state === 'OPEN' ? 'is-open' : 'is-half-open'}`} role="alert" data-testid="service-banner">
      {state === 'OPEN'
        ? 'The library service is unavailable. Requests are paused for a few seconds to let it recover.'
        : 'Reconnecting to the library service…'}
    </div>
  );
}
