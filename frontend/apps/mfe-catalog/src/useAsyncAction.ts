import type { EventBus } from '@library/contracts';
import { useCallback, useState } from 'react';

/**
 * Runs a command against the API, reports the outcome as a toast and broadcasts
 * `library:changed` so every remote refreshes. Prevents double submission while pending.
 */
export function useAsyncAction(bus: EventBus, source: string) {
  const [pending, setPending] = useState<string | null>(null);

  const run = useCallback(
    async (key: string, action: () => Promise<{ message: string }>): Promise<boolean> => {
      setPending(key);
      try {
        const { message } = await action();
        bus.emit('toast:show', { kind: 'success', message });
        bus.emit('library:changed', { source, reason: key });
        return true;
      } catch (error) {
        bus.emit('toast:show', { kind: 'error', message: error instanceof Error ? error.message : String(error) });
        // The server state may differ from what we showed (e.g. someone else borrowed it): resync.
        bus.emit('library:changed', { source, reason: `${key}:failed` });
        return false;
      } finally {
        setPending(null);
      }
    },
    [bus, source],
  );

  return { pending, run };
}
