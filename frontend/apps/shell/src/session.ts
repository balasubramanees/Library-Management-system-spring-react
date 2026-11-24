import type { UserDto } from '@library/contracts';

const KEY = 'library.session';

export interface StoredSession {
  token: string;
  user: UserDto;
}

/**
 * Session persistence in sessionStorage: survives a page reload but not closing the tab.
 * The token is only an opaque handle; it becomes useless when the backend restarts.
 */
export const sessionStore = {
  load(): StoredSession | null {
    try {
      const raw = sessionStorage.getItem(KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as Partial<StoredSession>;
      return typeof parsed.token === 'string' && parsed.user ? (parsed as StoredSession) : null;
    } catch {
      return null;
    }
  },
  save(session: StoredSession): void {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(session));
    } catch {
      // Storage can be unavailable (privacy mode); the session then lasts until reload.
    }
  },
  clear(): void {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      /* ignore */
    }
  },
};
