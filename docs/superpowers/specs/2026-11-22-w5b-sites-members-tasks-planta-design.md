# W5b: Sites in Plants, Members and invites, Custom tasks, Planta import, one dropdown

Wave W5b of the Android UI/UX programme. Built on W5a (branch t3/w5-more-settings, head a0bb424). Goal: every W5b item below scores 9.5 or higher for every council member on a live lab build, judged by the "Scoring bar" in council-w5a-brief.md. Sol and Astra score Sol's item IDs; Opus scores Opus's.

Source: /home/t3/fytorium-ux/w5-design-draft.md, decisions D9 to D13 and D18, with the owner's answers of 2026-11-19: Q1 Sites move into the Plants tab (D9 O2), Q6 copying an invite link is allowed, Q7 keep the name "Custom tasks", Q8 the real Planta import is done and Planta import is a rarely used tool. Each decision names the draft item it implements.

Paths: `A/` = `android/app/src/main/java/com/epaflix/fytorium/`; server paths are from the repo root. Line numbers are at a0bb424.

Reused by name, not redesigned:
- the status line under the top bar title (`BarTitle`, `A/TaskScreen.kt:228-242`, in a bar of `barHeight()`, `:244-246`), with Try again in its explanation, and the full-screen "Update Fytorium" stop (`requireUpdate()`, as Sites already calls it, `A/sites/SitesScreen.kt:38`);
- DialogTitle and DialogBody (`A/DialogBody.kt:26-53`) with the fit rules C37, C39 and C41;
- the DiscardDialog ("Discard" / "Keep editing") asked on Back and on a tab or notification tap through `onFormDirty` (`A/AppShell.kt:81-90`, `:163-165`);
- receipts over the navigation bar (W2 A13a; `A/AppShell.kt:119-162`; on Plants pages `repository.showReceipt`);
- reload when Fytorium answers again (C21; the `answers` counter, as `NotificationRoute` gets it, `A/AppShell.kt:175`), on open, on app resume and on reconnect;
- the More rows (`MoreRoot`, `A/MoreScreen.kt:26-64`);
- per-member offline copies through `cachedJson` and `saveJson` (`A/Repository.kt:297-301`), as W5a-P32 does for settings;
- drafts that survive rotation, theme change and process death (`rememberSaveable`, `savedFields`), with routes keyed by the member id, never by the `Member` object: its `Role` enum hash changes per process, so a key on it drops saved state on restore (W5a council round 6, `A/AppShell.kt:172`);
- nothing moves under the finger (W2 A1, A1c); progress overlays the top edge; an error goes under its field or under Save (C23);
- W4-P2 keyboard behaviour: Search, Go or Enter runs the action and never inserts text.

W5b has no switch or other save-on-change control: sites, invites, members and tasks are typed forms with an explicit Save (draft D6). `SettingWrites` and `Pending` (`A/WriteQueue.kt:12-28`) are therefore not used in W5b.

UI wording below is in straight quotes. Example names are synthetic and match the lab seed.

## 1. Scope

### Council items in W5b

| Area | Sol (and Astra) | Opus |
|---|---|---|
| Sites | S49 Sites list 5.5, S50 Create site 5, S51 Edit site 5, S52 Delete site 6, F21 Create/edit a site 5 | S26 Sites 4, F9 Sites 5 |
| Members and invites | S53 Members list 5.5, S54 Invite form 4.5, S55 One-time invite link 4.5, S56 Edit member and shares 5, S57 Remove member 5, S58 Revoke invite 5, F22 Create invite 4.5, F23 Manage member 5 | S28 Members and invites 5, F10 Household and invites 5 |
| Custom tasks | S59 Custom tasks and target 4.5, S60 Create task 5.5, S61 Edit task 5.5, S62 Delete task 5.5, F24 Custom task 4.5 | S29 Custom tasks 4 |
| Planta import | S63 Code and fetch 5, S64 Review 5.5, S65 Uncertain species 4.5, S66 Completed import 6, F28 Planta import 5 | S30 Import from Planta 6, F12 Planta import 6 |
| Dropdowns | S67 Shared dropdown menus 5.5 | U18, dropdown part |

Findings behind these rows, all in scope: Sol U35, U36, U39, U40, U41, U44, U45, U8 (the W5b forms only); Opus U25, U5 (regression only, see section 2), U8 (back arrows on W5b screens), U16 (Refresh buttons on W5b screens), U18 (dropdowns, and casing on W5b screens). Audit F13 (Refresh buttons) on the W5b screens.

Carried notes, all in scope:
- K1 (Opus W3 r4). Restore → Create a site accepted a second "Kitchen"; the site picker then showed two identical entries.
- K2 (Opus W3 r1, W3-opus-13). "Create a site" opened the Sites list instead of a new-site form. Fixed in W3 (`creating = true`, `A/plants/PlantsScreen.kt:281-284`); regression only, because W5b rebuilds the site form.
- K3 (Opus W5a rounds 3 to 6). Members offline shows "Refresh members" and an in-content offline line.

Regression only, must not get worse: W3 Plants grid, plant page, C1/C1b, C19, C26 and C37-C42 on pages W5b touches; W4 Add plant with "Create a site" (W4 inline site creation), receipts and the Journal; W5a More, Weather and feed, Notifications and Species without care data (W3 C24).

