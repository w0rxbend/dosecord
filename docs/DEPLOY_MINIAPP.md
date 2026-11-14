# Deploying the Mini App

Status: written on 2026-11-13 for the owner's first https origin. Nothing in this guide has been
run against a real bot or a public host: the steps were checked with the container image, a local
stack and a browser suite, not on Telegram. The decision behind it is the addendum "production
from checkpoint CP3-a" of [ADR-016](adr/ADR-016.md); the security rules are ARCHITECTURE sections
9.1, 9.2 and 9.8 and MINI_APP section 13.

## 1. What runs where

One process serves the bot, the Mini App and its API. It opens two listeners:

| Listener | Setting | Default | Serves | Reached by |
|---|---|---|---|---|
| Public | `HTTP_ADDRESS`, `HTTP_PORT` | `127.0.0.1:8081` | `/app/` (the built Mini App) and `/api/v1/` | The TLS proxy only |
| Health | `HEALTH_ADDRESS`, `HEALTH_PORT` | `127.0.0.1:8080` | `/healthz`, `/readyz`, `/metrics` ([OPERATIONS.md](OPERATIONS.md)) | The host, the container health check |

The application sets the security headers itself (content security policy, HSTS, `nosniff`,
`Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Resource-Policy`, `Cache-Control`), so the
proxy adds none and removes none. It sends no CORS header: the app and its API share one origin.
Every path outside `/app/` and `/api/` answers 404. The proxy terminates TLS and forwards
`/app/` and `/api/` and nothing else; `/healthz` is never routed.

The container image contains the built Mini App at `/opt/dosegram/miniapp` and sets
`MINIAPP_STATIC_DIR` to it. `docker/Dockerfile` builds it in its own stage from the frozen
lockfile, with lifecycle scripts off, and the build fails when the bundle exceeds its budget.

```sh
docker build -f docker/Dockerfile -t dosegram:<version> .
```

## 2. Settings

Production refuses `MINIAPP_ENABLED=true` unless `MINI_APP_URL` is an absolute `https` address
with a host and `HTTP_PORT` is set explicitly. It also refuses `MINIAPP_HMAC_FALLBACK=true` and
`MINIAPP_INITDATA_PUBLIC_KEY`: launch data is checked with Ed25519 against Telegram's own key, so
the API never relies on the bot token for it.

| Variable | Value | Note |
|---|---|---|
| `ENVIRONMENT` | `production` | The default |
| `ENABLED_ADAPTERS` | `telegram` | |
| `TELEGRAM_TOKEN_FILE` | path to the secret | Prefer the `_FILE` form |
| `TELEGRAM_BOT_ID` | the bot's numeric id | Launch data is verified for it |
| `TELEGRAM_UPDATES_MODE` | `poll` | No webhook endpoint exists yet (ROADMAP G1.7) |
| `TELEGRAM_ALLOWED_USER_IDS` | your user id | The owner allowlist of G1.6 |
| `MINIAPP_ENABLED` | `true` | |
| `MINI_APP_URL` | `https://<host>/app/` | The address BotFather gets; the menu button and every app button open it |
| `HTTP_PORT` | `8081` | Required in production with the Mini App |
| `HTTP_ADDRESS` | `127.0.0.1`, or `0.0.0.0` in a container | `0.0.0.0` only on a network the proxy alone can reach |
| `HEALTH_ADDRESS` | `127.0.0.1`, or `0.0.0.0` in a container whose health port is published | |

With `MINI_APP_URL` set, the bot makes the Mini App the default menu button of every private
chat at start-up, labelled "App", and the Today card and `/app` carry an "Open app" button.
Without it the menu button is the command list and no app button is shown.

## 3. Reverse proxy and TLS

Both examples serve one host name, redirect plain HTTP to https, forward `/app/` and `/api/` to
the public listener, answer 404 for everything else, limit request bodies to 64 kB (the API's own
limit), and write no access log: a request line can carry a record id, and AGENTS.md forbids
logging request URLs. Compression is applied to the static files only.

### Caddy

Caddy obtains and renews the certificate itself; ports 80 and 443 must reach it.

```caddyfile
dosegram.example.com {
	request_body {
		max_size 64KB
	}

	handle /app* {
		encode zstd gzip
		reverse_proxy 127.0.0.1:8081
	}

	handle /api/* {
		reverse_proxy 127.0.0.1:8081
	}

	handle {
		respond 404
	}
}
```

