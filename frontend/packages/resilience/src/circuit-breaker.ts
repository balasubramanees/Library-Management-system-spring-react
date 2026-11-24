/**
 * A small, dependency-free circuit breaker.
 *
 *   CLOSED ──(failureThreshold consecutive failures)──▶ OPEN
 *   OPEN   ──(resetTimeoutMs elapsed, next call)──────▶ HALF_OPEN
 *   HALF_OPEN ──(trial succeeds)──▶ CLOSED
 *   HALF_OPEN ──(trial fails)─────▶ OPEN
 *
 * Only failures that `isFailure` classifies as infrastructure failures trip the
 * breaker. Business errors (e.g. "book not available") are expected outcomes and
 * must never open the circuit.
 */

export type CircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

export interface CircuitBreakerOptions {
  /** Human readable name, used in errors and metrics. */
  name: string;
  /** Consecutive failures that open the circuit. */
  failureThreshold?: number;
  /** How long the circuit stays open before a trial call is allowed. */
  resetTimeoutMs?: number;
  /** Max concurrent trial calls while half-open. */
  halfOpenMaxCalls?: number;
  /** Decides whether an error counts as a failure. Defaults to "every error". */
  isFailure?: (error: unknown) => boolean;
  /** Injected clock, for deterministic tests. */
  now?: () => number;
  /** Called on every state transition. */
  onStateChange?: (from: CircuitState, to: CircuitState, name: string) => void;
}

export class CircuitOpenError extends Error {
  override readonly name = 'CircuitOpenError';
  constructor(
    readonly circuit: string,
    /** Milliseconds until the breaker will allow a trial call. */
    readonly retryAfterMs: number,
  ) {
    super(`Circuit "${circuit}" is open; retry in ${Math.ceil(retryAfterMs / 1000)}s`);
  }
}

export interface CircuitBreakerSnapshot {
  name: string;
  state: CircuitState;
  consecutiveFailures: number;
  totalFailures: number;
  totalSuccesses: number;
  totalRejected: number;
  openedAt: number | null;
}

export class CircuitBreaker {
  private state: CircuitState = 'CLOSED';
  private consecutiveFailures = 0;
  private openedAt: number | null = null;
  private halfOpenInFlight = 0;
  private totalFailures = 0;
  private totalSuccesses = 0;
  private totalRejected = 0;

  private readonly failureThreshold: number;
  private readonly resetTimeoutMs: number;
  private readonly halfOpenMaxCalls: number;
  private readonly isFailure: (error: unknown) => boolean;
  private readonly now: () => number;

  constructor(private readonly options: CircuitBreakerOptions) {
    this.failureThreshold = positive(options.failureThreshold ?? 5, 'failureThreshold');
    this.resetTimeoutMs = positive(options.resetTimeoutMs ?? 10_000, 'resetTimeoutMs');
    this.halfOpenMaxCalls = positive(options.halfOpenMaxCalls ?? 1, 'halfOpenMaxCalls');
    this.isFailure = options.isFailure ?? (() => true);
    this.now = options.now ?? Date.now;
  }

  get name(): string {
    return this.options.name;
  }

  /** Current state, taking an elapsed reset timeout into account. */
  getState(): CircuitState {
    if (this.state === 'OPEN' && this.openedAt !== null && this.now() - this.openedAt >= this.resetTimeoutMs) {
      this.transition('HALF_OPEN');
    }
    return this.state;
  }

  snapshot(): CircuitBreakerSnapshot {
    return {
      name: this.name,
      state: this.getState(),
      consecutiveFailures: this.consecutiveFailures,
      totalFailures: this.totalFailures,
      totalSuccesses: this.totalSuccesses,
      totalRejected: this.totalRejected,
      openedAt: this.openedAt,
    };
  }

  async execute<T>(action: () => Promise<T>): Promise<T> {
    const state = this.getState();

    if (state === 'OPEN') {
      this.totalRejected++;
      throw new CircuitOpenError(this.name, this.remainingOpenMs());
    }
    if (state === 'HALF_OPEN') {
      if (this.halfOpenInFlight >= this.halfOpenMaxCalls) {
        this.totalRejected++;
        throw new CircuitOpenError(this.name, 0);
      }
      this.halfOpenInFlight++;
    }

    try {
      const result = await action();
      this.onSuccess();
      return result;
    } catch (error) {
      if (this.isFailure(error)) {
        this.onFailure();
      } else {
        // A business error still proves the dependency is reachable.
        this.onSuccess();
      }
      throw error;
    } finally {
      if (state === 'HALF_OPEN') this.halfOpenInFlight--;
    }
  }

  /** Forces the breaker back to CLOSED (e.g. operator action, tests). */
  reset(): void {
    this.consecutiveFailures = 0;
    this.openedAt = null;
    this.transition('CLOSED');
  }

  private onSuccess(): void {
    this.totalSuccesses++;
    this.consecutiveFailures = 0;
    if (this.state !== 'CLOSED') {
      this.openedAt = null;
      this.transition('CLOSED');
    }
  }

  private onFailure(): void {
    this.totalFailures++;
    this.consecutiveFailures++;
    if (this.state === 'HALF_OPEN' || this.consecutiveFailures >= this.failureThreshold) {
      this.openedAt = this.now();
      this.transition('OPEN');
    }
  }

  private remainingOpenMs(): number {
    if (this.openedAt === null) return 0;
    return Math.max(0, this.resetTimeoutMs - (this.now() - this.openedAt));
  }

  private transition(to: CircuitState): void {
    const from = this.state;
    if (from === to) return;
    this.state = to;
    this.options.onStateChange?.(from, to, this.name);
  }
}

function positive(value: number, field: string): number {
  if (!Number.isFinite(value) || value <= 0) {
    throw new RangeError(`${field} must be a positive number, got ${value}`);
  }
  return value;
}
