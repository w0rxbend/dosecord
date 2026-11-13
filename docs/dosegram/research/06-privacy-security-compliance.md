# Dosegram research brief 06: privacy, security and compliance baseline

Status: research input for the Dosegram plan and specification. Not an accepted design.
Date checked: 2026-11-12. Author role: research agent "privacy-security".
Scope: Telegram bot plus Telegram Mini App that stores medication, mood, sleep, food, water and possibly vitals data.

This document contains legal information. It is not legal advice. Nothing here replaces a qualified lawyer or a data protection professional. Every legal statement must be confirmed by counsel before public launch.

## 0. Read this first

### 0.1 The ten decisions this brief recommends

1. Treat every user-entered tracking record as special-category health data. Do not try to carve out "harmless" domains such as water. One class, one set of controls.
2. Treat Telegram as a necessary transport that can read everything sent through the bot. Bot chats are not end-to-end encrypted. Give users a discreet-notification mode and state the limit in the privacy notice.
3. Validate Mini App `initData` with the Ed25519 `signature` and Telegram's public key, not with the bot-token HMAC, wherever the client supplies a signature. A stolen bot token then cannot forge a Mini App login.
4. Exchange validated `initData` (maximum age 5 minutes) for a short-lived opaque server session. Do not send `initData` on every request. Do not use cookies.
5. Encrypt Class A fields (medication names, notes, free text, images) with a per-user data key wrapped by a key held outside the database. Hard delete by destroying the user key (crypto-shredding).
6. Enforce tenant isolation twice: PostgreSQL row-level security keyed on the account, and the per-user data key.
7. Keep an append-only, hash-chained audit log that holds identifiers and outcomes only. It never holds health content.
8. Capture explicit consent per tracking domain with a two-step action in chat, and store a versioned consent receipt.
9. Stay outside medical-device scope. The product records what the user did. It never calculates doses, checks interactions, interprets values or advises on missed doses.
10. Launch as an invite-only beta for adults (18+). Do not take payments at launch. Do not target United States residents until the US health-privacy items in section 14 are closed.

### 0.2 Hard constraints discovered

| # | Constraint | Status |
|---|---|---|
| H1 | Telegram can read all bot traffic, including generated images, captions, button labels and `callback_data`. Server-client encryption only. | VERIFIED-P |
| H2 | The bot token is embedded in every Bot API request URL. Anyone who holds it controls the bot. | VERIFIED-P |
| H3 | The HMAC key for `initData` is derived only from the bot token. Token theft plus HMAC-only validation equals impersonation of any user in the Mini App API. | VERIFIED-P (algorithm), inference |
| H4 | Telegram keeps undelivered updates for up to 24 hours. Inbound recovery time above 24 hours loses user actions. | VERIFIED-P |
| H5 | Telegram's Bot Developer Terms require user data to be "always encrypted at rest and stored separately from its encryption key", deletion on request "without undue delay", a privacy policy, breach alerts to users, and no dataset building or ML training on user data. | VERIFIED-P |
| H6 | Digital goods and services sold through a bot or Mini App must be sold in Telegram Stars (`XTR`). | VERIFIED-P |
| H7 | Since Bot API 10.2, Mini App methods are blocked when called from an origin other than the registered Mini App domain (automatic from 2026-07-20). The Mini App must live on one fixed origin. | VERIFIED-P |
| H8 | A bot cannot read a user's Telegram "last seen". Sleep can only be inferred from the user's interactions with this bot, and that inference is itself health data. | VERIFIED-S |
| H9 | GDPR Article 9 prohibits processing health data unless an exception applies. For a consumer tracker the only workable exception is explicit consent. | VERIFIED-P (text), interpretation |
| H10 | A message already delivered to Telegram cannot be reliably recalled. The existing design records a 48-hour bot delete window. Erasure in Dosegram does not erase chat history on Telegram or on the user's devices. | VERIFIED in docs/DESIGN.md section 2; not re-verified this session |

### 0.3 Evidence labels used in this document

| Label | Meaning |
|---|---|
| VERIFIED-P | Primary source fetched and read on 2026-11-12. URL in section 19. |
| VERIFIED-S | Secondary sources (law-firm notes, community answers, search summaries) checked on 2026-11-12. Primary text was not opened or was blocked. Confirm before reliance. |
| RECALLED | Stable statute or guidance text known to the author. Not fetched this session. Confirm before reliance. |
| ASSUMPTION | Design inference or unverified platform behaviour. Must be tested or confirmed. |
| PROPOSED | A value or control chosen by this brief. The owner or a later ADR may change it. |

## 1. System model and trust boundaries

### 1.1 Components in scope

| ID | Component | Holds | Trust level |
|---|---|---|---|
| C1 | Telegram clients and Telegram cloud | All chat content, delivered images, user profile | External. Independent controller. Not under our control. |
| C2 | Bot gateway (webhook receiver and Bot API sender) | Bot token, webhook secret, transient update bodies | Trusted. Only component that holds the bot token. |
| C3 | Mini App frontend (static assets in a Telegram WebView or iframe) | Session token in memory, rendered health data on screen | Untrusted. The user controls the client and can enable WebView inspection. |
| C4 | Backend API (Mini App API) | Session store, decrypted data in memory per request | Trusted. No bot token (see section 4). |
| C5 | Renderer (image generation) | Snapshot inputs and PNG outputs in memory | Trusted but isolated. No network egress. No database credentials. |
| C6 | PostgreSQL | All persistent data. Class A fields as ciphertext. | Trusted storage. Assumed to leak in a breach scenario. |
| C7 | Object storage | Rendered images, exports, optional photos. Ciphertext. | Trusted storage. Assumed to leak in a breach scenario. |
| C8 | Scheduler and outbox dispatcher | Due occurrences, outbox rows | Trusted. Highest blast radius for wrong-recipient sends. |
| C9 | Key management (KMS or sealed key file) | Key-encryption key | Most trusted. Separate from C6 and C7. |
| C10 | Operator access (owner, CI/CD, cloud console, BotFather account) | Deploy rights, break-glass data access | Trusted human. Largest insider risk. |

### 1.2 Data flow and boundaries

```
 [User device: Telegram app] --MTProto (server-client encrypted)--> [Telegram cloud]  (B0: outside our control)
                                                                    |            ^
                                        HTTPS webhook + secret hdr  |            | HTTPS Bot API (token in URL)
                                                              (B1)  v            |  (B2)
 [Mini App in WebView/iframe] --HTTPS + session token (B3)--> [Edge/TLS proxy] --> [C2 bot gateway]--+
                                                                    |                                |
                                                                    v                                v
                                                             [C4 backend API] <--in-process or--> [C8 scheduler/outbox]
                                                                    |      \                         |
                                                              (B4)  v       \ (B5: typed snapshot)   |
                                                              [C6 PostgreSQL] \--> [C5 renderer] ----+
                                                                    |                 (no egress)
                                                              (B6)  v
                                                              [C7 object storage]        [C9 KMS] (B7)
 [C10 operator] --SSO+MFA, break-glass (B8)--> deploy pipeline, cloud console, admin CLI
```

Trust boundaries: B0 Telegram itself. B1 inbound webhook. B2 outbound Bot API. B3 Mini App to API. B4 application to database. B5 application to renderer. B6 application to object storage. B7 application to key management. B8 human and CI access.

### 1.3 Assets, ranked

1. Health records of every user (confidentiality, then integrity).
2. The bot token and the BotFather-owning Telegram account (control of the product identity).
3. Key-encryption key and per-user data keys.
4. Reminder correctness and timeliness (integrity and availability; a missed or wrong reminder is a product harm).
5. The consent ledger and the audit log (evidence).
6. Operator credentials and the CI/CD pipeline.

## 2. Telegram-specific trust facts

Each row is a fact other agents may design from. Do not upgrade an ASSUMPTION to a fact without a test or a source.

| # | Fact | Status | Source | Design consequence |
|---|---|---|---|---|
| F1 | Cloud chats are stored on Telegram servers, encrypted, with keys held in other data centres in different jurisdictions. Only secret chats are end-to-end encrypted. Secret chats are one-to-one, device-specific and not part of the cloud. | VERIFIED-P | [T9] 3.3.1, 3.3.2; [T11] | Bot chats are cloud chats. Telegram holds message content. No end-to-end option exists for bots. |
| F2 | The FAQ describes end-to-end encryption only for secret chats, and states "Server-client encryption is used in Cloud Chats (private and group chats)." | VERIFIED-P | [T11] | Same as F1. The privacy notice must say this in plain words. |
| F3 | Bots receive the messages a user sends them, the public profile (display name, username, profile photo), interface language, and the IP address if the user opens a link to the bot's own site. | VERIFIED-P | [T9] 6.2, 6.3 | Minimise what is stored from the profile (section 7). |
| F4 | A Mini App automatically receives the user's IP address and may receive user ID, name, username, profile picture, language tag, premium status and theme colours. | VERIFIED-P | [T8] 4.1 | The API sees IP addresses. Treat as personal data. Do not persist raw. |
| F5 | For users who registered from Europe, Telegram states their data is stored in data centres in the Netherlands. Controller named: Telegram Messenger Inc. Affiliates in the British Virgin Islands and Dubai. | VERIFIED-P | [T9] 1, 4.1, 8.2 | Sending health content through the bot is a disclosure to a non-EU controller. Disclose it. |
| F6 | Telegram's privacy policy relies on legitimate interests. The fetched text names no EU representative and no minimum age. | VERIFIED-P (absence in fetched text; page served in Ukrainian localisation) | [T9], [T13] | Do not rely on Telegram for age gating. |
| F7 | Bot API requests take the form `https://api.telegram.org/bot<token>/METHOD_NAME`. | VERIFIED-P | [T1] | The token appears in URLs. HTTP client logs, proxies, APM and exception traces can leak it. Redact by construction. |
| F8 | File downloads use `https://api.telegram.org/file/bot<token>/<file_path>`. Link valid at least 1 hour. Download limit 20 MB, upload limit 50 MB on the hosted Bot API. | VERIFIED-S (URL form, 1 hour); VERIFIED-P (20 MB, 50 MB) | [T4], library docs | Never hand a file URL to a client or a log. Download server-side only. |
| F9 | `setWebhook` accepts `secret_token`: 1 to 256 characters, `A-Z a-z 0-9 _ -`. Telegram sends it in the `X-Telegram-Bot-Api-Secret-Token` header on every webhook request. | VERIFIED-P | [T1] | Mandatory check, constant-time compare. |
| F10 | Webhooks: ports 443, 80, 88, 8443 only. TLS 1.2 or newer. IPv6 not supported. Source ranges `149.154.160.0/20` and `91.108.4.0/22`, which "may change". `max_connections` 1 to 100, default 40. | VERIFIED-P | [T1], [T3] | IP allowlist is defence in depth only. The secret header is the control. |
| F11 | A non-2xx webhook response makes Telegram retry and eventually give up. Unretrieved updates are stored for up to 24 hours. | VERIFIED-P | [T1] | Persist first, answer 200, process after. Deduplicate on `update_id`. Inbound RTO ceiling is 24 hours. |
| F12 | Mini App `initData` validation (first-party): `secret_key = HMAC_SHA256(key="WebAppData", msg=<bot_token>)`; compare `hex(HMAC_SHA256(key=secret_key, msg=data_check_string))` with `hash`. The data-check-string is all received fields sorted alphabetically as `key=<value>` joined by line feeds. | VERIFIED-P | [T2] | See H3. |
| F13 | Third-party validation: `signature` is a base64url Ed25519 signature over `<bot_id>:WebAppData\n` followed by the sorted fields excluding `hash` and `signature`. Production public key (hex) `e7bf03a2fa4602af4580703d88dda5bb59f32ed8b02a56c187fe7d34caed242d`. Test key `40055058a4ee38156a06562e52eece92a771bcd8346a8c4615cb7376eddf72ec`. Introduced in Bot API 8.0 (2024-11-17). | VERIFIED-P | [T2], [T6] | The API can authenticate users without holding the bot token. |
| F14 | Telegram instructs developers to check `auth_date` to prevent use of outdated data. It sets no window. `initDataUnsafe` "should not be trusted". | VERIFIED-P | [T2] | We choose the window (section 4). |
| F15 | `WebAppInitData` fields: `query_id`, `chat_join_request_query_id`, `user`, `receiver`, `chat`, `chat_type`, `chat_instance`, `start_param`, `can_send_after`, `auth_date`, `hash`, `signature`. `WebAppUser` fields: `id`, `is_bot`, `first_name`, `last_name`, `username`, `language_code`, `is_premium`, `added_to_attachment_menu`, `allows_write_to_pm`, `photo_url`. | VERIFIED-P | [T2] | Store `id` only. See section 7. |
| F16 | `initData` is empty when a Mini App is launched from a keyboard button or from inline mode. | VERIFIED-P | [T2] | Do not offer those launch paths for the authenticated app. |
| F17 | Mini App storage: CloudStorage 1024 items per user, keys 1 to 128 characters, values up to 4096 characters, stored in Telegram's cloud. DeviceStorage 5 MB per user. SecureStorage 10 items per user, backed by iOS Keychain or Android Keystore (Bot API 9.0, 2025-04-11). | VERIFIED-P | [T2], [T6] | Never put health data or tokens in CloudStorage. SecureStorage may hold an optional app-lock secret. |
| F18 | Users and developers can enable WebView inspection on iOS, Android, Desktop and macOS. | VERIFIED-P | [T2] | The Mini App is a fully user-controlled client. All checks are server-side. |
| F19 | In the test environment Mini Apps may be loaded over plain HTTP. | VERIFIED-P | [T2] | Never register a production bot against a test-only origin. |
| F20 | `downloadFile` from a Mini App requires the file response to carry `Access-Control-Allow-Origin: https://web.telegram.org` for web platforms. | VERIFIED-P | [T2] | Confirms that web clients run the Mini App under the `web.telegram.org` parent origin. |
| F21 | Telegram Web opens Mini Apps in an iframe. A page that sends `X-Frame-Options: DENY` is blocked there. | VERIFIED-S | community reports | Use CSP `frame-ancestors` with an explicit Telegram list, not `X-Frame-Options`. The exact list is an ASSUMPTION to test per client. |
| F22 | Bot API 10.2 (2026-07-14): "Disallowing the usage of Mini App methods from origins different from the original Mini App domain." Automatic for all Mini Apps on 2026-07-20. Opt-out exists in BotFather. | VERIFIED-P | [T6] | One origin. Do not opt out. |
| F23 | CVE-2024-33905: a malicious Mini App could run script in the `web.telegram.org` parent context through `postMessage` in older WebK versions. | VERIFIED-S | [T14] | The embedding boundary has failed before. Keep the Mini App free of third-party script and strict on CSP. |
| F24 | Deep-link `start` parameters: up to 64 characters, `A-Z a-z 0-9 _ -`. | VERIFIED-P | [T5] | Invite and link codes must be single-use, short-lived, and carry no meaning. |
| F25 | Rate limits: about 30 messages per second overall, about 1 message per second per chat, 20 per minute in a group. Paid broadcast raises the ceiling to 1000 per second at 0.1 Stars per message above the free rate, and needs 100,000 Stars and 100,000 monthly active users. | VERIFIED-P | [T4], [T7] 6.2.5 | Outbound throttling is mandatory. It is also an abuse bound. |
| F26 | A bot cannot read a user's last-seen or online status through the Bot API. That exists only in the user-client (MTProto) API, subject to the target's privacy settings. | VERIFIED-S | [T15] | See H8. Using a user-account client to read presence would breach the spirit of F27 and is rejected. |
| F27 | Bot Developer Terms 4.3: data use is limited to what is essential. Prohibited: "any form of data collection aimed at creating large datasets", ML and AI products built on user data, and scraping. | VERIFIED-P | [T7] | No model training on user data. No analytics data lake. |
| F28 | Bot Developer Terms 4.5: credentials must not be made public; the developer is responsible for actions by anyone who authenticates with them. 6.3: the developer must secure the Telegram account that owns the bot. | VERIFIED-P | [T7] | The owning Telegram account is a root credential (section 11). |
| F29 | A new bot token is generated in BotFather when the current one "is compromised or lost". Managed bots can use `getManagedBotToken` and `replaceManagedBotToken` (Bot API 9.6, 2026-04-03). | VERIFIED-P | [T5], [T6] | Rotation of a normal bot token is a manual, human step in a Telegram chat. Plan the runbook around that. |
| F30 | Bots send content with a `protect_content` option that prevents forwarding and saving. | RECALLED | Bot API | Use on report images by default. It does not stop screenshots on every client. |
| F31 | Telegram may delete chats, messages and files at any time and does not guarantee data availability. | VERIFIED-P | [T7] 6.3 | Telegram is never the system of record. |
| F32 | Bots can set a privacy policy in BotFather, surfaced in the bot profile, with the Standard Bot Privacy Policy as default when none is set. | VERIFIED-P for the default rule ([T7] 4, [T10] 2.1); RECALLED for the BotFather setting | [T7], [T10] | Publish an own policy. The standard one relies on legitimate interests and is not adequate for health data. |
| F33 | Latest Bot API at the time of writing: 10.4, released 2026-11-09. | VERIFIED-P | [T1], [T6] | Pin and record the API level the design was checked against. |

