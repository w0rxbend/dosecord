# Research brief 05: Sleep tracking and Telegram presence

Status: research evidence for the Dosegram plan. No product code and no decision is final until the owner accepts it.
Date checked for every external source in this file: 2026-11-12.
Scope: whether sleep can be derived from a Telegram user's "last seen", and the design of the best honest alternative.

## 1. Summary

1. The Telegram Bot API does not expose a user's last seen time or online status in any object, method or update. Telegram's own FAQ states that bots cannot see it. A Mini App cannot see it either. This is VERIFIED against Bot API 10.4 (2026-11-09) and the Mini App documentation (last change 2026-06-11).
2. Only MTProto user sessions see presence (`UserStatus`). Reading it for Dosegram would require either a service-owned observer account or a server-side session logged in as the user. The first does not work reliably and is a presence-surveillance pattern. The second works in principle but requires Dosegram to hold a credential with full access to the user's Telegram account. Neither is defensible for a health product. Recommendation: do not build either.
3. "Last seen" is also a weak sleep signal on its own merits. It measures "a Telegram client was in the foreground", not sleep.
4. The honest alternative is a tiered sleep feature built from data the product legitimately has:
   - Tier 1, passive: timestamps of the user's own interactions with the bot and the Mini App. This yields a "quiet window", which is a hard upper bound on sleep opportunity, not a sleep measurement.
   - Tier 2, explicit: one-tap "Going to sleep" and "I'm up" check-ins, a bedtime-mode keyboard, and morning confirmation or correction of the passive estimate. This is sleep-diary grade.
   - Tier 3, sensor: optional imports (file exports, then OAuth wearable APIs). This is the only tier that may show stages or a device sleep score.
5. Each metric is shown only at the tier that supports it. Stages, efficiency, latency, awakening counts and any "sleep score" are never computed by Dosegram without sensor data.

## 2. Evidence conventions

- VERIFIED means the statement was read in the cited primary source on 2026-11-12. The source URL is given inline or in section 14.
- ASSUMPTION means the statement was not confirmed in a primary source during this run. It may come from general knowledge, a secondary source, or the absence of a feature in documentation. Each assumption that affects a design decision is repeated in section 13 with a way to verify it.
- Design proposals (algorithms, thresholds, tiers, copy) are this brief's own work. They are neither VERIFIED nor ASSUMPTION about a platform; they are recommendations to accept or overrule.
- Sleep-science references are cited by author, year and journal. Where a number was not re-read in this run, the text says so.

## 3. What Telegram exposes about presence

### 3.1 Bot API

Source: https://core.telegram.org/bots/api, "Recent changes" top entry "Bot API 10.4", dated November 9, 2026.

| Claim | Status | Evidence |
| --- | --- | --- |
| The Bot API documentation page contains no occurrence of the words "online" or "last seen". | VERIFIED | Full-text search of the page on 2026-11-12. The only matches for "presence" are the anonymous-administrator flags ("True, if the user's presence in the chat is hidden") and an unrelated date-format sentence. |
| `User` has no presence field. Its fields are `id`, `is_bot`, `first_name`, `last_name`, `username`, `language_code`, `is_premium`, `added_to_attachment_menu`, `can_join_groups`, `can_read_all_group_messages`, `supports_guest_queries`, `supports_inline_queries`, `can_connect_to_business`, `has_main_web_app`, `has_topics_enabled`, `allows_users_to_create_topics`, `can_manage_bots`, `supports_join_request_queries`. | VERIFIED | Same page, `User`. |
| `ChatFullInfo` (returned by `getChat`) has no presence field. The only "status" fields are `emoji_status_custom_emoji_id` and `emoji_status_expiration_date`, which describe a profile emoji, not presence. | VERIFIED | Same page, `ChatFullInfo`. |
| `ChatMember*` types carry membership state only. | VERIFIED | Same page; no presence wording. |
| `BusinessConnection` has `id`, `user`, `user_chat_id`, `date`, `rights`, `is_enabled`. `BusinessBotRights` has 14 rights (`can_reply`, `can_read_messages`, `can_delete_sent_messages`, `can_delete_all_messages`, `can_edit_name`, `can_edit_bio`, `can_edit_profile_photo`, `can_edit_username`, `can_change_gift_settings`, `can_view_gifts_and_stars`, `can_convert_gifts_to_stars`, `can_transfer_and_upgrade_gifts`, `can_transfer_stars`, `can_manage_stories`). None grants presence. | VERIFIED | Same page. |
| `Update` types are `message`, `edited_message`, `channel_post`, `edited_channel_post`, `business_connection`, `business_message`, `edited_business_message`, `deleted_business_messages`, `guest_message`, `message_reaction`, `message_reaction_count`, `inline_query`, `chosen_inline_result`, `callback_query`, `widget_query`, `shared_widget_query`, `shipping_query`, `pre_checkout_query`, `purchased_paid_media`, `poll`, `poll_answer`, `my_chat_member`, `chat_member`, `chat_join_request`, `chat_boost`, `removed_chat_boost`, `managed_bot`, `subscription`, `stopped_message_generation`. There is no presence update and no "user opened the chat" update. | VERIFIED | Same page, `Update`. |
| Bots do not receive read receipts for messages they send in a private chat. | ASSUMPTION | No such field or update exists in the page. The only read-related items are the business right `can_read_messages` and `readBusinessMessage`, which mark messages as read on behalf of a business account. |
| `CallbackQuery` has no date field (`id`, `from`, `message`, `inline_message_id`, `chat_instance`, `data`, `game_short_name`). The time of a button press is the time the server receives the update. | VERIFIED | Same page, `CallbackQuery`. |
| `Message.date` is "Date the message was sent in Unix time". | VERIFIED | Same page, `Message`. |
| The Bot API gives no time zone for a user. | VERIFIED | The only time zone field on the page belongs to business opening hours. |
| The maximum file size a bot can download with `getFile` is 20 MB. | VERIFIED | Same page, `getFile`. |

Telegram states the same in plain language. FAQ, section on bots: "Bots can see your public name, username and profile picture, the messages you send them, your interface language and the groups you share with them. [...] Bots cannot see your last seen time, your phone number or your other chats unless you share them." VERIFIED, https://telegram.org/faq.

The Telegram Privacy Policy, section 6.3 (what data bots receive), lists public account data, messages sent to the bot, interface language, group co-membership, and the IP address when the user opens a link that leads to the bot's own site. It does not list presence. VERIFIED from the localized page at https://telegram.org/privacy; the English wording was not retrieved in this run.

### 3.2 Mini Apps

Source: https://core.telegram.org/bots/webapps, latest change entry June 11, 2026 (Bot API 10.1).

