import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { CircuitBreaker } from '@library/resilience';
import { ApiError, createApiClient, isBreakerFailure } from '../src/platform/api-client.js';

type Reply = { status: number; body?: unknown } | 'network-error';

function harness(replies: Reply[], token: string | null = 'tok') {
  const calls: { url: string; init: RequestInit }[] = [];
  let unauthorized = 0;
  const breaker = new CircuitBreaker({ name: 'api', failureThreshold: 2, resetTimeoutMs: 60_000, isFailure: isBreakerFailure });
  const fetchImpl = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    const reply = replies.shift() ?? { status: 200, body: {} };
    if (reply === 'network-error') throw new TypeError('Failed to fetch');
    return new Response(reply.body === undefined ? '' : JSON.stringify(reply.body), { status: reply.status });
  }) as unknown as typeof fetch;
  const api = createApiClient({
    baseUrl: '/api',
    breaker,
    fetchImpl,
    getToken: () => token,
    onUnauthorized: () => unauthorized++,
    newRequestId: () => 'req-1',
    getAttempts: 3,
  });
  return { api, calls, breaker, unauthorized: () => unauthorized };
}

const envelope = (code: string, message: string) => ({ error: { code, message, requestId: 'srv' } });

describe('api client', () => {
  let h: ReturnType<typeof harness>;

  beforeEach(() => {
    h = harness([]);
  });

  it('sends JSON with bearer token and correlation id', async () => {
    h = harness([{ status: 200, body: { message: 'You borrowed "1984".' } }]);
    const res = await h.api.post<{ message: string }>('/books/borrow', { title: '1984' });
    assert.equal(res.message, 'You borrowed "1984".');
    const { url, init } = h.calls[0]!;
    const headers = init.headers as Record<string, string>;
    assert.equal(url, '/api/books/borrow');
    assert.equal(init.method, 'POST');
    assert.equal(init.body, '{"title":"1984"}');
    assert.equal(headers.Authorization, 'Bearer tok');
    assert.equal(headers['X-Request-Id'], 'req-1');
    assert.equal(headers['Content-Type'], 'application/json');
  });

  it('omits Authorization when logged out', async () => {
    h = harness([{ status: 201, body: {} }], null);
    await h.api.post('/sessions', { name: 'a' });
    assert.equal((h.calls[0]!.init.headers as Record<string, string>).Authorization, undefined);
  });

  it('surfaces business errors with the server message and does not retry them', async () => {
    h = harness([{ status: 404, body: envelope('BOOK_NOT_FOUND', 'Sorry, "1985" is not registered.') }]);
    await assert.rejects(h.api.get('/books'), (e: unknown) => {
      assert.ok(e instanceof ApiError);
      assert.equal(e.code, 'BOOK_NOT_FOUND');
      assert.equal(e.message, 'Sorry, "1985" is not registered.');
      assert.equal(e.status, 404);
      return true;
    });
    assert.equal(h.calls.length, 1);
    assert.equal(h.breaker.getState(), 'CLOSED');
  });

  it('notifies on 401 so the shell can end the session', async () => {
    h = harness([{ status: 401, body: envelope('UNAUTHENTICATED', 'Please log in first.') }]);
    await assert.rejects(h.api.get('/me/status'), /Please log in first/);
    assert.equal(h.unauthorized(), 1);
  });

  it('retries idempotent GETs on 503 and network errors', async () => {
    h = harness(['network-error', { status: 503, body: envelope('SERVICE_UNAVAILABLE', 'down') }, { status: 200, body: { ok: true } }]);
    assert.deepEqual(await h.api.get('/books'), { ok: true });
    assert.equal(h.calls.length, 3);
  });

  it('never retries POSTs (they are not idempotent)', async () => {
    h = harness([{ status: 503, body: envelope('SERVICE_UNAVAILABLE', 'down') }]);
    await assert.rejects(h.api.post('/books/borrow', { title: 'x' }), (e: unknown) => e instanceof ApiError && e.status === 503);
    assert.equal(h.calls.length, 1);
  });

  it('opens the circuit after repeated infrastructure failures and then fails fast', async () => {
    h = harness([
      { status: 500, body: envelope('INTERNAL_ERROR', 'x') },
      { status: 502 },
    ]);
    await assert.rejects(h.api.post('/books/borrow', { title: 'x' }));
    await assert.rejects(h.api.post('/books/borrow', { title: 'x' }));
    assert.equal(h.breaker.getState(), 'OPEN');

    await assert.rejects(h.api.get('/books'), (e: unknown) => e instanceof ApiError && e.code === 'CIRCUIT_OPEN');
    assert.equal(h.calls.length, 2, 'no request sent while open');
  });

  it('maps unreadable error bodies to a generic message', async () => {
    h = harness([{ status: 418 }]);
    await assert.rejects(h.api.delete('/sessions/current'), (e: unknown) => e instanceof ApiError && /Unexpected response \(418\)/.test(e.message));
  });
});
