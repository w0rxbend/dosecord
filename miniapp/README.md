# Dosegram Mini App

The Telegram Mini App of Dosegram: Svelte 5, TypeScript, Vite, pnpm. The specification is
[`docs/MINI_APP.md`](../docs/MINI_APP.md); requirement ids in the source
(`MA-...`) refer to it. This directory has its own Node toolchain and is not a Mill module.

## Commands

Run from the repository root (ROADMAP 0.6, frontend gate):

```sh
pnpm --dir miniapp install --frozen-lockfile
pnpm --dir miniapp run check   # svelte-check, then scripts/lint-source.mjs
pnpm --dir miniapp run test    # unit and component tests against the fake TelegramPort
pnpm --dir miniapp run build   # vite build, then scripts/check-budget.mjs
pnpm --dir miniapp run api:check     # src/api/schema.d.ts must equal the types of api/openapi.json
pnpm --dir miniapp run api:generate  # rewrite src/api/schema.d.ts after the OpenAPI document changed
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

### Against a real backend

The fake port can talk to a backend on the developer's machine instead of the mock. The backend
runs with `ENVIRONMENT=development`, `MINIAPP_ENABLED=true`, `MINIAPP_HMAC_FALLBACK=true` and a
development value in `TELEGRAM_TOKEN` (never the token of a real bot), and the account must exist
with a Telegram identity for the user id used below.

```sh
export VITE_API_PROXY=http://localhost:8081
export VITE_DEV_INIT_DATA="$(DEV_BOT_TOKEN=<the same development token> node miniapp/scripts/dev-init-data.mjs <telegram user id>)"
pnpm --dir miniapp run dev
```

The launch data is accepted for 300 seconds; after that the gate "This session has ended" appears
and the two variables are set again.

## Launch and session

`src/app/start.ts` runs MINI_APP 3.2: outside Telegram and on a client below 8.0 it shows a gate
screen at once; otherwise it exchanges the launch data for a session (`POST /api/v1/session`) and
mounts the shell only when that succeeds. Gate screens (3.5): G-1 outside, G-2 old client, G-3
account required, G-4 session ended, G-6 no connection with "Try again". The session token lives in
a closure of `src/api/http.ts`; a 401 inside the 300-second window is answered by one silent
exchange, a later one by gate G-4.

## Layout

| Path | Holds |
|---|---|
| `src/platform/` | `TelegramPort`, the real adapter, the fake, the theme adapter. Nothing else touches `window.Telegram`. |
| `src/api/` | The `ApiClient` interface and the types the screens use; `schema.d.ts`, generated from `api/openapi.json`; `wire.ts`, which maps one to the other; the HTTP client. Nothing else calls `fetch`. |
| `src/mock/` | The mock backend: synthetic data, schedule preview, chart scenes. |
| `src/app/` | Shell, router, tab bar, gate screen. |
| `src/screens/` | Today, medication list, medication editor, schedule builder, insights. |
| `src/ui/`, `src/charts/` | Shared components and the scene painter. |
| `src/forms/` | Rule tables shared by the forms and the mock backend. |
| `src/styles/tokens.css` | Semantic tokens; the only place for literal colours besides the theme adapter. |

## Known limits

- The server keeps a name, a dose and notes for a medication, and one dose per schedule. The
  option resources say so, and the forms then leave out form, strength, colour, shape, the
  reminder-name switch and the dose per time. The mock backend still offers all of them.
- The server has no action route for a dose yet. Over HTTP a day is read-only and says that
  reminders are answered in the chat; hydration and insights answer "not available yet".
- Gate G-5 (a newer build is required) is not built: the bootstrap document carries no minimum
  build yet. The gate actions are "Close" and "Try again"; the links into the chat need the bot
  username, which does not exist yet.
- The application has not been run in a Telegram client or under the production content security
  policy in a browser; both belong to the live checks of ROADMAP G1.3 and G3.12.
- The mock schedule preview knows calendar days only; clock-change notes come from the server.