## 3. STRIDE threat model

Method: STRIDE per component, as required by the brief. Severity is a qualitative product of impact on health-data confidentiality or reminder integrity and likelihood for an internet-facing consumer bot. H = high, M = medium, L = low. Every H threat has a launch-blocking control in section 16.

### 3.1 Bot webhook and Bot API channel (C2, B1, B2)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| WH-1 | S | Forged webhook POST injects fake updates (fake "taken" taps, fake `/start` for another user ID). | H | Require `X-Telegram-Bot-Api-Secret-Token`, constant-time compare, 32 random bytes base64url. Reject before parsing. Webhook path contains no token and no guessable secret. Source-IP allowlist of F10 as a second layer. |
| WH-2 | S | Stolen bot token: attacker calls `setWebhook` to redirect updates, reads user messages, sends phishing as the bot, downloads user files. | H | Token only in C2. Never in the Mini App API, renderer, CI logs or client. Outbound HTTP logging off or path-redacted. Periodic `getWebhookInfo` check alerts when URL or IP differs from expected. Rotation runbook (section 13). |
| WH-3 | T | Replay of a captured genuine update. | M | Persist `update_id` with a unique constraint. Idempotent handlers. Signed callback tokens (ADR-006) with an occurrence epoch. |
| WH-4 | T | Tampered `callback_data` from a modified client. | M | HMAC-signed callback tokens (ADR-006 carried over). Verify before any state change. The token subject must belong to the account resolved from `from.id`. |
| WH-5 | R | User disputes that an intake was recorded or changed. | M | Append-only action log keyed by `update_id`, with source channel. No message body stored in the audit log. |
| WH-6 | I | Update bodies (message text with medication names) written to access logs, error trackers or dead-letter queues. | H | No request-body logging at the edge or in the app. Inbox table stores the raw update encrypted and purges it after processing (PROPOSED: 24 hours maximum). Error reports carry `update_id` and type only. |
| WH-7 | I | Health content sent to the wrong chat because of a recipient mix-up. | H | Outbox row stores `account_id` and `chat_id`. Dispatcher re-reads the binding in the send transaction and refuses on mismatch. Property test over interleaved accounts. Alert on any refusal. |
| WH-8 | I | Bot added to a group, then posts personal data there. | M | Private chats only. Leave any group on join. Ignore non-private chat types. Disable "allow groups" in BotFather. |
| WH-9 | D | Update flood from many accounts or one account. | M | Per-user and global token buckets at the gateway. Body cap (PROPOSED 1 MiB). `max_connections` tuned to worker capacity. Persist-then-ack keeps the handler fast. |
| WH-10 | D | Telegram 429 or outage blocks reminders. | M | Outbox with `Retry-After` honoured, priority classes, per-chat pacing at 1 message per second. Reminder lateness SLO alert. |
| WH-11 | E | A crafted message body reaches an interpreter (SQL, shell, template, HTML parse mode). | H | Parameterised SQL only. Escape all user text for the chosen parse mode. No user text in file paths or template names. |
| WH-12 | S | Look-alike bot impersonates Dosegram and harvests data. | M | Publish the canonical username in the notice and site. Never ask for passwords or codes in chat. Report clones to Telegram. |

### 3.2 Mini App frontend (C3, B3)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| MA-1 | S | Forged or replayed `initData` used to log in as another user. | H | Server-side signature validation (section 4). `auth_date` freshness of 5 minutes. Never trust `initDataUnsafe`. |
| MA-2 | S | `initData` leaks through the URL fragment into analytics, error reports, referrers or screenshots of debug tools. | H | No third-party scripts except `telegram-web-app.js`. `Referrer-Policy: no-referrer`. Own error reporter strips URL and fragment. Short freshness window bounds the damage. |
| MA-3 | T | XSS through user-entered medication names or notes rendered in the app. | H | Framework auto-escaping. No `innerHTML`. CSP with no inline script and Trusted Types. Server stores text as text, never as HTML. |
| MA-4 | T | Hostile parent frame (clickjacking, UI redress, hostile `postMessage`). | M | `frame-ancestors` limited to Telegram origins. Validate `event.origin` on any `message` listener the app adds. Destructive actions need a second confirmation. |
| MA-5 | R | User denies an edit or delete made in the app. | L | Server audit event per mutation with session ID and channel `miniapp`. |
| MA-6 | I | Health data cached on the device or in Telegram CloudStorage. | M | `Cache-Control: no-store` on API responses. No health data in CloudStorage, DeviceStorage or `localStorage`. No service-worker cache of API data. |
| MA-7 | I | Shoulder surfing and shared-device access after Telegram is unlocked. | M | Optional app lock (section 4.6). Auto-hide sensitive screens after idle. Recommend Telegram passcode lock and two-step verification in onboarding. |
| MA-8 | D | Script-driven API flooding from an inspected WebView. | M | Per-session and per-account rate limits. Request size limits. Pagination caps. |
| MA-9 | E | Client-side role checks bypassed (caregiver sees owner-only fields). | H | Authorisation only on the server. The API returns only fields the grant allows. |
| MA-10 | T | Compromised frontend build or dependency ships a data-exfiltrating script. | H | Lockfile, script-free installs, dependency review, CSP `connect-src 'self'` so exfiltration to another host is blocked by the browser, signed build provenance. |

### 3.3 Backend API (C4)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| API-1 | S | Session token theft and reuse. | H | Opaque random token, stored hashed, idle 15 minutes and absolute 12 hours (PROPOSED), bound to account and to the `initData` hash, revocable. Sent in the `Authorization` header only. |
| API-2 | E | Broken object-level authorisation (IDOR): `/medications/{id}` for another account. | H | Every query carries the account predicate. Row-level security as a second layer. Unknown and foreign IDs both return 404. Automated cross-tenant tests for every route. |
| API-3 | E | Broken function-level authorisation: caregiver calls an owner-only mutation. | H | Policy function per operation, deny by default, tested with a role-by-operation matrix. |
| API-4 | T | Mass assignment (client sets `account_id`, `role`, `consent`). | M | Strict request schemas, unknown properties rejected, server-owned fields never read from the body. |
| API-5 | T | Injection through search, sort or filter parameters. | H | Parameterised SQL. Allowlisted sort keys. |
| API-6 | R | Export or delete disputed. | M | Audit events `data.export.requested/completed`, `account.delete.requested/confirmed/completed` with a receipt ID shown to the user. |
| API-7 | I | Verbose errors, stack traces or SQL fragments in responses. | M | Generic problem responses with a correlation ID. Details in server logs only, after redaction. |
| API-8 | I | Bulk exfiltration through export or large list endpoints after session theft. | H | Step-up confirmation in the bot chat for export and delete. Export rate limit (PROPOSED 3 per 24 hours). Export delivered once, link expires in 15 minutes. |
| API-9 | D | Expensive endpoints (analytics ranges, image renders) abused. | M | Range caps, per-account concurrency of 1 for renders, queue bound, deadlines. |
| API-10 | S | CSRF. | L | No cookies, so no ambient credential. Mutations require the bearer header and JSON content type. |
| API-11 | I | CORS misconfiguration exposes the API to other origins. | M | Serve the API on the same origin as the Mini App. Send no CORS headers at all. |

### 3.4 Renderer (C5)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| RN-1 | T/E | Injection through text drawn into images when an SVG, HTML or template pipeline is used (script, external entity, `xlink:href`, CSS `url()`). | H | Typed snapshot input only. Draw text through a text API, never by string-building markup. If any markup stage exists, escape, disable external resource loading, and run with no network. |
| RN-2 | I | Server-side request forgery: renderer fetches a URL from user data (fonts, images, avatars). | H | No network egress for the renderer process. Fonts and assets bundled. No remote avatars. |
| RN-3 | I | Cross-user leak through shared render state, temp files, caches or object pools. | H | No on-disk temp files (or a per-job tmpfs wiped after use). No cache keyed on anything but an account-scoped key. Test with interleaved accounts and pixel assertions. |
| RN-4 | D | Decompression or layout bomb: huge ranges, very long strings, many series. | M | Input bounds validated before render (range, series count, string length). Output cap (existing design: 2 MB PNG). Deadline and memory limit per job. |
| RN-5 | E | Native image or font library memory-safety bug. | M | Prefer memory-safe rendering in the JVM. If native libraries are used, run the renderer as a separate non-root process with seccomp, read-only filesystem, no credentials. |
| RN-6 | I | Image metadata leaks (creator, timestamps, internal IDs). | L | Strip metadata. Deterministic encoder settings. |
| RN-7 | R | A user disputes what an image showed. | L | Store the snapshot hash and renderer version with the outbox row, not the image, beyond its time to live. |

### 3.5 Database (C6)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| DB-1 | I | Database dump or backup leaks. | H | Class A fields encrypted at field level with per-user keys. Volume encryption underneath. Backups encrypted with a separate key. |
| DB-2 | I | Operator or compromised application role reads another tenant. | H | Row-level security with `FORCE`. Application role is not the table owner and has no `BYPASSRLS`. Separate migration role. |
| DB-3 | T | Ciphertext swapped between rows or users. | M | AES-256-GCM with associated data binding `account_id`, table, column and row ID. |
| DB-4 | T/R | Audit or action log edited. | H | INSERT and SELECT only for the application role, trigger rejects UPDATE and DELETE (existing design section 8), hash chain, daily off-host anchor. |
| DB-5 | D | Lock contention or connection exhaustion from a hot tenant. | M | Statement timeouts, pool limits, per-account concurrency limits. |
| DB-6 | E | SQL injection escalates to the migration or superuser role. | H | Least-privilege roles. No superuser at runtime. No dynamic SQL. |
| DB-7 | I | Deleted data persists in WAL, replicas, backups. | M | Crypto-shredding of the user key. Backup retention of 35 days. Restore-time purge list. |

