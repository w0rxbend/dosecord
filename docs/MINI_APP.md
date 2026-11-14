# Dosegram Mini App specification

Status: In force since 2026-11-13: the owner approved the plan and roadmap slice G0.2 promoted this document from `docs/dosegram/` to `docs/` ([ADR-014](adr/ADR-014.md)). Where the text below says "proposal" or "proposed", read it as the adopted specification; an open question keeps the status its own section gives it. Dosecord documents named as inputs (`docs/DESIGN.md`, the old `docs/ROADMAP.md`, `docs/BOT_UX_PLAN.md`) are now under `docs/archive/`.
Date: 2026-11-12.
Scope: the Telegram Mini App of Dosegram: its role, entry points, navigation, every screen, native controls, design tokens, interface states, accessibility, performance, frontend technology, build, tests, and the list of backend resources it needs.
Out of scope: product rules and acceptance criteria per module (PRODUCT_SPEC.md), chat flows and chat copy (BOT_UX.md), backend design, authentication internals and the detailed API (ARCHITECTURE.md), card designs, chart grammar and metric formulas (VISUALS_AND_ANALYTICS.md), delivery order (ROADMAP.md in this directory).

This document writes no product code and renames nothing. The repository is still named `dosecord` at the time of writing.

Next action for the reviewer: decide the nine open questions in section 18. Questions MA-Q-1 (frontend stack), MA-Q-2 (minimum Telegram client) and MA-Q-3 (no health data in client storage) change the most work downstream.

## 0. How to read this document

### 0.1 Evidence base

| Ref | File | Used here for |
|---|---|---|
| R01 | `research/01-repo-inventory.md` | Existing engine, schema, limits of the current contracts |
| R02 | `research/02-telegram-platform.md` | Exact `Telegram.WebApp` names, launch modes, limits |
| R03 | `research/03-health-app-teardown.md` | Screen ideas, refused features, regulatory lines |
| R04 | `research/04-image-rendering.md` | Server images, the mood blob, the shared SVG profile |
| R05 | `research/05-sleep-and-presence.md` | Sleep tiers, the quiet-window flow, Mini App foreground signal |
| R06 | `research/06-privacy-security-compliance.md` | Session model, client hardening, consent, step-up, quotas |

Sibling documents are referred to by file name. Module ids are those of the capability map in PRODUCT_SPEC.md: `identity-and-consent`, `medication-registry`, `schedule-and-reminders`, `intake-log`, `daily-overview`, `hydration`, `nutrition`, `mood`, `sleep`, `insights-and-correlations`, `visual-reports`, `import-export`, `sharing-and-caregivers`, `localisation`.

### 0.2 Labels

| Label | Meaning |
|---|---|
| VERIFIED | A statement about an external platform or tool read in a primary source on 2026-11-12. Section 2.1 gives the source URL for each. |
| ASSUMPTION | A statement about an external platform, a browser engine or a tool that was not confirmed in a primary source or by a test. Section 2.2 lists each one with the spike that confirms it. |
| PROPOSED | A value chosen by this document (a limit, a default, a budget). The owner may change it without changing the structure of the requirement. |

No requirement in this document rests on an unlabelled claim about Telegram.

### 0.3 Identifiers

| Kind | Form | Example |
|---|---|---|
| Mini App requirement | `MA-<AREA>-n` | `MA-TODAY-3` |
| Platform fact | `TGF-nn` (renamed from `TG-nn`, which collided with the adapter requirements `TG-nn` of ARCHITECTURE) | `TGF-07` |
| Assumption | `MA-A-nn` | `MA-A-04` |
| Spike | `MA-SPK-n` | `MA-SPK-2` |
| Open question | `MA-Q-n` | `MA-Q-1` |
| Screen | `S-nn` | `S-05` |

R06 uses `MA-1` to `MA-10` for threats against the Mini App frontend. Those are cited here as "R06 threat MA-3". Identifiers of the form `MA-<AREA>-n` belong to this document.

Every requirement ends with one or more verification tags. The tags are those of PRODUCT_SPEC.md section 0.5: [U] unit test, [M] Mini App component or end-to-end test against the API, [G] golden file, [L] live check on real Telegram clients, [O] continuous-integration gate.

### 0.4 Decisions in brief

| # | Decision | Rejected alternative | Section |
|---|---|---|---|
| 1 | The Mini App is the configuration and exploration surface. The chat stays the capture surface. | A Mini App that replaces the chat for daily logging | 1 |
| 2 | Entry points: Main Mini App (with `startapp` routes), menu button, inline `web_app` buttons. | Reply-keyboard buttons, inline mode, attachment menu, a second named direct-link app | 3.1 |
| 3 | Minimum Telegram client level is Bot API 8.0, because the session exchange uses the Ed25519 `signature`. | Supporting older clients through bot-token HMAC inside the API | 3.3 |
| 4 | Four bottom tabs (Today, Meds, Log, Insights). Settings open from the native `SettingsButton` and a header control. The tab bar and the native `MainButton` never appear together. | One tab per tracking domain; a drawer menu | 4 |
| 5 | Colours come only from Telegram `themeParams` plus one small set of data colours with a light and a dark variant. A runtime contrast guard corrects weak pairs. | An own theme with a manual light and dark switch | 5 |
| 6 | No health data in any client-side store. The session token and all records live in memory only. Form drafts are saved on the server. | Drafts and an offline queue in `DeviceStorage` | 6.6, 8 |
| 7 | Optimistic updates with idempotency keys for log actions; version checks with field-level merge for registry edits; no automatic merge for schedules. | Last write wins | 8.5 |
| 8 | Svelte 5 with TypeScript, built with Vite as a static single-page application, API types generated from the backend's OpenAPI document. | React; SolidJS (runner-up); Scala.js with Laminar | 12 |
| 9 | Interactive charts are scenes laid out by the server and painted into SVG DOM nodes by one small in-repository scene painter (VISUALS VA-MA-02, ARCHITECTURE section 9.5.1; ROADMAP conflict X-30). The client has no chart layout of its own. Report cards are server-rendered images shown as they will appear in chat. | A chart library; client-side SVG chart components fed by data series (first draft of this document); an inline SVG string from the server | 5.6, 7.11 |
| 10 | The Mini App is served from the same origin as its API and ships in the same release artefact. | A separate static host or a content delivery network on another origin | 13 |
| 11 | Every Telegram call goes through one `TelegramPort` interface with a fake implementation, so the whole application runs and is tested in an ordinary browser. | Tests that need a Telegram client | 14 |

## 1. Role of the Mini App versus the bot

### 1.1 Division of work

PRODUCT_SPEC.md section 3.1 allocates each kind of interaction to a surface. The Mini App follows that allocation. The table restates it from the Mini App's side.

| Job | Bot chat | Mini App |
|---|---|---|
| Answer a reminder (taken, skip, snooze, undo) | Primary. One tap on the reminder message. | Possible on Today. Primary for correcting the time or the state afterwards. |
| Quick log of water, mood, a sleep check-in, a meal type | Primary | Also possible; primary for editing and backdating |
| See today | One message edited in place | Today screen with the same content and more detail |
| Add or edit a medication | Fallback wizard | Primary: one form with validation and review |
| Build or change a schedule | Fallback wizard for the simplest kinds | Primary: schedule builder with a seven-day preview |
| History, calendar, charts | Short text summaries and image cards | Primary |
| Settings, quiet hours, time zone, notification budget | Commands with confirmation | Primary |
| Consent, export, deletion | Commands; consent is always confirmed in chat | Primary entry; sensitive steps are confirmed in chat (step-up) |
| Weekly report | Delivered as an image | Detail, drill-down, preview of cards |
| Import a file | Not used (the bot download limit is 20 MB, TGF-36) | Primary, in a later wave |
| Caregiver sharing | Invitation acceptance and alerts | Managing grants and viewing what is shared |

### 1.2 Rules that follow

MA-ROLE-1. Every safety-relevant action that the Mini App offers (add a medication, pause a schedule, export, delete the account) must also be reachable in chat, so that the product never depends on a WebView working. The Mini App must not be the only place where a medication reminder can be stopped. [L]

MA-ROLE-2. The Mini App never creates an account and never records consent by itself. A user without an account or without consent for a domain is sent to the chat to complete that step (section 3.5). This keeps one consent evidence path, the confirming callback in chat (R06 section 14.3). [M]

MA-ROLE-3. A change made in the Mini App has the same effect as the same change made in chat. In particular, marking a dose on the Today screen must finalise the reminder message in the chat in the same way a button press there does. The Mini App does not send chat messages itself; the backend does. [M][L]

MA-ROLE-4. The Mini App sends no message into the chat on the user's behalf through `sendData` or `answerWebAppQuery`. It talks only to the Dosegram API. Rationale: `sendData` is unauthenticated and limited to 4096 bytes (TGF-01), and one write path through the API keeps authorisation in one place. Rejected alternative: returning form results to the bot as a `web_app_data` service message. [O]

MA-ROLE-5. The Mini App displays records and descriptive statistics only. It contains no dose calculator, no advice after a missed dose, no interpretation of a value, no condition names in product-authored text, no composite score, and no judgement colours for health values (PRODUCT_SPEC.md section 5, non-goals N2 to N9). A lint over the Mini App message catalogues enforces the forbidden-phrase list that the copy catalogue already has (R01 section 11). [O]

### 1.3 Where the owner's wishes meet verified constraints

| Owner's wish | Constraint | What the Mini App does |
|---|---|---|
| Sleep tracking from the user's Telegram "last seen" | Not available to a bot or a Mini App. `WebAppInitData` and `WebAppUser` carry no presence field (TGF-06). | The Sleep screen (S-10) works from check-ins and from edits. As an opt-in it reports its own foreground periods to the backend, which helps the quiet-window estimate of R05 section 7. The opt-in text says in plain words that Dosegram cannot read Telegram last seen. |
| "Best in class" interface that does everything | A Mini App is a web page in a WebView with a fixed set of bridges. It has no background execution, no sensors when closed, and no bridge to on-device health stores (R02 section 19.2). | Everything that can be configured, logged, corrected and drawn is specified here and staged in section 16. Step counting, automatic sleep detection and wearable sync are not offered. |
| Attachment-menu entry | "Currently only available for major advertisers on the Telegram Ad Platform" (TGF-02). | Not used. |
| Scan a medicine pack | The native scanner returns text; whether it reads retail barcodes is unknown (MA-A-15). A drug database with dosing text is refused in any case (non-goal N3). | Not in the first three waves. Names are typed by the user. |
| Import large health exports | The bot cannot download more than 20 MB (TGF-36). Multi-year Apple Health exports are far larger (R03 section 7.1). | Imports go through the Mini App, which parses the file on the device and uploads only normalised entries (S-14, later wave). |
| App lock | `BiometricManager` exists (TGF-23); its behaviour on Telegram Desktop and Web is unknown (MA-A-14). It does not protect the chat history. | Optional setting in a later wave, with a stated limit (section 6.7). |

## 2. Platform facts and assumptions

### 2.1 Verified facts used by this specification

Sources, all read on 2026-11-12:

- WA: Mini Apps guide and `Telegram.WebApp` reference, https://core.telegram.org/bots/webapps (read by R02 as source S6).
- BA: Bot API reference 10.4, https://core.telegram.org/bots/api (R02 sources S1 and S3).
- CL: Bot API changelog, https://core.telegram.org/bots/api-changelog (R02 source S2).
- DL: Deep links reference, https://core.telegram.org/api/links (R02 source S18).
- DT: Bot Platform Developer Terms, https://telegram.org/tos/bot-developers (R02 source S11).
- MT: Terms of Service for Mini Apps, https://telegram.org/tos/mini-apps (R02 source S12).
- JS: the published script https://telegram.org/js/telegram-web-app.js, downloaded and read for this document on 2026-11-12 (127,770 bytes; 20,809 bytes after `gzip -9`).
- NPM: the public npm registry metadata, https://registry.npmjs.org/<package>, and Maven Central metadata, https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml, read for this document on 2026-11-12.

| Id | Fact | Source |
|---|---|---|
| TGF-01 | Seven launch modes exist. Signed `initData` with the user is delivered for the Main Mini App, the menu button, inline `web_app` buttons, direct links and the attachment menu. `initData` is empty for reply-keyboard buttons and inline-mode launches. `sendData` works only for reply-keyboard launches, carries at most 4096 bytes and is unauthenticated. | WA; R02 section 11.1 |
| TGF-02 | The attachment menu is "currently only available for major advertisers on the Telegram Ad Platform". | WA; R02 section 11.1 |
| TGF-03 | `https://t.me/<bot>?startapp[=<param>][&mode=compact]` opens the Main Mini App. The parameter arrives in `initData.start_param` and in the GET parameter `tgWebAppStartParam`. `mode=fullscreen` is also defined. The `start` parameter of a bot link is limited to 64 characters of `A-Z a-z 0-9 _ -`. | WA, DL; R02 section 9 |
| TGF-04 | The page must load `https://telegram.org/js/telegram-web-app.js` in `<head>` before other scripts. `ready()` hides the loading placeholder. `expand()` maximises height. `close()` closes the app. | WA; R02 section 11.2 |
| TGF-05 | Since 2026-07-20 Mini App methods are refused when called from an origin other than the registered Mini App domain, unless the developer opts out in BotFather. | CL; R02 section 11.2 |
| TGF-06 | `WebAppInitData` fields: `query_id`, `chat_join_request_query_id`, `user`, `receiver`, `chat`, `chat_type`, `chat_instance`, `start_param`, `can_send_after`, `auth_date`, `hash`, `signature`. `WebAppUser` fields: `id`, `is_bot`, `first_name`, `last_name`, `username`, `language_code`, `is_premium`, `added_to_attachment_menu`, `allows_write_to_pm`, `photo_url`. Neither carries a time zone or presence. `initDataUnsafe` must not be trusted. The Ed25519 `signature` exists since Bot API 8.0. | WA; R02 section 11.3; R06 fact F13 |
| TGF-07 | `colorScheme` is `light` or `dark`. `themeParams` keys: `bg_color`, `text_color`, `hint_color`, `link_color`, `button_color`, `button_text_color`, `secondary_bg_color`, `header_bg_color`, `bottom_bar_bg_color`, `accent_text_color`, `section_bg_color`, `section_header_text_color`, `section_separator_color`, `subtitle_text_color`, `destructive_text_color`. Each is also a CSS variable `--tg-theme-<name-with-dashes>`. All are optional. Event `themeChanged`. | WA; R02 section 11.4 |
| TGF-08 | `viewportHeight`, `viewportStableHeight`, CSS `--tg-viewport-height` and `--tg-viewport-stable-height`, `isExpanded`, event `viewportChanged{isStateStable}`. Bottom-pinned elements must use the stable height. | WA; R02 section 11.4 |
| TGF-09 | `safeAreaInset` and `contentSafeAreaInset` with `top`, `bottom`, `left`, `right`; CSS `--tg-safe-area-inset-*` and `--tg-content-safe-area-inset-*`; events `safeAreaChanged`, `contentSafeAreaChanged`. | WA; R02 section 11.4 |
| TGF-10 | `setHeaderColor`, `setBackgroundColor`, `setBottomBarColor`. | WA; R02 section 11.4 |
| TGF-11 | `requestFullscreen()`, `exitFullscreen()`, `isFullscreen`, events `fullscreenChanged`, `fullscreenFailed{error}`; `lockOrientation()`, `unlockOrientation()` (Bot API 8.0). | WA; R02 section 11.4 |
| TGF-12 | `enableVerticalSwipes()`, `disableVerticalSwipes()` (7.7); `enableClosingConfirmation()`, `disableClosingConfirmation()` (6.2). | WA; R02 section 11.4 |
| TGF-13 | `isActive`; events `activated` and `deactivated` (8.0). The event list contains no "closed" event. | WA; R02 section 11.4; R05 section 3.2 |
| TGF-14 | On Android the User-Agent ends with `Telegram-Android/{app_version} ({manufacturer} {model}; Android {android_version}; SDK {sdk_version}; {performance_class})`, where the class is `LOW`, `AVERAGE` or `HIGH`. | WA; R02 section 11.4 |
| TGF-15 | `MainButton` and `SecondaryButton` (7.10) are of type `BottomButton`: `text`, `color`, `textColor`, `isVisible`, `isActive`, `hasShineEffect`, `position` (secondary only: `left`, `right`, `top`, `bottom`), `isProgressVisible`; methods `setText`, `onClick`, `offClick`, `show`, `hide`, `enable`, `disable`, `showProgress(leaveActive)`, `hideProgress`, `setParams`. | WA; R02 section 11.5 |
| TGF-16 | `BackButton`: `isVisible`, `onClick`, `offClick`, `show`, `hide`. `SettingsButton`: an item in the Mini App context menu with `show`, `hide`, `onClick`. | WA; R02 section 11.5 |
| TGF-17 | `HapticFeedback`: `impactOccurred(light, medium, heavy, rigid, soft)`, `notificationOccurred(error, success, warning)`, `selectionChanged()`. | WA; R02 section 11.5 |
| TGF-18 | `showPopup(PopupParams{title 0-64, message 1-256, buttons 1-3})`, `showAlert`, `showConfirm`; event `popupClosed`. | WA; R02 section 11.5 |
| TGF-19 | `showScanQrPopup(ScanQrPopupParams{text 0-64})` returns text through `qrTextReceived`. | WA; R02 section 11.5 |
| TGF-20 | `hideKeyboard()` (9.1). | WA; R02 section 11.5 |
| TGF-21 | `openLink(url, {try_instant_view})` and `openTelegramLink(url)` work only in response to a user interaction. | WA; R02 section 11.5 |
| TGF-22 | `CloudStorage`: per bot per user, 1024 keys, key 1-128 characters of `A-Z a-z 0-9 _ -`, value 0-4096 characters, stored in Telegram's cloud. `DeviceStorage`: on the device, 5 MB per user (9.0). `SecureStorage`: Keychain or Keystore, 10 items per user (9.0). | WA; R02 section 11.6 |
| TGF-23 | `BiometricManager` (7.2): `init`, `requestAccess({reason 0-128})`, `authenticate({reason 0-128})`, `updateBiometricToken`, `openSettings`; fields `isBiometricAvailable`, `biometricType`, `isAccessGranted`, `isBiometricTokenSaved`, `deviceId`. | WA; R02 section 11.6 |
| TGF-24 | `addToHomeScreen()`, `checkHomeScreenStatus(callback)` with status `unsupported`, `unknown`, `added`, `missed`; events `homeScreenAdded`, `homeScreenChecked` (8.0). | WA; R02 section 11.7 |
| TGF-25 | `downloadFile(DownloadFileParams{url, file_name})` (8.0). The server should answer with `Content-Disposition: attachment; filename="..."` and `Access-Control-Allow-Origin: https://web.telegram.org`. Event `fileDownloadRequested{status}`. | WA; R02 section 11.7 |
| TGF-26 | `requestWriteAccess(callback)`, event `writeAccessRequested{status}`, and `WebAppUser.allows_write_to_pm`. | WA; R02 section 11.7 |
| TGF-27 | `shareMessage(msg_id)` sends a `PreparedInlineMessage` created by the bot with `savePreparedInlineMessage`. | WA, BA; R02 section 10 |
| TGF-28 | `Telegram.WebApp.version`, `platform` and `isVersionAtLeast(version)` report what the user's client supports. | WA; R02 section 2 |
| TGF-29 | `InlineKeyboardButton.web_app` works only in private chats between a user and the bot and is not supported in ephemeral messages. | BA; R02 sections 6.10 and 7.1 |
| TGF-30 | `setChatMenuButton` accepts `MenuButtonWebApp{type, text, web_app}`. It may carry a `t.me` link to a Mini App of the bot instead of a URL. | BA; R02 section 8 |
| TGF-31 | WebView inspection can be enabled on iOS, Android, and Telegram Desktop beta on Windows and Linux. | WA; R02 section 21 |
| TGF-32 | In the Telegram test environment a Mini App may be loaded over plain HTTP, and the test environment uses separate accounts and bots. | WA; R02 section 21 |
| TGF-33 | Telegram's design guidelines for Mini Apps: mobile-first and responsive, mimic existing native components, animations at 60 frames per second, labels on all inputs and images, follow the theme colours, respect both safe areas, reduce effects on low performance-class Android devices. | WA; R02 section 11.4 |
| TGF-34 | Interface elements must work and must not imitate Telegram, the operating system or the browser. Every Mini App must have an accessible privacy policy, and all Mini Apps show a Privacy Policy button in their settings. | DT sections 4 and 5.3; R02 section 20.1 |
| TGF-35 | Opening a Mini App gives the developer the user's IP address. | MT; R02 section 20.3 |
| TGF-36 | A bot can download a file of at most 20 MB with `getFile`. | BA; R02 section 17 |
| TGF-37 | The published script reads the launch parameters (`tgWebAppData`, `tgWebAppVersion`, `tgWebAppPlatform`, `tgWebAppThemeParams` and others) from `location.hash`, merges them with, and stores them in, `sessionStorage` under the key `__telegram__initParams`. The stored value includes the raw `initData`. | JS |
| TGF-38 | The published script sets theme and viewport CSS variables with `document.documentElement.style.setProperty('--tg-' + name, value)`. When the page runs inside an iframe it also creates a `<style>` element and assigns its `innerHTML` from a `set_custom_style` message whose origin is `https://web.telegram.org`. | JS |
| TGF-39 | Outside Telegram the published script starts with `version` `6.0`, `platform` `unknown` and an empty `initData`. A hash parameter `tgWebAppDebug` makes it draw a debug bottom bar for the bottom buttons. | JS |
| TGF-40 | Latest published versions on 2026-11-12: `svelte` 5.57.3 (2026-10-26), `solid-js` 1.9.19, `react` and `react-dom` 19.3.1, `vite` 8.3.8, `typescript` 7.0.2, `@tanstack/svelte-query` 6.4.1, `vitest` 5.1.0, `@playwright/test` 1.65.0, `openapi-typescript` 7.13.0, `msw` 3.0.5, `@testing-library/svelte` 5.4.2, `axe-core` 4.14.0 (MPL-2.0, development only), `size-limit` 14.3.0, `@telegram-apps/sdk` 3.11.8 (last published 2025-10-08). Licences as declared in the registry: MIT, except `typescript` and `@playwright/test` (Apache-2.0) and `axe-core` (MPL-2.0). Scala.js library 1.22.0. The latest published Laminar artefact for Scala 3 (`com.raquo:laminar_sjs1_3`) is the milestone 18.0.0-M5, last updated 2026-02-27. | NPM |

### 2.2 Assumptions that need a spike

| Id | Assumption | Why it matters | Spike |
|---|---|---|---|
| MA-A-01 | The query string of the URL in `WebAppInfo.url` reaches the page unchanged when an inline `web_app` button or the menu button opens the app. | Contextual routes from chat buttons (section 3.4) | MA-SPK-1 |
| MA-A-02 | Calling `openTelegramLink` with the bot's own `?startapp=` link from inside the Mini App relaunches it with a fresh `initData`. | One-tap recovery after a session ends (section 3.5) | MA-SPK-1 |
| MA-A-03 | The `startapp` parameter has the same 64-character limit and alphabet as `start`. The design stays inside that limit either way. | Route grammar | MA-SPK-1 |
| MA-A-04 | `Intl.DateTimeFormat().resolvedOptions().timeZone` returns the device zone in every Telegram WebView (R02 assumption 6). | Time-zone mismatch prompt | MA-SPK-2 |
| MA-A-05 | `<input type="time">` and `<input type="date">` show a usable native picker on Android, iOS, Windows and macOS clients. On Linux desktop (WebKitGTK) no picker is expected, so a typed fallback is specified. | Schedule builder, entry editors | MA-SPK-2 |
| MA-A-06 | `<input type="file">` opens the system picker in every Telegram WebView, including the camera option on phones. | Imports, meal photos | MA-SPK-2 |
| MA-A-07 | The Android system back gesture triggers `backButtonClicked` when the `BackButton` is visible. | Navigation | MA-SPK-2 |
| MA-A-08 | TalkBack and VoiceOver read web content inside the Telegram WebView, and the native bottom buttons and back button are exposed to them. | Accessibility targets | MA-SPK-3 |
| MA-A-09 | Text sized in `rem` follows the operating system's text-size setting inside the WebView on Android and iOS. | Text scaling | MA-SPK-3 |
| MA-A-10 | The content security policy of R06 section 12.4 does not break the Telegram script. TGF-38 shows one known conflict: on Telegram Web the script's `<style>` injection is blocked by `style-src 'self'`, and with `require-trusted-types-for 'script'` the `innerHTML` assignment throws. The expected effect is the loss of the parent's custom style only. | Security headers | MA-SPK-4 |
| MA-A-11 | The correct `frame-ancestors` list for Telegram Web clients (R06 section 12.4 note 1). | Telegram Web support | MA-SPK-4 |
| MA-A-12 | Every client at Bot API 8.0 or later sends `signature` in `initData` for all three adopted launch modes. | Minimum client decision | MA-SPK-1 |
| MA-A-13 | Framework runtime sizes (gzip): Svelte 5 about 10 to 15 KB for an application of this size, SolidJS about 8 to 10 KB, React with React DOM about 55 to 60 KB, Scala.js with Laminar 60 KB or more before application code. | Stack decision and budget | MA-SPK-5 |
| MA-A-14 | `BiometricManager`, `SecureStorage` and `addToHomeScreen` are unavailable or partial on Telegram Desktop and Web (R02 assumption 14). | App lock, shortcut | MA-SPK-2 |
| MA-A-15 | `showScanQrPopup` does not reliably read one-dimensional retail barcodes (R02 assumption 17). | Pack scanning stays out of scope | none planned |
| MA-A-16 | `deactivated` fires on each client when the app is minimised, and a request sent while the app closes may be lost (R05 section 13). | Sleep foreground signal, unsent writes | MA-SPK-2 |
| MA-A-17 | Low-end Android phones in use have an Android System WebView of Chromium 100 or newer. | Browser baseline (section 10.4) | MA-SPK-5 |
| MA-A-18 | A WebView reload keeps `sessionStorage`, so the Telegram script restores its launch parameters after the application has changed the URL path (TGF-37). | History-based routing | MA-SPK-1 |
| MA-A-19 | `downloadFile` works on Android, iOS and Desktop; on clients where it does not, a blob download through an `<a download>` link works. | Export download | MA-SPK-2 |
| MA-A-20 | Hiding content on `deactivated` happens before the operating system takes the task-switcher snapshot. | Privacy curtain | MA-SPK-3 |

