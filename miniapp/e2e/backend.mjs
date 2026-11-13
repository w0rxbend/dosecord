// Starts the test backend of the browser suite: the real application (dosegram.app.e2eBackend) on a throwaway
// PostgreSQL in Docker, serving miniapp/dist. Mill only resolves the class path; the JVM is this process's child, so
// the signal Playwright sends at the end reaches it.
// Usage: node e2e/backend.mjs <port> <dist dir> <public key hex> <bot id> <telegram user id>
import { spawn, spawnSync } from 'node:child_process';
import { delimiter, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const shown = spawnSync(join(repo, 'mill'), ['--ticker', 'false', 'show', 'app.test.runClasspath'], {
  cwd: repo,
  encoding: 'utf8',
  stdio: ['ignore', 'pipe', 'inherit'],
});
if (shown.status !== 0) {
  process.stderr.write('e2e backend: mill could not resolve the class path of app.test\n');
  process.exit(1);
}
// Each entry is a path reference, "ref:v0:<hash>:<absolute path>".
const classPath = JSON.parse(shown.stdout)
  .map((entry) => entry.replace(/^(q?ref):[^:]+:[^:]+:/, ''))
  .join(delimiter);

const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
const child = spawn(java, ['-cp', classPath, 'dosegram.app.e2eBackend', ...process.argv.slice(2)], {
  cwd: repo,
  stdio: 'inherit',
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