Caddy writes no access log unless a `log` directive asks for one.

### nginx

Obtain the certificate first, for example with `certbot certonly --webroot` or your provider's
tool, then:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name dosegram.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name dosegram.example.com;

    ssl_certificate     /etc/letsencrypt/live/dosegram.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dosegram.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log off;
    client_max_body_size 64k;

    proxy_http_version 1.1;
    proxy_set_header Host $host;

    location /app {
        gzip on;
        gzip_types text/css text/javascript application/javascript image/svg+xml;
        proxy_pass http://127.0.0.1:8081;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8081;
    }

    location / {
        return 404;
    }
}
```

Do not add `add_header` lines for security headers: nginx would send them next to the
application's own and a browser applies the stricter or the first of two policies.

### In containers

When the proxy runs as a container next to the application, put both on one private network and
publish only the proxy:

```yaml
services:
  app:
    image: dosegram:<version>
    environment:
      HTTP_ADDRESS: 0.0.0.0
      HTTP_PORT: "8081"
      # ...the settings of section 2, with secrets as *_FILE
    networks: [internal]
  proxy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    networks: [internal]
networks:
  internal: {}
volumes:
  caddy_data: {}
```

In the Caddyfile, forward to `app:8081` instead of `127.0.0.1:8081`.

## 4. BotFather

The bot's name, description, command list and default menu button are set by the application;
the Mini App itself is registered by hand (ROADMAP owner action OA-8). Do it for the staging bot
first.

1. Open `@BotFather`, choose `/mybots`, the bot, then "Bot Settings".
2. "Configure Mini App", "Enable Mini App", and enter `MINI_APP_URL` exactly, with `/app/`. This
   is the Main Mini App: it gives the bot profile an "Open" button and makes
   `https://t.me/<bot>?startapp=<route>` work.
3. Optionally set the splash icon and colours there, and the privacy policy URL under "Bot
   Settings".
4. Leave "Menu Button" alone: the application sets it at every start, and a value set in BotFather
   is replaced then.

The labels of BotFather's menus are from research brief 02 and were not checked against the
current BotFather in this run.

### Deep links

`https://t.me/<bot>?startapp=<route>` opens the app on a screen (MINI_APP section 3.4). Routes
that work today:

| Route | Screen |
|---|---|
| `today` (or none) | Today |
| `water` | Water |
| `mood`, `moodnew` | Mood, mood check-in |
| `sleep` | Sleep |
| `reminders` | Reminders |
| `insights`, `hist` | Insights, history calendar |
| `meds`, `mednew` | Medications, new medication |
| `settings`, `set_tz`, `set_notif`, `set_privacy` | Settings and its sections |

An inline `web_app` button of the bot carries the same route as the query parameter `route`.

## 5. Smoke checklist

Run it after every deploy; write the result down, and never record a step as passed that was not
observed. Per Telegram client this is the live check LS-6 (ROADMAP G3.12).

1. `curl -sI https://<host>/app/` answers 200 with `Content-Security-Policy`,
   `Strict-Transport-Security`, `Cache-Control: no-store` and no `Access-Control-Allow-Origin`.
2. `curl -s https://<host>/api/v1/today` answers 401 with `application/problem+json`.
3. `curl -sI https://<host>/healthz` and `curl -sI https://<host>/` answer 404.
4. `curl -sI http://<host>/app/` redirects to https.
5. On the host, `curl -fsS http://127.0.0.1:8080/healthz` answers `ok`, and the log shows
   "Mini App and API listening on" with the address and port you configured.
6. In Telegram, the chat with the bot shows the "App" menu button (reopen the chat if it still
   shows the command list); it opens Today with your data.
7. `/app` and `/today` show "Open app"; both open Today.
8. `https://t.me/<bot>?startapp=water` opens Water.
9. Telegram Web (web.telegram.org) opens the app inside its frame; a refused frame means the
   `frame-ancestors` list needs that client's origin (ARCHITECTURE section 9.8, open).
10. The application log holds no launch data, no token and no request path.

## 6. Rolling back

Set `MINIAPP_ENABLED=false` and remove `MINI_APP_URL`, then restart: the public listener is not
opened, the menu button returns to the command list at start-up, and no app button is shown. An
app left open shows "No connection." The session rows stay until the app is enabled again and
its sweep deletes the expired ones.
