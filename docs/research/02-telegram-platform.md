# Research brief 02: Telegram platform capability and constraint reference

Status: research evidence for the Dosegram plan. Not a design document and not product code.
Date checked: 2026-11-12. Every source in section 1 was fetched on that date.
Scope: what the Telegram Bot Platform (Bot API, Mini Apps, widgets, payments, terms) lets a
medication, water, food, mood and sleep tracking bot do, and what it prevents.

## 0. How to read this document

### 0.1 Claim labels

Every statement about Telegram behaviour carries one of two labels.

- `V[Sn]` means VERIFIED: the statement was read in primary source `Sn` (section 1) on 2026-11-12.
- `A` means ASSUMPTION: the statement is common knowledge, an inference, or third-party
  information that was not found in a primary source during this run. An assumption must be
  confirmed by a spike before a design depends on it. Section 24 lists the spikes.

"Verified by absence" means the relevant primary reference page was read in full and the field,
parameter or method does not exist in it. It is weaker than a positive statement and is flagged
where used.

### 0.2 Method

The primary sources were downloaded as raw files (the machine-readable `.md` version that
`core.telegram.org` now publishes for each page, plus the HTML of `telegram.org/tos/*`) and read
directly, not through a summariser. Per-method and per-type pages were read for every method and
type cited here. Library maintenance data came from the GitHub REST API on the same date.

### 0.3 Most important findings

1. The current Bot API is **10.4, released 2026-11-09** `V[S2]`. The platform changed heavily in
   2026: rich messages (10.1), ephemeral messages (10.2), message widgets (10.4), streaming drafts
   (9.3, 9.5), private-chat topics (9.3, 9.4), button styles (9.4), `date_time` entities (9.5).
2. **The Bot API exposes no "last seen", online status or presence of a user**, and **no time
   zone** (verified by absence in `User`, `ChatFullInfo`, `WebAppUser`) `V[S3][S6]`. The owner's
   idea of deriving sleep from last seen cannot be built on the Bot API. Section 19 gives the
   buildable substitute.
3. **A bot cannot start a conversation** `V[S9]` and cannot override a user's notification
   settings `A`. A reminder is an ordinary chat message. There is no alarm-grade delivery.
4. **Broadcast capacity is about 30 messages per second per bot, 1 per second per chat**
   `V[S5]`. Reminder storms at popular local times must be queued and smoothed.
5. **Checklists are available only through a Telegram Business connection** `V[S3]`, so they
   cannot be the daily dose checklist of an ordinary bot.
6. **Message widgets** (interactive HTML inside a chat bubble, no network access, 32 KB HTML,
   4 KB compressed) shipped three days before this brief and in production work only in the
   private chat with the bot `V[S8]`. This is a direct fit for a "mood blob" or a water counter,
   but it is new and client support is unproven `A`.
7. Digital goods and subscriptions must be sold in Telegram Stars (`XTR`) only `V[S10][S11]`.
8. Developer terms require encryption at rest with separately stored keys, deletion on request,
   data minimisation and an accessible privacy policy `V[S11]`. No clause mentions health data
   specifically (verified by absence in S11, S12, S13); general data-protection law applies
   `V[S11]`.

## 1. Source index

All URLs were fetched on 2026-11-12.

| Key | Source | URL |
| --- | --- | --- |
| S1 | Bot API reference (index, sending files, local server, paid broadcasts, ephemeral rules) | https://core.telegram.org/bots/api |
| S2 | Bot API changelog | https://core.telegram.org/bots/api-changelog |
| S3 | Bot API per-method and per-type pages | https://core.telegram.org/bots/api/methods/<name>.md and https://core.telegram.org/bots/api/types/<name>.md |
| S4 | Bot features guide | https://core.telegram.org/bots/features |
| S5 | Bots FAQ | https://core.telegram.org/bots/faq |
| S6 | Mini Apps guide and `Telegram.WebApp` reference | https://core.telegram.org/bots/webapps |
| S7 | Webhook guide | https://core.telegram.org/bots/webhooks |
| S8 | Widgets guide and `TelegramWidget` reference | https://core.telegram.org/bots/widgets |
| S9 | Bots introduction | https://core.telegram.org/bots |
| S10 | Bot Payments API for digital goods and services | https://core.telegram.org/bots/payments-stars |
| S11 | Bot Platform Developer Terms of Service | https://telegram.org/tos/bot-developers |
| S12 | Terms of Service for Mini Apps | https://telegram.org/tos/mini-apps |
| S13 | Terms of Service for Bots | https://telegram.org/tos/bots |
| S14 | Standard Bot Privacy Policy | https://telegram.org/privacy-tpa |
| S15 | Telegram Privacy Policy, section 6 (read through a fetch summariser because the direct download was geo-redirected to a translated page; treat field lists as paraphrase) | https://telegram.org/privacy |
| S16 | Formatting options | https://core.telegram.org/bots/api/formatting |
| S17 | Rich messages reference | https://core.telegram.org/bots/api/rich-messages |
| S18 | Deep links reference | https://core.telegram.org/api/links |
| S19 | Telegram Serverless | https://core.telegram.org/bots/serverless |
| S20 | Telegram API (MTProto) Terms of Service | https://core.telegram.org/api/terms |
| S21 | Local Bot API server source | https://github.com/tdlib/telegram-bot-api |
| S22 | GitHub REST API repository and release metadata for the libraries in section 22 | https://api.github.com/repos/<owner>/<repo> |

## 2. Platform version

| Item | Value | Label |
| --- | --- | --- |
| Latest Bot API version | 10.4 | `V[S2]` |
| Release date | 2026-11-09 | `V[S2]` |
| Local Bot API server version in `CMakeLists.txt` on master | 10.4 | `V[S21]` |
| Versioning model | The Bot API is not versioned per request. Every bot always talks to the newest server. New fields and enum values appear without notice. | `A` (stated in a third-party library README; consistent with S1) |
| Mini App client capability check | `Telegram.WebApp.version` and `isVersionAtLeast(version)` report the Bot API level the user's app supports | `V[S6]` |

Releases that matter to this product, newest first `V[S2]`:

| Version | Date | Relevant additions |
| --- | --- | --- |
| 10.4 | 2026-11-09 | Message widgets: `sendWidget`, `sendSharedWidget`, `answerWidgetQuery`, `editMessageWidgetData`, `editSharedWidgetData`, updates `widget_query` and `shared_widget_query`. Ephemeral replies to callback queries in any chat except channel direct-message chats. Private-chat topic links `https://t.me/<bot_username>/<message_thread_id>`. |
| 10.3 | 2026-08-24 | `EphemeralMessageParameters` (replaces `receiver_user_id` and `callback_query_id` parameters), `replace_callback_query_message`, `DisabledButton` and `InlineKeyboardButton.disabled`, `force_reply` on `InlineKeyboardMarkup` and `ReplyKeyboardMarkup`, rich message buttons, `can_stop` and `keep_on_stop` on drafts, update `stopped_message_generation`. |
| 10.2 | 2026-07-14 | Ephemeral messages and `BotCommand.is_ephemeral`. Update `subscription` (`BotSubscriptionUpdated`). Mini App origin hardening, enabled for all Mini Apps on 2026-07-20. |
| 10.1 | 2026-06-11 | Rich messages: `sendRichMessage`, `sendRichMessageDraft`, `rich_message` on `editMessageText`. |
| 10.0 | 2026-05-08 | Guest mode. `deleteMessageReaction`, `deleteAllMessageReactions`. Poll media. Business bots no longer require the account owner to have Premium. Bot-to-bot messaging. |
| 9.6 | 2026-04-03 | Managed bots. Poll changes (`correct_option_ids`, `allows_revoting`, `open_period` up to 2628000 s). `savePreparedKeyboardButton`, `WebApp.requestChat`. |
| 9.5 | 2026-03-01 | `MessageEntity` type `date_time`. `sendMessageDraft` allowed for all bots. `BottomButton.iconCustomEmojiId`. |
| 9.4 | 2026-02-09 | `style` and `icon_custom_emoji_id` on `KeyboardButton` and `InlineKeyboardButton`. Bots can create topics in private chats. `setMyProfilePhoto`, `removeMyProfilePhoto`. |
| 9.3 | 2025-12-31 | Topics in private chats (`has_topics_enabled`), `sendMessageDraft`. |
| 9.1 | 2025-07-03 | Checklists (`sendChecklist`, `editMessageChecklist`), `getMyStarBalance`, `WebApp.hideKeyboard`, poll options raised to 12. |
| 9.0 | 2025-04-11 | `DeviceStorage`, `SecureStorage`. Business account management methods. |
| 8.0 | 2024-11-17 | Star subscriptions (`subscription_period`, `editUserStarSubscription`). Mini App fullscreen, home-screen shortcut, emoji status, `shareMessage`, `downloadFile`, `LocationManager`, motion sensors, `savePreparedInlineMessage`. |
| 7.11 | 2024-10-31 | `CopyTextButton`, `allow_paid_broadcast`. |
| 7.10 | 2024-09-06 | `SecondaryButton`, `setBottomBarColor`. |
| 7.4 | 2024-05-28 | Telegram Stars (`XTR`), `refundStarPayment`, `message_effect_id`. |
| 7.2 | 2024-03-31 | Business connections, `BiometricManager`. |
| 7.0 | 2023-12-29 | Reactions, `ReplyParameters`, `LinkPreviewOptions`, `deleteMessages`, `KeyboardButtonRequestUsers`. |

Consequence: a client library or codec that is one release behind already misses update kinds.
The decoder must tolerate unknown fields, unknown update kinds and unknown enum values.

## 3. Request model

| Fact | Label |
| --- | --- |
| Endpoint form is `https://api.telegram.org/bot<token>/METHOD_NAME`. The token is part of the URL path. | `V[S1]` |
| GET and POST are accepted. Parameters may be a URL query string, `application/x-www-form-urlencoded`, `application/json` (not for file upload) or `multipart/form-data` (required for upload). | `V[S1]` |
| The response is JSON with Boolean `ok`. On success the value is in `result`. On failure there are `description`, Integer `error_code` (contents "subject to change"), and optionally `parameters` of type `ResponseParameters`. | `V[S1]` |
| `ResponseParameters.retry_after` is the number of seconds to wait after exceeding flood control. `ResponseParameters.migrate_to_chat_id` reports a group migration. | `V[S3]` |
| Method names are case-insensitive. All requests must be UTF-8. | `V[S1]` |
| Identifiers of users and chats can exceed 32 bits and have at most 52 significant bits. A signed 64-bit integer is required. | `V[S3]` |
| `MessageEntity.offset` and `length` are counted in UTF-16 code units. | `V[S3]` |
| A webhook handler may answer the HTTP request with a method call in the response body (field `method`). The result of that call cannot be observed. | `V[S1][S5]` |
| The test environment uses `https://api.telegram.org/bot<token>/test/METHOD_NAME` and separate accounts and bots. Flood limits are not raised there and may be stricter. | `V[S4][S6]` |

Because the token sits in the URL path, request URLs must never be logged. This matches the
repository rule against logging credentials.

## 4. Receiving updates

### 4.1 Common rules

