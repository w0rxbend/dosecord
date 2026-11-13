# Dosegram Mini App

The Telegram Mini App of Dosegram: Svelte 5, TypeScript, Vite, pnpm. The specification is
[`docs/dosegram/MINI_APP.md`](../docs/dosegram/MINI_APP.md); requirement ids in the source
(`MA-...`) refer to it. This directory has its own Node toolchain and is not a Mill module.

## Commands

Run from the repository root (ROADMAP 0.6, frontend gate):

```sh
pnpm --dir miniapp install --frozen-lockfile
pnpm --dir miniapp run check   # svelte-check, then scripts/lint-source.mjs
pnpm --dir miniapp run test    # unit and component tests against the fake TelegramPort
pnpm --dir miniapp run build   # vite build, then scripts/check-budget.mjs
pnpm --dir miniapp run dev     # plain browser, http://localhost:5173/app/
```

## Development mode

`dev` needs no Telegram client and no backend. It uses the fake `TelegramPort` and the
in-memory mock backend with synthetic data, and draws a strip of controls: Back, three themes
(light, dark, a deliberately low-contrast one), three data scenarios (typical, empty, error)
and "answer in chat". `?startapp=<route>` opens a route of MINI_APP 3.4, and `?latency=0`
removes the mock's delay.

A production build contains neither the fake port nor the mock backend; the budget check
fails if their markers appear in `dist/`.

## Layout

| Path | Holds |
|---|---|
| `src/platform/` | `TelegramPort`, the real adapter, the fake, the theme adapter. Nothing else touches `window.Telegram`. |
| `src/api/` | The `ApiClient` interface, its types and the HTTP client. Nothing else calls `fetch`. |
| `src/mock/` | The mock backend: synthetic data, schedule preview, chart scenes. |
| `src/app/` | Shell, router, tab bar, gate screen. |
| `src/screens/` | Today, medication list, medication editor, schedule builder, insights. |
| `src/ui/`, `src/charts/` | Shared components and the scene painter. |
| `src/forms/` | Rule tables shared by the forms and the mock backend. |
| `src/styles/tokens.css` | Semantic tokens; the only place for literal colours besides the theme adapter. |

## Known limits of this scaffold

- `src/api/types.ts` is written by hand until the backend publishes its OpenAPI document.
- `src/api/http.ts` has not been run against the real backend.
- The mock schedule preview knows calendar days only; clock-change notes come from the server.
