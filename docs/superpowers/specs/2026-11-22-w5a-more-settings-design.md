# W5a: More tab, About, update, log out, weather and feed, notifications

Wave W5a of the Android UI/UX programme. Built on W4 (branch t3/w5-more-settings, head 171e84e). Goal: every W5a item below scores 9.5 or higher for every council member on a live lab build, judged by the "Scoring bar" in council-w3-brief.md. Sol and Astra score Sol's item IDs; Opus scores Opus's.

Source: /home/t3/fytorium-ux/w5-design-draft.md. Spyros answered Q3, Q5 and Q8 on 2026-11-19; every other recommendation stands (Q1 Sites into Plants, Q2 place search without "Use my location", Q4 stop notifications only, Q6 copy allowed, Q7 keep "Custom tasks" and use "Species without care data", Q9 login screen in W5 with a log-out confirmation, Q10 split). D19 O2 splits W5: this spec is W5a. Each decision names the draft item it implements.

Paths: `A/` = `android/app/src/main/java/com/epaflix/fytorium/`; server paths are from the repo root. Line numbers are at 171e84e.

Reused by name, not redesigned:
- the C1/C1b status line under the top bar title (`BarTitle`, `A/TaskScreen.kt:228-242`, in a bar of `barHeight()`, `:244-246`), with Try again in its explanation, and the full-screen "Update Fytorium" stop;
- drafts that survive rotation, theme change and process death (W3 round 8), and the DiscardDialog ("Discard" / "Keep editing", `A/plants/PlantsScreen.kt:453-458`), asked on Back and on a tab tap through `onFormDirty` (`A/AppShell.kt:77-86`, `:156-158`);
- DialogTitle and DialogBody (`A/DialogBody.kt:26-30`) with the fit rules C37, C39 and C41;
- receipts over the navigation bar (W2 A13a) and W1's Undo rules;
- nothing moves under the finger (W2 A1, A1c); the error line under Save (C23);
- reload when Fytorium answers again (C21);
- W4-P2 keyboard behaviour: Search or Enter runs a search and never inserts text; the window resizes for the keyboard.

UI wording below is in straight quotes. Example names are synthetic.

## 1. Scope

### Council items in W5a

| Area | Sol (and Astra) | Opus |
|---|---|---|
| More root | S42 More 4.5 | S22 More menu 3 |
| About | S43 About 5.5 | S23 About 4 |
| Required update | S2 Required update 4, F31 Required update recovery 2 | S2 Update required 6 |
| Login (Q9) | S1 Login 5.5, F1 First launch and login 5 | S1 Login 5, F1 First launch and login 5 |
| Notifications | S44 Notifications 5, S45 Reminder time 6.5, S46 Devices 5.5, F25 Notifications setup 5 | S24 My notifications 4, F11 Notifications 4 |
| Weather and feed | S47 Feed/weather settings 4.5, S48 Household settings 4, F26 Weather/feed setup 4 | S25 Feed and weather settings 3, S27 Household settings 3, F16 Feed and weather setup 3 |
| Sitter's More | F29 Sitter daily use (regression; closed in W2) | F13 Sitter use 6 |
| Offline, settings part | F30 Offline use 5.5 | F14 Offline 6 |
| Species without care data | S32 Unmatched species (first score; W3 C24 laid it out, Sol never scored it) | - |

Findings behind these rows, all in scope: Sol U12, U37, U38, U42, U43, U46, U47 (feed and location part), U8 (the W5a forms only); Opus U8 (back arrows on W5a screens), U14, U16 (W5a screens), U17, U18 (casing on W5a screens), U19. Audit F10 (W5a part: three back styles), F12 (device removal, update screen) and F13 (More as pills, two copies of the settings, raw coordinates, Refresh buttons) as listed in the draft's section 1b. I did not find the audit document that numbers these F10/F12/F13; the mapping uses the draft's description of them.

Carried notes, all in scope:
- K1 (Opus W2 r1). The feed's "Late tasks" switch does nothing on Android: Today drops that card whatever the switch says (`A/feed/HomeFeed.kt:21`).
- K2 (Opus W2 r3/r4). Today's location card opens feed settings at the top, with the coordinates below the fold.
- K3 (Sol W3 r10, not reproduced). After switching accounts through More → Log out, Plants briefly showed "Offline · updated 08:31" while online.
- K4 (Opus W3 r1). Unmatched species sits in More as a top-level entry.

Regression only, must not get worse: W2 Today feed strip and its cards; W3 C1/C1b, C19, C26 and C37-C42 on pages W5a touches; W4 receipts and the Journal; W3 C24 on Species without care data.

### Out of scope
- W5b: Sites (Sol S49-S52, F21, U35, U36; Opus S26, F9; draft D9, D10), Members and invites (Sol S53-S58, F22, F23, U39-U41; Opus F10, U25; D11), Custom tasks (Sol S59-S62, F24; Opus S29; D12), Planta import (Sol S63-S66, F28, U44, U45; Opus S30, F12, U5; D13), the two dropdown styles (Sol S67, Opus U18 dropdown part), pull to refresh on those lists (D18), and moving Sites into the Plants tab (D9 O2). W5a keeps their More rows and opens their unchanged screens.
- W6: a tab tap resetting More sub-screens (`A/AppShell.kt:84`), Back targets across tabs, Planta's "Open imported plant" switching tabs.
- The web UI. Server changes keep the web building and its tests passing, nothing more.

## 2. Current behaviour (checked against the code)