### 3.6 Object storage (C7)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| OS-1 | I | Public bucket or listing exposes images and exports. | H | Private bucket, public access blocked at account level, policy check in CI. |
| OS-2 | I | Leaked presigned URL. | M | No presigned URLs to clients. The API streams objects after an authorisation check. |
| OS-3 | I | Guessable keys reveal user identity or content type. | L | Random 128-bit object keys under an opaque account prefix. No names, no Telegram IDs. |
| OS-4 | T | Object replaced or served to the wrong user. | M | Database row holds the SHA-256 and the owning account. Verify on read. Objects encrypted under the user key, so a cross-user read fails to decrypt. |
| OS-5 | D | Storage exhaustion through uploads or render spam. | M | Per-account quotas, MIME and size validation, lifecycle expiry. |
| OS-6 | R | Disputed deletion. | L | Lifecycle rules plus an audit event with object count, no keys or content. |

### 3.7 Scheduler and outbox (C8)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| SC-1 | T | Duplicate or missed reminders after a crash or clock change. | H | Existing design: materialised occurrences, `FOR UPDATE SKIP LOCKED`, epoch fencing, outbox with bounded duplicate. Injected clock. NTP monitoring. |
| SC-2 | I | Reminder sent to the wrong account (see WH-7). | H | Same control as WH-7. |
| SC-3 | D | Thundering herd at round hours exceeds Telegram limits and delays reminders. | M | Pre-computed pacing, jitter within the tolerance window, priority for reminders over reports. |
| SC-4 | D | Poison row stalls the loop. | M | Per-row savepoints, quarantine state, alert (existing design). |
| SC-5 | E | A job payload names a file path or account that the job then trusts. | M | Jobs carry IDs only. The worker re-authorises against the database. |
| SC-6 | R | No evidence that a reminder was sent. | M | Outbox state transitions retained 90 days (PROPOSED) without content. |
| SC-7 | I | Notification preview on a lock screen reveals a medication name. | M | Discreet mode. In discreet mode the push text is generic and the name appears only after an explicit tap inside the chat. |

### 3.8 Operator access and delivery pipeline (C10, B8)

| ID | STRIDE | Threat | Sev | Control |
|---|---|---|---|---|
| OP-1 | S | Takeover of the Telegram account that owns the bot in BotFather. | H | Telegram two-step verification with a strong password and recovery email, passcode lock, no shared sessions, a dedicated account for bot ownership (PROPOSED). |
| OP-2 | S | Takeover of source hosting, CI or cloud accounts. | H | Phishing-resistant MFA (security key), no long-lived cloud keys in CI (OIDC federation), branch protection, signed commits optional. |
| OP-3 | I | Operator browses user health data. | H | No standing access to plaintext. Admin tooling works on opaque IDs. Decryption requires a break-glass procedure that emits a user-visible audit event. |
| OP-4 | T | Malicious or mistaken migration or admin command destroys or alters data. | H | Reviewed migrations, expand and contract, point-in-time recovery, dry-run mode in the admin command line. |
| OP-5 | R | Operator action without trace. | M | All admin commands write audit events with operator identity and a reason code. Cloud control-plane logs retained. |
| OP-6 | E | CI secret exfiltration through a compromised action or dependency. | H | Actions pinned by commit SHA. Secrets scoped per environment. No secrets on pull-request builds from forks. |
| OP-7 | I | Secrets or user data in the repository, issue tracker or agent transcripts. | M | Secret scanning in CI and pre-commit. AGENTS.md rule kept: never log credentials, message bodies, medication names or notes. Synthetic fixtures only. |
| OP-8 | D | Single operator unavailable during an incident. | M | Written runbooks, automated restore, a documented "safe shutdown" that stops sends but keeps data. |

## 4. Authentication and session design for the Mini App API

### 4.1 Decision

The Mini App authenticates by presenting `initData` once to `POST /api/session`. The server validates it and returns a short-lived opaque session token. All other endpoints accept only that token in `Authorization: Bearer`.

Rejected alternative A: send `initData` on every request and accept it for 24 hours. It is stateless, but it cannot be revoked, it carries profile data into every request and log line, and a leaked value is replayable for the whole window.

Rejected alternative B: a session cookie. Telegram Web runs the Mini App in a third-party iframe (F20, F21), where cookie behaviour depends on `SameSite=None`, partitioning support and each platform's WebView. That behaviour is unverified across Telegram clients (ASSUMPTION), and cookies bring back cross-site request forgery.

Rejected alternative C: a self-contained JWT. It adds a signing key and gives no revocation, for no benefit in a single-backend system.

### 4.2 Validation algorithm

1. Read the raw `initData` query string from the request body. Limit 4096 bytes (PROPOSED).
2. Parse into key and value pairs without re-encoding. Reject duplicate keys.
3. If `signature` is present: build `"<bot_id>:WebAppData\n" + sorted(key=value pairs except hash and signature, joined by "\n")`. Verify Ed25519 with the production public key in F13. The bot ID is configuration, not a secret.
4. If `signature` is absent (client older than Bot API 8.0): PROPOSED default is to reject with an "update Telegram" message. See open question Q4 for the HMAC fallback and where it would run.
5. Check `auth_date`: not more than 300 seconds in the past and not more than 60 seconds in the future (PROPOSED). Telegram defines no window (F14).
6. Require `user` with a numeric `id` and `is_bot` not true. Ignore every other user field for identity.
7. Look up the account by Telegram user ID. No account means the user has not completed `/start` and consent in the bot. Return 403 with a prompt to open the bot. The Mini App never creates accounts.
8. Create a session: 256-bit random token, store `SHA-256(token)`, account ID, `SHA-256(initData)`, created time, last-used time, user-agent family, expiry.
9. Return the token in the response body. The frontend holds it in memory.

Why Ed25519 first: with HMAC validation the verifying service must hold the bot token, and a stolen token lets an attacker mint valid `initData` for any user ID (H3). With Ed25519 the signing key is Telegram's. A stolen bot token still allows phishing through the bot, but not direct API login as a victim. This also keeps the bot token out of the API process entirely.

### 4.3 Session rules (PROPOSED values)

| Property | Value | Rationale |
|---|---|---|
| Idle timeout | 15 minutes, sliding | Mini App sessions are short. |
| Absolute lifetime | 12 hours | Forces a fresh Telegram launch daily. |
| Re-exchange of the same `initData` | Allowed only inside the 5-minute freshness window; returns a new session and revokes the previous one for that `initData` hash | Supports a WebView reload without widening replay. |
| Storage on client | Memory only | Survives nothing. A reload after 5 minutes shows "reopen from Telegram". |
| Concurrent sessions per account | 5 | Phone, desktop, web. Oldest is evicted. |
| Revocation | On account deletion, consent withdrawal, "sign out everywhere", bot blocked, suspected compromise | |
| Transport | TLS 1.2 or newer, HSTS, same origin as the static app | |

### 4.4 Replay analysis

A captured `initData` is replayable for at most 5 minutes and only for session creation. Residual risk: an attacker with the value inside that window gets a session. Single-use enforcement was considered and rejected because a legitimate reload re-presents the same value, and the freshness window already bounds the exposure. The rule in 4.3 revokes the earlier session, so the victim's app fails visibly, which is a detection signal. Log `session.created` with a flag when an `initData` hash is seen twice.

### 4.5 Step-up for sensitive operations

Operations: full data export, account deletion, creating or widening a caregiver grant, turning off app lock, changing the consent set.

Step-up method (PROPOSED): the API creates a pending action and the bot sends a confirmation message with a signed inline button to the user's private chat. The action runs only when the callback arrives through the webhook. This binds the action to a second channel (the Bot API path) and leaves an obvious trace in the chat.

Rejected alternative: a password or emailed code. Passwords are forbidden in chat by AGENTS.md and add a credential store. Email adds a new identifier that the product does not otherwise need.

Limit: both channels depend on the same Telegram account. A full Telegram account takeover defeats this step-up. That residual risk is disclosed in the notice and reduced by the optional app lock.

### 4.6 Optional app lock (Later)

Use Mini App `BiometricManager` with a server-issued token stored through the biometric token mechanism, or a secret in `SecureStorage` (F17). The server requires that token for sessions when the user enabled app lock. Status: ASSUMPTION that all target clients support it; it must be feature-detected. It protects against a person holding the unlocked phone, not against malware.

### 4.7 Bot-side identity

Identity in chat is the `from.id` of an update that arrived over the authenticated webhook. Telegram is the only identity provider. The existing password, Argon2id and cross-vendor link-challenge machinery (ADR-007) is not needed for a Telegram-only product, and removing it removes a credential store. This is an input for the architecture brief, not a decision made here.

Account recovery follows from that: losing the Telegram account means losing access. PROPOSED mitigation is easy export, not a second credential. See open question Q6.

## 5. Authorisation model

### 5.1 Principals and roles

| Role | Who | Can |
|---|---|---|
| Owner | The Telegram user whose data it is | Everything on the own account. |
| Caregiver-notified | Another Telegram user with a grant | Receive missed-dose or check-in alerts for scoped items. Sees nothing else. |
| Caregiver-viewer | Another Telegram user with a grant | Read scoped summaries in the Mini App. No notes. No mood unless granted. No writes. |
| Operator | The service owner | No routine data access. Break-glass only (section 11). |
| System | Scheduler, renderer, dispatcher | Acts on behalf of an account with the account context set. |

Decision: role checks plus a per-grant scope set (attribute-based on domain and item), evaluated by one policy function in the core. Rejected alternative: a general policy engine such as OPA. It is not justified for one service and five roles.

### 5.2 Caregiver and shared access (recommended as "Later", modelled now)

If adopted, the rules are:

1. A grant is created only by the owner, through an invite deep link with a single-use, 128-bit, hashed code that expires in 24 hours and fits the 64-character start parameter (F24).
2. The grantee must accept in their own private chat with the bot and must have an account (adult, consented to the terms).
3. A grant names domains (medication, mood, sleep, food, water, vitals), optional item IDs, a level (notified or viewer) and an expiry. Maximum 12 months, then the owner must renew (PROPOSED).
4. The owner sees every grant and every access under it, and can revoke in one tap. Revocation ends the grantee's sessions for that account at once.
5. Granting is a separate explicit consent to disclose health data to a named recipient. It is recorded in the consent ledger.
6. Alerts sent to a caregiver travel through Telegram to a second account. In discreet mode they contain no medication name.
7. No caregiver write access in the first version. It complicates the action log and non-repudiation.
8. No operator-created grants. No "emergency access" override.

Dependent profiles (an adult tracking a child's or parent's medication under their own account) are a different feature. The data subject is then a third person who has not consented. See open question Q7.

### 5.3 Enforcement points

1. Mediator and API: resolve principal, then call the policy function with operation, resource owner and grant set.
2. SQL: every statement includes the account predicate. Mutations put the predicate in the `UPDATE` or `DELETE` itself, never check-then-write.
3. Database: row-level security (section 12).
4. Cryptography: data is decryptable only with the owner's data key, which the key service releases for the owner's account context or a valid grant.

## 6. Data classification

| Class | Name | Examples | Handling |
|---|---|---|---|
| A | Health content | Medication names, strengths, forms, notes, free-text mood notes, food descriptions, photos, symptom text, rendered images, exports | Field-level encryption with the user key. Never in logs, metrics, traces, audit, analytics, error reports or support tickets. Shown only to the owner or a scoped grantee. |
| B | Health facts without free text | Intake events and their times, schedules, mood scores, sleep intervals, water and food quantities, vitals values, adherence aggregates, consent set | Special-category data. Tenant-isolated by row-level security. Encrypted at rest at volume level. Field-level encryption where cheap (PROPOSED: vitals values). Never in logs or metrics labels. |
| C | Personal identifiers | Telegram user ID, chat ID, language, time zone, country of residence (self-declared), session records, IP address | Personal data. Pseudonymised in logs by the internal account UUID. IP never stored raw beyond rate-limit memory. |
| D | Operational | Outbox states, job timings, counters, feature flags | No personal content. Account UUID permitted. |
| S | Secrets | Bot token, webhook secret, callback keys, key-encryption key, database credentials | Section 11. |

Decision: classes A and B together are "health data" for every legal and security purpose. Water, food and sleep are included. Rationale: the Court of Justice of the European Union reads "data concerning health" broadly, including data from which health information can be inferred (C-184/20; C-21/23 Lindenapotheke on medicine orders; RECALLED). Washington's statute expressly covers inferred data (section 14.6). A mixed regime would also be harder to implement correctly than a uniform one. Rejected alternative: treat water and food as ordinary personal data to simplify consent. The saving is small and the classification is contestable.

A schedule's timing alone is Class B. A reminder at 08:00 and 20:00 every day reveals a treatment pattern even without a name.

## 7. Data minimisation

### 7.1 What is not collected

| Item | Decision | Reason |
|---|---|---|
| Telegram first name, last name, username, photo URL, premium flag | Not stored. Used transiently for greeting if at all. | Not needed. Telegram can supply them on each update. |
| Phone number, contacts, precise location | Never requested. | No feature needs them. Time zone is chosen, not derived from location. |
| Date of birth | Not collected. A yes/no adult attestation only. | Age gate without storing age. |
| Email | Not collected at launch. | No feature needs it. |
| Raw IP address | Not persisted. Rate limiting in memory. Audit stores a keyed, truncated hash rotated daily (PROPOSED). | Abuse detection without a location trail. |
| Raw webhook update bodies | Deleted after successful processing, 24 hours at most, encrypted while held. | They contain message text. |
| Diagnosis, condition, prescriber, pharmacy | No fields for them. | Not needed to remind or record. |
| Device fingerprints, advertising IDs, third-party analytics | None. | Bot Developer Terms 4.3 and Article 9. |
| Telegram presence ("last seen") | Not available and not sought (F26). | Platform limit and proportionality. |