## 3. Launch, session and deep links

### 3.1 Entry points

| Entry point | How it is configured | Lands on | Verdict |
|---|---|---|---|
| Main Mini App | BotFather. "Open" button on the bot profile. Link `https://t.me/<bot>?startapp=<route>`. | The route in `start_param`, or Today | Adopted. The only link form that can be placed outside the chat (privacy policy page, onboarding text, the session-recovery link). |
| Menu button | `setChatMenuButton` with `MenuButtonWebApp` (TGF-30), text "App", set per chat when onboarding completes and reset on account deletion; the default for chats without an account stays the command list (BOT_UX BUX-MENU-01 to BUX-MENU-03, ARCHITECTURE section 7.9; ROADMAP conflict X-28) | Today | Adopted. It is the entry that stays visible when the chat is muted. Because it exists only after onboarding, gate screen G-3 is not the first thing a new user meets. |
| Inline `web_app` button | A button under a bot message, URL `https://<origin>/?route=<route>` | The named screen | Adopted for contextual jumps: "Edit schedule", "Fix time", "Open history", "Adjust sleep", "Set up" after `/start`. Not available in ephemeral messages (TGF-29). |
| Home-screen shortcut | `addToHomeScreen()` from Settings (section 6.8) | Today | Adopted as an optional convenience. |
| Named direct-link app `https://t.me/<bot>/<appname>` | A second Mini App registration | n/a | Rejected. One application on one origin covers every route through `startapp`. A second registration adds BotFather configuration and a second splash screen for no gain. |
| Reply-keyboard `web_app` button | `KeyboardButton.web_app` | n/a | Rejected. `initData` is empty, so the API cannot authenticate the user (TGF-01). |
| Inline mode | `InlineQueryResultsButton.web_app` | n/a | Rejected. `initData` is empty, and inline mode is off for the product (PRODUCT_SPEC.md non-goal N12). |
| Attachment menu | `?startattach` | n/a | Rejected. Not available to this bot (TGF-02). |

MA-LAUNCH-1. The bot offers an inline `web_app` button only in a private chat and only in a non-ephemeral message. [T in BOT_UX.md; listed here because the Mini App depends on it]

MA-LAUNCH-2. Every entry point leads to the same single-origin application. No screen is served from another origin, and the application embeds no third-party page (TGF-05). [O]

### 3.2 Launch sequence

The sequence is the same for every entry point.

1. The HTML shell loads. It contains no inline script and no inline style. It references the Telegram script in `<head>` (TGF-04), one stylesheet and one module script.
2. The application reads `Telegram.WebApp.initData` once. If it is empty, the application shows gate screen G-1 "Open in Telegram" and stops.
3. If `isVersionAtLeast('8.0')` is false, the application shows gate screen G-2 "Update Telegram" and stops (section 3.3).
4. The application applies the theme tokens (section 5.2), sets the header, background and bottom-bar colours to the page-background and bottom-bar tokens with `setHeaderColor`, `setBackgroundColor` and `setBottomBarColor` (TGF-10), paints the skeleton of the target screen, and calls `ready()` and then `expand()`.
5. It calls `disableVerticalSwipes()` (section 6.5).
6. It sends `POST session` with the raw `initData`, the device time zone (MA-A-04), the application build id, `platform`, `version` and `colorScheme`.
7. The response carries the session token and the bootstrap document: account locale, time zone, units, enabled domains with consent state, reachability, feature flags, limits, and the minimum client build the server accepts.
8. The application keeps the token in a module-scope variable. It keeps no copy of `initData` in its own state.
9. It resolves the route (section 3.4) and loads that screen's data.
10. It evaluates the launch banners in this order and shows at most one: bot blocked or write access missing, time-zone mismatch, consent re-prompt pending.

MA-LAUNCH-3. `ready()` is called after the skeleton of the target screen is painted and before any API response is awaited. [M]

MA-LAUNCH-4. `initData` is sent to exactly one endpoint, `POST session`, in the request body. It never appears in a URL, a header of another request, a log line, an error report or application state. [M][O]

MA-LAUNCH-5. The session token is held in memory only. A test that completes a full session and then inspects `localStorage`, `sessionStorage`, IndexedDB, cookies and the `CloudStorage` and `DeviceStorage` fakes finds no token and no canary health string. The only permitted `sessionStorage` entry is Telegram's own `__telegram__initParams` (TGF-37). [M]

