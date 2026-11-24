#!/usr/bin/env node
/**
 * Builds every frontend artefact independently, as separate CI pipelines would:
 *
 *   apps/shell/dist        host: index.html, styles.css, main.js, vendor/ (shared React)
 *   apps/mfe-catalog/dist  remote: remoteEntry.js
 *   apps/mfe-account/dist  remote: remoteEntry.js
 *   apps/mfe-admin/dist    remote: remoteEntry.js (admin only)
 *
 * React is bundled once, into the shell's vendor/ folder, and marked external everywhere else; the
 * import map in index.html resolves the bare "react" imports of all bundles to that single copy.
 */
import { build } from 'esbuild';
import { cp, mkdir, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(join(root, 'package.json'));
const production = process.env.NODE_ENV !== 'development';
const only = process.argv.slice(2);

const shared = {
  bundle: true,
  format: 'esm',
  target: 'es2022',
  platform: 'browser',
  jsx: 'automatic',
  sourcemap: true,
  minify: production,
  logLevel: 'warning',
  legalComments: 'none',
  define: { 'process.env.NODE_ENV': JSON.stringify(production ? 'production' : 'development') },
  alias: {
    '@library/contracts': join(root, 'packages/contracts/src/index.ts'),
    '@library/resilience': join(root, 'packages/resilience/src/index.ts'),
  },
};
const externals = ['react', 'react/jsx-runtime', 'react-dom/client'];

async function buildVendor(outdir) {
  // React ships CommonJS; generate ESM facades with explicit named exports so the import map
  // can serve them to native ES modules.
  const facades = join(root, 'node_modules/.cache/vendor-entries');
  await mkdir(facades, { recursive: true });
  const entries = {
    react: { spec: 'react', names: Object.keys(require('react')) },
    'react-jsx-runtime': { spec: 'react/jsx-runtime', names: Object.keys(require('react/jsx-runtime')) },
    'react-dom-client': { spec: 'react-dom/client', names: Object.keys(require('react-dom/client')) },
  };
  const entryPoints = {};
  for (const [name, { spec, names }] of Object.entries(entries)) {
    const valid = names.filter((n) => /^[A-Za-z_$][\w$]*$/.test(n) && n !== 'default');
    const file = join(facades, `${name}.js`);
    await writeFile(
      file,
      `import * as m from ${JSON.stringify(spec)};\nconst mod = m.default ?? m;\nexport default mod;\n` +
        `export const { ${valid.join(', ')} } = mod;\n`,
    );
    entryPoints[name] = file;
  }
  await build({ ...shared, entryPoints, outdir, splitting: true, chunkNames: 'chunks/[name]-[hash]' });
}

async function buildShell() {
  const app = join(root, 'apps/shell');
  const dist = join(app, 'dist');
  await rm(dist, { recursive: true, force: true });
  await buildVendor(join(dist, 'vendor'));
  await build({ ...shared, entryPoints: { main: join(app, 'src/main.tsx') }, outdir: dist, external: externals });
  await cp(join(app, 'index.html'), join(dist, 'index.html'));
  await cp(join(app, 'styles.css'), join(dist, 'styles.css'));
}

async function buildRemote(name) {
  const app = join(root, 'apps', name);
  const dist = join(app, 'dist');
  await rm(dist, { recursive: true, force: true });
  await build({ ...shared, entryPoints: { remoteEntry: join(app, 'src/index.tsx') }, outdir: dist, external: externals });
}

const targets = {
  shell: buildShell,
  'mfe-catalog': () => buildRemote('mfe-catalog'),
  'mfe-account': () => buildRemote('mfe-account'),
  'mfe-admin': () => buildRemote('mfe-admin'),
};

const selected = only.length ? only : Object.keys(targets);
const started = Date.now();
for (const name of selected) {
  if (!targets[name]) throw new Error(`Unknown build target "${name}". Known: ${Object.keys(targets).join(', ')}`);
}
await Promise.all(selected.map((name) => targets[name]()));
console.log(`[build] ${selected.join(', ')} built in ${Date.now() - started}ms (${production ? 'production' : 'development'})`);