| Fact | Label |
| --- | --- |
| `getUpdates` and webhooks are mutually exclusive. | `V[S1][S3]` |
| Undelivered updates are stored on Telegram's side for at most 24 hours. | `V[S1]` |
| `Update.update_id` increases sequentially and is meant for de-duplication and re-ordering with webhooks. After a week without updates the next identifier is chosen randomly. | `V[S3]` |
| At most one optional field is present in an `Update`. | `V[S3]` |
| `allowed_updates` is a JSON list of update type names. An empty list means every type except `chat_member`, `message_reaction` and `message_reaction_count`. If the parameter is omitted the previous setting is kept. A change does not affect updates created before the call. | `V[S3]` |

Update types in Bot API 10.4 `V[S3]`: `message`, `edited_message`, `channel_post`,
`edited_channel_post`, `business_connection`, `business_message`, `edited_business_message`,
`deleted_business_messages`, `guest_message`, `message_reaction`, `message_reaction_count`,
`inline_query`, `chosen_inline_result`, `callback_query`, `widget_query`, `shared_widget_query`,
`shipping_query`, `pre_checkout_query`, `purchased_paid_media`, `poll`, `poll_answer`,
`my_chat_member`, `chat_member`, `chat_join_request`, `chat_boost`, `removed_chat_boost`,
`managed_bot`, `subscription`, `stopped_message_generation`.

Types this product needs: `message`, `edited_message`, `callback_query`, `my_chat_member`,
`message_reaction`, `pre_checkout_query` (if payments), `subscription` (if payments),
`widget_query` (if widgets), `poll_answer` (if polls). All others should be excluded through
`allowed_updates`.

`my_chat_member` in a private chat is delivered only when the user blocks or unblocks the bot
`V[S3]`. It is the only authoritative signal that reminders can no longer be delivered.

### 4.2 Long polling: `getUpdates`

| Parameter | Rule | Label |
| --- | --- | --- |
| `offset` | Must be one greater than the highest `update_id` received. Calling with a higher offset confirms earlier updates. A negative offset reads from the end of the queue and forgets earlier updates. | `V[S3]` |
| `limit` | 1-100, default 100. | `V[S3]` |
| `timeout` | Seconds of long polling, default 0. Short polling is for testing only. | `V[S3]` |
| `allowed_updates` | See 4.1. | `V[S3]` |
| Concurrency | Only one `getUpdates` consumer can run per token; a second concurrent call fails with a conflict error. | `A` |

### 4.3 Webhook: `setWebhook`

| Parameter or rule | Value | Label |
| --- | --- | --- |
| `url` | HTTPS URL. Empty string removes the webhook. | `V[S3]` |
| `secret_token` | 1-256 characters, only `A-Z`, `a-z`, `0-9`, `_`, `-`. Sent back in the header `X-Telegram-Bot-Api-Secret-Token` on every delivery. | `V[S3]` |
| `max_connections` | 1-100, default 40. | `V[S3]` |
| `ip_address` | Optional fixed IP used instead of DNS resolution. | `V[S3]` |
| `drop_pending_updates` | Boolean; also on `deleteWebhook`. | `V[S3]` |
| `certificate` | `InputFile` public key for a self-signed certificate. | `V[S3]` |
| Ports | 443, 80, 88, 8443 only. | `V[S3][S7]` |
| Network | IPv4 only. Deliveries come from `149.154.160.0/20` and `91.108.4.0/22`. | `V[S7]` |
| TLS | TLS 1.2 or newer. Redirects are not followed. Certificate CN or SAN must match the host. Wildcard certificates "may not be supported". | `V[S5][S7]` |
| Failure handling | Any response other than `2XY` is retried, and Telegram gives up "after a reasonable amount of attempts". The retry schedule is not documented. | `V[S3]` |
| Response deadline | Not documented in S3 or S7. | `A`: treat as short; acknowledge after a durable write, process asynchronously |
| Ordering | Up to `max_connections` deliveries run in parallel, so arrival order is not guaranteed; `update_id` restores order. | `V[S3]` for the purpose of `update_id`; parallel out-of-order arrival is `A` |
| Diagnostics | `getWebhookInfo` returns `url`, `has_custom_certificate`, `pending_update_count`, `ip_address`, `last_error_date`, `last_error_message`, `last_synchronization_error_date`, `max_connections`, `allowed_updates`. | `V[S3]` |

The FAQ still recommends a secret path in the webhook URL `V[S5]`. `secret_token` is the newer
mechanism and should be used in addition to, not instead of, a non-guessable path.

### 4.4 Local Bot API server

Running `tdlib/telegram-bot-api` locally changes these limits `V[S1][S4]`:

| Limit | Cloud Bot API | Local server |
| --- | --- | --- |
| File download | 20 MB | unlimited |
| File upload | 50 MB | 2000 MB |
| Webhook URL | HTTPS | HTTP allowed, any local IP, any port |
| `max_connections` | 1-100 | up to 100000 |
| `getFile` | returns a download path | returns an absolute local path |

A bot must call `logOut` before moving to a local server and then cannot return to the cloud
server for 10 minutes. `close` must be called before moving between local servers and returns
error 429 during the first 10 minutes after launch `V[S3]`.

### 4.5 Decision

Decision: use a **webhook in production** with `secret_token`, an explicit `allowed_updates`
list and a durable inbox keyed by `update_id`; use **`getUpdates` long polling for local
development and as an operator-selectable fallback**. Both feed the same inbox.

Rationale: the Mini App already forces a public HTTPS endpoint, so a webhook adds no new ingress.
A webhook has no single-consumer restriction, which keeps a second application instance possible.
A durable inbox makes the undocumented retry schedule and the unordered parallel delivery
irrelevant.

Rejected alternative: a **local Bot API server**. Its only gains are larger files and more
webhook connections. Generated report images are far below 10 MB and 100 connections exceed the
free broadcast rate. It would add a stateful C++ service, a 10-minute cloud lock-out on switching,
and a second place where the token lives.

Rejected alternative: **Telegram Serverless** (JavaScript modules, a built-in database and Mini
App hosting on Telegram infrastructure at `https://app<app_id>.tgcloud.ai/`) `V[S19]`. It is
JavaScript-only, which abandons the Scala core, and it places health records in a database the
operator does not control, which conflicts with the encryption-at-rest and key-separation duty
in S11 section 4.4 being provable by the operator.

## 5. Rate limits and flood control

| Limit | Value | Label |
| --- | --- | --- |
| Single chat | "avoid sending more than one message per second"; short bursts may be tolerated, then HTTP 429 | `V[S5]` |
| Group | no more than 20 messages per minute | `V[S5]` |
| Bulk notifications | about 30 messages per second across all chats | `V[S5][S1]` |
| Error signal | HTTP 429 with `parameters.retry_after` in seconds | `V[S3]` for the field; that every flood rejection is a 429 with this field is `A` |
| Paid broadcast ceiling | up to 1000 messages per second with `allow_paid_broadcast = true` | `V[S1][S3][S5]` |
| Paid broadcast price | 0.1 Stars per message above the free 30 per second, charged only for successful sends, non-refundable | `V[S5][S11]` |
| Paid broadcast eligibility | S5 and S11 section 6.2.5: at least 100,000 Stars on the bot balance and at least 100,000 monthly active users. S1: "at least 10,000 Stars". The sources disagree. | `V` for both texts; the effective threshold is `A` |
| Widget state edits | One `editMessageWidgetData` call counts as one sent message. A shared widget accepts at most one edit per second. | `V[S8]` |
| Widget requests | 5 per 5 seconds per user and bot; the bot must answer within 15 seconds | `V[S8]` |
| Limits for edits, `answerCallbackQuery`, `sendChatAction`, `getFile` | Not documented | `A`: assume they share the per-chat and global budgets |
| Test environment | Flood limits are not raised and may be stricter; bots must "not depend on hardcoded limit values" | `V[S4]` |
| Circumvention | A bot "must not attempt to circumvent or otherwise undermine Telegram rate limits", for example by spreading load across tokens | `V[S11]` section 5.2(f) |

Capacity arithmetic: at 30 messages per second, 10,000 users who all have an 08:00 reminder in
the same time zone need about 5.5 minutes to drain if each reminder is one message. A reminder
that is a photo plus a follow-up message halves that capacity.

BotFather status alerts monitor popular bots (about 300 requests per minute) for a low
reply-to-message ratio and for unanswered callback queries, with at most one alert per bot per
hour `V[S4]`. Every incoming message and every callback query must therefore receive a response.

## 6. Message types and delivery features

### 6.1 Text

| Fact | Label |
| --- | --- |
| `sendMessage.text` is 1-4096 characters after entity parsing. | `V[S3]` |
| Parse modes are `MarkdownV2`, `HTML` and legacy `Markdown`. Entities can instead be passed explicitly in `entities`. | `V[S16]` |
| Entity types include `bold`, `italic`, `underline`, `strikethrough`, `spoiler`, `blockquote`, `expandable_blockquote`, `code`, `pre`, `text_link`, `text_mention`, `custom_emoji`, `date_time`. `blockquote` and `expandable_blockquote` cannot be nested. | `V[S3][S16]` |
| Custom emoji in a bot's own messages require the bot owner to have Telegram Premium or the bot to own an additional username bought on Fragment. | `V[S2][S3]` |
| `protect_content` blocks forwarding and saving of the message. | `V[S3]` |
| `disable_notification` delivers the message with a silent notification. | `V[S3]` |
| `message_effect_id` adds a message effect; private chats only. | `V[S3]` |
| The list of valid effect identifiers is not in the reference. | `A`: identifiers must be captured from real messages (`Message.effect_id`) |
| `reply_parameters` (`ReplyParameters`) replies to a message; `link_preview_options` (`LinkPreviewOptions`) controls previews. | `V[S3]` |
| No send method accepts a schedule time. Scheduling is entirely the bot's job. | Verified by absence `V[S3]` |

### 6.2 The `date_time` entity

A `date_time` entity carries `unix_time` and `date_time_format`. The format string must match
`r|w?[dD]?[tT]?`: `r` relative time, `w` weekday, `d` short date, `D` long date, `t` short time,
`T` long time. In HTML it is written `<tg-time unix="1647531900" format="wDT">fallback</tg-time>`
and in MarkdownV2 `![fallback](tg://time?unix=1647531900&format=wDT)`. With an empty format the
text is shown as written but "the user can still receive the underlying date in their local
format" `V[S16][S3]`.

That the client renders the instant in the device's own time zone and locale is `A` (strongly
implied by the wording, not stated). If confirmed, it lets every reminder and report show a
correct local time even before the user's time zone is known to the backend.

### 6.3 Photos, albums and documents

| Fact | Label |
| --- | --- |
| `sendPhoto`: at most 10 MB; width plus height at most 10000; aspect ratio at most 20; caption 0-1024 characters; `has_spoiler`; `show_caption_above_media`. | `V[S3]` |
| Three ways to send a file: an existing `file_id` (no size limit), an HTTP URL that Telegram downloads (5 MB for photos, 20 MB otherwise), or multipart upload (10 MB for photos, 50 MB otherwise). | `V[S1]` |
| A `file_id` is persistent, is bound to one bot, and cannot change file type on resend. One file can have several valid `file_id` values. | `V[S1][S5]` |
| `sendDocument`: any file type up to 50 MB; thumbnail JPEG under 200 kB and at most 320 px per side; by URL only PDF and ZIP work. | `V[S3][S1]` |
| `sendMediaGroup`: 2-10 items; photos and videos can be mixed; documents only with documents, audio only with audio. | `V[S3]` |
| `editMessageMedia` replaces the media of an existing message, and can turn a text message into a media message. | `V[S3][S2]` |
| Telegram recompresses and downsizes images sent as photos, so small text in a chart can blur. | `A`: measure in a spike; `sendDocument` is the lossless fallback |
| `sendChatAction` with `upload_photo` shows a status for 5 seconds or until a message arrives. | `V[S3]` |