### More root, About, update, login, log out (`A/AppShell.kt`)
- More is a Column of filled `Button`s (`:282-295`): My notifications (everyone), Feed and weather settings (owner, family), Sites, one button per `HouseholdArea` (Household settings, Members for owner, Custom tasks), Unmatched species (owner, family), Import from Planta (owner), About, Log out (`:294`, no confirmation). The shell draws the "More" top bar only on the root and on About (`:123-126`). Sub-screens are saveable boolean flags (`:60-76`), all reset by `select()` (`:84`).
- About (`:277-281`): "Version x", a filled "Copy diagnostics" button, "Diagnostics stay on this device until you copy them." The top bar reads "About" with no arrow; system Back works (`:114`). The copy confirmation is the session message "Diagnostics copied" (`A/SessionController.kt:37-40`), printed as text under the content (`A/AppShell.kt:300`).
- Required update (`:269-272`): "Update Fytorium" and "This server needs a newer app. Install the latest Fytorium release." No buttons, no bottom bar (`:137`). The flag is the session's or the care state's (`A/MainActivity.kt:38`). The session check reads `/api/meta` at start and after login only (`A/SessionController.kt:23-27`, `:58`, `:64-73`). `/api/meta` needs a login (`src/lib/server/policy.ts:17-18`, `:50`), so a logged-out app never sees the stop. The session's flag is never cleared (`A/SessionController.kt:72`): from code, logging out at the stop would leave the stop on screen with no way to log in. The care flag clears on the next successful refresh (`A/Repository.kt:176`). The server refuses old apps with 426 on every `/api/` path except `/api/meta` and `/api/app/*`, and the 426 body carries `minAppVersion` (`src/hooks.server.ts:27-36`).
- Login (`A/AppShell.kt:273-276`, `:300-301`): "Your plants, together.", "Log in", the session message, "Please wait…". Messages: "Couldn't open login. Install a browser and try again.", "Login failed. Please try again.", "Can't reach Fytorium. Please try again." (`A/SessionController.kt:34`, `:56`, `:83`). The server redirects with `error=not_member`, `access_denied` or `server_error` (`src/lib/server/app-auth.ts:69-74`, `src/lib/server/auth.ts:130`, `:136`), but the app drops any redirect with an error (`A/Pkce.kt:23`), so every failure reads "Login failed".
- Log out: `ApiClient.logout()` posts `/api/app/logout` and clears the session in `finally` (`A/ApiClient.kt:110-113`). Online, the server revokes the app device (`src/lib/server/app-auth.ts:169-174`) and a trigger deletes its push row (`migrations/20261013120000_fcm.sql:19`). The app wipes Room, thumbnails, camera captures and diagnostics (`A/FytoriumApplication.kt:37-48`). Offline, the POST throws after the session is cleared, so `work()` shows "Can't reach Fytorium. Please try again." on the login screen (`A/SessionController.kt:62`, `:75-91`). From code, not seen live.
- Who is logged in: nowhere. `/api/me` returns language, member id and role, household id and name (`src/lib/server/auth.ts:15-19`, `src/routes/api/me/+server.ts`). The household name is always "My household": the column defaults to it (`migrations/20261011120000_household_auth.sql:4`) and no route renames it. Members' display names come from the login claims (`src/lib/server/auth.ts:121-123`).

### Notifications (`A/notifications/NotificationScreen.kt`)
- Text "Back" and a headline instead of a top bar (`:99-100`); two paragraphs (`:101-102`); "Enable notifications" / "Android notification settings" (`:103`); when Android blocks notifications, a red line and a second "Open Android notification settings" (`:104-107`).
- Offline and update lines are content text (`:108-109`); the result message too (`:111`). Both move everything below. "Refresh notifications" (`:113`).
- Switches Daily reminders, Weather alerts (not sitters), "Task completed by someone else", "Reminder time: 09:00", "Time zone: Europe/Oslo" (always shown), one sentence (`:114-119`). Nothing saves until "Save notification settings" (`:120`). The typed state is `remember` (`:79`): rotation or a tab tap drops it without asking.
- Time picker fixed to 24 hours (`:90`), in a plain `AlertDialog` (`:91-96`).
- Devices (`:122-134`): label, "This device", "Android app" or "Browser", an "Enable <label>" switch that saves at once, "Remove <label>" that deletes at once (`:131`; `A/notifications/NotificationRepository.kt:61-66`).
- Android device labels are the app device name, which login sets to "Fytorium Android" (`A/ApiClient.kt:48`; `src/lib/server/notifications/subscriptions.ts:47`, `:54`). Browser labels are the user agent, up to 200 characters (`subscriptions.ts:35`). The server returns `created_at` and `last_success_at` (`subscriptions.ts:14`); Android ignores them.
- From code, not seen live: removing an Android device does not last. The app registers its FCM token on every start (`A/MainActivity.kt:56`, `A/notifications/PushRegistration.kt:21`) and the server re-creates the row (`subscriptions.ts:52-54`). Debug builds never get a token (`android/app/src/debug/.../PushTokenSource.kt:8`), so the lab phone never has a "This device" row.
- Settings and devices are memory only; offline after a restart the screen is empty.

### Feed and weather settings (`A/feed/FeedSettingsScreen.kt`, shown at `A/AppShell.kt:199-209`)
- Opens from More and from Today's feed cards whose link is not a plant (`A/AppShell.kt:252`), such as the household-location card (`A/feed/FeedModels.kt:53-56`). Text "Back" (`A/AppShell.kt:201`), "Refresh settings" (`:205-206`).
- Headline "Feed settings", the feed notice as content text (`A/feed/HomeFeed.kt:112-119`), four checkboxes and "Save my feed" (`FeedSettingsScreen.kt:18-28`). Saves report "Feed settings saved." and "Location saved. Weather refreshes in the background." as content text (`A/feed/FeedRepository.kt:109-114`).
- "Weather location": one sentence, Latitude, Longitude, "Place label, optional", Save location (`FeedSettingsScreen.kt:29-49`). Family reads the label or "Location set." and "Only the owner can change it." (`:32-35`). The app accepts any finite numbers; the server checks -90..90 and -180..180 and a label up to 200 characters (`src/lib/server/weather/location.ts:15-17`). A 426 shows as a message, not the full stop.

### Household settings (`A/household/`)
- The same four switches ("My home feed") and the same three location fields, each with a Save (`HouseholdForms.kt:49-67`), through the same APIs (`HouseholdRepository.kt:54-60`). The duplicate is Android only; the server has one copy (`/api/feed/settings`, `/api/household/location`; policy `src/lib/server/policy.ts:52`, `:62`).
- A successful save only reloads (`HouseholdRepository.kt:128`): no message.
- Arrow top bar, but offline and update lines as content text and a "Refresh household settings" button (`HouseholdScreen.kt:78-85`).

### Corrections to the draft
- The button-style `Choice` (`A/TaskScreen.kt:383-393`) is used by Sites (`A/sites/SitesScreen.kt:150`), the invite Role (`A/household/HouseholdForms.kt:28`) and Species care overrides (`A/species/SpeciesCareScreen.kt:229`), not only by Sites and Household. Its removal belongs to W5b and must cover Species care too.
- The location range check is at `location.ts:15-16`, not `:24-33`.
- The household name is always "My household", so the draft's header "Spyros · Owner · Psarras household" would print a constant. W5a-P2 leaves it out.