### Out of scope
- W6: Back targets across tabs, a tab tap closing sub-screens (`A/AppShell.kt:88`), and the tab switches from Planta ("View imported plants", "Open imported plant", and W5b's "Check sites").
- The web UI. Server changes keep the web building and its tests passing, nothing more.
- Planta's server flow: the real import is done (Q8), so W5b changes only the Android screen.

## 2. Current behaviour (checked against the code)

### Sites (`A/sites/`, opened from More)
- More lists Sites under "Household" (`A/MoreScreen.kt:49`); the shell opens `SitesRoute` from a `sites` flag (`A/AppShell.kt:66`, `:220-226`, `:281`). That call passes no `answers`, so the C21 reload never fires from More → Sites (`A/AppShell.kt:222-223`; the parameter defaults to 0, `A/sites/SitesScreen.kt:29`, `:42`). Plants opens the same route with `answers` for Restore and Add plant → Create a site (`A/plants/PlantsScreen.kt:281-284`).
- The list: one sentence, "Create site" and "Refresh sites" side by side (`A/sites/SitesScreen.kt:94-98`); cards with the name, "Indoor · 2 plants" and "Imported site. Complete its conditions." (`:101-105`). Cards are not tappable. Edit and Delete show on every card, enabled by `writable` alone, also for a site with plants (`:106-109`).
- Delete asks "Delete site?" / "This permanently deletes <site> and its settings if it has no plants." / "Confirm delete" (`:119-136`). The server refuses an occupied site with 409 "Move or remove the 2 plants on this site first." (`src/routes/api/sites/[id]/+server.ts:10-33`, message at `:29`). The count is active plants only; graveyard plants lose their site in the same delete (`:18`).
- The form: Name, then three button-style menus showing "Location: indoor", "Light level: part sun", "Humidity: normal" (`A/sites/SitesScreen.kt:149-152`, values `replace('_', ' ')` in lowercase), "Grow light", heading "Temperature ranges" with one help sentence and four number fields (`:155-161`, `KeyboardType.Number`), then "Draft" (`:162`). The engine reads draft as moving air: `water.draft@1`, "Moving air dries soil and foliage faster." (`src/lib/schedule/rules.ts:69`). Drafts already survive recreation and ask before discard (`A/sites/SitesScreen.kt:54-70`, `:140-144`).
- A save closes the form with no receipt (`:88`). The list lives in memory only (`A/sites/SiteRepository.kt:39`), so offline after a restart it is empty.
- The server trims the name and accepts duplicates (`src/lib/server/sites.ts:21-22`; insert and update at `src/routes/api/sites/+server.ts:22-27`, `src/routes/api/sites/[id]/+server.ts:41-46`). Planta import matches a site by `lower(btrim(name))` and creates one only when none matches (`src/lib/server/planta/commit.ts:57-61`).
- Plants groups its grid by `siteName` (`A/plants/PlantViews.kt:81-82`), so two sites with one name merge into one heading. Headings are plain text (`:133-136`). The Plants overflow holds only "Graveyard" (`:98-103`). The plant page shows the site as plain text (`:368-373`). Plant rows carry `site_id` in `attributesJson` (`A/CareModels.kt:190-192`; `src/lib/server/plants.ts:11-18`).
- Two texts send users to More for sites: the feed's "Set it for each site under More, Sites." (`A/feed/FeedModels.kt:44`) and "Create a site under More before adding or restoring a plant." (`A/plants/PlantForms.kt:216`).

### Members and invites (`A/household/`)
- Owner only (`src/lib/server/policy.ts:58-59`). `HouseholdRoute` sits in `key(careState.member, householdArea)` (`A/AppShell.kt:195`): a member-object key.
- Top bar without status line; offline and update lines and "Refresh members" are content text (`A/household/HouseholdScreen.kt:75-82`). A 426 shows a line, not the full stop (`:80`; `A/household/HouseholdRepository.kt:119-120`).
- Members: cards with the name, "Role: family" in lowercase, "Edit member", "Remove member" (`HouseholdScreen.kt:98-105`). Invites: "sitter · Expires 2026-11-22" (`:110`; the date is the ISO string cut to 10 characters, `HouseholdRepository.kt:68`).
- Invite form: Role menu defaulting to Family with no explanation (`HouseholdForms.kt:25-31`); sitter plants are checkboxes with display names only (`:21-24`).
- After create: "Copy this link now. It appears only here, once, and expires after seven days.", "Share invite link", "Hide link" (`HouseholdScreen.kt:88-90`). No copy action. The link is `remember(area)` state (`:63`), so rotation or a theme change loses it; from code, not seen live. Sharing uses the system chooser (`:37-41`).
- Editors use `remember` (`:59-62`): rotation drops the open invite, member or task form. No form reports to `onFormDirty`.
- Dialogs: plain `AlertDialog` with "Remove member?" / "Remove this member and all their devices?", "Revoke invite?" / "Revoke this invite?", "Delete task?" and "Confirm remove", "Confirm revoke", "Confirm delete" (`:138-150`). No receipts.
- Server: an invite lasts 7 days, its create response returns `id`, `role`, `expires_at` and `link` (`src/lib/server/household.ts:19-30`). Removing a member deletes their sessions, revokes their app devices, deletes push rows and shares, and keeps their name in history (`:79-98`).

### Custom tasks
- One More screen. A "Target" button menu over every plant (graveyard ones marked " · Graveyard") and site, labelled "plant: Name" / "site: Name" (`HouseholdScreen.kt:116-120`, `HouseholdRepository.kt:45-49`). Nothing shows until a target is chosen; the choice is forgotten on leaving.
- Then two fixed sentences, "Add custom task", cards "Interval in days: 7" with "Edit task" and "Delete task" (`HouseholdScreen.kt:121-133`). Form "Task name", "Interval in days", "Save task" (`HouseholdForms.kt:40-48`); the range shows only after a refusal.
- Server: name 1-60 characters, 1-365 whole days (`src/lib/server/custom-tasks.ts:26-31`); `GET /api/custom-tasks` needs exactly one of `plant_id`, `site_id` (`:8-9`, `:20-23`). The first due date is max(plant added date, task created date) plus the interval (CONTEXT.md:241). The plant page shows custom tasks as Care rows but cannot add one (`A/plants/PlantViews.kt:378-427`).

### Planta import (`A/planta/PlantaScreen.kt`)
- `PlantaRoute` sits in `key(careState.member)` (`A/AppShell.kt:183`).
- The portal instruction is plain text (`:80`). README names https://getplanta.com/apps/manage (android/README.md:226); on 2026-11-22 that address redirected to `https://getplanta.com/apps/login?r=/manage`, page title "Planta App Portal".
- Offline and update lines are content text (`:83-84`); "Refresh review" (`:92-93`).
- Cards show raw values: "Pot: plastic · 12 cm · Soil: all purpose", "Last clean leaves: 2026-05-20T12:00:00.000Z" (`:124-125`). The server stores a date-only value or a full ISO timestamp (`src/lib/server/planta/import.ts:57`); missing values become "unknown" (`A/planta/PlantaRepository.kt:44`).
- "Which picture looks like your plant?" whenever candidates exist (`:141`), photos or not.
- "Import confirmed plants" sits at the end of the page (`:186-187`), then the GBIF credit, "New sites use default conditions." and "Complete them in Sites" (`:188-192`). Commit needs every row out of review (`src/lib/server/planta/import.ts:79`).
- The code lives only in `remember` and is cleared before the request (`:59`, `:90`). Keep.
- There is no way to discard a staged review; a new fetch replaces it (android/README.md:92).

### Dropdowns
- Two components. The button-style `Choice` (`A/TaskScreen.kt:383-393`, "Label: value" on an OutlinedButton) is used by Sites (`A/sites/SitesScreen.kt:150`), the invite and member Role (`A/household/HouseholdForms.kt:28`, `:36`), the custom task Target (`A/household/HouseholdScreen.kt:117`) and the Species care override for choice fields (`A/species/SpeciesCareScreen.kt:228`). The plant form's `Choice` (`A/plants/PlantForms.kt:71-84`) is an exposed dropdown text field.

### Corrections to the draft
- Opus scores Members and invites as S28 (5). The W5a out-of-scope list left it out; W5b includes it.
- Opus U5 (Planta names) is already fixed on the server: Planta's custom name, else its `localizedName`, fills the nickname, and a fetch replaces the nickname only when Planta's name changed (`src/lib/server/planta/import.ts:28-42`, CONTEXT.md:185). W5b checks it as a regression only and keeps the screen's sentence about it.
- The plant form's `Choice` is at `A/plants/PlantForms.kt:71`, not `:72`.
- "Last clean leaves" values are ISO timestamps for Planta timestamps and plain dates for date-only values, not always ISO.
- The draft's D13 O3 ("hide it from More after the first import") needs nothing: the owner's empty Today offers Import only when there are no plants (`A/TaskScreen.kt:175`), and More already lists it under Tools (W5a-P1).

## 3. Decisions

### More after W5b (draft D9 O2)

- W5b-P1. More's "Household" group becomes: Weather and feed; Members ("People and invites", owner only); Custom tasks ("Your own repeating tasks"). The Sites row goes (`A/MoreScreen.kt:49`), and so do the shell's `sites` flag and its route (`A/AppShell.kt:66`, `:220-226`, `:281`). Every other W5a-P1 rule stays.

### Sites in the Plants tab (draft D9 O2, D10; Sol S49-S52, F21, U35, U36; Opus S26, F9; K1, K2)

- W5b-P2. Ways in, owner and family only:
  1. The Plants overflow ("More options") lists "Sites" above "Graveyard".
  2. In the Plants grid each site heading is one 48 dp tap target: the site name and a trailing chevron, click label "Open <site>". It opens that site's page (W5b-P4). Headings stay plain text in the Graveyard grid, the Journal plant picker and for sitters.
  3. On the plant page the site line (place icon and name) becomes a 48 dp tap target, click label "Open <site>", opening the site page.
  The grid groups by site id instead of name, so two sites never merge; headings still sort by name.
  Back returns to where the user came from: the grid at the same scroll position, the plant page, or the Sites list. A plant opened from a site page returns to that site page. All of this stays inside the Plants tab.
- W5b-P3. Sites list. Top bar: Back arrow, "Sites", status line, and an add action (plus icon, content description "Create site"), as Plants has for Add plant. One 72 dp row per site (Material `ListItem`): the name; under it "Indoor · 3 plants", "Outdoor · 1 plant" or "Indoor · No plants"; an imported site with default conditions adds " · Conditions not set". The whole row opens the site page. Sites sort by name, as the server sends them (`src/routes/api/sites/+server.ts:11`). No sentence and no "Refresh sites" button; pull down to refresh. With no sites: "No sites yet", then "Create a site" (filled).
- W5b-P4. Site page. Top bar: Back arrow, the site name, status line, an edit action (pencil, "Edit <site>") and, only when the site has no active plants, an overflow with "Delete site". Body:
  1. "Indoor · Part sun · Normal humidity" (humidity reads "Dry air", "Normal humidity" or "Humid air").
  2. When on: "Grow light"; "Draft from a door, window or vent".
  3. When any bound is set: "Warm half of the year: 18 to 24 °C" and "Cold half of the year: 12 to 20 °C"; a missing bound reads "at least 18 °C" or "at most 24 °C"; a half with no bounds is left out.
  4. For an imported site with default conditions: "Imported from Planta with default conditions. Edit the site to set them." with an "Edit site" text button.
  5. Heading "Plants (3)", then the site's active plants as the grid's tiles (cover or initial, name, late pill), sorted by name, each opening the plant page. The plants are the cached plant list filtered by `site_id`, so the page works offline.
  6. With plants: "To delete Kitchen, move its 3 plants to another site or to the graveyard first." With none: "No plants here yet."
  Pull down to refresh reloads the site and the plants.
- W5b-P5. Site form ("New site" / "Edit <site>"), used from the Sites list, the site page, Restore and Add plant:
  1. "Name".
  2. Three exposed dropdown fields (W5b-P25): "Location" ("Indoor", "Outdoor"), "Light" ("Full shade", "Part shade", "Part sun", "Full sun"), "Humidity" ("Dry", "Normal", "Humid").
  3. Switch rows, each one toggleable target: "Grow light"; "Draft from a door, window or vent", with "Moving air dries soil faster." under it.
  4. An Expander ("Temperatures (optional)", the plant form's `Expander`, `A/plants/PlantForms.kt:113-119`), folded unless a bound has a value or an error. Inside: "Warm half: when the heating is off, or summer. Cold half: when the heating is on, or winter. Leave a bound blank if you don't know it." and the four fields with today's labels. The fields use the text keyboard, so a minus sign is typeable, as the Species care temperatures already do (`A/species/SpeciesCareScreen.kt:219`).
  5. "Save site" (filled), "Cancel" (text), the save error line under them (as now).
  New sites keep today's defaults (Indoor, Part sun, Normal, both switches off).
- W5b-P6. Saving. Success closes the form and shows a receipt: "Created Hallway" or "Saved Kitchen". A new site opened from the Sites list lands on its site page; from Restore or Add plant it returns to that form with the site chosen, as now (K2). A duplicate name is refused under Name with the server's sentence (SV1): "You already have a site called Kitchen." The name keeps its typed value.
- W5b-P7. Delete (D10 O1). Offered only in the site page's overflow and only while the site's server plant count is 0. Dialog: title "Delete site?" (W3 rule: a generic title, the target named in the body, so a long name never crowds the title), body "Hallway and its conditions are removed for good. Plants in the graveyard that were here keep their history; restoring one asks for a site." (true to `src/routes/api/sites/[id]/+server.ts:18`), buttons "Cancel" and "Delete". Success returns to the Sites list with the receipt "Deleted Hallway". If a plant arrived meanwhile, the server's 409 sentence shows inside the dialog under the body and the dialog stays (the server check stays for races).
- W5b-P8. Texts that pointed to More now point to Plants: "Set it for each site under Plants, Sites." (`A/feed/FeedModels.kt:44`) and "Create a site under Plants, Sites before adding or restoring a plant." (`A/plants/PlantForms.kt:216`).

### Members and invites (draft D11; Sol S53-S58, F22, F23, U39, U40, U41; Opus S28, F10, U25; K3)

- W5b-P9. Members page. Top bar: Back arrow, "Members", status line. Body:
  1. "Invite someone" (filled).
  2. Heading "People". One row per member in server order (`src/lib/server/household.ts:34-38`): the name, " (you)" on the owner's own row; under it "Owner", "Family" or "Sitter · 2 plants" ("1 plant", "No plants"). A row opens the member page (W5b-P12) when the server allows a role change or removal (`role_options`, `can_remove`); other rows are not tappable.
  3. Heading "Pending invites". One row per invite: "Sitter invite" or "Family invite"; under it the shared plants' names for a sitter invite ("Spider Plant, Thyme"; more than three: "Spider Plant, Thyme and 2 more") and "Expires Sun 22 Nov" (`shortDate` of `expires_at` in the phone's time zone, year added when not this year). A trailing "Revoke" text button (W5b-P14). None: "No pending invites."
  No "Refresh members" button; pull down to refresh.
- W5b-P10. Invite form ("Invite someone"). Two selectable cards, none selected at first (D11 O1), each one target with role RadioButton:
  - "Family": "Can see and change everything except members, the weather location and Planta import."
  - "Sitter": "Sees only the plants you share, and can mark their care done, snooze or skip it. No photos, history or other plants."
  Both sentences follow the server's rules (CONTEXT.md:268, :274). Choosing Sitter shows heading "Plants to share" and the plant picker: one 56 dp toggleable row per active plant, sorted by site then name, with a 40 dp cover (or the initial, `PlantCover`), the name, the site under it, and a trailing checkbox. With none ticked: "No plants chosen. You can share plants later from Members." "Create invite link" (filled) is disabled-looking until a role is chosen and explains on tap: "Choose Family or Sitter first." The role and the ticked plants survive rotation, theme change and process death; leaving with a role chosen asks with the DiscardDialog, title "Discard this invite?", body "No invite is created until you tap Create invite link."
- W5b-P11. Link page (D11, Q6). Title "Invite link". Body: "Send this link to the person you invite. It works once and expires on Sun 22 Nov. You can't see it again after you leave this page." Buttons: "Share link" (filled, the system chooser as now), "Copy link" (outlined), "Done" (text).
  - Copy puts the full link on the clipboard. On Android 13 and later the clip carries `ClipDescription.EXTRA_IS_SENSITIVE`, so the system confirmation hides it, and no receipt shows; on Android 12 and lower a receipt reads "Link copied".
  - The link lives in process memory, keyed by invite id, never in saved state, Room, diagnostics or logs. It survives rotation and theme change. It is dropped on Done, on leaving the page, on revoke and at log out.
  - Back, Done or a tab tap before Share or Copy asks: title "Leave without sending the link?", body "You can't see this link again. The invite still works until Sun 22 Nov unless you revoke it under Pending invites.", buttons "Stay" and "Leave". After a Share or Copy, nothing asks.
  - After process death the page keeps only the invite id, role and plants in saved state and reads: "This link can't be shown again", "Fytorium was closed, so the link is gone. If you didn't send it, revoke the invite and create a new one." Buttons "Revoke and create new" (filled: revokes that invite, creates one with the same role and plants, and shows the new link page) and "Keep the invite" (text: back to Members).
- W5b-P12. Member page (title: the member's name). For family and sitters: the two role cards with the current role selected; for a sitter, "Plants to share" with the picker, current shares ticked. "Save" (filled). Receipt: "Saved changes for Synthetic Sitter". Under Save, when `can_remove`: "Remove from household" (text button, W5b-P13). Changing to Family clears shares, as the server does (`src/lib/server/household.ts:72-76`); the Family card says so while a sitter is being changed: "Family members see every plant, so their shares are cleared." Unsaved changes survive recreation and ask with the DiscardDialog ("Discard changes?", "Your changes to Synthetic Sitter are not saved yet.").
- W5b-P13. Remove: title "Remove member?" (W3 rule: generic title, the person named in the body), body "Synthetic Helper is logged out on every device and loses access to this household. Their name stays in care history.", buttons "Cancel" and "Remove". Receipt "Removed Synthetic Helper"; back to Members. The body matches what the server does (`src/lib/server/household.ts:91-97`).
- W5b-P14. Revoke: title "Revoke this invite?", body "The sitter invite for Spider Plant and Thyme stops working. It would have expired on Sun 22 Nov." (family: "The family invite stops working. It would have expired on Sun 22 Nov."), buttons "Cancel" and "Revoke". Receipt "Revoked the sitter invite". The row goes; rows below it may move up, since the user confirmed a dialog (W4-P33).

### Custom tasks (draft D12 O1, Q7; Sol S59-S62, F24; Opus S29)

- W5b-P15. Custom tasks page (More → Household → Custom tasks, owner and family). Top bar: Back arrow, "Custom tasks", status line. Body: "Add task" (filled), then every live custom task in the household (SV2), grouped by target:
  - site groups first, by name: heading "Kitchen", under it "Site · every plant here, now 3";
  - then active plants, by name: heading "Monty", under it "Plant in Guestroom";
  - then graveyard plants: heading "Old aloe", under it "In the graveyard. Its tasks pause until it is restored."
  Each task is a 72 dp row: the name and "Every 7 days" ("Every day" for 1); the row opens the task form. None: "No custom tasks yet. Add one for a plant or for every plant on a site." No "Target" menu and no "Refresh" button; pull down to refresh.
- W5b-P16. "Add task" opens "For which plant or site?": a search field "Search plants and sites" (filters as typed), heading "Sites" with one row per site (name, "3 plants"), heading "Plants" with one row per active plant (40 dp cover or initial, name, site). Graveyard plants are not offered: they get no reminders (CONTEXT.md:244). A tap opens the task form for that target. Back returns to the list.
- W5b-P17. Task form ("New task" / "Edit task").
  1. A target line: "For Monty · Guestroom", or for a site "For every plant in Kitchen, also plants added later". A new task's target line has a "Change" text button back to the picker (not on the plant page, W5b-P18). The target of a saved task cannot change (server rule, `src/lib/server/custom-tasks.ts:52`).
  2. "Task name", supporting "Up to 60 characters".
  3. "Repeat every", number keyboard, suffix "days", supporting "1 to 365 days".
  4. New task only: "First due Sun 22 Nov" (household today plus the typed interval; hidden while the interval is not a whole number from 1 to 365). For a site: "First due Sun 22 Nov for the plants there now." This matches the server's rule for every plant already in the household (CONTEXT.md:241).
  5. "Save task" (filled), "Cancel" (text), the save error under them; for a saved task, "Delete task" (text) last.
  The app checks before sending: an empty or longer name shows "Enter a name from 1 to 60 characters." under Task name; a non-whole or out-of-range interval shows "Enter whole days from 1 to 365." under Repeat every (the server's own wording, `:29-30`). Nothing is sent while either shows. The typed values and target survive rotation, theme change and process death; Back, Cancel, a tab or a notification with changes asks with the DiscardDialog ("Discard changes?", "Your new task for Monty is not saved yet." / "Your changes to Mist leaves are not saved yet.").
  Receipts: "Added Mist leaves for Monty", "Added Wipe windowsill for every plant in Kitchen", "Saved Mist leaves".
- W5b-P18. Plant page (D12 O1). Under the Care card (or under "No care tasks yet.") of an active plant, owner and family see "Add a task" (text button, 48 dp). It opens the task form with this plant as the fixed target, inside the Plants tab. Save returns to the plant page with the receipt; the page reads the plant on return, so the new Care row shows then. Back returns to the plant page at the same place (W3 rule).
- W5b-P19. Delete task: title "Delete task?" (W3 rule: generic title, the task named in the body), body for a plant "Monty stops getting Mist leaves. Its past care stays in history.", for a site "Every plant in Kitchen stops getting Wipe windowsill. Past care stays in history.", buttons "Cancel" and "Delete". Receipt "Deleted Mist leaves"; back to the list.

### Planta import (draft D13 O1, Q8; Sol S63-S66, F28, U44, U45; Opus S30, F12, U5)

- W5b-P20. Entry points stay: More → Tools → Import from Planta, and the owner's empty Today (Q8). Top bar: Back arrow, "Import from Planta", status line.
- W5b-P21. Code step, first on the page:
  1. "Get a one-time app code from the Planta App Portal, then paste it here. Fytorium reads your plants through Planta's official API, never your Planta password."
  2. "Open Planta App Portal" (text button) opens https://getplanta.com/apps/manage in the browser.
  3. "One-time app code" (password field as now, keyboard action Go fetches), then "Fetch plants" (filled).
  4. Muted: "Fetching again updates plants you imported before: species, cultivar, site, pot and soil change to Planta's. A name you gave a plant in Fytorium stays until its name in Planta changes." (true to CONTEXT.md:185)
  While a review exists, the step folds into one text button, "Fetch again with a new code", which unfolds it.
- W5b-P22. Review. Heading "Review", then a summary line: "4 plants: 1 ready, 2 need a choice, 1 skipped" (parts with 0 left out). Cards that need a choice come first, open:
  - the Planta photo (160 dp tall; "No Planta photo" placeholder), the name (the server's `display_name`), "From Planta: Nephrolepsis exaltata · Hallway nook";
  - heading "Choose the species" (always: the photos load later and may be missing, so the heading never promises pictures), then the candidate tiles, the genus option, "Search for another species" and "Skip plant" as now.
  Ready, skipped and imported plants are one compact row each: 56 dp Planta photo, the name, "Monstera deliciosa · Kitchen", and on the right "Ready" (check icon), "Skipped" or "Imported". Tapping the row unfolds the details and that state's actions ("Change species or skip", "Undo skip", "Open imported plant").
  Details read as sentences, parts unknown left out, the line left out when all are unknown:
  - pot and soil: "Plastic pot · 17 cm · All-purpose soil". Pots: "Plastic pot", "Terracotta pot", "Ceramic pot", "Glass pot", "Metal pot", "Wood pot", "Fabric pot", "Other pot", and "In the ground" for `in_ground`. Soils: "All-purpose soil", "Cactus soil", "Orchid mix", "Aroid mix", "Other soil".
  - last care: "Last watered 10 Nov", "Last fertilized 20 Oct", "Leaves last cleaned 1 Nov", "Last repotted 2 Apr 2025". A timestamp is shown as its date in the household time zone; the year shows when it is not this year.
  - photo: "Photo comes with the import", "Photo imported", "Photo not imported: <reason> Fetch again to retry.", "Photo skipped" or "No Planta photo".
- W5b-P23. Import bar, fixed at the bottom of the page while a review is open and not imported:
  - all decided: "Import 2 plants" (filled, full width; "Import 1 plant"); with skipped rows, a line above it: "1 skipped plant stays out.";
  - nothing ready yet while some need a choice: "Import plants", disabled-looking, explaining as below;
  - some need a choice: the button is disabled-looking and explains on tap "Choose a species or skip the plants above first."; the line above reads "Still to choose:" followed by up to two names as text buttons that scroll to their cards, then "and 1 more";
  - nothing to import: "Nothing to import", disabled.
  While importing the button keeps its size and reads "Importing…". The old end-of-page button and both review summaries go (`A/planta/PlantaScreen.kt:103`, `:185-187`).
- W5b-P24. After the import: receipt "Imported 2 plants". The bar goes; the review's top shows "Import complete" and "View imported plants" (switches to Plants, W6). When the import made a site with default conditions: "New sites from Planta start with default conditions." and "Check sites", which opens Plants → Sites (a tab switch, W6). The GBIF credit stays last.

### One dropdown (Sol S67; Opus U18)

- W5b-P25. The button-style `Choice` (`A/TaskScreen.kt:383-393`) is deleted. Every choice from a list uses the plant form's exposed dropdown `Choice` (`A/plants/PlantForms.kt:71-84`): the site form (W5b-P5) and the Species care override for choice fields (`A/species/SpeciesCareScreen.kt:228`, label "Override value for light", first option "Choose a value", values "Full shade" and so on through `choiceText`). Roles become cards (W5b-P10) and the custom task target becomes the picker (W5b-P16). After W5b no screen shows a "Label: value" outlined button that opens a menu.

### Across W5b (draft D18; Sol U8 W5b part; Opus U8, U16, U18; K3)

- W5b-P26. Status and reloading (C1, C1b, C21; D18 O1). Sites list, site page, Members, Custom tasks and Import from Planta show page-level states only in the line under the title: "Offline · updated 08:12", "Fytorium isn't responding", or "<Page> didn't load" ("Sites", "Members", "Custom tasks", "The review") with the server's sentence and Try again. After a refused or unanswered change, writes pause and the line reads "<Page> can't be changed right now" ("Sites can't be changed right now") with the refusal and Try again, which reloads. Nothing is inserted above or inside the content. These pages reload on open, on app resume, on reconnect and when Fytorium answers again; the shell passes `answers` to each route. Each list pulls to refresh. No "Refresh …" button remains on a W5b page.
- W5b-P27. Offline. The sites list, members with invites, and the full custom task list are kept per member in CareCache under new resource keys (`site-list`, `members`, `custom-tasks`), wiped at log out with the rest. Offline these pages show the last copy with "Offline · updated hh:mm"; their write controls (Create site, Edit, Delete, Invite someone, Revoke, Add task, Add a task, Save, Fetch plants) look disabled and explain on tap: "Changing sites needs a connection.", "Changing members needs a connection.", "Changing tasks needs a connection.", "Importing needs a connection." The Planta review is not cached: offline after a restart the page shows the code step, disabled, and the status line.
- W5b-P28. A 426 on any W5b request calls `requireUpdate()`, so the full stop shows. The in-content update lines go (`A/household/HouseholdScreen.kt:80`, `A/planta/PlantaScreen.kt:84`).
- W5b-P29. Drafts and keys. The site form, invite form (role, plants), member page, task form (target, name, interval) and the open dialogs survive rotation, theme change and process death; each form asks with the DiscardDialog on Back, Cancel, a tab or a notification tap through `onFormDirty`, which the shell passes to `HouseholdRoute` and `PlantaRoute` too. The routes are keyed by the member id: `key(careState.member)` at `A/AppShell.kt:183` and `key(careState.member, householdArea)` at `:195` change to the id, and repositories are remembered by id. Invite links and Planta codes never enter saved state.
- W5b-P30. Every new dialog (delete site, leave link page, revoke, remove, delete task, the discard questions, the status explanation) uses DialogTitle and DialogBody and meets C37, C39 and C41 in landscape (800 × 360 dp) at 1.0x and 1.3x. Destructive buttons name the action ("Delete", "Remove", "Revoke"), never "Confirm …".
- W5b-P31. Accessibility. Tap targets at least 48 dp. A role card is one selectable target with role RadioButton; TalkBack reads its title, sentence and state. A picker row is one toggleable target with role Checkbox and reads "<name>, <site>, <state>". List rows are one target with role Button. Headings are headings. Covers in pickers are decorative (the row reads the name). Contrast at least 4.5:1 for text, 3:1 for icons.
- W5b-P32. Wording. Roles capitalised. Choice values capitalised. Dates "Sun 22 Nov" (`shortDate`), "10 Nov" or "2 Apr 2025" (`dayMonth`, `dayMonthYear`, `A/CareModels.kt:118-121`). No W5b page shows a lowercase enum value, an underscore, an ISO date, or the texts "Target", "Interval in days", "Refresh", "Confirm delete", "Copy this link now" or "Which picture looks like your plant?".
- W5b-P33. Nothing moves. Progress overlays the top edge; a failed save's message goes under its buttons into space kept for it; a field error goes into its field's supporting line. Going offline or online changes only the line under the title.

## 4. Server and API changes

OpenAPI (`api/openapi.yaml`) is updated for both; `tests/api/openapi.test.ts` passes.

- SV1. Site names are unique per household (draft A3, D10 O1). On `POST /api/sites` and on `PUT /api/sites/:id` when the name changes, the server refuses a name equal to another site's under `lower(btrim(name))`, the rule Planta import already uses (`src/lib/server/planta/commit.ts:57`): 422 `{ errors: { name: "You already have a site called <existing name>." } }`. A PUT whose name equals the site's own current name under that rule is never refused, so editing one of two existing duplicates without renaming still saves. Existing duplicates are left alone; no database constraint. Create and rename run in a transaction that first locks the household row (`SELECT id FROM households WHERE id = $1 FOR UPDATE`, the pattern at `src/lib/server/household.ts:68`), so two concurrent creates of one name give one 201 and one 422. CONTEXT.md "Site" gains one sentence on the rule. Tests: "kitchen " and "KITCHEN" next to "Kitchen" give 422 with the sentence; renaming to another site's name gives 422; renaming "Kitchen" to "kitchen" gives 200; an SQL-inserted duplicate pair can each be saved without renaming; two parallel creates give one 201 and one 422; Planta import still matches an existing site.
- SV2. `GET /api/custom-tasks` with neither `plant_id` nor `site_id` lists every live custom task in the household (draft A4). One query; each item is the existing `CustomTask` plus `target_name` (plant display name in the member's language through `plant_display_name`, or the site name), `target_site_name` (plants: their site's name or null; sites: null), `target_status` (plants: `active` or `graveyard`; sites: null) and `target_plant_count` (sites: active plants on the site; plants: null). Only tasks with `deleted_at` null whose target still exists. Sorted by target kind (sites first), target name, task name, id. With exactly one parameter the route behaves as now; with both, 422 as now. Policy unchanged (`'edit'`, `src/lib/server/policy.ts:84`). OpenAPI: the four fields are added to `CustomTask` as optional properties. `tests/api/sharing-coverage.test.ts:23` changes its GET expectation from 422 to 200. Tests: owner and family get all tasks with the right target fields; a sitter gets 403; deleted tasks and tasks of deleted targets are absent; a graveyard plant's task has `target_status` `graveyard`; a site task's `target_plant_count` counts active plants only; one-parameter calls are unchanged.
- No server change for: invites, members, removal and revocation (existing routes, `src/lib/server/household.ts`); the invite expiry (create response has `expires_at`); copying a link (client); the first due date (household date plus interval, client); site pages (sites list plus the plant list's `site_id`); Planta (no discard route: a new fetch replaces the review, Q8).

## 5. Lab setup the implementer provides

Synthetic data only. Never touch namespace `fytorium`. Scripts that call the API log in through the web as `synthetic-ux-owner` (seed.mjs pattern), never print or store invite links, tokens or Planta codes other than the synthetic lab code below, and print only fixed success or failure lines. Document each step in /home/t3/fytorium-ux/lab-runbook.md, new section 14. Record the lab state before and after each script (read-only SQL).

- L1. Sites. Seed has Kitchen, Bathroom, Office, Guestroom, Garden (seed.mjs). The script /home/t3/fytorium-ux/lab-sites `add|remove` adds an empty indoor site "Hallway" (delete test) and a duplicate pair for SV1's exception: "Porch" through the API and a second "porch" through one SQL INSERT into the owner's household. `remove` deletes those three sites through the API, if they still exist and have no active plants.
- L2. Members and invites. Family and sitter exist (`synthetic-ux-family`, `synthetic-ux-sitter`, the sitter sharing Spider Plant and Thyme, seed.mjs:119-123). The script /home/t3/fytorium-ux/lab-members:
  - `helper`: creates a family invite and accepts it in-process as the new subject `synthetic-ux-helper` (display name "Synthetic Helper"), so Remove can be tested without touching family or sitter. Run it again after a removal: an invite reactivates a removed member (CONTEXT.md:271).
  - `invites`: creates one sitter invite (Spider Plant, Thyme) and one family invite and keeps only their ids in a file outside /work/fytorium; `clean` revokes the invites in that file that are still pending.
- L3. A plant with a twin name. The script /home/t3/fytorium-ux/lab-twin `add|remove` adds a Chlorophytum comosum plant in Office without a nickname, so the invite picker shows two rows with Spider Plant's display name on different sites; `remove` deletes it through the API with its confirm name. It adds Water tasks to Today; regression checks account for it.
- L4. Custom tasks. The script /home/t3/fytorium-ux/lab-tasks `add|remove` creates "Mist leaves" every 7 days on Monty (Swiss Cheese Plant), "Wipe windowsill" every 14 days on the Kitchen site, and a plant "Old aloe" (Aloe vera, Garden) with "Check for rot" every 30 days, then moves Old aloe to the graveyard. `remove` deletes those tasks and permanently deletes Old aloe.
- L5. Planta. The existing fakes container already answers as `planta` (compose.yaml:115) and the lab app keeps `PLANTA_BASE_URL=http://planta:8080/v1` and `PLANTA_TEST_IMAGE_ORIGIN=https://planta:8443` (compose.yaml:32-33; compose.lab.yaml does not change them). The script /home/t3/fytorium-ux/lab-planta posts runtime stubs to that WireMock's admin API (from `emu`, as section 9 does for plantnet-lab), so no tracked file or container changes:
  - `add`: code `synthetic-lab-planta` (a synthetic lab code, not a secret) authorises with access token `synthetic-lab-access`; `GET /v1/addedPlants` with that bearer header returns one page of four plants:
    1. custom "Kitchen monstera", scientific "Monstera deliciosa", site "kitchen" (matches Kitchen), plastic pot 17 cm, all-purpose soil, watered "2026-11-10" (date only), fertilized "2026-10-20T09:00:00Z", repotted "2025-04-02", cover `https://planta:8443/import-cover.png`;
    2. custom "Hall fern", scientific "Nephrolepsis exaltata" (misspelt, for a review with candidates), site "Hallway nook" (new, default conditions), terracotta 14 cm, tropical soil, cleaned "2026-11-01", same cover;
    3. custom "Mystery succulent", no scientific name, site "Hallway nook", no cover (review without candidates);
    4. no custom name, `localizedName` "Snake plant", scientific "Dracaena trifasciata", site "Office", terracotta pot, no size or soil (nickname from Planta's name, U5).
  - `reset`: deletes through the owner API the plants whose Planta ids start with `synthetic-lab-planta-` and the site "Hallway nook", so the next member's first import starts fresh.
  - `remove`: `reset`, then deletes the stubs by id.
  Stubs use the same priority as `tests/stack/mappings/planta.json` and match on the bearer header; WireMock picks the newest of equal-priority matches. The implementer checks with `curl` from `emu` that the lab code returns the four plants and the browser-test code still returns its own three. The real GBIF decides which rows are ready; the implementer records each row's status after a first fetch. Stubs vanish when the fakes container restarts: run `add` again (section 7).
- L6. Phone. Nothing new. Clipboard checks paste into a text field in the app and compare only the start of the text (T11); members never write a full link into a report.

## 6. Accepted limits and shortcuts

- A1. An invite link is gone after process death, by design (never saved). The page says so and offers "Revoke and create new".
- A2. Android 10 to 12 have no sensitive-clipboard flag; other apps that read the clipboard can see a copied link until it is replaced.
- A3. Existing duplicate site names stay. Only new names and renames are checked.
- A4. Two plants with the same name on the same site differ only by photo in the pickers.
- A5. "First due" assumes plants already in the household; a plant added later starts from its own date, and the site wording says "for the plants there now".
- A6. The Planta review is not kept offline; a staged review cannot be discarded, only replaced by a new fetch.
- A7. "View imported plants", "Open imported plant" and "Check sites" switch tabs; Back across tabs is W6.
- A8. A tab tap still closes More and Plants sub-screens (W6). W5b asks first when a form has changes or a link was not sent.
- A9. A Planta code typed but not fetched is lost on leaving, with no question: it is never saved by design.
- A10. Invite expiry and Planta last-care timestamps show in the phone's time zone (the app does not hold the household's zone; date-only values show as sent). The lab phone and household are both Europe/Oslo.
- A11. Lab Planta covers are WireMock's 1 × 1 image; the photo suggestion in the lab comes from plantnet-lab's fixed answers.

## 7. Acceptance checks

Run on the lab emulator as the W5a brief describes (`emu ui`, `emu text`, `emu key 66`, `emu shot`, test clock 2026-11-15, read-only SQL, `svc wifi/data`, font scale 1.3, rotation, dark mode), at 1.0x and 1.3x, portrait and landscape, light and dark, unless a check says otherwise. Each check passes only if every listed result holds. "Kill" means Home, `emu sh am kill com.epaflix.fytorium`, reopen. Leave the lab as the W5a brief says, plus: `lab-planta reset`, `lab-members clean` and `lab-members helper` (if you removed the helper), no site, task or invite of your own left, sitter shares back to Spider Plant and Thyme.

Sites
- T1 (way in). As owner and family: More has no "Sites" row; the Plants overflow lists "Sites" then "Graveyard". In the grid, each site heading reports a click label "Open <site>" and opens that site's page; Back returns to the grid at the same scroll offset (`emu ui` bounds of the first visible tile before and after). On a plant page, the site line opens its site; Back returns to the plant page. As sitter: no overflow, headings and the plant page's site line are not tappable.
- T2 (list). "Sites" lists every site from `SELECT name FROM sites` for the household, with "Indoor · No plants" on Hallway and plant counts equal to `count(*)` of active plants per site. No "Refresh sites" text anywhere. A pull shows the indicator and reloads. Rows open their site page.
- T3 (site page). Kitchen shows its conditions as sentences, "Plants (n)" with exactly the active plants whose `site_id` is Kitchen's, each tile opening its plant; Back from that plant returns to Kitchen. Kitchen's top bar has no overflow and the page reads "To delete Kitchen, move its n plants to another site or to the graveyard first." Hallway has the overflow with "Delete site".
- T4 (form). New site: "Location", "Light", "Humidity" are exposed dropdown fields showing "Indoor", "Part sun", "Normal"; the menu lists capitalised values. "Draft from a door, window or vent" with its line. "Temperatures (optional)" is folded; unfolded, type "-5" in "Cold minimum °C" (minus typeable). Name "kitchen ", Save: "You already have a site called Kitchen." under Name, the typed name stays, SQL unchanged. Name "Lab shelf", Save: receipt "Created Lab shelf", the new site page opens, SQL row matches every field. Edit Kitchen, rename to "KITCHEN", Save: saved. Edit "Porch" (L1 pair), change Humidity, Save: saved.
- T5 (delete). Lab shelf → Delete site → "Delete site?" with the body naming Lab shelf; Cancel keeps it; Delete: receipt "Deleted Lab shelf", the Sites list without it, SQL row gone. Race: open Hallway's Delete dialog, move a plant into Hallway through the API, tap Delete: the 409 sentence shows in the dialog, which stays; move the plant back.
- T6 (drafts). Half-typed new site: rotate, dark mode, kill: the form is open with the values and the temperatures section unfolded if it had a value. Back asks "Discard changes?"; a tab tap asks the same.
- T7 (offline). Open Sites and Kitchen online. Wi-Fi and data off, kill, open both: the last list and page with "Offline · updated hh:mm"; Create, Edit and Delete look disabled and explain "Changing sites needs a connection."; connection back: the line clears without a tap.
- T8 (other ways in, K1, K2). Restore a graveyard plant → "Create a site": the new-site form opens directly; typing an existing name is refused under Name; a new name returns to Restore with the site chosen. Add plant → "Create a site" the same. A plant-warning card's Why? for site light reads "under Plants, Sites".

Members and invites
- T9 (list). Members shows People with "Synthetic Owner (you)" "Owner", "Synthetic Family" "Family", "Synthetic Sitter" "Sitter · 2 plants", and Pending invites (after `lab-members invites`) "Sitter invite" "Spider Plant, Thyme · Expires Sun 22 Nov" and "Family invite" "Expires Sun 22 Nov". No lowercase role, no ISO date, no "Refresh members". Offline after a kill: the same rows with "Offline · updated hh:mm", "Invite someone" explains "Changing members needs a connection." (K3)
- T10 (invite form). "Invite someone": both cards unselected; "Create invite link" explains "Choose Family or Sitter first.". Choose Sitter: the picker lists active plants with photo or initial, name and site; the two Spider Plant rows (L3) show "Bathroom" and "Office". Tick the Office one, rotate, kill: role and tick kept. Create: the link page; SQL `household_invites` has a sitter row and `invite_plants` holds exactly the Office plant.
- T11 (link page). "Share link" opens the system chooser. "Copy link": the system confirmation shows with the content hidden (API 35); paste into Plants search: the text starts with "http://app:3000/invite/" (check only that start, then clear the field). Rotate and dark mode: Share and Copy still work. Back before any Share or Copy on a fresh link: "Leave without sending the link?"; Stay keeps the page. Kill on the link page and reopen: "This link can't be shown again"; "Revoke and create new" shows a new link page; SQL: the old invite has `revoked_at`, a new one exists with the same role and plants. `adb logcat -d | grep -c /invite/` is 0.
- T12 (member page). Open Synthetic Sitter: Sitter selected, Spider Plant (Bathroom) and Thyme ticked; tick another plant, Save: receipt "Saved changes for Synthetic Sitter", SQL `plant_shares` match. Untick it again and Save. Unsaved change then Back: "Discard changes?".
- T13 (remove). `lab-members helper`; open Synthetic Helper → "Remove from household": the dialog names them and says they are logged out on every device; Cancel keeps them; Remove: receipt "Removed Synthetic Helper", SQL `members.removed_at` set and their `app_devices` revoked. Run `lab-members helper` again.
- T14 (revoke). Revoke the sitter invite: "Revoke this invite?" with "The sitter invite for Spider Plant and Thyme stops working. It would have expired on Sun 22 Nov."; Revoke: receipt "Revoked the sitter invite", SQL `revoked_at` set.

Custom tasks
- T15 (list). After `lab-tasks add`: Kitchen group ("Site · every plant here, now n") with "Wipe windowsill" "Every 14 days"; Monty group ("Plant in Guestroom") with "Mist leaves" "Every 7 days"; Old aloe last with the graveyard line. No "Target", no "Refresh". Offline after a kill: the same with the offline line.
- T16 (add from list). "Add task" → "For which plant or site?" lists sites and active plants, no Old aloe; search "fern" narrows to Fernando. Pick Fernando: target line "For Fernando · Bathroom"; "1 to 365 days" shows before typing; interval "0" and Save: "Enter whole days from 1 to 365." under the field and SQL unchanged; "366" the same; "7": "First due Sun 22 Nov"; name "Test spray", Save: receipt "Added Test spray for Fernando"; Upcoming lists Test spray for Fernando on Sun 22 Nov. A site task shows "For every plant in Kitchen, also plants added later" and "First due … for the plants there now".
- T17 (plant page). On Thyme's page: "Add a task" under Care opens the form with "For Thyme · Garden" and no "Change"; save "Test prune" every 14 days: receipt, back on Thyme's page, a "Test prune" Care row reading "Due in 14 days".
- T18 (edit, delete, drafts). Edit Test spray: type a new name, rotate, kill: the form is back with it; Back asks. Delete task: "Delete task?" with "Fernando stops getting Test spray. Its past care stays in history."; Delete: receipt, SQL `deleted_at` set, the row gone. Delete Test prune the same way.

Planta
- T19 (code step). `lab-planta add`, `lab-planta reset`. "Open Planta App Portal" opens the browser at getplanta.com. Type the lab code, rotate: the field is empty (never saved). Type it again, press Go (key 66): no character added, the review loads.
- T20 (review). Summary line counts match the rows. "Hall fern" and "Mystery succulent" come first, open, under "Choose the species"; Mystery succulent offers search. Kitchen monstera is a compact row "Monstera deliciosa · Kitchen" with "Ready" (if GBIF matched; L5 records it); unfolded: "Plastic pot · 17 cm · All-purpose soil", "Last watered 10 Nov", "Last fertilized 20 Oct", "Last repotted 2 Apr 2025". The Snake plant row reads "Terracotta pot" only and is headed "Snake plant". No underscore, "unknown" or ISO date (`emu ui`).
- T21 (bar and import). Counts below assume L5's expected statuses; use the statuses L5 recorded. The bar reads "Still to choose: Hall fern, Mystery succulent" and a disabled-looking "Import 2 plants" that explains on tap. Tap "Hall fern" in the line: its card scrolls into view. Choose a species for Hall fern, skip Mystery succulent: "Import 3 plants" with "1 skipped plant stays out.". Import: the button reads "Importing…" without moving, then receipt "Imported 3 plants", "Import complete", "New sites from Planta start with default conditions." and "Check sites". SQL: three plants with `planta_plant_id` `synthetic-lab-planta-*`, the Snake plant's nickname "Snake plant", a site "Hallway nook" with `imported_conditions_incomplete` true. "Check sites" opens Plants → Sites, where Hallway nook shows "Conditions not set". End with `lab-planta reset`.
- T22 (status). On the Planta page: no "Refresh review"; a pull reloads; Wi-Fi off changes only the line under the title and "Fetch plants" explains "Importing needs a connection.".

Across
- T23 (dropdowns). Site form and a Species care override for Light (Plant page → Details → Care defaults for this species → override light) show an exposed dropdown field with a trailing arrow; the menu values are capitalised. `emu ui` on Sites, site form, Members, invite form, member page, Custom tasks, task form and Species care finds no button text of the form "<Label>: <value>".
- T24 (426). With `lab-min-version 1.0.1` while each of Sites, Members, Custom tasks and Import from Planta is open, pull to refresh: the full "Update Fytorium" stop shows, not a line in the page. End with `off`.
- T25 (nothing moves). On the Sites list, a site page, Members, Custom tasks and the Planta review, toggle Wi-Fi off and on: `emu ui` shows every row at the same y; only the line under the title changes. A failed save (stop `fytorium-app-1`, Save a site) puts its message under the buttons without moving them; start the container and set the clock.
- T26 (dialogs). Delete site, leave link page, revoke, remove, delete task, each discard question and the status explanation in landscape at 1.0x and 1.3x: title at most two lines, body scrolls inside with dividers only over hidden content, both buttons visible.
- T27 (keys). Open a member page with an unsaved change, the invite form with Sitter chosen, and a task form with typed text, one at a time; kill and reopen each: still open with the change (no member-object key).
- T28 (TalkBack). `emu ui` content descriptions: grid headings ("Open Kitchen"), role cards (title, sentence, selected state), picker rows ("Spider Plant, Office, not checked"), list rows (title and supporting line), the import bar's state.
- T29 (regression). W3: Plants grid, search, Graveyard and plant page behave as before apart from the tappable headings and site line; W4: Add plant end to end with "Create a site"; W5a: T1, T8 and T19 of the W5a spec still pass; Species without care data unchanged.
- T30 (wording). On every W5b page: none of the texts in W5b-P32, no lowercase role or choice value, no ISO date.

### Item to check map

| Item | Checks |
|---|---|
| Sol S49, Opus S26 (list) | T1, T2, T7 |
| Sol S50, S51, F21, Opus F9 | T4, T6, T8 |
| Sol S52 | T3, T5 |
| Sol U35 | T1, T3 |
| Sol U36 | T3, T5 |
| Sol S53, Opus S28 | T9 |
| Sol S54, F22, U39, Opus F10 | T10 |
| Sol S55, U41, Opus U25 | T11 |
| Sol S56, F23 | T12, T27 |
| Sol S57 | T13 |
| Sol S58 | T14 |
| Sol U40 | T5, T13, T14, T18 |
| Sol S59, F24, Opus S29 | T15, T16, T17 |
| Sol S60 | T16 |
| Sol S61 | T18, T27 |
| Sol S62 | T18 |
| Sol S63, U44 | T19 |
| Sol S64, S65, U45, Opus S30 | T20 |
| Sol S66, F28, Opus F12 | T21 |
| Opus U5 (regression) | T20, T21 |
| Sol S67, Opus U18 (dropdowns) | T4, T23 |
| Sol U8 (W5b part) | T6, T10, T18, T27 |
| Opus U8 (back arrows) | T1, T3, T9, T15, T19 |
| Opus U16, audit F13 (W5b part) | T2, T9, T15, T22 |
| Opus U18 (casing) | T9, T20, T30 |
| K1 | T4, T8 |
| K2 | T8 |
| K3 | T9 |
| All | T24, T25, T26, T28, T29 |

## 8. Implementation order

Each slice builds, passes its tests and can be reviewed alone. Server slices run the full web suite (`./scripts/stack test`) and typecheck; Android slices run unit, Robolectric screenshot and accessibility tests, with screenshots updated, not deleted.

1. Server SV1 and SV2: route code, OpenAPI, sharing-coverage, CONTEXT.md, tests.
2. One dropdown: delete the button `Choice`, move Sites and Species care to the exposed dropdown (W5b-P25).
3. Sites in Plants: More row and shell flag out, overflow, tappable headings and site line, Sites list, site page, form, receipts, delete, texts, site-list cache (W5b-P1 to W5b-P8, part of W5b-P27).
4. Members and invites: list, role cards, picker, link page and its process-memory holder, member page, dialogs, receipts, cache (W5b-P9 to W5b-P14).
5. Custom tasks: list from SV2, picker, form, plant page entry, delete, cache (W5b-P15 to W5b-P19).
6. Planta: code step, review rows, readable values, import bar, after-import (W5b-P20 to W5b-P24).
7. Across: status lines, `answers`, pull to refresh, 426, member-id keys, `onFormDirty` for More routes, dialogs, accessibility, wording (W5b-P26 to W5b-P33); android/README.md "Sites", "Household management" and "Planta import" updated.
8. Lab L1 to L6 and the runbook (can run beside 1-7), then a full run of T1-T30 by the implementer before the council round.

## 9. Open questions (each with my recommendation)

The draft and the owner's answers leave three gaps. The spec follows the recommendation unless Spyros says otherwise.

- Q1. May a sitter invite share no plants? The server allows it (`src/lib/server/household.ts:24`). Recommendation: allow it, with the line "No plants chosen. You can share plants later from Members." (W5b-P10). The sitter then sees "No plants are shared with you yet." (W5a-P3).
- Q2. Should leaving the link page before Share or Copy ask? The draft says the link can't be seen again but not whether leaving asks. Recommendation: ask (W5b-P11). One extra tap in the rare case beats a revoke-and-recreate.
- Q3. Should "For which plant or site?" offer graveyard plants, as the current Target menu does? Recommendation: no; they get no reminders. Their existing tasks stay listed and editable (W5b-P15, P16).

## 10. Not verified

- Anything live. I did not touch the lab pod or the emulator; the lab's current sites, plants, members, invites and staged Planta rows may differ from the seed after earlier council rounds.
- From code only: the invite link lost on rotation, and the missing C21 reload from More → Sites.
- Whether the real GBIF matches the lab Planta names as L5 expects (ready, review with candidates, review without).
- Whether WireMock's newest-stub rule lets the L5 runtime stubs answer without changing `tests/stack/mappings/planta.json`.
- How the API 35 emulator shows a sensitive clip, and whether Plants search accepts a paste via `emu`.
- Whether the exposed dropdown inside the Species care dialog meets C37, C39 and C41 in landscape at 1.3x.
- Whether the web site form shows the new SV1 name error well; the web must only keep building and passing its tests.
- That production has no duplicate site names (production data was not read, by rule). Duplicates there would keep working under SV1's exception.
- The Planta App Portal address beyond its redirect to a login page titled "Planta App Portal" (read 2026-11-22).

## Owner decisions assumed (2026-11-19)
Q1: Sites move into the Plants tab (D9 O2). Q6: the app may copy an invite link to the clipboard. Q7: the name stays "Custom tasks". Q8: the real Planta import is done and Planta import is a rarely used tool, so its entry points stay and its server is unchanged.
Section 9 Q1 to Q3: the recommendations stand (Spyros, 2026-11-20: decide without asking; the goal is 9.5 and above).