Consequence for generated infographics: render once, upload once by multipart, store the
returned `file_id` against a content hash, and resend by `file_id`. A "today" card can be one
long-lived message whose image is replaced with `editMessageMedia` instead of a new message per
change.

### 6.4 Polls

`sendPoll`: question 1-300 characters; 1-12 options of 1-100 characters; `is_anonymous` defaults
to true; `type` is `regular` or `quiz`; `allows_multiple_answers`; `allows_revoting`;
`open_period` 5-2628000 seconds; description 0-1024 characters `V[S3]`. A bot receives
`poll_answer` only for non-anonymous polls it sent itself `V[S3]`.

Decision: do not use polls for mood or symptom check-ins; use inline keyboards. Rationale: a
keyboard message can be edited in place into its result and carries an exact payload. Rejected
alternative: non-anonymous polls, which cannot be restyled, leave a permanent poll object in the
chat and add a second answer path (`poll_answer`).

### 6.5 Checklists

`sendChecklist` and `editMessageChecklist` both require `business_connection_id`; they send "on
behalf of a connected business account" `V[S3]`. A checklist has a title of 1-255 characters and
1-30 tasks of 1-100 characters `V[S3]`. An ordinary bot cannot send a checklist in its own
private chat. The daily dose list must be built from inline keyboard buttons, a rich message, or
a widget.

### 6.6 Reactions

| Fact | Label |
| --- | --- |
| `setMessageReaction` lets a bot set at most one reaction per message; `is_big` plays a large animation; bots cannot use paid reactions. | `V[S3]` |
| The allowed emoji set is fixed and includes the pill emoji (U+1F48A), thumbs up, party popper, hundred points and trophy. | `V[S3]` (`ReactionTypeEmoji`) |
| `message_reaction` updates must be requested explicitly in `allowed_updates`. The reference says the bot "must be an administrator in the chat". | `V[S3]` |
| A bot receives `message_reaction` for reactions a user places on messages in the private chat with the bot. | `A`: the reference wording does not cover private chats; needs a spike |

A bot reaction is a zero-message acknowledgement: reacting to the user's own "taken" message does
not consume a send from the broadcast budget in the way a reply does `A`.

### 6.7 Streaming drafts

`sendMessageDraft(chat_id, draft_id, text, ...)` streams a partial message to a private chat. The
draft is "a temporary 30-second preview"; the bot must call `sendMessage` to persist the result.
An empty text shows a "Thinking..." placeholder. `can_stop` shows a stop button that produces a
`stopped_message_generation` update `V[S3]`. Version 9.5 "allowed all bots to use the method"
`V[S2]`, while S11 section 6.2.6 ties "the streaming of message drafts" to enabling topics in
private chats; the sources are not fully consistent, so availability without topics is `A`.

Relevance: only if a later slice adds generated text (for example a weekly narrative summary).

### 6.8 Rich messages

`sendRichMessage` sends structured content given as `html`, `markdown` or `blocks` (exactly one)
`V[S3]`. Limits: 32768 UTF-8 characters, 500 blocks, 16 nesting levels, 50 media attachments, 20
table columns `V[S17]`. Supported structure includes headings, lists and task lists, tables with
alignment and spans, collapsible details blocks, media blocks, maps, formulas and embedded buttons
`V[S4][S17]`. `editMessageText` accepts `rich_message` `V[S3]`.

Relevance: a weekly report with a real table and collapsible sections, without rendering an
image. How clients older than Bot API 10.1 display a rich message is `A`.

### 6.9 Message widgets

A widget is one self-contained HTML document drawn inside the message bubble `V[S8]`.

| Property | Value | Label |
| --- | --- | --- |
| Size | at most 32768 bytes of UTF-8 HTML and at most 4096 bytes after zlib level 9 | `V[S8][S3]` |
| Engine | QuickJS sandbox, not a WebView; ES2023; DOM subset; Canvas 2D; inline SVG; CSS flex and grid | `V[S8]` |
| Network | none; `fetch`, `XMLHttpRequest`, `WebSocket` throw `NetworkDisabledError`; images only as `data:` URLs (not SVG `data:`), at most 1024 x 1024 px | `V[S8]` |
| Input | taps and pointer events only; no keyboard, no form controls, no scrolling inside the widget | `V[S8]` |
| Geometry | 200 px to the bubble maximum wide (phones about 320-430 px); 50-600 px tall; overflow is clipped | `V[S8]` |
| State | `chat_data` and `public_data`, each up to 1024 bytes of text, written only by the bot | `V[S8]` |
| State change | `editMessageWidgetData`; silent (no "edited" mark, no notification, no chat-list bump) | `V[S8]` |
| Request to bot | `TelegramWidget.request(data)` with 1-64 bytes; arrives as `widget_query`; answered by `answerWidgetQuery` with up to 1024 bytes within 15 seconds; requires `allow_queries` at send time | `V[S8]` |
| Background requests | only in the private chat with the bot and only while the widget is visible: one when it appears, then at most one per 5 seconds | `V[S8]` |
| Local storage | `localStorage` 4 KB per message per device, never sent to the bot | `V[S8]` |
| Code | fixed at send time; no method replaces the HTML of a sent widget | `V[S8]` |
| Production availability | private chat with the bot only; sent widgets are automatically protected content and cannot be forwarded; groups, inline mode and forwards are test-server only | `V[S8][S3]` |
| Unsupported clients | show the message "as unsupported media with its caption" | `V[S8]` |
| Copying | widget messages cannot be copied with `copyMessage` | `V[S3]` |
| Identity | `from` in a `widget_query` is authenticated by Telegram and cannot be forged; `data` is untrusted | `V[S8]` |
| Runtime limits | 300 ms of JavaScript per second; a single task over 500 ms stops the widget; 8 MB memory; 2000 DOM nodes; 400 CSS rules; canvas total 2048 x 2048 px | `V[S8]` |

Two privacy-relevant statements from S8: "In the private chat, background requests show when the
user has the chat open with the widget on screen", and a widget's code may put "its language,
theme, text size, bubble width, time zone" into the 64 bytes of request data.

Relevance: a mood selector with an animated blob, a water counter with tap-to-add, or a dose card
that flips to "taken" can each be one widget message that updates silently. Widgets are three
days old; client coverage across iOS, Android, Desktop and Web is `A`.

### 6.10 Ephemeral messages

Ephemeral messages are visible only to one user and the bot. In 10.4 a bot can send an ephemeral
reply to a callback query "in any chat except channel direct messages chats" by passing
`ephemeral_message_parameters.callback_query_id` without `receiver_user_id`, within 15 seconds of
the action; `replace_callback_query_message` shows the reply in place of the original message.
Delivery is "not guaranteed", and ephemeral messages "may disappear automatically after some
time, or if the app is restarted" `V[S1][S2][S3]`. Inline `web_app` and `login_url` buttons are
not supported in ephemeral messages `V[S3]`.

Relevance: a transient confirmation or hint after a button press that should not stay in the chat
history. Never the carrier of anything the user must be able to find again.

### 6.11 Topics in private chats

A bot can enable forum-topic mode for its private chats in BotFather; `getMe` then returns
`has_topics_enabled`, and `createForumTopic` works in a private chat (topic name 1-128
characters) `V[S2][S3][S4]`. Enabling it makes "all subsequent Telegram Stars purchases carried
out within that TPA" subject to a non-refundable 15 percent fee `V[S11]` section 6.2.6.

Relevance: separate threads for medication, water, mood and reports would reduce clutter, at the
cost of the fee once anything is sold.

### 6.12 Other

| Item | Fact | Label |
| --- | --- | --- |
| Pinning | In private chats a bot can pin any non-service message with `pinChatMessage`; notifications for pins are always disabled in private chats. | `V[S3]` |
| Location | `KeyboardButton.request_location` (private chats only) sends the user's current location. | `V[S3]` |
| Live location | `live_period` 60-86400 seconds or `0x7FFFFFFF`. | `V[S3]` |
| Dice | `sendDice` with a fixed emoji set; not relevant. | `V[S3]` |
| Bot profile | `setMyProfilePhoto`, `removeMyProfilePhoto`. | `V[S3]` |

## 7. Keyboards and buttons

### 7.1 Inline keyboards

`InlineKeyboardButton` fields `V[S3]`:

| Field | Rule |
| --- | --- |
| `text` | label |
| `callback_data` | 1-64 **bytes** |
| `url` | HTTP or `tg://` URL |
| `web_app` | `WebAppInfo{url}`; private chats between a user and the bot only; not in business or ephemeral messages |
| `login_url` | `LoginUrl`; not in ephemeral messages |
| `switch_inline_query`, `switch_inline_query_current_chat`, `switch_inline_query_chosen_chat` | inline mode hand-off |
| `copy_text` | `CopyTextButton{text}` with 1-256 characters copied to the clipboard |
| `pay` | must be the first button of the first row; invoice messages only |
| `style` | `danger` (red), `success` (green) or `primary` (blue); otherwise app default |
| `icon_custom_emoji_id` | custom emoji before the label; needs a Fragment username or a Premium owner |
| `disabled` | `DisabledButton`; the button does nothing |

Exactly one action field must be set. `InlineKeyboardMarkup.force_reply` (10.3) opens the reply
interface and cannot be changed when the keyboard is edited `V[S3]`.

Callback handling `V[S3]`:

- A press produces `callback_query` with `id`, `from`, `message` (`MaybeInaccessibleMessage`),
  `chat_instance` and `data`. "The message originated the query can contain no callback buttons
  with this data", so `data` is untrusted input.
- The client shows a progress indicator until `answerCallbackQuery` is called. It must always be
  called. `text` is 0-200 characters, `show_alert` turns the toast into a modal alert,
  `cache_time` allows client-side caching.
- The deadline for answering is not in the reference. `A`: answer within a few seconds; the
  existing 1.5 second acknowledgement watchdog from the Discord design is a sound default.

Limits on the number of rows and buttons are not documented `A` (commonly reported: 100 buttons
per keyboard and 8 per row).

Only messages without `reply_markup` or with an inline keyboard can be edited `V[S1]`.

### 7.2 Reply keyboards

`ReplyKeyboardMarkup` fields: `keyboard`, `is_persistent`, `resize_keyboard`,
`one_time_keyboard`, `input_field_placeholder` (1-64 characters), `selective`, `force_reply`
`V[S3]`.

`KeyboardButton` request types, all private chats only `V[S3]`:

| Field | Effect |
| --- | --- |
| `request_users` | `KeyboardButtonRequestUsers{request_id, user_is_bot, user_is_premium, max_quantity 1-10, request_name, request_username, request_photo}`; result arrives as a `users_shared` service message |
| `request_chat` | `KeyboardButtonRequestChat{...}`; result arrives as `chat_shared` |
| `request_contact` | sends the user's phone number as a contact |
| `request_location` | sends the user's current location |
| `request_poll` | `KeyboardButtonPollType` |
| `web_app` | opens a Mini App that can return data with `Telegram.WebApp.sendData` |
| `request_managed_bot` | not relevant |