### 7.2 Sleep inferred from bot activity

The owner's idea is to derive sleep from "last seen". The Bot API does not expose it (F26). The only available signal is the timestamps of the user's own interactions with this bot and Mini App. Rules:

1. Off by default. A separate explicit opt-in that explains exactly which signal is used and how crude it is.
2. Presented as an estimate the user confirms or corrects. Never recorded as a fact without confirmation (PROPOSED).
3. The derived interval is Class B. The raw interaction timestamps are not kept for this purpose beyond 48 hours (PROPOSED).
4. No inference about sleep disorders, and no comparison with norms (section 14.9).

Rejected alternative: a user-account (MTProto) client that reads presence. It would require the user's Telegram credentials or a contact relationship, conflicts with the Bot Developer Terms' minimisation rule, and is disproportionate.

### 7.3 Content sent through Telegram

Everything the bot sends is copied to Telegram's cloud and to each of the user's devices (F1). Rules:

1. Discreet mode (PROPOSED: a forced choice at onboarding, no preselected option). On: reminder text is generic ("Time for your 08:00 item"), names appear only after the user taps inside the chat. Off: names are shown.
2. `callback_data`, deep-link parameters and file names carry opaque tokens only.
3. Report images use `protect_content` by default (F30) and carry no account identifiers.
4. Caregiver alerts never contain notes.
5. The bot never echoes back a full medication list unless asked.
6. A "clear chat history" helper deletes bot messages younger than 48 hours where the platform allows it (H10) and tells the user how to clear the rest themselves.

## 8. Encryption and key management

### 8.1 Decision

Envelope encryption with a per-user data-encryption key (DEK).

1. Algorithm: AES-256-GCM, 96-bit random nonce per value, associated data = `account_id || table || column || row_id || key_version`.
2. Each account has one active DEK (256-bit random). It is stored wrapped by the key-encryption key (KEK) in a `user_keys` table.
3. The KEK lives outside the database and outside the database backups. Option 1 (PROPOSED for a hosted deployment): a cloud KMS or HashiCorp Vault Transit key; the application never sees the KEK and calls wrap and unwrap. Option 2 (self-hosted single node): a 256-bit key file delivered as a Docker secret from a separate secret store, with an offline copy held by the owner. Either option satisfies Telegram's "stored separately from its encryption key" clause (H5) only if the KEK is not on the same volume or in the same backup as the data.
4. Unwrapped DEKs are cached in process memory for at most 5 minutes (PROPOSED), bounded in count, never logged, never serialised.
5. Class A fields are always encrypted. Objects in storage are encrypted with the same DEK using a streaming AEAD construction, plus provider-side encryption underneath.
6. Rotation: KEK every 12 months, with rewrap of all DEKs (cheap). DEK rotation on demand and on suspected compromise, with lazy re-encryption. Each ciphertext carries a key version.
7. Hard delete: destroy the wrapped DEK. All of that user's Class A ciphertext, including copies in backups and WAL, becomes unreadable.

Rejected alternative A: disk or transparent database encryption only. It does not protect against SQL injection, a leaked logical dump, a compromised application role or an operator with database access, and it gives no way to erase data inside backups.

Rejected alternative B: client-side end-to-end encryption in the Mini App. The server must read names to send reminders and to render images, and the bot channel is not end-to-end encrypted in any case (F1). It would promise a property the system cannot deliver.

Rejected alternative C: one application-wide data key. It protects against dump leaks but provides neither per-tenant blast-radius limits nor crypto-shredding.

### 8.2 Search and autocomplete over encrypted names

The existing design autocompletes over a normalised name column. Decision: no searchable ciphertext. The application decrypts the user's own registry (expected tens of rows) in memory and filters there. Rejected alternative: deterministic encryption or a blind index. Both leak equality across rows and add key material for no gain at this scale.

Consequence for other agents: a unique-name constraint cannot be enforced by a database index on plaintext. Enforce it in the application inside the account's transaction, or with a keyed per-user HMAC column (acceptable: the key is per user, so equality leaks only within one account).

### 8.3 What is deliberately not field-encrypted

Class B timestamps, quantities and foreign keys stay in plaintext columns so that the scheduler and analytics can run in SQL. They are protected by row-level security, volume encryption, backup encryption and access control. After crypto-shredding they are also deleted from live tables (section 9); what remains in backups is pseudonymous event data with no names, which ages out with the backup.

### 8.4 Transport

TLS 1.2 minimum, TLS 1.3 preferred, on every hop that leaves a host, including application to database when they are on different hosts. HSTS on the public origin. Certificates from a public CA, auto-renewed. The webhook endpoint needs a CA-verified or uploaded certificate whose name matches the host (F10).

## 9. Retention, export and hard delete

### 9.1 Retention schedule (PROPOSED)

| Data | Retention | Trigger for deletion |
|---|---|---|
| Health records (A and B) | While the account is active | Account deletion, per-domain consent withdrawal, or user-set auto-delete window (optional: 1, 2, 5 years, or keep) |
| Rendered images and report snapshots | Existing design: purge within 1 hour of terminal state; queued items until deadline, at most 24 hours | Timer |
| Exports | 15 minutes after ready, single download | Timer |
| Raw webhook updates | Until processed, at most 24 hours | Timer |
| Sessions | Expiry plus 30 days of metadata for abuse analysis | Timer |
| Outbox and delivery states (no content) | 90 days | Timer |
| Application logs (no health content) | 30 days | Timer |
| Security audit log | 13 months | Timer; account-linked rows are pseudonymised on account deletion |
| Consent ledger | Life of the account plus 3 years, minimal fields | Timer. Needed to demonstrate consent after the fact. Period to be confirmed by counsel. |
| Backups | 35 days | Rolling |
| Inactive accounts | Notice at 23 months, deletion at 24 months of no interaction | Timer |
| Unreachable accounts (bot blocked, or Telegram account deleted) | Deletion 90 days after the first permanent delivery failure, reminders paused at once | Timer |

### 9.2 Export (access and portability)

1. Self-service from the bot (`/privacy`) and the Mini App. Step-up confirmation in chat (4.5).
2. Format: one ZIP with JSON (machine-readable, documented schema, one file per domain), CSV per domain, and a human-readable summary. Includes consent history and the access history visible to the user.
3. Delivery decision: download inside the Mini App through the authenticated API. Rejected alternative: sending the export file into the Telegram chat. That would copy the user's complete health history into Telegram's cloud. Offer it only as an explicit, warned opt-in.
4. Limits: 3 exports per 24 hours, generated asynchronously, encrypted at rest, deleted 15 minutes after ready.
5. Target: ready in under 10 minutes. Legal ceiling: one month under GDPR Article 12(3) (RECALLED); 30 days under Telegram's Standard Bot Privacy Policy 7.3(c) (VERIFIED-P); 45 days under Washington RCW 19.373.040 (RECALLED).

### 9.3 Hard delete

Sequence for account deletion:

1. User requests in bot or Mini App. Step-up confirmation in chat.
2. PROPOSED grace period: none by default. Optional 7-day cancellable delay that the user can choose. Reminders stop immediately in both cases.
3. In one transaction: revoke sessions and grants, cancel queued outbox rows and render jobs, mark the account as deleting.
4. Destroy the wrapped DEK (crypto-shred). From this point Class A data is unrecoverable everywhere.
5. Delete rows in all account-scoped tables, including the append-only action log through the erasure-gated path the existing roadmap already plans (ADR-002 amendment, ROADMAP M4.4). Delete objects by account prefix.
6. Pseudonymise remaining audit rows: replace the account ID with a random tombstone ID. Keep a deletion receipt (tombstone ID, time, counts, no content).
7. Add the account ID to a restore-time purge list kept outside the main database, so a restored backup re-applies the deletion before serving traffic.
8. Send a final confirmation message, then stop messaging the user.
9. Backups containing Class B rows expire within 35 days.

What deletion cannot do, and the notice must say: remove messages and images already delivered to Telegram and to the user's devices (H10), or remove copies the user forwarded or a caregiver saved.

Per-domain deletion (for example "delete all mood data") follows steps 5 to 7 for that domain and withdraws that domain's consent.

Legal anchors: GDPR Article 17 erasure "without undue delay" (RECALLED). Bot Developer Terms 4.2 "without undue delay" (VERIFIED-P). Washington: deletion includes notifying processors and third parties, with a limited allowance for archived or backup systems (RECALLED; confirm the six-month figure in RCW 19.373.040).

## 10. Audit log design

### 10.1 Purpose and rule

The audit log answers "who did what to which object, when, through which channel, with what outcome". It must be useful as evidence and safe to read by an operator or an auditor. Rule: it never contains health content. No medication names, notes, values, message text, image bytes, or free text of any kind.

It is separate from the domain action log (the existing append-only `dose_actions` style tables), which is health data and belongs to the user.

### 10.2 Event record

| Field | Content | Notes |
|---|---|---|
| `id` | UUIDv7 | Time-ordered. |
| `occurred_at` | UTC timestamp | Server clock. |
| `event_type` | Enumerated code | Closed list, see 10.3. |
| `actor_type` | `user`, `caregiver`, `operator`, `system` | |
| `actor_id` | Internal account UUID, operator ID, or service name | Never a Telegram ID or username. |
| `subject_account_id` | Internal account UUID whose data was touched | Replaced by a tombstone on deletion. |
| `object_type` | Enumerated: `medication`, `schedule`, `intake`, `mood_entry`, `export`, `grant`, `consent`, `session`, `key`, ... | |
| `object_id` | Opaque UUID | Never a name. |
| `action` | `create`, `read`, `update`, `delete`, `grant`, `revoke`, `export`, `login`, `deny`, ... | |
| `outcome` | `success`, `denied`, `error` | |
| `reason_code` | Enumerated | For denials and operator actions. No free text. |
| `channel` | `bot`, `miniapp`, `admin_cli`, `scheduler` | |
| `request_id` | Correlation ID | Joins to logs and traces. |
| `session_id` | Opaque | Mini App only. |
| `ip_hash` | Keyed hash, truncated, daily key | Mini App and admin only. |
| `policy_version` | For consent events | Hash of the notice text shown. |
| `prev_hash`, `hash` | SHA-256 chain | Tamper evidence. |

Changed-field names may be recorded (for example `fields: ["time", "quantity"]`). Changed values may not.

### 10.3 Event catalogue (minimum)

| Group | Events |
|---|---|
| Authentication | `session.created`, `session.rejected` (reason: bad signature, stale `auth_date`, no account), `session.revoked`, `initdata.reused`, `stepup.requested`, `stepup.confirmed`, `stepup.expired` |
| Authorisation | `access.denied` (operation, object type), `grant.created`, `grant.accepted`, `grant.revoked`, `grant.expired` |
| Data access | `data.read.caregiver` (every caregiver read), `data.export.requested`, `data.export.completed`, `data.export.downloaded`, `data.read.operator` |
| Data change | `record.created`, `record.updated`, `record.deleted` by object type; `account.delete.requested`, `account.delete.completed`, `domain.delete.completed` |
| Consent | `consent.granted`, `consent.withdrawn`, `consent.reprompted`, `age.attested` |
| Security | `webhook.rejected` (bad secret, bad source), `callback.rejected` (bad MAC), `ratelimit.triggered`, `wrong_recipient.blocked`, `webhook.config.drift` |
| Keys and secrets | `key.dek.created`, `key.dek.rotated`, `key.dek.destroyed`, `key.kek.rotated`, `secret.rotated` |
| Administrative | `admin.command` (command code, reason code), `breakglass.opened`, `breakglass.closed`, `backup.completed`, `restore.completed`, `migration.applied` |

Owner reads of the owner's own data are not audited per record. Rationale: volume, and it would create a detailed behavioural trail that is itself sensitive. Rejected alternative: log every read. Caregiver and operator reads are always audited.

### 10.4 Integrity, storage and access

1. Append-only table: the application role has INSERT and SELECT only, and a trigger rejects UPDATE and DELETE (pattern already in docs/DESIGN.md section 8).
2. Hash chain per day partition. A daily job writes the chain head to write-once storage outside the database host (object lock or a second provider). A verification job recomputes the chain daily and alerts on mismatch.
3. Audit writes that record a state change commit in the same transaction as the change. Denials and rejections are written on a separate connection so they survive the rollback.
4. Retention 13 months (PROPOSED). GDPR sets no fixed period; storage limitation applies (RECALLED, Article 5(1)(e)).
5. Users see their own access history (grants, caregiver reads, exports, operator break-glass) in the Mini App.
6. Erasure: on account deletion, `subject_account_id` and `actor_id` values are replaced by a random tombstone. The chain is built over a canonical form that uses a per-account pseudonym rather than the raw ID, so the mapping can be destroyed without breaking the chain (PROPOSED; needs a design spike).

## 11. Logging, telemetry, secrets and operator access

### 11.1 Logging and telemetry redaction rules

Principle: allowlist, not blocklist. A log statement may contain only fields from an approved set. Anything else fails a build-time or test-time check.

