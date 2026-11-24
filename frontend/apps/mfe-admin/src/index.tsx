import { MFE_CONTRACT_VERSION, type MicroFrontend, type MountProps } from '@library/contracts';
import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { Dashboard } from './Dashboard.js';
import { ErrorBoundary } from './ErrorBoundary.js';
import { styles } from './styles.js';

const roots = new Map<HTMLElement, Root>();
const STYLE_ID = 'mfe-admin-styles';

export const contractVersion = MFE_CONTRACT_VERSION;

export function mount(container: HTMLElement, props: MountProps): void {
  if (!document.getElementById(STYLE_ID)) {
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = styles;
    document.head.appendChild(style);
  }
  unmount(container);
  const root = createRoot(container);
  root.render(
    <StrictMode>
      <ErrorBoundary name="admin">
        <Dashboard {...props} />
      </ErrorBoundary>
    </StrictMode>,
  );
  roots.set(container, root);
}

export function unmount(container: HTMLElement): void {
  roots.get(container)?.unmount();
  roots.delete(container);
}

// Compile-time check that this module satisfies the shell contract.
const _contract: MicroFrontend = { contractVersion, mount, unmount };
void _contract;
