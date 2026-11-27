// The https rehearsal's ports (`pnpm run e2e:https`): E2E_ORIGIN=https puts the TLS proxy in front of the backend.
// Fixed defaults, overridable, because every Playwright process loads the configuration and must agree on them.

export interface ProxyPorts {
  readonly https: number;
  readonly http: number;
}

export function proxyPorts(env: Record<string, string | undefined>): ProxyPorts | undefined {
  if (env.E2E_ORIGIN !== 'https') return undefined;
  return { https: Number(env.E2E_HTTPS_PORT ?? 18443), http: Number(env.E2E_HTTP_PORT ?? 18480) };
}