| Claim | Status | Evidence |
| --- | --- | --- |
| `WebAppInitData` fields are `query_id`, `user`, `receiver`, `chat`, `chat_type`, `chat_instance`, `start_param`, `can_send_after`, `auth_date`, `hash`, `signature`. None carries presence. | VERIFIED | Mini App page, `WebAppInitData`. |
| `auth_date` is "Unix time when the form was opened". | VERIFIED | Same. |
| `Telegram.WebApp.isActive` (Bot API 8.0+) is "True, if the Mini App is currently active. False, if the Mini App is minimized." | VERIFIED | Same. |
| Events `activated` ("Occurs when the Mini App becomes active (e.g., opened from minimized state or selected among tabs)") and `deactivated` ("Occurs when the Mini App becomes inactive (e.g., minimized or moved to an inactive tab)") exist since Bot API 8.0. | VERIFIED | Same, "Events Available for Mini Apps". |
| The complete event list is `activated`, `deactivated`, `themeChanged`, `viewportChanged`, `safeAreaChanged`, `contentSafeAreaChanged`, `mainButtonClicked`, `secondaryButtonClicked`, `backButtonClicked`, `settingsButtonClicked`, `invoiceClosed`, `popupClosed`, `qrTextReceived`, `scanQrPopupClosed`, `clipboardTextReceived`, `writeAccessRequested`, `contactRequested`, `biometricManagerUpdated`, `biometricAuthRequested`, `biometricTokenUpdated`, `fullscreenChanged`, `fullscreenFailed`, `homeScreenAdded`. There is no "closed" event. | VERIFIED | Same. |
| The Mini App API offers no access to the user's Telegram presence and no time zone field. | VERIFIED | No such field on the page. |
| A Mini App can read the device time zone with the standard web API `Intl.DateTimeFormat().resolvedOptions().timeZone`. | ASSUMPTION | Standard web platform behaviour; not tested inside each Telegram client's webview. |
| A final network request sent while the Mini App is being closed is not guaranteed to be delivered. | ASSUMPTION | General webview behaviour. The absence of a "closed" event is VERIFIED. |
| `DeviceStorage` gives "up to 5 MB per user" of local storage on the device. | VERIFIED | Mini App page, `DeviceStorage`. |
| A Mini App can ask to manage the user's emoji status (`requestEmojiStatusAccess`), after which the bot can call `setUserEmojiStatus`. | VERIFIED | Mini App page and Bot API page. This is an optional way to show a "sleeping" emoji status during bedtime mode; it is not a presence reader. |

Consequence: a Mini App can report its own foreground state to the Dosegram backend. That is a legitimate first-party signal. It says nothing about the user's presence elsewhere in Telegram.

### 3.3 MTProto user clients

Source: https://core.telegram.org/type/UserStatus and linked constructor pages, schema layer 225.

`UserStatus` has six constructors. VERIFIED.

| Constructor | TL signature | Meaning |
| --- | --- | --- |
| `userStatusEmpty` | `userStatusEmpty#9d05049 = UserStatus;` | "User status has not been set yet." |
| `userStatusOnline` | `userStatusOnline#edb93949 expires:int = UserStatus;` | Online; `expires` is "Time to expiration of the current online status". |
| `userStatusOffline` | `userStatusOffline#8c703f was_online:int = UserStatus;` | Offline; `was_online` is "Time the user was last seen online". |
| `userStatusRecently` | `userStatusRecently#7b197dc8 flags:# by_me:flags.0?true = UserStatus;` | "Online status: last seen recently". |
| `userStatusLastWeek` | `userStatusLastWeek#541a1d1a flags:# by_me:flags.0?true = UserStatus;` | "Online status: last seen last week". |
| `userStatusLastMonth` | `userStatusLastMonth#65899777 flags:# by_me:flags.0?true = UserStatus;` | "Online status: last seen last month". |

Related facts:

| Claim | Status | Evidence |
| --- | --- | --- |
| The `user` constructor carries `status:flags.6?UserStatus`. Status changes arrive as `updateUserStatus#e5bdf8de user_id:long status:UserStatus`, described as "Contact status update". | VERIFIED | https://core.telegram.org/api/privacy (schema excerpt), https://core.telegram.org/constructor/updateUserStatus |
| `account.updateStatus#6628562c offline:Bool = Bool` "Updates online user status". "Only users can use this method." | VERIFIED | https://core.telegram.org/method/account.updateStatus |
| `contacts.getStatuses` returns "the online statuses of all contacts with an accessible Telegram account". "Only users can use this method." | VERIFIED | https://core.telegram.org/method/contacts.getStatuses |
| The privacy key is `inputPrivacyKeyStatusTimestamp`: "Whether people will be able to see our exact last online timestamp." | VERIFIED | https://core.telegram.org/constructor/inputPrivacyKeyStatusTimestamp |
| Reciprocity: "if we decide to hide our exact last online timestamp to someone [...] and we do not have a Premium subscription, we won't be able to see the exact last online timestamp of those users [...], even if those users do share it with us." In that case the `by_me` flag is set on the approximate constructors. | VERIFIED | Same page. TDLib names the flag `by_my_privacy_settings` on `userStatusRecently`: "Exact user's status is hidden because the current user enabled userPrivacySettingShowStatus privacy setting for the user and has no Telegram Premium." https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1user_status_recently.html |
| Premium difference: "Telegram Premium users can hide their last seen and read times and still see the times of people who share them." | VERIFIED | https://telegram.org/faq |
| Approximate buckets shown when the exact time is hidden: "Last seen recently — covers anything between 1 second and 2-3 days"; "Last seen within a week — between 2-3 and seven days"; "Last seen within a month — between 6-7 days and a month"; "Last seen a long time ago — more than a month (this is also always shown to blocked users)". | VERIFIED | https://telegram.org/faq |
| Online status follows the same rule: "People can only see you online if you're sharing your last seen status with them." Exceptions: the other party sees the user online "for a brief period (~30 seconds)" when the user sends them a message, reads their message in a one-on-one chat, or broadcasts a typing status. | VERIFIED | https://telegram.org/faq |
| Read time is kept "for 7 days only", and "If you hide your last seen time from someone, your read time is hidden from them as well." `globalPrivacySettings.hide_read_marks` ties `messages.getOutboxReadDate` to the same privacy key and returns `403 USER_PRIVACY_RESTRICTED` otherwise. | VERIFIED | https://telegram.org/faq, https://core.telegram.org/constructor/globalPrivacySettings |
| A user session receives its own account's `status`, and `updateUserStatus` for the own user id, so that it could log its owner's presence. | ASSUMPTION | The schema allows it (`user.self` plus `status`). Not confirmed by a test or by documentation in this run. |
| A server-side session that never calls `account.updateStatus(offline = false)` does not make the user appear online. | ASSUMPTION | Plausible from the method description; not confirmed. If it is false, the session itself corrupts the signal it is trying to read. |

### 3.4 Why "last seen" is a weak sleep signal even when it is readable

This is reasoning, not a platform claim.

- It records the last time any of the user's Telegram clients was in the foreground. A desktop or web client left open, or a second device used by another household member, moves it.
- It records Telegram use only. A user who reads another app until 02:00 has a Telegram last seen of 23:00.
- It gives one timestamp per offline period. It gives the same two edges (last activity, first activity) that the bot's own interaction log gives, with more density but with none of the user's consent, intent or confirmation.
- The hidden-status buckets ("recently" covers one second to two or three days) carry no sleep information at all.

## 4. Could a user session read it for Dosegram?

Three technical routes exist. Each is classified as forbidden, risky or clean. "Forbidden" means a rule in a cited Telegram document prohibits it. "Risky" means no rule names it, but it exposes the user or the product to a stated enforcement mechanism or to a serious trust problem. "Clean" means it uses only documented bot and Mini App capabilities with informed consent.

### 4.1 The governing documents

Telegram API Terms of Service, https://core.telegram.org/api/terms. VERIFIED quotations:

- 1.1: "All client apps must, therefore, guard their users' privacy with utmost care and comply with our Security Guidelines."
- 1.3: "As a client developer, you must make sure that all the basic features of the main Telegram apps function correctly and in an expected way both in your app and when users of your app communicate with other Telegram users."
- 1.4: "It is forbidden to interfere with the basic functionality of Telegram. This includes but is not limited to: making actions on behalf of the user without the user's knowledge and consent, preventing self-destructing content from disappearing, preventing last seen and online statuses from being displayed correctly, tampering with the 'read' statuses of messages (e.g. implementing a 'ghost mode'), preventing typing statuses from being sent/displayed, etc."
- 1.5: "you are prohibited from using, accessing or aggregating data obtained from the Telegram platform to train, fine-tune or otherwise engage in the development, enhancement or deployment of artificial intelligence, machine learning models and similar technologies."
- 2.1: "You must obtain your own api_id for your application." 2.2 requires that users know the app uses the Telegram API.
- 4.1 and 4.2: Telegram notifies the responsible account of a breach; if not fixed "within 10 days", it will "discontinue your access to Telegram API".

Obtaining an api_id, https://core.telegram.org/api/obtaining_api_id. VERIFIED quotations:

- "all API client libraries are strictly monitored to prevent abuse."
- "If you use the Telegram API for flooding, spamming, faking subscriber and view counters of channels, you will be banned forever."
- "Due to excessive abuse of the Telegram API, all accounts that log in using unofficial Telegram API clients are automatically put under observation to avoid violations of the Terms of Service."

Bot Platform Developer Terms, https://telegram.org/tos/bot-developers. VERIFIED quotations:

- Section 4: "Telegram only stores the data it needs to function as a secure and feature-rich messaging service, and your TPA are required to do the same." Every bot and Mini App "must be bound by a privacy policy that is easily accessible to their users".
- 4.2: delete user data on request, when retention becomes unnecessary, and when the service ceases.
- 4.3: "You agree not to use your TPA to collect, store, aggregate or process data beyond what is essential for the operation of your services. Always prohibited uses include any form of data collection aimed at creating large datasets, machine learning models and AI products". And: "you are free to use data submitted directly and voluntarily to your TPA by users, provided that you clearly inform them of the data's intended use and they give their individual, explicit, active and revocable consent."
- 4.4 (a): "Make sure user data is always encrypted at rest and stored separately from its encryption key".

### 4.2 Route A: a service-owned observer account

Design: Dosegram operates one or more ordinary Telegram user accounts through TDLib or tdlight. Each account watches the `UserStatus` of Dosegram users.

| Aspect | Finding | Status |
| --- | --- | --- |
| Technical reach | The observer sees an exact `was_online` only if the user's "Last seen and online" privacy setting allows that observer. With the setting at "Nobody", or "My contacts" without adding the observer, it sees only `userStatusRecently`. | VERIFIED (privacy key semantics, section 3.3) |
| Reciprocity | A non-Premium observer that hides its own status cannot see others' exact status. | VERIFIED |
| Onboarding burden | The user must change a global privacy setting or add an exception for an unknown account. | Reasoning |
| Enforcement exposure | Observer accounts log in through an unofficial client and are "automatically put under observation". An account that watches thousands of unrelated users has the same shape as commercial "last seen tracker" services. | First sentence VERIFIED; the judgement that Telegram would act against it is an ASSUMPTION. |
| Terms | No clause in the API Terms names presence monitoring. Bot Developer Terms 4.3 (data beyond what is essential) is engaged if the observer is operated as part of the bot service. | Reasoning from VERIFIED text |
| Product ethics | The mechanism is indistinguishable from stalkerware. A health product must not normalise it or operate the infrastructure for it. | Reasoning |

Classification: risky, and rejected. It fails functionally for every user who keeps a private last seen, and its failure is silent (the bucket "recently" looks like data).

### 4.3 Route B: a server-side session logged in as the user

Design: the user gives Dosegram their phone number, the login code and, if set, the two-step verification password. Dosegram keeps an authorised MTProto session (TDLib or tdlight) for that account and logs the account's own status changes.

| Aspect | Finding | Status |
| --- | --- | --- |
| Technical feasibility | Third-party clients may log in with "Telegram codes, Fragment codes, email codes, future auth tokens, QR codes, and passkeys". With two-step verification enabled, `auth.signIn` returns `SESSION_PASSWORD_NEEDED` and the SRP password check is required. | VERIFIED, https://core.telegram.org/api/auth |
| Whether the session can read its owner's exact presence | Likely, but unconfirmed. | ASSUMPTION (section 3.3) |
| What the session can do | An authorised user session has the full client API: read every cloud chat, contact and media file, send and delete messages, change privacy settings, and terminate other sessions. MTProto has no scope that limits a session to presence. | ASSUMPTION for the exact method list; the absence of scoped user authorisation is consistent with the auth documentation read in this run. |
| Login-code handling | Telegram's login message warns users not to give the code to anyone. Reports say a code that is sent or forwarded inside Telegram is invalidated, which would block collecting it through the bot chat. | ASSUMPTION (secondary sources only) |
| Conflict with repository rules | `AGENTS.md`: "Do not collect passwords in chat." Collecting the two-step verification password is the same class of action. | VERIFIED (repository file) |
| Terms, forbidden parts | Acting for the user "without the user's knowledge and consent" (1.4). Causing last seen or online to be displayed incorrectly (1.4); a session that marks the user online at night would do exactly that. Using the collected data to train or tune a model (1.5). | VERIFIED text |
| Terms, permitted parts | Third-party clients with their own `api_id` are allowed (2.1). A session that the user knowingly authorised and that does not alter status is not named as a breach. | VERIFIED text; the reading that a headless single-purpose session counts as a compliant "client app" under 1.3 is an ASSUMPTION. |
| Enforcement exposure | The account "put under observation" is the user's personal account. A ban costs the user their Telegram identity, not Dosegram's. | VERIFIED quotation; consequence is reasoning. |
| Security blast radius | A breach of Dosegram would expose live sessions to every connected user's private messages, including third parties who never consented. Bot Developer Terms 4.3 limits collection to "what is essential". | Reasoning from VERIFIED text |
| Trust | The session appears in the user's device list as a foreign login. A medication product that asks for the Telegram login code trains users to hand codes to bots, which is the pattern account-takeover scams use. | Reasoning |

Classification: the status-altering and non-consensual variants are forbidden. The consensual, read-only variant is risky, and rejected. It is disproportionate: a credential with full account access, held server-side, to obtain two timestamps per day that the product can ask the user for directly.

### 4.4 Route C: Telegram Business connection

A user with Telegram Business can connect a bot to their account. `BusinessConnection` and `BusinessBotRights` contain no presence right (VERIFIED, section 3.1). The connection would also expose the user's private chats to the bot, which is unrelated to sleep. Classification: clean in terms of rules, useless for presence, and rejected for sleep.

### 4.5 Classification summary

| Mechanism | Class | Decision |
| --- | --- | --- |
| Bot API or Mini App reading last seen | Impossible | Not available |
| Observer user account watching users' status | Risky | Reject |
| User-authorised server-side session, read-only | Risky | Reject |
| Any session that changes or hides the user's status, or acts without consent | Forbidden (API Terms 1.4) | Reject |
| Training a model on data taken from Telegram | Forbidden (API Terms 1.5, Bot Developer Terms 4.3) | Reject |
| Collecting the login code or two-step password in chat | Forbidden by repository rule; unsafe | Reject |
| Timestamps of the user's own interactions with the Dosegram bot, with explicit opt-in | Clean (Bot Developer Terms 4.3 consent clause) | Use |
| Mini App foreground events reported to the Dosegram backend, with opt-in | Clean | Use |
| Explicit check-ins and a bedtime mode | Clean | Use |
| User-initiated file imports and OAuth wearable connections | Clean | Use, staged |

