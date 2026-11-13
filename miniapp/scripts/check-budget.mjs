// Bundle budget gate (MINI_APP 10.2, MA-PERF-8) and the check that no development-only code
// shipped (MA-TEST-2). Reads dist/ after `vite build`; exits non-zero when a budget is exceeded.
// Raising a budget is a reviewed change to the table in MINI_APP 10.2, not to this file alone.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const DIST = fileURLToPath(new URL('../dist', import.meta.url));
const KB = 1024;

const BUDGET = {
  html: 3 * KB,
  initialJs: 70 * KB,
  initialCss: 15 * KB,
  routeChunkJs: 35 * KB,
  totalJs: 250 * KB,
};

// Strings that exist only in the fake port, the mock backend and the development panel.
const DEV_MARKERS = ['fake-init-data', 'dosegram-mock-backend', 'Development controls', 'MOCK-0001'];

const gzipSize = (path) => gzipSync(readFileSync(path), { level: 9 }).length;
const kb = (bytes) => `${(bytes / KB).toFixed(2)} KB`;

const html = readFileSync(join(DIST, 'index.html'), 'utf8');
const assets = readdirSync(join(DIST, 'assets')).filter((name) => /\.(js|css)$/.test(name));
// The initial files are the ones the HTML shell names; every other script is a lazy chunk.
const isInitial = (name) => html.includes(`assets/${name}`);
const sizes = assets.map((name) => ({ name, initial: isInitial(name), bytes: gzipSize(join(DIST, 'assets', name)) }));
const sum = (items) => items.reduce((total, item) => total + item.bytes, 0);
const scripts = sizes.filter((item) => item.name.endsWith('.js'));
const styles = sizes.filter((item) => item.name.endsWith('.css'));
const lazyScripts = scripts.filter((item) => !item.initial);
const largestLazy = lazyScripts.reduce((largest, item) => (item.bytes > largest.bytes ? item : largest), { name: 'none', bytes: 0 });

const rows = [
  ['HTML shell', gzipSize(join(DIST, 'index.html')), BUDGET.html],
  ['Initial JavaScript', sum(scripts.filter((item) => item.initial)), BUDGET.initialJs],
  ['Initial CSS', sum(styles.filter((item) => item.initial)), BUDGET.initialCss],
  [`Largest lazy chunk (${largestLazy.name})`, largestLazy.bytes, BUDGET.routeChunkJs],
  ['All JavaScript', sum(scripts), BUDGET.totalJs],
];

const failures = [];
console.log('Bundle budget (gzip)');
for (const [label, actual, limit] of rows) {
  const ok = actual <= limit;
  console.log(`  ${ok ? 'ok  ' : 'OVER'}  ${label}: ${kb(actual)} of ${kb(limit)}`);
  if (!ok) failures.push(`${label} is ${kb(actual)}, over its budget of ${kb(limit)}`);
}

if (!scripts.some((item) => item.initial)) failures.push('index.html names no initial script');
if (/<style[\s>]|<script(?![^>]*\bsrc=)[^>]*>/.test(html)) failures.push('index.html has an inline script or style');

for (const { name } of sizes) {
  const text = readFileSync(join(DIST, 'assets', name), 'utf8');
  for (const marker of DEV_MARKERS) {
    if (text.includes(marker)) failures.push(`${name} contains development-only code ("${marker}")`);
  }
}

if (failures.length > 0) {
  console.error(`\ncheck-budget: FAILED\n  ${failures.join('\n  ')}`);
  process.exit(1);
}
console.log('check-budget: within budget; no development-only code in the bundle');
