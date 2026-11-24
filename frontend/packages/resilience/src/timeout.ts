export class TimeoutError extends Error {
  override readonly name = 'TimeoutError';
  constructor(readonly operation: string, readonly timeoutMs: number) {
    super(`${operation} timed out after ${timeoutMs}ms`);
  }
}

/**
 * Races `action` against a timer. The action receives an AbortSignal so that
 * cancellable work (fetch, streams) is actually stopped, not just ignored.
 */
export async function withTimeout<T>(
  operation: string,
  timeoutMs: number,
  action: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new TimeoutError(operation, timeoutMs));
    }, timeoutMs);
  });
  try {
    return await Promise.race([action(controller.signal), timeout]);
  } finally {
    clearTimeout(timer);
  }
}
