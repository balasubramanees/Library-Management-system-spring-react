/**
 * Micro-frontend runtime contract.
 *
 * Every remote exposes `mount` and `unmount` from its entry module. The shell owns
 * cross-cutting concerns (session, HTTP client with circuit breaker, event bus) and
 * injects them, so remotes never talk to each other directly and never duplicate
 * resilience logic.
 */

import type { UserDto } from './api.js';

export const MFE_CONTRACT_VERSION = 1;

/** Events exchanged over the shell's bus. Keep payloads serialisable. */
export interface LibraryEvents {
  /** Something changed the catalogue or a user's loans; listeners should refetch. */
  'library:changed': { source: string; reason: string };
  /** Ask the shell to display a toast. */
  'toast:show': { kind: 'success' | 'error' | 'info'; message: string };
  /** The account remote loaded fresh `status` lines (notification lines excluded); the shell mirrors them in the welcome panel. */
  'account:status': { lines: string[] };
  /** Shell-owned: the session ended (logout or expiry). */
  'session:ended': { reason: 'logout' | 'expired' };
}

export type LibraryEventName = keyof LibraryEvents;

export interface EventBus {
  emit<K extends LibraryEventName>(event: K, payload: LibraryEvents[K]): void;
  on<K extends LibraryEventName>(event: K, listener: (payload: LibraryEvents[K]) => void): () => void;
}

/** Minimal HTTP surface injected into remotes. */
export interface ApiClient {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
  delete<T>(path: string): Promise<T>;
}

export interface MountProps {
  user: UserDto;
  api: ApiClient;
  bus: EventBus;
}

export interface MicroFrontend {
  contractVersion: number;
  mount(container: HTMLElement, props: MountProps): void;
  unmount(container: HTMLElement): void;
}

export interface RemoteManifestEntry {
  name: string;
  url: string;
  /** Not shown to the admin (librarian) account, e.g. the member's own loans. */
  memberOnly?: boolean;
  /** Only shown to the admin (librarian), e.g. the library dashboard. */
  adminOnly?: boolean;
}

export interface RemoteManifest {
  remotes: Record<string, RemoteManifestEntry>;
}
