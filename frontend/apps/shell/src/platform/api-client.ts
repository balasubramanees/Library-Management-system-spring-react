import type { ApiClient, ErrorCode, ErrorResponse } from '@library/contracts';
import { REQUEST_ID_HEADER } from '@library/contracts';
import { CircuitBreaker, CircuitOpenError, retry, TimeoutError, withTimeout } from '@library/resilience';

/** Any failed API call. `message` is always safe to show to the user. */
export class ApiError extends Error {
  override readonly name = 'ApiError';
  constructor(
    readonly code: ErrorCode,
    message: string,
    /** HTTP status, or 0 when no response was received. */
    readonly status: number,
    readonly requestId?: string,
  ) {
    super(message);
  }

  /** Infrastructure failure (as opposed to a business answer like "book not available"). */
  get isInfrastructureFailure(): boolean {
    return this.status === 0 || this.status >= 500;
  }
}

export interface ApiClientOptions {
  baseUrl: string;
  getToken: () => string | null;
  /** Called on 401, e.g. to drop an expired session and show the login page. */
  onUnauthorized: () => void;
  breaker: CircuitBreaker;
  timeoutMs?: number;
  /** Attempts for idempotent GETs (1 = no retry). */
  getAttempts?: number;
  fetchImpl?: typeof fetch;
  newRequestId?: () => string;
}

/**
 * The single HTTP client of the application, owned by the shell and injected into every
 * micro-frontend. Layering, from outside in:
 *
 *   circuit breaker  ->  retry (GET only)  ->  timeout  ->  fetch
 *
 * - The breaker counts only infrastructure failures (network, timeout, 5xx). Business errors
 *   (404 "not registered", 409 "not available") and 429 prove the backend is alive.
 * - Only GETs are retried: a POST like "borrow" that timed out may already have been applied.
 */
export function createApiClient(options: ApiClientOptions): ApiClient {
  const fetchImpl = options.fetchImpl ?? ((input, init) => fetch(input, init));
  const timeoutMs = options.timeoutMs ?? 8_000;
  const getAttempts = options.getAttempts ?? 3;
  const newRequestId = options.newRequestId ?? (() => crypto.randomUUID());

  async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
    const requestId = newRequestId();
    const once = () =>
      withTimeout(`${method} ${path}`, timeoutMs, (signal) => exchange<T>(method, path, body, requestId, signal));
    const call =
      method === 'GET'
        ? () =>
            retry(once, {
              maxAttempts: getAttempts,
              baseDelayMs: 150,
              shouldRetry: (error) => isRetryable(error),
            })
        : once;
    try {
      return await options.breaker.execute(call);
    } catch (error) {
      throw toApiError(error, requestId);
    }
  }

  async function exchange<T>(
    method: string,
    path: string,
    body: unknown,
    requestId: string,
    signal: AbortSignal,
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json', [REQUEST_ID_HEADER]: requestId };
    const token = options.getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    let response: Response;
    try {
      response = await fetchImpl(`${options.baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      });
    } catch (error) {
      if (signal.aborted) throw new TimeoutError(`${method} ${path}`, timeoutMs);
      throw new ApiError('NETWORK_ERROR', 'Cannot reach the library. Check that the application is running.', 0, requestId);
    }

    const payload = await readJson(response);
    if (response.ok) return payload as T;

    const envelope = (payload as Partial<ErrorResponse> | null)?.error;
    if (response.status === 401) options.onUnauthorized();
    throw new ApiError(
      envelope?.code ?? (response.status >= 500 ? 'SERVICE_UNAVAILABLE' : 'INTERNAL_ERROR'),
      envelope?.message ?? `Unexpected response (${response.status}).`,
      response.status,
      envelope?.requestId ?? response.headers.get(REQUEST_ID_HEADER) ?? requestId,
    );
  }

  return {
    get: (path) => send('GET', path),
    post: (path, body) => send('POST', path, body ?? {}),
    delete: (path) => send('DELETE', path),
  };
}

/** Breaker classification: only infrastructure failures count. */
export function isBreakerFailure(error: unknown): boolean {
  if (error instanceof TimeoutError) return true;
  return error instanceof ApiError && error.isInfrastructureFailure;
}

function isRetryable(error: unknown): boolean {
  if (error instanceof TimeoutError) return true;
  return error instanceof ApiError && (error.status === 0 || error.status === 502 || error.status === 503 || error.status === 504);
}

function toApiError(error: unknown, requestId: string): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof CircuitOpenError) {
    const seconds = Math.max(1, Math.ceil(error.retryAfterMs / 1000));
    return new ApiError('CIRCUIT_OPEN', `The library is temporarily unavailable. Retrying in ${seconds}s.`, 0, requestId);
  }
  if (error instanceof TimeoutError) {
    return new ApiError('TIMEOUT', 'The library is taking too long to answer. Please try again.', 0, requestId);
  }
  return new ApiError('INTERNAL_ERROR', 'Something went wrong. Please try again.', 0, requestId);
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