## 5. Signals the product can legitimately obtain

All signals below are first-party: the user produces them by using Dosegram, or grants them explicitly.

| # | Signal | How it is obtained | What it proves | Precision | Main bias |
| --- | --- | --- | --- | --- | --- |
| S1 | Message to the bot | `Message.date` (Unix seconds) | The user was awake and using Telegram at that instant | 1 s | Sparse. Most users send few messages. |
| S2 | Button press | `callback_query` receipt time on the server | Awake at that instant | About 1 s plus delivery delay | No timestamp in the update; webhook retries can delay receipt. |
| S3 | Reminder acknowledgement (taken, skip, snooze) | The same callback, linked to an occurrence | Awake at that instant; anchors morning and evening for users with scheduled doses | As S2 | Times cluster at reminder times, not at true bed or wake time. |
| S4 | Mini App launch | `initData.auth_date`, validated on the backend | Awake at that instant | 1 s | Only when the Mini App is opened. |
| S5 | Mini App foreground periods | `activated` and `deactivated` events and periodic heartbeats sent by the Mini App to the backend while `isActive` is true | Awake during the active period | Heartbeat interval | No "closed" event; a desktop tab can stay active unattended (ASSUMPTION). Count only heartbeats that follow user input. |
| S6 | "Going to sleep" check-in | One tap on a reply-keyboard or inline button, or a command | Stated intention to sleep at that time | 1 s, but it marks bedtime, not sleep onset | Users tap early or forget. |
| S7 | "I'm up" check-in | One tap, or the first interaction of the day treated as a prompt for it | Stated wake time | 1 s, but may be tapped after waking | Late tapping. |
| S8 | Retrospective entry | Morning prompt: "When did you go to sleep and wake up?" with editable times | Self-reported episode | 5 to 15 min | Recall and rounding. |
| S9 | Self-rated sleep quality | One tap, 1 to 5 | Subjective rating | Ordinal | Mood-dependent. Kept as a subjective rating, never a score. |
| S10 | File import | The user uploads an export through the Mini App | Device-recorded episodes | Device-dependent | Stale; manual. |
| S11 | Wearable API | OAuth grant; webhooks or polling | Device-recorded episodes, stages, device scores | Device-dependent | Vendor access rules (section 9). |
| S12 | Bot blocked or unblocked | `my_chat_member` update | The user stopped or resumed the bot | n/a | Used only to suspend inference. |

Signals that are not available and must not be simulated: Telegram last seen or online status, read receipts for bot messages, typing indicators, device sensors, and the device time zone through the bot (the Mini App may supply it, see section 3.2).

Bot-originated events (reminders sent, messages delivered) are not evidence of wakefulness. Only user-originated events count.

## 6. Evidence from the literature on passive inference

- Borger, Huber and Ghosh, "Capturing sleep-wake cycles by using day-to-day smartphone touchscreen interactions", npj Digital Medicine 2019, https://pmc.ncbi.nlm.nih.gov/articles/PMC6662846/. VERIFIED on 2026-11-12: 79 analysed participants and about 1,400 nights. Touches were binned into 60-second active or rest bins; gaps longer than 2 hours were extracted; a 24-hour cosine fit located the 6-hour period of least activity; a gap counted as sleep if it overlapped that period by at least 10 percent (36 minutes); the longest qualifying gap was selected. Agreement with wrist actigraphy: sleep onset R² 0.84 and wake time R² 0.90 on pooled nights, but sleep duration R² only 0.28. Nearly all users touched their phones during actigraphy-defined sleep. Naps, shift workers and people with insomnia were not validated. The sample was young (mean age 23) and Android-only.
- Implication for Dosegram: that study used every touch on the phone. A bot sees a few interactions per day. Edge timing will therefore be much looser than in the study, and duration from gaps alone is unreliable even with full touchscreen data. The design in section 7 treats a passive gap as a bound, not a measurement.
- Self-reported sleep duration tends to exceed actigraphy-measured duration (Lauderdale et al., Epidemiology 2008; not re-read in this run). Tier 2 values are therefore labelled "self-reported".
- Windred et al., "Sleep regularity is a stronger predictor of mortality risk than sleep duration", Sleep 2024, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10782489/. VERIFIED from the abstract: about 61,000 UK Biobank participants with one week of actigraphy; regularity measured by the Sleep Regularity Index; the association is observational.
- Sletten et al., "The importance of sleep regularity: a consensus statement of the National Sleep Foundation sleep timing and variability panel", Sleep Health 9(6), 2023, https://pubmed.ncbi.nlm.nih.gov/37684151/. VERIFIED from the abstract: consistency of sleep onset and offset timing is important for health, safety and performance; catch-up sleep on non-work days may be beneficial when weekday sleep is insufficient.
- These two sources support making regularity, not a composite score, the headline metric. Regularity is also the metric that diary-grade data supports best.

## 7. Inference algorithm for a passive sleep window

This section is a design proposal. All thresholds are initial values to be tuned on the owner's own data and are configuration, not constants of nature.

### 7.1 Principle: a gap is a bound

Let `a` be the last user-originated event before a long gap and `b` the first user-originated event after it. Then:

- Sleep onset is not earlier than `a`.
- Final wake is not later than `b`.
- Sleep duration is at most `b - a`.

These three statements are true whatever the user did in between. Nothing in passive data gives a lower bound. The product therefore names the passive result a "quiet window" and words it as "at most". A quiet window of 5 hours is strong evidence of a short night. A quiet window of 12 hours says only that the user did not use Dosegram for 12 hours.

### 7.2 Inputs

- The user-originated event stream S1 to S5 as UTC instants.
- Explicit events S6 to S8.
- The user's IANA time zone history (zone id and the instant it took effect).
- The user's declared profile: typical bedtime and wake time (optional), work-day pattern (optional), "irregular or shift schedule" flag (optional).
- Service outage intervals from the worker heartbeat that `docs/DESIGN.md` already defines (`lastHealthyTick`).

### 7.3 Steps

1. Preconditions. Passive inference runs only if the user opted in (section 10) and the bot is not blocked. A sleep day with a service outage longer than 30 minutes inside the candidate window is marked `unknown` for passive inference.
2. Sleep-day frame. A sleep day `D` is the local calendar date on which the main sleep ends. The search frame for `D` is from 18:00 local on `D - 1` to 18:00 local on `D`, in the zone in force at each instant. The 18:00 boundary matches the day change that Oura documents for late naps (VERIFIED, Oura OpenAPI 1.43, `PublicSleepType`). For users with the irregular-schedule flag, the frame is the 24 hours ending at the most recent user-originated event after a gap of at least 3 hours.
3. Explicit data wins. If the frame contains a "Going to sleep" and a later "I'm up" check-in, or a retrospective entry, that episode is the result with tier 2 and the passive step only adds consistency checks (for example, an interaction 40 minutes after "Going to sleep" moves the onset bound and is recorded as "active after bedtime").
4. Candidate gaps. Sort user-originated events in the frame. Every interval between consecutive events of at least `MIN_GAP = 3 h` is a candidate.
5. Night-waking merge. An isolated burst inside a longer quiet period is bridged: if a burst has at most 3 events, spans at most 10 minutes, and is followed by a further quiet period of at least 60 minutes, the gaps on both sides are merged into one candidate with `interrupted = true` and the burst is kept as an awake interval. A dose acknowledgement at 03:10 therefore does not split the night into two "sleeps". Reminders that Dosegram itself sends at night are a confound; the card says so when the burst is a reminder acknowledgement.
6. Rest-phase prior. Determine the user's expected rest phase, in order of preference: the circular mean of onset and wake of the last 14 confirmed episodes (at least 5); the declared typical bedtime and wake time; the 6-hour window of least activity over the last 14 days (the cosine-fit idea from the cited study); or, with fewer than 7 days of data, 23:00 to 07:00 local. For users with the irregular-schedule flag there is no prior and step 7 uses length only.
7. Selection. Keep candidates that overlap the expected rest phase by at least 60 minutes. Select the longest. If none qualifies, the result is "no estimate".
8. Plausibility. If the selected window is longer than `MAX_PLAUSIBLE = 14 h`, the result is "no estimate" (the user was simply away from the bot). If one edge is explicit and the other is passive, keep the explicit edge and apply the bound wording to the passive edge only.
9. Output. `quietWindow = [a, b]`, `upperBoundDuration = b - a - sum(awake bursts)`, `interrupted`, `confidence` (7.4), `tier`, and the list of evidence event kinds (never their content).

