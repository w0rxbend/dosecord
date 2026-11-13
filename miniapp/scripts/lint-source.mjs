// Source rules that the type checker cannot see (MINI_APP MA-STACK-1 to 3, MA-SEC-1, 5.2, 5.4,
// 6.6, MA-DS-4). Exits non-zero on any finding. Run by `pnpm run check`.
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../src', import.meta.url));
const MAX_COMPONENT_LINES = 200;

const inDir = (dir) => (file) => file.startsWith(dir + sep);
const isTest = (file) => file.endsWith('.test.ts') || file.startsWith('test' + sep);
const never = () => false;

/** Each rule: what is forbidden, where it is allowed, and why. */
const RULES = [
  { name: 'window.Telegram outside platform/', pattern: /\bTelegram\s*\??\.\s*WebApp|window\.Telegram/, allowed: inDir('platform') },
  { name: 'fetch outside api/', pattern: /\bfetch\s*\(/, allowed: inDir('api') },
  { name: 'markup sink', pattern: /innerHTML|outerHTML|insertAdjacentHTML|document\.write|\{@html|\beval\s*\(/, allowed: never },
  { name: 'client-side store', pattern: /localStorage|sessionStorage|indexedDB|document\.cookie|caches\.open/, allowed: never },
  { name: 'message listener', pattern: /addEventListener\(\s*['"]message['"]/, allowed: never },
  { name: 'console output', pattern: /\bconsole\.\w+\s*\(/, allowed: never },
  { name: 'Telegram theme variable outside the theme adapter', pattern: /--tg-theme-/, allowed: inDir('platform') },
  {
    name: 'literal colour outside tokens and the theme adapter',
    pattern: /#[0-9a-fA-F]{3,8}\b|\brgba?\(|\bhsla?\(/,
    // dev/ holds Telegram theme fixtures for the development panel, not component colours.
    allowed: (file) => file === join('styles', 'tokens.css') || file === join('platform', 'theme.ts') || inDir('dev')(file),
  },
  {
    name: 'judgement word in product copy',
    pattern: /\b(streak|adherence|compliance|fail(ed|ure)?|bad|poor|good)\b/i,
    allowed: (file) => !inDir('i18n')(file) && !inDir('mock')(file),
  },
];

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? walk(path) : [path];
  });
}

/** Comments explain the rules and may name what they forbid. */
function stripComments(text) {
  return text
    .replace(/<!--[\s\S]*?-->/g, (match) => match.replace(/[^\n]/g, ' '))
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
}

const findings = [];
for (const path of walk(ROOT)) {
  const file = relative(ROOT, path);
  if (!/\.(ts|svelte|css)$/.test(file)) continue;
  const source = readFileSync(path, 'utf8');
  const lines = stripComments(source).split('\n');

  if (file.endsWith('.svelte') && source.split('\n').length > MAX_COMPONENT_LINES) {
    findings.push(`${file}: component exceeds ${MAX_COMPONENT_LINES} lines`);
  }
  if (isTest(file)) continue;
  for (const rule of RULES) {
    if (rule.allowed(file)) continue;
    lines.forEach((line, index) => {
      if (rule.pattern.test(line)) findings.push(`${file}:${index + 1}: ${rule.name}`);
    });
  }
}

if (findings.length > 0) {
  console.error(findings.join('\n'));
  console.error(`\nlint-source: ${findings.length} finding(s)`);
  process.exit(1);
}
console.log('lint-source: no findings');
