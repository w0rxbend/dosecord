# Deploying the Mini App

Status: rewritten on 2026-11-27 as the owner's go-live checklist, for the live host that runs the bot from
`.live/run.sh`. Every command below was rehearsed on this machine against throwaway containers (section 9);
nothing was run against a public host, a real certificate or a Telegram client. The decision behind it is the
addendum "production from checkpoint CP3-a" of [ADR-016](adr/ADR-016.md); the security rules are ARCHITECTURE
sections 9.1, 9.2 and 9.8 and MINI_APP section 13.

Once sections 2 to 4 are done, turning the Mini App on is one start:

```sh
MINI_APP_URL=https://<host>/app/ LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh
```

and turning it off again is the same start without `MINI_APP_URL` (section 8).

## 1. What runs where

One process serves the bot, the Mini App and its API. It opens two listeners:

| Listener | Settings | On the live host | Serves | Reached by |
|---|---|---|---|---|
| Public | `HTTP_ADDRESS`, `HTTP_PORT` | `127.0.0.1:18082` | `/app/` (the built Mini App) and `/api/v1/` | The TLS proxy only |
| Health | `HEALTH_ADDRESS`, `HEALTH_PORT` | `127.0.0.1:18081` | `/healthz`, `/readyz`, `/metrics` ([OPERATIONS.md](OPERATIONS.md)) | The host |

