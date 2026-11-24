import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { CircuitBreaker, CircuitOpenError, type CircuitState } from '../src/index.js';

const fail = () => Promise.reject(new Error('boom'));
const succeed = () => Promise.resolve('ok');

function setup(options: Partial<ConstructorParameters<typeof CircuitBreaker>[0]> = {}) {
  let now = 0;
  const transitions: string[] = [];
  const breaker = new CircuitBreaker({
    name: 'test',
    failureThreshold: 3,
    resetTimeoutMs: 1_000,
    now: () => now,
    onStateChange: (from: CircuitState, to: CircuitState) => transitions.push(`${from}->${to}`),
    ...options,
  });
  return { breaker, transitions, advance: (ms: number) => (now += ms) };
}

describe('CircuitBreaker', () => {
  it('opens after the failure threshold and then rejects without calling', async () => {
    const { breaker } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(breaker.execute(fail), /boom/);
    assert.equal(breaker.getState(), 'OPEN');

    let called = false;
    await assert.rejects(
      breaker.execute(async () => {
        called = true;
      }),
      CircuitOpenError,
    );
    assert.equal(called, false);
    assert.equal(breaker.snapshot().totalRejected, 1);
  });

  it('a success resets the consecutive failure count', async () => {
    const { breaker } = setup();
    await assert.rejects(breaker.execute(fail));
    await assert.rejects(breaker.execute(fail));
    await breaker.execute(succeed);
    await assert.rejects(breaker.execute(fail));
    assert.equal(breaker.getState(), 'CLOSED');
  });

  it('half-opens after the reset timeout; a successful trial closes it', async () => {
    const { breaker, transitions, advance } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(breaker.execute(fail));
    advance(999);
    assert.equal(breaker.getState(), 'OPEN');
    advance(1);
    assert.equal(breaker.getState(), 'HALF_OPEN');
    assert.equal(await breaker.execute(succeed), 'ok');
    assert.deepEqual(transitions, ['CLOSED->OPEN', 'OPEN->HALF_OPEN', 'HALF_OPEN->CLOSED']);
  });

  it('a failed trial re-opens immediately', async () => {
    const { breaker, advance } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(breaker.execute(fail));
    advance(1_000);
    await assert.rejects(breaker.execute(fail), /boom/);
    assert.equal(breaker.getState(), 'OPEN');
  });

  it('limits concurrent trial calls while half-open', async () => {
    const { breaker, advance } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(breaker.execute(fail));
    advance(1_000);
    let release!: () => void;
    const trial = breaker.execute(() => new Promise<string>((r) => (release = () => r('ok'))));
    await assert.rejects(breaker.execute(succeed), CircuitOpenError);
    release();
    assert.equal(await trial, 'ok');
    assert.equal(breaker.getState(), 'CLOSED');
  });

  it('business errors do not count as failures', async () => {
    class NotFound extends Error {}
    const { breaker } = setup({ isFailure: (e) => !(e instanceof NotFound) });
    for (let i = 0; i < 10; i++) await assert.rejects(breaker.execute(() => Promise.reject(new NotFound())));
    assert.equal(breaker.getState(), 'CLOSED');
  });

  it('reports remaining open time in the error', async () => {
    const { breaker, advance } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(breaker.execute(fail));
    advance(400);
    await assert.rejects(breaker.execute(succeed), (e: unknown) => e instanceof CircuitOpenError && e.retryAfterMs === 600);
  });

  it('rejects invalid configuration', () => {
    assert.throws(() => new CircuitBreaker({ name: 'x', failureThreshold: 0 }), RangeError);
  });
});