### 7.4 Confidence score

Confidence expresses how close the bound is likely to be to the truth. It is not a probability of a medical fact.

For each passive edge, edge sharpness measures whether activity ran up to the edge:

- `k_on` = number of user-originated events in the 60 minutes before `a`; `s_on = min(1, k_on / 3)`.
- `k_wake` = number of user-originated events in the 60 minutes after `b`; `s_wake = min(1, k_wake / 3)`.
- An explicit edge has sharpness 1.

Duration plausibility `p_dur`: 1 for 4 h to 11 h; falling linearly to 0 at 3 h and at 14 h.

Prior agreement `p_prior`: `exp(-(|a - expectedOnset| + |b - expectedWake|) / 180 min)`; 0.5 when there is no prior.

| Evidence | Confidence |
| --- | --- |
| Sensor episode (tier 3) | 1.0, shown as the device's own data |
| Both edges explicit or retrospectively entered (tier 2) | 0.9; 0.7 if a passive event contradicts an edge by more than 30 minutes |
| Passive estimate confirmed by the user without change | 0.8, promoted to tier 2 |
| One explicit edge, one passive edge | `0.5 + 0.3 * s_passive * p_dur` |
| Both edges passive (tier 1) | `0.5 * ((s_on + s_wake) / 2) * p_dur * (0.5 + 0.5 * p_prior)`, so never above 0.5 |

Presentation bands: high (at least 0.8), medium (0.5 up to 0.8), low (0.25 up to 0.5). Below 0.25 nothing is shown and the day is offered for manual entry. Passive-only data can never reach the medium band. That cap is the central honesty rule of the design.

### 7.5 Confirmation and correction flow

1. Trigger. The first user-originated interaction after the quiet window, or the user's morning summary time, whichever comes first. Never during quiet hours. At most one sleep prompt per sleep day.
2. Prompt (copy is illustrative): "Quiet from 00:40 to 07:10, at most 6 h 30 min. Is that about when you slept?" with buttons "Yes", "Adjust", "Not sleep" and "Stop asking".
3. "Yes" promotes the episode to tier 2 with confidence 0.8.
4. "Adjust" opens two time pickers in the Mini App, or inline buttons that move each edge by 15 or 30 minutes. The corrected episode is tier 2 with source `self_report_corrected`. The original passive estimate is kept in the audit record so the algorithm's error can be measured.
5. "Not sleep" discards the estimate and records a negative label for that weekday and hour to improve the prior.
6. "Stop asking" disables prompts, not passive estimation; settings offer both switches separately.
7. Fatigue control. After three consecutive unanswered prompts, prompting pauses for 7 days and the weekly report offers bulk review instead.
8. Late edits. Any past day can be edited from the Mini App calendar. Edits are appended as new revisions, consistent with the append-only style of `docs/DESIGN.md`.

### 7.6 Time zones, travel and daylight saving time

- Store every event as a UTC instant plus the zone id in force and the derived local date and local time. This matches the existing occurrence model in `docs/DESIGN.md` (`local_date`, `local_time`, `tz`, `dst_kind`).
- Durations are always differences of instants. On a spring-forward night an 8-hour sleep spans 9 clock hours; on a fall-back night it spans 7. The card shows clock times and the true duration, and adds a note on transition nights.
- The sleep day is the local date of the wake edge in the zone in force at that instant.
- Regularity metrics (section 8) use local clock time in the zone in force. A sleep day is flagged `tz_shift` when the zone offset at the wake edge differs from the offset at the previous day's wake edge, including DST transitions. Flagged days are shown but excluded from regularity and social jet lag for that day and the next one.
- Travel. When the user changes time zone (through settings, or a Mini App prompt when the device zone differs from the stored zone), the rest-phase prior is re-anchored to the new local clock and its weight is halved for 3 days.
- The bot cannot detect a time zone change by itself (VERIFIED: no time zone in the Bot API). A user who travels without updating the zone gets shifted estimates. The Mini App should compare the device zone with the stored zone on every launch and ask.

### 7.7 Shift workers and irregular schedules

- A profile flag "My schedule is irregular or I work shifts" removes the night prior, the 18:00 frame and the default 23:00 to 07:00 assumption.
- Main sleep becomes the longest qualifying episode in a rolling 24 hours. A second episode of at least 3 hours in the same 24 hours is recorded as a second main sleep, not a nap.
- Social jet lag and "bedtime consistency" are not shown as better-or-worse judgements for these users. The product shows duration per 24 hours, a 7-day total, and regularity relative to the user's own declared shift pattern if one is entered.
- An optional work-calendar (work days, free days, shift type per day) is the only reliable input for work-day and free-day comparisons. Without it the product does not guess.

### 7.8 Naps

- Naps are recorded only from an explicit "Nap" check-in (start and end, or a duration), from a retrospective entry, or from sensor data.
- A passive daytime gap is never classified as a nap. A three-hour daytime gap in bot use is normal behaviour.
- A nap contributes to "total sleep per 24 hours" and is excluded from bedtime, wake time, midpoint and regularity of the main sleep.

### 7.9 Failure modes

| Failure mode | Effect | Mitigation |
| --- | --- | --- |
| Sparse use (for example, one morning and one evening dose) | The quiet window equals the gap between reminders, often 10 to 12 hours | Edge sharpness is 0 to 0.33, so confidence stays below 0.25 and nothing is shown; the user is offered check-ins instead. |
| The user uses the phone but not Dosegram late at night | The onset bound is too early, so the bound is loose but still true | "At most" wording; never state an onset time as fact at tier 1. |
| Acknowledgement by someone else (carer, partner) | False wake evidence | Out of scope to detect; the correction flow covers it. |
| Night reminders wake the user | An interrupted night caused by the product | Night-waking merge; the card names the reminder; suggest quiet hours for non-critical reminders. No clinical advice about dose timing. |
| Service or webhook outage | A false quiet period | Mark outage-overlapping days `unknown`; use `Message.date` for messages rather than receipt time. |
| Mini App left open on a desktop | False activity | Count Mini App heartbeats only after user input in the preceding interval. |
| Device offline (flight, no coverage) | A quiet period that is not sleep | Plausibility cap of 14 hours; confirmation flow. |
| Time zone not updated after travel | Shifted local times and wrong prior | Device-zone check in the Mini App; `tz_shift` flag. |
| The user is awake all night away from the phone | A quiet window with no sleep | The bound remains true ("at most"); only confirmation can correct it. |
| Bot muted or archived | Fewer acknowledgements, later wake edge | Same as sparse use. |
| The user gaming a streak | Check-ins that do not reflect sleep | No rewards tied to sleep duration; rewards, if any, attach to logging, not to values. |
| Two main sleeps (biphasic, infant care) | The longest-gap rule drops one | The night-waking merge handles short bursts; longer wake periods need explicit entries or the irregular-schedule flag. |

