import type { MicroFrontend, MountProps } from '@library/contracts';
import { useEffect, useRef, useState } from 'react';

interface Props {
  name: string;
  url: string;
  props: MountProps;
  load: (name: string, url: string) => Promise<MicroFrontend>;
}

type SlotState = { phase: 'loading' } | { phase: 'mounted' } | { phase: 'failed'; message: string };

/**
 * Hosts one micro-frontend in its own DOM container and React root. If the remote cannot be
 * fetched, is incompatible, or throws while mounting, only this slot shows an error; the shell
 * and the other remotes keep working (fault isolation).
 */
export function RemoteSlot({ name, url, props, load }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [state, setState] = useState<SlotState>({ phase: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    let cancelled = false;
    let mounted: MicroFrontend | null = null;
    setState({ phase: 'loading' });

    load(name, url)
      .then((remote) => {
        if (cancelled) return;
        remote.mount(element, props);
        mounted = remote;
        setState({ phase: 'mounted' });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        console.error(`[shell] remote "${name}" failed`, error);
        setState({ phase: 'failed', message: error instanceof Error ? error.message : `The ${name} module failed.` });
      });

    return () => {
      cancelled = true;
      try {
        mounted?.unmount(element);
      } catch (error) {
        console.error(`[shell] remote "${name}" failed to unmount`, error);
      }
    };
    // `props` is rebuilt per session; identity of user/api/bus is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, url, load, attempt, props.user, props.api, props.bus]);

  return (
    <section className="remote-slot" aria-busy={state.phase === 'loading'} data-remote={name}>
      {state.phase === 'loading' && <div className="remote-loading">Loading {name}…</div>}
      {state.phase === 'failed' && (
        <div className="remote-error" role="alert">
          <p>{state.message}</p>
          <button type="button" className="btn btn-ghost" onClick={() => setAttempt((n) => n + 1)}>
            Retry
          </button>
        </div>
      )}
      <div ref={container} hidden={state.phase === 'failed'} />
    </section>
  );
}