| Rule | Detail |
|---|---|
| L1 | Never log: message text, captions, callback payload contents, `initData`, session tokens, bot token, webhook secret, medication names, notes, any Class A or B value, file IDs of user uploads, Telegram usernames or names. |
| L2 | Identify a user in logs only by the internal account UUID. Telegram user IDs and chat IDs are not logged at INFO. |
| L3 | Outbound Bot API calls are logged as method name, outcome class, latency and `retry_after`. The URL is never logged, because it contains the token (F7). HTTP client debug logging is disabled in production and the logger for the Telegram client library is pinned to WARN with a redacting filter. |
| L4 | Exceptions are logged by class and a sanitised message. Exception messages from JSON parsers, SQL drivers and HTTP clients can contain payload fragments, so they pass through a redactor that strips string literals. |
| L5 | Metrics labels come from closed enumerations only. No account IDs, no object IDs, no free text. This also keeps cardinality bounded. |
| L6 | Traces carry span names, enumerated attributes and the account UUID. No request or response bodies. No SQL parameter values. |
| L7 | The edge proxy logs method, route template, status, size and duration. No query strings, no bodies, no `Authorization` header. Client IP is truncated (IPv4 /24, IPv6 /48) or dropped. |
| L8 | Crash dumps and heap dumps are disabled in production, or written only to an encrypted volume and deleted within 24 hours. They contain decrypted data and keys. |
| L9 | Frontend error reporting is first-party only and sends error class, component name and build ID. It never sends the URL, fragment, DOM text or network payloads. |
| L10 | A "log hygiene" test runs in CI: it drives the system with canary strings in every user-supplied field and fails if any canary appears in logs, metrics, traces or audit rows. The existing conformance suite already lists log hygiene as a case; extend it to the Mini App API and the renderer. |
| L11 | Support and debugging use synthetic accounts. Production data is never copied to development or CI. |
| L12 | Third-party telemetry processors receive only Class D data. If a hosted observability vendor is used, it needs a data processing agreement and an EU region. |

### 11.2 Secrets management

| Secret | Holder | Storage | Rotation (PROPOSED) |
|---|---|---|---|
| Bot token | Bot gateway only | Secret manager or Docker secret, mounted as a file, never in environment dumps | On suspicion at once; otherwise every 12 months. Manual through BotFather (F29). |
| Webhook secret token | Bot gateway | Same | Every 90 days by calling `setWebhook`; accept old and new for 10 minutes. |
| Callback HMAC keys | Mediator | Same; key ring with key ID in the token (ADR-006) | Every 90 days, old key valid until the oldest live message that needs it; reminder tokens are long-lived, so keep verification-only keys for the retention window. |
| KEK | KMS, Vault, or key file | Outside database and backups | Every 12 months. |
| Backup encryption key | Backup job | Separate from the KEK | Every 12 months. |
| Database credentials | Application, migrator (two roles) | Secret manager | Every 90 days, or dynamic credentials if Vault is used. |
| Object storage credentials | Application | Workload identity where possible | Every 90 days. |
| IP-hash daily key | Application | Derived, in memory | Daily. |
| CI and registry credentials | CI | OIDC federation, no static cloud keys | Not applicable. |

Rules:

1. No secret in the repository, in container images, in command-line arguments or in `.env` files on shared hosts. `.env` stays gitignored; `.env.example` holds placeholders (the existing design generates it from the settings class).
2. Startup validates presence and shape of each secret without printing values.
3. Secret scanning runs in pre-commit and CI. A secret that reaches a remote is treated as compromised: rotate first, then clean history.
4. The KEK has a tested offline recovery copy under dual custody (for example two sealed copies in separate places). Losing the KEK loses every user's Class A data permanently. This is the main durability risk introduced by section 8.
5. The existing gitignored `.env` in this repository holds Discord credentials. They must be revoked when Discord is removed.

### 11.3 Operator access and break-glass

1. No routine access to production data. Dashboards show Class D only.
2. The admin command line accepts opaque account UUIDs. A user who wants support generates a short support code in the bot; the code maps to their UUID for 24 hours. The operator never searches by Telegram name.
3. Break-glass: a time-boxed (PROPOSED 60 minutes) elevation that allows decrypting a single named account, requires a reason code, writes `breakglass.opened` and `data.read.operator`, and notifies the affected user in chat afterwards unless that would defeat a security investigation.
4. Production shell and database access require phishing-resistant MFA and are logged by the platform.
5. Access review every quarter: list every human and machine identity with production rights. For a solo operator this is a 10-minute checklist, and it still gets done.
6. The Telegram account that owns the bot is protected as in OP-1 and is not used for daily chatting (PROPOSED).

## 12. Isolation, resilience and platform hardening

### 12.1 Multi-tenant isolation

Tenant means one account. There is no organisation level.

1. Every account-scoped table has `account_id uuid NOT NULL` and a row-level security policy `USING (account_id = current_setting('app.account_id')::uuid)` with the same `WITH CHECK`. `FORCE ROW LEVEL SECURITY` is on.
2. The application sets `app.account_id` with `SET LOCAL` at the start of each transaction, from the authenticated principal. A unit-of-work helper is the only way to obtain a connection, so a transaction without a tenant context cannot be opened by ordinary code.
3. Three database roles: `dosegram_app` (no ownership, no `BYPASSRLS`), `dosegram_worker` (scheduler; may select due rows across accounts through narrow `SECURITY DEFINER` functions that return IDs and due times only), `dosegram_migrator` (owner; used only by migrations).
4. Caregiver reads use a second policy that joins the grants table, or run through a function that checks the grant and sets the context to the owner's account with a read-only flag.
5. Cross-tenant test: for every repository method and every API route, a test runs it as account A against account B's IDs and expects not-found. This test set is a release gate.
6. Caches and in-memory maps are keyed by account ID first. The renderer receives one account's snapshot per job and has no access to any shared store.
7. Per-tenant resource limits (12.3) stop one account from degrading others.
8. The per-user DEK is the last line: a query that bypasses both the predicate and row-level security still returns ciphertext for Class A fields.

Rejected alternative: schema or database per tenant. It is operationally heavy for single-user tenants and breaks cross-account scheduler scans.

### 12.2 Backup and restore

| Item | Baseline (PROPOSED) |
|---|---|
| Method | Continuous WAL archiving with point-in-time recovery, plus a daily base backup. A nightly `pg_dump` alone (the existing design) gives an RPO of up to 24 hours and is not sufficient for the targets in section 15. |
| Encryption | Client-side, with the backup key. Class A fields are additionally ciphertext inside the backup. |
| Location | A second site or provider in the same legal region as production. |
| Retention | 35 days. |
| Object storage | Versioning off for user objects, or version expiry at most 35 days, so deletions take effect. |
| Key material | KEK and backup key are backed up separately, offline, under dual custody. Never in the same backup as the data. |
| Restore test | Automated weekly restore to an isolated environment with checks: row counts, chain verification of the audit log, decryption of a canary account, scheduler fold check. Full disaster-recovery exercise every quarter with timing recorded against RTO. |
| Restore-time purge | Before a restored system serves traffic it applies the purge list of accounts deleted since the backup point (9.3 step 7) and re-validates the Telegram webhook configuration. |
| Post-restore reconciliation | Outbox rows in an uncertain state are reconciled before any send, so a restore does not replay old reminders. The existing recovery work (commit 88772f0, "reconcile uncertain outcomes") is the pattern. |

### 12.3 Abuse and rate limiting

Limits are enforced in a shared store (PostgreSQL token bucket as in the existing design, or Redis if introduced) once more than one process serves traffic. In-memory counters alone are valid only for a single process.

| Scope | Limit (PROPOSED) | On breach |
|---|---|---|
| Webhook, per Telegram user | 30 updates per minute, burst 10 | Drop with 200 to Telegram, one throttled notice per 10 minutes |
| Webhook, global | Sized to worker capacity; shed lowest-priority work first | 200 to Telegram, queue |
| `POST /api/session`, per IP prefix | 10 per minute | 429 with `Retry-After` |
| `POST /api/session`, per Telegram user | 6 per minute | 429 |
| API reads, per session | 120 per minute | 429 |
| API writes, per account | 60 per minute | 429 |
| Image renders, per account | 1 concurrent, 20 per day | Text fallback |
| Exports, per account | 3 per 24 hours | Refuse with next-allowed time |
| Caregiver invites, per account | 5 per day, 5 active grants | Refuse |
| Outbound, per chat | 1 message per second (F25) | Queue |
| Outbound, global | 25 messages per second, below Telegram's approximately 30 | Queue by priority |
| Step-up confirmations | 5 pending per account, 10-minute expiry | Refuse |

Per-account quotas bound storage and work: 200 medications, 400 schedules, 500 entries per day across domains, 500 characters per note, 5 MB per photo if photos are adopted, 100 MB of objects per account (all PROPOSED).

Abuse cases to test: mass `/start` from throwaway accounts (cost is bounded because no data is stored before consent); a blocked bot (stop sending on the first 403 and pause the account); invite-link spam (limits above, and invites are useless without acceptance); scripted API use from an inspected WebView (limits above); attempts to use the bot as a spam relay to caregivers (alerts only fire from the owner's own recorded events and are rate-limited per grantee).

Application limits do not absorb network-level floods. Put the public origin behind a provider with volumetric protection. Note that the webhook source ranges are fixed (F10), so the webhook path can be restricted to them at the edge.

### 12.4 Mini App web hardening

Response headers for the static app and the API (PROPOSED starting point; test on every Telegram client before tightening further):

```
Content-Security-Policy: default-src 'none'; script-src 'self' https://telegram.org; style-src 'self';
  img-src 'self' blob: data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none';
  object-src 'none'; frame-ancestors https://web.telegram.org; require-trusted-types-for 'script'
Strict-Transport-Security: max-age=31536000
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()
Cache-Control: no-store            (API responses)
Cross-Origin-Resource-Policy: same-origin
```

Notes:

1. `X-Frame-Options` is omitted on purpose. `DENY` breaks Telegram Web (F21). `frame-ancestors` is the control. The exact ancestor list is an ASSUMPTION; legacy web client hosts may need to be added after testing.
2. `https://telegram.org` in `script-src` is required to load `telegram-web-app.js`. That script is unversioned, so subresource integrity cannot be pinned. This is an accepted dependency on Telegram, which is already inside the trust boundary for transport. Rejected alternative: self-hosting a copy. It would drift from client behaviour and Telegram does not document that as supported (ASSUMPTION).
3. No other third-party origin appears anywhere. No analytics, fonts, maps, tag managers or CDNs.
4. `connect-src 'self'` makes the browser block exfiltration to other hosts even if a script is compromised.
5. The API and the static app share one origin, so no CORS headers are sent.
6. The app reads `initData` once, exchanges it, and does not copy it into application state, storage or logs.
7. If camera access is ever needed for food photos, widen `Permissions-Policy` for that route only.
8. HSTS `includeSubDomains` and `preload` are added only after every subdomain is confirmed HTTPS-only.

### 12.5 Dependency and supply-chain controls

| Area | Control |
|---|---|
| JVM dependencies (Mill) | Exact versions, checksum verification, a committed lock or resolution file, one upgrade PR per dependency group with changelog review. |
| Frontend dependencies | Committed lockfile, frozen installs in CI, lifecycle scripts disabled by default with an explicit allowlist, minimal dependency count, review of new packages for ownership, age and transitive graph. |
| Vulnerability scanning | OSV or equivalent scan on every PR and nightly on the default branch for JVM, npm and the container image. Critical or high and reachable: fix or mitigate within 7 days; known-exploited: 72 hours (PROPOSED). Deferrals carry a reason and a review date. |
| SBOM | CycloneDX generated per release and stored with the artefact. |
| Build provenance | Builds run only on the hosted CI from a protected branch. Provenance attestation (SLSA Build Level 2 as the target) and image signing; deployment verifies the signature. |
| CI hygiene | Third-party actions pinned by commit SHA. Least-privilege workflow tokens. No secrets for fork PRs. Any scanner telemetry disabled. |
| Base image | Minimal JRE image, non-root, read-only root filesystem, pinned by digest, rebuilt at least monthly. |
| Renderer libraries and fonts | Version-pinned, licence-checked, bundled. No runtime downloads. |
| Telegram client library | Treated as a boundary component. Pin the version, record the Bot API level, and keep its logging at WARN (L3). |
| Runtime drift | The running image digest is exported as a metric and compared with the released digest. |

## 13. Incident response

### 13.1 Severity and clocks

| Sev | Definition | Examples | First response |
|---|---|---|---|
| S1 | Confirmed or likely disclosure, loss or unauthorised change of health data; or loss of control of the bot | Token theft, database or backup leak, wrong-recipient send, operator account takeover, KEK exposure | Immediately. Containment within 1 hour. |
| S2 | Reminder delivery SLO breach or data integrity doubt without disclosure | Scheduler stall, restore needed, Telegram outage beyond 30 minutes | Within 30 minutes. |
| S3 | Degraded non-critical function | Renderer down (text fallback works), export queue slow | Next working day. |

Regulatory clocks that start when the controller becomes aware of a personal-data breach:

| Regime | Deadline | Status |
|---|---|---|
| GDPR Article 33 | Supervisory authority within 72 hours unless the breach is unlikely to result in a risk | RECALLED |
| GDPR Article 34 | Affected individuals without undue delay when the risk is high. A health-data breach will usually qualify. | RECALLED |
| UK GDPR | Same structure, to the ICO | RECALLED |
| Telegram Bot Developer Terms 4.4 | Alert users of any data breach "in accordance with applicable laws"; make all efforts to remediate immediately | VERIFIED-P |
| FTC Health Breach Notification Rule (if US users) | Individuals without unreasonable delay and within 60 calendar days; FTC at the same time when 500 or more people are affected; smaller breaches reported annually | VERIFIED-S |
| Washington and other US state laws (if applicable) | State breach statutes add their own deadlines | Not checked |

Wrong-recipient delivery of a single reminder is a personal-data breach. It must be recorded, assessed and, if the risk is not negligible, notified.

### 13.2 Runbooks to write before launch

1. Bot token compromise: generate a new token in BotFather; deploy; call `setWebhook` with a new secret token; verify `getWebhookInfo`; review the period of exposure; assume all messages sent to the bot in that period were readable by the attacker and that the bot may have sent phishing; notify users; if HMAC `initData` validation was in use, revoke all sessions. Target: under 1 hour from detection.
2. Database or backup exposure: rotate database credentials; determine whether the KEK was also exposed. If not, Class A is protected and Class B plus identifiers are exposed. If yes, treat as full disclosure. Rotate KEK, rotate DEKs, notify.
3. Wrong-recipient send: stop the dispatcher; identify affected rows from outbox states; delete the messages if still inside the 48-hour window; notify both users; fix; add a regression test.
4. Operator account takeover (source hosting, cloud, Telegram owner account): revoke sessions and tokens, rotate all secrets in 11.2, verify the deployed image digest, review audit and control-plane logs.
5. KEK loss or corruption: restore from the offline copy. If impossible, Class A data is lost; tell users plainly.
6. Telegram outage or bot ban: reminders cannot be delivered. Status page update. No alternative channel exists at launch; this is a stated product limit.
7. Vulnerability report from outside: a `security.txt` on the Mini App origin and a contact in the bot's `/privacy` reply. Acknowledge within 3 working days.

### 13.3 Communication

If the bot token is compromised, the bot chat is not a trustworthy channel until rotation completes. The privacy notice therefore names a status and notice page on the Mini App origin as the out-of-band channel. Post-rotation, the bot sends the notification and links to that page.

Every S1 and S2 incident gets a written, blameless review with a timeline, the data classes involved, the number of accounts, the notification decision and its reasoning. Keep these records; GDPR Article 33(5) requires documenting all breaches (RECALLED).

## 14. Legal baseline

This section is legal information, not legal advice. Where a statute could not be opened this session the row is marked RECALLED or VERIFIED-S and must be checked by counsel.

### 14.1 Who is responsible

1. The person or company that decides why and how Dosegram processes user data is the controller. A solo developer who offers the bot to other people is a controller in their own name.
2. The "purely personal or household activity" exemption in GDPR Article 2(2)(c) covers the owner using the bot for their own medication. It does not cover offering the service to other people (RECALLED).
3. Telegram is not Dosegram's processor. It is an independent controller of the chat data it carries (interpretation; Telegram's own policy names Telegram Messenger Inc. as controller of user data and states that bot developers are not affiliated with it, [T9] 6.4, VERIFIED-P).
4. GDPR applies if the controller is established in the EU, or if it offers the service to people in the EU regardless of where it is established (Article 3, RECALLED). A bot reachable by anyone on Telegram, in EU languages, is likely to be "offering" unless access is restricted.