## 8. Metrics by data-quality tier

Tiers: T0 no data; T1 passive only; T2 explicit or confirmed (diary grade); T3 sensor import.

Definitions used below:

- Midpoint: the instant halfway between onset and wake, expressed as local clock time.
- Bedtime consistency and wake consistency: the circular standard deviation of onset and of wake clock times over a window. Windows: 7, 14 and 28 sleep days. Minimum data: 5 of 7, 10 of 14, or 20 of 28 days.
- Sleep Regularity Index (SRI), Phillips et al., Scientific Reports 2017: the percentage probability that a person is in the same state (asleep or awake) at any two time points 24 hours apart, rescaled so that 100 is perfectly regular and 0 is random. In epoch form: `SRI = -100 + (200 / (M * (N - 1))) * sum over days i and epochs j of [state(i, j) == state(i + 1, j)]`, with `M` epochs per day and `N` days. The formula is quoted from memory of the paper and must be checked against it before implementation. The verbal definition is VERIFIED from Windred et al. 2024.
- Social jet lag (Wittmann et al., Chronobiology International 2006): `SJL = |MSF - MSW|`, the absolute difference between the midpoint of sleep on free days and on work days. Sources disagree on sign convention, so the product stores the signed value `MSF - MSW` and shows the absolute value. The sleep-corrected free-day midpoint (MSFsc, Roenneberg) is not used in the first version.

| Metric | T1 passive | T2 explicit or confirmed | T3 sensor |
| --- | --- | --- | --- |
| Quiet window ("at most N hours") | Show, labelled as an upper bound | Not needed | Not needed |
| Last activity and first activity times | Show as "activity rhythm", not as sleep | Show as context | Show as context |
| Time in bed or sleep duration | Do not show as a duration | Show, labelled "self-reported" | Show the device's total sleep time and time in bed |
| Bedtime and wake time | Do not show as facts | Show | Show |
| Duration against the user's own goal, and weekly sleep debt against that goal | No | Show | Show |
| Bedtime and wake consistency (standard deviation) | Show only as "consistency of first and last activity", low confidence | Show | Show |
| Sleep Regularity Index | No | Show when at least 5 of 7 days exist; computed from binary sleep or wake episodes at 5-minute epochs | Show |
| Social jet lag | No | Show when the window has at least 3 work days and 2 free days, with work-day labels from the user | Show under the same condition |
| Naps and total sleep per 24 hours | No | Show (explicit naps only) | Show |
| Self-rated quality (1 to 5) | n/a | Show as the user's own rating | Show alongside device data |
| Sleep stages (REM, deep, light), awake time after onset, awakening count, sleep latency, sleep efficiency | Never | Never | Show as imported, attributed to the device |
| Sleep score | Never | Never | Show the vendor's score verbatim with attribution (for example "Oura sleep score"); never recompute or blend it |
| Heart rate, heart-rate variability, blood oxygen, respiratory rate, snoring, apnea indicators | Never | Never | Out of scope for the first sensor stage; if imported later, display only, no interpretation |
| Diagnostic or clinical wording ("insomnia", "sleep disorder", "you should sleep more") | Never | Never | Never |

Additional rules:

1. Every sleep card and chart states its tier in words ("estimated from activity", "your check-ins", "from your Oura ring") and renders low-confidence intervals as visibly uncertain (for example a hatched or faded bar from `a` to `b`). The image generator must take the tier and confidence as inputs.
2. Dosegram defines no composite sleep score at any tier. The headline numbers are duration against the user's goal and regularity.
3. Cross-domain statements (sleep against mood, dose timing or water intake) are descriptive only, need at least 14 paired days, and never use causal wording.
4. The duration goal is set by the user. Whether the product may show a general public reference such as "7 or more hours for adults" (Watson et al., consensus of the American Academy of Sleep Medicine and Sleep Research Society, 2015; not re-read in this run) is an open question (section 12).

## 9. Optional imports and wearable integrations

A Telegram bot and a Mini App run in a chat client and a webview. They cannot call on-device health stores. That single fact orders the options.

