import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { MFE_CONTRACT_VERSION } from '@library/contracts';
import { createEventBus } from '../src/platform/event-bus.js';
import { createRemoteLoader, RemoteLoadError } from '../src/platform/remote-loader.js';

const validRemote = { contractVersion: MFE_CONTRACT_VERSION, mount: () => undefined, unmount: () => undefined };

describe('remote loader', () => {
  it('loads and caches a valid remote', async () => {
    let imports = 0;
    const load = createRemoteLoader(async () => {
      imports++;
      return validRemote;
    });
    await load('catalog', 'http://x/remoteEntry.js');
    await load('catalog', 'http://x/remoteEntry.js');
    assert.equal(imports, 1);
  });

  it('rejects modules that do not implement the contract', async () => {
    const load = createRemoteLoader(async () => ({ mount: () => undefined }));
    await assert.rejects(load('catalog', 'u'), (e: unknown) => e instanceof RemoteLoadError && /mount\/unmount/.test(e.message));
  });

  it('rejects incompatible contract versions', async () => {
    const load = createRemoteLoader(async () => ({ ...validRemote, contractVersion: 99 }));
    await assert.rejects(load('catalog', 'u'), /contract v99/);
  });

  it('does not cache failures, so a retry can succeed', async () => {
    let attempt = 0;
    const load = createRemoteLoader(async () => {
      attempt++;
      if (attempt === 1) throw new TypeError('Failed to fetch dynamically imported module');
      return validRemote;
    });
    await assert.rejects(load('account', 'u'), /could not be loaded/);
    assert.equal(await load('account', 'u'), validRemote);
  });

  it('times out a remote that never arrives', async () => {
    const load = createRemoteLoader(() => new Promise(() => undefined), 20);
    await assert.rejects(load('account', 'u'), RemoteLoadError);
  });
});

describe('event bus', () => {
  it('delivers typed payloads and supports unsubscribe', () => {
    const bus = createEventBus();
    const seen: string[] = [];
    const off = bus.on('library:changed', (p) => seen.push(p.reason));
    bus.emit('library:changed', { source: 'catalog', reason: 'borrow' });
    off();
    bus.emit('library:changed', { source: 'catalog', reason: 'ignored' });
    assert.deepEqual(seen, ['borrow']);
  });

  it('isolates a throwing listener from the others', () => {
    const bus = createEventBus();
    const seen: string[] = [];
    const originalError = console.error;
    console.error = () => undefined;
    try {
      bus.on('toast:show', () => {
        throw new Error('bad listener');
      });
      bus.on('toast:show', (p) => seen.push(p.message));
      bus.emit('toast:show', { kind: 'info', message: 'hi' });
    } finally {
      console.error = originalError;
    }
    assert.deepEqual(seen, ['hi']);
  });
});
