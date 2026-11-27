// Starts the TLS proxy of the https rehearsal (`pnpm run e2e:https`): Caddy with the site of docker/proxy/Caddyfile, in
// a throwaway compose project on the host's loopback interface, with a certificate from Caddy's own local authority.
// The project is removed, volumes included, when Playwright stops this process.
// Usage: node e2e/proxy.mjs <upstream host:port> <https port> <http port>
import { spawn, spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [upstream, httpsPort, httpPort] = process.argv.slice(2);
if (!upstream || !httpsPort || !httpPort) {
  process.stderr.write('usage: node e2e/proxy.mjs <upstream host:port> <https port> <http port>\n');
  process.exit(2);
}

const compose = resolve(fileURLToPath(new URL('../../docker/proxy/rehearsal.compose.yml', import.meta.url)));
const project = `dosegram-rehearsal-${httpsPort}`;
const env = {
  ...process.env,
  MINIAPP_UPSTREAM: upstream,
  REHEARSAL_HTTPS_PORT: httpsPort,
  REHEARSAL_HTTP_PORT: httpPort,
};
const args = ['compose', '-p', project, '-f', compose, '--profile', 'caddy'];

const down = () => spawnSync('docker', [...args, 'down', '-v', '--remove-orphans'], { env, stdio: 'ignore' });
down();
const child = spawn('docker', [...args, 'up', '--no-log-prefix'], { env, stdio: ['ignore', 'ignore', 'inherit'] });
let stopping = false;
const stop = () => {
  if (stopping) return;
  stopping = true;
  child.kill('SIGTERM');
};
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, stop);
child.on('exit', (code) => {
  down();
  process.exit(stopping ? 0 : (code ?? 1));
});