| Source | Access model | Requirements and limits | Status | Fit for Dosegram |
| --- | --- | --- | --- | --- |
| Oura API v2 | OAuth2 authorisation code. Authorise `https://cloud.ouraring.com/oauth/authorize`; token `https://api.ouraring.com/oauth/token`. Scopes: `email`, `personal`, `daily`, `heartrate`, `workout`, `tag`, `session`, `spo2`, `heart_health`. Endpoints `/v2/usercollection/sleep`, `/v2/usercollection/daily_sleep`, `/v2/usercollection/sleep_time`. Webhooks at `/v2/webhook/subscription` authenticated with `x-client-id` and `x-client-secret`; notifications arrive "approximately 30 seconds after data syncs from the mobile app". | "Personal access tokens were deprecated in December 2025 and are no longer available". "API Applications are limited to 10 users before requiring approval from Oura." Every request needs a `User-Agent` header. Rate limits apply per access token and per application; the numbers are returned in `X-RateLimit-*` headers and are not stated in the specification. Sleep record fields include `bedtime_start`, `bedtime_end`, `total_sleep_duration`, `time_in_bed`, `efficiency`, `latency`, `deep_sleep_duration`, `light_sleep_duration`, `rem_sleep_duration`, `awake_time`, `sleep_phase_5_min`, `type`. `type` is one of `deleted`, `sleep`, `long_sleep`, `late_nap`, `rest`. | VERIFIED, OpenAPI 1.43 at https://cloud.ouraring.com/v2/static/json/openapi-1.43.json | Good first OAuth integration. The 10-user cap is enough for the owner's own use; wider use needs Oura approval. |
| Oura membership | "Gen3 and later users without active Oura Membership can't access their data through the Oura API." | — | VERIFIED, https://support.ouraring.com/hc/en-us/articles/4415266939155-The-Oura-API | Show a clear error when the grant returns no data. |
| Google Health API (successor to the Fitbit Web API) | Google OAuth 2.0. Data type `sleep`; read scope suffix `.sleep.readonly`. | "On September 30, 2026, support for the legacy Fitbit Web API ended. On November 2, 2026, the legacy Fitbit Web API and Fitbit OAuth endpoints will be turned off"; requests "will fail and return an HTTP 410 Gone status code". "All Google Health API scopes are categorized as Restricted, necessitating a privacy and security review for access." Sleep sessions are paged (25 per page). | VERIFIED, https://developers.google.com/health/about and https://developers.google.com/health/data-types | Do not build against the legacy Fitbit API; it is past its turn-off date. The Restricted-scope review is the main cost. |
| Google restricted-scope review cost | Restricted scopes can require a recurring third-party security assessment. | — | ASSUMPTION | Check before committing; this may be out of proportion for a personal project. |
| Withings Public API | OAuth2. Sleep data through "Sleep v2 - Get" and "Sleep v2 - Getsummary". Notifications by category number (`appli`): 44 "Sleep summary" (scope `users.activity`; "duration, sleep state, deep/light state duration (no REM), and wakeup counts"), 50 "Bed in" and 51 "Bed out" (scope `user.sleepevents`, Withings Sleep sensor), 52 sensor inflated. Categories 98 and 99 (10-minute bed occupancy) require "a Withings contract". | The public API is "designed for individuals and partners without a formal contract". Some sleep products are region-limited ("Sleep Analyzer is available only in the EU, Australia and the UK"). | VERIFIED, https://developer.withings.com/developer-guide/v3/data-api/all-available-health-data/ and the notification-content page | Second OAuth integration. Bed-in and bed-out events are the closest thing to a true presence signal. |
| Withings OAuth URLs and rate limits | — | — | ASSUMPTION (not read in this run) | Read the API reference before design. |
| Garmin Connect Developer Program | Tiers: Personal (own data, "Pull only", personal API key); Starter business (Health API in JSON, delivery "Push only", historical data "Not included", user capacity "Limited"); Enterprise (push or ping/pull, historical data "1 month +", unlimited users, commercial terms). | Business tiers need an application and approval. | VERIFIED, https://developer.garmin.com/gc-developer-program/overview/ | Last. Push-only delivery needs a public webhook. |
| Garmin program availability in 2026 | Secondary reports describe a pause of new access in September 2026 and a later reopening. | — | ASSUMPTION (secondary sources, conflicting) | Confirm on the Garmin portal before planning. |
| Android Health Connect | On-device store read by an Android app through the Health Connect SDK. `SleepSessionRecord` with stage constants `STAGE_TYPE_UNKNOWN` 0, `AWAKE` 1, `SLEEPING` 2, `OUT_OF_BED` 3, `LIGHT` 4, `DEEP` 5, `REM` 6, `AWAKE_IN_BED` 7. | By default an app reads "up to 30 days prior to when any permission was first granted"; older data needs `PERMISSION_READ_HEALTH_DATA_HISTORY`; background reads need `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`. | VERIFIED, https://developer.android.com/health-and-fitness/health-connect/read-data and the `SleepSessionRecord` reference | Requires a native Android companion app. Not reachable from a bot or a Mini App. |
| Health Connect cloud access | There is no server-side REST API for Health Connect. | — | ASSUMPTION (only the on-device SDK is documented) | — |
| Apple Health (HealthKit) | On-device only; no web API. The Health app can export all data as a zip archive containing `export.xml`, where sleep is recorded as sleep-analysis category samples. | Exports can be very large. | ASSUMPTION (Apple documentation could not be read in this run) | File import only, unless a native iOS companion is ever built. |
| Samsung Health | The app offers "Download personal data"; Samsung states the download can take up to 24 hours. | File names and columns differ between secondary sources. Sync of sleep sessions to Health Connect is reported by secondary sources. | Export feature VERIFIED only through a search-result summary of Samsung support; format and Health Connect sync are ASSUMPTION | File import after inspecting a real export from the owner's device. |
| WHOOP, Polar, Ultrahuman and others | Vendor OAuth APIs. | — | ASSUMPTION (not researched) | Not planned. |
| Aggregators (Terra, Thryve, Open Wearables and similar) | One API in front of many vendors. | Adds a further processor of health data and a subscription cost. | ASSUMPTION (vendor blogs only) | Rejected for now; reconsider only if more than three vendor integrations are wanted. |

Design consequences:

1. File imports must not travel through the bot chat. The bot download limit is 20 MB (VERIFIED) and a health export is frequently larger. The Mini App should parse the export in the browser and upload only the normalised sleep episodes. This also keeps unrelated health records off the server.
2. Imported episodes keep their source, device and vendor identifiers and are never edited in place. A user correction creates a tier 2 override that references the imported record.
3. When sensor and self-reported data exist for the same sleep day, the sensor episode is primary for metrics and the self-report is kept for the subjective rating.
4. Recommended order: file import (Apple Health, Samsung Health, generic CSV), then Oura, then Withings, then Google Health API, then Garmin. A native Health Connect or HealthKit companion app is a separate product decision and is not recommended in the first plan.

## 10. Consent, privacy and data handling

- Sleep estimation from interaction timestamps is profiling of behaviour for a health purpose. Bot Developer Terms 4.3 permits use of data "submitted directly and voluntarily" when users are clearly informed and give "individual, explicit, active and revocable consent" (VERIFIED). Passive sleep estimation is therefore off by default and has its own opt-in screen, separate from general onboarding, with a plain statement of what is used ("the times you tap or message this bot") and what is not ("your Telegram last seen, your other chats").
- Sleep data is very likely health data under data-protection law (special category under GDPR Article 9). ASSUMPTION; this is not legal advice. The plan should treat sleep, mood, medication and nutrition records uniformly as sensitive.
- Data minimisation. Raw interaction timestamps used for inference are retained for a short rolling window (proposed: 35 days, enough for a 28-day prior) and then reduced to per-day episodes. Bot Developer Terms 4.2 requires deletion on request and when retention is no longer necessary (VERIFIED).
- Encryption at rest with separately stored keys is a platform requirement (Bot Developer Terms 4.4 (a), VERIFIED).
- No machine-learning model is trained across users on this data. The algorithm in section 7 is deterministic and uses only the same user's history. This keeps the feature clear of API Terms 1.5 and Bot Developer Terms 4.3 (VERIFIED text; the reading is this brief's).
- Logs follow the repository rule: no message bodies, medication names or notes. Sleep evidence records store event kind and instant only.
- Every estimate offers "How was this estimated?" with the evidence kinds and the tier. Every tier can be switched off independently, and "delete my sleep data" is a first-class action.
- OAuth tokens for wearable APIs are secrets of the same class as the bot token: encrypted, never logged, revocable from the Mini App.

## 11. Recommendation

Decision proposed for acceptance:

1. Do not use Telegram last seen or online status in any form. Dosegram will not operate user sessions, observer accounts, TDLib or tdlight logins of users, or any MTProto client. Rationale: the Bot API cannot read presence; the only working route requires a full-account credential; presence is a poor sleep proxy. Rejected alternative: a user-authorised read-only server-side session (section 4.3).
2. Build sleep as a three-tier feature with explicit check-ins as the primary input, passive quiet-window estimation as an opt-in assistant that proposes and never asserts, and sensor imports as the only path to stages and device scores. Rejected alternative: passive-only "automatic sleep tracking", because the evidence in section 6 and the sparse event stream cannot support a duration.
3. Make regularity (bedtime and wake consistency, SRI, social jet lag) and duration against the user's own goal the headline metrics. Define no Dosegram sleep score. Rejected alternative: a composite 0 to 100 score, because without sensors it would be invented precision and would imitate wearable scores that measure different things.
4. Stage the work:
   - Stage S1: check-ins ("Going to sleep", "I'm up", nap), bedtime-mode keyboard, retrospective entry and edit in the Mini App, duration and consistency metrics, sleep card image with tier labelling.
   - Stage S2: opt-in passive quiet-window estimation with the confirmation flow, Mini App foreground signal, time zone and DST handling, irregular-schedule mode.
   - Stage S3: SRI, social jet lag with a work-day calendar, cross-domain descriptive views (sleep with mood and dose timing).
   - Stage S4: file imports parsed in the Mini App, then Oura and Withings OAuth.
   - Stage S5 (optional): Google Health API and Garmin, subject to their review and approval processes.