### 14.2 GDPR: special-category data and lawful basis

1. Article 9(1) prohibits processing "data concerning health" unless an Article 9(2) exception applies (VERIFIED-P, [L1]).
2. The exception that fits a consumer self-tracking product is Article 9(2)(a): "the data subject has given explicit consent to the processing of those personal data for one or more specified purposes" (VERIFIED-P).
3. Article 9(2)(h) (health or social care) requires processing by or under the responsibility of a professional bound by secrecy and does not fit (RECALLED).
4. An Article 6 basis is needed as well. Recommended: consent (Article 6(1)(a)) for the tracking domains, so one withdrawable act covers both articles; contract (Article 6(1)(b)) for account and session data needed to run the service; legitimate interests (Article 6(1)(f)) for security logging and abuse prevention, with a written balancing test. Rejected alternative: legitimate interests for everything, as in Telegram's Standard Bot Privacy Policy ([T10] 5.1). It cannot lift the Article 9 prohibition.
5. Article 9(4) lets member states add conditions for health data (VERIFIED-P). National rules must be checked for each launch country.
6. Consent must be freely given, specific, informed and unambiguous, demonstrable by the controller, and as easy to withdraw as to give (Article 7, RECALLED). Consent obtained by making an unrelated feature conditional on it is not freely given.
7. Other duties that follow: transparency (Articles 12 to 14), access, rectification, erasure, restriction, portability and objection (Articles 15 to 21), data protection by design and by default (Article 25), processor contracts (Article 28), security (Article 32) (all RECALLED).

### 14.3 Capturing explicit consent inside a chat

"Explicit" means an express statement of consent, not an inference from use. The EDPB consent guidelines (Guidelines 05/2020; RECALLED, the PDF could not be fetched this session) accept electronic means such as filling in a form, and describe two-stage verification as a way to make consent explicit.

PROPOSED flow:

1. `/start` shows who runs the service, what it does, and that nothing is stored yet. No tracking features are available before consent.
2. Adult attestation: "I am 18 or older" (button).
3. Region of residence: a short self-declared list. It selects the applicable notice text and is needed because Telegram does not tell the bot where the user lives.
4. Layered notice in chat (short, plain language) with a link to the full privacy notice. The short layer states: what is stored; that it is health data; that Telegram can read bot messages and images because bot chats are not end-to-end encrypted; who can access it; retention; how to export, delete and withdraw; that the product gives no medical advice.
5. Per-domain consent, one message per domain actually being enabled. The button label is the statement, for example: "I agree that Dosegram stores and processes my medication records." No pre-selected state. No "accept all" as the only path.
6. Second stage: a confirmation message repeats the statement and asks for "Confirm". The consent is recorded only on the second tap.
7. The bot sends a consent receipt message: domains, date, notice version, and how to withdraw.
8. Separate, later consents when the feature is first used: sleep inference from bot activity; caregiver sharing with a named recipient; report images delivered into the Telegram chat; any optional analytics; any AI processing by a third party.
9. Withdrawal: `/privacy` and the Mini App settings show each consent with a one-tap "Withdraw". Withdrawal stops processing for that domain and offers deletion of its data.

Consent ledger record: `consent_id`, `account_id`, `purpose_code`, `granted_at`, `withdrawn_at`, `notice_version`, `notice_text_sha256`, `locale`, `region`, `channel`, `evidence_ref` (the `update_id` of the confirming callback), `stage1_at`, `stage2_at`. The exact notice text for each version and locale is kept in the repository so the hash can be resolved.

Re-prompt when the notice changes materially or a new purpose is added. Do not treat silence as renewed consent.

### 14.4 DPIA, records, DPO, EU representative

| Topic | Position | Status |
|---|---|---|
| DPIA | Article 35(1) requires one where processing is likely to result in high risk. Article 35(3)(b) names large-scale processing of special categories as a case that always requires one. An invite-only beta is not large scale, but the combination of health data, systematic monitoring over time and a new delivery context meets the usual regulator criteria. Recommendation: complete a DPIA before public beta in any case. | VERIFIED-P for Article 35(1), (3), (7); RECALLED for the regulator criteria (WP248) |
| DPIA contents | Description and purposes; necessity and proportionality; risks to individuals; measures. Sections 1 to 13 of this brief supply most of the input. | VERIFIED-P (Article 35(7)) |
| Records of processing | Article 30. The small-organisation exemption does not apply when special categories are processed. Keep a record from day one. | RECALLED |
| DPO | Mandatory when core activities consist of large-scale processing of special categories (Article 37(1)(c)). Not triggered at beta scale. Name a privacy contact anyway. Reassess at each growth step. | RECALLED |
| EU representative | A controller not established in the EU that is caught by Article 3(2) must appoint one (Article 27), unless processing is occasional and does not include large-scale special-category processing. A continuously running health tracker is unlikely to be "occasional". | VERIFIED-S |
| Breach notification | Section 13.1. | RECALLED |
| Automated decisions | Article 22 is not engaged as long as the product only records and displays. Keep it that way. | RECALLED |

"Large scale" has no numeric threshold in the Regulation. Do not design around a specific user count without counsel.

### 14.5 Data residency, transfers and processors

1. Recommendation: host all Dosegram systems (database, object storage, backups, logs, KMS) in one EU or EEA region. Rationale: it removes transfer questions for EU users and matches Telegram's stated storage location for European accounts ([T9] 4.1). Rejected alternative: a US region for convenience; it adds a transfer mechanism requirement with no benefit.
2. Region is configuration, not code, so a later second region does not need a redesign.
3. Every vendor that touches Class A, B or C data (hosting, object storage, KMS, backup location, observability) is a processor and needs a data processing agreement and a place in a public sub-processor list.
4. Telegram is disclosed in the notice as the delivery platform and as an independent controller outside the EU. The user's use of Telegram is governed by Telegram's own terms. This framing is an interpretation to confirm with counsel.
5. Remote operator access from outside the EEA is treated by regulators as a transfer (RECALLED). If the owner operates from a country without an EU adequacy decision, this needs a documented position. See open question Q1.

### 14.6 United States

| Regime | Applies? | Key points | Status |
|---|---|---|---|
| HIPAA | Not to a consumer app where users enter their own data and no covered entity or business associate relationship exists. It would apply if Dosegram were provided on behalf of a provider, plan or their contractor. | Do not sign provider or insurer integrations without a HIPAA programme. | VERIFIED-S ([L9]) |
| FTC Health Breach Notification Rule, 16 CFR Part 318 | Yes for US consumers. The 2024 amendments (effective 2024-07-29) make clear that health apps outside HIPAA are covered. | "Breach" includes unauthorised disclosure, not only intrusion. Notice to individuals and the FTC within 60 calendar days; contemporaneous FTC notice at 500 or more people. | VERIFIED-S ([L7]) |
| FTC Act section 5 | Yes. | Privacy promises are enforceable. Sharing health data contrary to the notice has been enforced as deceptive or unfair. | RECALLED |
| Washington My Health My Data Act, chapter 19.373 RCW | Yes if the service is targeted to Washington consumers or the entity does business there. No revenue or user-count threshold exempts small entities; "small business" status only delayed the start date (2024-06-30 instead of 2024-03-31). | "Consumer health data" is personal information linked or reasonably linkable to a consumer that identifies past, present or future physical or mental health status. The enumerated list includes use of prescribed medication, bodily functions, vital signs, symptoms, and data derived or inferred from non-health data. Requires a separate consumer health data privacy policy, opt-in consent for collection beyond what is necessary to provide the requested service, separate consent for sharing, rights to confirm, withdraw and delete, and is enforceable by the Attorney General and by private action through the Consumer Protection Act. | VERIFIED-S for the definition, consent standard, small-business date and enforcement; RECALLED for the enumerated list and deletion mechanics. Primary text could not be opened. |
| Nevada SB 370 | Similar scope to Washington, no private right of action. | | RECALLED |
| Connecticut, Maryland, Vermont and other comprehensive state laws | Health data is "sensitive data" needing opt-in consent, usually above applicability thresholds that a small service will not meet. Connecticut's consumer-health-data provisions have no such threshold. | 20 or more states had comprehensive laws in effect by August 2026. | VERIFIED-S ([L13]) for the state count and the health-data treatment; RECALLED for the Connecticut threshold point |
| COPPA | Applies to services directed to children under 13 or with actual knowledge of such users. Amended rule in full effect since 2026-04-22. | An 18+ gate keeps the product outside it if honoured. | VERIFIED-S ([L12]) |

Recommendation: do not target US residents in the first release. The region step in onboarding (14.3) makes the position explicit. Before opening to the US: write the Washington-style consumer health data privacy policy, build the breach-notification procedure for the FTC rule, and take advice on Washington's private right of action.

### 14.7 United Kingdom

1. UK GDPR and the Data Protection Act 2018 mirror the EU position on health data: Article 9 condition plus Article 6 basis; explicit consent is the fitting condition (RECALLED).
2. The Data (Use and Access) Act 2025 received Royal Assent on 2025-06-19. Its main data-protection changes came into force on 2026-02-05 (VERIFIED-S, [L11]). None of the reported changes removes the special-category regime.
3. A UK-established controller pays the ICO data protection fee. Tier 1 (micro organisations) is GBP 52 a year since 2025-02-17 (VERIFIED-S).
4. Whether a controller outside the UK must still appoint a UK representative after the 2025 Act was not verified. Check before offering to UK residents.
5. The UK Age Appropriate Design Code applies to online services likely to be accessed by children (RECALLED). The 18+ position and the absence of child-directed design reduce exposure but do not remove the question.

### 14.8 Ukraine

1. The Law of Ukraine "On Personal Data Protection" No. 2297-VI of 2010-06-01 is in force. It is supervised by the Parliament Commissioner for Human Rights (Ombudsperson) and follows the older EU Directive model (VERIFIED-S, [L10]).
2. Its Article 7 prohibits processing health data except in listed cases, which include the data subject's unambiguous consent. Processing that poses a special risk, which includes health data, must be notified to the Ombudsperson within 30 working days of starting (RECALLED; the official text could not be fetched).
3. Draft Law No. 8153, a GDPR-aligned replacement, passed first reading on 2024-11-20 and was not enacted according to sources dated up to mid-2026 (VERIFIED-S). Expect GDPR-like duties and penalties when it passes.
4. If the controller is a Ukrainian resident or entity, the Ukrainian law applies to the processing regardless of where users are.

### 14.9 Medical-device boundary

The line is drawn by intended purpose and claims, not by technology.