MA-LAUNCH-6. From navigation start to the first API response the application issues at most two API requests (`session`, then the target screen's resource). The bootstrap document is part of the `session` response, not a separate request. [M]

Note on TGF-37. The Telegram script itself keeps the raw `initData` in `sessionStorage` for the life of the WebView session. The application cannot prevent that without breaking the script's reload behaviour. The exposure is bounded by the five-minute freshness window that R06 section 4.2 sets for the session exchange. This residual risk belongs to R06 threat MA-2 and is recorded here so that ARCHITECTURE.md does not lengthen that window.

### 3.3 Minimum client level

Decision (PROPOSED): the Mini App requires a Telegram client at Bot API level 8.0 or later (released 2024-11-17, R02 section 2).

Rationale: R06 section 4.2 validates `initData` with the Ed25519 `signature`, which exists since 8.0 (TGF-06), so that the API process never holds the bot token. The same level brings safe-area insets, `activated` and `deactivated`, fullscreen events, `downloadFile` and the home-screen shortcut (TGF-09, TGF-11, TGF-13, TGF-24, TGF-25), which removes a class of fallbacks.

Rejected alternative: supporting older clients by verifying the HMAC inside the bot gateway (R06 open question Q4). It keeps a second validation path alive for clients that are two years old.

Coordination note: R02 section 11.3 recommends HMAC with Ed25519 deferred, and R06 section 4 recommends Ed25519 first. This document follows R06. ARCHITECTURE.md owns the final choice. If it chooses HMAC, the minimum level may drop to 7.10, and the requirements that use 8.0 features (MA-LAYOUT-2, MA-NATIVE-10, MA-NATIVE-11, MA-NATIVE-13, MA-SLEEP-7) need version gates and fallbacks.

MA-LAUNCH-7. Features newer than 8.0 are gated with `isVersionAtLeast` and a capability check, and each has a fallback: `DeviceStorage` and `SecureStorage` (9.0, not used for data), `hideKeyboard` (9.1, fallback: blur the active element). [U]

### 3.4 Routes

One route string selects the first screen. It arrives in one of three ways, checked in this order:

1. `start_param` from a `?startapp=` link (TGF-03).
2. The query parameter `route` of the page URL, set by an inline `web_app` button (MA-A-01).
3. The URL path, when the WebView reloads an already running application (MA-A-18).

Route grammar: `<screen>` or `<screen>_<argument>`, at most 64 characters, alphabet `A-Z a-z 0-9 _ -` (MA-A-03). An identifier argument is the 22-character base64url form of the record's UUID. A date argument is `YYYYMMDD`.

| Route | Screen | Internal path |
|---|---|---|
| (none), `today` | S-01 Today | `/` |
| `today_<date>` | S-01 Today for that local date | `/day/<date>` |
| `meds` | S-02 Medication list | `/meds` |
| `med_<id>` | S-03 Medication detail | `/meds/<id>` |
| `mednew` | S-04 Medication editor, new | `/meds/new` |
| `medit_<id>` | S-04 Medication editor, existing | `/meds/<id>/edit` |
| `sched_<id>` | S-05 Schedule builder for an existing schedule | `/schedules/<id>` |
| `schednew_<medId>` | S-05 Schedule builder, new schedule for a medication | `/meds/<id>/schedules/new` |
| `log` | S-06 Log hub | `/log` |
| `water` | S-07 Hydration | `/log/water` |
| `food` | S-08 Food diary | `/log/food` |
| `mood` | S-09 Mood, history view | `/log/mood` |
| `moodnew` | S-09 Mood check-in | `/log/mood/new` |
| `sleep` | S-10 Sleep | `/log/sleep` |
| `sleepfix_<date>` | S-10 Sleep, edit sheet for that sleep day | `/log/sleep/<date>` |
| `insights` | S-11 Insights | `/insights` |
| `hist` | S-12 History calendar | `/insights/history` |
| `hist_<date>` | S-13 Day detail | `/insights/history/<date>` |
| `reports` | S-14 Reports and export | `/insights/reports` |
| `settings` | S-15 Settings | `/settings` |
| `set_<section>` | S-15 Settings section: `notif`, `tz`, `lang`, `privacy`, `data`, `about` | `/settings/<section>` |
| `share` | S-16 Sharing | `/settings/sharing` |

MA-ROUTE-1. A route selects a screen and nothing else. It never grants access. Every record named by a route is authorised by the API against the session's account, and an unknown or foreign identifier produces the "Not found" state of section 8.3, identical in both cases (R06 threat API-2). [M]

MA-ROUTE-2. An unparseable route, an unknown screen code or a route to a screen that is not enabled opens Today and shows one non-blocking notice "That link did not match a screen". The application does not echo the route text. [U][M]

MA-ROUTE-3. Internal navigation uses the History API with URL paths. It does not use the URL fragment, because the Telegram script reads its launch parameters from the fragment (TGF-37). The static host answers every unknown path with the HTML shell. [M]

MA-ROUTE-4. When a route opens a screen that is not a tab root, the application builds the logical parent chain (for example Today, Meds, Medication detail), so that `BackButton` leads to the parent and not out of the application. [M]

MA-ROUTE-5. Caregiver invitations are accepted in chat through a `?start=` link (R06 section 5.2). The Mini App defines no invitation route. [O]

### 3.5 Gate screens

A gate screen replaces the whole application. It has no tab bar.

| Id | Condition | Content | Action |
|---|---|---|---|
| G-1 | `initData` is empty (opened in a browser, or from an unsupported entry) | "Dosegram runs inside Telegram." The canonical bot username as text. | Link `https://t.me/<bot>?startapp` |
| G-2 | Client below Bot API 8.0 | "This Telegram version is too old for Dosegram. Update Telegram, then open Dosegram again." | `MainButton` "Close" calls `close()` |
| G-3 | `session` answers 403 `account_required` (the one code of ARCHITECTURE section 9.4) | "Start Dosegram in chat first. Setup takes about a minute and includes your consent choices." | `MainButton` "Open chat" calls `openTelegramLink('https://t.me/<bot>?start=<payload>')`; BOT_UX.md names the payload |
| G-4 | `session` answers 401 (stale `auth_date`, bad signature) or a later request answers 401 after the freshness window | "This session has ended." | `MainButton` "Continue" calls `openTelegramLink('https://t.me/<bot>?startapp=<current route>')` (MA-A-02). Secondary text: "If nothing happens, close this window and open Dosegram from the chat." |
| G-5 | The bootstrap document's minimum client build is newer than the running build | "A new version of Dosegram is available." | `MainButton` "Reload" calls `location.reload()` |
| G-6 | Network failure before the first successful `session` response | "No connection." | `MainButton` "Try again" repeats step 6 of the launch sequence |
| G-7 | App lock is enabled and biometric authentication fails or is unavailable (later wave) | "Dosegram is locked on this device." | Section 6.7 |

MA-GATE-1. Every gate screen is rendered from the application bundle with no API data, uses the theme tokens, and is reachable by screen reader with one heading and one action. [M]

MA-GATE-2. On a 401 inside the five-minute freshness window (measured from `auth_date`), the application repeats the session exchange once, silently, with the same `initData`, and retries the failed request. Outside that window it shows G-4 and keeps the current route for the relaunch link. [M]

MA-GATE-3. While a form is dirty, the application saves the server-side draft (section 8.6) before it shows G-4, if the session still allows it. After relaunch the editor offers the draft. [M]

### 3.6 Session lifetime as the user experiences it

R06 section 4.3 proposes a sliding idle timeout of 15 minutes and an absolute lifetime of 12 hours. `initData` does not refresh while the app stays open (R02 section 11.3, assumption), so every expiry ends in gate screen G-4.

MA-SESSION-1. While the application is active (`isActive` is true) and the user has produced input in the last 5 minutes, the application keeps the session alive with its normal requests or, if none occurred for 5 minutes, one `POST session/keepalive`. Without user input it sends nothing, so an application left open on a desk expires. [M]

MA-SESSION-4. Decision rule tied to spike MA-SPK-1 (ROADMAP G1.3; ARCHITECTURE API-06). Telegram clients keep a Mini App minimised for long periods, and a WebView that the operating system reloads presents the same old `initData`, which the server accepts for only 300 seconds after `auth_date`. A return after 15 idle minutes and a reload after 5 minutes therefore both end on G-4, and this is a common path for a tool used to edit a registry, not an edge case. If the spike shows that the one-tap relaunch of G-4 (MA-A-02) does not deliver fresh `initData` on every tested client, the idle timeout of a session bound to a live WebView is set equal to the absolute lifetime of 12 hours, and the privacy curtain (MA-SEC-7) is the control against an onlooker; the token is held in memory only, so a short idle timeout adds little. The spike measures, per client, a return from the minimised state after 20 minutes and an operating-system reload. [L]

MA-SESSION-2. Background refresh (section 8.7) follows the same rule: no polling without user input in the last 5 minutes. [M]

MA-SESSION-3. `DELETE session` is called when the user chooses "Sign out on this device" in Settings. The application then shows G-4 without the relaunch action. [M]

## 4. Information architecture and navigation

### 4.1 Navigation map

```
Dosegram Mini App
|
+-- Today (tab)                          S-01
|     +-- Occurrence detail (sheet)
|     +-- Log an as-needed dose (sheet)
|     +-- Day detail for another date    S-13
|
+-- Meds (tab)                           S-02
|     +-- Medication detail              S-03
|     |     +-- Medication editor        S-04
|     |     +-- Schedule builder         S-05
|     |     +-- Refill (sheet)
|     +-- Medication editor, new         S-04
|
+-- Log (tab)                            S-06
|     +-- Hydration                      S-07
|     +-- Food diary                     S-08
|     |     +-- Meal entry (sheet)
|     +-- Mood                           S-09
|     |     +-- Mood check-in
|     +-- Sleep                          S-10
|     |     +-- Night entry (sheet)
|     +-- Measurements, Symptoms (wave V2; S-17 and S-18 are specified at ROADMAP checkpoint CP9-d)
|
+-- Insights (tab)                       S-11
|     +-- History calendar               S-12
|     |     +-- Day detail               S-13
|     +-- Reports and export             S-14
|
+-- Settings (SettingsButton, header control)  S-15
      +-- Language and region
      +-- Time zone
      +-- Notifications and quiet hours
      +-- Privacy and consent
      +-- Your data (export, delete)
      +-- Sharing                        S-16
      +-- About and help
```

The deepest path is three steps below a tab root (Meds, Medication detail, Schedule builder).

Decision: four tabs, with the four tracking domains behind one Log tab. Rationale: medication is the first job of the product and gets its own tab; the other domains are enabled one by one through consent, so a tab per domain would show tabs that are empty for many users. Rejected alternative: a tab for each domain (seven or more destinations, which does not fit a 320-pixel-wide viewport with 48-pixel targets). Rejected alternative: a drawer menu, which hides the primary destinations behind a control at the top of the screen, out of thumb reach.

### 4.2 Screen inventory

| Id | Screen | Tab | Primary action | Module | Suggested wave |
|---|---|---|---|---|---|
| S-01 | Today | Today | Answer a due dose | `daily-overview`, `intake-log` | MVP |
| S-02 | Medication list | Meds | Add medication | `medication-registry` | MVP |
| S-03 | Medication detail | Meds | Edit | `medication-registry`, `schedule-and-reminders` | MVP |
| S-04 | Medication editor | Meds | Save | `medication-registry` | MVP (inventory fields: V1) |
| S-05 | Schedule builder | Meds | Save schedule | `schedule-and-reminders` | MVP for fixed times, weekdays and as-needed; V1 for intervals, every N days and cycles; V2 for tapering |
| S-06 | Log hub | Log | Open a domain | all tracking modules | MVP |
| S-07 | Hydration | Log | Add water | `hydration` | MVP |
| S-08 | Food diary | Log | Add meal | `nutrition` | V1 |
| S-09 | Mood | Log | Save check-in | `mood` | MVP |
| S-10 | Sleep | Log | Log a night | `sleep` | V1 (passive estimate: later in V1 or V2 per ROADMAP.md) |
| S-11 | Insights | Insights | Change range | `insights-and-correlations` | V1 (correlation cards: V2) |
| S-12 | History calendar | Insights | Open a day | `insights-and-correlations` | MVP for medication, water and mood |
| S-13 | Day detail | Insights | Correct an entry | `daily-overview`, `intake-log` | MVP |
| S-14 | Reports and export | Insights | Export | `visual-reports`, `import-export` | MVP for full export; V1 for report cards and PDF; V2 for import |
| S-15 | Settings | none | Change a setting | `identity-and-consent`, `schedule-and-reminders`, `localisation` | MVP |
| S-16 | Sharing | none | Invite | `sharing-and-caregivers` | V2, if adopted |

The wave column is a recommendation to ROADMAP.md, which decides.

### 4.3 Navigation rules

MA-NAV-1. The tab bar is shown on the four tab roots (S-01, S-02, S-06, S-11) and nowhere else. The native `MainButton` and `SecondaryButton` are shown only on screens without the tab bar. The two never appear together. [M]

MA-NAV-2. `BackButton` is visible exactly when the current screen is not a tab root and not a gate screen. Its click goes to the previous history entry, or to the logical parent when the screen was opened by a route (MA-ROUTE-4). On a tab root the Telegram header shows its own close control. [M]

MA-NAV-3. `SettingsButton.show()` is called once after launch. Its click opens S-15. Because the native item sits in a context menu, Today also carries a visible settings control in its header. [M]

MA-NAV-4. Switching tabs keeps each tab's scroll position and its in-memory navigation stack for the life of the session. Selecting the active tab again returns that tab to its root. [M]

MA-NAV-5. Leaving a dirty form by `BackButton`, a tab change or a route change opens a native `showPopup` with three buttons: "Keep editing", "Discard", "Save draft" (TGF-18). While a form is dirty, `enableClosingConfirmation()` is active; it is disabled again when the form is saved or discarded. [M]

MA-NAV-6. Every navigation action is available as a visible control. No destination, action or dismissal depends on a swipe, a long press or a double tap alone. [M]

MA-NAV-7. Sheets (bottom-anchored panels for a short task) are modal: focus is trapped inside, the page behind is inert, `BackButton` or the sheet's Close control dismisses it, and focus returns to the control that opened it. A sheet that edits data uses `MainButton` for its primary action. [M]

MA-NAV-8. A screen transition shows the destination's skeleton within 300 ms of the tap on the reference device (section 10). Transitions animate opacity and transform only, last at most 200 ms, and are replaced by an instant cut when reduced motion applies (section 5.5). [M]

### 4.4 Layout frame

```
+------------------------------------------+
| Telegram header (native): close or back, |
| title "Dosegram", context menu           |
+------------------------------------------+   <- content safe area top
| Screen header: title, 0-2 header controls|
+------------------------------------------+
| Banner slot (0 or 1 banner)              |
+------------------------------------------+
|                                          |
| Scrolling content                        |
| max width 560 px, centred on wide        |
| viewports; side padding 16 px            |
|                                          |
+------------------------------------------+
| Tab bar (tab roots only)                 |   <- pinned to the stable viewport
+------------------------------------------+   <- content safe area bottom
| MainButton / SecondaryButton (native,    |
| task screens only)                       |
+------------------------------------------+
```

MA-LAYOUT-1. Bottom-pinned elements (tab bar, toasts, sheets) are positioned against `--tg-viewport-stable-height`, never against `100vh` (TGF-08). [M]

MA-LAYOUT-2. Content is padded by `--tg-content-safe-area-inset-*` and `--tg-safe-area-inset-*` on all four sides, and the layout updates on `safeAreaChanged` and `contentSafeAreaChanged` without reload (TGF-09). [M]

MA-LAYOUT-3. The layout works from 320 px to 1280 px viewport width. Up to 719 px it is one column. From 720 px (Telegram Desktop and Web in a wide window) Insights and Day detail use two columns; forms stay one column at a maximum width of 560 px. There is no horizontal scrolling of the page at any width or at 200 percent text size. [M]

MA-LAYOUT-4. When the on-screen keyboard opens, the focused field stays visible, and no bottom-pinned element of the page covers it. Toasts are suppressed while a text field has focus. [L]

## 5. Design system

### 5.1 Direction

The Mini App should look like a part of the user's Telegram, not like a web site inside it. Telegram asks for this (TGF-33), and it is also the honest choice for a health record: the user's own theme, the user's own text size, no brand theatre around medical data.

Plan:

- Colour: surfaces, text and the accent come only from `themeParams`. Dosegram adds one small palette for data: five domain colours and one "recorded" colour, each in a light and a dark variant.
- Type: the system interface font of the device, set in `rem`, with tabular figures wherever numbers align. No web font is downloaded. Rationale: it matches the native header and bottom buttons that surround the page, it costs zero bytes on a slow connection, and it follows the operating system's text-size setting (MA-A-09). Rejected alternative: bundling Inter to match the server-rendered cards (R04 section 8.4); the cards are images with their own typography, and the Mini App gains nothing from 100 KB of font files.
- Layout: grouped sections on the secondary background, the idiom of Telegram's own settings screens. Left-aligned text in left-to-right locales. No decorative cards, no shadows, no gradients outside the mood blob.
- Signature elements, used sparingly:
  1. The day line: one horizontal 24-hour line on Today and Day detail. Doses are marks on it, water entries are ticks below it, sleep is a band, the present moment is a hairline. It is the one drawn element that belongs to this product, because the product is about things that happen at times of day.
  2. The mood blob (section 7.9), drawn from the same parameters as the server-rendered blob.
  3. One set of status glyphs for dose states (section 5.4), identical in every list, chart legend and calendar.

Reviewed against generic defaults: the plan avoids identical rounded cards, an own accent colour, eyebrow labels in capitals, numbered section markers and entrance animations. Section headers are sentence case in `section_header_text_color`. The only motion that is not a response to the user's action is the slow breathing of the mood blob, and it is off under reduced motion.

MA-DS-1. The application loads no font file, no image and no script from any origin other than its own, except `https://telegram.org/js/telegram-web-app.js` (R06 section 12.4). [O]

MA-DS-2. Interface elements do not imitate Telegram's own dialogs, the operating system's dialogs or browser chrome (TGF-34). Confirmation of a destructive action uses the real native popup (`showPopup`, `showConfirm`), not a drawn copy of it. [O, design review]

### 5.2 Semantic tokens bound to themeParams

Components use the semantic token only. No component reads a `--tg-theme-*` variable or a literal colour.

| Token | Bound to | Light fallback | Dark fallback | Used for |
|---|---|---|---|---|
| `--dg-bg` | `secondary_bg_color` | `#F0F2F5` | `#0F1113` | Page background behind sections |
| `--dg-surface` | `section_bg_color`, then `bg_color` | `#FFFFFF` | `#1F2226` | Sections, list rows, sheets |
| `--dg-surface-alt` | `bg_color` | `#FFFFFF` | `#17191C` | Inputs inside sections, chart plot area |
| `--dg-text` | `text_color` | `#111418` | `#F2F4F7` | Primary text |
| `--dg-text-muted` | derived (section 5.3) | `#5B6470` | `#A3ABB5` | Secondary text, units, captions |
| `--dg-text-section` | `section_header_text_color`, corrected | `#5B6470` | `#A3ABB5` | Section headers |
| `--dg-separator` | `section_separator_color` | `#D9DDE3` | `#2C3036` | Row separators, chart grid |
| `--dg-accent` | `button_color`, corrected | `#1F6FD0` | `#2A72D0` | Filled primary controls, selected tab, "due now" emphasis |
| `--dg-on-accent` | `button_text_color` | `#FFFFFF` | `#FFFFFF` | Text and glyphs on `--dg-accent` |
| `--dg-accent-text` | `accent_text_color`, then `link_color`, corrected | `#1F6FD0` | `#6CB0FF` | Text buttons, links, selected values |
| `--dg-danger` | `destructive_text_color`, corrected | `#C62828` | `#FF6B6B` | Destructive actions only. Never a health value, never a dose state. |
| `--dg-focus` | `--dg-accent-text` | as above | as above | Focus ring, 2 px, offset 2 px |
| `--dg-bottom-bar` | `bottom_bar_bg_color`, then `secondary_bg_color` | `#F0F2F5` | `#0F1113` | Tab bar background |

The fallback columns are Dosegram's own values for the case where a key is absent (all keys are optional, TGF-07). They are not a statement about Telegram's default themes.

MA-THEME-1. The token set is applied before the first paint from `themeParams` and re-applied within 100 ms of `themeChanged`, without a reload and without losing form state. [M]

MA-THEME-2. The application has no theme switch of its own. Light or dark follows `colorScheme`. The setting "Image theme" in S-15 concerns the images the bot sends, not the Mini App. [M]

MA-THEME-3. On every launch the application reports `colorScheme` in the `session` request, so that the backend can choose the theme of chat images for accounts set to "auto" (R04 section 8.3). [M]

### 5.3 Derived tokens and the contrast guard

Telegram themes are chosen by the user and may be customised. Two facts follow: a theme pair can fail the contrast targets of section 9, and `hint_color` is designed for placeholder-weight text, not for content.

The theme adapter is a pure function from `themeParams` and `colorScheme` to the token set. It is unit-tested with table inputs.

MA-THEME-4. `--dg-text-muted` is computed, not taken from `hint_color`: it is `text_color` mixed toward `--dg-surface` until the contrast against `--dg-surface` is as close as possible to 7:1 and never below 4.5:1. [U]

MA-THEME-5. For each of these pairs the adapter measures the WCAG contrast ratio and corrects the foreground by mixing it toward black or white in steps of 4 percent, at most 12 steps, until the target is met: `--dg-text` on `--dg-surface` and on `--dg-bg` (4.5:1); `--dg-accent-text` on `--dg-surface` (4.5:1); `--dg-danger` on `--dg-surface` (4.5:1); `--dg-text-section` on `--dg-bg` (4.5:1); `--dg-on-accent` on `--dg-accent`, corrected by adjusting `--dg-accent` (4.5:1); `--dg-separator` against `--dg-surface` (no target; decorative). If 12 steps do not reach the target, the adapter uses the fallback pair of the current `colorScheme`. [U]

MA-THEME-6. The data colours of section 5.4 are checked the same way against `--dg-surface` with a target of 3:1 for graphics. A data colour that fails is replaced by its variant from the other scheme, and if that fails too, by `--dg-text`. Because no meaning is carried by colour alone (MA-A11Y-6), this substitution never removes information. [U]

MA-THEME-7. The native `MainButton` keeps Telegram's own colours. The application sets `color` and `textColor` on a bottom button only to mark it disabled-looking while a form is invalid, using `--dg-separator` and `--dg-text-muted` values. [M]

Rejected alternative: ignoring `themeParams` and shipping an own audited palette. It would guarantee contrast, but the page would clash with the native header and buttons that frame it, and Telegram's guidelines ask for the theme to be followed (TGF-33).

### 5.4 Data and status tokens

Values are provisional. VISUALS_AND_ANALYTICS.md owns the final palette for server images. The requirement on both documents is one shared token source: a single design-token file in the repository from which the Mini App stylesheet and the renderer's token sets are generated (MA-Q-9).

| Token | Meaning | Light | Dark |
|---|---|---|---|
| `--dg-domain-medication` | Medication marks and series | `#2456C8` | `#7FA6FF` |
| `--dg-domain-hydration` | Water ring, bars, ticks | `#0B7F8C` | `#4CC4D2` |
| `--dg-domain-nutrition` | Meal marks | `#A85A00` | `#F0A04B` |
| `--dg-domain-mood` | Mood series outside the blob | `#B0307A` | `#F07AB8` |
| `--dg-domain-sleep` | Sleep bands and bars | `#6A42C2` | `#B49CFF` |
| `--dg-recorded` | A completed, recorded state (dose taken, goal reached) | `#1F7A4D` | `#5FCF94` |
| `--dg-neutral-strong` | Skipped, missed, unknown glyphs | `--dg-text` | `--dg-text` |
| `--dg-neutral-weak` | Empty calendar cells, gaps, track of a ring | `--dg-separator` | `--dg-separator` |
| `--dg-estimate` | Hatch pattern for estimated values (sleep tier T1) | `--dg-domain-sleep` at 45 percent with a diagonal hatch | same |

Calculated contrast of the light values against `#FFFFFF` is between 4.7:1 and 6.6:1, and of the dark values against `#1F2226` above 6:1 (computed for this document with the WCAG formula; to be re-checked by the lint of section 14.4).

Dose states. The glyph and the word carry the meaning; colour only supports it.

| State (ADR-012 lifecycle) | Glyph | Word | Colour |
|---|---|---|---|
| pending | Empty circle | "Later" with the time | `--dg-text-muted` |
| due | Filled dot in a ring | "Due" with the elapsed time | `--dg-accent` |
| snoozed | Clock | "Snoozed until HH:MM" | `--dg-text-muted` |
| taken, on time | Check mark in a filled circle | "Taken HH:MM" | `--dg-recorded` |
| taken, late | Check mark in a filled circle | "Taken HH:MM, late" | `--dg-recorded` |
| skipped | Horizontal bar in a circle | "Skipped" | `--dg-neutral-strong` |
| missed | Circle with a diagonal stroke | "Not confirmed" | `--dg-neutral-strong` |
| unknown | Question mark in a dashed circle | "No record" | `--dg-text-muted` |
| cancelled | not listed on Today; struck through in history | "Not scheduled" with the reason | `--dg-text-muted` |

MA-DS-3. Skipped, missed and unknown are never drawn in `--dg-danger` or any red. Unknown is never merged into missed. A denominator of zero is shown as "No data", never as 0 percent. A gap in a series is drawn as a gap (PRODUCT_SPEC.md principle 4; R04 section 8.1). [G][M]

MA-DS-4. The user-visible words for dose states are those of the status vocabulary in BOT_UX section 22.4, which owns them: "Not confirmed" for the missed state, "No record" for unknown, "Item 1" onward for discreet rows. This document defines no status word of its own. No screen uses the words fail, bad, poor, good or streak for medication records (PRODUCT_SPEC.md principle 9). [O]

### 5.5 Type, spacing, shape, motion

| Token group | Values |
|---|---|
| Font family | The system interface font stack of the platform; `font-variant-numeric: tabular-nums` for times, quantities and table columns |
| Type scale (rem, 1 rem = the WebView default, normally 16 px) | caption 0.8125; secondary 0.875; body 1; emphasised body 1 at weight 600; title 1.25 at weight 600; screen title 1.5 at weight 700; metric 2 at weight 700 |
| Minimum text size | 0.8125 rem (13 px at default size). No text below it, including chart labels. |
| Line height | 1.4 for body, 1.2 for titles and metrics |
| Line length | At most 70 characters for running text |
| Spacing scale (px) | 4, 8, 12, 16, 20, 24, 32. No other values. |
| Radius (px) | 8 for inputs and buttons, 12 for sections and sheets, full for chips and the tab indicator |
| Touch target | At least 48 by 48 CSS px for every interactive element; at least 8 px between adjacent targets |
| Row height | At least 48 px for one-line rows, 64 px for two-line rows |
| Elevation | None. Sheets are separated by a scrim of `--dg-text` at 40 percent opacity. |
| Motion durations | 120 ms press feedback, 200 ms state change and sheet, 300 ms maximum |
| Motion properties | `transform` and `opacity` only |

MA-DS-5. Reduced motion applies when `prefers-reduced-motion: reduce` matches, when the Android performance class in the User-Agent is `LOW` (TGF-14), or when the user turns on "Reduce motion" in S-15. Under reduced motion every transition is an instant cut, the mood blob does not breathe or morph, and charts draw without animation. [M]

MA-DS-6. No element flashes, and no animation loops for more than 5 seconds without a visible control to stop it, except the mood blob's breathing, which stops with the setting of MA-DS-5. [M]

### 5.6 Component inventory

Each component has the states default, pressed, focus-visible, disabled and, where it loads data, loading and error. There is no hover-only behaviour.

| Component | Purpose | Notes |
|---|---|---|
| `Section`, `Row` | Grouped list in the Telegram settings idiom | A row is a single button or link when it navigates; its accessible name is the whole row text |
| `TabBar` | Four destinations | `role="tablist"` is not used; it is a `nav` with links and `aria-current="page"` |
| `Button` (filled, tinted, text, danger) | In-page actions | Label is a verb phrase; the same action keeps one name across the flow |
| `Chip`, `ChipGroup` | Single or multiple choice of up to 7 short options | Native radio or checkbox inputs underneath |
| `Segmented` | 2 to 5 mutually exclusive views | Native radio inputs |
| `Field` | Label, input, help text, error text | Label always visible above the input; `aria-describedby` links help and error |
| `NumberField` | Decimal quantity with unit | `inputmode="decimal"`; accepts comma and dot; stepper buttons optional |
| `TimeField`, `DateField` | Clock time and date | Native `type="time"` and `type="date"`; typed fallback `HH:MM` and `YYYY-MM-DD` with pattern validation where the picker is missing (MA-A-05) |
| `Stepper` | Small integer or fixed-step value | Two 48 px buttons and a live value; no drag needed |
| `Slider` | Position on a labelled scale | Native `type="range"` with `aria-valuetext`; paired with a `Stepper` alternative |
| `Toggle` | Immediate on or off setting | Native checkbox with `role="switch"` |
| `Sheet` | Short modal task | MA-NAV-7 |
| `Banner` | One launch or connectivity notice | `role="status"`; at most one visible |
| `Toast` | Outcome of an action, with optional Undo | `role="status"`, 8 seconds, pauses while focused |
| `Skeleton` | Loading placeholder with the final layout's shape | `aria-busy` on the region |
| `EmptyState`, `ErrorState` | Section 8 | One sentence, one action |
| `DoseCard`, `DoseRow` | An occurrence with its state and actions | Section 7.1 |
| `StatusGlyph` | Dose state glyph of section 5.4 | Inline SVG symbol with a text alternative supplied by the parent |
| `DayLine` | 24-hour line with marks | Decorative duplicate of the list below it; `aria-hidden="true"` |
| `Vessel` | Progress to a goal, drawn as a vertical meter; hydration is a vessel, not a ring, on every surface (ROADMAP conflict X-12) | `role="img"` with a label that states value, goal and percent |
| `BarChart`, `LineChart`, `StackedBar` | Series over days | Section 7.11 rules |
| `CalendarHeatmap` | Month or year grid | Section 7.12 |
| `SleepTimeline` | Nights on a clock axis | Section 7.10 |
| `MoodBlob` | Parametric blob | Section 7.9 |
| `DataTable` | Text alternative of any chart | Native table markup with a caption |
| `ReportCardImage` | Server-rendered card | `img` from a blob URL with the text equivalent as `alt` |

Decision: charts are scenes. The server lays out each chart with the scene primitives of R04 section 6.1 (vessel, bar, stacked bar, line, calendar grid, timeline), the same primitives that produce the chat images, and returns the scene as JSON from `insights/series/{metric}` and the other summary resources. One in-repository scene painter creates SVG DOM nodes from it; the client has no axis, scale or layout code. This is the single mechanism of the document set (VISUALS VA-MA-02; ARCHITECTURE section 9.5.1; ROADMAP conflict X-30). Rejected alternatives: client-side SVG chart components with their own axis and scale helper, which the first draft of this document specified and which would be a second layout engine beside the server's; and a chart library. It would add tens of kilobytes to a budget of 250 KB (section 10), bring its own colours, fonts and tooltips that must be overridden to follow the theme, and still not provide the day line, the sleep timeline or the blob.

MA-DS-7. Interactive charts in the Mini App and server-rendered cards use the same encoding for the same thing: the same glyph per dose state, the same hue per domain, the same hatch for an estimate, the same treatment of gaps. A fixture set renders both for the same snapshot, and a reviewer compares them on the visual review sheet that R04 section 12 already defines. [G, design review]

### 5.7 Copy rules for the Mini App

1. Sentence case everywhere. No capitals-only labels.
2. A control says what it does: "Save schedule", "Add 250 ml", "Delete account". The outcome message uses the same verb: "Schedule saved".
3. An error says what happened and what to do next. It does not apologise and does not blame.
4. An empty state says what the screen will show and offers the first action.
5. Numbers carry their unit. Times follow the account's 12-hour or 24-hour setting. Dates follow the account locale.
6. Estimates are called estimates. A value from a device import names the device.
7. Product-authored text names no disease and gives no instruction about treatment. The standing sentence for questions about a late or missed dose is the one defined by BOT_UX.md from `docs/MEDICATION_REMINDER_UX.md`.
8. Every string comes from the message catalogue of the account locale. First locales: English and Ukrainian (R04 open question Q10). Layouts tolerate strings 40 percent longer than English. Plural forms use `Intl.PluralRules`.
9. Strings passed to `showPopup` respect its limits in every locale: title at most 64 characters, message at most 256 (TGF-18). A catalogue lint checks this. [O]

Supervisor decision, 2026-11-14, owner asleep (ROADMAP G2.22): the catalogues are `src/i18n/en.ts` and `src/i18n/uk.ts`; `uk` has the type of `en`, so a missing key or a function with other arguments fails the type check. `src/i18n/index.svelte.ts` holds the locale as reactive state and serves the catalogue of the current locale; screens import it under the name they used before (`import { t as en }`), so a language change re-renders them without a reload. The locale comes from `language` in the session's bootstrap document, then from the settings screen; before the session the gate screens follow the client's `language_code` (MA-SEC-6). Dates and numbers use `Intl` with the locale; the server writes its sentences in the account's language too.

## 6. Native controls

### 6.1 Bottom buttons

| Screen kind | `MainButton` | `SecondaryButton` |
|---|---|---|
| Tab roots | Hidden | Hidden |
| Editor (S-04, S-05, entry sheets, settings sub-pages with a form) | "Save ..." with the object named ("Save medication", "Save schedule"). Shows progress while the request runs. | Hidden. Delete actions are never placed here (MA-NATIVE-3). |
| Mood check-in | "Save check-in" | Hidden |
| Export request (S-14) | "Request export" | "Cancel" |
| Account deletion (S-15) | Hidden. The danger button is in the page (MA-NATIVE-3). | Hidden |
| Gate screens | The single recovery action | Hidden |

MA-NATIVE-1. The application registers exactly one click handler per bottom button at a time and removes it with `offClick` when the screen that owns it leaves. A test that opens and closes an editor 20 times and then presses the button records exactly one save request. [M]

MA-NATIVE-2. While a save request is in flight the button shows `showProgress(false)`, which also deactivates it, and the form's fields are read-only. On failure the button returns to its enabled state and the error is shown in the page. [M]

MA-NATIVE-3. A destructive action (delete an entry, archive a medication, withdraw consent, delete the account) is never the `MainButton`. It is an in-page `Button` of kind danger, placed last in the page and away from the bottom edge, followed by a native confirmation (`showPopup` with a destructive-type button). [M]

MA-NATIVE-4. On a client where `SecondaryButton` is unavailable, the secondary action is an in-page text button directly above the bottom edge. With the minimum level of section 3.3 this branch is exercised only in the fake (section 14.1). [U]

### 6.2 BackButton and SettingsButton

Rules MA-NAV-2 and MA-NAV-3. The application never draws its own back arrow in the page header, because the native one is always present on non-root screens; two back controls would compete.

### 6.3 Haptics

| Event | Call |
|---|---|
| Dose marked taken; check-in saved; goal reached for the first time that day | `notificationOccurred('success')` |
| Validation failed on save; request rejected | `notificationOccurred('error')` |
| Destructive confirmation shown | `notificationOccurred('warning')` |
| Chip, segment, stepper or slider stop changed | `selectionChanged()` |
| Quick-add water; snooze chosen; tab changed | `impactOccurred('light')` |

MA-NATIVE-5. Haptics accompany a visible change and never replace one. They are skipped under "Reduce motion" (MA-DS-5) and can be turned off in S-15. No haptic fires without a direct user action. [M]

### 6.4 Popups

`showConfirm` and `showPopup` are used for: discarding a dirty form, deleting an entry, archiving a medication, ending a schedule, and the first confirmation of account deletion. `showAlert` is not used for errors; errors are shown in the page so that a screen reader user can re-read them.

### 6.5 Viewport, swipes, fullscreen, closing

MA-NATIVE-6. The application calls `expand()` on every launch and `disableVerticalSwipes()` once (TGF-12). Rationale: every screen scrolls vertically, and a downward scroll gesture at the top of a list must not minimise the app. The Telegram header still minimises or closes it. Rejected alternative: compact half-height launches for single tasks; the viewport is unstable when the keyboard opens and differs between clients. [M]

MA-NATIVE-7. There is no pull-to-refresh. Each data screen has a visible "Refresh" control in its header overflow, and data refreshes on `activated` (section 8.7). [M]

MA-NATIVE-8. The application never calls `requestFullscreen()` or `lockOrientation()`. No screen needs them. If the application is launched with `mode=fullscreen` (TGF-03) or the user's client starts it fullscreen, it must still lay out correctly: the screen header starts below `--tg-content-safe-area-inset-top`. Rejected alternative: fullscreen chart views; they would need the application to draw around the client's floating controls on each platform. [M][L]

MA-NATIVE-9. `enableClosingConfirmation()` is active while a form is dirty or while the unsent-writes queue of section 8.4 is not empty, and inactive otherwise. [M]

MA-NATIVE-10. On `deactivated` the application stops polling, flushes the unsent-writes queue if online, saves a dirty form's draft, and, when the privacy curtain setting is on, covers the content (section 11). On `activated` it refreshes the visible screen if its data is older than 30 seconds. [M]

### 6.6 Storage

Decision: no health data and no credential in any client-side store.

| Store | Use |
|---|---|
| Memory | Session token, all records, form state, unsent writes |
| `CloudStorage` (TGF-22) | Interface preferences only, listed below |
| `DeviceStorage` | Not used |
| `SecureStorage` | Not used for data. Reserved for the app-lock secret if `BiometricManager`'s own token store proves insufficient (section 6.7). |
| `localStorage`, `sessionStorage`, IndexedDB, Cache API, service worker | Not used by the application. Telegram's script uses `sessionStorage` for its own launch parameters (TGF-37). |

`CloudStorage` keys (complete list, PROPOSED):

| Key | Values | Purpose |
|---|---|---|
| `ui_v1_last_tab` | `today`, `meds`, `log`, `insights` | Not used for launch (launch always follows the route); used to restore the tab after gate screen G-5's reload |
| `ui_v1_insights_range` | `7`, `30`, `90` | Default range on S-11 |
| `ui_v1_calendar_mode` | `month`, `year` | Default mode on S-12 |
| `ui_v1_hints_seen` | Comma-separated hint ids | One-time hints (home-screen shortcut, day line legend) |
| `ui_v1_haptics` | `on`, `off` | Haptics preference |

Rationale: `CloudStorage` values live with Telegram outside the operator's encryption and deletion controls (R02 section 11.6), and R06 threat MA-6 forbids health data in any device store. A registry of tens of rows loads fast enough from the API that an offline copy is not worth a second place where medication names live.

Rejected alternative: a `DeviceStorage` draft and offline queue, as R02 section 11.6 allows. It would let a queued "taken" survive the app being closed while offline. It would also leave medication names and intake times in a store that the account-deletion flow cannot reach. See MA-Q-3.

MA-STORE-1. A test writes canary strings into every user-editable field, exercises every screen, and then asserts that no canary appears in `CloudStorage`, `DeviceStorage`, `SecureStorage`, `localStorage`, `sessionStorage`, IndexedDB, the Cache API or cookies. [M][O]

MA-STORE-2. `CloudStorage` access is asynchronous and may fail. Every read has a default and a 500 ms timeout; a failure never blocks a screen. [U]

### 6.7 Biometric app lock (later wave)

R06 section 4.6 defines the lock as a server-enforced requirement: when the account has app lock on, the session exchange needs a lock token that only the device's biometric gate releases.

Flow to enable: S-15, "Privacy and consent", "App lock". The application calls `BiometricManager.init()`; if `isBiometricAvailable` is false it shows "Not available on this device" and stops. Otherwise `requestAccess({reason})`, then `PUT app-lock` (confirmed in chat by step-up), which returns a lock token; then `updateBiometricToken(token)`.

Flow on launch: when the bootstrap of a first, limited `session` response says `app_lock_required`, the application calls `authenticate({reason})`, receives the stored token and repeats `POST session` with it. Failure or cancellation shows gate screen G-7 with "Try again".

MA-LOCK-1. The `reason` strings are at most 128 characters in every locale (TGF-23). [O]

MA-LOCK-2. On a device without biometric support, an account with app lock on can open the Mini App only after a confirmation in chat for that session (step-up), so the lock cannot be bypassed by switching to Telegram Desktop or Web. This is PROPOSED; see MA-Q-6. [M]

MA-LOCK-3. The setting screen states the limit in one sentence: the lock protects the Mini App on this device; it does not hide the chat with Dosegram or the images in it. [M]

### 6.8 Home-screen shortcut

MA-NATIVE-11. S-15 "About and help" shows a row "Add to home screen" when `checkHomeScreenStatus` returns `missed` or `unknown`, hides it for `unsupported`, and shows "Added" for `added` (TGF-24). The row calls `addToHomeScreen()`. A one-time hint on Today offers the same after the third launch. Rationale: when the chat is muted, the shortcut and the menu button are the ways back into the product (R02 section 15). [M][L]

### 6.9 Links, downloads, write access, scanner

MA-NATIVE-12. External pages (privacy policy, sub-processor list, status page) open with `openLink`, links into Telegram with `openTelegramLink`, and both only from a tap handler (TGF-21). The application never navigates its own WebView to another origin. [M]

MA-NATIVE-13. A generated file is saved with `downloadFile({url, file_name})`, where `url` is a download ticket on the application's own origin (section 15; the ticket rules are in ARCHITECTURE section 9.7: valid for 15 minutes, bound to one export, usable for at most 5 requests, single use only if MA-SPK-2 shows that every client issues exactly one request) and `file_name` is neutral: `dosegram-export-YYYYMMDD.zip`, `dosegram-report-YYYYMMDD.pdf`. If `downloadFile` reports an error or is unavailable (MA-A-19), the application fetches the file with the session token and offers it through a blob link; if that also fails, it offers "Send to my chat instead" with the warning of S-14. [M][L]

MA-NATIVE-14. When the bootstrap reports that the bot cannot write to the user (blocked, or `allows_write_to_pm` false), the launch banner reads "Dosegram cannot send you reminders" with the action "Allow", which calls `requestWriteAccess` (TGF-26). If the user declines, the banner stays, and S-15 repeats the state under Notifications. [M]

MA-NATIVE-15. `showScanQrPopup`, `LocationManager`, the motion sensors, `requestContact`, `setEmojiStatus`, `shareToStory` and `openInvoice` are not used. `shareMessage` is used only by S-16 if caregiver sharing is adopted. [O]

## 7. Screen specifications

Conventions for this section:

- Wireframes show a phone viewport about 360 px wide, in a left-to-right locale, without the native Telegram header. `[ Label ]` is a button, `( )` a radio choice, `[x]` a checkbox or toggle, `>` a row that navigates, `v` a control that expands.
- Sample medication names in wireframes are placeholders ("Medication A"). Product-authored text never names a real medicine.
- "Resource" names refer to section 15.
- Each screen defines its loading, empty and error states by reference to section 8 and lists only what is specific to it.
- A domain screen whose consent is missing shows the "consent needed" state of section 8.2 instead of its content.

### 7.1 S-01 Today

Purpose: show what is due and what is done for one local day, and let the user answer or correct it. It mirrors the "today" message in chat and adds detail.

Resource: `today` (one aggregated read for the day), `occurrences/{id}/actions`, `intakes`, `hydration/entries`.

```
+------------------------------------------+
| Today                        [<] [>] [*] |
| Thursday 12 November                     |
+------------------------------------------+
| 00   06    |   12        18          24  |
| ==sleep==..o..|..x...........o........   |  day line
|            now                           |
+------------------------------------------+
| Due now                                  |
| +--------------------------------------+ |
| | (.) Medication A                     | |
| |     1 tablet - 08:00 - due 12 min    | |
| | [ Taken ]  [ Skip ]  [ Snooze v ]    | |
| +--------------------------------------+ |
| Later today                              |
|  o  13:00  Medication B   2 ml         > |
|  o  21:00  Medication A   1 tablet     > |
| Earlier today (2)                      v |
|  v  07:58  Medication C   Taken        > |
|  -  08:00  Medication D   Skipped      > |
| [ + Log an as-needed dose ]              |
+------------------------------------------+
| Water              1.2 of 2.0 L   (60%)  |
| [ +150 ml ] [ +250 ml ] [ +500 ml ]    > |
+------------------------------------------+
| Mood     Not logged today  [ Check in ]  |
| Sleep    7 h 10 min, your check-ins    > |
| Food     2 meals logged       [ Add ]    |
+------------------------------------------+
|  Today  |  Meds  |  Log  |  Insights     |
+------------------------------------------+
```

`[*]` is the settings control. `[<]` and `[>]` move one day; a "Today" chip appears when another day is shown.

Behaviour:

1. Occurrences are grouped as Due now (state due, and snoozed occurrences whose snooze time has passed), Later today (pending, snoozed with a future time), and Earlier today (taken, skipped, missed, unknown). Earlier today is collapsed when it has more than three rows.
2. "Taken" records the occurrence as taken with the effective time "now". "Skip" records it as skipped. "Snooze" opens a three-item choice whose durations come from the schedule's reminder policy (default 10, 30 and 60 minutes, R03 section 4.2).
3. Tapping a row or the body of a card opens the occurrence sheet: scheduled time, current state, the action history as a list of time and action, and the corrections that ADR-012 allows: "Taken at ..." with a time field, "Change to skipped", "Change to taken", "Undo". The sheet also holds the optional note field when notes are in scope.
4. "Log an as-needed dose" opens a sheet: medication (only active medications), amount (default: the medication's dose), time (default now), optional note. Under the medication the sheet shows "Last logged: 3 h 20 min ago" as a plain record. It shows no limit, no interval warning and no advice (non-goal N2).
5. The water row adds an entry with one tap per preset and shows the new total at once. The chevron opens S-07.
6. Rows for mood, sleep and food appear only for enabled domains. If no optional domain is enabled, one row "Track water, mood, sleep or food" leads to S-15 "Privacy and consent".

Requirements:

MA-TODAY-1. Today renders from one `today` response. The response lists every occurrence of the local day in the account time zone with its state, the trackers' day totals and the day-line marks. [M]

MA-TODAY-2. Taken, Skip and Snooze each complete with one tap and one request. The card changes state within 100 ms of the tap (optimistic update, section 8.5), and a toast "Marked taken at 08:12" with "Undo" stays for 8 seconds. [M]

MA-TODAY-3. If the occurrence was already resolved elsewhere (for example in chat a moment earlier), the API answers with the current state and the code `already_resolved`. The card then shows that state and the toast says "Already marked taken at 08:04 in chat". No error is shown and no second action is recorded. [M]

MA-TODAY-4. After a successful action from the Mini App, the reminder message in chat shows the same final state (MA-ROLE-3). [L]

MA-TODAY-5. Today shows no adherence percentage, no streak, no score and no reward for any medication action (non-goals N7 and N11). [O, design review]

MA-TODAY-6. A missed or unknown occurrence can be corrected from its sheet within the correction window that PRODUCT_SPEC.md defines. Next to the correction controls the sheet shows the standing sentence of copy rule 7 and nothing else about what to do. [M]

MA-TODAY-7. The day line is drawn from the same response, has at most 120 SVG nodes, is hidden from assistive technology, and conveys nothing that the lists below it do not also convey as text. [M]

MA-TODAY-8. While Today is visible and the rules of MA-SESSION-2 hold, it refreshes through the `changes` resource every 30 seconds and at once on `activated`. A change made in chat appears without user action within 35 seconds. [M]

MA-TODAY-9. Viewing another date uses the same screen with `today?date=`. Future dates show planned occurrences read-only, without action buttons. [M]

States: skeleton with three card placeholders and the water row; empty state "Nothing scheduled today" with the actions "Add a medication" and "Log an as-needed dose"; a day with only trackers shows the trackers without the medication groups.

### 7.2 S-02 Medication list

Purpose: the registry. Find a medication, see its schedule in one line, add a new one.

Resource: `medications`.

```
+------------------------------------------+
| Medications                    [ + Add ] |
+------------------------------------------+
| [ Search by name                      ]  |
| ( Active 4 ) ( Paused 1 ) ( Archived 2 ) |
+------------------------------------------+
| (o) Medication A                       > |
|     500 mg tablet - 1 tablet             |
|     Daily 08:00, 21:00 - next 21:00      |
|------------------------------------------|
| (=) Medication B                       > |
|     5 mg/ml liquid - 2 ml                |
|     Mon, Wed, Fri 13:00 - next Fri 13:00 |
|     12 doses left                        |
|------------------------------------------|
| (o) Medication C                       > |
|     As needed - last logged yesterday    |
+------------------------------------------+
|  Today  |  Meds  |  Log  |  Insights     |
+------------------------------------------+
```

`(o)` and `(=)` stand for the colour and shape tag.

Behaviour:

1. The list is loaded whole (at most 200 medications, R06 section 12.3, PROPOSED) and filtered in memory. Search matches the name, case-insensitively and ignoring leading and trailing spaces, as the existing `name_norm` rule does (R01 section 7). There is no server-side search, because names are encrypted at rest (R06 section 8.2).
2. Each row shows: tag, name, strength and form, dose per intake, a one-line schedule summary produced by the server, the next occurrence, and the remaining quantity when inventory is tracked and at or below the user's threshold.
3. A medication with several schedules shows "2 schedules" and the earliest next occurrence.
4. Order: medications with a due occurrence first, then by next occurrence, then as-needed medications by name.

Requirements:

MA-REG-1. Typing in the search field filters the list within 100 ms for 200 rows on the reference device, and announces the result count to assistive technology ("3 medications"). [M]

MA-REG-2. The schedule summary line is text from the API in the account locale. The client does not compose schedule sentences. [M]

MA-REG-3. The low-stock line states a quantity or a number of days ("12 doses left", "About 6 days left") and uses `--dg-text`, not a warning colour. [M]

MA-REG-4. The segmented filter shows counts. Archived medications are read-only and keep their history (R03 section 2.2). [M]

States: skeleton of four rows; empty state "No medications yet. Add the first one to get reminders." with the action "Add medication"; empty search result "No medication matches" with the action "Clear search".

### 7.3 S-03 Medication detail

Purpose: read one medication, reach its editor, its schedules and its inventory, and pause or archive it.

Resources: `medications/{id}`, `medications/{id}/schedules`, `medications/{id}/inventory`.

```
+------------------------------------------+
| (o) Medication A               [ Edit ]  |
|     500 mg tablet                        |
+------------------------------------------+
| Dose           1 tablet                  |
| Notes          With breakfast            |
| In reminders   Name shown                |
+------------------------------------------+
| Schedules                                |
|  Daily 08:00, 21:00                    > |
|  Next: today 21:00 - Europe/Kyiv         |
|  [ + Add schedule ]                      |
+------------------------------------------+
| Supply                                   |
|  18 tablets left - about 9 days          |
|  Remind me at 7 days                     |
|  [ Add refill ]            [ Adjust ]    |
+------------------------------------------+
| History                                  |
|  Last 30 days: 54 taken, 3 skipped,      |
|  1 not answered, 2 no record           > |
+------------------------------------------+
| [ Pause reminders ]                      |
| [ Archive medication ]                   |
+------------------------------------------+
```

Behaviour:

1. "Edit" opens S-04. A schedule row opens S-05. "Add schedule" opens S-05 for a new schedule.
2. "Pause reminders" stops future reminders for all schedules of this medication until "Resume". Pending occurrences are cancelled as ADR-012 defines. The button becomes "Resume reminders".
3. "Archive medication" asks for native confirmation, ends all schedules, and moves the medication to Archived. History is kept. An archived medication offers "Restore".
4. The history block shows counts for the last 30 days by state and links to S-12 filtered to this medication.
5. The supply block is present only when inventory tracking is on for the medication.

Requirements:

MA-REG-5. Pause, resume, archive and restore are single requests with optimistic update and rollback on failure (section 8.5). Each writes the same state the equivalent chat command writes. [M]

MA-REG-6. The history block shows counts, never a percentage with a judgement word, and names all five categories of the adherence model: taken on time, taken late, skipped, not answered, no record (`docs/MEDICATION_REMINDER_UX.md`, "Adherence categories"). [M]

MA-REG-7. "About 9 days" is computed by the server from the schedules and the remaining quantity and is labelled as an estimate. It is not shown for as-needed medications. [M]

### 7.4 S-04 Medication editor

Purpose: create or change a medication in one form with validation and review.

Resources: `medication-options` (enumerations and limits), `medications`, `medications/{id}`, `drafts/medication`.

```
+------------------------------------------+
| New medication                           |
+------------------------------------------+
| Name *                                   |
| [ Medication A                        ]  |
|                                          |
| Form                                     |
| [ Tablet                            v ]  |
|                                          |
| Strength (optional)                      |
| [ 500      ] [ mg                   v ]  |
|                                          |
| Dose each time *                         |
| [ - ] [ 1        ] [ + ]  tablet         |
+------------------------------------------+
| How it looks (optional)                  |
| Colour  (none)(white)(yellow)(pink)...   |
| Shape   (round)(oval)(capsule)(oblong)   |
+------------------------------------------+
| Notes (optional)                         |
| [ With breakfast                      ]  |
|                              14 of 500   |
+------------------------------------------+
| [x] Show the name in reminders           |
|     Off: reminders say "your 08:00 item" |
+------------------------------------------+
| Supply (optional)                      v |
| [x] Track how much is left               |
| Amount left    [ 18       ] tablets      |
| Remind me when ( ) days left [ 7  ]      |
|                ( ) amount left [    ]    |
+------------------------------------------+
| These are your own records. Dosegram     |
| does not check doses or interactions.    |
+------------------------------------------+
|            [ Save medication ]           |  MainButton
+------------------------------------------+
```

Fields:

| Field | Control | Required | Rules | Error code and message (English) |
|---|---|---|---|---|
| Name | Text, `autocapitalize="words"`, `autocomplete="off"`, `spellcheck="false"` | Yes | 1 to 80 characters after trimming (PROPOSED); no control characters; unique among the account's non-archived medications, compared case-insensitively | `name_required` "Enter a name"; `name_too_long` "Use at most 80 characters"; `name_taken` "You already have a medication with this name. Open it or choose another name." |
| Form | Select | No, default "Other" | One of the server's list. PROPOSED list: tablet, capsule, liquid, drops, injection, inhaler, spray, cream or ointment, patch, powder or sachet, suppository, other | none |
| Strength value | `NumberField` | No | Greater than 0; at most 6 integer digits and 3 decimals | `strength_invalid` "Enter a number greater than 0, for example 500 or 2.5" |
| Strength unit | Select | Only when a strength value is entered | PROPOSED list: mg, mcg, g, ml, IU, %, mg/ml, mcg/dose, other | `strength_unit_required` "Choose a unit for the strength" |
| Dose each time | `NumberField` with stepper | Yes for scheduled use | Greater than 0; at most 4 integer digits and 3 decimals; step 0.25 on the stepper, any valid decimal by typing | `dose_required` "Enter how much you take each time"; `dose_invalid` "Enter a number greater than 0" |
| Dose unit | Select, preset from the form | Yes | PROPOSED list: tablet, capsule, ml, drop, puff, spray, unit, application, patch, sachet, piece | none |
| Colour tag | `ChipGroup`, single choice, each chip shows a swatch and its colour name | No | One of: none, white, yellow, orange, pink, red, brown, green, blue, purple, grey (PROPOSED) | none |
| Shape tag | `ChipGroup`, single choice, each chip shows an outline and its name | No | One of: none, round, oval, capsule, oblong, other (PROPOSED). For non-solid forms the form's icon is used and this field is hidden. | none |
| Notes | Textarea with a character counter | No | At most 500 characters (R06 section 12.3) | `notes_too_long` "Use at most 500 characters" |
| Show the name in reminders | `Toggle` | n/a | Default follows the account's discreet-mode choice | none |
| Track how much is left | `Toggle` | n/a | Off by default | none |
| Amount left | `NumberField` | When tracking is on | 0 or more; same precision as dose; unit equals the dose unit | `supply_invalid` "Enter 0 or more" |
| Low-supply reminder | Radio: by days left, by amount left, off | n/a | Days 1 to 60; amount greater than 0. Default 7 days when the user turns the reminder on (PRODUCT_SPEC R-REG-11); the user can change it. | `threshold_invalid` "Enter a number from 1 to 60" |

Behaviour:

1. Validation timing: nothing is shown while a field is untouched or being typed in for the first time. A field is validated when it loses focus. After a field has shown an error it is validated on every change, and the error clears as soon as the value is valid.
2. Decimal fields accept a comma or a dot as the separator and store a canonical decimal. A value such as "1,5" is valid in every locale.
3. On save, client validation runs for all fields. If any fails, focus moves to the first invalid field, the page scrolls it into view, and an error summary at the top lists each error as a link to its field.
4. The server validates again and returns the same error codes per field (section 15.1). Server errors are shown in the same places as client errors.
5. A dirty form is saved as a server-side draft (section 8.6) every 10 seconds and on `deactivated`. Opening the editor with an existing draft offers "Continue your draft from 14:02" or "Start again".
6. After a successful save of a new medication the application opens S-05 for it with the heading "When do you take it?". The user can leave that step; the medication then exists without a schedule and is listed as "No schedule".
7. Changing the dose of a medication that has schedules shows, before save: "The new dose applies to doses from now on. Past records keep the dose they had." (R01 section 7, `dose_snapshot`).

Requirements:

MA-EDIT-1. Every field has a visible label above it. Placeholder text is never the only label. Required fields are marked with an asterisk and the word "required" in the accessible name. [M]

MA-EDIT-2. Each error message is linked to its field with `aria-describedby`, sets `aria-invalid`, and is announced once through a polite live region. [M]

MA-EDIT-3. The name field offers no suggestions from any drug catalogue (R06 section 14.9). `autocomplete` is off so that the WebView's own suggestion store does not keep medication names. [M]

MA-EDIT-4. The colour and shape tags are recognition aids chosen by the user. They are always shown with their names, and the colour swatches are literal pill colours with a 1 px `--dg-separator` outline so that white and yellow stay visible on a light surface. [M]

MA-EDIT-5. `MainButton` is enabled only when the form differs from its initial state. Pressing it with invalid fields runs behaviour 3; it is not silently disabled for invalid input, so that the user learns what is missing. [M]

MA-EDIT-6. The notice under the form is always present on S-04 and S-05 and uses the wording that BOT_UX.md defines for the non-clinical statement. [M]

MA-EDIT-7. Saving sends `If-Match` with the version the form was loaded with. A version conflict follows section 8.5. [M]

MA-EDIT-8. The editor is usable with the keyboard alone on Telegram Desktop: logical tab order, visible focus, `Enter` in a single-line field moves to the next field, and the native bottom button is reachable (MA-A-08). [M][L]

### 7.5 S-05 Schedule builder

Purpose: describe when a medication is taken, see the next seven days before saving, and set how reminders behave.

Resources: `schedule-options`, `schedule-previews`, `medications/{id}/schedules`, `schedules/{id}`, `schedules/{id}/revisions`, `time-zones`, `drafts/schedule`.

```
+------------------------------------------+
| Schedule for Medication A                |
| Every day at 08:00 and 21:00, 1 tablet,  |
| from 12 Nov, no end date.                |  summary sentence (from the server)
+------------------------------------------+
| Pattern                                  |
| (o) Every day                            |
| ( ) Certain days of the week             |
| ( ) Every few days or weeks              |
| ( ) Counted in hours                     |
| ( ) Cycle: days on, days off             |
| ( ) Changing dose over time              |
| ( ) Only when needed                     |
+------------------------------------------+
| Times and dose                           |
|  [ 08:00 ]  [ 1    ] tablet    [ Remove ]|
|  [ 21:00 ]  [ 1    ] tablet    [ Remove ]|
|  [ + Add a time ]  [ Fill every N hours ]|
+------------------------------------------+
| Dates                                    |
|  Starts   [ 2026-11-12 ]                 |
|  Ends     (o) No end date                |
|           ( ) On [ date ]                |
|           ( ) After [ n ] days           |
+------------------------------------------+
| Time zone                                |
| (o) Same clock time wherever I am        |
|     Uses your Dosegram time zone,        |
|     now Europe/Kyiv.                     |
| ( ) Fixed to one place [ Europe/Kyiv v ] |
+------------------------------------------+
| Reminders                                |
|  Remind again if not answered [ 30 min v]|
|  Snooze choices          10, 30, 60 min  |
|  Count as not answered after  [ 4 h   v ]|
|  [ ] Quiet reminder (no sound)           |
+------------------------------------------+
| Next 7 days                              |
|  Thu 12  08:00  21:00                    |
|  Fri 13  08:00  21:00                    |
|  ...                                     |
|  Sun 29 Mar  03:30 moved to 04:00        |
|              (clock change)              |
+------------------------------------------+
| Enter the plan you were given. Dosegram  |
| does not suggest doses or times.         |
+------------------------------------------+
|             [ Save schedule ]            |  MainButton
+------------------------------------------+
```

Patterns and their fields:

| Pattern | Maps to (R01, `docs/MEDICATION_REMINDER_UX.md`) | Fields | Limits (PROPOSED; the server's `schedule-options` is authoritative) |
|---|---|---|---|
| Every day | `fixed_times` | List of time and dose rows. Helper "Fill every N hours" asks for a first time, an interval and a last time and inserts the rows. | 1 to 12 rows; times unique; N from 1 to 12 hours |
| Certain days of the week | `weekly` | Weekday chips; list of time and dose rows; optional "Different times on some days", which gives each selected day its own rows | At least one weekday; 1 to 12 rows per day |
| Every few days or weeks | every N days or weeks | N; unit (days, weeks); first day; list of time and dose rows | N from 2 to 365 days or 1 to 52 weeks |
| Counted in hours | `interval_after_first_dose`, `interval_after_each_dose` | Interval in hours; counted from "the first dose I log each day" (with the time of the first reminder) or from "each dose I log"; dose | Interval 1 to 72 hours in steps of 0.5 |
| Cycle | `cycle` | Days on; days off; first day of the first cycle; list of time and dose rows for "on" days | Each from 1 to 365 days |
| Changing dose over time | taper | Ordered phases. Each phase: length in days (or "until I change it" for the last phase), and a list of time and dose rows. Phases follow one another without gaps from the start date. | 2 to 12 phases; each 1 to 365 days |
| Only when needed | `as_needed` | No times. The Reminders and Next 7 days sections are hidden and replaced by "No reminders. Log a dose from Today or in chat." | n/a |

Validation:

| Rule | Error code and message |
|---|---|
| At least one time row for patterns that have rows | `times_required` "Add at least one time" |
| Times within one day are unique | `time_duplicate` "This time is already in the list" |
| A time field holds a valid clock time | `time_invalid` "Enter a time such as 08:00" |
| Dose in each row is greater than 0 | `dose_invalid` "Enter a number greater than 0" |
| At least one weekday is selected | `weekday_required` "Choose at least one day" |
| The end date is not before the start date | `end_before_start` "The end date is before the start date" |
| The schedule produces at least one occurrence | `no_occurrences` "This schedule has no doses. Check the dates and days." |
| Phases are contiguous and each has at least one row | `phase_incomplete` "Phase 2 has no times yet" |
| The medication's schedule quota is not exceeded | `quota_schedules` "You have reached the limit of schedules" |

Behaviour:

1. The page is one scrolling form with sections that appear as the pattern requires them. It is not a multi-step wizard. Rationale: the fields depend on one another, and the preview at the end must stay one scroll away from any field. Rejected alternative: one step per section with Next buttons, which hides the preview until the last step.
2. The summary sentence under the title and the Next 7 days list come from `schedule-previews`. The application requests a preview 400 ms after the last change to a valid draft. While a preview is loading the previous one stays visible at reduced opacity with `aria-busy`.
3. The preview lists every occurrence in the next seven local days, starting from the later of today and the start date, grouped by day. If more than 100 occurrences fall into the window, the list is truncated with the line "and 12 more".
4. An occurrence affected by a clock change carries a note generated from the server's `dst_kind` value. The client shows the server's sentence and does not compute or restate the daylight-saving rule itself; ADR-011 defines the rule.
5. Time zone. "Same clock time wherever I am" stores the schedule as following the account time zone (the existing `tz_follows_user` flag, R01 section 7). "Fixed to one place" stores an IANA zone chosen from `time-zones`; the list is searchable by city and shows each zone's current offset. Under the choice the preview repeats the zone used.
6. Reminder options are rendered from `schedule-options`: the set of follow-up delays (PROPOSED default 30 minutes, R03 section 4.2), the snooze choices, the "not answered after" delays, and whether a quiet reminder is offered. The client hard-codes none of these lists.
7. When the account has quiet hours and a reminder of this schedule falls inside them, the preview marks that occurrence "inside your quiet hours" and states what will happen, in the server's words. Medication reminders are governed by the user's own settings and are exempt from the daily nudge budget (PRODUCT_SPEC.md section 3.2).
8. Editing an existing schedule creates a new revision. Above `MainButton` the page states "Changes apply from the next dose, 21:00 today. Past records stay as they are." The time in that sentence comes from the preview response.
9. An existing schedule has a danger button "End schedule" (native confirmation). It ends the schedule today and cancels its pending occurrences.

Requirements:

MA-SCHED-1. `MainButton` "Save schedule" is enabled only when a preview for exactly the current draft has been received without a blocking warning. A draft changed after the preview disables the button until the new preview arrives. [M]

MA-SCHED-2. The Next 7 days list shows local date, weekday, local time and dose for each occurrence and names the time zone in its heading. For a draft in the pattern "Every day at 08:00 and 21:00" it shows exactly 14 rows across seven days. [M]

MA-SCHED-3. For a fixture whose seven-day window contains a daylight-saving transition, the preview shows the server's clock-change note on the affected occurrences, in both the spring and the autumn fixture. [M][G]

MA-SCHED-4. Every time is entered through `TimeField` (MA-A-05). On a client without a native time picker the typed fallback accepts `H:MM`, `HH:MM` and, in 12-hour locales, `h:mm am/pm`, and normalises to `HH:MM`. [U][L]

MA-SCHED-5. The pattern "Only when needed" produces no reminders and no preview, and the saved schedule appears in S-01's as-needed sheet. [M]

MA-SCHED-6. The pattern "Changing dose over time" shows, above its phases, "Enter the plan you were given. Dosegram does not suggest steps." The client offers no function that generates phases. [M][O]

MA-SCHED-7. Saving a revision sends the revision number the form was loaded with. If the schedule changed meanwhile, the save is refused and section 8.5 applies; schedules are never merged automatically. [M]

MA-SCHED-8. The patterns offered are exactly those that `schedule-options` lists as enabled. A pattern that the backend does not yet support is not shown, so the builder can ship before every pattern exists. [M]

MA-SCHED-9. Changing the account time zone in S-15 shows which schedules follow it and what their next occurrences become, before the change is saved (section 7.15). [M]

MA-SCHED-10. No field, helper or message in the builder proposes a time, a dose, an interval or a step that the user did not enter. "Fill every N hours" only expands numbers the user typed. [O, design review]

### 7.6 S-06 Log hub

Purpose: one place to reach the tracking domains, with today's state of each.

Resource: `today` (the same aggregated read as S-01).

```
+------------------------------------------+
| Log                                      |
+------------------------------------------+
| Water    1.2 of 2.0 L today            > |
| Mood     Not logged today              > |
| Sleep    7 h 10 min last night         > |
| Food     2 meals today                 > |
+------------------------------------------+
| Not turned on                            |
| [ Choose what to track ]                 |
+------------------------------------------+
|  Today  |  Meds  |  Log  |  Insights     |
+------------------------------------------+
```

MA-LOG-1. The hub lists enabled domains first, each with one line of today's state, and offers "Choose what to track", which opens S-15 "Privacy and consent". A domain that is not enabled is not shown as an empty tracker. [M]

MA-LOG-2. With no optional domain enabled, the hub shows the empty state "Track water, mood, sleep or food. Each one is turned on separately, with your consent." and the same action. [M]

### 7.7 S-07 Hydration

Purpose: add water in one tap, see the day against the user's own goal, correct entries.

Resources: `hydration/entries`, `hydration/settings`, `hydration/summary`.

```
+------------------------------------------+
| Water                              [...] |
+------------------------------------------+
|               .-------.                  |
|             /    60%    \                |
|            |  1.2 of 2.0 |               |
|             \     L     /                |
|               '-------'                  |
| [ +150 ml ]  [ +250 ml ]  [ +500 ml ]    |
| [ Other amount ]                         |
+------------------------------------------+
| Today                                    |
|  08:10   250 ml                 [Remove] |
|  10:45   500 ml                 [Remove] |
|  13:20   250 ml                 [Remove] |
+------------------------------------------+
| Last 7 days                              |
|  |    _         _                        |
|  | _ | | _   _ | |  _    - - goal        |
|  || || || | | || || |                    |
|   F  S  S  M  T  W  T                    |
|  Goal reached on 5 of 7 days             |
+------------------------------------------+
| Goal, amounts and reminders            > |
+------------------------------------------+
```

Behaviour:

1. A preset button adds one entry with the current time. The vessel, the total and the list update at once. A toast offers "Undo" for 8 seconds.
2. "Other amount" opens a sheet with a `NumberField` and a time field (default now).
3. "Remove" deletes an entry after the toast's undo period; there is no confirmation dialog for a single small entry.
4. "Goal, amounts and reminders" opens a settings page: daily goal, unit (millilitres or fluid ounces), the preset amounts (up to four), and the drink nudge plan of PRODUCT_SPEC R-HYD-10: a forced choice of off, every 2 hours, every 3 hours or a custom interval between two times, inside the daily budget, and "Stop for the day when the goal is reached" (on by default, R03 section 4.2). The page states that drink reminders count against the daily budget for optional prompts and that medication reminders do not.
5. The goal is typed by the user. The page says "This is your own goal. Dosegram does not recommend an amount." There is no calculation from body weight or activity (R03 section 3.2 names such a figure "only an estimate"; the product gives none).

Requirements:

MA-HYD-1. Adding a preset amount takes one tap and one request with an idempotency key. Two rapid taps on the same button add two entries; a retried request after a network error adds one. [M]

MA-HYD-2. The vessel's accessible name states the value, the goal and the percent, for example "Water: 1.2 of 2.0 litres, 60 percent". At or above the goal the vessel is full and the text reads "2.3 of 2.0 litres". Nothing is drawn beyond a full ring. [M]

MA-HYD-3. Limits are those of PRODUCT_SPEC R-HYD-1 to R-HYD-3 and come from the bootstrap document: one entry 1 to 5000 ml; goal 100 to 10,000 ml; up to four presets, defaults 200, 300 and 500 ml (the sketches that show 150 and 250 ml illustrate edited presets). Fluid ounces are converted for display only; the stored unit is millilitres. [U][M]

MA-HYD-4. The seven-day chart is a bar chart from zero with a dashed goal line, a text summary "Goal reached on 5 of 7 days", and a "Show as table" control. A day without entries is a gap, not a zero-height bar with a judgement. [M]

MA-HYD-5. Reaching the goal produces one success haptic and the full vessel. It produces no animation loop, no badge and no message about failing on other days. [M]

States: skeleton of the vessel and three list rows; empty state for the list "Nothing logged today" under the vessel at 0 percent; without a goal the vessel is replaced by the total and the action "Set a goal".

### 7.8 S-08 Food diary

Purpose: a meal journal by day. It records what the user ate and when. It is not a calorie counter (PRODUCT_SPEC.md section 4).

Resources: `meals`, `meals/{id}`, `meals/{id}/photo`, `meals/recent`, `nutrition/summary`.

```
+------------------------------------------+
| Food                   [<] Thu 12 Nov [>]|
+------------------------------------------+
| Breakfast                                |
|  08:15  Porridge, coffee      [photo]  > |
| Lunch                                    |
|  Not logged               [ Add lunch ]  |
| Dinner                                   |
|  Not logged              [ Add dinner ]  |
| Snacks                                   |
|  16:40  Apple                          > |
+------------------------------------------+
| [ + Add a meal ]                         |
+------------------------------------------+
| This week                                |
|  Breakfast 6  Lunch 5  Dinner 7 of 7 days|
+------------------------------------------+
```

Meal entry sheet:

```
+------------------------------------------+
| Add a meal                               |
| Meal   (Breakfast)(Lunch)(Dinner)(Snack) |
| Time   [ 13:05 ]                         |
| What did you have? (optional)            |
| [                                     ]  |
| Recent: (Soup, bread) (Salad) (Pasta)    |
| Photo  [ Add photo ]                     |
+------------------------------------------+
|               [ Save meal ]              |  MainButton
+------------------------------------------+
```

Behaviour:

1. A meal needs only its type and time. Description and photo are optional. A meal can therefore be logged in two taps: "Add lunch", then "Save meal".
2. "Recent" shows up to five distinct descriptions the user saved before for that meal type. Tapping one fills the description.
3. A photo that the user sent to the bot in chat appears on its entry. Adding a photo from the Mini App is offered only when the feature flag is on (MA-A-06; R06 open question Q12 puts photos after text).
4. Photos are loaded through the API with the session token and shown from a blob URL. The Telegram file URL never reaches the client (R02 section 17).
5. Optional numbers (energy, typed by the user) are a later wave and appear only when the bootstrap enables them. No value is estimated by a model (non-goal N9).

Requirements:

MA-FOOD-1. The diary shows no calorie budget, no remaining allowance, no colour that rates a meal or a day, and no message about eating less or more (R03 section 6.2 item 9). [O, design review]

MA-FOOD-2. Thumbnails are requested only for entries scrolled into view, at most three at a time, and each blob URL is revoked when its entry leaves the page. Each photo has alternative text: the description, or "Meal photo, 08:15" when there is none. [M]

MA-FOOD-3. Description is at most 500 characters. Photo upload from the Mini App, when enabled, accepts JPEG, PNG and WebP up to 5 MB (R06 section 12.3) and shows upload progress and a retry. [M]

MA-FOOD-4. The weekly line reports counts of days on which each main meal was logged. It uses neutral wording and no percentages. [M]

States: skeleton of four meal groups; a day without entries shows the four groups with their "Add" actions, which is also the empty state.

### 7.9 S-09 Mood and the mood blob

Purpose: record how the user feels in one movement, see recent check-ins.

Resources: `mood/scale`, `mood/blob-states`, `mood/checkins`, `mood/tags`, `mood/summary`.

History view (route `mood`):

```
+------------------------------------------+
| Mood                        [ Check in ] |
+------------------------------------------+
| Today 14:05                              |
|   (blob)  Slightly pleasant, calm      > |
+------------------------------------------+
| Last 7 days                              |
|   o    o    .    o    o    o    o        |
|   F    S    S    M    T    W    T        |
+------------------------------------------+
| This month                             > |
+------------------------------------------+
```

Check-in (route `moodnew`):

```
+------------------------------------------+
| How are you right now?                   |
|                                          |
|               .-~~~-.                    |
|             (   o o   )                  |
|             (    u    )                  |
|               '-...-'                    |
|          Slightly pleasant               |
|                                          |
| Unpleasant  o--o--o--o--O--o--o Pleasant |
|               [ - ]       [ + ]          |
+------------------------------------------+
| Energy (optional)                        |
| Low   ( ) ( ) (o) ( ) ( )   High         |
+------------------------------------------+
| What is it about? (optional)           v |
|  (work) (family) (health) (sleep) (+)    |
| Note (optional)                          |
| [                                     ]  |
+------------------------------------------+
| Dosegram records how you feel. It does   |
| not assess it. Help and contacts       > |
+------------------------------------------+
|             [ Save check-in ]            |  MainButton
+------------------------------------------+
```

The scale is data, not code. `mood/scale` returns the number of valence stops and their labels, the number of energy stops and their labels, the default tag catalogue and the revision of the blob states. The stored scale is 5 valence steps and 5 energy steps (PRODUCT_SPEC R-MOO-1 and R-MOO-2; ROADMAP conflict X-01), so the slider has five stops with the labels of the catalogue keys `mood.valence.1` to `mood.valence.5`; the blob geometry of R04 section 9.2 keeps 7 valence levels and draws the five steps in columns 1, 2, 4, 6 and 7, and the two intermediate levels appear only for averages. PRODUCT_SPEC.md owns the stored scale and the mapping; this screen renders whatever `mood/scale` describes.

The blob. `mood/blob-states` is a global, versioned asset: for each state it gives the outline as a closed path of a fixed number of cubic segments, the gradient stops for the light and the dark scheme, and the face parameters (eye openness, mouth curve, brows). It contains no user data, so it may be cached for the session. The client draws the state as inline SVG built with DOM calls. While the slider moves, the client interpolates the control points and colours between neighbouring states, so the blob changes shape continuously. The geometry is the server's; the client holds no copy of the generating formula (R04 section 9.3).

Rejected alternative: porting the blob algorithm to TypeScript. Two implementations of one shape would drift, and the image in chat and the blob in the app would stop matching.

Requirements:

MA-MOOD-1. The check-in needs one input. The slider starts in the middle with the blob in a neutral outline state and the label "Move the slider". `MainButton` becomes enabled only after the user has set the valence by the slider, the stepper buttons or the keyboard. An untouched default is never saved. [M]

MA-MOOD-2. The valence control is a native range input with one stop per valence level, `aria-valuetext` equal to the stop's label, and two 48 px stepper buttons as an alternative to dragging. Arrow keys move one stop. [M]

MA-MOOD-3. The blob has `role="img"` and an accessible name that states the current labels ("Slightly pleasant, medium energy"). The same words are printed under it. Colour and shape are never the only carrier of the value. [M]

MA-MOOD-4. Under reduced motion (MA-DS-5) the blob switches between states without interpolation and does not breathe. Otherwise breathing is a `transform: scale` between 0.98 and 1.02 over 4 seconds, and interpolation runs inside one animation frame per slider event. On the reference device a slider drag keeps frame time under 32 ms. [M]

MA-MOOD-5. All `mood/blob-states` outlines have the same number of segments in the same order, so that linear interpolation between any two neighbouring states yields a closed, non-self-intersecting outline. A golden test renders the midpoint of every neighbouring pair. This is a requirement on the asset that VISUALS_AND_ANALYTICS.md specifies. [G]

MA-MOOD-6. Energy, tags and the note are optional and are never required for any report or view. Tags are chips from the user's one editable list, seeded with the default list of PRODUCT_SPEC R-MOO-3 (at most 30 tags of at most 24 characters each, PROPOSED); at most 5 tags per check-in. The note is at most 500 characters. [M]

MA-MOOD-7. The line "Dosegram records how you feel. It does not assess it." and the link "Help and contacts" are shown on every mood screen for every value. The link opens a static page with the non-advice statement and the general instruction to contact a professional or emergency services (R06 section 14.9). The application never changes its behaviour, wording or prompts because of a low value, and never shows a questionnaire or a score (non-goal N4). [M][O]

MA-MOOD-8. Several check-ins per day are allowed. The history view shows the latest of the day large and the others as a list. The seven-day strip shows one small blob per day for the daily mean of that day's valences (PRODUCT_SPEC R-MOO-5), on the same 1 to 5 scale as every other surface, and a dot for a day without a check-in. The "latest" display in the history view is labelled as the latest check-in. [M]

MA-MOOD-9. After saving, the application shows the blob with the saved labels and the actions "Done" and "Add another". It does not send an image to the chat on its own. [M]

States: skeleton of the blob circle and the slider; history empty state "No check-ins yet. The first one takes a few seconds." with the action "Check in".

### 7.10 S-10 Sleep

Purpose: record nights and naps, confirm or correct an estimate, and see duration and regularity. The screen states at every value how the value was obtained.

Constraint stated plainly: Dosegram cannot read the user's Telegram "last seen" or online status, in the bot or in the Mini App (TGF-06; R05 section 3). The screen works from the three sources that exist: the user's check-ins and edits, the opt-in estimate from the user's own activity in Dosegram, and, later, imported device data.

Resources: `sleep/episodes`, `sleep/checkins`, `sleep/estimates/{sleepDay}`, `sleep/settings`, `sleep/summary`, `sleep/activity-signals`.

```
+------------------------------------------+
| Sleep                              [...] |
+------------------------------------------+
| Last night, Thu 12 Nov                   |
|  23:40 to 06:50        7 h 10 min        |
|  From your check-ins   Quality 4 of 5    |
|  [ Edit ]                                |
+------------------------------------------+
|  (shown instead when only an estimate    |
|   exists)                                |
|  Quiet from 00:40 to 07:10               |
|  At most 6 h 30 min. Estimated from      |
|  your activity in Dosegram.              |
|  [ Yes ] [ Adjust ] [ Not sleep ]        |
|  Stop asking                             |
+------------------------------------------+
| [ Going to sleep ]                       |
| [ + Add a night or a nap ]               |
+------------------------------------------+
| Last 7 nights                            |
|      18   21   00   03   06   09   12    |
|  Fri       |#############|      7 h 05   |
|  Sat          |###########|     6 h 20   |
|  Sun       |/////////////////|  at most  |
|  Mon        |############|      7 h 00   |
|  ...                                     |
|  # your check-ins   / estimate           |
+------------------------------------------+
| Averages, last 7 nights                  |
|  Duration        6 h 55 min              |
|  Your goal       7 h 30 min              |
|  Bedtime varies by about 35 min          |
|  Wake time varies by about 20 min        |
|  Regularity index   needs 5 of 7 nights  |
+------------------------------------------+
| Sleep settings                         > |
+------------------------------------------+
```

Night entry sheet (also used by "Adjust" and "Edit"):

```
+------------------------------------------+
| Night of Wed 11 to Thu 12 Nov            |
| Type          (Night) (Nap)              |
| Fell asleep   [ 2026-11-11 ] [ 23:40 ]   |
| Woke up       [ 2026-11-12 ] [ 06:50 ]   |
| Duration      7 h 10 min                 |
| How was it?   (1) (2) (3) (4) (5)        |
|               optional                   |
+------------------------------------------+
|               [ Save night ]             |  MainButton
+------------------------------------------+
```

Behaviour:

1. The primary button is "Going to sleep" when no open check-in exists and "I'm up" when one does. Each is one tap and one request.
2. The estimate card appears only when the user has opted in to activity-based estimates and the backend has an estimate for the sleep day at or above the display threshold of R05 section 7.4. Its four answers map to R05 section 7.5: "Yes" confirms, "Adjust" opens the night sheet filled with the estimate, "Not sleep" discards it, "Stop asking" turns prompts off and says where to turn them on again.
3. The timeline draws one row per sleep day on an axis from 18:00 to 18:00 (R05 section 7.3). Confirmed episodes are solid bars. Estimates are hatched bars with the label "at most". Imported device episodes carry the device name. Naps are thinner bars. An acknowledged night-time reminder inside an episode is a small mark with the text "reminder answered 03:10" in the row's detail (R05 open question Q9).
4. Sleep settings: the user's own duration goal; typical bedtime and wake time (optional); "My schedule is irregular or I work shifts"; work days (weekday chips); "Ask me to confirm estimates"; and the state of activity-based estimates with the action "Turn on in chat" or "Turn off".
5. The opt-in explainer for activity-based estimates, shown before the user is sent to the chat to consent, lists what is used ("the times you tap or message Dosegram, and when this app is open on your screen"), what is not used ("your Telegram last seen, your online status, your other chats"), how long the raw times are kept (the value comes from the API), and that the result is an upper bound that the user confirms.

Requirements:

MA-SLEEP-1. Every sleep value on the screen carries its source in words next to it: "From your check-ins", "Estimated from your activity in Dosegram", or "From your <device name>". [M]

MA-SLEEP-2. An unconfirmed estimate is never shown as a duration or as bed and wake times without the words "at most" or "quiet from ... to ...", is always hatched in charts, and is excluded from every average on the screen. [M][G]

MA-SLEEP-3. The screen shows no sleep score, no stage data, no efficiency and no judgement word for a night, unless the value was imported from a device, in which case it is shown with the device's name and unchanged (R05 section 8). [O, design review]

MA-SLEEP-4. The duration goal is the user's own number. The screen shows no general reference value for sleep duration (R05 open question Q4). [M]

MA-SLEEP-5. Metrics are gated by data, and a gated metric shows what is needed instead of a value: bedtime and wake variation need 5 of the last 7 sleep days; the regularity index needs 5 of 7; the work-day and free-day difference needs at least 3 work days and 2 free days in the window and the user's work-day setting (R05 section 8). For accounts with the irregular-schedule setting, the work-day and free-day difference and the variation lines are not shown. [M]

MA-SLEEP-6. The night sheet validates: wake time after sleep time (`wake_before_sleep` "The wake time is before the sleep time"); duration at most 24 hours (`too_long`); no overlap with another episode (`overlap` "This overlaps a night you already logged. Edit that one instead."). A duration over 14 hours asks for confirmation once. The duration line is the difference of instants, so on a clock-change night it differs from the clock arithmetic, and a note says "Clocks changed this night". [U][M]

MA-SLEEP-7. With activity-based estimates turned on, and only then, the application reports its foreground periods to `sleep/activity-signals`: one signal on `activated`, one on `deactivated`, and while active at most one heartbeat per 5 minutes, sent only if the user produced input in that interval (R05 section 7.9, "Mini App left open on a desktop"). With the option off, a test records zero requests to this resource. [M]

MA-SLEEP-8. A past night can be edited from S-10 or from S-13. An edit never changes an imported device episode; it creates the user's own correction next to it (R05 section 9). [M]

MA-SLEEP-9. On every launch the application compares the device time zone with the account time zone and raises the mismatch banner (section 7.15), because a stale zone shifts every sleep time (R05 section 7.6). [M]

States: skeleton of the last-night block and four timeline rows; empty state "No nights logged yet. Tap Going to sleep tonight, or add last night now." with the action "Add last night".

### 7.11 S-11 Insights and dashboards

Purpose: descriptive statistics of the user's own records over a chosen range, one block per enabled domain, with drill-down.

Resources: `insights/overview`, `insights/series/{metric}`, `insights/correlations`.

```
+------------------------------------------+
| Insights                                 |
| ( 7 days ) ( 30 days ) ( 90 days )       |
| 14 Oct to 12 Nov                         |
+------------------------------------------+
| Medication                             > |
|  112 scheduled doses                     |
|  [##############======--..]              |
|  96 on time, 8 late, 4 skipped,          |
|  3 not answered, 1 no record             |
|  Previous 30 days: 90 on time of 110     |
+------------------------------------------+
| Water                                  > |
|  1.7 L a day on average                  |
|  Goal reached on 19 of 30 days           |
|  ._.-._.--._.-'-._                       |
+------------------------------------------+
| Mood                                   > |
|  41 check-ins on 26 of 30 days           |
|  _.-._.-''-._.-._                        |
+------------------------------------------+
| Sleep                                  > |
|  6 h 55 min a night, 24 nights logged    |
|  Bedtime varies by about 40 min          |
+------------------------------------------+
| Food                                   > |
|  Meals logged on 22 of 30 days           |
+------------------------------------------+
| What goes together                       |
|  After nights of 7 h or more (18 days)   |
|  your average mood was slightly          |
|  pleasant. After shorter nights          |
|  (12 days) it was neutral.               |
|  How this is counted                   > |
+------------------------------------------+
| History calendar                       > |
| Reports and export                     > |
+------------------------------------------+
|  Today  |  Meds  |  Log  |  Insights     |
+------------------------------------------+
```

Block anatomy: the domain name with the period; one headline count or duration in words; one comparison with the user's own previous period of the same length; one small chart; a chevron to the drill-down. The drill-down shows the full chart for the range, a "Show as table" control and the list of days.

Chart choice per metric:

| Metric | Chart | Reason |
|---|---|---|
| Dose outcomes in a range | One horizontal stacked bar with five segments, each labelled with its count | Parts of a whole with five ordered categories; counts are printed, so no legend lookup is needed |
| Dose outcomes by week | Stacked bars, one per week | Comparison across a few periods |
| Water per day | Bars from zero with a dashed goal line | Daily magnitudes against one reference |
| Mood per day | Line of the daily average with dots for days with data; gaps where there is none | A trend with missing days |
| Sleep per night | Timeline (S-10) and a bar of duration per night | Timing and length are different questions |
| Meals per day | Dot rows per meal type | Presence, not magnitude |
| Long-range pattern | Calendar heatmap (S-12) | Daily values over months |

Requirements:

MA-INS-1. The range control offers 7, 30 and 90 days ending today (PROPOSED). The chosen range applies to every block and is kept for the session and, as an interface preference, in `CloudStorage` (section 6.6). [M]

MA-INS-2. Every number is a count, a duration, a percentage of the user's own goal, or a regularity measure defined in VISUALS_AND_ANALYTICS.md. The screen shows no composite score, no rating word and no comparison with other people or with population norms (non-goals N5 and N7). [O, design review]

MA-INS-3. A comparison with the previous period is written in words with both figures ("Previous 30 days: 90 on time of 110"). It uses no green or red, no up or down arrow coloured as good or bad, and no percentage change for medication outcomes. [M]

MA-INS-4. A block whose denominator is zero says "No data in this period" and shows no chart. A block with fewer days than its minimum says what is needed ("3 more nights for the sleep summary"), with the minimum coming from the API (R03 section 6.1 item 5). [M]

MA-INS-5. Every chart has: a title, labelled axes with units where axes exist, a one-sentence text summary generated by the API from the same data, a "Show as table" control that reveals a `DataTable`, a `role="img"` wrapper whose accessible name is the text summary, and at most 400 SVG nodes (the node budget of a scene; VISUALS VA-MA-02 uses the same number). Bar charts start at zero. No chart has two value axes. Series are identified by direct labels or a legend with glyphs, never by colour alone. [M]

MA-INS-6. Tapping a data point or bar shows its exact value in a persistent caption under the chart (not in a hover tooltip), and the same values are reachable by keyboard with the arrow keys. [M]

MA-INS-7. The "What goes together" block is shown only when `insights/correlations` returns at least one card. Each card states the two groups, the number of days in each, and the two descriptive values. The text comes from the API. It contains no causal verb (causes, improves, leads to, because), and a catalogue lint enforces that. "How this is counted" opens the API's explanation and the minimum of paired days (R05 section 8 rule 3: at least 14). [M][O]

MA-INS-8. Blocks below the first two load when they are scrolled within 200 px of the viewport. The first two blocks are requested in one `insights/overview` call. [M]

MA-INS-9. From 720 px viewport width the blocks are laid out in two columns in reading order. [M]

States: skeleton block per enabled domain; empty state for a new account "Insights appear after a few days of entries. Nothing is missing; there is just not enough yet."

### 7.12 S-12 History calendar

Purpose: see months of one domain at a glance and open any day.

Resource: `insights/calendar`.

```
+------------------------------------------+
| History              [<] November 2026 [>]|
| (Meds) (Water) (Mood) (Sleep) (Food)     |
| Medication: All medications            v |
+------------------------------------------+
|  Mo   Tu   We   Th   Fr   Sa   Su        |
|                                 1        |
|                                [#]       |
|   2    3    4    5    6    7    8        |
|  [#]  [#]  [=]  [#]  [#]  [-]  [#]       |
|   9   10   11   12   13   14   15        |
|  [#]  [#]  [#]  [=]  [ ]  [ ]  [ ]       |
|  ...                                     |
+------------------------------------------+
| # all taken  = some taken  - none taken  |
| . nothing scheduled   ? no record        |
| [ ] Show values in cells                 |
+------------------------------------------+
| November so far: 54 of 60 doses taken    |
+------------------------------------------+
| ( Month ) ( Year )                       |
+------------------------------------------+
```

Cell encoding per domain:

| Domain | Cell value | Steps |
|---|---|---|
| Medication | Share of that day's scheduled doses recorded as taken | Nothing scheduled; none; some; all; plus a marker when any occurrence is "no record" |
| Water | Share of the day's goal, capped at 100 percent | No entries; four quartile steps |
| Mood | Last check-in of the day | One step per valence level, using the blob's colour for that level |
| Sleep | Duration of the main sleep | No record; four steps relative to the user's own goal; hatched for an unconfirmed estimate |
| Food | Number of main meals logged | 0, 1, 2, 3 |

Requirements:

MA-HIST-1. Each day cell is a button whose hit area is its whole grid track: 48 CSS px high and one seventh of the content width wide, which is 41 px at a 320 px viewport and 47 px at 360 px. This is the one stated exception to the 48 px target rule of section 5.5; it stays well above the 24 px minimum of WCAG 2.2 criterion 2.5.8. Its accessible name states the date and the value in words, for example "Thursday 12 November, 2 of 2 doses taken". Tapping opens S-13 for that day. [M]

MA-HIST-2. The grid is navigable with the arrow keys, Home and End, and Page Up and Page Down for months. Month change is also available through the two header buttons. No swipe is required. [M]

MA-HIST-3. Steps of a ramp differ in lightness, not only in hue, and are drawn from one domain colour, except for mood, which uses the blob's level colours. "Show values in cells" prints the value in each cell ("2/2", "1.8", the level number, "7:10", "3"), so that the calendar is readable without colour. [M]

MA-HIST-4. "Nothing scheduled", "no entries" and "no record" are three different cell appearances, and none of them is drawn as the lowest step of the ramp. Future days are blank. [M][G]

MA-HIST-5. The year mode shows twelve month blocks in a grid of three columns, each a miniature of the month grid with non-interactive cells. Tapping a month block opens that month. A table with one row per month is the alternative. [M]

MA-HIST-6. Week start follows the account setting. Weekday headers are full names for assistive technology and two-letter abbreviations on screen. [M]

MA-HIST-7. One month is one request of at most 31 day records. Neighbouring months are fetched only when requested. [M]

### 7.13 S-13 Day detail

Purpose: everything recorded on one local day, in time order, with the means to correct it.

Resources: `days/{date}` and the domain resources for edits.

```
+------------------------------------------+
| Thursday 12 November            [<] [>]  |
+------------------------------------------+
| 00   06    |   12        18          24  |
| ==sleep==..o..|..x...........o........   |
+------------------------------------------+
| 06:50  Woke up - slept 7 h 10 min      > |
| 07:58  Medication C - Taken            > |
| 08:00  Medication D - Skipped          > |
| 08:10  Water - 250 ml                  > |
| 08:15  Breakfast - Porridge, coffee    > |
| 08:12  Medication A - Taken            > |
|        scheduled 08:00                   |
| 14:05  Mood - Slightly pleasant        > |
+------------------------------------------+
| [ + Add to this day ]                    |
+------------------------------------------+
```

MA-DAY-1. Entries are listed by effective time. A dose shows both its scheduled time and the time it was taken when they differ; the two are never merged (`docs/MEDICATION_REMINDER_UX.md`, "Keep `scheduled_for` and `taken_at` separate"). [M]

MA-DAY-2. Tapping an entry opens the same sheet that its domain screen uses, with the same validation. "Add to this day" asks for the kind of entry and opens that sheet with the date preset. [M]

MA-DAY-3. Corrections and backdated entries are accepted for the period that PRODUCT_SPEC.md allows. Outside it, the entry is read-only and the sheet says why. The API is the authority; the client only reflects the answer. [M]

MA-DAY-4. A correction never erases the record of what was there before. The sheet's history list shows the earlier action and the correction (ADR-012; R05 section 7.5 item 8). [M]

### 7.14 S-14 Reports and export

Purpose: preview the cards the bot sends, produce the record summary (V2), export everything, and later import.

Resources: `report-cards`, `report-cards/{kind}/renders`, `renders/{id}`, `report-schedule`, `exports`, `exports/{id}`, `exports/{id}/download-tickets`, `imports`, `pending-actions`.

```
+------------------------------------------+
| Reports and export                       |
+------------------------------------------+
| Report cards                             |
|  Weekly summary     3 to 9 Nov         > |
|  Monthly summary    October            > |
|  Mood calendar      needs 3 more days    |
| Weekly report in chat  Mon 09:00       > |
+------------------------------------------+
| For an appointment                       |
|  Medication list and history (PDF)     > |
+------------------------------------------+
| Your data                                |
|  Export everything (ZIP)               > |
|  Past exports                          > |
+------------------------------------------+
| Bring data in                            |
|  Import from a file                    > |
+------------------------------------------+
```

Report card page: a period selector, the options "Theme: auto, light, dark" and "Show medication names" (off by default, R04 open question Q4), the rendered image, the text equivalent printed below it, and the actions "Send to my chat" and "Save image".

Behaviour:

1. A card is rendered by the backend (R04 section 7) and returned as a PNG. The client requests a render, shows a skeleton with the card's aspect ratio, and displays the image from a blob URL. The text equivalent arrives with the render result and is shown at once; if the image is not ready after 5 seconds or fails, the text stays and the image area says "The image could not be made. The text has the same content." (R04 section 7.1 invariant 4).
2. "Send to my chat" asks the backend to deliver the card in the private chat. The first use explains that images sent to the chat are stored by Telegram and stay there until the user deletes them, and requires the corresponding consent, which is given in chat (R06 section 14.3 item 8).
3. The record summary (the PDF titled "Record summary", PRODUCT_SPEC R-IMX-6; wave V2, deferred in ROADMAP section 9.1; never called a clinical or medical report) offers: period (30 days, 90 days, custom up to 366 days), sections to include, and "Show medication names" (on by default for this document, because it is saved to the device and not sent to the chat). Its description reads "A plain record of what you logged. It is not a clinical report." (R06 section 14.9).
4. Generated files are delivered by download (MA-NATIVE-13). "Send to my chat instead" is a secondary choice with the warning "This puts a copy of your records into your Telegram chat, where Telegram stores it." (R06 section 9.2).
5. "Export everything" explains the content (one ZIP with JSON and CSV per domain, consent history and access history), then asks for confirmation in chat (section 8.8). The export is prepared in the background. When it is ready the page offers "Download" for 15 minutes and one download; afterwards the entry reads "Expired". At most 3 exports per 24 hours (R06 section 9.2); the fourth request shows when the next one is possible.
6. Import (wave V2). First the Dosegram CSV template (download the template, pick the filled file, see a preview of counts and errors, confirm). Then the Apple Health export for sleep, body mass and water (R03 section 7.1): the file is opened with a file input, read on the device in a background worker with a streaming parser, reduced to an allow-list of record types, shown as a preview of counts per type and date range, and only the normalised entries are uploaded in batches. The raw file never leaves the device (R05 section 9, design consequence 1).

Requirements:

MA-REP-1. A report card in the Mini App and the same card sent to the chat come from the same render request parameters and show the same numbers as the text equivalent on the page. [G][M]

MA-REP-2. The text equivalent is real text in the page, placed directly after the image, and is the image's `alt` when it fits in 1024 characters, otherwise `alt` names the card and points to the text below. [M]

MA-REP-3. A card that needs a minimum of data shows its progress toward it and offers no render action until the minimum is met. [M]

MA-REP-4. No export or report file is fetched through a URL that contains the session token. The download ticket expires in 15 minutes, is bound to one export, is useless for any other resource, and follows the request limit of ARCHITECTURE section 9.7. [M]

MA-REP-5. While an export is waiting for confirmation or being prepared, the page shows its state ("Waiting for your confirmation in chat", "Preparing", "Ready until 14:32", "Expired", "Failed") and updates it without a manual reload while the page is open. [M]

MA-REP-6. The import parser runs off the main thread, reports progress at least every second, can be cancelled, and keeps the interface responsive (interaction delay under 200 ms) while it runs. The largest file size and the device classes on which it is offered are set after spike MA-SPK-6; until then import of device exports is offered on Telegram Desktop only. [M][L]

MA-REP-7. Imported entries are shown with their source and are never merged into or mistaken for the user's own entries (R03 open question Q10). [M]

### 7.15 S-15 Settings

Purpose: language, time, notifications, privacy, data and help in one place.

Resources: `settings`, `notification-settings`, `notification-options`, `time-zones`, `time-zone-previews`, `consents`, `access-history`, `account/deletion`, `domains/{domain}/data`, `support-codes`, `session`, `pending-actions`.

```
+------------------------------------------+
| Settings                                 |
+------------------------------------------+
| Language and region                      |
|  Language            English           > |
|  Units               Metric            > |
|  Clock               24-hour           > |
|  Week starts on      Monday            > |
+------------------------------------------+
| Time zone            Europe/Kyiv       > |
+------------------------------------------+
| Notifications                            |
|  Quiet hours         22:30 to 07:00    > |
|  Optional prompts    Up to 3 a day     > |
|  Discreet reminders  On                > |
|  Weekly report       Mon 09:00         > |
+------------------------------------------+
| Privacy and consent                    > |
| Your data                              > |
| Sharing                                > |
+------------------------------------------+
| Appearance                               |
|  Images in chat      Auto              > |
|  Reduce motion                     [ ]   |
|  Vibration                         [x]   |
+------------------------------------------+
| About and help                         > |
+------------------------------------------+
```

Sub-pages:

| Sub-page | Content |
|---|---|
| Language | The shipped locales (first English and Ukrainian). The choice applies to the Mini App and to the bot. The initial value is the account locale, which the bot took from Telegram's `language_code` when present (R02 section 23 row 42). |
| Units, clock, week start | Metric or US units for volume; 12-hour or 24-hour clock; Monday, Saturday or Sunday. Defaults follow the locale. |
| Time zone | The account zone, the device zone, "Use this device's time zone", and a searchable zone list from `time-zones`. Before saving, the page shows the result of `time-zone-previews`: which schedules follow the account zone and keep their clock times, which are fixed to a place and what local time they will ring at in the new zone. `MainButton` "Change time zone". |
| Quiet hours | On or off; start and end time; how a medication reminder that falls inside is handled, as a choice rendered from `notification-options`; a statement that optional prompts are not sent inside quiet hours. |
| Optional prompts | The daily maximum of optional prompts (range 0 to 12, default 4; PRODUCT_SPEC R-SCH-22); the times of the morning, midday and evening touchpoints (R03 section 4.2); one toggle per domain. A fixed line: "Medication reminders are not limited by this number." |
| Discreet reminders | On or off, with a preview of both forms of a reminder: with the name, and "Time for your 08:00 item" (R06 section 7.3). |
| Weekly report | Off, or a weekday and a time. |
| Privacy and consent | Each purpose with its state, the date it was given and the version of the notice; "Withdraw" for granted purposes; "Turn on in chat" for the others. Then: activity-based sleep estimates; report images in chat; "Show medication names in images" (off by default); "Protect images from forwarding" (on by default, with the note that it also prevents forwarding a card to someone the user wants to show it to); "Hide content when I leave the app"; app lock (later wave); "Who accessed my data" (access history); the privacy policy (opens with `openLink`); the sentence "Telegram can read the messages and images in your chat with Dosegram. Bot chats are not end-to-end encrypted." (R06 fact F2). |
| Your data | "Export everything" (S-14); "Delete the data of one area" with a choice of domain; "Delete account"; "Sign out on this device". |
| About and help | Version and build id; the non-clinical statement; "Help and contacts"; "Reminders are ordinary Telegram messages. Dosegram cannot override a muted chat or your phone's do-not-disturb mode, and cannot see whether a message was read." (R02 section 15); "Create a support code" (R06 section 11.3 item 2); the security contact; "Add to home screen" (MA-NATIVE-11); open-source licences. |

Time-zone mismatch banner (launch step 10): "This device is on Europe/Lisbon. Dosegram uses Europe/Kyiv." with "Review", which opens the Time zone sub-page, and "Keep Europe/Kyiv", which records on the server that this device zone was declined so that the banner does not return for it.

Account deletion page: what is deleted (all records, schedules, settings, consents, generated files); what stops at once (all reminders); what cannot be recalled ("Messages and images already in your Telegram chat stay there until you delete them", R06 section 9.3); the choice "Delete now" or "Delete in 7 days, and let me cancel until then" (R06 open question Q17); a danger button "Delete account". The button opens a native confirmation, then the confirmation in chat (section 8.8). After completion the application shows "Your account is deleted" with the single action "Close", which calls `close()`.

Requirements:

MA-SET-1. A toggle or single-choice setting saves on change with an optimistic update, shows "Saved" through a polite live region, and reverts with an inline error if the request fails. A sub-page with several dependent fields (quiet hours, time zone, optional prompts) saves with `MainButton`. [M]

MA-SET-2. Changing the language re-renders every visible string without a reload, sets the document's `lang` and `dir`, and persists on the server so that the bot's messages follow. [M]

MA-SET-3. The Time zone sub-page never saves a zone without showing the preview first, and its preview names every schedule that keeps a fixed zone. [M]

MA-SET-4. Quiet hours accept a range that crosses midnight. Equal start and end times are rejected (`quiet_hours_empty` "Start and end are the same"). [U][M]

MA-SET-5. Withdrawing a consent explains the effect ("Dosegram stops recording mood and stops mood prompts"), offers "Also delete my mood data", and is confirmed in chat. After withdrawal the domain disappears from Today, the Log hub and Insights, and its screen shows the "consent needed" state. [M]

MA-SET-6. Turning a domain on from the Mini App starts the consent flow in chat and returns the user to the Mini App afterwards through an inline `web_app` button in the consent receipt. The Mini App shows no consent checkbox of its own (MA-ROLE-2). [M][L]

MA-SET-7. "Delete account" and "Delete the data of one area" are danger buttons at the end of their page, each followed by a native confirmation and the confirmation in chat. Neither is reachable in fewer than three deliberate actions, and neither is hidden, renamed or made harder than this (R03 section 6.2 item 11). [M]

MA-SET-8. The access history lists exports, caregiver reads, operator access and consent changes with date and kind, newest first, with cursor paging. It contains no health content (R06 section 10.4 item 5). [M]

MA-SET-9. A link to the privacy policy is reachable from S-15 in two taps and from every gate screen that involves an account (G-3). [M]

MA-SET-10. Reminders (ADR-023, route `/settings/reminders`, start parameter `reminders`), reached from S-15. One block per prompt kind that can be turned on (water, mood, bedtime, morning) with its preset and next time; the presets of the kind as a choice with nothing preselected, whose preview of the next five times shows before anything is saved, and "Turn on" and "Turn off". "Pause 1 h" and "Pause 3 h", or "Resume", with the sentence that medication reminders are not affected. Tomorrow's reminders across every domain as a timeline, medication reminders without a name. The medication schedule count links to S-02. The day profile (wake, breakfast, lunch, dinner, bedtime, work days, work start and end, quiet hours) saves on the version read; work that ends before it starts is refused before the request. Routes: `GET /reminders`, `GET /reminders/presets` (with the medication presets as schedule drafts), `POST /reminders/plans`, `DELETE /reminders/plans/{kind}`, `POST /reminders/pause`, `GET` and `PATCH /reminders/day`, `GET /reminders/timeline`. [M]

### 7.16 S-16 Sharing with a caregiver (wave V2, if adopted)

PRODUCT_SPEC.md places `sharing-and-caregivers` in wave V2, and R06 section 5.2 defines the rules. This section fixes only what the Mini App shows, so that the grant model and the screens agree.

```
+------------------------------------------+
| Sharing                                  |
+------------------------------------------+
| People you share with                    |
|  "Olena"  told about unanswered doses  > |
|   Medication A only - until 12 May 2027  |
|  [ + Invite someone ]                    |
+------------------------------------------+
| Shared with you                          |
|  "Dad"  unanswered doses               > |
+------------------------------------------+
```

Invite form: a label for the person (the user's own word for them; Dosegram does not store Telegram names, R06 section 7.1); level ("Is told when a scheduled dose is not answered" or "Can also view summaries"); areas and, for medication, which medications; "Show medication names" (off by default, R03 open question Q9); an end date at most 12 months ahead. `MainButton` "Create invitation", then confirmation in chat. The result is a single-use link valid for 24 hours, offered through "Share" (`shareMessage`, TGF-27, if the prepared-message route is confirmed) and "Copy link".

MA-SHARE-1. The owner sees every grant with its scope and end date, and can end one with a danger button and a native confirmation. Ending takes effect at once. [M]

MA-SHARE-2. The grant page shows every access made under the grant. [M]

MA-SHARE-3. A caregiver's view is read-only, shows only what the API returns for the grant, carries a persistent header "Shared by <label>", and never shows notes. Role checks are made by the API; the client hides nothing that it received (R06 threat MA-9). [M]

MA-SHARE-4. There is no caregiver write access, no group view and no ranking of people. [O]

## 8. Interface states

### 8.1 Loading

MA-STATE-1. A screen that waits for data shows a skeleton in the shape of its final layout within 300 ms of navigation. The skeleton region has `aria-busy="true"` and one accessible label ("Loading medications"). No spinner is used for content. [M]

MA-STATE-2. Data already in the in-memory cache is shown at once and refreshed in the background (stale-while-revalidate). While a refresh runs, nothing on screen jumps: the layout shift of a refresh is zero unless the data changed. [M]

MA-STATE-3. An action in flight shows progress on the control that started it: `showProgress` on a bottom button, a busy state on an in-page button. The control is inactive while busy; the rest of the screen stays usable. [M]

MA-STATE-4. A request that takes longer than 10 seconds is treated as failed for the interface and enters the error state; the client aborts it. Long-running work (renders, exports, imports) is modelled as a job with a status resource, never as a long request. [M]

### 8.2 Empty and blocked states

Each screen's own empty state is listed in section 7. These states are shared:

| State | Shown when | Content | Action |
|---|---|---|---|
| Consent needed | The domain's consent is absent or withdrawn (`403 consent_required`) | "Mood tracking is off. Dosegram records mood only with your consent." | "Turn on in chat" |
| Not enough data | A view has a stated minimum | What is needed, with progress | none, or the logging action |
| Not found | `404` for a record | "This item does not exist or was removed." | "Back to <parent>" |
| Feature off | The bootstrap does not enable the screen | Not rendered; routes to it follow MA-ROUTE-2 | none |
| Read-only | An archived medication, an entry outside the correction period, a caregiver's view | The content, with one line that says why it cannot be changed | none |

MA-STATE-5. An empty state has one sentence that says what will appear, at most one primary action, and no illustration that suggests failure. [M]

### 8.3 Errors

The API answers errors as problem documents with a stable `code` (section 15.1). The client maps codes, not message texts.

| Condition | Interface |
|---|---|
| `401` | Section 3.5: silent re-exchange inside the freshness window, otherwise gate screen G-4 |
| `403 account_required` | Gate screen G-3 |
| `403 consent_required` | "Consent needed" state for that screen |
| `403 stepup_required` | Confirmation in chat (section 8.8) |
| `403` other | Inline "You cannot do this" with the reference id |
| `404` | "Not found" state |
| `409 already_resolved` | Not an error: reconcile and inform (MA-TODAY-3) |
| `409` or `412` version conflict | Section 8.5 |
| `422` with field errors | Errors on the fields, focus on the first, summary at the top |
| `429` | Inline "Too many requests. Try again in N seconds." using `Retry-After`; the control re-enables when the time has passed. Automatic retries stop. |
| `5xx`, timeout | Read: `ErrorState` in place of the content with "Try again". Write: the optimistic change is rolled back or queued (section 8.4), and a toast says what was not saved. |
| No network | Section 8.4 |
| Script error in a screen | An error boundary replaces that screen's content with "Something went wrong on this screen" and "Reload screen"; the tab bar and other tabs keep working. |

MA-STATE-6. Every error message says what happened and what the user can do. It contains no stack trace, no SQL, no raw status text and no apology. It shows the response's reference id as "Reference: <id>" so that the user can quote it to support (R06 threat API-7). [M]

MA-STATE-7. Idempotent reads are retried automatically up to 2 times with exponential backoff and jitter (PROPOSED: 0.5 s and 1.5 s). Writes that carry an idempotency key are retried the same way with the same key. Writes without one are never retried automatically. [U][M]

MA-STATE-8. Errors appear in the page, linked to what they concern, and are announced once through a live region. They are not shown only as a toast and not as a native alert. [M]

MA-STATE-9. The first-party error reporter sends the error class, the component name, the build id and the route template to `client-errors`. It never sends the URL, a record id, a message text from the API, field values or DOM text (R06 rule L9). It drops the known exception of TGF-38 on Telegram Web. [M][O]

### 8.4 Offline

Because no record is stored on the device (section 6.6), offline support is limited to the running session.

Delivery level (ROADMAP G3.7 and section 9.1). The MVP builds the minimum of this section: a failed write is reported as not sent and can be retried by the user; it is never dropped silently. The in-memory queue that replays actions on reconnect is deferred until a measured need exists.

MA-OFF-1. The application detects loss of connectivity from failed requests and the browser's `offline` event, shows the banner "No connection. Showing what was loaded at 14:02.", and removes it after the first successful request. [M]

MA-OFF-2. While offline, screens whose data is in memory stay readable and are marked with the time of their data. Screens without data show `ErrorState` "No connection" with "Try again". [M]

MA-OFF-3. These log actions may be made offline and are queued in memory: dose taken, skipped or snoozed; water added or removed; mood check-in; sleep check-in; meal added. Each queued action keeps the time of the tap as its effective time and its idempotency key. The queue holds at most 20 actions (PROPOSED); beyond that the controls are inactive with the reason shown. [M]

MA-OFF-4. A queued action is visibly different from a saved one: its row carries the label "Not saved yet". The banner counts them ("2 changes not saved yet"). `enableClosingConfirmation()` is active while the queue is not empty (MA-NATIVE-9). [M]

MA-OFF-5. On reconnect the queue is replayed in order. An action that the server refuses is removed from the queue, its optimistic effect is undone, and the user is told which action it was and why. An action whose effective time the server adjusts or rejects as too old is reported the same way; the server is the authority on accepted times. [M]

MA-OFF-6. Forms that need server validation (medication, schedule, settings pages, exports) cannot be saved offline. Their `MainButton` is inactive with the inline reason "No connection", and what the user typed stays in the form. [M]

MA-OFF-7. If the application is closed while the queue is not empty, the queued actions are lost. This limit is accepted (MA-Q-3) and is the reason for MA-OFF-4. [L]

### 8.5 Optimistic updates and conflicts

The bot and the Mini App can change the same record within seconds of each other, and one user may have the Mini App open on a phone and a desktop. Three mechanisms cover the cases.

Delivery level (ROADMAP G3.5 and section 9.1). The MVP answers a stale edit of any record with 412 and the current representation; the editor reloads the record and tells the user that it changed elsewhere, and nothing is applied silently. The field-level merge of the second row below is deferred: an account is edited by one person, so two concurrent edits of different fields of one medication are rare. It returns when caregivers or measured conflicts make it worth its cost.

| Kind of change | Mechanism | Examples |
|---|---|---|
| Command on a state machine or an append to a log | Idempotency key, decided by the server; the response is the authoritative state | Dose taken, skip, snooze, undo; water entry; mood check-in; sleep check-in; meal |
| Edit of a record with independent fields | Version check with `If-Match`; field-level merge on conflict | Medication details, hydration settings, notification settings, a meal's description |
| Edit of a record whose parts depend on one another | Base revision check; no automatic merge | A schedule; a sleep episode; quiet hours |

MA-CONC-1. Every command request carries an `Idempotency-Key` generated when the user acts, not when the request is sent, so that a retry and a replay from the offline queue repeat the same key. [U][M]

MA-CONC-2. An optimistic change is applied to the in-memory cache at once, marked as pending, and replaced by the server's answer. If the request fails for a reason other than connectivity, the cache is restored to its prior value and the user is told. [M]

MA-CONC-3. For a command, the server's answer always wins. If the answer differs from the optimistic guess, the interface shows the server's state and says so in one sentence that names where the other change came from when the API provides it ("Already skipped at 08:03 in chat"). [M]

MA-CONC-4. For a versioned edit refused with a version conflict, the client fetches the current record and compares three versions: the one the form was opened with (base), the user's (mine) and the current one (theirs). Fields changed only in mine are kept; fields changed only in theirs are taken; the request is then repeated once with the new version, without asking, if no field was changed on both sides. [U][M]

MA-CONC-5. If at least one field was changed on both sides to different values, the editor stays open and shows, for each such field, both values with the choice "Keep mine" or "Use the other one", under the heading "This medication was also changed elsewhere". Nothing is saved until the user has chosen for every such field. [M]

MA-CONC-6. For a schedule or a sleep episode refused with a revision conflict, the builder shows "This schedule was changed elsewhere at 14:05" with the server's summary sentence of the current revision and two actions: "Load the current schedule" (discarding the user's changes after confirmation) and "Keep editing mine" (which re-bases the draft on the current revision and requires a fresh preview before saving). [M]

MA-CONC-7. A record deleted or archived elsewhere while it is open in an editor results in the message "This item was removed elsewhere" and the single action "Back". The user's typed values are offered as text to copy only for free-text fields. [M]

MA-CONC-8. Conflict handling is covered by tests that interleave a Mini App request and a chat action on the same record in both orders, for each of the three mechanisms. [M]

### 8.6 Drafts

MA-DRAFT-1. S-04, S-05 and the night sheet save a draft to `drafts/{kind}` 10 seconds after the last change, on `deactivated`, and before gate screen G-4 is shown. A draft is tied to the account and, for an edit, to the record and the version it started from. [M]

MA-DRAFT-2. There is at most one draft per kind and record. Opening the editor with a draft offers "Continue your draft from HH:MM" or "Start again". Saving or discarding the form deletes the draft. The server expires drafts (the period belongs to ARCHITECTURE.md; PROPOSED 24 hours). [M]

MA-DRAFT-3. A draft whose base version is older than the record's current version is opened through the conflict handling of section 8.5. [M]

### 8.7 Keeping data current

Delivery level (ROADMAP G3.7 and section 9.1). The MVP does not build the change feed: the client refetches the resources of the visible screen on `activated`, on every tab change and after each of its own writes. MA-SYNC-1 and MA-SYNC-2 describe the deferred feed, which returns when refetch-on-focus is measured to be too slow or too costly.

MA-SYNC-1. The client learns about changes made elsewhere through the `changes` resource: one request with a cursor returns the list of resource keys that changed since the cursor. The client marks those cache entries stale and refetches the ones that are on screen. [M]

MA-SYNC-2. `changes` is requested on `activated`, on every tab change, after each of the client's own writes, and every 30 seconds while S-01 is visible, subject to MA-SESSION-2. It is not requested while the application is inactive. [M]

Decision: polling a small change feed. Rationale: Today changes a few times a day; a held connection per open app costs battery on a phone and a thread or socket on a single small server. Rejected alternative: server-sent events or a WebSocket.

### 8.8 Confirmation in chat (step-up)

R06 section 4.5 requires a second confirmation through the bot for: full export, account deletion, deletion of a domain's data, withdrawing or changing consent, creating or widening a caregiver grant, and turning app lock off.

Flow in the Mini App:

1. The user confirms in the page (native popup for destructive actions).
2. The client creates the action with `POST pending-actions` and receives its id and expiry (10 minutes, R06 section 12.3).
3. A sheet says "Confirm in your chat with Dosegram. We sent you a message with a Confirm button." with the actions "Open chat" (`openTelegramLink('https://t.me/<bot>')`) and "Cancel request".
4. While the sheet is open and the application is active, the client polls `pending-actions/{id}` every 3 seconds; on `activated` it polls at once.
5. On `confirmed` the sheet closes and the page shows the result. On `expired` or `cancelled` the sheet says so and offers "Send again".

MA-STEP-1. The Mini App cannot complete any step-up action by itself; no API route accepts the action without the confirmation that arrives through the bot. A test calls each such route with a valid session and expects `403 stepup_required`. [M]

MA-STEP-2. At most 5 pending actions exist per account (R06 section 12.3); the sixth request is refused with the reason shown. [M]

## 9. Accessibility

Target: WCAG 2.2 level AA for every screen, on Telegram for Android with TalkBack, Telegram for iOS with VoiceOver, and Telegram Desktop with keyboard only. MA-A-08 and MA-A-09 must be confirmed by spike MA-SPK-3 before the target is claimed for a platform.

| Id | Requirement | Criterion | Check |
|---|---|---|---|
| MA-A11Y-1 | Text contrast at least 4.5:1, large text and meaningful graphics at least 3:1, in light and dark, enforced by the contrast guard for arbitrary themes (section 5.3) and by a lint over the fallback and data tokens | 1.4.3, 1.4.11 | [U][O] |
| MA-A11Y-2 | Every interactive element is a native control (`button`, `a`, `input`, `select`, `textarea`) or has the complete role, name, state and keyboard behaviour of one. No click handler on a `div`. | 4.1.2, 2.1.1 | [O] lint, [M] |
| MA-A11Y-3 | Every control has an accessible name. Icon-only controls (settings, previous day, next day, remove) have a text name. | 4.1.2, 1.1.1 | [M] automated scan with zero violations |
| MA-A11Y-4 | Every screen has one `h1`, headings in order without skipped levels, one `main` landmark, and the tab bar in a `nav` landmark. Route changes move focus to the `h1` and set the document title. | 1.3.1, 2.4.2, 2.4.3, 2.4.6 | [M] |
| MA-A11Y-5 | Touch targets are at least 48 by 48 CSS px with at least 8 px between them. The calendar day cell (MA-HIST-1) is the one stated exception. | 2.5.8 (exceeded) | [M] measured in end-to-end tests |
| MA-A11Y-6 | No information is carried by colour alone. Dose states have a glyph and a word; chart series have labels or glyph legends; calendar cells have accessible names and the "Show values" mode; estimates are hatched and worded. | 1.4.1 | [M], design review |
| MA-A11Y-7 | Every chart and the mood blob has a text alternative with the same content, and every chart has a data table. Server-rendered cards have their text equivalent in the page. | 1.1.1 | [M] |
| MA-A11Y-8 | Everything works with the keyboard alone, in a logical order, with a focus indicator of at least 2 px and 3:1 contrast that is never hidden behind the tab bar, a toast or a sheet. | 2.1.1, 2.4.3, 2.4.7, 2.4.11 | [M] |
| MA-A11Y-9 | No action needs a drag, a swipe, a long press or a multi-finger gesture. The slider has stepper buttons. Lists are reordered, where ordering exists, with "Move up" and "Move down". | 2.5.1, 2.5.7 | [M] |
| MA-A11Y-10 | At 200 percent text size and at 320 px width, no content is lost or clipped and no page scrolls horizontally. Text is set in `rem`; containers grow with their text. | 1.4.4, 1.4.10 | [M] screenshots at 200 percent |
| MA-A11Y-11 | Form errors are identified in text, tied to their field, suggest a correction, and are summarised at the top after a failed save. Destructive and data-changing submissions are confirmed or reversible (Undo). | 3.3.1, 3.3.3, 3.3.4 | [M] |
| MA-A11Y-12 | Status messages (saved, added, offline, result counts) are announced through `role="status"` without moving focus. | 4.1.3 | [M] |
| MA-A11Y-13 | Motion can be turned off (MA-DS-5); nothing flashes; no content moves for more than 5 seconds without a way to stop it. | 2.2.2, 2.3.1, 2.3.3 | [M] |
| MA-A11Y-14 | The session time limit does not lose work: a dirty form is saved as a draft before the session ends and is offered again after relaunch (MA-GATE-3). Toasts with "Undo" stay 8 seconds, pause while focused, and the same undo is available from the entry's sheet afterwards. | 2.2.1 | [M] |
| MA-A11Y-15 | The document declares `lang` and `dir` from the account locale. Layout uses logical CSS properties, so a right-to-left locale mirrors without per-screen work. One right-to-left pseudo-locale is part of the visual tests from the start. | 3.1.1, 1.3.2 | [M][G] |
| MA-A11Y-16 | A user is never asked to re-enter information already given in the same flow (the medication's name and dose are carried into the schedule builder; the estimate fills the night sheet). | 3.3.7 | [M] |
| MA-A11Y-17 | Opening the Mini App requires no puzzle, password or transcription. Authentication is the Telegram launch itself. | 3.3.8 | by design |
| MA-A11Y-18 | Native controls keep their own accessibility: the application sets `MainButton` text that names the action and its object and does not replace native popups with drawn ones. | 4.1.2 | [L] |

Manual verification per release candidate: one pass with TalkBack, one with VoiceOver, one with keyboard only on Telegram Desktop, each over the flows "answer a dose", "add a medication and a schedule", "check in mood", "export". The automated scan is expected to find only a part of the defects, so these passes are a release gate, not an option.

## 10. Performance budget

### 10.1 Reference conditions

| Name | Definition |
|---|---|
| Reference device | An Android phone whose Telegram User-Agent reports performance class `LOW` (TGF-14). PROPOSED: the owner names one physical model for the device matrix. |
| Lab profile | Chromium with 6 times CPU slowdown and a network of 1.6 Mbit/s down, 750 kbit/s up, 150 ms round-trip time. Used in continuous integration because a physical phone is not available there. |
| Cold launch | No cached assets. |
| Warm launch | Assets cached by the WebView's HTTP cache. |

### 10.2 Size budgets (gzip, PROPOSED)

| Item | Budget | Gate |
|---|---|---|
| HTML shell | 3 KB | [O] |
| Initial JavaScript (application shell, router, theme adapter, API client, Today) | 70 KB | [O] |
| Initial CSS | 15 KB | [O] |
| Each lazily loaded route chunk | 35 KB | [O] |
| All application JavaScript together | 250 KB | [O] |
| Icon sprite (inline SVG symbols) | 8 KB | [O] |
| One locale catalogue | 12 KB | [O] |
| Fonts | 0 (none loaded) | [O] |
| Third-party runtime dependencies | At most 5 packages in the production bundle (PROPOSED) | [O] |
| Telegram script (not under our control) | 20.8 KB measured on 2026-11-12 (TG source JS); reported, not gated | report |
| `today` response | 20 KB | [M] |
| `medications` list response at 200 rows | 60 KB | [M] |
| `mood/blob-states` | 40 KB, cached for the session | [M] |

### 10.3 Time budgets on the lab profile (PROPOSED)

| Measure | Cold | Warm | Gate |
|---|---|---|---|
| First contentful paint (skeleton) | 1.8 s | 0.8 s | [O] |
| `ready()` called | 2.0 s | 1.0 s | [O] |
| Today interactive: a dose button responds | 3.5 s | 1.5 s | [O] |
| Interaction to next paint, 75th percentile over the end-to-end suite | 200 ms | 200 ms | [O] |
| Visible response to a tap (pressed state or optimistic change) | 100 ms | 100 ms | [M] |
| Navigation to skeleton | 300 ms | 300 ms | [M] |
| Cumulative layout shift per screen | 0.05 | 0.05 | [O] |
| Longest main-thread task after launch | 50 ms target, 100 ms limit | same | [O] |
| Search over 200 medications | 100 ms | 100 ms | [M] |
| Schedule preview shown after the last edit, excluding server time | 500 ms | 500 ms | [M] |

Server response times are budgeted in ARCHITECTURE.md. The client budgets above assume an API response within 300 ms at the 95th percentile for reads; if ARCHITECTURE.md sets a slower target, the "interactive" rows move by the difference.

### 10.4 Rules that keep the budget

MA-PERF-1. Each tab and each task screen is a separate lazily loaded chunk. Only the shell and Today are in the initial bundle. Chunks for the other three tabs are prefetched when the application is idle after launch, unless the performance class is `LOW` or the connection reports data saving. [O][M]

MA-PERF-2. Lists longer than 60 rows are windowed, so that at most 40 rows are in the document. A screen has at most 1,200 document nodes. A chart has at most 400 nodes. [M]

MA-PERF-3. Animations use `transform` and `opacity` only, and none runs while the application is inactive. On performance class `LOW` motion is reduced as in MA-DS-5. [M]

MA-PERF-4. The JavaScript heap after visiting all four tabs and three task screens stays under 30 MB on the lab profile (PROPOSED). Listeners, timers, blob URLs and bottom-button handlers are released when their screen leaves; a test visits each screen 20 times and checks that the heap and the listener count do not grow. [M]

MA-PERF-5. No request is made that the current screen does not need. Launch makes at most two API requests before Today is interactive (MA-LAUNCH-6). Polling stops when the application is inactive (MA-SYNC-2). [M]

MA-PERF-6. Images are requested at the size they are shown, decoded asynchronously, and given explicit dimensions so that they do not shift the layout. [M]

MA-PERF-7. The build targets a stated browser baseline (PROPOSED: Chromium 100 and Safari 15.4, MA-A-17) with no polyfill bundle. A launch on an older engine shows gate screen G-2's layout with the text "This device's browser component is too old for Dosegram." [O][L]

MA-PERF-8. Bundle sizes and lab timings are checked on every pull request that touches the Mini App. A change that exceeds a budget fails the check; raising a budget is a reviewed change to this table. [O]

## 11. Security and privacy on the client

The frontend is an untrusted client (R06 section 1.1, component C3). Every check that matters is made by the API. The rules below reduce what a compromised page, a curious bystander or a careless log can reveal.

MA-SEC-1. The page runs under the content security policy of R06 section 12.4 with zero violations in the end-to-end suite, apart from the known Telegram Web case of MA-A-10. The application contains no inline script, no inline style element, no `eval`, no `innerHTML`, `outerHTML`, `insertAdjacentHTML` or `document.write`, and builds dynamic SVG with DOM calls. A lint forbids these sinks. [O][M]

MA-SEC-2. User-entered text (names, notes, tags, labels) is rendered only as text nodes. It is never interpreted as HTML, as a URL, as CSS or as a route. [M] with hostile-string fixtures

MA-SEC-3. The only third-party code that executes is the Telegram script. The production bundle has no analytics, no error-tracking service, no font or icon service and no tag manager (non-goal N16). A test loads every screen and asserts that all requests go to the application's origin or to `https://telegram.org/js/telegram-web-app.js`. [M][O]

MA-SEC-4. The application registers no `message` event listener of its own. If one becomes necessary, it validates `event.origin` and `event.source` (R06 threat MA-4). [O]

MA-SEC-5. Requests carry the session token in the `Authorization` header only. No cookie is set or read. No token or record id is placed in a query string, except the download ticket of MA-NATIVE-13. [M]

MA-SEC-6. The application never trusts `initDataUnsafe` for anything but choosing a language for a gate screen and reading the untrusted `start_param` for routing. Identity, roles and enabled features come from the bootstrap document. [O]

MA-SEC-7. Privacy curtain: when "Hide content when I leave the app" is on (default on, PROPOSED), the application covers its content with a neutral panel on `deactivated` and after 2 minutes without input, and removes it on the next tap. The curtain is a convenience against onlookers; it is not access control (MA-A-20). [M]

MA-SEC-8. Nothing the application writes to the console in production contains a record value, a name, a token or `initData`. Development logging is removed by the build. [O]

MA-SEC-9. Role and scope checks are never made only in the client. The client renders what the API returned and offers the actions the API lists for the record. [M]

MA-SEC-10. The canary test of R06 rule L10 covers the Mini App: canary strings entered in every field must not appear in client error reports, in the URL, in `CloudStorage`, or in any request other than the one that saves that field. [M][O]

## 12. Frontend stack

### 12.1 Decision

Svelte 5 with TypeScript, built by Vite as a static single-page application without a server-rendering framework. API types are generated from the backend's OpenAPI document. Server state is held by `@tanstack/svelte-query`. Routing, the theme adapter, form validation, internationalisation and charts are small in-repository modules.

Versions current on 2026-11-12 are listed in TGF-40. Exact versions are pinned in the lockfile when the first slice starts.

### 12.2 Comparison

The backend is Scala 3 on the JVM (R01 section 3), so a Scala front end was evaluated together with the three TypeScript options.

| Criterion | TypeScript + React 19 | TypeScript + SolidJS 1.9 | TypeScript + Svelte 5 | Scala.js 1.22 + Laminar |
|---|---|---|---|---|
| Framework runtime in the bundle (MA-A-13, to be measured) | Largest of the TypeScript options; uses most of the 70 KB initial budget alone | Smallest | Small | Largest overall once the Scala standard library and a date-time library are linked |
| Work on a slow phone | A virtual tree is compared on each update; needs care (memoisation, list keys) to stay smooth (ASSUMPTION) | Updates the affected nodes directly (ASSUMPTION) | Updates the affected nodes directly (ASSUMPTION) | Updates the affected nodes directly; start-up cost follows bundle size (ASSUMPTION) |
| Sharing with the Scala backend | Types generated from OpenAPI | Types generated from OpenAPI | Types generated from OpenAPI | Could compile `contracts` and its codecs for the browser and share validation code directly |
| Schedule preview and time-zone rules | Must come from the server | Must come from the server | Must come from the server | Could run the Scala evaluator in the browser, but that needs a time-zone database in the bundle (ASSUMPTION about size) and creates a second place where the rule runs |
| Styling under `style-src 'self'` | Needs a choice (CSS modules) | Needs a choice | Scoped styles are compiled into the static stylesheet by the standard build | Styles set through DOM calls, or a separate stylesheet |
| Component and accessibility test tooling | Mature (Testing Library) | Available | Available (`@testing-library/svelte` 5.4.2, TGF-40) | Thin; mostly browser-level tests (ASSUMPTION) |
| Release maturity on 2026-11-12 (TGF-40) | Stable 19.3.1 | Stable 1.9.19 | Stable 5.57.3 | The latest published Laminar artefact for Scala 3 is a milestone, 18.0.0-M5, from February 2026 |
| Build | Node toolchain beside Mill | Node toolchain beside Mill | Node toolchain beside Mill | Stays inside Mill |
| Supply chain | Largest ecosystem, and the strongest pull toward many dependencies | Small | Small | No npm dependencies in production |
| People and coding agents who can maintain it | Most | Few | Many | Very few |

### 12.3 Rationale

1. The binding constraint is a WebView on a low-end Android phone with a slow connection (section 10). Svelte and SolidJS leave most of the 70 KB initial budget for the application; React does not (MA-A-13).
2. The screens are forms, lists and SVG. Svelte's templates are close to HTML, which keeps native controls, labels and landmarks the path of least resistance, and that is what the accessibility target needs.
3. Scoped styles compiled to one static stylesheet satisfy the content security policy without a styling library and without runtime style injection (ASSUMPTION about the default build output, confirmed in spike MA-SPK-5).
4. The useful part of "one language" is one definition of the data contract. That is achieved by generating TypeScript types from the OpenAPI document that the Scala server already describes its endpoints with (the repository uses Tapir, R01 section 3.6). The other part, running domain rules in the browser, is deliberately avoided: schedule evaluation and daylight-saving rules have one implementation, on the server, and the builder asks it for a preview.
5. Between Svelte and SolidJS the technical difference is small. Svelte has the larger community and the maintained testing library, which matters for a project built mostly by coding agents under review.

Rejected alternative, the runner-up: SolidJS with TypeScript. It would meet the same budget. It is the fallback if spike MA-SPK-5 finds a blocking problem with Svelte under the content security policy or in an old WebView.

Rejected alternative: React with TypeScript. Its ecosystem is the largest, but this application deliberately uses almost none of it (no component kit, no chart library, no styling library), so the ecosystem buys little, and the runtime costs budget on exactly the devices that have none to spare.

Rejected alternative: Scala.js with Laminar. Keeping one language and one build tool is a real advantage for a solo owner who writes Scala. It is outweighed here by the expected bundle size on a low-end phone, the date-time and time-zone data problem, the thin component-test tooling, and the fact that the current Laminar line for Scala 3 is a milestone release. If the owner values one language above the phone budget, this is the option to choose instead, and MA-Q-1 records that.

Rejected alternative: a server-rendering meta-framework (SvelteKit and similar). The application is authenticated from its first request and holds no public pages, so server rendering has nothing to render, and a static bundle keeps the Scala server the only server.

Rejected alternative: the community Telegram SDK `@telegram-apps/sdk`. Its last publication is from 2025-10-08 (TGF-40), more than a year before Bot API 10.4. The application wraps the official script behind its own `TelegramPort` (section 14.1) instead.

### 12.4 Production dependencies

| Package | Purpose | Note |
|---|---|---|
| `svelte` | Component runtime | Compiled; only the parts used are bundled |
| `@tanstack/svelte-query` | In-memory server state: caching, deduplication, cancellation, retries, optimistic updates with rollback, invalidation by key | Persistence to storage stays off. If spike MA-SPK-5 shows it does not fit the budget or the Svelte version, an in-repository cache with the behaviours of section 8 replaces it. |

Nothing else ships to the browser. Development dependencies (TGF-40): `vite`, `typescript` (the major version that the Svelte tooling supports at the time; ASSUMPTION that TypeScript 7 is supported), `vitest`, `@testing-library/svelte`, `@playwright/test`, `msw`, `axe-core`, `openapi-typescript`, `size-limit`, and a linter with the Svelte plugin.

Decision on forms: native constraint validation plus one in-repository validation function driven by a rule table per form, with the server's error codes as the shared vocabulary. Rejected alternative: a schema-validation library. The application has about eight forms with a handful of cross-field rules; the server validates everything again in any case.

### 12.5 Source layout (PROPOSED)

```
miniapp/
  index.html            shell: Telegram script, one stylesheet, one module
  src/
    app/                shell, router, error boundary, gate screens
    platform/           TelegramPort, real adapter, fake adapter, theme adapter
    api/                generated types, client, error mapping, query keys
    state/              session, offline queue, drafts, change feed
    ui/                 design-system components (section 5.6)
    charts/             chart components and scale helpers
    screens/            one folder per screen, S-01 to S-16
    i18n/               catalogues: en, uk, pseudo right-to-left
    styles/             tokens.css (generated), base.css
  tests/                component tests, end-to-end tests, fixtures
```

MA-STACK-1. No component file exceeds 200 lines; a screen is composed of smaller components. Data fetching lives in the screen's container; presentation components receive data as properties. [O] lint

MA-STACK-2. Only `platform/` touches `window.Telegram`. A lint forbids the identifier elsewhere. [O]

MA-STACK-3. Only `api/` calls `fetch`. Every request goes through one client that adds the token, the idempotency key and the build id, applies the timeout, and maps problem documents to typed errors. [O]

## 13. Build, hosting and release

### 13.1 Build

1. Node at its active long-term-support version, pinned in the repository. `pnpm` with a committed lockfile, frozen installs and lifecycle scripts disabled by default (R06 section 12.5).
2. `vite build` writes `miniapp/dist/`: `index.html`, one initial script, one stylesheet, lazily loaded chunks, the icon sprite and the locale catalogues, all with content hashes in their names except `index.html`.
3. The build inlines nothing into `index.html`: no script, no style, no data.
4. `api/types.d.ts` is generated from the backend's OpenAPI document. The check job regenerates it and fails if the committed file differs, so the client cannot drift from the server.
5. `styles/tokens.css` is generated from the shared design-token file (MA-Q-9).
6. Source maps are produced and kept as a build artefact. They are not served.
7. The production dependencies pass the same permissive-licence rule as the Maven dependencies (R01 section 6.5). `axe-core` is MPL-2.0 and is a development dependency only.

Decision: the Mini App is a directory with its own Node toolchain, not a Mill module. Continuous integration gets one more job, and the container build gets one more stage that copies `dist/` into the image. Rejected alternative: driving the Node build from a Mill task, which couples two build tools for no shared artefact other than the OpenAPI file.

### 13.2 Hosting

MA-HOST-1. The static files and the API are served from one origin: the files at the root, the API under one path prefix that ARCHITECTURE.md names (R06 uses `/api`). No cross-origin resource sharing headers are sent, except the single header that `downloadFile` needs on the download-ticket route (TGF-25). [M][L]

MA-HOST-2. The origin is the one registered in BotFather for the Main Mini App. The origin restriction of TGF-05 is not opted out of. [L]

MA-HOST-3. Response headers: the security headers of R06 section 12.4 on every response; `Cache-Control: no-cache` on `index.html`; `Cache-Control: public, max-age=31536000, immutable` on hashed assets; `Cache-Control: no-store` on API responses. Text assets are served with Brotli or gzip compression. [M]

MA-HOST-4. Any path that is not under the API prefix and has no file extension returns `index.html` (MA-ROUTE-3). [M]

MA-HOST-5. The same origin serves three pages that work without Telegram and without a session: the privacy policy, the status and notice page that R06 section 13.3 requires as the out-of-band channel, and `/.well-known/security.txt`. [M]

Whether the files are served by a TLS proxy in front of the application or by the application's own HTTP server is a decision for ARCHITECTURE.md. This document requires only the properties above.

### 13.3 Release

MA-REL-1. The Mini App and the API are built from one commit and shipped in one release artefact, so a deployed client and server always match at deploy time. [O]

MA-REL-2. A WebView can stay open across a deployment. The client sends its build id with every request; the bootstrap carries the minimum build the server accepts; an API response may carry the same value. When the running build is older, the client finishes the current request, saves any draft, and shows gate screen G-5. [M]

MA-REL-3. A release changes the API only in backward-compatible ways for at least the previous client build, or raises the minimum build in the same release. [O] contract test against the previous build's recorded requests

MA-REL-4. Rolling back the release artefact rolls back the Mini App with it. No Mini App asset lives outside the artefact. [O]

### 13.4 Environments and BotFather configuration

| Environment | Bot | Mini App origin | Notes |
|---|---|---|---|
| Local development | None; the fake `TelegramPort` | `http://localhost` | Backend in its development profile |
| Telegram test environment | A bot in the test environment | May be plain HTTP (TGF-32) | Separate accounts; used for automated live checks where possible |
| Staging | A second bot in production Telegram | Its own HTTPS origin | `file_id` values are not shared between bots (R02 section 21) |
| Production | The Dosegram bot | The production origin | |

Per bot, in BotFather: the Main Mini App URL; the menu button; the splash-screen icon and colours; the privacy policy URL; media previews for the profile; group joins off; inline mode off (R02 sections 8, 18 and 21). R02 open question 11 recommends creating these when the first Telegram slice is scheduled.

## 14. Testing strategy

### 14.1 Running outside Telegram

All Telegram calls go through one interface. The sketch shows the shape of the contract; it is not product code.

```ts
interface TelegramPort {
  readonly initData: string
  readonly startParam: string | undefined
  readonly platform: string
  readonly performanceClass: 'LOW' | 'AVERAGE' | 'HIGH' | 'UNKNOWN'
  isVersionAtLeast(version: string): boolean

  theme(): { scheme: 'light' | 'dark'; params: Readonly<Record<string, string>> }
  onThemeChanged(listener: () => void): Unsubscribe
  insets(): { safe: Insets; content: Insets; stableHeight: number }
  onViewportChanged(listener: () => void): Unsubscribe
  onActivated(listener: () => void): Unsubscribe
  onDeactivated(listener: () => void): Unsubscribe

  ready(): void
  expand(): void
  close(): void
  disableVerticalSwipes(): void
  setClosingConfirmation(enabled: boolean): void
  setChromeColors(colors: { header: string; background: string; bottomBar: string }): void

  mainButton: BottomButtonPort
  secondaryButton: BottomButtonPort
  backButton: { show(): void; hide(): void; onClick(listener: () => void): Unsubscribe }
  settingsButton: { show(): void; onClick(listener: () => void): Unsubscribe }

  haptic(event: HapticEvent): void
  popup(params: PopupParams): Promise<string | null>
  confirm(message: string): Promise<boolean>
  preferences: { get(key: string): Promise<string | null>; set(key: string, value: string): Promise<void> }

  openLink(url: string): void
  openTelegramLink(url: string): void
  downloadFile(url: string, fileName: string): Promise<'started' | 'cancelled' | 'unsupported'>
  requestWriteAccess(): Promise<boolean>
  homeScreen: { status(): Promise<HomeScreenStatus>; add(): void }
  biometric: BiometricPort
}
```

Two implementations exist:

1. The real adapter wraps `window.Telegram.WebApp` and nothing else does.
2. The fake adapter is included in development and test builds only. It draws a device frame around the application with a header (back and close), the two bottom buttons, and a control panel that changes: colour scheme and theme (a set of presets including one deliberately low-contrast theme), safe-area insets, viewport height (to imitate the keyboard), client version, platform, performance class, active or inactive, online or offline, and availability of each optional capability. It records haptic calls, popups and link openings so that tests can assert on them.

Development authentication: the fake supplies an `initData` string signed with a development Ed25519 key. The backend accepts that key only in its development profile and refuses to start with it when the production key is configured. The details belong to ARCHITECTURE.md; the requirement here is that no test-only login path exists in a production build of either side.

MA-TEST-1. Every screen and every flow of section 7 can be run and tested in Chromium and WebKit on a developer machine and in continuous integration without a Telegram client and without a network connection to Telegram. [O]

MA-TEST-2. The production bundle contains no fake adapter, no development key and no mock handler. A build check searches the bundle for their markers. [O]

Note: the official script also runs outside Telegram, where it reports version `6.0`, platform `unknown` and an empty `initData` (TGF-39). The application treats that as gate screen G-1. The script is therefore loaded in development as in production, and the fake adapter is selected by build configuration, not by sniffing the environment.

### 14.2 Test layers

| Layer | Tool | Scope | Runs |
|---|---|---|---|
| Unit | Vitest | Theme adapter and contrast guard (table tests over real and hostile themes); route parser; validators; three-way merge; offline queue; formatters; time-field fallback parser | Every commit |
| Component | Vitest, Testing Library, fake port | Every component of section 5.6 in every state; keyboard behaviour; accessible names; automated accessibility scan | Every commit |
| Screen | Same, with mocked API handlers | Each screen in loading, empty, typical, error, offline and conflict states; the mocked responses are validated against the OpenAPI document so that mocks cannot drift | Every commit |
| Contract | Generated types, schema validation | The client compiles against the generated types; recorded client requests validate against the schema | Every commit |
| End to end | Playwright (Chromium and WebKit, phone viewport), fake port, real backend and PostgreSQL in containers | The flows: first launch and gate screens; add a medication and a schedule; answer, correct and undo a dose; as-needed dose; water; mood; sleep night and estimate; export with confirmation; consent withdrawal; account deletion; time-zone change | Every pull request |
| Cross-surface | End-to-end harness plus the Telegram fake wire that the adapter tests use (R01 section 5) | MA-ROLE-3, MA-TODAY-3, MA-TODAY-4, MA-CONC-8, MA-SET-6: the same record changed from chat and from the Mini App in both orders | Every pull request |
| Visual regression | Playwright screenshots | Each screen, typical state, in the matrix: light, dark, low-contrast theme; English, Ukrainian, pseudo right-to-left with long strings; viewports 320 by 568, 360 by 740, 412 by 915 and 900 by 700; plus 200 percent text size for the form screens | Every pull request; a contact sheet is attached for review |
| Performance | `size-limit`, Playwright traces on the lab profile | Section 10 budgets | Every pull request |
| Security | Playwright with the production headers | Zero content-security-policy violations; request allow-list (MA-SEC-3); storage canary (MA-STORE-1); no session token in any URL | Every pull request |
| Catalogue lints | Script | Missing keys between locales; forbidden clinical and shaming phrases; causal verbs in insight texts; popup length limits; `reason` length limits | Every commit |
| Live | Real Telegram clients | Section 14.3 | Every release candidate |

MA-TEST-3. The automated accessibility scan reports zero violations of its WCAG A and AA rules on every screen in every tested state. A suppressed rule needs a written reason next to the suppression. [O]

MA-TEST-4. Hostile-string fixtures are part of the component and screen tests: markup, quotes, ampersands, bidirectional control characters, a 500-character name without spaces, emoji, and right-to-left text in a left-to-right layout. Nothing overflows its container, nothing is interpreted as markup, and bidirectional controls do not reorder neighbouring text. [M]

MA-TEST-5. A seeded failure proves each new gate fails closed (a deliberately oversized chunk, a deliberately low-contrast token, a deliberately forbidden phrase), as the repository already requires for new gates (R04 section 12). [O]

### 14.3 Live checks on Telegram clients

Matrix (PROPOSED): Android on the reference low-end device; Android on a current device; iOS on a current iPhone; Telegram Desktop on Windows, Linux and macOS; Telegram Web (both web clients).

Script, recorded as a smoke transcript per client:

1. Open from the menu button; Today appears; theme matches the client.
2. Switch the client theme while the app is open; the app follows.
3. Open from an inline button to a medication; `BackButton` leads to the list, then to Today.
4. Open a `?startapp=water` link from another chat; the hydration screen appears.
5. Add a medication with a two-time daily schedule; the preview shows 14 rows; save.
6. Mark a dose taken in the Mini App; the chat message shows the final state.
7. Mark a dose in chat with the Mini App open; Today follows within 35 seconds.
8. Rotate the device and open the keyboard in a form; nothing is covered.
9. Turn the connection off, add water, turn it on; the entry is saved once.
10. Request an export; confirm in chat; download the file.
11. Leave the app for 20 minutes; return; the session-ended screen relaunches the app at the same screen.
12. With the screen reader on: answer a dose and complete a mood check-in.

Debugging on a device uses the WebView inspection modes of TGF-31.

## 15. API contract: resource list

This section maps each screen to the API resources it needs. Paths are relative to the API base. ARCHITECTURE section 9.5 is the single list of resources and owns their names, methods, status codes and authentication; after the review of 2026-11-12 the names below are exactly the names of that list (ROADMAP conflict X-25), and a resource is never added here without being added there in the same change.

### 15.1 Conventions the client relies on

| Topic | Convention |
|---|---|
| Authentication | `Authorization: Bearer <session token>` on every request except `POST session` |
| Format | JSON; field names stable; unknown fields ignored by the client; unknown request fields rejected by the server (R06 threat API-4) |
| Time | Instants as ISO 8601 with offset, plus the IANA zone where a local reading matters; local dates as `YYYY-MM-DD`; local times as `HH:MM` |
| Quantities | Decimal numbers as strings with at most 3 fraction digits; units from server-defined lists |
| Commands | `POST` with an `Idempotency-Key` header; the response is the resulting state of the record |
| Versioned edits | The record carries a version; `PATCH`, `PUT` and `DELETE` send `If-Match`; a mismatch is a version conflict with the current record in the answer |
| Errors | Problem documents with `code`, a reference id, and for validation a list of `{field, code}`; the client maps codes to its own localised messages |
| Server-written text | Schedule summaries, clock-change notes, chart summaries, correlation sentences and text equivalents are produced by the server in the account locale |
| Paging | Cursor and limit; limit at most 100 |
| Caching | `Cache-Control: no-store`; the client may send `If-None-Match` from memory for conditional reads |
| Limits | The bootstrap document lists the limits the client shows (name length, quotas, ranges), so they are not hard-coded |
| Authorisation | Every resource is scoped to the session's account; a foreign id is indistinguishable from an unknown one |

### 15.2 Resources by module

`identity-and-consent`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `session` | Create from `initData` (returns token and bootstrap); delete | Launch, S-15 | MVP |
| `session/keepalive` | Touch | MA-SESSION-1 | MVP |
| `settings` | Read; change language, units, clock, week start, time zone, image theme, names in images, image protection, privacy curtain, reduce motion | S-15 | MVP |
| `time-zones` | Read the zone list with offsets | S-05, S-15 | MVP |
| `time-zone-previews` | Create a preview of the effect of a zone change on schedules | S-15 | MVP |
| `time-zone-prompts` | Record that a device zone was declined | Launch banner | MVP |
| `consents` | Read purposes and states | S-15, all domain screens | MVP |
| `consents/{purpose}/requests` | Start the consent flow in chat; the purposes are those of PRODUCT_SPEC R-IDC-6, and the app never records a consent (MA-ROLE-2) | S-15, "consent needed" state | MVP |
| `consents/{purpose}/withdrawals` | Withdraw (step-up) | S-15 | MVP |
| `pending-actions`, `pending-actions/{id}` | Create a step-up action; read its state; cancel | Section 8.8 | MVP |
| `access-history` | Read | S-15 | V1 |
| `account/deletion` | Request (step-up); cancel during the delay | S-15 | MVP |
| `domains/{domain}/data` | Delete one domain's data (step-up) | S-15 | MVP |
| `support-codes` | Create | S-15 | V1 |
| `app-lock` | Turn on; turn off (step-up) | S-15 | V2 |
| `changes` | Read changed resource keys since a cursor | Section 8.7 | Later (deferred with the change feed; the MVP client refetches on focus) |
| `drafts/{kind}` | Read, write, delete a form draft | S-04, S-05, S-10 | MVP |
| `client-errors` | Report a client error (class, component, build, route template) | MA-STATE-9 | MVP |

`medication-registry`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `medication-options` | Read forms, units, tags, limits | S-04 | MVP |
| `medications` | List by status; create | S-02, S-04 | MVP |
| `medications/{id}` | Read; change (versioned) | S-03, S-04 | MVP |
| `medications/{id}/state-changes` | Pause, resume, archive, restore | S-03 | MVP |
| `medications/{id}/inventory` | Read; set amount and threshold | S-03, S-04 | V1 |
| `medications/{id}/refills` | Add a refill; list | S-03 | V1 |

`schedule-and-reminders`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `schedule-options` | Read enabled patterns, limits, follow-up delays, snooze choices, miss delays | S-05 | MVP |
| `schedule-previews` | Create a preview for a draft: next seven days, summary sentence, warnings, effective-from time | S-05 | MVP |
| `medications/{id}/schedules` | List; create | S-03, S-05 | MVP |
| `schedules/{id}` | Read the current revision | S-05 | MVP |
| `schedules/{id}/revisions` | Create a revision on a base revision; list | S-05 | MVP |
| `schedules/{id}/state-changes` | Pause, resume, end | S-03, S-05 | MVP |
| `notification-settings` | Read; change quiet hours, optional-prompt budget and touchpoints, per-domain prompts, discreet mode | S-15, S-07 | MVP |
| `notification-options` | Read the choices offered for quiet-hours handling and budgets | S-15 | MVP |
| `reachability` | Part of the bootstrap: whether the bot can write to the user | Launch banner | MVP |

`intake-log`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `occurrences` | List by date range, optionally by medication | S-12, S-13 | MVP |
| `occurrences/{id}` | Read with its action history | S-01, S-13 | MVP |
| `occurrences/{id}/actions` | Command: taken, skipped, snoozed, undo, correct (with effective time) | S-01, S-13 | MVP |
| `intakes` | Create an as-needed or manual dose; list | S-01, S-13 | MVP |

`daily-overview`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `today` | Read one local day: occurrences by group, tracker totals, day-line marks | S-01, S-06 | MVP |
| `days/{date}` | Read every entry of a local day across domains | S-13 | MVP |

`hydration`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `hydration/entries`, `hydration/entries/{id}` | List by date; add; change; remove | S-01, S-07, S-13 | MVP |
| `hydration/settings` | Read; change goal, unit, presets, reminder | S-07 | MVP |
| `hydration/summary` | Read totals per day for a range | S-07, S-11 | MVP |

`nutrition`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `meals`, `meals/{id}` | List by date; add; change; remove | S-08, S-13 | V1 |
| `meals/{id}/photo` | Read at a named size; upload; remove | S-08 | V1 read; upload when enabled |
| `meals/recent` | Read recent descriptions per meal type | S-08 | V1 |
| `nutrition/summary` | Read meals logged per day for a range | S-08, S-11 | V1 |

`mood`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `mood/scale` | Read stops, labels, default tags, blob revision | S-09 | MVP |
| `mood/blob-states` | Read the global blob asset for a revision | S-09 | MVP |
| `mood/checkins`, `mood/checkins/{id}` | List by date; add; change; remove | S-09, S-13 | MVP |
| `mood/tags` | Read; change the user's own tags | S-09 | V1 |
| `mood/summary` | Read per-day values for a range | S-09, S-11 | MVP |

`sleep`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `sleep/episodes`, `sleep/episodes/{id}` | List by range; add; correct (new revision); remove | S-10, S-13 | V1 |
| `sleep/checkins` | Command: going to sleep, awake, nap start, nap end | S-10 | V1 |
| `sleep/estimates/{sleepDay}` | Read the estimate; resolve it (confirm, adjust, not sleep, stop asking) | S-10 | V1 or V2 |
| `sleep/settings` | Read; change goal, typical times, irregular schedule, work days, prompts | S-10 | V1 |
| `sleep/summary` | Read averages, variation and gated metrics for a window | S-10, S-11 | V1 |
| `sleep/activity-signals` | Report foreground start, end and input heartbeats (only with consent `sleep_passive_estimate`) | MA-SLEEP-7 | V2 |

`insights-and-correlations`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `insights/overview` | Read one block per enabled domain for a range, with text summaries and minimum-data state | S-11 | V1 |
| `insights/series/{metric}` | Read the chart for a range as a scene (JSON) with its text summary | S-11 drill-down | V1 |
| `insights/calendar` | Read per-day cell values for a domain and month, or month totals for a year | S-12 | MVP |
| `insights/correlations`, `insights/correlations/{id}/explanation` | Read descriptive cards and how each is counted | S-11 | V2 |

`visual-reports`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `report-cards` | Read the catalogue: kinds, available periods, progress to minimum data | S-14 | V1 |
| `report-cards/{kind}/renders` | Create a render job for a period, theme and names option | S-14 | V1 |
| `renders/{id}`, `renders/{id}/image` | Read job state and text equivalent; read the PNG | S-14 | V1 |
| `report-cards/{kind}/deliveries` | Send the card to the user's chat | S-14 | V1 |
| `report-schedule` | Read; change the weekly report day and time | S-14, S-15 | V1 |

`import-export`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `exports` | Create a full export (step-up) or the record summary PDF (kind `record_summary_pdf`); list | S-14 | MVP full export; V2 record summary (deferred) |
| `exports/{id}` | Read state | S-14 | MVP |
| `exports/{id}/download-tickets` | Create a download ticket URL (`/downloads/{ticket}`) | MA-NATIVE-13 | MVP |
| `exports/{id}/chat-deliveries` | Send the file to the chat (explicit opt-in; consent `export_to_chat`) | S-14 | V1 |
| `import-templates/{kind}` | Download a template | S-14 | V2 |
| `imports`, `imports/{id}` | Create an import from normalised entries; read state and result | S-14 | V2 |

`sharing-and-caregivers`

| Resource | Operations | Used by | Wave |
|---|---|---|---|
| `grants`, `grants/{id}` | List; create (step-up); read with access list; end | S-16 | V2 |
| `grants/{id}/invitations` | Create the single-use link and the prepared message | S-16 | V2 |
| `shared-with-me`, `shared-with-me/{grantId}` | List; read the scoped summary | S-16 | V2 |

MA-API-1. Every screen of section 7 can be built from the resources above without any other call. A resource added later is added to this section in the same change. [O] review

MA-API-2. The Mini App needs no resource that returns another account's data, a Telegram file URL, the bot token, or a Telegram user id other than as part of the session exchange. [O] review

## 16. Suggested delivery order for the Mini App

ROADMAP.md decides. This is the dependency order as seen from the Mini App. Each slice is usable on its own and ends with its screens passing the gates of section 14.

| Slice | Content | Depends on |
|---|---|---|
| A | Spikes MA-SPK-1 to MA-SPK-5 | A registered test bot and origin |
| B | Shell: build, hosting, `TelegramPort` with fake, theme adapter, session exchange, gate screens, router, tab bar, error boundary, all continuous-integration gates with empty screens | Session endpoint; slice A |
| C | Registry: S-02, S-03, S-04 with drafts and conflict handling | `medication-registry` resources |
| D | Schedule builder for the MVP patterns: S-05 | `schedule-previews`, schedule resources |
| E | Today and Day detail: S-01, S-13; change feed; offline queue | `today`, `intake-log` resources; the reminder loop running in the executable (R01 section 1 item 5) |
| F | Settings core, consent view, full export, deletion: S-15, part of S-14; step-up | `identity-and-consent`, `exports` |
| G | Hydration, Mood, Log hub, History calendar: S-06, S-07, S-09, S-12 | `hydration`, `mood`, `insights/calendar` |
| H | Sleep: S-10 | `sleep` resources |
| I | Food diary: S-08 | `nutrition` resources |
| J | Insights and report cards: S-11, rest of S-14 | `insights-and-correlations`, `visual-reports` |
| K | Further schedule patterns; inventory and refills | Engine support for those patterns (R01 section 6.3, slices M7.1 to M7.3) |
| L | Correlation cards, import, sharing, app lock | V2 modules |

Slices B to F are the Mini App part of wave MVP as PRODUCT_SPEC.md defines it, with G completing it; H to K belong to V1; L to V2.

## 17. Spikes

Each spike is one issue with a recorded verdict, in the style of the repository's existing spikes.

| Id | Question | Confirms | Pass criterion | If it fails |
|---|---|---|---|---|
| MA-SPK-1 | Do the three launch paths deliver a route and a signed `initData`, and does relaunch work? | MA-A-01, MA-A-02, MA-A-03, MA-A-12, MA-A-18 | On Android, iOS, Desktop and Web: the `route` query survives; `startapp` arrives; `signature` is present; a reload restores the screen; `openTelegramLink` to the own `startapp` link yields a newer `auth_date`; the measured behaviour of a return from the minimised state after 20 minutes and of an operating-system reload (MA-SESSION-4) | Routes move into the URL path of the button URL; gate screen G-4 keeps only the manual instruction; MA-Q-4 is reopened |
| MA-SPK-2 | What does each WebView support? | MA-A-04, MA-A-05, MA-A-06, MA-A-07, MA-A-14, MA-A-16, MA-A-19 | A recorded capability table per client for: device time zone, time and date pickers, file input, back gesture, `BiometricManager`, `addToHomeScreen`, `downloadFile`, `deactivated`. For one `downloadFile` call per client: the number of HTTP requests the client sends to the ticket URL, their methods (`GET`, `HEAD`) and any `Range` header, logged on the server | The corresponding fallback of this document becomes the only path on that client |
| MA-SPK-3 | Do assistive technology, text scaling and the privacy curtain work inside the WebView? | MA-A-08, MA-A-09, MA-A-20 | TalkBack and VoiceOver read a test form and reach the bottom button; text follows the system size; the task-switcher snapshot shows the curtain | The accessibility target is restated per platform; the curtain is described as partial |
| MA-SPK-4 | Does the content security policy hold on every client? | MA-A-10, MA-A-11 | A test page with the full policy loads on every client with only the known Telegram Web exception; the `frame-ancestors` list is recorded | ARCHITECTURE.md adjusts the policy with a stated reason; MA-SEC-1 is amended |
| MA-SPK-5 | Does the chosen stack meet the budget on the reference device? | MA-A-13, MA-A-17 | A prototype of the shell and Today in Svelte 5 with the query library: initial script at most 70 KB gzip; "Today interactive" within the cold budget on the lab profile and on the reference device; zero policy violations | Replace the query library with an in-repository cache; if still failing, switch to SolidJS |
| MA-SPK-6 | Can a phone parse a large health export? | MA-REP-6 | A 300 MB XML file is reduced to normalised sleep entries on Telegram Desktop within 2 minutes without exceeding 500 MB of memory; the same measured on a current phone | Device-export import stays desktop-only or moves to a later wave |

## 18. Open questions

Each entry needs the owner's decision. The recommendation is the default this document assumes.

| Id | Question | Recommendation |
|---|---|---|
| MA-Q-1 | Frontend technology: Svelte 5 with TypeScript, or Scala.js with Laminar to keep one language and one build tool? | Svelte 5 with TypeScript, for the phone budget and the test tooling (section 12). Choose Scala.js only if one language matters more to the owner than the low-end phone target. |
| MA-Q-2 | Minimum Telegram client and signature method. R06 recommends Ed25519 only, which needs Bot API 8.0 clients; R02 recommends HMAC first. | Ed25519 only and a minimum of 8.0 (section 3.3). Measure the share of refused launches during the invite-only phase before deciding on an HMAC fallback. |
| MA-Q-3 | No health data in any client-side store. The cost: an action queued while offline is lost if the app is closed before reconnecting, and the registry cannot be read offline at launch. | Accept. The alternative keeps medication names and intake times in `DeviceStorage`, outside the server's deletion and encryption controls. |
| MA-Q-4 | Session lifetime as experienced: R06 proposes 15 minutes idle and 12 hours absolute, and each expiry requires a relaunch. | Keep the values; the client keeps the session alive only while the user is active, saves drafts before expiry, and offers a one-tap relaunch. If MA-SPK-1 shows that relaunch is not seamless on every tested client, the idle timeout of a session bound to a live WebView equals the absolute lifetime of 12 hours (MA-SESSION-4, ARCHITECTURE API-06). |
| MA-Q-5 | Mood scale. R04 defines 7 valence by 5 energy levels for the blob; R03 suggests five buttons in chat; the existing column stores 1 to 10. | Settled by the review (ROADMAP conflict X-01): five valence steps and five optional energy steps are captured and stored on every surface, with the labels of PRODUCT_SPEC R-MOO-1 and R-MOO-2. The Mini App renders what `mood/scale` returns, which is that five-step scale; the seven-column geometry is used only for drawing and for averaged states. |
| MA-Q-6 | App lock on a device without biometrics (Telegram Desktop and Web, MA-A-14). | Require a confirmation in chat for each session on such a device. Rejected: refusing those devices entirely; allowing them without the lock. |
| MA-Q-7 | Retention of the raw activity times used for sleep estimates. R05 proposes 35 days (needed for the learned rest-phase prior); R06 proposes 48 hours. The opt-in text in S-10 must state one value. | Settled by PRODUCT_SPEC R-SLP-16 (ROADMAP conflict X-08): raw instants are kept for 48 hours and an hourly profile of counts for 14 days. The opt-in text of S-10 states these two periods; the Mini App shows the values the API reports. The first draft of this document recommended 35 days; that is withdrawn. |
| MA-Q-8 | Which physical low-end Android phone is the reference device, and which clients can the owner test on? | Name one phone that reports performance class `LOW` and keep it for the life of the budget. Without one, the lab profile is the only gate and section 10 is weaker. |
| MA-Q-9 | One shared design-token file for the Mini App and the server renderer? | Yes. One JSON file in the repository generates `tokens.css` and the renderer's token sets, so the chart colours and state encodings of both surfaces cannot drift (MA-DS-7). VISUALS_AND_ANALYTICS.md owns the values. |

## 19. Not checked, and the risk

1. No Telegram client was used for this document. Every statement about WebView behaviour that is not in section 2.1 is an assumption (section 2.2) until the spikes run. The largest risks are MA-A-02 (relaunch after session end), MA-A-08 (screen readers inside the WebView) and MA-A-10 (content security policy against the Telegram script).
2. The Telegram script was read in the parts that concern storage, theme variables, style injection and start-up defaults (TGF-37 to TGF-39). It is unversioned and can change without notice; a scheduled check should re-read those parts when the Bot API changelog changes.
3. The sibling documents were being written in parallel. Only the first part of PRODUCT_SPEC.md (principles, surface allocation, waves, non-goals) was available. Resource names, wave labels, correction periods, the mood scale and the wording of standing sentences must be reconciled with PRODUCT_SPEC.md, BOT_UX.md, ARCHITECTURE.md and VISUALS_AND_ANALYTICS.md before this document is accepted.
4. ADR-011 and ADR-012 were not re-read. The schedule builder and the correction sheets defer to them for daylight-saving rules and for which corrections are allowed.
5. Framework sizes and timings were not measured (MA-A-13). The budget of section 10 is a target until MA-SPK-5.
6. Contrast figures for the fallback and data tokens were computed by hand with the WCAG formula. The token lint of section 14 must confirm them.
7. Package versions in TGF-40 are the latest published on 2026-11-12, not versions tested together. Compatibility of the Svelte tooling with the current TypeScript major is an assumption.
8. The Measurements and Symptoms screens of wave V2 (`vitals-and-symptoms`) are named in the navigation map and not specified; S-17 and S-18 are written at ROADMAP checkpoint CP9-d.
10. After the cross-document review of 2026-11-12 this document was amended in place (REVIEW.md lists every change): platform facts renamed to `TGF-nn`, one chart mechanism (scenes), the per-chat menu button, the error code `account_required`, the vessel instead of a ring, mood on the stored 1 to 5 scale with the daily mean, the notification and hydration values of PRODUCT_SPEC, and resource names owned by ARCHITECTURE section 9.5. Screen sketches were not redrawn; where a sketch shows a superseded value, the requirement text wins.
9. Legal wording shown in the Mini App (consent texts, the non-clinical statement, deletion limits) is information from R06, not legal advice, and needs the review that R06 requires before a public beta.