## 3. Decisions

### More root (draft D1, D16, D17; Sol S42, U46; Opus S22, U19, K4)

- W5a-P1. More is one scrolling list. Top bar: title "More" and the C1 line from Today's state (`CareState.connectionLine`). Every entry is a 72 dp two-line row (Material `ListItem`): a decorative leading icon, the title, a one-line description. The whole row is one tap target. No filled buttons. Order for the owner:
  1. the header (W5a-P2);
  2. heading "You": Notifications, "Reminders, alerts and devices";
  3. heading "Household": Weather and feed, "Location and cards on Today"; Sites, "Where your plants live"; Members, "People and invites"; Custom tasks, "Your own repeating tasks";
  4. heading "Tools": Import from Planta, "Bring plants over from Planta"; Species without care data, "Species with no care data from sources";
  5. a divider, then About, "Version, data sources and diagnostics";
  6. "Log out", a 48 dp text button, last.
  Family sees the same without Members and Import from Planta. The sitter list is W5a-P3. The Household settings entry goes (W5a-P15). Species care's title in this mode becomes "Species without care data" (`A/species/SpeciesCareScreen.kt:121`); the rest of that screen stays as W3 C24 made it. Sites, Members, Custom tasks and Import from Planta open their current screens until W5b.
- W5a-P2. Header, first in the list, not tappable: the member's display name (title style), and under it the role: "Owner", "Family" or "Sitter". The name comes from `/api/me` (SV1). The identity JSON is already cached per member (`A/CareCache.kt:21-26`), so the header shows offline. The app parses the names apart from `Member`, which keeps only id and role, because `Member` equality decides cache wipes (`A/Repository.kt:162`, `A/CareCache.kt:24`). Until a name has loaded (an identity cached by an older build), the header shows the role alone. TalkBack reads "<name>, <role>".
- W5a-P3. Sitter's More (D16 O1; Opus F13). Header: name and "Sitter", then one sentence: "You look after 2 plants for <owner>." ("1 plant"; with none: "No plants are shared with you yet."), then "Ask <owner> to share more." (with none: "Ask <owner> to share some."). The count is the size of the sitter's plant list, which is the server's shared active plants (`A/Repository.kt:167-168`). <owner> is `household.owner_name` from SV1, else "the owner". Rows: Notifications, About, then Log out. The whole list fits without scrolling in portrait at 1.0x and 1.3x.
- W5a-P4. Fit. On the lab emulator (1080 × 1920 at 420 dpi, 411 × 731 dp, read 2026-11-21) in portrait at 1.0x, owner and family see the header, the "You" group and the "Household" heading with Weather and feed without scrolling. Log out stays last on purpose and is reached by scrolling. At 1.3x no title is cut; descriptions may wrap to two lines.

### Log out and login (draft D2, Q9, 1c; Sol S1, F1; Opus S1, F1; K3)