The application sets every security header itself (content security policy with
`frame-ancestors https://web.telegram.org`, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy`,
`Cross-Origin-Resource-Policy`, `Cache-Control`), so the proxy adds none and removes none, and it sends no CORS
header. Every path outside `/app/` and `/api/` answers 404. The proxy terminates TLS, forwards `/app/` and `/api/`
and nothing else, limits request bodies to 64 kB and writes no access log (a request line can hold a record id).

## 2. Once: the domain

1. Choose a host name, for example `dosegram.example.com`, and point an `A` record (and `AAAA` if the host has
   IPv6) at the host.
2. Check from another network: `dig +short dosegram.example.com` prints the host's address.
3. Open TCP 80 and 443 to the host (80 is needed for the certificate and the redirect). Nothing else is opened:
   18081 and 18082 stay on the loopback interface.

## 3. Once: the proxy and its certificate

Both configurations are committed under `docker/proxy/`; `MINIAPP_HOST` is the host name and `MINIAPP_UPSTREAM`
the public listener (`127.0.0.1:18082`). Use one of them.

### Caddy (obtains and renews the certificate itself)

```sh
docker run -d --name dosegram-proxy --restart unless-stopped --network host \
  -e MINIAPP_HOST=dosegram.example.com -e MINIAPP_UPSTREAM=127.0.0.1:18082 \
  -v "$PWD/docker/proxy/Caddyfile:/etc/caddy/Caddyfile:ro" -v dosegram_caddy_data:/data \
  caddy:2.10.2-alpine
```

Run it from the repository root of the checkout you keep (not a release worktree you will delete). A Caddy
installed from the distribution reads the same file: copy it to `/etc/caddy/Caddyfile` and put
`MINIAPP_HOST=dosegram.example.com` into the service's environment (`systemctl edit caddy`, then
`Environment=MINIAPP_HOST=dosegram.example.com`).

### nginx (with a certificate from certbot)

```sh
sudo certbot certonly --standalone -d dosegram.example.com     # before nginx listens on port 80
MINIAPP_HOST=dosegram.example.com MINIAPP_UPSTREAM=127.0.0.1:18082 \
  envsubst '${MINIAPP_HOST} ${MINIAPP_UPSTREAM}' < docker/proxy/nginx.conf.template \
  | sudo tee /etc/nginx/conf.d/dosegram.conf >/dev/null
sudo nginx -t && sudo systemctl reload nginx
```

Renewal: `sudo certbot renew --deploy-hook 'systemctl reload nginx'` from certbot's timer (switch the renewal to
`--webroot` or stop nginx for it, since nginx now holds port 80). Do not add `add_header` lines for security
headers: a browser applies the stricter of two policies, and `scripts/miniapp-preflight.sh` fails a second
`Content-Security-Policy` or any `X-Frame-Options`.

## 4. Once: the start script and the build

1. In `.live/run.sh`, replace the line `export MINIAPP_ENABLED=false` with:

   ```sh
   if [[ -f "$WT/scripts/miniapp-env.sh" ]]; then . "$WT/scripts/miniapp-env.sh" || exit 1; else export MINIAPP_ENABLED=false; fi
   ```

   `scripts/miniapp-env.sh` belongs to the release being started. With `MINI_APP_URL` set it exports
   `MINIAPP_ENABLED=true`, `HTTP_PORT=18082`, `HTTP_ADDRESS=127.0.0.1` and `MINIAPP_STATIC_DIR` (the release's
   `miniapp/dist`), and logs `miniapp: on at <url>, public listener 127.0.0.1:18082`; without it, it exports
   `MINIAPP_ENABLED=false` and logs `miniapp: off`. An address that is not `https://<host>/app/`, or a release
   whose `miniapp/dist` is not built, stops the start before Java runs. A release older than this file starts with
   the Mini App off. The script already sets `TELEGRAM_BOT_ID` (launch data is verified for it with Telegram's
   own key; production refuses a test key and the HMAC fallback) and `RESVG_PATH` (the share builder's preview
   is drawn by it).
2. Build the Mini App in each release worktree, next to `./mill app.assembly` in step 3 of the deploy sections of
   OPERATIONS.md:

   ```sh
   pnpm --dir .live/wt-<sha>/miniapp install --frozen-lockfile
   pnpm --dir .live/wt-<sha>/miniapp run build      # fails when the bundle is over its budget
   ```

## 5. Turn it on

1. Stop the running bot (Ctrl-C or SIGTERM; the log ends with `shutdown: pool closed`).
2. `MINI_APP_URL=https://dosegram.example.com/app/ LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. The log shows
   `miniapp: on at https://dosegram.example.com/app/, public listener 127.0.0.1:18082`, then
   `Mini App and API listening on 127.0.0.1:18082`, and the bot makes the Mini App the menu button of every
   private chat ("App"); `/app` and the Today card carry "Open app".
3. In another shell on the host: `scripts/miniapp-preflight.sh https://dosegram.example.com/app/`. It prints one
   line per check and `every check passed`: the shell answers 200 with one policy that has `default-src 'none'`,
   `script-src 'self' https://telegram.org`, `connect-src 'self'` and `frame-ancestors https://web.telegram.org`,
   HSTS, `nosniff`, `no-referrer`, `no-store`, no CORS header and no `X-Frame-Options`; a built file is cached
   for good; launch data with a forged signature is refused with 401 `unauthenticated` and launch data without a
   signature with 401 `client_too_old`; `/api/v1/today` without a session answers 401; `/`, `/healthz`,
   `/readyz` and `/metrics` answer 404 at the proxy; a 70 kB body is refused with 413; `http://<host>/app/`
   redirects to https; `http://127.0.0.1:18081/readyz` answers 200. From another machine add `--health none`.
   Any `FAIL` line: roll back (section 8) before BotFather gets the address.

## 6. BotFather

The bot's name, commands and default menu button are set by the application at every start; the Mini App itself
is registered by hand (ROADMAP OA-8):

1. `@BotFather` → `/mybots` → the bot → "Bot Settings" → "Configure Mini App" → "Enable Mini App", and enter
   `https://dosegram.example.com/app/` exactly, with `/app/`. This is the Main Mini App: the bot profile gets an
   "Open" button and `https://t.me/<bot>?startapp=<route>` works.
2. Optionally set the splash icon and colours there, and the privacy policy URL under "Bot Settings".
3. Leave "Menu Button" alone: the application replaces it at every start.

The labels of BotFather's menus are from research brief 02 and were not checked against the current BotFather.

## 7. Smoke in Telegram (live check LS-6)

Write the result down per client; never record a step as passed that was not observed.

1. The chat with the bot shows the "App" menu button (reopen the chat if it still shows the command list); it
   opens Today with your data.
2. `/app` and `/today` show "Open app"; both open Today.
3. `https://t.me/<bot>?startapp=water` opens Water; add a glass and see it in `/water` in the chat.
4. Answer a due dose on Today (Taken, then Undo); log a mood check-in, a night on Sleep and a meal with words on
   Food.
5. Insights → "Send a card to your chat": the preview is an image with "Text of the card"; "Send to my chat" puts
   the card into the chat.
6. Reports and export → "Request export", confirm in the chat, then "Download": Telegram saves the ZIP.
7. Telegram Web (web.telegram.org) opens the app inside its frame; a refused frame means the client's origin is
   missing from `frame-ancestors` (ARCHITECTURE section 9.8, open).
8. The bot's log holds no launch data, no token and no request path.

### Deep links

`https://t.me/<bot>?startapp=<route>` opens the app on a screen (MINI_APP section 3.4): `today` (or none),
`water`, `mood`, `moodnew`, `sleep`, `food`, `reminders`, `insights`, `hist`, `reports`, `import`, `meds`, `mednew`,
`settings`, `set_tz`, `set_notif`, `set_privacy`, `set_day`, `setup`. An inline `web_app` button of the bot carries
the same route as the query parameter `route`.

## 8. Roll back

1. Stop the bot and start it without the variable: `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. The log shows
   `miniapp: off (MINI_APP_URL is not set)` and no "Mini App and API listening"; port 18082 refuses connections,
   the menu button returns to the command list at start-up and no app button is shown. An app left open shows
   "No connection."
2. Optionally disable the Main Mini App in BotFather ("Configure Mini App" → "Disable Mini App"), so the profile's
   "Open" button goes too.
3. The proxy may keep running: with nothing on 18082 it answers 502. The session rows stay until the app is
   turned on again and its sweep deletes the expired ones. No schema change is involved.

## 9. Rehearsal

Run on 2026-11-27, on this machine, with throwaway containers on free loopback ports only:

- `scripts/miniapp-rehearsal.sh` (after `./mill app.assembly` and `pnpm --dir miniapp run build`): the production
  assembly with `ENVIRONMENT=production`, the console adapter in place of the token, a throwaway key file and
  PostgreSQL, switched on through `scripts/miniapp-env.sh`; behind `docker/proxy/Caddyfile` (Caddy 2.10.2, its
  local authority) and then `docker/proxy/nginx.conf.template` (nginx 1.30, a self-signed certificate), each
  checked by `scripts/miniapp-preflight.sh`: 26 of 26 checks passed through each proxy. The preflight failed a
  proxy that adds a second policy and `X-Frame-Options`; the log held no launch data or request path; SIGTERM
  ended with `shutdown: pool closed`; the start without `MINI_APP_URL` opened no public listener; an `http`
  address stopped the start before Java ran. 60 checks in all.
- `pnpm --dir miniapp run e2e:https` (with `eval "$(scripts/fetch-resvg.sh)"`): the browser suite through Caddy
  on `https://localhost`, with launch data signed by the suite's Ed25519 key and hashed with a test bot token as
  Telegram does, against the application in its test environment (the only one that accepts a test key):
  `origin` 7 tests (the headers once each over TLS, compression of the built files, the proxy's paths, the
  redirect, the 413, forged and unsigned launch data, Telegram Web framing the app and another site refused by
  `frame-ancestors`), `flows` 10, `a11y` 1, `walk` 1 (a night, a meal with words, the share builder's drawn
  preview and "Send to my chat", an export request), all passed. `pnpm --dir miniapp run e2e:https:screens`
  took every screen at 390 and 360 px in light and dark through the same origin; they were reviewed by eye.
- Defects the review found and fixed in the same change: four switches ("Use quiet hours", "Use a daily goal",
  "Show values in cells", the reminder-name switch of a medication) were drawn as a 24 px ring, because a style
  of each screen overrode the shared switch; and the day arrows of Food were filled black triangles, invisible in
  the dark scheme. The walk checks both.
- Bundle (gzip, MINI_APP 10.2): HTML shell 0.35 KB of 3 KB, initial JavaScript 67.86 KB of 70 KB, initial CSS
  3.76 KB of 15 KB, largest lazy chunk 6.86 KB of 35 KB, all JavaScript 140.42 KB of 250 KB.

Not rehearsed: a public host name, a certificate from a public authority, renewal, BotFather, any Telegram client,
the chat's half of a card and an export (the tap that confirms it), and `frame-ancestors` in Telegram Web itself.

Known behaviour:

- A `HEAD` request for `/app/` answers 404: the static route answers `GET` only. Supervisor decision, 2026-11-28:
  no `HEAD` route is added, because Telegram's clients and the preflight use `GET`; check with `GET`, not
  `curl -I`.
- Open: a process without `RESVG_PATH` and `RENDER_FONTS_DIR` still offers the share builder, and its preview
  answers 404 (the text-only cards lay out no week card). The live start script sets both, so the live host is
  not affected; the builder should be hidden in a text-only process (MINI_APP 7.11).

## 10. In containers

When the application runs as a container next to the proxy, put both on one private network, set
`HTTP_ADDRESS=0.0.0.0` and `HTTP_PORT` in the application, publish only the proxy (`80:80`, `443:443`), and set
`MINIAPP_UPSTREAM=app:<HTTP_PORT>` for the proxy. The container image contains the built Mini App at
`/opt/dosegram/miniapp` and sets `MINIAPP_STATIC_DIR` to it; `docker/Dockerfile` builds it from the frozen lockfile
and fails when the bundle exceeds its budget.