`KeyboardButton` also supports `style` and `icon_custom_emoji_id`. `ReplyKeyboardRemove` removes
the keyboard. `ForceReply{force_reply, input_field_placeholder, selective}` opens the reply
interface `V[S3]`.

Relevance of `request_users`: it is the consent-preserving way to nominate a caregiver, because
the user picks the person and Telegram hands the bot the identifier.

## 8. Commands, menu button and bot profile

| Item | Rule | Label |
| --- | --- | --- |
| `setMyCommands` | at most 100 commands; `scope` defaults to `BotCommandScopeDefault`; optional two-letter ISO 639-1 `language_code` | `V[S3]` |
| `BotCommand` | `command` 1-32 characters, lowercase English letters, digits and underscores; `description` 1-256 characters; optional `is_ephemeral` | `V[S3]` |
| Scopes | `BotCommandScopeDefault`, `BotCommandScopeAllPrivateChats`, `BotCommandScopeAllGroupChats`, `BotCommandScopeAllChatAdministrators`, `BotCommandScopeChat`, `BotCommandScopeChatAdministrators`, `BotCommandScopeChatMember` | `V[S3]` |
| Resolution in the private chat | first list that exists among: `BotCommandScopeChat` + language, `BotCommandScopeChat`, `BotCommandScopeAllPrivateChats` + language, `BotCommandScopeAllPrivateChats`, `BotCommandScopeDefault` + language, `BotCommandScopeDefault` | `V[S1]` |
| Trust | Updates do not say which scope a command came from and "may contain commands that don't exist at all"; the backend must validate every command | `V[S4]` |
| Global commands | `/start`, `/help`, `/settings` are expected of every bot | `V[S4]` |
| Payment commands | `/paysupport` is mandatory for bots that sell digital goods; `/terms` and `/support` are recommended | `V[S10][S11]` |
| `setChatMenuButton` | `chat_id` optional (omit for the default); `menu_button` is `MenuButtonCommands`, `MenuButtonWebApp{type, text, web_app}` or `MenuButtonDefault` | `V[S3]` |
| Menu button and Mini App | A `MenuButtonWebApp` may carry a `t.me` link to a Mini App of the bot instead of a URL | `V[S3]` |
| `setMyName` | 0-64 characters, per language | `V[S3]` |
| `setMyDescription` | 0-512 characters, per language; shown in an empty chat | `V[S3]` |
| `setMyShortDescription` | 0-120 characters, per language; shown on the profile and when the bot is shared | `V[S3]` |

Per-user command lists (`BotCommandScopeChat`) and per-user menu buttons make it possible to
change the visible surface after onboarding, for example to show `/water` only once water
tracking is enabled.

## 9. Deep links

| Link | Behaviour | Label |
| --- | --- | --- |
| `https://t.me/<bot>?start=<param>` | Private chat receives `/start <param>`. Up to 64 characters from `A-Z a-z 0-9 _ -`; base64url is recommended for other content. | `V[S4][S18]` |
| `https://t.me/<bot>?startgroup=<param>` | Prompts to add the bot to a group. | `V[S4]` |
| `https://t.me/<bot>?startapp[=<param>][&mode=compact]` | Opens the Main Mini App. The parameter arrives in `initData.start_param` and in the GET parameter `tgWebAppStartParam`. | `V[S6]` |
| `mode=fullscreen` | Also defined for Main Mini App and direct links in the client link reference. | `V[S18]` |
| `https://t.me/<bot>/<appname>?startapp=<param>&mode=compact` | Direct-link Mini App identified by a short name; a bot can own several. | `V[S6][S18]` |
| `https://t.me/<bot>?startattach[=<param>]` | Attachment menu; restricted, see 11.1. | `V[S6]` |
| `https://t.me/<bot>/<message_thread_id>` | Opens a private-chat topic, only for the user that topic belongs to. | `V[S2]` |
| `tg://resolve?domain=<bot>&start=<param>` and `&startapp=`, `&appname=` | `tg:` equivalents. | `V[S18]` |
| `/start bizChat<user_chat_id>` | Sent when a Business user taps "Manage Bot". | `V[S4]` |

A `start` or `startapp` parameter is attacker-controllable text. It may select a screen or carry
an opaque single-use token; it must never carry an identifier that grants access by itself.

## 10. Inline mode

Inline mode must be enabled in BotFather (`/setinline`) `V[S1]`. `answerInlineQuery` returns at
most 50 results; `cache_time` defaults to 300 seconds on Telegram's servers; without
`is_personal` a cached answer "may be returned to any user who sends the same query";
`next_offset` is at most 64 bytes; `button` (`InlineQueryResultsButton`) opens a Mini App or sends
a start parameter `V[S3]`. `chosen_inline_result` updates require enabling feedback collection
`V[S3]`.

Mini Apps offer a narrower sharing path: `savePreparedInlineMessage(user_id, result, allow_user_chats,
allow_bot_chats, allow_group_chats, allow_channel_chats)` returns a `PreparedInlineMessage{id,
expiration_date}` that `Telegram.WebApp.shareMessage(msg_id)` lets the user send to a chat of
their choice `V[S3][S6]`. Whether this requires inline mode to be enabled for the bot is `A`.

Decision: keep inline mode disabled at launch. Rationale: an inline query is typed in other
people's chats and its results are cached server-side; one missing `is_personal` flag would leak
one user's health card to another. Rejected alternative: inline mode as the sharing mechanism.
Explicit sharing of a report card through `shareMessage` covers the caregiver use case with the
user choosing the recipient each time.

## 11. Mini Apps

### 11.1 Launch modes

S6 lists seven launch modes.

| Mode | How | `initData` | Can message the chat | Label |
| --- | --- | --- | --- | --- |
| Main Mini App | Configured in BotFather; "Launch app" button on the bot profile; `?startapp` link; opens full height unless `mode=compact` or BotFather says otherwise | user, `start_param`, `chat_type`, `chat_instance` | no | `V[S6]` |
| Menu button | `setChatMenuButton` with `MenuButtonWebApp`, or BotFather `/setmenubutton` | user, `query_id` | yes, via `answerWebAppQuery` | `V[S6][S3]` |
| Inline keyboard button | `InlineKeyboardButton.web_app`; private chat only | user, `query_id` | yes, via `answerWebAppQuery` | `V[S6][S3]` |
| Reply keyboard button | `KeyboardButton.web_app`; private chat only | **empty** | only through `sendData(data)`, up to 4096 bytes, which closes the app and produces a `web_app_data` service message | `V[S6]` |
| Inline mode | `InlineQueryResultsButton.web_app` | **empty** | no; returns with `switchInlineQuery` | `V[S6]` |
| Direct link | `https://t.me/<bot>/<appname>` | user, `start_param`, `chat_type`, `chat_instance` | no | `V[S6]` |
| Attachment menu | `?startattach`; "currently only available for major advertisers on the Telegram Ad Platform"; open to all bots on the test server | user, `receiver` or `chat`, `query_id` | yes | `V[S6]` |

An eighth entry point, chat join requests through a guard bot (10.1), is not relevant.

`WebAppData.data` and `WebAppData.button_text` come with the warning that "a bad client can send
arbitrary data in this field" `V[S3]`. `sendData` payloads are unauthenticated user input.

Decision: the registry and schedule manager is the bot's **Main Mini App**, also reachable from
the **menu button** and from contextual **inline `web_app` buttons** with a `startapp`-style
route. Rationale: all three deliver signed `initData`, so the backend can authenticate every API
call. Rejected alternative: reply-keyboard `web_app` buttons with `sendData`, because `initData`
is empty there, the payload is limited to 4096 bytes and it is unauthenticated.

### 11.2 Bootstrapping

The page must load `https://telegram.org/js/telegram-web-app.js` in `<head>` before other scripts
`V[S6]` (the published snippet currently carries the cache-buster `?65`). `ready()` hides the
loading placeholder and should be called as early as possible; `expand()` maximises height;
`close()` closes the app `V[S6]`.

Since 2026-07-20 Telegram disallows "the usage of Mini App methods from origins different from
the original Mini App domain" unless the developer opts out in BotFather and accepts
responsibility for untrusted links `V[S2]`. The Mini App must be a single-origin application.

### 11.3 `initData` and its validation

`WebAppInitData` fields `V[S6]`: `query_id`, `chat_join_request_query_id`, `user` (`WebAppUser`),
`receiver`, `chat`, `chat_type` (`sender`, `private`, `group`, `supergroup`, `channel`),
`chat_instance`, `start_param`, `can_send_after`, `auth_date` (Unix time the form was opened),
`hash`, `signature`.

`WebAppUser` fields `V[S6]`: `id`, `is_bot`, `first_name`, `last_name`, `username`,
`language_code`, `is_premium`, `added_to_attachment_menu`, `allows_write_to_pm`, `photo_url`.

`initDataUnsafe` is the parsed, untrusted form. Only the raw `initData` string, sent to the
backend and validated there, may be trusted `V[S6]`.

Validation with the bot token (HMAC-SHA-256) `V[S6]`:

1. Parse `initData` as a query string.
2. Build the data-check-string: every received field except `hash`, as `key=<value>`, sorted
   alphabetically by key, joined with a line feed (0x0A).
3. `secret_key = HMAC_SHA256(key = "WebAppData", message = <bot_token>)`.
4. Accept if `hex(HMAC_SHA256(key = secret_key, message = data_check_string))` equals `hash`.
5. Check `auth_date` to reject stale data.

Validation without the bot token (Ed25519, "for third-party use") `V[S6]`:

1. Build the data-check-string as `<bot_id>:WebAppData`, a line feed, then every received field
   except `hash` and `signature`, as `key=<value>`, sorted alphabetically, joined with line feeds.
2. `signature` is the base64url-encoded Ed25519 signature of that string.
3. Verify against Telegram's public key, in hex:
   - production: `e7bf03a2fa4602af4580703d88dda5bb59f32ed8b02a56c187fe7d34caed242d`
   - test environment: `40055058a4ee38156a06562e52eece92a771bcd8346a8c4615cb7376eddf72ec`
4. Check `auth_date`.

Unverified details `A`: whether `signature` is included in the HMAC data-check-string (the HMAC
text says "all received fields", which implies yes); the acceptable maximum age of `auth_date`
(Telegram states none); whether `initData` is refreshed while a Mini App stays open (assume not).

Decision: the backend validates with **HMAC-SHA-256**, compares in constant time, enforces an
`auth_date` freshness window, and exchanges a valid `initData` for its own short-lived session
token. Rationale: one backend already holds the bot token, and a session token removes the
dependence on a signature that does not refresh. Rejected alternative: **Ed25519 as the only
path**. It is the right tool when a separate web tier must authenticate users without holding the
bot token, and the codec should be written so that it can be switched on then; using it now buys
nothing and adds a second public-key constant per environment.

### 11.4 Theme, viewport and safe areas