| Regime | Rule of thumb | Status |
|---|---|---|
| EU MDR 2017/745 | Software is a medical device when the manufacturer intends it for a medical purpose such as diagnosis, prevention, monitoring, prediction, prognosis, treatment or alleviation of disease. Software for lifestyle and well-being is not. Storage, communication and simple search do not by themselves qualify. Under Rule 11, software that provides information used to take diagnostic or therapeutic decisions is class IIa or higher. | RECALLED. Guidance: MDCG 2019-11 rev.1, published 2025-06-17 (VERIFIED-P for title and date, [L6]). A Commission proposal of December 2025 would revise the MDR, including Rule 11 (VERIFIED-S). |
| US FDA | Software for "maintaining or encouraging a healthy lifestyle" unrelated to diagnosis, cure, mitigation, prevention or treatment of disease is outside the device definition (section 520(o) FD&C Act as amended by the 21st Century Cures Act). The General Wellness guidance was reissued as final on 2026-01-06 (docket FDA-2014-N-1039). | VERIFIED-P ([L4]) |
| US FDA, medication reminders | FDA's software-function examples list software that tracks medications and provides user-configured reminders among functions for which it exercises enforcement discretion (guidance of 2022-09-28). | VERIFIED-S ([L5]) |
| UK MHRA | Intended-purpose test similar to the EU's. | RECALLED |

Product guardrails that keep Dosegram outside device scope and inside the AGENTS.md non-clinical rule:

| Allowed | Not allowed |
|---|---|
| Record what the user says they took, when, and how much, in the user's own words and units | Calculate, suggest or adjust a dose |
| Remind at times the user configured | Recommend a schedule or tell the user what to do after a missed dose |
| Show counts, timelines and adherence percentages as records | Label adherence as clinically good or bad, or predict outcomes |
| Refill reminders based on a count the user entered | Drug interaction, duplication, contraindication or allergy checks |
| Self-rated mood on a simple scale with user-chosen tags | Scored screening instruments with interpretation, or any statement about depression, anxiety or risk |
| Sleep, water and food logs with user-set goals | Diagnose or flag a disorder; compare with clinical norms as advice |
| Diary of user-entered vitals (if adopted) | Alerts that interpret a reading ("your blood pressure is high"), trend warnings, or treatment suggestions |
| Export a plain record the user may show a clinician | Present the export as a clinical report or send it to a clinician on the user's behalf |
| A static "this is not medical advice; contact a professional or emergency services" notice | Triage or crisis assessment |

A medication name catalogue or autocomplete from a drug database is a boundary case. A plain name list is storage and search. Attaching dosing, indication or warning information to it moves toward a device and toward clinical advice. Recommendation: free-text names plus the user's own history only, at least until counsel reviews a catalogue.

Marketing and bot copy count as claims. "Improves adherence", "helps manage your condition" and similar phrases are medical claims. Use "reminds" and "records".

### 14.10 Minors

1. Decision: adults only, 18+, by self-attestation, with no date of birth stored. Rejected alternative: 16+ with country-specific rules. GDPR Article 8 sets the age of digital consent between 13 and 16 depending on the member state (RECALLED), and health data for minors adds parental-authority questions that differ by country.
2. Telegram's Terms of Service and Privacy Policy, as fetched, state no minimum age (VERIFIED-P for absence in the fetched text, [T9], [T13]). Telegram does not pass an age signal to bots.
3. No age verification beyond attestation is proposed. If the operator learns a user is under 18, the account is closed and its data deleted.
4. Design must not be directed at children: no child-oriented characters or rewards in the mood and streak features.
5. Dependent profiles for children are a separate decision (Q7).

### 14.11 Telegram developer terms: health data and payments

Health data:

1. The Bot Developer Terms and the Mini Apps Terms, as fetched, contain no clause specific to health or medical data and no ban on health trackers (VERIFIED-P for absence).
2. The general duties apply and are binding (VERIFIED-P, [T7]): an accessible privacy policy (section 4); only essential data (4.1, 4.3); deletion on user or Telegram request without undue delay (4.2); no large datasets, no ML or AI training on user data (4.3); encryption at rest with separate key storage (4.4(a)); breach alerts to users under applicable law (4.4(b)); credentials kept private (4.5); no spam, no impersonation, no requests for passwords or one-time codes (5.2); compliance with data protection law such as the GDPR (9.1).
3. Telegram's Standard Bot Privacy Policy applies by default when a bot has none, relies on legitimate interests, sets no retention periods and says nothing on sensitive data ([T10], VERIFIED-P). It is not adequate here. Publish an own policy, link it in BotFather, and answer `/privacy` in chat. If the standard policy is ever relied on, it commits the developer to answer rights requests within 30 days ([T10] 7.3(c)).
4. Telegram may moderate, suspend or remove a bot at its discretion ([T7] 5.1, 5.2, 10). Platform dependency is a business-continuity risk: export must work without the bot being reachable, which the Mini App origin provides only while the Mini App can still authenticate. See Q6.

Payments:

1. Digital goods and services must be sold in Telegram Stars with currency `XTR`; other currencies and providers are not allowed for digital goods ([T12], [T7] 6.2; VERIFIED-P). A premium tier of Dosegram is a digital service.
2. The bot must answer `/paysupport`, must offer `/terms` or an equally easy way to read the terms before going live, and the developer is solely responsible for disputes and refunds, which use `refundStarPayment` (VERIFIED-P).
3. Developer rewards are paid at 0.013 USD per Star; Stars expire 3 years after receipt; the developer bears all taxes ([T7] 6.2.4, 6.2.7, 6.4; VERIFIED-P).
4. Physical goods use third-party providers and are out of scope ([T7] 6.1).
5. Mini Apps with cryptocurrency functions must use TON only ([T7] 7). Not relevant; do not add crypto features.
6. Recommendation: no payments at launch. If a paid tier is added later: Stars only; never put export, deletion, consent withdrawal, security features or the reliability of reminders behind payment; store only the Telegram payment charge identifier needed for refunds; treat "user X pays for a medication tracker" as Class C data.

### 14.12 Solo developer versus company

| Topic | Individual | Company |
|---|---|---|
| Controller | The person, with personal and unlimited liability | The entity |
| GDPR duties | Identical. There is no small-controller exemption for special-category data. | Identical |
| Processor agreements | Many providers contract only with businesses or apply business terms regardless | Straightforward |
| Notice | Must name the individual and a contact address | Names the entity |
| DPO | Not mandatory at small scale | Same test |
| Insurance | Cyber and liability cover is hard to buy as an individual | Available |
| Telegram payouts and taxes | Personal income | Company income |
| Continuity | A single person is a single point of failure for security response and for rights requests | Can be staffed |

Recommendation: run the invite-only beta as an individual with a small, known group (PROPOSED cap: 50 accounts), a written privacy notice, the consent flow and all launch-blocking controls. Form a legal entity and take legal advice before public beta. Rationale: the legal duties do not shrink for an individual, but the exposure does shrink with a small invited group, and incorporation is cheap relative to the liability of a public health-data service.

## 15. What "enterprise grade" means here

Measurable properties. All values are PROPOSED targets for the owner to accept or change. "Beta" is the invite-only phase; "GA" is public availability.

| Property | Measure | Beta | GA |
|---|---|---|---|
| Availability, bot and API | Successful responses over total, 30-day window, measured at the edge | 99.5% | 99.9% (about 43 minutes a month) |
| Reminder delivery, timeliness | Share of due reminders accepted by the Bot API within 60 seconds of the scheduled instant, 30-day window, excluding users who blocked the bot and Telegram-side outages confirmed by status | 99.5% | 99.9% |
| Reminder delivery, completeness | Share of due occurrences that reach a terminal, explained state (sent, deferred by quiet hours, undeliverable with reason) | 100% | 100% |
| Late reminders | Share accepted within 5 minutes | 99.9% | 99.99% |
| Duplicates | At most one duplicate per process crash, flagged (existing ADR-009) | Same | Same |
| Wrong-recipient sends | Count | 0, any occurrence is an S1 incident | 0 |
| Durability, RPO | Maximum data loss after a failure | 15 minutes | 5 minutes |
| Recovery, RTO | Time to restore service | 4 hours | 1 hour |
| Inbound loss ceiling | Outage length after which user taps are lost by Telegram | 24 hours (F11), fixed by the platform | Same |
| Backups | Restore test frequency and pass rate | Weekly automated, 100% pass | Same, plus quarterly full exercise |
| Detection | Time from SLO breach or security signal to alert | 5 minutes | 5 minutes |
| Observability | Request, error and duration metrics for every endpoint and job; traces across webhook, core, outbox and renderer; SLO burn-rate alerts; dashboards with no personal data | Required | Required |
| Auditability | Share of caregiver reads, operator reads, exports, deletions, consent changes, grant changes and admin commands present in the audit log | 100% | 100% |
| Audit integrity | Chain verification frequency and failures | Daily, 0 failures | Same |
| Tenant isolation | Cross-tenant test suite pass rate at release | 100%, release gate | Same |
| Erasure | Time from confirmed deletion to unrecoverable Class A data; to removal from live stores; to expiry from backups | Immediate; 24 hours; 35 days | Same |
| Access and portability | Time from request to export ready | 10 minutes | 10 minutes |
| Rights requests handled manually | Response time | 30 days at most | Same |
| Vulnerability response | Time to fix reachable critical or high findings | 7 days; 72 hours if exploited | Same |
| Secret hygiene | Maximum age of rotating secrets; time to rotate the bot token after suspected compromise | 90 days; 1 hour | Same |
| Change safety | Share of production changes that pass CI, are reviewed, and are reversible (expand and contract migrations) | 100% | 100% |
| Log hygiene | Canary strings found in logs, metrics, traces or audit | 0, CI gate | 0 |
| Privacy governance | Current privacy notice, record of processing, DPIA, sub-processor list, incident runbooks | Notice, record, runbooks | All |

What it does not mean at this stage: SOC 2 or ISO 27001 certification, multi-region active-active, a 24-hour staffed operations centre, or HIPAA compliance. Those are business decisions that follow demand. The properties above are chosen so that a later certification maps onto controls that already exist.

Honest limit: 99.9% availability and a 1-hour RTO are not credible with a single operator and a single host. GA targets presume managed PostgreSQL with automated failover or an equivalent, and paging that reaches a second person.

## 16. Control checklist

### 16.1 Launch-blocking (required before the first external user)

| ID | Control | Section |
|---|---|---|
| LB-01 | Webhook rejects requests without the correct secret token; path has no token; `update_id` deduplication | 3.1 |
| LB-02 | Bot token held only by the bot gateway; outbound URLs never logged; `getWebhookInfo` drift alert | 3.1, 11 |
| LB-03 | `initData` validated server-side (Ed25519), 5-minute `auth_date` window, opaque server session | 4 |
| LB-04 | Every API route and repository method passes the cross-tenant test suite | 12.1 |
| LB-05 | Row-level security forced on all account-scoped tables; application role without ownership or bypass | 12.1 |
| LB-06 | Class A fields and objects encrypted with per-user keys; KEK stored outside the database and its backups | 8 |
| LB-07 | KEK offline recovery copy exists and a restore from it has been tested | 11.2 |
| LB-08 | Wrong-recipient guard in the dispatcher with an interleaved-account property test | 3.1, 3.7 |
| LB-09 | Log, metric and trace allowlist with the canary log-hygiene test as a CI gate | 11.1 |
| LB-10 | Explicit two-step consent per domain with a stored, versioned consent ledger; no tracking before consent | 14.3 |
| LB-11 | Adult attestation and region step in onboarding | 14.3, 14.10 |
| LB-12 | Published privacy notice that states the Telegram transport limit; linked in BotFather; `/privacy` command | 14.11 |
| LB-13 | Self-service export and hard delete working end to end, including crypto-shredding and object removal | 9 |
| LB-14 | Audit log append-only with hash chain and the event catalogue of 10.3; no health content | 10 |
| LB-15 | Encrypted backups, off-host, with one successful automated restore | 12.2 |
| LB-16 | Rate limits on webhook, session creation, API, renders and exports | 12.3 |
| LB-17 | Mini App security headers and CSP; no third-party scripts except the Telegram script; same-origin API | 12.4 |
| LB-18 | Renderer has no network egress and takes typed snapshots only | 3.4 |
| LB-19 | Product copy and features reviewed against the guardrail table; non-advice notice present | 14.9 |
| LB-20 | Bot refuses group chats; private chats only | 3.1 |
| LB-21 | Owner Telegram account, source hosting and cloud accounts protected with strong second factors | 3.8 |
| LB-22 | Incident runbooks for token compromise, data exposure and wrong-recipient send; out-of-band notice page | 13 |
| LB-23 | Record of processing activities written; processor agreements signed for every vendor that holds user data | 14.4, 14.5 |
| LB-24 | Dependency lockfiles, vulnerability scan in CI, secret scanning | 12.5 |
| LB-25 | Old Discord credentials revoked; no Discord secrets remain in any environment | 11.2 |

### 16.2 Before public beta

