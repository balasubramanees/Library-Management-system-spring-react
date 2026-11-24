import type { EventBus, LibraryEventName, LibraryEvents } from '@library/contracts';

/**
 * Typed pub/sub between the shell and the micro-frontends. Remotes never import each other;
 * they only publish facts ("library:changed") and react to them.
 */
export function createEventBus(): EventBus {
  const target = new EventTarget();
  return {
    emit(event, payload) {
      target.dispatchEvent(new CustomEvent(event, { detail: payload }));
    },
    on<K extends LibraryEventName>(event: K, listener: (payload: LibraryEvents[K]) => void) {
      const handler = (e: Event) => {
        try {
          listener((e as CustomEvent<LibraryEvents[K]>).detail);
        } catch (error) {
          // One faulty subscriber must not break the others.
          console.error(`[event-bus] listener for "${event}" failed`, error);
        }
      };
      target.addEventListener(event, handler);
      return () => target.removeEventListener(event, handler);
    },
  };
}