| Item | Detail | Label |
| --- | --- | --- |
| `colorScheme` | `light` or `dark`; CSS `--tg-color-scheme` | `V[S6]` |
| `themeParams` | `bg_color`, `text_color`, `hint_color`, `link_color`, `button_color`, `button_text_color`, `secondary_bg_color` (6.1), `header_bg_color` (7.0), `bottom_bar_bg_color` (7.10), `accent_text_color` (7.0), `section_bg_color` (7.0), `section_header_text_color` (7.0), `section_separator_color` (7.6), `subtitle_text_color` (7.0), `destructive_text_color` (7.0). Each is also a CSS variable `--tg-theme-<name-with-dashes>`. All are optional. | `V[S6]` |
| Theme change | event `themeChanged` | `V[S6]` |
| Viewport | `viewportHeight`, `viewportStableHeight` (CSS `--tg-viewport-height`, `--tg-viewport-stable-height`), `isExpanded`, event `viewportChanged{isStateStable}`. Bottom-pinned elements must use the stable height. | `V[S6]` |
| Safe areas | `safeAreaInset` and `contentSafeAreaInset`, each with `top`, `bottom`, `left`, `right`; CSS `--tg-safe-area-inset-*` and `--tg-content-safe-area-inset-*`; events `safeAreaChanged`, `contentSafeAreaChanged` | `V[S6]` |
| Colours | `setHeaderColor`, `setBackgroundColor`, `setBottomBarColor` (7.10) | `V[S6]` |
| Fullscreen | `requestFullscreen()`, `exitFullscreen()`, `isFullscreen`, events `fullscreenChanged`, `fullscreenFailed{error: UNSUPPORTED or ALREADY_FULLSCREEN}` (8.0) | `V[S6]` |
| Orientation | `lockOrientation()`, `unlockOrientation()`, `isOrientationLocked` (8.0) | `V[S6]` |
| Swipes and closing | `enableVerticalSwipes()`, `disableVerticalSwipes()` (7.7); `enableClosingConfirmation()`, `disableClosingConfirmation()` (6.2) | `V[S6]` |
| Activity | `isActive`, events `activated`, `deactivated` (8.0) | `V[S6]` |
| Android device class | The User-Agent ends with `Telegram-Android/{app_version} ({manufacturer} {model}; Android {android_version}; SDK {sdk_version}; {performance_class})` where the class is `LOW`, `AVERAGE` or `HIGH` | `V[S6]` |

Design guidelines from S6: mobile-first and responsive, mimic existing native components,
animations at 60 fps, labels on all inputs and images, follow the theme colours, respect both safe
areas, and reduce effects on low performance-class Android devices `V[S6]`.

### 11.5 Native controls

| Control | API | Label |
| --- | --- | --- |
| `MainButton`, `SecondaryButton` | type `BottomButton`: `text`, `color`, `textColor`, `isVisible`, `isActive`, `hasShineEffect` (7.10), `position` for the secondary button (`left`, `right`, `top`, `bottom`), `iconCustomEmojiId` (9.5), `isProgressVisible`; methods `setText`, `onClick`, `offClick`, `show`, `hide`, `enable`, `disable`, `showProgress(leaveActive)`, `hideProgress`, `setParams`. Defaults are "Continue" and "Cancel". Events `mainButtonClicked`, `secondaryButtonClicked`. | `V[S6]` |
| `BackButton` | `isVisible`, `onClick`, `offClick`, `show`, `hide`; event `backButtonClicked` | `V[S6]` |
| `SettingsButton` | item in the Mini App context menu; `show`, `hide`, `onClick`; event `settingsButtonClicked` | `V[S6]` |
| `HapticFeedback` | `impactOccurred(light, medium, heavy, rigid, soft)`, `notificationOccurred(error, success, warning)`, `selectionChanged()` | `V[S6]` |
| Popups | `showPopup(PopupParams{title 0-64, message 1-256, buttons 1-3})`, `showAlert`, `showConfirm`; event `popupClosed` | `V[S6]` |
| QR scanner | `showScanQrPopup(ScanQrPopupParams{text 0-64})`, `closeScanQrPopup()`; events `qrTextReceived`, `scanQrPopupClosed` | `V[S6]` |
| Keyboard | `hideKeyboard()` (9.1) | `V[S6]` |
| Links | `openLink(url, {try_instant_view})`, `openTelegramLink(url)`; only in response to a user interaction | `V[S6]` |
| Clipboard | `readTextFromClipboard` works only for attachment-menu Mini Apps | `V[S6]` |

The QR scanner returns text only. Scanning a medicine pack's barcode therefore works for codes the
native scanner recognises as text; whether it reads one-dimensional EAN barcodes is `A`.

### 11.6 Storage

| Store | Capacity and scope | API | Label |
| --- | --- | --- | --- |
| `CloudStorage` | Telegram cloud, per bot per user: 1024 keys; key 1-128 characters of `A-Z a-z 0-9 _ -`; value 0-4096 characters | `setItem`, `getItem`, `getItems`, `removeItem`, `removeItems`, `getKeys` (6.9) | `V[S6]` |
| `DeviceStorage` | On the device, per bot: 5 MB per user | `setItem`, `getItem`, `removeItem`, `clear` (9.0) | `V[S6]` |
| `SecureStorage` | iOS Keychain or Android Keystore, encrypted at rest: 10 items per user | `setItem`, `getItem`, `restoreItem`, `removeItem`, `clear` (9.0) | `V[S6]` |
| `BiometricManager` | Device biometrics gate plus one stored token | `init`, `requestAccess({reason 0-128})`, `authenticate({reason 0-128})`, `updateBiometricToken`, `openSettings`; fields `isBiometricAvailable`, `biometricType` (`finger`, `face`, `unknown`), `isAccessGranted`, `isBiometricTokenSaved`, `deviceId`; events `biometricManagerUpdated`, `biometricAuthRequested`, `biometricTokenUpdated` (7.2) | `V[S6]` |

Behaviour of `SecureStorage` and `BiometricManager` on Telegram Desktop and Web is `A` (the text
describes iOS and Android only).

Decision: the backend database is the only system of record. `CloudStorage` holds non-sensitive
interface preferences only; `DeviceStorage` may hold an offline draft; `SecureStorage` is not
used at launch. Rationale: `CloudStorage` values are held by Telegram outside the operator's
encryption and deletion controls, which the developer terms make the operator answerable for.
Rejected alternative: storing the medicine registry in `CloudStorage` to avoid a backend, which
also cannot serve reminders because the bot cannot read it server-side.

`BiometricManager` is a candidate for an optional app lock on the health dashboard.

### 11.7 Device and platform integrations

| Capability | API | Label |
| --- | --- | --- |
| Home-screen shortcut | `addToHomeScreen()`, `checkHomeScreenStatus(callback)` with status `unsupported`, `unknown`, `added`, `missed`; events `homeScreenAdded`, `homeScreenChecked` (8.0) | `V[S6]` |
| Location | `LocationManager.init`, `getLocation(callback)`, `openSettings`; `LocationData{latitude, longitude, altitude, course, speed, horizontal_accuracy, vertical_accuracy, course_accuracy, speed_accuracy}`; events `locationManagerUpdated`, `locationRequested` (8.0) | `V[S6]` |
| Motion sensors | `Accelerometer`, `DeviceOrientation`, `Gyroscope`, each with `start({refresh_rate 20-1000 ms})` and `stop`; active only while the Mini App is open | `V[S6]` |
| Emoji status | `setEmojiStatus(custom_emoji_id, {duration})` opens a native dialog; `requestEmojiStatusAccess()` grants standing permission, after which the bot may call `setUserEmojiStatus(user_id, emoji_status_custom_emoji_id, emoji_status_expiration_date)` | `V[S6][S3]` |
| Share a prepared message | `shareMessage(msg_id)` with a `PreparedInlineMessage`; events `shareMessageSent`, `shareMessageFailed{error}` | `V[S6]` |
| Share to story | `shareToStory(media_url, StoryShareParams{text, widget_link})` (7.8) | `V[S6]` |
| File download | `downloadFile(DownloadFileParams{url, file_name})`; the server should answer with `Content-Disposition: attachment; filename="..."` and `Access-Control-Allow-Origin: https://web.telegram.org`; event `fileDownloadRequested{status: downloading or cancelled}` | `V[S6]` |
| Write access | `requestWriteAccess(callback)`; event `writeAccessRequested{status: allowed or cancelled}`; `WebAppUser.allows_write_to_pm`; service message `write_access_allowed` | `V[S6][S3]` |
| Phone number | `requestContact(callback)`; event `contactRequested{status: sent or cancelled}` | `V[S6]` |
| Invoice | `openInvoice(url, callback)`; event `invoiceClosed{url, status: paid, cancelled, failed, pending}` | `V[S6]` |
| Chat or user picker | `requestChat(req_id)` with a `PreparedKeyboardButton` from `savePreparedKeyboardButton` (9.6) | `V[S6][S3]` |
| Serverless endpoints | `Telegram.WebApp.Serverless.call(name, input, callback)`; only for Telegram-hosted projects | `V[S6]` |

The motion sensors stop when the Mini App is closed, so they cannot record sleep overnight
(`V[S6]` that they need `start` inside a running Mini App; that no background mode exists is
verified by absence).

Emoji status is a Telegram Premium feature `A`. For Premium users it would allow an ambient
"all doses taken today" indicator without any message.

`downloadFile` is the export path for a PDF or CSV of the user's own records.

`requestWriteAccess` matters when a user reaches the Mini App through a link before ever
pressing Start: without write access the bot cannot send that user a reminder.

## 12. Choosing between images, rich messages, widgets and the Mini App

| Surface | Strength | Limit | Use |
| --- | --- | --- | --- |
| Server-rendered image (`sendPhoto`, `editMessageMedia`) | Works on every client; looks the same everywhere; survives in history; forwardable by the user | Not interactive beyond the keyboard under it; each change is an edit or a new upload | Baseline for every infographic, report card and mood image |
| Rich message (10.1) | Real tables, collapsible sections, buttons inside the content, no rendering pipeline | Requires recent clients `A`; no custom drawing | Text-and-table reports |
| Widget (10.4) | Interactive, animated, updates silently, drawn in the chat theme | Production: private chat only, not forwardable; 4 KB compressed; no keyboard input; three days old | Progressive enhancement for mood, water and dose cards |
| Mini App | Full web application with authenticated backend calls | Opened on demand; not visible in the chat | Registry, schedules, history, dashboards, settings, export |

Decision: **server-rendered images are the baseline** for everything visual that the backend
sends; widgets and rich messages are added per surface only after a client-coverage spike, and
each must have an image or plain-text fallback. Rationale: the owner's stated core idea is a
backend that generates images, and images are the only one of the four that every Telegram
client displays identically today. Rejected alternative: widget-first status cards, because
unsupported clients show only a caption and the feature's production scope is still restricted.

## 13. Payments, Telegram Stars and subscriptions