| ID | Control | Section |
|---|---|---|
| PB-01 | DPIA completed and reviewed | 14.4 |
| PB-02 | Legal review of the notice, consent flow, launch geography, controller identity and representative duties | 14 |
| PB-03 | Legal entity formed, or a recorded decision not to | 14.12 |
| PB-04 | Point-in-time recovery; weekly automated restore test; first full disaster-recovery exercise with RPO and RTO measured | 12.2 |
| PB-05 | Step-up confirmation in chat for export, delete, grants and consent changes | 4.5 |
| PB-06 | User-visible access history | 10.4 |
| PB-07 | Break-glass procedure implemented with user notification | 11.3 |
| PB-08 | SBOM, signed images, pinned CI actions, provenance attestation | 12.5 |
| PB-09 | SLO dashboards and burn-rate alerts for availability and reminder timeliness; paging to a human | 15 |
| PB-10 | Independent security review or penetration test of webhook, Mini App and API, with findings closed | 3 |
| PB-11 | Retention jobs for every row of the schedule in 9.1, each with a test | 9.1 |
| PB-12 | Restore-time purge list and post-restore reconciliation tested | 12.2 |
| PB-13 | Discreet mode and `protect_content` on images | 7.3 |
| PB-14 | Key rotation exercised once for KEK, DEK, webhook secret and callback keys | 8, 11.2 |
| PB-15 | Vulnerability disclosure contact and `security.txt` | 13.2 |
| PB-16 | Sub-processor list published; region documented | 14.5 |
| PB-17 | Decision and controls for each open question in section 17 | 17 |
| PB-18 | Audit chain anchoring to write-once storage and daily verification | 10.4 |

### 16.3 Later

| ID | Control | Section |
|---|---|---|
| LT-01 | Caregiver grants with scoped access, expiry and audit | 5.2 |
| LT-02 | Optional app lock using device biometrics | 4.6 |
| LT-03 | US readiness: consumer health data privacy policy, FTC breach procedure, state-law review | 14.6 |
| LT-04 | UK readiness: ICO registration position, representative question | 14.7 |
| LT-05 | Payments in Stars with `/paysupport`, `/terms` and refund handling | 14.11 |
| LT-06 | High-availability database and a second on-call person, to make GA targets credible | 15 |
| LT-07 | User-configurable auto-delete windows per domain | 9.1 |
| LT-08 | External certification (SOC 2 Type II or ISO 27001) if business customers require it | 15 |
| LT-09 | DPO appointment review at each order-of-magnitude growth step | 14.4 |
| LT-10 | Food photo ingestion with metadata stripping, quotas and content rules | 17 Q12 |
| LT-11 | Any AI feature: processor agreement, no-training guarantee, separate explicit consent, output treated as untrusted | 17 Q9 |
| LT-12 | Bug bounty or recurring penetration test | 13.2 |

## 17. Open questions

Each has a recommendation. The owner decides.

| # | Question | Recommendation |
|---|---|---|
| Q1 | Who is the controller, and where are they established? This fixes the governing law, the supervisory authority, representative duties and the transfer analysis for operator access. | State it explicitly before any external user. If the owner is outside the EU and wants EU users, budget for an EU representative and legal advice. |
| Q2 | Launch geography. | Invite-only; EU or EEA and Ukraine residents; exclude US residents and defer the UK until 14.6 and 14.7 are closed. |
| Q3 | Default for discreet notifications. | Forced choice at onboarding with no preselection. If a default is required, choose discreet. |
| Q4 | Clients that send no Ed25519 `signature` (older than Bot API 8.0). | Reject with an update prompt. If measurement shows meaningful usage, add HMAC verification inside the bot gateway only, as an internal call, so the API still never holds the token. |
| Q5 | KEK location. | Cloud KMS or Vault Transit when hosted on a provider that offers it. A separately stored key file is acceptable for a single self-hosted node, with dual-custody offline copies. Depends on the hosting brief. |
| Q6 | Account recovery when a user loses their Telegram account, and data access if Telegram suspends the bot. | No second credential at launch. Promote regular export. Revisit a recovery code only if users ask; it adds an attack surface. |
| Q7 | Dependent profiles (tracking for a child or an elder under one account). | Not at launch. It makes the account holder a proxy for a third person's health data and needs its own notice, consent position and counsel review. |
| Q8 | In which milestone does caregiver access land? | After GA of the single-user product. Model the grant tables now so the authorisation function has one shape. |
| Q9 | Any AI or LLM processing (food recognition, summaries, natural-language entry). | None at launch. Later only with a processor that contractually does not train on the data, a separate explicit consent, and a per-request minimised payload. Telegram's terms forbid using bot user data to build AI products or datasets ([T7] 4.3); confirm how that clause applies to inference-only use. |
| Q10 | Paid tier. | None at launch. Stars only if added. |
| Q11 | Vitals (blood pressure, glucose, weight). | Diary only, user-entered, no interpretation or alerts. Defer if the product team wants thresholds; that needs a device-scope review. |
| Q12 | Food logging by photo. | Text and quick-pick first. Photos later with metadata stripping, quotas, and a rule that photos are never sent to third parties. |
| Q13 | Retention defaults in 9.1 (24-month inactivity, 13-month audit, consent ledger plus 3 years). | Accept as drafted, subject to counsel on the consent-ledger period. |
| Q14 | Minimum age 18 or 16. | 18. |
| Q15 | Dedicated Telegram account for bot ownership. | Yes, with two-step verification and a recovery email. |
| Q16 | Sleep inference from bot activity. | Keep as an opt-in estimate that the user confirms. Do not present it as measured sleep. |
| Q17 | Grace period on account deletion. | No delay by default; optional 7-day cancellable delay chosen by the user at the time of the request. |
| Q18 | Export delivered as a file in the Telegram chat. | Off by default. Mini App download is the primary path. |

## 18. Not checked, and residual risk

Not checked this session:

1. Primary text of chapter 19.373 RCW, 16 CFR Part 318, Ukrainian Law 2297-VI, EDPB Guidelines 05/2020, MDCG 2019-11 rev.1 body text, and the FDA guidance PDFs. The servers refused or redirected the fetch. Statements about them are VERIFIED-S or RECALLED.
2. Bot API sections beyond the first 100,000 characters of the reference page (`getFile`, `protect_content`, `deleteMessage` wording). F8 and F30 rest on secondary sources or recall. H10 rests on the existing design document.
3. Telegram pages were served in Ukrainian localisation; quotations from the Privacy Policy and Terms are translations. An English fetch should be compared before quoting them in a user-facing notice.
4. Actual WebView behaviour on each Telegram client: cookie handling, `frame-ancestors` hosts, storage persistence, availability of `signature`, `BiometricManager` and `SecureStorage`. All need a device test matrix.
5. National health-data rules under GDPR Article 9(4) for any specific member state.
6. UK representative requirement after the 2025 Act; UK and EU adequacy positions relevant to operator location.
7. US state breach-notification statutes and the current status of pending state health-privacy bills.
8. App-store style content rules that Telegram may apply to Mini Apps listed in its catalogue; none were found in the fetched terms.
9. No code, configuration or infrastructure was inspected for conformance. This brief sets a target; it verifies nothing about an implementation.

Residual risks that controls do not remove:

1. Telegram can read everything sent through the bot, and delivered messages cannot be recalled. Only minimising what is sent reduces this.
2. Telegram account takeover gives the attacker the user's Dosegram data. Step-up through the same account does not help; the optional app lock helps partly.
3. Telegram can suspend the bot. Users then lose reminders and, without a second login path, access to export.
4. A single operator is a single point of failure for incident response and for legal deadlines.
5. Loss of the KEK destroys all Class A data.
6. The legal position of an individual running a public health-data service across borders is exposed. The mitigation is scope (invite-only, limited regions) and counsel, not engineering.

## 19. Sources

All checked on 2026-11-12 unless marked.

Telegram platform (primary):

| Ref | Source | URL |
|---|---|---|
| T1 | Telegram Bot API reference (Bot API 10.4, 2026-11-09): making requests, `setWebhook`, update retention | https://core.telegram.org/bots/api |
| T2 | Telegram Mini Apps: validation, third-party validation, `WebAppInitData`, storage, debug mode, test environment | https://core.telegram.org/bots/webapps |
| T3 | Marvin's Marvellous Guide to All Things Webhook: IP ranges, ports, TLS | https://core.telegram.org/bots/webhooks |
| T4 | Bots FAQ: broadcast limits, file limits, privacy mode | https://core.telegram.org/bots/faq |
| T5 | Telegram Bot Features: deep links, token regeneration, local Bot API server, payments summary | https://core.telegram.org/bots/features |
| T6 | Bot API changelog: 8.0 (2024-11-17), 9.0 (2025-04-11), 9.6 (2026-04-03), 10.0 to 10.4 | https://core.telegram.org/bots/api-changelog |
| T7 | Telegram Bot Platform Developer Terms of Service | https://telegram.org/tos/bot-developers |
| T8 | Telegram Mini Apps Terms of Service | https://telegram.org/tos/mini-apps |
| T9 | Telegram Privacy Policy (latest dated entry 2024-09-29) | https://telegram.org/privacy |
| T10 | Standard Bot Privacy Policy | https://telegram.org/privacy-tpa |
| T11 | Telegram FAQ: secret chats, cloud chats, encryption | https://telegram.org/faq |
| T12 | Bot Payments API for Digital Goods and Services (Telegram Stars) | https://core.telegram.org/bots/payments-stars |
| T13 | Telegram Terms of Service | https://telegram.org/tos |

Telegram platform (secondary):

| Ref | Source | URL |
|---|---|---|
| T14 | Write-up of CVE-2024-33905 (Telegram Web App XSS) | https://medium.com/@pedbap/telegram-web-app-xss-session-hijacking-1-click-95acccdc8d90 |
| T15 | Community answers on bots and last-seen status; MTProto `userStatus` constructors | https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/38131042/getting-the-user-s-last-seen-from-a-telegram-bot ; https://core.telegram.org/constructor/userStatusOffline |
| T16 | Web3Auth community thread on Telegram Web iframes and `X-Frame-Options` | https://web3auth.io/community/t/refused-to-display-https-auth-web3auth-io-in-a-frame-because-it-set-x-frame-options-to-deny/7886 |
| T17 | Library documentation for `getFile` (download URL form, 1-hour validity) | https://docs.python-telegram-bot.org/en/v13.3/telegram.file.html |

Legal (primary where reachable):

| Ref | Source | URL | Status |
|---|---|---|---|
| L1 | GDPR Article 9 | https://gdpr-info.eu/art-9-gdpr/ | VERIFIED-P |
| L2 | GDPR Article 35 | https://gdpr-info.eu/art-35-gdpr/ | VERIFIED-P |
| L3 | GDPR Article 27 | https://gdpr-info.eu/art-27-gdpr/ | VERIFIED-S |
| L4 | FDA, General Wellness: Policy for Low Risk Devices (final, January 2026, docket FDA-2014-N-1039) | https://www.fda.gov/regulatory-information/search-fda-guidance-documents/general-wellness-policy-low-risk-devices | VERIFIED-P (page); commentary: https://www.cov.com/en/news-and-insights/insights/2026/01/fda-issues-revised-guidance-on-general-wellness-products |
| L5 | FDA, Policy for Device Software Functions and Mobile Medical Applications (2022-09-28) | https://www.fda.gov/media/80958/download | VERIFIED-S |
| L6 | European Commission, MDCG 2019-11 rev.1 (2025-06-17) | https://health.ec.europa.eu/latest-updates/update-mdcg-2019-11-rev1-qualification-and-classification-software-regulation-eu-2017745-and-2025-06-17_en | VERIFIED-P (title, date) |
| L7 | FTC Health Breach Notification Rule, final rule, 89 FR, document 2024-10855 | https://www.federalregister.gov/documents/2024/05/30/2024-10855/health-breach-notification-rule | VERIFIED-S (fetch redirected); commentary: https://www.venable.com/insights/publications/2024/07/final-changes-to-health-breach-notification |
| L8 | Washington My Health My Data Act, chapter 19.373 RCW | https://app.leg.wa.gov/RCW/default.aspx?cite=19.373 | VERIFIED-S (fetch refused); commentary: https://wabarnews.org/2024/04/09/the-washington-my-health-my-data-act/ |
| L9 | HHS OCR, Health Apps and HIPAA | https://www.hhs.gov/hipaa/for-professionals/special-topics/health-apps/ | VERIFIED-S |
| L10 | Ukraine: Law No. 2297-VI and draft law No. 8153 | https://zakon.rada.gov.ua/laws/show/2297-17 ; https://www.linklaters.com/en/insights/data-protected/data-protected---ukraine | VERIFIED-S (fetch refused) |
| L11 | UK Data (Use and Access) Act 2025 commencement; ICO fee change | https://privacymatters.dlapiper.com/2026/02/uk-commencement-of-the-data-protection-provisions-in-the-data-use-and-access-act/ ; https://www.gov.uk/government/consultations/data-protection-fee-regime-proposed-changes | VERIFIED-S |
| L12 | COPPA Rule amendments, compliance date 2026-04-22 | https://www.hunton.com/privacy-and-cybersecurity-law-blog/ftc-publishes-final-coppa-rule-amendments | VERIFIED-S |
| L13 | US state privacy law survey, mid-2026 | https://www.morganlewis.com/pubs/2026/07/new-us-state-consumer-privacy-laws-what-businesses-should-know | VERIFIED-S |
| L14 | EDPB Guidelines 05/2020 on consent | https://www.edpb.europa.eu/our-work-tools/our-documents/guidelines/guidelines-052020-consent-under-regulation-2016679_en | RECALLED (PDF fetch failed) |

Repository sources: `docs/DESIGN.md` (sections 2, 4.4, 6, 8, 12, 15), `docs/adr/README.md` (ADR-002, ADR-006, ADR-007, ADR-009), `AGENTS.md` (boundaries).

Security method references: OWASP API Security Top 10, OWASP ASVS, NIST CSF 2.0, CIS Controls v8, SLSA, CycloneDX. Named for orientation; not re-fetched.