- W5a-P5. Log out asks first (D2 O1). Dialog title "Log out of Fytorium?". Body online: "Plants and photos saved on this phone are removed, and this phone stops getting Fytorium notifications. You need a connection to log in again." Body offline: "This phone is offline, so you can't log in again until it reconnects. Plants and photos saved on this phone are removed." Buttons "Cancel" and "Log out". The open dialog survives rotation and process death. The same dialog serves More and the update stop (W5a-P11).
- W5a-P6. Offline log out. The app still clears the session and wipes what it wipes now (`A/FytoriumApplication.kt:37-48`). A failed `/api/app/logout` is not reported as an error: the login screen shows no "Can't reach Fytorium" line after a log out. While the phone is offline, the login screen's reserved line reads "You're logged out. Logging in needs a connection."; it clears when the connection returns.
- W5a-P7. Login screen. Top bar "Fytorium". Body: "Plant care for your household.", then "Log in" (filled), then a reserved line for progress and errors directly under the button (C23), then a muted line: "To join a household, open the invite link from its owner first, then log in here." While the app finishes a login, the button keeps its place and size, reads "Logging in…" and is disabled; "Please wait…" goes. Tapping "Log in" offline does not open the browser and shows "Logging in needs a connection." in the reserved line.
- W5a-P8. Login errors, in the reserved line. The app reads the redirect's `error` after the state check (today `A/Pkce.kt:23` discards it). No server change.
  - `not_member`: "This account isn't in a household yet." (the line under it already says to open the owner's invite link; amended 2026-11-22 after Opus W5a-opus-8)
  - `access_denied`: "Login was cancelled."
  - `server_error`: "Fytorium couldn't finish the login. Try again."
  - token exchange refused or redirect invalid: "Login didn't work. Try again."
  - network failure during the exchange: "Can't reach Fytorium. Check the connection and try again."
  - no browser: unchanged, "Couldn't open login. Install a browser and try again."
- W5a-P9. After any log out and a new login, no page shows an offline or not-responding line while the phone is online (K3). A log out resets the care state's offline flags with the wipe, and a login's first refresh sets them only from that refresh's own result.

### About (draft D15; Sol S43; Opus S23)

- W5a-P10. About gets its own top bar: Back arrow, title "About". Body, in order:
  1. "Fytorium for Android 1.1.0" (`BuildConfig.VERSION_NAME`) and "Server 1.1.0" from `serverVersion` in the last `/api/meta` read, which the app already caches (`A/Repository.kt:170-174`). Before any read: "Server version not loaded yet". The server prints what `SERVER_VERSION` holds, "dev" when unset (`src/lib/server/config.ts:38`).
  2. "Copy diagnostics" (outlined), then "Diagnostics hold the app version and recent error types with their times, nothing about your plants or account. They stay on this phone until you copy them." This matches what `Diagnostics` stores (`A/Diagnostics.kt:11-15`). Copying works as now (`A/MainActivity.kt:42-45`). On Android 12 and lower a receipt "Diagnostics copied" shows over the bar; Android 13 and later show their own clipboard confirmation, so no receipt. Nothing is printed in the content any more (`A/AppShell.kt:300`).
  3. Heading "Data sources", one row per source, each opening its site in the browser: GBIF, "Species and names · CC BY 4.0"; Wikidata, "Names · CC0"; NorTaxa, "Norwegian names"; Open-Meteo, "Weather and climate"; Open Plantbook, "Care data"; Perenual, "Care data"; Pl@ntNet, "Identify from photos". Only GBIF and Wikidata carry a licence, because the repo states only those (README.md:68, :153 says NorTaxa's and Open Plantbook's licences are unverified or unstated). The implementer checks each homepage URL opens before shipping.
  4. "Source code", opening https://github.com/SpyrosPsarras/epaflix-fytorium (public; checked 2026-11-21).
  System Back and the arrow return to More.

### Required update (draft D14, Q5; Sol S2, F31, U12; Opus S2)

- W5a-P11. The stop. Top bar "Fytorium", no bottom bar, as now. Body:
  1. "Update Fytorium" (headline).
  2. "This phone has Fytorium 1.0.0. Your household's server needs 1.1.0 or newer." The app reads `minAppVersion` from `/api/meta` when the stop appears; that route is never refused for an old app. Without a minimum: "Your household's server needs a newer Fytorium."
  3. "Get the update" (filled): opens https://github.com/SpyrosPsarras/epaflix-fytorium/releases/latest in the browser. On 2026-11-21 that address redirected to the android-v1.1.0 release.
  4. "Open Obtainium" (outlined), only when Obtainium is installed (package `dev.imranr.obtainium` or `dev.imranr.obtainium.fdroid`; the manifest declares both under `<queries>`). It launches Obtainium, where the user's tracked Fytorium shows its update. I chose launching over Obtainium's `obtainium://add/<url>` link, which exists (Obtainium wiki, checked 2026-11-21) but would add a second entry for someone who already tracks Fytorium.
  5. "Check again" (text button), then "Log out" (text button, W5a-P5).
  6. A reserved line under the buttons for the check's result.
- W5a-P12. "Check again" reruns the session's version check and a care refresh. The stop clears when the check says this version is enough and the refresh succeeds (it clears the care flag, `A/Repository.kt:176`). Still too old: "Still needs 1.1.0 or newer. Checked 08:12." Offline: "Checking needs a connection." Logging out from the stop clears the session's update flag, so the login screen shows; the check after login brings the stop back if the version is still too old.
- W5a-P13. A 426 on any W5a screen calls `requireUpdate()` (`A/Repository.kt:101`), as Sites does (`A/sites/SitesScreen.kt:38`), so the full stop shows. The in-content update lines on W5a screens go (`A/notifications/NotificationScreen.kt:109`).

### Weather and feed (draft D3, D4, D5, D6; Sol S47, S48, F26, U37, U38, U47; Opus S25, S27, F16, U14, U17; K1, K2)

- W5a-P14. One screen, "Weather and feed", one More row, for owner and family (D3 O1). Top bar: Back arrow, title, C1 line. Text "Back" and "Refresh settings" go. Body: "Household location" first, then "Cards on Today". `HouseholdArea.Settings`, `HouseholdSettingsForm`, the Settings load branch and the second `feedLabels` go (`A/household/HouseholdRepository.kt:11-12`, `:54-60`; `HouseholdForms.kt:49-67`; `HouseholdScreen.kt:98-99`). The web keeps its Household settings page. android/README.md "Household management" is updated to match.
- W5a-P15. Household location, owner:
  1. One line with the current place: its label, else the coordinates rounded to 2 decimals ("60.39, 5.32"), else "No location set. Weather cards and frost alerts need one."
  2. The place search (W5a-P16).
  3. Text buttons "Enter coordinates instead" (W5a-P17) and, when a location is set, "Remove location" (W5a-P18).
  4. One muted line: "Place searches and the household coordinates go to Open-Meteo for weather."
- W5a-P16. Place search (D4 O1, Q2). A field labelled "Find your town", supporting text "Town or city", keyboard action Search. Search or Enter runs it; typing alone does not. Under 2 characters, "Type at least 2 letters." shows under the field. Progress overlays the top edge; the previous results stay until the new answer replaces them. Results sit under the field, up to 10 rows: the place name, and under it the region and country ("Vestland, Norway"; the region is left out when missing or equal to the name).
  - No match: in the content, "No place called "<query>"", then "Check the spelling, or enter coordinates instead." The status line stays empty.
  - Place search unavailable (503): the status line reads "Place search isn't responding", its explanation offers Try again with the same query; the previous results stay.
  - Offline: the field looks disabled and explains on tap: "Searching needs a connection."
  - Tapping a result saves it at once through `PUT /api/household/location` with its latitude, longitude and label ("Bergen, Vestland, Norway"). The results are disabled while it saves. On success the query and results clear, the current-place line changes in place, and a receipt reads "Weather location set to Bergen, Vestland, Norway". On failure the results stay and the reason shows under them (C23).
- W5a-P17. Coordinates form, folded until "Enter coordinates instead". Fields "Latitude" (supporting "-90 to 90"), "Longitude" ("-180 to 180"), "Place name (optional)", prefilled from the current location; then "Save location". The keyboard must offer a minus sign: the implementer checks the lab keyboard with Compose's decimal keyboard and, if it has no minus key, uses the text keyboard with the same checks. The app checks both numbers and their ranges before sending; field errors show under their fields, server refusals under the button. Success folds the form, updates the current-place line and shows "Weather location saved". Empty coordinates no longer mean "remove"; W5a-P18 does that. The typed values and the fold state survive rotation, theme change and process death. Back, a tab tap or a notification while the values differ from the saved location asks with the DiscardDialog: title "Discard these coordinates?", body "The location you typed is not saved." (registered through `onFormDirty`; the shell's `BackHandler` for this screen, `A/AppShell.kt:49`, asks first).
- W5a-P18. "Remove location" asks: title "Remove the household location?", body "Weather cards and frost alerts stop until a location is set again.", buttons "Cancel" and "Remove". Receipt: "Removed the weather location".
- W5a-P19. Household location, family: the heading, the current-place line, and "Only <owner> can change it." (`household.owner_name` from SV1, else "Only the owner can change it."). No search, no form. Family can read but not write the location (`src/lib/server/policy.ts:62`).
- W5a-P20. Cards on Today: heading "Cards on Today", then "Only you see these choices." Three switch rows, each one tap target with a title and a description:
  - "Today's weather", "Low and high for the household location";
  - "Weather alerts", "Frost, cold and rain for outdoor plants";
  - "Plant warnings", "Light, drainage and missing details".
  No "Late tasks" row on Android (D5 O1; Q3: the owner does not use the web). The server needs all four keys (`src/lib/server/feed-settings.ts:11`), so the app sends the stored `late_tasks` value unchanged. The switches save on change (W5a-P30); no Save button, no success receipt. Today's strip picks up a change at its next apply moment (W2 A1c).