| Fact | Label |
| --- | --- |
| Digital goods and services must be sold "exclusively" in Telegram Stars, currency `XTR`; other currencies, external providers and cryptocurrency are not allowed inside Telegram apps, even if the service also sells on its own website. | `V[S10][S11]` |
| Flow: `sendInvoice` or `createInvoiceLink` with `currency = "XTR"` and an empty `provider_token`; exactly one `LabeledPrice`; update `pre_checkout_query`; `answerPreCheckoutQuery` within **10 seconds**; service message `successful_payment`. Goods are delivered only after `successful_payment`. | `V[S10][S3]` |
| Invoice limits: `title` 1-32 characters, `description` 1-255 characters, `payload` 1-128 bytes. | `V[S3]` |
| `SuccessfulPayment.telegram_payment_charge_id` must be stored; it is the handle for refunds and subscription edits. | `V[S10][S3]` |
| Subscriptions: `subscription_period` "must always be 2592000 (30 days)"; price at most 10000 Stars; several subscriptions per user may be active. `SuccessfulPayment` carries `is_recurring`, `is_first_recurring`, `subscription_expiration_date`. | `V[S3]` |
| `editUserStarSubscription(user_id, telegram_payment_charge_id, is_canceled)` cancels or re-enables renewal. | `V[S3]` |
| Update `subscription` (`BotSubscriptionUpdated{user, invoice_payload, state}`), state `canceled`, `active` or `failed`. | `V[S3]` |
| `refundStarPayment(user_id, telegram_payment_charge_id)`; `getStarTransactions`; `getMyStarBalance`. | `V[S3][S2]` |
| Obligations: respond to `/paysupport`; offer terms (for example `/terms`) that users accept before purchase; provide support; the developer alone handles disputes; enable two-step verification on the owning account. | `V[S10][S11]` |
| Enabling topics in private chats adds a 15 percent fee on Stars purchases. | `V[S11]` |
| Half of the revenue from Telegram Ads shown in the chat with a bot is shared with the developer. | `V[S4]` |
| Mini App Store featuring requires a Main Mini App, uploaded media previews and acceptance of Stars payments. | `V[S4][S6]` |

Whether a paid tier of a health tracker counts as a "digital service" is `A`, but nothing in S10
or S11 suggests an exemption, so the safe reading is that any paid feature must be sold in Stars.

## 14. Telegram Business, guest mode, managed bots, bot-to-bot

| Feature | What it is | Relevance | Label |
| --- | --- | --- | --- |
| Business bots ("Secretary Mode") | A user connects the bot to their own account. The bot receives `business_connection`, `business_message`, `edited_business_message`, `deleted_business_messages` and may act with `business_connection_id` within `BusinessBotRights` (`can_reply` for chats with incoming messages in the last 24 hours, `can_read_messages`, and others). Since 10.0 the account owner no longer needs Premium. | Would expose the user's private conversations to the bot. Subject to S11 section 5.4. Not used. | `V[S2][S3][S4][S11]` |
| Checklists | Only through a business connection. | See 6.5. | `V[S3]` |
| Guest mode (10.0) | A bot answers when mentioned in chats it is not a member of (`guest_message`, `answerGuestQuery`). | Not used. | `V[S2][S4]` |
| Managed bots (9.6) | A bot creates and controls other bots. | Not used. | `V[S2][S4]` |
| Bot-to-bot communication (10.0) | Opt-in in BotFather; requires loop prevention. | Not used; keep disabled. | `V[S4]` |
| Communities (10.2) | Linked groups, channels and bots. | Not used. | `V[S2]` |

## 15. Notification behaviour

| Fact | Label |
| --- | --- |
| "Bots can't start conversations with users. A user must either add them to a group or send them a message first." | `V[S9]` |
| Write access can also be granted by launching a Mini App from a link, adding the bot to the attachment menu, or accepting `requestWriteAccess`; the bot then receives `write_access_allowed`. | `V[S3][S6]` |
| `disable_notification = true` delivers a notification without sound. | `V[S3]` |
| A bot message is otherwise an ordinary chat message. The user's mute setting for the chat, the Telegram notification settings and the operating system's focus or do-not-disturb mode decide whether it alerts. The bot cannot read or override any of them. | `A` |
| There is no alarm, critical-alert, call or full-screen notification available to a bot. | Verified by absence `V[S3]`; that no other mechanism exists is `A` |
| The bot cannot learn whether a message was read or even displayed. | Verified by absence `V[S3]` (the only read-related method, `readBusinessMessage`, concerns business accounts) |
| When the user blocks the bot the bot receives `my_chat_member`; unblocking produces it again. | `V[S3]` |
| Sending to a user who blocked the bot fails with HTTP 403. | `A` (error text is not in the reference) |
| A message edit does not produce a new notification. | `A` |
| Widget state edits are documented as silent. | `V[S8]` |
| Pinning in a private chat never notifies. | `V[S3]` |
| "Your TPA must not harass or spam users with unsolicited messages." | `V[S11]` section 5.2(b) |

Consequence: a medication reminder is best-effort. The product must say so to the user, must
never claim alarm-grade reliability, and should keep the chat useful when muted (a pinned "today"
card, the menu button, the home-screen shortcut).

## 16. Editing and deleting messages

| Fact | Label |
| --- | --- |
| `editMessageText`, `editMessageCaption`, `editMessageMedia`, `editMessageReplyMarkup` change a bot's own message in place. | `V[S3]` |
| The only edit time limit in the reference is 48 hours for "business messages that were not sent by the bot and do not contain an inline keyboard". No limit is stated for a bot's own messages. | `V[S3]`; that own messages stay editable indefinitely is `A` |
| An edit that changes nothing fails with `Bad Request: message is not modified`. | `V[S8]` (stated there for `editMessageText`) |
| `deleteMessage`: only if the message "was sent less than 48 hours ago"; in a private chat the bot can delete both its own outgoing and the user's incoming messages; a dice message in a private chat can only be deleted after 24 hours. | `V[S3]` |
| `deleteMessages`: 1-100 message identifiers per call; missing ones are skipped. | `V[S3]` |
| Reply keyboards cannot be edited; only messages without markup or with an inline keyboard are editable. | `V[S1]` |
| A draft from `sendMessageDraft` lasts about 30 seconds. | `V[S3]` |
| Ephemeral messages may vanish on their own. | `V[S1]` |

Consequence: a reminder card can be edited into its final state ("taken at 08:04") at any time,
but chat clean-up by deletion only works inside 48 hours. A message older than that can only be
edited down, not removed.

## 17. File limits

| Operation | Limit | Label |
| --- | --- | --- |
| Download with `getFile` | 20 MB; the link `https://api.telegram.org/file/bot<token>/<file_path>` is valid for at least 1 hour | `V[S3][S5]` |
| Upload, photo | 10 MB multipart; 5 MB by URL; width + height at most 10000; ratio at most 20 | `V[S1][S3]` |
| Upload, other files | 50 MB multipart; 20 MB by URL | `V[S1][S5]` |
| Resend by `file_id` | no limit | `V[S1]` |
| Voice by `sendVoice` | OGG audio up to 1 MB is a voice note; 1-20 MB is sent as a file | `V[S1]` |
| Document thumbnail | JPEG, under 200 kB, at most 320 px per side | `V[S3]` |
| Caption | 0-1024 characters | `V[S3]` |
| `getFile` metadata | "may not preserve the original file name and MIME type" | `V[S3]` |
| Local Bot API server | download unlimited, upload 2000 MB | `V[S1]` |

The download URL contains the bot token. A user-supplied food photo must be fetched by the
backend and stored under its own access control; the Telegram file URL must never reach the Mini
App or a log line.

## 18. Privacy mode and groups

Privacy mode is on by default. With it a bot in a group receives only commands addressed to it,
general commands if it was the last bot to post, messages sent via it, replies to its messages,
and service messages. It always receives all messages from private chats. Bot admins and bots
with privacy mode off receive all group messages except those of other bots `V[S4][S5]`.
`getMe` reports `can_join_groups` and `can_read_all_group_messages` `V[S3]`.

Decision: Dosegram is a **private-chat-only** bot. Group membership is disabled in BotFather and
privacy mode stays enabled. Rationale: every domain record (medication, mood, sleep, food) is
personal health data, and a group surface invites disclosure by mistake. Rejected alternative:
family or caregiver groups as a first-class surface; the caregiver case is covered by
person-to-person sharing that the user initiates.

That BotFather has a setting that prevents adding the bot to groups is `A` (the `can_join_groups`
flag confirms the concept; the setting itself was not read in a primary source).

## 19. What a bot can and cannot learn about a user

### 19.1 Available data

| Data | Where | Label |
| --- | --- | --- |
| `id`, `is_bot`, `first_name`, `last_name`, `username` | `User` in every update | `V[S3]` |
| `language_code` (IETF tag; optional; can be missing or stale) | `User`, `WebAppUser` | `V[S3][S4][S15]` |
| `is_premium` | `User`, `WebAppUser` | `V[S3]` |
| `added_to_attachment_menu` | `User`, `WebAppUser` | `V[S3]` |
| `allows_write_to_pm`, `photo_url` | `WebAppUser` | `V[S6]` |
| Profile photos | `getUserProfilePhotos` | `V[S3]` |
| `bio`, `birthdate` (`day`, `month`, optional `year`), `personal_chat`, emoji status, `has_private_forwards`, `has_restricted_voice_and_video_messages`, accent colours | `getChat` returning `ChatFullInfo` for the private chat; each subject to the user's privacy settings | `V[S3]`; the privacy-setting dependency is `A` |
| Everything the user sends: text, media, location, contact, files | messages | `V[S3][S15]` |
| Message timestamps | `Message.date`, `edit_date`, `MessageReactionUpdated.date` | `V[S3]` |
| Blocked or unblocked | `my_chat_member` | `V[S3]` |
| IP address of the device | only when the Mini App loads from the developer's server | `V[S12]` |
| Theme, platform, client Bot API level | `Telegram.WebApp.themeParams`, `platform`, `version` | `V[S6]` |
| Phone number | only if the user shares it: `request_contact`, `requestContact()` | `V[S3][S6]` |
| Location | only if the user shares it: `request_location`, `LocationManager` | `V[S3][S6]` |

### 19.2 Not available

| Data | Evidence | Label |
| --- | --- | --- |
| **Time zone** | No such field in `User`, `Chat`, `ChatFullInfo`, `WebAppUser`, `WebAppInitData` | Verified by absence `V[S3][S6]` |
| **Last seen, online status, presence** | No such field or update anywhere in the Bot API | Verified by absence `V[S3]` |
| Read receipts for bot messages | No such update | Verified by absence `V[S3]` |
| Phone number, email, contacts | Only on explicit sharing | `V[S3]` |
| Device motion or step data outside an open Mini App | Sensors require `start()` in a running Mini App | `V[S6]` |
| Health-platform data (steps, heart rate, sleep stages from a phone or watch) | The Mini App is a WebView with only the bridges listed in section 11; none reaches Health Connect, Samsung Health or HealthKit | Verified by absence `V[S6]` |
| Other chats or messages of the user | Only through a Business connection | `V[S4]` |

### 19.3 Time zone

Ways to obtain it, in order of preference:

1. In the Mini App, read the standard JavaScript value
   `Intl.DateTimeFormat().resolvedOptions().timeZone` and send it to the backend. This is a web
   platform feature, not a Telegram one `A` (expected to work in every Telegram WebView).
2. In chat-only onboarding, ask the user to pick a city or an offset.
3. Offer `request_location` and map coordinates to a zone; more data than needed.
4. In a widget, `Intl` is available and S8 names "time zone" as something a widget can pass in
   its 64 bytes of request data `V[S8]`.

Independently of the stored zone, `date_time` entities let the client render instants locally
(section 6.2).

### 19.4 Sleep tracking without "last seen"

The owner's idea was to derive sleep from the user's last-seen time. The Bot API does not expose
it. The alternatives:

