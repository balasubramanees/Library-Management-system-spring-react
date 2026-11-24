import { MFE_CONTRACT_VERSION, type MicroFrontend } from '@library/contracts';
import { withTimeout } from '@library/resilience';

export class RemoteLoadError extends Error {
  override readonly name = 'RemoteLoadError';
  constructor(
    readonly remote: string,
    message: string,
    override readonly cause?: unknown,
  ) {
    super(message);
  }
}

export type ModuleImporter = (url: string) => Promise<unknown>;

/** Native dynamic import; kept behind a function so bundlers leave it alone and tests can stub it. */
const nativeImport: ModuleImporter = (url) => import(/* webpackIgnore: true */ /* @vite-ignore */ url);

/**
 * Loads a micro-frontend at runtime from its own origin and validates it against the mount
 * contract. Successful loads are cached; failures are not, so "Retry" really retries.
 */
export function createRemoteLoader(importer: ModuleImporter = nativeImport, timeoutMs = 5_000) {
  const cache = new Map<string, Promise<MicroFrontend>>();

  return function loadRemote(name: string, url: string): Promise<MicroFrontend> {
    const cached = cache.get(url);
    if (cached) return cached;

    const loading = withTimeout(`load ${name}`, timeoutMs, () => importer(url))
      .catch((error: unknown) => {
        throw new RemoteLoadError(name, `The ${name} module could not be loaded.`, error);
      })
      .then((module) => validate(name, module));
    cache.set(url, loading);
    loading.catch(() => cache.delete(url));
    return loading;
  };
}

function validate(name: string, module: unknown): MicroFrontend {
  const candidate = (module ?? {}) as Partial<MicroFrontend>;
  if (typeof candidate.mount !== 'function' || typeof candidate.unmount !== 'function') {
    throw new RemoteLoadError(name, `The ${name} module does not implement mount/unmount.`);
  }
  if (candidate.contractVersion !== MFE_CONTRACT_VERSION) {
    throw new RemoteLoadError(
      name,
      `The ${name} module speaks contract v${String(candidate.contractVersion)}, the shell expects v${MFE_CONTRACT_VERSION}.`,
    );
  }
  return candidate as MicroFrontend;
}
