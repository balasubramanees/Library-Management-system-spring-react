import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { retry, TimeoutError, withTimeout } from '../src/index.js';

describe('retry', () => {
  it('retries until success with exponential, jittered, capped delays', async () => {
    const delays: number[] = [];
    let attempts = 0;
    const result = await retry(
      async () => {
        attempts++;
        if (attempts < 4) throw new Error('transient');
        return 'done';
      },
      { maxAttempts: 5, baseDelayMs: 100, maxDelayMs: 250, random: () => 1, sleep: async (ms) => void delays.push(ms) },
    );
    assert.equal(result, 'done');
    assert.deepEqual(delays, [100, 200, 250]);
  });

  it('gives up after maxAttempts and rethrows the last error', async () => {
    let attempts = 0;
    await assert.rejects(
      retry(
        async () => {
          attempts++;
          throw new Error(`fail ${attempts}`);
        },
        { maxAttempts: 3, sleep: async () => undefined },
      ),
      /fail 3/,
    );
  });

  it('does not retry errors rejected by shouldRetry', async () => {
    let attempts = 0;
    await assert.rejects(
      retry(
        async () => {
          attempts++;
          throw new Error('permanent');
        },
        { maxAttempts: 5, shouldRetry: () => false, sleep: async () => undefined },
      ),
    );
    assert.equal(attempts, 1);
  });
});

describe('withTimeout', () => {
  it('resolves fast operations', async () => {
    assert.equal(await withTimeout('fast', 100, async () => 42), 42);
  });

  it('rejects slow operations and aborts their signal', async () => {
    let aborted = false;
    await assert.rejects(
      withTimeout('slow', 20, (signal) => {
        signal.addEventListener('abort', () => (aborted = true));
        return new Promise(() => undefined);
      }),
      (e: unknown) => e instanceof TimeoutError && e.timeoutMs === 20,
    );
    assert.equal(aborted, true);
  });
});