| Approach | Signal | Verdict |
| --- | --- | --- |
| Self-report in chat | "Good night" and "I'm up" buttons, or a morning question with a time picker | Adopt as the primary source |
| Interaction-derived estimate | Timestamp of the last interaction with the bot or Mini App in the evening and the first in the morning: `Message.date`, callback queries, Mini App API calls, and widget background requests, which "show when the user has the chat open with the widget on screen" `V[S8]` | Adopt only as a pre-filled suggestion the user confirms. It measures use of this bot, not use of Telegram, and far less than use of the phone |
| MTProto user-account client reading `UserStatus` of each user | Real last-seen, when the user's privacy settings expose it | Reject |
| Telegram Business connection | Timestamps of the user's own outgoing messages in their private chats | Reject |
| Mini App motion sensors overnight | Accelerometer while the Mini App stays open and the screen stays on | Reject |

Decision: sleep is **self-reported, with an interaction-derived estimate offered as an editable
suggestion and labelled as an estimate**.

Rejected alternative, MTProto presence reading: it needs a logged-in user account operated by the
service rather than a bot; a user who hides last seen yields only a coarse bucket `A`; it collects
data "beyond what is essential", which S11 section 4.3 prohibits; and the client API terms (S20
section 1.4) forbid interfering with last-seen behaviour and require every action on behalf of a
user to have that user's knowledge and consent. It is also the least reliable signal operationally.

Rejected alternative, Business connection: it would hand the bot the content of the user's
private conversations in order to extract timestamps, which is disproportionate and falls under
S11 section 5.4.

## 20. Terms and policy

### 20.1 Bot Platform Developer Terms (S11)

| Clause | Obligation | Label |
| --- | --- | --- |
| 4 | Store only the data needed to function. Comply with privacy law, the Telegram Terms and Privacy Policy, and Apple and Google developer guidelines. | `V[S11]` |
| 4 | Every bot and Mini App must have an easily accessible privacy policy. If none is registered in BotFather, the Standard Bot Privacy Policy (S14) applies by default, and the developer is responsible for it actually fitting. All Mini Apps automatically show a Privacy Policy button in their settings. | `V[S11]` |
| 4.1 | Additional data may be requested from users by agreement, but never by deception. | `V[S11]` |
| 4.2 | Delete user data without undue delay on the user's request, on Telegram's request, when retention is no longer necessary, when the bot ceases to operate, and on lawful request. | `V[S11]` |
| 4.3 | Do not "collect, store, aggregate or process data beyond what is essential". Building datasets or training machine-learning models from collected data is always prohibited. Data that users submit "directly and voluntarily" may be used if they are clearly informed and give "individual, explicit, active and revocable consent". | `V[S11]` |
| 4.4 | Minimum security: user data "always encrypted at rest and stored separately from its encryption key"; breach notification to users under applicable law; a reasonably secure environment with vetted libraries; immediate remediation of leaks; all documented security checks (for example auth-data validation); defence against malicious clients (for example arbitrary data in `WebAppData`). | `V[S11]` |
| 4.5 | Credentials are confidential; actions taken with them are attributed to the developer. | `V[S11]` |
| 5.2 | No spam or unsolicited messages; no impersonation; no misrepresentation of function; no asking for a Telegram password or one-time code; no circumvention of rate limits; no regulated or questionable goods; the bot must behave predictably and tell users about significant changes with an opt-out. | `V[S11]` |
| 5.3 | Interface elements must work and must not imitate Telegram, the operating system or the browser. | `V[S11]` |
| 6.2 | Digital goods only in Stars; `/paysupport` mandatory; the developer handles disputes. | `V[S11]` |
| 6.2.5 | Paid broadcasts only "in good faith, for legitimate business purposes". | `V[S11]` |
| 6.3 | Telegram "may delete or make inaccessible whole chats, messages, media and files sent to and from your TPA at any time". Design persistence so that losing data normally provided by the platform does no harm. The chat history is therefore never the system of record. | `V[S11]` |
| 9, 9.1 | The developer must comply with all applicable law, "such as" the GDPR, and determine alone whether it applies. | `V[S11]` |

### 20.2 Health data