- W5a-P21. From Today, the feed cards that open settings open this screen at the top, which is the location section; Back returns to Today (K2). At 1.0x portrait the current-place line and the search field are on screen without scrolling.

### Notifications (draft D7, D8; Sol S44, S45, S46, F25, U42, U43; Opus S24, F11, U17)

- W5a-P22. Layout (D7 O1). Top bar: Back arrow, "Notifications", C1 line. The text Back, headline, two paragraphs, "Refresh notifications", "Save notification settings" and the in-content offline, update and message lines go (`NotificationScreen.kt:99-113`, `:120`). Body: the status card, "Send me", "Send a test notification", "Devices".
- W5a-P23. Status card (Sol U43). One sentence and one button, the same height in every state: the sentence space is reserved for two lines at the current text size.
  - Android blocks Fytorium's notifications: "Notifications are off for Fytorium on this phone." Button "Turn on": on Android 13 and later the first tap asks the permission, once, remembered as now (`NotificationScreen.kt:41-62`); later taps open Android's app notification settings.
  - Allowed, but this phone's device row is switched off: "Fytorium isn't sending to this phone." Button "Turn on" switches that device on.
  - Otherwise: "Notifications are on for this phone." Button "Android settings", for muting a category.
  The card updates when the app resumes, as now (`:45-51`). The red line and the second settings link go (`:104-107`).