How this answers the owner's request: the product still "notices" a likely sleep window without the user doing anything, using the user's own activity in Dosegram instead of Telegram's last seen. It presents that as a bound and asks for one tap to confirm. The owner can overrule item 1; section 4.3 lists exactly what that would require and what it would put at risk.

## 12. Open questions

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Accept the rejection of every last-seen route (observer account, user session)? | Yes. If the owner still wants an experiment, limit it to the owner's own account, outside the product, and never ship it. |
| Q2 | Should passive quiet-window estimation be on by default? | No. Off by default with a dedicated opt-in, because of Bot Developer Terms 4.3 and because it is behavioural profiling. |
| Q3 | Should a "bedtime mode" also set a Telegram emoji status through `setUserEmojiStatus`? | Optional, later. It needs a separate permission prompt and reveals bedtime to the user's contacts. |
| Q4 | May the product show a general public reference for adult sleep duration, or only the user's own goal? | User's own goal only in the first version, to stay clearly non-clinical. Revisit with wording review. |
| Q5 | Is a native Android or iOS companion app (for Health Connect or HealthKit) in scope? | No for this plan. File imports and OAuth wearables cover the need without a second client. |
| Q6 | Which wearable does the owner actually use? | Build that integration first after file import. If it is a Samsung Galaxy Watch, the only bot-compatible route today is the Samsung Health export file. |
| Q7 | Retention of raw interaction timestamps: is 35 days acceptable? | Yes, as a default, configurable down to 0 (which disables the learned prior). |
| Q8 | Is Dosegram single-user (owner only) or multi-user? | This changes the weight of the Oura 10-user cap, the Google restricted-scope review and the privacy-policy obligations. Decide before stage S4. |
| Q9 | Should night-time medication reminders be flagged on the sleep card as a cause of interruption? | Yes, as a neutral fact ("a reminder was acknowledged at 03:10"), with no advice on changing dose times. |
| Q10 | Legal classification of inferred sleep data in the owner's jurisdiction. | Treat as sensitive health data regardless; obtain a short legal review before any multi-user release. |

## 13. Assumptions to verify before implementation

| Assumption | How to verify |
| --- | --- |
| Bots receive no read receipts in private chats | Confirm in a live private chat; watch for any new update type in Bot API change logs. |
| Mini App `deactivated` fires reliably on each client (Android, iOS, Desktop, Web) and a closing request can be lost | Device test matrix during the Mini App spike. |
| `Intl` time zone is correct inside each Telegram webview | Same test matrix. |
| A user session can read its own account's exact status without altering it | Not to be verified; the route is rejected. Recorded only so that nobody relies on it. |
| Login codes sent inside Telegram are invalidated | Not to be verified; the route is rejected. |
| Phillips 2017 SRI formula as written in section 8 | Read the paper (Scientific Reports 7, 3216) before implementing. |
| Lauderdale 2008 and Watson 2015 details | Read the papers before quoting numbers in product copy. |
| Apple Health export structure; Samsung Health export file names and columns; Samsung Health to Health Connect sync | Inspect real exports from the owner's devices. |
| Withings OAuth endpoints and rate limits; Oura numeric rate limits | Read the vendor references; read `X-RateLimit-*` headers from a sandbox call. |
| Google Health API restricted-scope review cost and timeline | Google OAuth verification documentation and a test project. |
| Garmin program currently accepts applications | Garmin developer portal. |
| Sleep data is special-category data in the applicable jurisdiction | Legal review. |

## 14. Sources

All checked on 2026-11-12.

| Source | URL | Used for |
| --- | --- | --- |
| Telegram Bot API (10.4, 2026-11-09) | https://core.telegram.org/bots/api | Absence of presence; `User`, `ChatFullInfo`, `BusinessConnection`, `BusinessBotRights`, `Update`, `CallbackQuery`, `Message.date`, `getFile` limit, `setUserEmojiStatus` |
| Telegram Mini Apps | https://core.telegram.org/bots/webapps | `WebAppInitData`, `isActive`, `activated`, `deactivated`, event list, `DeviceStorage`, emoji status access |
| Telegram FAQ | https://telegram.org/faq | "Bots cannot see your last seen time"; approximate buckets; reciprocity; Premium; 30-second exceptions; read time |
| Telegram Privacy Policy | https://telegram.org/privacy | Section 6.3, data bots receive |
| Telegram API Terms of Service | https://core.telegram.org/api/terms | Sections 1.1 to 1.5, 2.1, 2.2, 4.1, 4.2 |
| Creating your Telegram application | https://core.telegram.org/api/obtaining_api_id | Monitoring of unofficial clients; ban statements |
| Bot Platform Developer Terms | https://telegram.org/tos/bot-developers | Sections 4, 4.2, 4.3, 4.4 |
| MTProto `UserStatus` | https://core.telegram.org/type/UserStatus | Six constructors |
| `userStatusOffline`, `userStatusOnline`, `userStatusRecently` | https://core.telegram.org/constructor/userStatusOffline and siblings | Field meanings, `by_me` |
| `updateUserStatus` | https://core.telegram.org/constructor/updateUserStatus | Status update |
| `account.updateStatus` | https://core.telegram.org/method/account.updateStatus | Users only |
| `contacts.getStatuses` | https://core.telegram.org/method/contacts.getStatuses | Users only |
| `inputPrivacyKeyStatusTimestamp` | https://core.telegram.org/constructor/inputPrivacyKeyStatusTimestamp | Reciprocity and Premium rule |
| `globalPrivacySettings` | https://core.telegram.org/constructor/globalPrivacySettings | `hide_read_marks` |
| Privacy (API) | https://core.telegram.org/api/privacy | `user` and `userFull` schema |
| User authorisation | https://core.telegram.org/api/auth | Login methods, two-step verification |
| TDLib `userStatusRecently` | https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1user_status_recently.html | `by_my_privacy_settings` |
| Oura API v2 OpenAPI 1.43 | https://cloud.ouraring.com/v2/static/json/openapi-1.43.json | OAuth, scopes, endpoints, limits, sleep types |
| Oura support, "The Oura API" | https://support.ouraring.com/hc/en-us/articles/4415266939155-The-Oura-API | Membership requirement |
| Google Health API, About | https://developers.google.com/health/about | Fitbit turn-off dates, restricted scopes |
| Google Health API, data types | https://developers.google.com/health/data-types | `sleep` data type and scope |
| Withings, available health data and notifications | https://developer.withings.com/developer-guide/v3/data-api/all-available-health-data/ and https://developer.withings.com/developer-guide/v3/data-api/notifications/notification-content/ | Sleep data, `appli` categories |
| Garmin Connect Developer Program overview | https://developer.garmin.com/gc-developer-program/overview/ | Tiers |
| Health Connect, read data | https://developer.android.com/health-and-fitness/health-connect/read-data | 30-day limit, history and background permissions |
| Health Connect `SleepSessionRecord` | https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/SleepSessionRecord | Stage constants |
| Borger et al. 2019 | https://pmc.ncbi.nlm.nih.gov/articles/PMC6662846/ | Passive inference accuracy and limits |
| Windred et al. 2024 | https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10782489/ | Regularity as an outcome predictor; SRI definition |
| Sletten et al. 2023 | https://pubmed.ncbi.nlm.nih.gov/37684151/ | Consensus on regularity |
| Wittmann et al. 2006 | https://read.qxmd.com/read/16687322/social-jetlag-misalignment-of-biological-and-social-time | Social jet lag definition |
| Repository rules | `AGENTS.md`, `docs/DESIGN.md` | Password rule, logging rule, time model, heartbeat |