A search of S11, S12 and S13 for "health", "medic", "sensitive", "biometric" and "special
categor" returned no match (verified by absence, 2026-11-12). Telegram's terms neither forbid nor
specially regulate health records. The controlling rules are therefore general: clause 4.3
consent, clause 4.4 encryption and key separation, clause 4.2 deletion, and clause 9.1 data
protection law. Under the GDPR health data is a special category that needs explicit consent
`A` (legal reading, not a platform fact; needs the owner's decision on jurisdictions).

Clause 5.2(h) forbids facilitating the sale of "regulated or questionable goods and services". A
medicine tracker that records what the user already takes does not sell anything; a feature that
links to pharmacies or recommends products would move toward that clause `A`.

### 20.3 Mini App and bot user terms

Opening a Mini App automatically gives the developer the user's IP address, and may share user
id, public name, username, profile picture, language tag, Premium status and theme colours
`V[S12]`. The Standard Bot Privacy Policy states that the service "only requests, collects,
processes and stores data that is necessary", does not monetise user data outside the service,
and "will never share user data with third parties" without authorisation `V[S14]`. A health
product should publish its own policy rather than rely on that generic text, because clause 4 of
S11 requires a separate policy when the standard one does not describe the actual processing.

### 20.4 Client API terms

Only relevant if an MTProto client were ever used: an own `api_id` is required, last-seen and
read statuses must not be tampered with, actions on behalf of a user need consent, and data from
the platform must not be used to train models `V[S20]`.

## 21. Testing and operations support

| Item | Fact | Label |
| --- | --- | --- |
| Separate bot | The simplest test set-up is a second bot token; `file_id` values are not shared between bots. | `V[S4]` |
| Test environment | Separate accounts and bots; `.../bot<token>/test/METHOD`; HTTP URLs allowed for Mini Apps and login; every widget feature is available there. | `V[S4][S6][S8]` |
| Mini App debugging | iOS: "Allow Web View Inspection" then Safari; Android: "Enable WebView Debug" then `chrome://inspect`; Telegram Desktop beta on Windows and Linux: "Enable webview inspection". | `V[S6]` |
| Status alerts | BotFather alerts on too few replies to private messages and too few answers to callback queries. | `V[S4]` |
| Splash screen | Mini App loading screen icon and colours are set in BotFather. | `V[S4]` |
| Media previews | Screenshots and video demos per language on the bot profile once a Main Mini App is set. | `V[S4][S6]` |

## 22. JVM client options for a Scala 3 codebase

Context read from `build.mill` on 2026-11-12: Scala 3.8.4, JDK 25, direct style with Ox
(`com.softwaremill.ox::core`), JSON with uPickle, HTTP server with Tapir on the synchronous Netty
backend, `core` and `contracts` required to stay vendor-neutral.

Maintenance data from the GitHub REST API on 2026-11-12 `V[S22]`:

| Option | Kind | Latest release (date) | Bot API level | Stack it brings | Licence | Fit |
| --- | --- | --- | --- | --- | --- | --- |
| Own thin client: HTTP client plus own uPickle codecs for the methods used | In-repo code | n/a | whatever is implemented | none new if the JDK `java.net.http.HttpClient` is used | n/a | Direct style, no transitive dependencies, exact control of timeouts, retries, logging and redaction |
| `rubenlagus/TelegramBots` (`org.telegram:telegrambots-*`) | Java library | v10.3.0 (2026-09-07) | 10.3, one release behind | Jackson 2.17, OkHttp 4.12, Lombok; Java 17; optional Spring Boot starters; separate long-polling, webhook and client modules | MIT | Blocking API fits direct style; 5553 stars, 195 open issues |
| `pengrad/java-telegram-bot-api` | Java library | 10.3.0 (2026-09-26) | 10.3, one release behind; the previous release was 10.1 on 2026-06-12 | Gson, OkHttp | Apache-2.0 | Small and simple; blocking; 1955 stars, 7 open issues |
| `apimorphism/telegramium` | Scala library, generated from the official documentation | 10.1003.2 (2026-11-06); 10.4 support merged to master on 2026-11-10 | 10.4 on master | cats-effect, http4s, circe | MIT | Most current, but the effect stack conflicts with the Ox direct-style codebase |
| `bot4s/telegram` | Scala library | v7.1.0 (2026-09-27) | not stated in the README section read | sttp client4, circe, cats; Scala 2.12, 2.13, 3 and Scala.js | Apache-2.0 | Future-based client; Bot API level `A` |
| `augustjune/canoe` | Scala library | v0.6.0 (2022-05-05); last push 2024-09 | far behind | cats-effect, fs2 | MIT | Unmaintained |
| `tdlight-team/tdlight-java` | Java wrapper over native TDLib (MTProto, not the Bot API) | v3.5.9+td.1.8.68 (2026-10-19) | n/a | native binaries per platform | LGPL-3.0 | Different protocol; needed only for user-account features this product rejects |
| `InsanusMokrassar/ktgbotapi`, `vendelieu/telegram-bot` | Kotlin libraries | 37.1.0 (2026-10-10); 9.8.0 (2026-11-09) | current | Kotlin coroutines, kotlinx.serialization, Ktor | Apache-2.0 | Coroutine APIs are awkward from Scala |

Observation: on the day of this brief the API was at 10.4 and both Java libraries were at 10.3.
The lag is days to weeks, and during it new update kinds either fail to decode or are dropped.

### 22.1 Recommendation

Decision: write an **own thin Telegram client inside a new `adapter-telegram` module**: one
blocking HTTP call function over the JDK `HttpClient` (JSON and multipart), hand-written uPickle
codecs for the roughly forty methods and the handful of types the product uses, tolerant decoding,
and a single place that applies rate limiting, `retry_after` handling and redaction.

Rationale:

1. The product needs a narrow slice of a very large API, and wants features from the newest
   release (widgets, rich messages, `date_time`) on its own schedule rather than a library's.
2. The codebase is direct style with uPickle; an own client adds no JSON library, no HTTP client
   library and no effect system.
3. The token is in every URL and message bodies are sensitive; owning the transport is the only
   way to guarantee that neither is logged.
4. `core` and `contracts` stay free of vendor symbols by construction, which the existing
   forbidden-symbol gate can keep enforcing.
5. The existing Discord adapter already proves the port shape (acknowledgement watchdog, fork per
   event, "unreachable" mapping, fake wire for conformance tests).

Rejected alternative, the runner-up: **`pengrad/java-telegram-bot-api`**. It would remove the
codec work and its blocking API suits direct style, but it adds Gson and OkHttp, lagged a full
release twice in 2026, and its models would have to be hidden behind the same port anyway.

Rejected alternative: **`telegramium`**. It is the most current option, but it would bring
cats-effect, http4s and circe into a codebase that standardised on Ox, uPickle and Tapir.

Rejected alternative: **TDLight / TDLib**. It is an MTProto client with native binaries and an
LGPL licence, justified only by user-account capabilities that section 19.4 rejects.

Cost of the decision: the team owns multipart upload, the error taxonomy and keeping up with the
changelog. Mitigation: a conformance suite on a fake wire (as for Discord), plus a scheduled check
of the changelog page whose machine-readable form is at
`https://core.telegram.org/bots/api-changelog.md`.

## 23. Design consequence

| # | Constraint | Label | What the product must do |
| --- | --- | --- | --- |
| 1 | No presence or last-seen data in the Bot API | Verified by absence `V[S3]` | Make sleep self-reported; offer interaction-derived times only as an editable, clearly labelled estimate; never describe it as measured sleep |
| 2 | No time zone in any user object | Verified by absence `V[S3][S6]` | Capture an IANA zone during onboarding (Mini App `Intl` value or a chat picker), store it per user, let the user change it, and use `date_time` entities so displayed times are still local |
| 3 | A bot cannot start a conversation | `V[S9]` | Every entry path must end in the user pressing Start or granting write access; the Mini App must call `requestWriteAccess` when `allows_write_to_pm` is absent |
| 4 | Reminders are ordinary messages; no alarm-grade delivery; mute and focus modes are invisible | `A` | State best-effort delivery in onboarding and terms; add follow-up nudges and a missed-dose state; never present the bot as a safety-critical alarm |
| 5 | Block and unblock arrive as `my_chat_member` | `V[S3]` | Subscribe to `my_chat_member`; on block, stop scheduling sends for that user and mark the account unreachable; on unblock, resume |
| 6 | About 30 messages per second per bot, 1 per second per chat, 429 with `retry_after` | `V[S5][S3]` | Central outbound queue with a global and a per-chat token bucket, priority (dose reminders before reports), jitter around popular times, honour `retry_after`, and prefer one message per reminder |
| 7 | Paid broadcast needs a large Star balance and user base; sources disagree on the threshold | `V[S1][S5][S11]` | Do not plan on it; design for the free limit |
| 8 | BotFather alerts on unanswered messages and callbacks | `V[S4]` | Answer every private message and call `answerCallbackQuery` for every callback, including on error paths |
| 9 | Undelivered updates are kept at most 24 hours; webhook retries are unspecified; delivery may be parallel | `V[S1][S3]` | Durable inbox keyed by `update_id`, idempotent handlers, quick acknowledgement after the durable write, alert on `pending_update_count` and `last_error_date` |
| 10 | Webhook source can be checked by header and by subnet | `V[S3][S7]` | Require `X-Telegram-Bot-Api-Secret-Token`, compare in constant time, optionally restrict ingress to `149.154.160.0/20` and `91.108.4.0/22`, listen on 443 or 8443 over IPv4 |
| 11 | Bot token is part of every API and file URL | `V[S1][S3]` | Never log request URLs; redact in error paths; never hand a Telegram file URL to a client; proxy downloads |
| 12 | The API is unversioned and gains fields monthly | `V[S2]` | Tolerant decoding; unknown update kinds are counted and dropped without logging bodies; explicit `allowed_updates` |
| 13 | `callback_data` is 1-64 bytes and is untrusted | `V[S3]` | Carry a short opaque token that resolves to server-side state; re-check ownership and state on every press; make every action idempotent |
| 14 | Callback deadline undocumented | `A` | Keep the acknowledgement watchdog; acknowledge first, do slow work after |
| 15 | Checklists need a Business connection | `V[S3]` | Build the daily dose list from inline keyboards, a rich message or a widget |
| 16 | Text 4096 characters, caption 1024 characters | `V[S3]` | Put detail in the image or the Mini App; keep captions short; split long text deliberately |
| 17 | Photo limits and probable recompression | `V[S3]`, `A` | Render at a fixed phone-friendly size, test legibility after Telegram's processing, offer the lossless file through `sendDocument` or `downloadFile` |
| 18 | `file_id` is persistent and reusable | `V[S1][S5]` | Cache `file_id` per rendered-image content hash; do not re-upload identical images |
| 19 | Own messages can be edited; deletion only within 48 hours | `V[S3]` | Prefer editing a card into its final state over delete-and-resend; schedule chat clean-up inside 48 hours or not at all |
| 20 | Only inline-keyboard or markup-free messages are editable | `V[S1]` | Use inline keyboards on all cards that change; keep reply keyboards for stable navigation only |
| 21 | `initData` is the only trustworthy Mini App identity and must be validated server-side | `V[S6]` | Validate with HMAC-SHA-256, enforce `auth_date` freshness, issue a short-lived session token, never trust `initDataUnsafe` |
| 22 | `initData` is empty for reply-keyboard and inline-mode launches; `sendData` is unauthenticated | `V[S6][S3]` | Launch the Mini App only from the Main Mini App, the menu button and inline `web_app` buttons |
| 23 | Mini App methods are blocked from foreign origins since 2026-07-20 | `V[S2]` | Single-origin Mini App; no opt-out; no embedded third-party pages |
| 24 | Start parameters are 64 characters of a restricted alphabet and are attacker-controlled | `V[S4][S18]` | Use them for routing and opaque single-use tokens only |
| 25 | `CloudStorage` lives with Telegram: 1024 keys of 4096 characters | `V[S6]` | No health data in `CloudStorage`; interface preferences only |
| 26 | Theme and safe areas are supplied by the client | `V[S6]` | Style exclusively from `--tg-theme-*` variables; handle `themeChanged`, `viewportChanged`, safe-area events; test light and dark |
| 27 | Client capability differs by app version | `V[S6]` | Gate every Mini App feature with `isVersionAtLeast`; keep a fallback for each |
| 28 | Widgets: private chat only in production, 4 KB compressed, no forwarding, three days old | `V[S8]` | Treat widgets as optional enhancement behind a per-user capability flag with an image fallback; keep authoritative state on the server |
| 29 | Widget background requests reveal when the chat is open | `V[S8]` | Disclose it in the privacy policy if used; do not store it beyond the purpose stated to the user |
| 30 | Motion sensors and location work only inside an open Mini App; no health-platform bridge | `V[S6]` | Do not promise step counting, automatic sleep detection or wearable sync; steps and similar metrics are manual entry or out of scope |
| 31 | Digital goods only in Stars; subscriptions are 30-day only; 10-second pre-checkout deadline | `V[S10][S3]` | Keep an entitlement boundary in the domain so a paid tier can be added; if added, implement `/paysupport`, `/terms`, refunds and the `subscription` update |
| 32 | Topics in private chats cost 15 percent of Stars revenue | `V[S11]` | Leave topics off unless the UX gain is judged worth the fee |
| 33 | Inline results are cached server-side unless `is_personal` | `V[S3]` | Keep inline mode off; share through user-initiated `shareMessage` |
| 34 | Privacy mode and group surfaces | `V[S4]` | Private chats only; disable group joins; keep privacy mode on |
| 35 | Encryption at rest with separated keys, deletion on request, minimisation, breach notice | `V[S11]` | Field-level or volume encryption with keys in a separate secret store; a user-facing "delete my data" and "export my data" command; retention schedule; breach runbook |
| 36 | Privacy policy must exist and be accessible; the standard policy is generic | `V[S11][S14]` | Publish a specific policy, register it in BotFather, link it from `/start`, `/help` and Mini App settings |
| 37 | No training or dataset building from collected data | `V[S11]` | Analytics stay per-user and operational; no model training on user records |
| 38 | Bots must not impersonate or imitate system dialogs; no password or code collection | `V[S11]` | No fake system notifications in the Mini App; no credential prompts in chat (already a repository rule) |
| 39 | No health-specific clause in Telegram terms; general law applies | Verified by absence `V[S11][S12][S13]` | Obtain explicit, revocable consent before the first health record; keep all guidance non-clinical; get a legal review before a public launch |
| 40 | Java client libraries lag the API by a release | `V[S22]` | Own thin client in `adapter-telegram`; conformance tests on a fake wire; scheduled changelog check |
| 41 | Test environment has stricter, not looser, flood limits | `V[S4]` | Do not hard-code limits; drive pacing from configuration and from `retry_after` |
| 42 | `language_code` is optional and can be stale | `V[S4][S15]` | Fall back to the last recorded language, then English; let the user set language explicitly; localise commands, name and descriptions per language |

## 24. Assumptions that need a spike before design depends on them

| # | Assumption | How to confirm |
| --- | --- | --- |
| 1 | `message_reaction` updates are delivered for the private chat with the bot | Subscribe with `allowed_updates`, react to a bot message from each client |
| 2 | Clients render `date_time` entities in the device time zone and locale | Send one entity to devices in two zones |
| 3 | Photos are recompressed and downsized; at what size text stays legible | Send test charts as photo and as document on iOS, Android, Desktop, Web |
| 4 | Widgets render on current iOS, Android, Desktop and Web clients; what unsupported clients show | Send one widget in production to each client |
| 5 | Rich messages on clients older than Bot API 10.1 | Same, with one rich message |
| 6 | `Intl...timeZone` returns the device zone in every Telegram WebView | Open a test Mini App on each client |
| 7 | Webhook response deadline and retry schedule | Measure with a deliberately slow and a failing endpoint |
| 8 | A second `getUpdates` consumer is rejected | Run two pollers on a test token |
| 9 | Callback query answer deadline | Delay `answerCallbackQuery` in steps |
| 10 | HTTP 403 and its description when the user has blocked the bot | Block a test bot and send |
| 11 | Own messages remain editable after 48 hours | Edit a message older than two days |
| 12 | Inline keyboard size limits | Send keyboards of growing size |
| 13 | `savePreparedInlineMessage` works with inline mode disabled | Call it on a bot without `/setinline` |
| 14 | `SecureStorage`, `BiometricManager`, `addToHomeScreen` on Desktop and Web | Call each and record the result |
| 15 | Valid `message_effect_id` values | Read `effect_id` from messages sent with effects |
| 16 | `signature` is part of the HMAC data-check-string | Validate real `initData` both ways |
| 17 | The QR popup reads retail barcodes | Scan a medicine pack |
| 18 | `sendMessageDraft` works without topics enabled | Call it on a plain bot |
| 19 | Effective paid-broadcast threshold (10,000 or 100,000 Stars) | Read the BotFather setting screen |

## 25. Open questions

Each entry needs the owner's decision. The recommendation is the default the plan should assume.

1. **Sleep source.** The owner asked for sleep derived from last seen; the platform does not
   provide it. Recommendation: self-reported bedtime and wake time, with an editable estimate
   from interaction timestamps, and no MTProto or Business-connection data collection.
2. **Reminder reliability wording.** A Telegram message cannot be an alarm. Recommendation:
   accept best-effort delivery, state it in onboarding and in the terms, and add escalation
   nudges plus an optional caregiver notification rather than seeking another channel.
3. **Widgets.** Recommendation: ship images first, run the client-coverage spike, then add a
   widget for mood and water as an opt-in enhancement with an image fallback.
4. **Topics in private chats.** They would give separate threads per domain but cost 15 percent
   of any future Stars revenue. Recommendation: leave off; revisit after the first usable release.
5. **Monetisation.** Any paid tier must use Stars with 30-day subscriptions. Recommendation: no
   payments in the first stages; keep an entitlement boundary so it can be added.
6. **Inline mode and sharing.** Recommendation: inline mode off; user-initiated `shareMessage`
   of a single report card when caregiver sharing is built.
7. **Emoji status.** Premium-only ambient indicator. Recommendation: defer; low value for the
   effort.
8. **Biometric app lock in the Mini App.** Recommendation: offer as an optional setting in a
   later stage, since chat history itself is not protected by it.
9. **Jurisdictions and legal review.** Health data is specially protected in several legal
   systems. Recommendation: the owner names the target jurisdictions before public launch and the
   plan includes explicit consent, export and deletion from the first stage.
10. **Own client versus library.** Recommendation: own thin client as in section 22.1; if the
    owner prefers less in-house code, `pengrad/java-telegram-bot-api` behind the same port is the
    fallback.
11. **Bot identity.** The rename to Dosegram implies a new BotFather bot, username, Main Mini App
    registration, privacy policy URL and a separate test-environment bot. Recommendation: create
    them when the first Telegram slice is scheduled, not during planning.