- W5a-P24. "Send me", in order:
  - switch "Daily reminder", "What needs care today. Nothing on days with no tasks.";
  - row "Reminder time" with the time on its right ("9:00 AM" or "09:00", following the phone's 12/24-hour setting); it opens W5a-P25 and works whether or not Daily reminder is on;
  - for owner and family: switch "Weather alerts", "Weather warnings for outdoor plants";
  - switch "When someone else completes a task", "Care done by other members".
  Each switch saves on change (W5a-P30) and sends the whole settings object (`src/lib/server/notifications/settings.ts:10-11`).
- W5a-P25. Reminder time dialog: title "Reminder time"; Material's TimePicker with `is24Hour` from `DateFormat.is24HourFormat(context)` (today fixed `true`, `NotificationScreen.kt:90`). When the window is under 480 dp tall (landscape), the dialog uses two menus instead, Hour and Minute (minutes in fives plus the saved minute), so it meets C37, C39 and C41 at 1.3x without a keyboard. Each menu opens at the chosen value and marks it. Buttons "Cancel" and "Set". "Set" saves at once; the row shows the new time. The open dialog and its current choice survive rotation and process death.
- W5a-P26. Time zone. Under Reminder time, "Times use Europe/Oslo time." shows only when the household zone (`/api/meta` `timezone`) differs from the phone's zone.
- W5a-P27. "Send a test notification", a text button. With no device switched on it looks disabled and explains on tap: "Turn on a device below first." The result is a receipt: "Test sent to 2 devices" (or "1 device"); "Test sent, but notifications are off for Fytorium on this phone" when the card shows the first state; "No device got the test. Try again later." when the server reports 0 sent.
- W5a-P28. Devices (D8 O1, Q4). Heading "Devices". This phone first, then the others in server order (`subscriptions.ts:14`). Each row: the name as title; under it "This phone", "Android app" or "Browser", then " · Last notified 14 Nov" from `last_success_at` (year added when not this year) or " · Not notified yet"; a trailing switch with content description "Send notifications to <name>". Browser rows also get a 48 dp remove icon button, "Remove <name>" (W5a-P29). Android rows have no Remove: a removed phone would register itself again on its next start (section 2). Names:
  - Android: the stored name. The old default "Fytorium Android" shows as "Android phone".
  - Browser: "<browser> on <system>" from the user agent label, recognising Firefox, Edge, Chrome and Safari, and Android, iOS, Windows, macOS and Linux. When neither part matches: the first 40 characters and "…".
  - No devices: "No devices get notifications yet."
  New logins send the phone's name instead of "Fytorium Android" (`A/ApiClient.kt:48`): `Settings.Global.DEVICE_NAME`, else `Build.MODEL`, trimmed to 100 characters (server limit, `src/lib/server/app-auth.ts:103`). Existing logins keep their stored name.
- W5a-P29. Remove a browser device: title "Remove <name>?", body "It stops getting Fytorium notifications. To get them again, turn notifications on in that browser.", buttons "Cancel" and "Remove". After success the receipt reads "Removed <name>". Rows below it may move up, since the user confirmed a dialog and was not touching the list (W4-P33).

### Across W5a (draft D6, D18; Sol F30; Opus F14, U16, U18)

- W5a-P30. Save on change (D6 O1). A switch shows its new value at once and stays enabled. One screen's writes run one at a time in tap order; each sends the state at the moment it starts, so the last tap wins. A failed write returns only the switch it changed to the saved value. Connection loss and an unresponsive server go to the status line (C1b) with Try again; a refusal shows as a receipt naming the setting: "Daily reminder wasn't saved. <server sentence>". Offline, switches look disabled and explain on tap: "Changing settings needs a connection." Nothing moves.
- W5a-P31. Status and reloading (C1, C1b, C21; D18 O1). The More root, Notifications and Weather and feed show page-level states only in the line under the title: "Offline · updated 08:12", "Fytorium isn't responding", or "Settings didn't load" with the server's sentence and Try again. Nothing is inserted above or inside the content. No "Refresh …" button remains on a W5a screen. These screens reload on open, on app resume, on reconnect and when Fytorium answers again (the `answers` counter, as `SitesRoute` uses it). Success messages are receipts or nothing.
- W5a-P32. Offline (F30 settings part). Notification settings, devices and time zone, and the household location, are kept in CareCache per member under new resource keys, wiped at log out with the rest (`A/Repository.kt:143-148`). The feed switches already come from the cached feed (`A/feed/FeedRepository.kt:38`). Offline, the screens show the last values with "Offline · updated hh:mm" and nothing editable.
- W5a-P33. Drafts. The coordinates form's values and fold state, the place query with its shown results, and every open W5a dialog survive rotation, theme change and process death. Only the coordinates form asks before leaving.
- W5a-P34. Every new dialog (log out, remove location, discard coordinates, reminder time, remove device, the status explanation) uses DialogTitle and DialogBody and meets C37, C39 and C41 in landscape (800 × 360 dp) at 1.0x and 1.3x.
- W5a-P35. Accessibility. Tap targets at least 48 dp. A switch row is one toggleable target with role Switch; TalkBack reads its title, description and state. Group titles are headings. The status card's sentence is a polite live region. Icons in rows are decorative. Contrast at least 4.5:1 for text, 3:1 for icons and dividers.
- W5a-P36. Wording. Roles are capitalised. Dates read "14 Nov" or "14 Nov 2025" (`dayMonth` and `dayMonthYear`, `A/CareModels.kt:120-121`). No W5a screen shows a lowercase enum value, an ISO date, or the names "My notifications", "Feed settings", "Household settings" or "Unmatched species".

## 4. Server and API changes

OpenAPI (`api/openapi.yaml`) is updated for both; `tests/api/openapi.test.ts` passes. If `tests/api/sharing-coverage.test.ts` lists routes, the new route is added there.

- SV1. `GET /api/me` adds `member.display_name` (from `members.display_name`) and `household.owner_name` (the display name of the household's owner who is not removed, or null). Additive. The route handler (`src/routes/api/me/+server.ts`) reads both with one query; the `Identity` type and every other user of it stay unchanged. The sitter projection for `/api/me` passes the body through (`src/lib/server/policy.ts:64`, `:166`). OpenAPI: `Identity.member` forbids extra properties (`api/openapi.yaml:3496-3510`), so `/api/me` gets its own `Me` schema with the two fields. Why: the header (W5a-P2), the sitter sentence (W5a-P3) and "Only <owner> can change it." (W5a-P19) need names the app has no other way to read; `/api/household/members` is owner-only (`policy.ts:59`). Tests: owner, family and sitter each get their own name and the owner's name; nothing else is added to a sitter's response.
- SV2. `GET /api/household/location/search?q=` (D4 O1). Owner only (policy entry `'owner'`). `q` is trimmed and must be 2 to 100 characters, else 422 `{ errors: { q: "Type 2 to 100 letters." } }`. The server calls `<OPEN_METEO_GEOCODING_BASE_URL>v1/search?name=<q>&count=10&language=<member language>&format=json` with the 8 s timeout the forecast client uses (`src/lib/server/weather/open-meteo.ts:7`). It returns 200 `{ results: [{ label, name, region, country, latitude, longitude }] }`, where `label` joins name, region (when present and different) and country with ", " and stays within the 200-character label limit (`location.ts:17`). An answer without `results` is an empty list. An upstream error, timeout or malformed answer gives 503 "Place search isn't responding. Try again in a minute." The query is not stored, cached or logged; request metrics label route templates only (`src/hooks.server.ts:70-74`). New setting `OPEN_METEO_GEOCODING_BASE_URL`, default `https://geocoding-api.open-meteo.com/`; compose.yaml points it at WireMock; new mapping `tests/stack/mappings/open-meteo-geocoding.json` (a "Bergen" answer and an empty one). Response fields (`results[].name, latitude, longitude, admin1, country`; no `results` key on no match) were checked with a read-only request for "Bergen" on 2026-11-21. Tests: owner gets mapped results; family and sitter get 403; `q` of 1 or 101 characters gives 422; no match gives `[]`; upstream 500 and a timeout give 503; the member language reaches Open-Meteo (WireMock request journal). Why a server route: the phone talks only to Fytorium, and Open-Meteo already receives the household coordinates, so no new processor.
- No server change for: device names (Android sends them at login), last-notified dates (already returned), the update screen (versions from `/api/meta`, link constant in the app), login error codes (already in the redirect), hiding Late tasks (Android sends the stored value), switch saves and location save or removal (existing PUTs), Obtainium, the sitter's plant count (the sitter's plant list).

## 5. Lab setup the implementer provides

Synthetic data only. Never touch namespace `fytorium`. Document each step in /home/t3/fytorium-ux/lab-runbook.md.

- L1. Minimum app version switch. A script, /home/t3/fytorium-ux/lab-min-version `<x.y.z>|off`, recreates only the app container with or without `MIN_APP_VERSION` (`COMPOSE_FILE=compose.yaml:compose.lab.yaml:<override> docker compose up -d --no-deps app`, with the override file kept outside /work/fytorium, because `lab-run` deletes untracked files there), waits for health, sets the test clock to 2026-11-15T08:00Z and force-stops the Android app. The debug APK is 1.0.0 (`android/app/build.gradle.kts:57`), so `1.0.1` triggers the stop. Postgres and minio are not recreated. The implementer runs it both ways once and records the result. Council members end with `off`.
- L2. Synthetic browser device. A script, /home/t3/fytorium-ux/lab-push-device `add|remove`, logs in as the owner through the web (seed.mjs pattern), sends `POST /api/notifications/subscriptions` with the app's Origin, endpoint `https://push.invalid/<uuid>`, a valid P-256 public key and 16-byte auth secret, no label, and the user agent `Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0`, so the row reads "Firefox on Linux". The lab app has `FYTORIUM_TEST_PUSH_URL` (compose.yaml:21), so a test notification to it goes to `fake-push`. `remove` deletes the row it added.
- L3. Members. The seed already has `synthetic-ux-family` and `synthetic-ux-sitter` (seed.mjs:119-123). Log out in the app and log in with either subject through the Chrome tab (runbook section 5); log back in as `synthetic-ux-owner` afterwards. For `not_member`, log in with the new subject `synthetic-ux-stranger`: an existing household makes the server refuse it without creating a member (`src/lib/server/auth.ts:116-117`, `:130`).
- L4. Place search. /home/t3/fytorium-ux/compose.lab.yaml sets `OPEN_METEO_GEOCODING_BASE_URL: https://geocoding-api.open-meteo.com/`, as it already points forecasts at the real Open-Meteo. Before the council round, record the lab household's location with read-only SQL so members can restore it; `/__test/weather-worker` runs the weather worker after a change.
- L5. Phone settings. Record which of these work on the API 35 emulator: `settings put system time_12_24 12|24`, `pm revoke|grant com.epaflix.fytorium android.permission.POST_NOTIFICATIONS`, and a time zone change (for example `cmd alarm set-timezone America/New_York`, not verified on this image).

## 6. Accepted limits and shortcuts

- A1. Place searches send what the owner types to Open-Meteo. The screen says so.
- A2. Android devices can be switched off, not removed (Q4).
- A3. Android logins made before W5a keep the name "Fytorium Android" on the server; the app shows "Android phone". The lab emulator's own name is "sdk_gphone64_x86_64" (read 2026-11-21).
- A4. An offline log out leaves the server's app device and its push row in place; the phone ignores pushes while logged out (`A/notifications/PushMessages.kt:51`). Devices idle for 90 days lose their login (`src/lib/server/app-auth.ts:11`); whether that also removes the push row was not checked.
- A5. "Get the update" opens the repository's latest release. If a non-Android release were ever marked latest, the link would land there.
- A6. "Open Obtainium" shows only when Obtainium is installed. The lab emulator has none, so a Robolectric test covers it.
- A7. Debug builds have no FCM token, so the lab never shows a "This phone" row or the status card's second state. Compose tests cover both.
- A8. Late tasks is hidden on Android; its stored value still controls the web feed.
- A9. The `access_denied` and `server_error` login messages are covered by unit tests; the lab login provider cannot produce them on demand (not checked).
- A10. A tab tap still closes More sub-screens (W6). W5a only asks first when the coordinates form has changes.
- A11. About shows the server version from the last `/api/meta` read.
- A12. On Android 13 and later the diagnostics copy confirmation is the system's.

## 7. Acceptance checks

Run on the lab emulator as the W4 brief describes (`emu ui`, `emu text`, `emu key 66`, `emu shot`, test clock 2026-11-15, read-only SQL, `svc wifi/data`, font scale 1.3, rotation, dark mode), at 1.0x and 1.3x, portrait and landscape, light and dark, unless a check says otherwise. Each check passes only if every listed result holds. Leave the lab as the W4 brief says: owner logged in, `lab-min-version off`, the synthetic browser device removed, the household location restored, phone settings back to defaults.

More, login, About, update
- T1 (More rows). As owner, family and sitter, `emu ui` lists exactly the rows W5a-P1 and W5a-P3 give that role, in order, under the group headings, with the header "<name>, <role>". No row is a filled button; "Log out" is the last element. No "Household settings", "My notifications" or "Unmatched species" text. The Tools row opens a page titled "Species without care data", laid out as W3 C24 requires.
- T2 (fit). Portrait 1.0x: owner and family see the header through Weather and feed without scrolling; the sitter sees every row and Log out without scrolling, also at 1.3x. Landscape: the list scrolls and nothing is cut.
- T3 (log out online). Log out → the dialog with the online body; Cancel keeps everything. Log out → Log out: the login screen, with no error line. SQL: the app device row has `revoked_at` set. Log in as the family subject: no page shows an offline or not-responding line while online (K3), including Plants after a pull. Log back in as owner.
- T4 (log out offline). Wi-Fi and data off: the dialog shows the offline body; confirm: the login screen reads "You're logged out. Logging in needs a connection." and no "Can't reach Fytorium". Tap Log in: no browser opens, the line says "Logging in needs a connection.". Connection back: the line clears; log in works.
- T5 (login). The login screen shows the sentence, Log in, and the invite line. Log in as `synthetic-ux-stranger`: back in the app, "This account isn't in a Fytorium household. …" sits under the button and the button did not move (bounds before and after). Log in as owner afterwards.
- T6 (About). Arrow and system Back both return to More. "Fytorium for Android" matches the APK's version and "Server" matches `/api/meta` `serverVersion`. Copy diagnostics: paste into a text field shows "Fytorium 1.0.0" and event lines only. Each data-source row and "Source code" opens the browser at its site. Offline after a restart: the server version still shows.
- T7 (update). `lab-min-version 1.0.1`, open the app: the stop shows "This phone has Fytorium 1.0.0. Your household's server needs 1.0.1 or newer.", "Get the update", "Check again", "Log out", no "Open Obtainium" and no bottom bar. "Get the update" opens the browser at the releases page. "Check again": "Still needs 1.0.1 or newer. Checked …". Log out from the stop: the login screen, not the stop; log in: the stop again. `lab-min-version off`, "Check again": Today. With the minimum set again while Notifications is open, toggle a switch: the full stop shows, not a line in the page.

Weather and feed
- T8 (one screen). More has one Weather and feed row. The page has a Back arrow and the title "Weather and feed", "Household location" above "Cards on Today", no "Refresh" or "Save my feed" button, no "Late tasks" row. With the location removed and a plant warning present, Today's "Set your household location for frost alerts" card opens this page with the current-place line and the search field on screen at 1.0x portrait; Back returns to Today.
- T9 (place search). Type "Bergen", press Enter (key 66): no character added, the keyboard closes, results list "Bergen" with "Vestland, Norway". Tap it: receipt "Weather location set to Bergen, Vestland, Norway"; SQL `households.latitude`, `longitude`, `location_label` match; after `/__test/weather-worker`, Today's weather card appears. Search "zzqxw": the no-match message in the content and an empty status line. Search "Bergen", then turn Wi-Fi and data off and tap the field: the results stay and it explains "Searching needs a connection."
- T10 (coordinates). "Enter coordinates instead": the ranges show before typing; type latitude "-33.9" (a minus sign is typeable) and longitude "200": "Save location" puts the longitude error under its field and sends nothing (SQL unchanged). Fix it and save: receipt and SQL match. Type new values, rotate, switch dark mode, then Home, `emu sh am kill com.epaflix.fytorium`, reopen: the form is open with the values. Back asks "Discard these coordinates?"; a tab tap asks the same. Restore the recorded location afterwards.
- T11 (remove). "Remove location" → dialog; Cancel keeps it; Remove: receipt "Removed the weather location", SQL null, the line reads "No location set. …".
- T12 (family). As family: the place line and "Only Synthetic Owner can change it." (the seed owner's display name), no search, no form; the three switches work.
- T13 (switches). Toggle "Plant warnings": no Save needed; SQL `members.feed_plant_warnings` changes and `feed_late_tasks` keeps its value. Five quick taps on one switch: SQL ends at the last tapped state. With `docker stop fytorium-app-1`, tap a switch: it returns to its saved value and the line reads "Fytorium isn't responding". Start the app container, set the clock: the line clears without a tap.

Notifications
- T14 (layout and card). Top bar with Back arrow, "Notifications", no headline in the content, no "Refresh" or "Save" button. `pm revoke` the permission, reopen: "Notifications are off for Fytorium on this phone." with "Turn on"; the tap opens Android's prompt or settings; `pm grant` and return: "Notifications are on for this phone." with "Android settings". The card's bounds are equal in both states.
- T15 (save on change). Toggle Daily reminder, Weather alerts and When someone else completes a task: SQL `members.reminders_enabled`, `weather_alerts_enabled`, `completion_notices_enabled` follow each tap; rotation keeps the values. Offline: switches look disabled and explain on tap.
- T16 (time). `time_12_24 12`: the row reads "9:00 AM" style and the picker shows AM/PM; `24`: "09:00" and no AM/PM. Set 07:30: SQL `reminder_time` = 07:30. The dialog in landscape at 1.3x shows its title, the time fields and both buttons.
- T17 (time zone). With the phone in the household zone, no time zone line. After a zone change (L5): "Times use Europe/Oslo time." Restore the zone.
- T18 (devices). `lab-push-device add`: the row reads "Firefox on Linux", "Browser · Not notified yet", a switch and a Remove button. "Send a test notification": receipt "Test sent to 1 device"; reopen: "Last notified 15 Nov". Remove → "Remove Firefox on Linux?"; Cancel keeps it; Remove: receipt "Removed Firefox on Linux", SQL row gone. No Android row offers Remove (Compose test, A7).
- T19 (sitter). As the sitter: More shows the header, "You look after <n> plants for Synthetic Owner." with n equal to the sitter's Plants count, Notifications, About, Log out, and no household rows. Notifications has no Weather alerts row.

Across
- T20 (offline). Open Notifications and Weather and feed online. Wi-Fi and data off, kill and reopen, open both: the last values show with "Offline · updated hh:mm" and nothing is editable. Connection back: the lines clear without a tap.
- T21 (nothing moves). On More, Notifications and Weather and feed, toggle Wi-Fi off and on: `emu ui` shows every row at the same y before and after; only the line under the title changes. A switch tap moves no row.
- T22 (dialogs). Log out, remove location, discard coordinates, reminder time, remove device and the status explanation in landscape at 1.0x and 1.3x: title at most two lines, body scrolls inside with dividers only over hidden content, buttons visible.
- T23 (TalkBack). `emu ui` content descriptions: the header ("<name>, <role>"), each row (title and description), switch rows (title, description, state), device switches ("Send notifications to Firefox on Linux"), "Remove Firefox on Linux", the status card.
- T24 (regression). Today's feed strip and its cards still open their targets; W4 Journal and Plants unaffected; W3 discard on a tab tap from Edit plant still asks; the plant page's status line unchanged.
- T25 (wording). On every W5a screen: no "Refresh …" button, no lowercase role or enum value, no ISO date.

### Item to check map

| Item | Checks |
|---|---|
| Sol S42, Opus S22 | T1, T2, T23 |
| Sol S43, Opus S23 | T6 |
| Sol S2, F31, Opus S2 | T7 |
| Sol S1, F1, Opus S1, F1 | T4, T5 |
| Sol S44, F25, Opus S24, F11 | T14, T15, T20, T21 |
| Sol S45 | T16, T17 |
| Sol S46 | T18 |
| Sol S47, S48, F26, Opus S25, S27, F16 | T8-T13 |
| Sol F29 (regression), Opus F13 | T19 |
| Sol F30, Opus F14 (settings part) | T20, T21 |
| Sol S32 | T1, T24 |
| Sol U12 | T7 |
| Sol U37, Opus U14 | T8, T9 |
| Sol U38 | T9, T10 |
| Sol U42 | T18 |
| Sol U43 | T14 |
| Sol U46, Opus U19 | T1, T6 |
| Sol U47 (W5a part) | T9-T11 |
| Sol U8 (W5a part) | T10 |
| Opus U8 (back arrows), audit F10 (W5a part) | T6, T8, T14 |
| Opus U16 | T8, T14, T25 |
| Opus U17 | T13, T15, T18 |
| Opus U18 (casing, W5a part) | T25 |
| Audit F12 | T7, T18 |
| Audit F13 | T1, T8, T9, T25 |
| K1 | T8, T13 |
| K2 | T8 |
| K3 | T3 |
| K4 | T1 |
| All | T21, T22, T23 |

## 8. Implementation order

Each slice builds, passes its tests and can be reviewed alone. Server slices run the full web suite (`./scripts/stack test`) and typecheck; Android slices run unit, Robolectric screenshot and accessibility tests, with screenshots updated, not deleted.

1. Server SV1 and SV2: route code, policy, OpenAPI, WireMock mapping, compose setting, tests.
2. More root, header, sitter header, status line, Species without care data title (W5a-P1 to W5a-P4).
3. Log out dialog, offline log out, login screen and errors, K3 (W5a-P5 to W5a-P9).
4. About and the update stop, with the Obtainium `<queries>` (W5a-P10 to W5a-P13).
5. Weather and feed: merge, place search, coordinates form, removal, family view, switches, location cache, README (W5a-P14 to W5a-P21, part of W5a-P32).
6. Notifications: card, switches, time picker, time zone, test, devices, login device name, cache (W5a-P22 to W5a-P29, rest of W5a-P32).
7. Lab L1 to L5 and the runbook. Can run beside 1-6.
8. Save-on-change, status, drafts, dialogs, accessibility and wording pass over every W5a screen (W5a-P30 to W5a-P36), then a full run of T1-T25 by the implementer before the council round.

## 9. Not verified

- Anything live except the emulator facts read on 2026-11-21 (model and device name, API 35, 420 dpi, 1080 × 1920). I did not run the app.
- The offline log out error line, the stuck update stop after log out, and the Android device re-registration: from code only.
- The audit document that numbers F10, F12 and F13.
- Open-Meteo geocoding's `language` parameter for nb and el.
- Whether Compose's decimal keyboard offers a minus sign on the lab keyboard (W5a-P17).
- Whether the TimePicker fits a 360 dp tall dialog at 1.0x; W5a-P25 avoids it with the hour and minute menus.
- The homepage URLs for the data sources in W5a-P10, and the licences of NorTaxa, Open-Meteo, Open Plantbook, Perenual and Pl@ntNet.
- The time zone command on the API 35 emulator (L5), and that `fake-push` accepts deliveries so `last_success_at` is set (L2, T18).
- Whether `docker compose up -d --no-deps app` with an extra override recreates only the app container in this lab (L1).
- Whether the 90-day idle rule removes an offline-logged-out phone's push row (A4).

## Owner decisions assumed (2026-11-22)
The owner asked not to be asked again; the first answer in each case stands. Q1: sitters see the owner's display name. Q2: Unmatched species (Sol S32) and the login screen are in W5a. Q3: "Open Obtainium" opens the app, not its add-app link. Q4: Android devices can only be switched off, not removed.
