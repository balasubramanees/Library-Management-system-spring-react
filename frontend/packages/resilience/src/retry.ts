/**
 * Retry with exponential backoff and full jitter.
 *
 * Callers decide what is retryable. Never retry non-idempotent operations
 * (e.g. "borrow") blindly: a request that timed out may still have succeeded.
 */

export interface RetryOptions {
  /** Total attempts including the first one. */
  maxAttempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  shouldRetry?: (error: unknown, attempt: number) => boolean;
  /** Injected for deterministic tests. */
  sleep?: (ms: number) => Promise<void>;
  random?: () => number;
  onRetry?: (error: unknown, attempt: number, delayMs: number) => void;
}

export const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

export async function retry<T>(action: (attempt: number) => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const maxAttempts = Math.max(1, options.maxAttempts ?? 3);
  const baseDelayMs = options.baseDelayMs ?? 200;
  const maxDelayMs = options.maxDelayMs ?? 2_000;
  const shouldRetry = options.shouldRetry ?? (() => true);
  const sleep = options.sleep ?? defaultSleep;
  const random = options.random ?? Math.random;

  let attempt = 1;
  for (;;) {
    try {
      return await action(attempt);
    } catch (error) {
      if (attempt >= maxAttempts || !shouldRetry(error, attempt)) throw error;
      const ceiling = Math.min(maxDelayMs, baseDelayMs * 2 ** (attempt - 1));
      const delay = Math.round(random() * ceiling);
      options.onRetry?.(error, attempt, delay);
      await sleep(delay);
      attempt++;
    }
  }
}
