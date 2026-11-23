# W6: Navigation and back stack

Wave W6 of the Android UI/UX programme. Built on branch t3/w6-navigation at 1958ea0, which contains W1 to W5b. Goal: every W6 item below scores 9.5 or higher for every council member (GPT-6.2 Sol, GPT-6.2 Astra, Claude Opus 5.5) on a live lab build, judged by the "Scoring bar" in council-w5b-brief.md. Sol and Astra score Sol's item IDs; Opus scores Opus's.

Sources: merged audit F10 (/home/t3/handoffs/fytorium-ux/2026-11-09-fytorium-ux-audit.md); council-sol.md U8, U15, U16, U17 and flows F8, F10, F12, F17; council-opus.md U8, U12 and flow F11; the out-of-scope and accepted-limit lines that W2 to W5b left to W6 (W2 spec line 10, W3 lines 10 and 80, W4 line 49, W5a line 48 and A10, W5b line 47, W5b-P24, A7, A8); the W4 follow-up in 2026-11-22-w4-done.md; the W6 notes in the council reports of W2 to W5b.

Paths: `A/` = `android/app/src/main/java/com/epaflix/fytorium/`; `T/` = `android/app/src/test/java/com/epaflix/fytorium/`. Line numbers are at 1958ea0.

Reused by name, not redesigned:
- every screen's own pages and their Back, as W3 to W5b built and the council passed them: PlantsScreen's `page` machine (`A/plants/PlantsScreen.kt:36-37`, `back()` at `:148-161`), SitesRoute, MembersRoute, CustomTasksRoute, PhotosRoute, SpeciesCareRoute, NotificationRoute, PlantaRoute, WeatherFeedRoute; and their place keeping (W3 C27, C33-C35; W4-P36; the W5b list places);
- the DiscardDialog and LeaveDialog (`A/plants/PlantsScreen.kt:498-506`), the question each form reports through `onFormDirty` (`A/AppShell.kt:84`, `:92`), and W5b's link-page question (`leaveQuestion`, `A/household/MembersScreen.kt:68`);
- receipts over the navigation bar and the 500 ms bar pause after a receipt (W2 A13a, A13h, A13i; `A/AppShell.kt:127-170`);
- the top bar arrow (`Icons.AutoMirrored.Filled.ArrowBack`, content description "Back") that every W3 to W5b page has;
- drafts that survive rotation, theme change and process death (`rememberSaveable`, `savedFields`), with routes keyed by the member id, never by the `Member` object (W5b-P29);
- the member and role rules that close pages (`A/AppShell.kt:101-121`, covered by `T/AppShellRestorationTest.kt:25-100`);
- W2 A1 (nothing moves under the finger) and W2 A14 / W3 C2 (a return to a page counts as a return, so fresh data may show then);
- Today's separate Today and Upcoming scroll states (`A/AppShell.kt:61-62`).

W6 has no server change and no new dependency. UI wording below is in straight quotes. Example names are synthetic and match the lab seed.

## 1. Scope

### Council items in W6

No screen or flow item covers the navigation findings, so the council scores them as items, with the area score as the audit score.

| Area | Sol (and Astra) | Opus |
|---|---|---|
| Navigation (area) | Navigation 3.5 | Navigation 3 |
| Findings scored as items | U8 Tab switch or Back drops editing context (S1), U15 Today and Upcoming share one scroll (S2, regression only, fixed in W2), U16 Search jumps tabs and forgets the query (S2), U17 Back ignores where a plant was opened (S2) | U8 Tab jumps and Back lands in the wrong place (S2), U12 The bell is settings, not notifications (S2, regression only, fixed in W2) |
| Flows, W6 part only | F10 Find a plant 5 (W3: 10), F12 Edit plant 5 (W3: 10; work lost on a tab switch), F28 Planta import 5 (W5b: 9.5; the three switches after import) | F6 Edit plant 5 (W3: 10; tab part), F11 Notifications 4 (W5a: 9.5; Back part), F12 Planta import 6 (W5b: 10; the three switches) |

Audit F10 (both members) is in scope in full.

Carried notes, all in scope:
- K1 (Opus W2 r1). Back from a plant opened from a feed card lands on the Plants grid, not Today. Reselecting Today does not scroll back to the top.
- K2 (Opus W3 r1). The bottom bar highlights Plants while a plant opened from Today is shown.
- K3 (Opus W3 r1 to r12). A tab switch closes the plant page and clears the Plants search. A late tap after a receipt can land on a tab; W2 A13i limits it, and W6 makes such a switch lose nothing.
- K4 (Opus W3 r6). A plant created on another device appears in Plants only after a pull.
- K5 (Sol W4 r6, Opus W4 r5). Journal name link, then Back, lands on Plants.
- K6 (W4 draft U5; Sol and Astra W4). Today "+" switches the bar to Plants; Back from the new plant lands on the Plants grid; the Journal tab's place in the bar.
- K7 (W5a A10, W5b A8, Astra W5b). A tab tap closes More and Plants sub-screens; reselecting a tab.
- K8 (W5b-P24, A7). Planta's "View imported plants", "Open imported plant" and "Check sites" switch tabs.
- K9 (W4 done). A tab switch right after Save can delete the kept identify photo before it uploads.
- K10 (Sol W2 O4). Sitters still have a Plants tab and Today's search.
- K11 (new, code). Back at the More root leaves the app, while Back at the Plants and Journal roots goes to Today.
- K12 (new, code). The app has not opted in to predictive back.
- K13 (new, code). Pages clean up their photos when they leave composition (`A/plants/PlantsScreen.kt:95-99`, `A/photos/PhotoSources.kt:80-86`). Once tabs keep their pages, that would delete a hidden draft's photos.
- K14 (new, code). Log out does not delete kept identify photos (`noBackupFilesDir/photo-drafts`, `A/photos/PhotoImages.kt:80-81`); the log-out cleanup clears only `cacheDir/captures` (`A/FytoriumApplication.kt:38-48`).
- K15 (new, code, not seen live). The Journal's PhotosRoute is keyed by the `Member` object (`A/AppShell.kt:251`), the key W5b-P29 forbids, so a Journal photo draft is likely lost after process death.

Regression only, must not get worse: every W3 to W5b item on the pages W6 touches, in particular W3 S12, S13, S16, F10, F17 (place keeping, graveyard Back), W4 Add plant, Identify and Journal, W5a More and its pages, W5b Sites in Plants, Members, Custom tasks, Planta; W2 F8 (Upcoming scroll); W1 names; receipts.

### Out of scope
- Rewriting screens' own pages (PlantsScreen's 20 pages, the More pages' internal steps). They passed W3 to W5b; W6 changes only how the shell moves between screens and the few lines where a screen hands Back to the shell.
- Deep links from notifications to a plant or task. Every notification still opens Today (W6-A4).
- The web UI.

## 2. Current behaviour (checked against the code)

The shell is a set of saved flags, not a stack: `tab`, `household`, `sites`, `journalPlantId`, `journalAdd`, `unmatched`, `plantaImport`, `careSpecies`, `addPlant`, `plantsSearch`, `notifications`, `about`, `feedSettings`, `feedPlant`, `todayTaskKey` (`A/AppShell.kt:51-81`). A tab tap calls `select()`, which resets all of them (`:90`). Only the current tab is composed, so leaving a tab also drops every page state that lives inside that tab's screens. Navigation Compose is not in the Gradle files (`android/gradle/libs.versions.toml`; `android/app/build.gradle.kts:95-106`); `androidx.activity:activity-compose` 1.10.1 is, which has `BackHandler` and `PredictiveBackHandler`.

| # | Complaint (source) | Status | Evidence |
|---|---|---|---|
| C1 | Today row switches the bar to Plants (audit F10, Opus U8, K2) | Open. Back target already right. | `onOpenTask` sets `tab = "Plants"` (`A/AppShell.kt:265`); PlantsScreen gets `returnToToday` (`:281`), so Back calls `onBack` (`A/plants/PlantsScreen.kt:153`) and Today keeps its scroll (`T/TodayNavigationTest.kt:23-25`). |
| C2 | Bell opens notification settings; Back lands on More (audit F10, Opus U12, Opus F11) | Fixed in W2 (D1) and W5a | Today's bar has Search, Task view and Add, no bell (`A/TaskScreen.kt:141-147`). Notifications open only from More (`A/MoreScreen.kt:44-46`); Back returns to More (`A/AppShell.kt:182`). |
| C3 | Search icon only switches to Plants (audit F10, Sol U16, Opus U8) | Partly fixed in W3 (P2) | The field takes focus (`A/plants/PlantViews.kt:88`), and the query survives opening a plant inside Plants (`A/plants/PlantsScreen.kt:41`). The icon still switches tabs (`A/AppShell.kt:270`), and any tab trip loses the query, because PlantsScreen leaves composition. |
| C4 | A tab tap resets sub-screens and drops drafts (audit F10, Sol U8, Opus U8, K3, K7) | Partly fixed in W3 to W5b | Since W3 C19 a form with changes asks first ("Discard changes?", `A/AppShell.kt:92`, `:171-174`). After the answer the draft is still dropped, and pages without changes (a plant page, a search, a list's scroll) close silently. With no draft, reselecting Plants from a page opened inside Plants does nothing (the `key` at `:279` does not change). |
| C5 | Three Back styles: arrow, "Back" text button, none on About (audit F10, Opus U8) | Fixed in W5a | About has its arrow (`A/AppShell.kt:140`); Notifications and Weather and feed use the top bar arrow (`A/notifications/NotificationScreen.kt:175`, `A/feed/FeedSettingsScreen.kt:138`). The remaining "Back" texts are a dialog button (`A/plants/SpeciesSearchScreen.kt:183`) and "Back to results" (`A/photos/PhotoScreens.kt:400`), not page navigation. |
| C6 | Today and Upcoming share one scroll (Sol U15) | Fixed in W2 | Two saved scroll states (`A/AppShell.kt:61-62`, used at `:264`); Sol F8 scored 10. |
| C7 | Feed card plant link, then Back, lands on Plants (Sol U17, K1) | Open | `feedPlant` and `tab = "Plants"` (`A/AppShell.kt:275`); `returnToToday` is false without a task key, so Back goes to `from = "list"` (`A/plants/PlantsScreen.kt:155`). |
| C8 | Journal name link, then Back, lands on Plants (Sol U17, K5) | Open | `onPlant = { journalPlantId = it; tab = "Plants" }` (`A/AppShell.kt:253`). |
| C9 | Graveyard, plant, Back loses the graveyard (Sol U17) | Fixed in W3 | `from = page` (`A/plants/PlantsScreen.kt:358`, the grid's `onPlant`) and `page = from` (`:155`); Sol S16 scored 10. |
| C10 | Today "+" switches the bar to Plants; Back from the new plant lands on the grid (K6) | Open | `onAddPlant = { addPlant = true; ...; tab = "Plants" }` (`A/AppShell.kt:271`); after Save, `page = "detail"` with `from = "list"`. |
| C11 | Empty Today's "Import from Planta" opens More (code) | Open | `onImport = { tab = "More"; plantaImport = true }` (`A/AppShell.kt:271`); Back from Planta then shows the More list. |
| C12 | Planta's three links switch tabs (K8) | Open | `onPlants`, `onPlant`, `onSites` close Planta and set `tab = "Plants"` (`A/AppShell.kt:194-196`); Back from the plant or the Sites list lands on the Plants grid. |
| C13 | Back at a tab root differs by tab (K11) | Open | Plants root calls `onBack` and goes to Today (`A/plants/PlantsScreen.kt:153`, `A/AppShell.kt:280`); Journal root does the same (`A/AppShell.kt:253`); More and Today have no handler, so Back leaves the app. |
| C14 | Bar highlight while a page from another tab is open (K2) | Open | The bar shows `selected = tab == title` (`A/AppShell.kt:147`), and every cross-tab link changes `tab`. |
| C15 | Notification taps | Works, to be kept | `OPEN_HOME` (`A/notifications/PushMessages.kt:42`, `A/MainActivity.kt:22-23`) calls `leave("home")`: Today, Today view, asking first when a form in any tab has changes (`A/AppShell.kt:91`, `:99`). |
| C16 | Kept identify photo deleted by a tab switch right after Save (K9) | Open | The upload runs in the screen's `rememberCoroutineScope` (`A/plants/PlantsScreen.kt:120`, `:438-446`); a tab tap after Save does not ask (`formChanged = { false }`, `:437`), disposes PlantsScreen, cancels that scope and deletes the file (`:95-99`). |
| C17 | Predictive back (K12) | Not opted in | No `android:enableOnBackInvokedCallback` in `android/app/src/main/AndroidManifest.xml`; targetSdk 35 (`android/app/build.gradle.kts:55`). |
| C18 | Cross-device refresh (K4) | Fixed, from code only | PlantsScreen reloads on `ON_RESUME` (`A/plants/PlantsScreen.kt:236-241`); a lifecycle observer added while the activity is resumed receives `ON_RESUME` at once, so every entry into Plants reloads. Not seen live since W3 r6. |
| C19 | Sitter has a Plants tab and Today search (K10) | As designed | Sitters get Today, Plants, More (`A/AppShell.kt:146`); their Plants grid lists shared plants only. |

### Corrections to earlier notes
- The audit's F10 cause line ("about 15 boolean flags") is close: 15 saved flags and ids today, listed above.
- Opus U12 and F11's bell part, audit F10's bell and Back-style bullets, and Sol U15 are closed. W6 checks them as regressions only.

## 3. Decisions

### The model (audit F10 fix line; Sol U17 smallest fix; Opus U8 smallest fix)

- W6-P1. One back stack per tab, in the shell. AppShell keeps, for each tab, a list of page routes whose first route is the tab's root (Today, Plants, Journal, More). The routes are plain strings saved with `rememberSaveable`, so they survive rotation and process death. Each page's own state lives in one `rememberSaveableStateHolder()`, keyed by the page's tab, position and route (for example `More/1/members`). Only the top page of the selected tab is composed. A tab tap shows that tab's top page as it was left. This is what Navigation Compose's documented bottom-bar setup gives (`saveState` and `restoreState`: one saved back stack per tab, restored on a tab tap), written in the app's own code.
  Why not Navigation Compose: it is not in the Gradle files and the owner's rule is no new dependency. It would also not remove a mechanism: PlantsScreen's pages would either be rewritten as library routes (a rewrite of pages the council passed) or stay beside them, which is two mechanisms anyway. The 15 flags the shell has now are already a stack without history; W6 replaces them with the lists.
- W6-P2. Pages the shell opens. Everything else stays inside its screen.

  | Route | Opened from | Shows | Back goes to |
  |---|---|---|---|
  | `plant/<id>`, plus the occurrence when opened from a Today row | a Today or Upcoming row; a feed card that links a plant; a Journal card's plant name; Planta "Open imported plant" | the plant page (PlantsScreen at `detail`, with the row's task selected as now) | the page that opened it, at the same place |
  | `find` | Today's search icon | "Find a plant" (W6-P13) | Today |
  | `add` | Today's "+" and the empty Today's "Add plant" | Add plant (PlantsScreen at `search`); after Save, the new plant page | Today |
  | `sites` | Planta "Check sites" | the Sites list (PlantsScreen at `site-pages`) | Import from Planta |
  | `planta` | More, Import from Planta; the empty Today's "Import from Planta" | Import from Planta | More or Today |
  | `weather` | More, Weather and feed; Today's "Set your household location" card | Weather and feed | More or Today |
  | `notifications`, `members`, `custom-tasks`, `unmatched`, `about` | their More rows | as now | More, at the same place (`moreScroll`) |
  | `species/<key>` | a row in Species without care data | Species care | Species without care data |
  | `journal-pick`, `journal-add/<plant id>` | Journal "+" | "Choose a plant", then the photo form (W4-P32) | as now |

  The Plants tab's root is PlantsScreen at its grid, with all its pages (plant page, forms, Graveyard, Sites, Add plant from the Plants "+") inside it, as now.
- W6-P3. Handing Back to the shell. A screen calls its `onBack` when Back leaves its first page; the shell then removes the top route. A PlantsScreen opened by the shell as `plant`, `add` or `sites` never shows the grid: wherever its Back would show the grid (`from = "list"`, the Add search page, the Sites list returning to `list`, a delete returning to `from`; `A/plants/PlantsScreen.kt:153-158`, `:256`, `:304-305`), it calls `onBack` instead. This one rule replaces `returnToToday` (`:34`, `:153`). The `find` page is the one PlantsScreen whose first page is the grid.
- W6-P4. State. Hiding a tab or covering a page keeps everything that page had: its inner page, scroll, query, typed draft, open dialog, unfolded sections. A page's saved state is removed only when its route leaves the stack: Back, reselect (W6-P6), a notification tap (W6-P16), "View imported plants" (W6-P15), or the member rules (W6-P17). Every page key uses the member id, never the `Member` object; that also fixes K15. Restoring after process death brings back every tab's stack and pages. Saved state must not crash the app: Android's Binder buffer is 1 MB per process, and Android's guidance is to keep saved state under 50 KB. W6-T13 measures the Bundle in a busy scenario, fails above 200 KB and reports the size; above 50 KB the implementer lists what takes the space before the council round (both numbers are my budget, not a measurement).

### Tabs

- W6-P5. A tab tap never closes a page and never asks. The tapped tab shows what it showed when the user left it, including a form with changes, its open dialog and an invite link page. The tab-tap discard question goes (`A/AppShell.kt:92` for tab taps, `:147`); the LeaveDialog stays for W6-P6, W6-P15 and W6-P16. `InviteLinks.clear()` leaves the tab tap (`:90`): a link stays in memory while its page is in More's stack and goes when that page closes or at log out, as W5b-P11 says.
- W6-P6. Reselecting the current tab:
  1. above the root (a pushed page, or an inner page of the root screen): go back to the root in one step. The root keeps its own state: the Plants grid keeps its query and scroll, More its scroll. If a page that would close has a form with changes or an unsent invite link, the same question its Back asks shows first; "Keep editing" (or "Stay") leaves everything as it was.
  2. at the root: scroll to the top. Today goes to the top of the list (feed strip and the Today/Upcoming toggle visible) and keeps the chosen view; Plants scrolls the grid to the top and keeps the query; Journal scrolls to the first card; More to its header.
- W6-P7. The bar highlights the tab whose stack holds the shown page. A page opened from Today (plant, Find a plant, Add plant, Import from Planta, Weather and feed) keeps Today highlighted; a plant opened from the Journal keeps Journal; one opened from Planta keeps More.
- W6-P8. The Journal stays the third tab: Today, Plants, Journal, More; sitters keep Today, Plants, More (W6-Q1). Reasons: Material's navigation bar takes three to five destinations; the Journal is a household-wide photo view with its own "+", and W4 scored it as a tab (Sol S33 10, Opus S21 9.5). Planta's bar has three icons (Home, My plants, a plant doctor, from `user/planta-today.png`); I don't know where Planta keeps its journal, and it has no More tab in that bar either, so it is no direct model here.

### Back

- W6-P9. Back at a tab root. Plants, Journal and More roots go to Today, which shows as it was left (view, scroll, any page still open over it). Today's root leaves the app: Android moves the task to the background and, with W6-P11, shows the back-to-home preview. This follows Android's principles of navigation (a fixed start destination, the last screen before the launcher). Fixes K11 for More.
- W6-P10. One Back style. Every page above a tab root shows the top bar arrow with content description "Back"; tab roots show none. On every page the arrow, the system Back button and the Back gesture do the same thing. Pages that ask before leaving ask the same question for all three. No text button navigates back a page; dialog buttons and "Back to results" stay (they are not page navigation).
- W6-P11. Predictive back. The manifest's `<application>` gets `android:enableOnBackInvokedCallback="true"`. At Today's root, where no callback is enabled, the system shows its back-to-home preview during the gesture. Inside the app, Back keeps today's behaviour: the page changes when the gesture is released, with no custom in-app animation (W6-A1). Compose's `BackHandler` keeps working with the flag (activity 1.6 and later route it through `OnBackInvokedCallback`). Nothing in the app overrides `onBackPressed` or handles `KEYCODE_BACK`.
- W6-P12. Back targets, complete list:

  | From | Opened by | Back goes to |
  |---|---|---|
  | Plant page | Today or Upcoming row | Today or Upcoming, same scroll, the row in place |
  | Plant page | feed card plant link | Today, same scroll, the feed strip at the same card |
  | Weather and feed | Today's location card | Today |
  | Weather and feed | More row | More, same place |
  | Find a plant | Today's search icon | Today |
  | Plant page | Find a plant | Find a plant, with its query, results and scroll |
  | Add plant (search, Identify, form) | Today's "+" | the previous Add step, then Today |
  | New plant page after Save | Add plant from Today | Today; from the Plants "+", the grid (as now) |
  | Import from Planta | empty Today | Today |
  | Import from Planta | More row | More, same place |
  | Plant page | Planta "Open imported plant" | the Planta review, same card |
  | Sites list | Planta "Check sites" | the Planta review, same place |
  | Plant page | Journal card's plant name | the Journal, same card |
  | "Choose a plant", photo form | Journal "+" | as now (form to picker to Journal) |
  | Plant page | Plants grid, Graveyard, a site page | as now (grid, Graveyard or site page, same place) |
  | Any page inside a plant page (photos, forms, Species names, Care defaults, site pages, Add a task) | the plant page | as now (W3 C27) |
  | More pages and their inner pages | More | as now, ending at More, same place |
  | Today after a notification tap | notification | Today's root is shown; Back leaves the app |
  | Plants, Journal, More roots | tab | Today |
  | Today root | | leaves the app |

### Cross-tab links

- W6-P13. Find a plant. Today's search icon opens, over Today, a page with the Back arrow, the title "Find a plant", the status line, and the Plants grid's search field focused with the keyboard up (W3 P2). It is the Plants grid in the Journal picker's form (`chooseTitle`, `A/plants/PlantViews.kt:94-97`): no "+", no overflow, site headings as plain text. Its query is its own, separate from the Plants tab's search (W6-A10). A plant opened from it comes back to it with the query, results and scroll. The next time it opens from Today it starts empty, because Back removed it. Sitters get the same page over their shared plants.
- W6-P14. Add plant from Today. Today's "+" and the empty Today's "Add plant" open the W4 add flow over Today. After Save the new plant page shows with the W4 receipt; Back from it goes to Today. The Plants tab's "+" keeps its W4 behaviour (Back from the new plant goes to the grid). This is W4 D17's O2 ("go back to where Add started"), which needed W6.
- W6-P15. Planta's links (W5b-P24, K8):
  1. "Open imported plant" opens that plant over the review (More stays highlighted). Back returns to the review at the same card.
  2. "Check sites" opens the Sites list over the review. Back returns to the review.
  3. "View imported plants" closes Import from Planta (its route leaves the stack it is in, More's or Today's) and shows the Plants tab at its grid: the Plants stack goes back to the grid, its search is cleared and it scrolls to the top, so the imported plants show. If that would close a Plants page with a form that has changes, the form's question shows first; "Keep editing" stays on the review and changes nothing.
- W6-P16. Notification taps (`OPEN_HOME`): select Today, close every page over Today's root, show the Today view (not Upcoming), as now. Other tabs keep their pages and drafts and are not asked about. If a page over Today has a form with changes, its question shows first; "Keep editing" leaves the user on that form in Today.

### Drafts, cleanup and writes

- W6-P17. Member and role rules, unchanged in effect, now on stacks: log out or a different member clears every stack and removes every saved page, and Today opens at the top (as `A/AppShell.kt:106-117`). Becoming a sitter removes the Journal stack, and any `add`, `sites`, `weather`, `planta`, `members`, `custom-tasks`, `unmatched` and `species` route from every stack; `plant` routes stay, as the feed plant does now (`:110-117`). A member who is no longer the owner loses `planta` and `members` routes (`:118`). After such a removal, a stack whose top went shows the page under it.
- W6-P18. When questions ask. A form with changes asks, as W3 C19 and W5b-P29 define, only when its page is about to close: its Back (arrow, button, gesture), Cancel, a reselect that closes it (W6-P6), a notification tap that closes it (W6-P16), "View imported plants" (W6-P15). A tab tap, a link that opens a page over it, rotation and process death never ask. The W5b link page asks on Back, Done and a reselect of More; a tab tap keeps the page and its link.
- W6-P19. Cleanup when a page closes, not when it is hidden (K13). PlantsScreen deletes `captures/identify` and the kept photo (`A/plants/PlantsScreen.kt:95-99`), and PhotoSources deletes camera captures and releases gallery grants (`A/photos/PhotoSources.kt:80-86`), only when their page has left its stack. The shell gives each page a check that answers "has this page left its stack?" (a CompositionLocal set per page); outside the shell, as in tests that render one screen, it answers yes, so today's behaviour holds there. A page closed inside a screen already clears its photos through Discard (`A/photos/PhotoRoute.kt:86`, `:99`), as now. Log out also deletes `noBackupFilesDir/photo-drafts` (K14), next to the existing `clearCameraCaptures` call (`A/FytoriumApplication.kt:40`).
- W6-P20. The kept identify photo (K9, W4 follow-up). On a successful Save of a new plant with "Keep this photo" on, the add flow hands the photo to an upload that runs in the Repository's own scope (`background`, `A/Repository.kt:70`), not in the screen's `rememberCoroutineScope`. The screen forgets the path at once, so its cleanup can never touch it. The upload owns the file: it uploads it as the cover, reloads the plant list and the open plant, and deletes the file when it ends, uploaded or not. A failure shows the W4 receipt "Added Monty to Kitchen. Its photo didn't upload." A tab tap, Back, reselect or leaving Plants right after Save can neither cancel the upload nor delete the file.
- W6-P21. Writes finish where they started. A tab tap, reselect, notification tap or "View imported plants" made while the shown page has a save, delete or upload in flight takes effect when that request ends, whether it succeeds or fails; the page's progress line already shows the wait. Until then the bar keeps the current tab highlighted; a second tap during the wait replaces the first. A failed save keeps its draft on the page, which the user finds when they come back; on More and Sites pages its message is not shown again until the next save (W6-A12). Pages report this to the shell next to their discard question, from the `saving` flags their repositories already have (`PlantsRequestState.saving`, `HouseholdState.saving`, `PlantaState.saving`, `NotificationState.saving`, `PhotoState.saving`, `SpeciesCareState.saving`, the feed's `saving`, PlantsScreen's `saving`). Without this, a page hidden mid-request would come back with its pre-save draft and invite a second save: a duplicate plant, photo, task or invite. The wait is at most the API call timeout, 20 s (`A/ApiClient.kt:30`; W6-A6). The kept-photo upload (W6-P20) does not count: it runs after the save and needs no page.
- W6-P22. Lists reload when shown again. A page that comes back into view (a tab tap, Back to it) reloads what it shows, as PlantsScreen and the More pages already do on entry and on resume (C18). Under W2 A1 a return is a return, so new rows may show then; nothing moves while the page stays in view. This closes K4 live. Today's feed polls only while Today's root is shown, as now (`A/AppShell.kt:37-38`, `:121-125`); the check reads Today's stack instead of `feedSettings`.
- W6-P23. Receipts and the bar stay as W2 built them: receipts over the bar, the 500 ms pause after one. A receipt that a write raises while its page is hidden shows on the next page that draws receipts (W6-A7).
- W6-P24. Accessibility. Bar items keep their tab role and selected state, and the selected state follows W6-P7. Every arrow reads "Back". "Find a plant" is a heading. Tap targets stay at least 48 dp.
- W6-P25. Wording. The only new text is the title "Find a plant". Questions reuse their current titles and bodies.
- W6-P26 (added in the build). Log out with a form left open in a hidden tab. A tab tap records the question of the form it hides. After "Log out of Fytorium?" is confirmed, that form's own question shows ("Discard changes?" with its body, or the link page's "Leave without sending the link?"); Keep editing shows that tab, Discard logs out. Without it a tab tap would let Log out drop a draft silently.
- W6-P17 amendment (build). Becoming a sitter keeps the More pages a sitter may open (Notifications, About) and closes every other page, the plant pages opened as owner or family included, as the flags did before. Every page state of the member or role before is removed, so switching back starts fresh.
- W6-P19 amendment (build). The Plants tab's own screen deletes `captures/identify` only when its own add flow ends, never on first showing its grid, so an add flow open over Today keeps its Identify photos.

## 4. Server and API changes

None.

## 5. Tests

Each test fails on 1958ea0 and passes after W6. Robolectric with `createComposeRule` and `StateRestorationTester`, as `T/TodayNavigationTest.kt` and `T/AppShellRestorationTest.kt` do; MockWebServer and `T/HeldServer.kt` where a request must be held. "Tab" means `isTab(...)` from the existing helpers. Each line ends with what fails on the old code.

- W6-T1 (U8, U16, K3). Plants: type "fern", scroll the grid, open the plant; tap Today, then Plants: the plant page shows; Back: the grid with "fern" and the same first visible tile. Old: the grid opens fresh with an empty query.
- W6-T2 (U8, F12, Opus F6). Built with the Sites form in Plants, the same draft mechanism. Plants, a plant, Edit: type a nickname; tap More: no dialog; `emulateSavedInstanceStateRestore()`; tap Plants: the form with the nickname; Back asks "Discard changes?". Old: the tab tap asks and the draft is dropped after Discard.
- W6-T3 (C1, K2, C14). Extend `navigate()` in `T/TodayNavigationTest.kt`: while the plant page opened from a Today row shows, the Today tab is selected and Plants is not. Old: Plants is selected.
- W6-T4 (U17, K1). Open a plant from a feed card (`feed-plant-one-no_drainage`, as `T/AppShellRestorationTest.kt:309`); Back: Today with the same list scroll and the feed strip at the same card. Old: the Plants grid.
- W6-T5 (U17, K5). Journal: scroll to the third card, tap its plant name: the plant page with Journal selected; Back: the Journal with the same first visible card. Old: Plants selected, Back to the grid.
- W6-T6 (U16, F10). Today search icon: "Find a plant" with a Back arrow, Today selected, the field focused; type a query, open a result, Back: the query and results kept; Back: Today root, same scroll. Old: the Plants tab opens.
- W6-T7 (K6). Today "+": Add plant with Today selected; pick a species, Save (MockWebServer): the new plant page; Back: Today. Old: Plants selected; Back to the grid.
- W6-T8 (C11). Owner with no plants: "Import from Planta" on Today opens Import from Planta with Today selected; Back: Today. Old: More is selected and Back shows the More list.
- W6-T9 (K8, F28, Opus F12). After a committed import (as `T/planta/PlantaScreenTest.kt` fixtures): "Open imported plant" shows the plant with More selected, Back returns to the review; "Check sites" shows Sites with More selected, Back returns to the review; "View imported plants" shows the Plants grid at the top with an empty search, and tapping More shows the More list. Also with an Edit draft open in Plants: "View imported plants" asks; "Keep editing" stays on the review. Old: the first two switch to Plants and Back lands on the grid.
- W6-T10 (K7, K1). Reselect: (a) Plants with "fern", open a plant, tap Plants: the grid with "fern"; (b) at the grid, scrolled, tap Plants: scrolled to the top, "fern" kept; (c) Today scrolled, tap Today: scroll 0, view kept; (d) Edit draft, tap Plants: "Discard changes?"; Keep editing keeps the form; Discard shows the grid with "fern"; (e) More, Notifications, tap More: the More list at the same place. Old: (a) stays on the plant page, (b) and (c) do not scroll, (d) recreates PlantsScreen with an empty query.
- W6-T11 (K11, W6-P9). At the More root, system Back (the dispatcher, as `T/TodayNavigationTest.kt:178`): Today is selected and shows as it was left. Same for the Journal and Plants roots. At the Today root the dispatcher has no enabled callback. Old: More has no handler.
- W6-T12 (W6-P16). (a) A Journal photo draft, then a notification (`homeRequest++`): Today shows, no question; tap Journal: the draft is there. (b) A draft on a photo form reached from a Today feed plant: a notification asks; Keep editing keeps it; again and Discard: Today root. Old: (a) asks.
- W6-T13 (W6-P4, K15). Built as two tests: every tab's page survives a restore, and the saved map of a shell with a page in every tab, measured as a Parcel through its own SaveableStateRegistry, stays under 200 KB. Open a plant from Today; in Plants search "fern" and open a plant; in Journal open the photo form for a plant and type a note; in More open Members, then a member page with a change; `emulateSavedInstanceStateRestore()`: each tab shows its page, the note and the change are kept, and Back walks each stack as W6-P12 says. Then call the activity's `onSaveInstanceState` and measure the Bundle with `Parcel.dataSize()`: under 200 KB, size printed. Old: only the current tab's page survives.
- W6-T14 (K9, K14, W6-P20). Built on `Repository.uploadKeptPhoto`, which the add flow calls and which returns its job: with no page alive it posts the photo and deletes the file, and on a failed upload it gives the receipt and deletes the file. Add plant through Identify (the `identify` route in `T/AppShellRestorationTest.kt:242`) with "Keep this photo" on; hold the photo upload with `HeldServer`; Save, then tap Today at once; release the upload: the server records the photo POST for the new plant, and `photo-drafts` is empty afterwards. With a failing upload: the receipt "... Its photo didn't upload." and the file deleted. Log out with a kept photo in a draft: `photo-drafts` is empty. Old: the file is deleted and the upload cancelled.
- W6-T15 (K13, W6-P19). A plant's photo form with a camera capture (a file in `captures/` selected through the test launcher): tap Today, then Plants: the file still exists and the form shows it. Old: deleted on dispose.
- W6-T16 (W6-P21). Built with a held `POST /api/sites` from the Sites form in Plants. Hold `PUT /api/plants/<id>` with `HeldServer`; Edit, change the nickname, Save, tap Journal: the form stays with its progress line; release: the plant page shows the saved name, then the Journal shows; tap Plants: the plant page, no draft, no question on Back. Same with a held `POST` of a new plant: one POST in total, the new plant page in Plants. Old: the tab tap asks "Discard changes?" during the save, and Discard switches at once, so the page's post-save steps never run.
- W6-T17 (W6-P5, W5b-P11). Members, create an invite: the link page; tap Today: no question; tap More: the link page, and Copy puts the link on the clipboard. Old: the question (`T/household/MembersScreenTest.kt:133`, `:203`).
- W6-T18 (K12). Manifest test beside `T/NetworkConfigTest.kt`: `<application>` has `android:enableOnBackInvokedCallback="true"`. Old: missing.
- W6-T19 (W6-P17). An owner with `plant` over Today, `planta` over Today and `members` in More becomes family: Today shows the plant page, `planta` and `members` are gone, More shows its list; becomes a sitter: Journal gone, the plant page stays; a different member: every tab at its root, Today at the top. Old: no `planta` over Today exists to drop.
- W6-T20 (W6-P10). For each shell route (`plant` from each opener, `find`, `add`, `sites`, `planta`, `weather`, `notifications`, `members`, `custom-tasks`, `unmatched`, `species`, `about`): open it; the arrow "Back" exists; system Back lands on the opener; open again; the arrow lands on the same page. Old: `find`, `add`, Planta's links and the Journal and feed plant links land elsewhere.

Existing tests whose expectations change (rewrite, do not delete): `T/AppShellRestorationTest.kt:71-76` (a tab tap away from a draft no longer asks; the site draft in Plants no longer asks on a notification; the feed plant's photo draft still asks on a notification, as W6-T12 b); `T/PlantPageTest.kt` `aNotificationTapFromAFormWithChangesAsksFirst` and `aNotificationTapFromTheOverrideDialogOnThePlantPageAsksFirst` (the form is in the Plants tab, so no question); the notification case in `T/species/SpeciesCareScreenTest.kt` near `:665` (More, no question); `T/household/MembersScreenTest.kt` `aTabTapForgetsTheInviteLink` and `theShellsLinkQuestionDoesNotOutliveTheLink` (become W6-T17; the reselect keeps the question). The implementer greps `homeRequest` and `isTab(` under `T/` for any other. Screenshot tests that show the bar keep their images unless the selected tab changes.

## 6. Lab setup and lab checks

### Setup the implementer provides
Synthetic data only; never touch namespace `fytorium`. No new fixture: W6 reuses the seed household, `lab-w5b` (`planta add|reset`, `members invites|clean`, `twin add|remove`), plantnet-lab for Identify (runbook section 9) and `lab-min-version`. Document in /home/t3/fytorium-ux/lab-runbook.md, new section 15:
- a notification tap without FCM: `emu sh am start -a com.epaflix.fytorium.OPEN_HOME -n com.epaflix.fytorium/.MainActivity` (MainActivity is exported, `AndroidManifest.xml:27`; debug builds have no FCM, W5a-A7);
- gesture navigation on and off for W6-L14: `emu sh cmd overlay enable com.android.internal.systemui.navbar.gestural` and `... enable com.android.internal.systemui.navbar.threebutton` (my guess at the overlay names on the API 35 image; the implementer confirms with `cmd overlay list` and records the default);
- a request that hangs, for W6-L16: `kubectl -n fytorium-uxlab exec lab -- docker pause fytorium-app-1`, then `docker unpause` the same way within 10 s (a guess that a paused container accepts the connection and never answers; the implementer confirms; re-set the clock if anything restarts);
- reading the kept-photo folder: `emu sh run-as com.epaflix.fytorium ls no_backup/photo-drafts` (debug build).

### Checks
Run as the W5b brief describes (`emu ui`, `emu tap`, `emu text`, `emu key 4`, `emu key 66`, `emu shot`, test clock 2026-11-15, read-only SQL, rotation, dark mode, font scale 1.3, Wi-Fi and data), portrait at 1.0x unless a check says otherwise. "Kill" means Home, `emu sh am kill com.epaflix.fytorium`, reopen. "Selected" is the bar item's `selected` in `emu ui`. Each check passes only if every listed result holds. Leave the lab as the W5b brief says, plus three-button navigation restored, the container unpaused, and no plant, photo, task or invite of your own left.

- W6-L1 (tab trip). Plants: search "fern", scroll, open Fernando, unfold Details. Tap Today, Journal, More, Plants: Fernando's page with Details unfolded at the same place; Back: the grid with "fern" and the same first tile bounds. Repeat with a kill while on More.
- W6-L2 (drafts across tabs). Edit Monty: type a nickname. Tap Journal, More: no question. Kill. Reopen, tap Plants: the form with the nickname. Back: "Discard changes?"; Keep editing; Cancel the edit by Back and Discard.
- W6-L3 (Today row). Scroll Today, open a row's plant: Today selected; Back arrow returns to Today with the same first row bounds; again with `emu key 4`; again in Upcoming. Tap Plants while the plant is open: the Plants grid; tap Today: the plant page again.
- W6-L4 (feed link). A plant warning card on Today opens its plant with Today selected; Back: Today, the feed strip on the same card.
- W6-L5 (Journal link). Journal, scroll to the third card, tap its plant name: Journal selected; Back: the same card at the same bounds.
- W6-L6 (Find a plant). Today's search icon: "Find a plant", Back arrow, keyboard up, Today selected. Type "fern", `emu key 66`: no character added. Open Fernando, Back: "fern" and the results; Back: Today. Open it again: empty field. At 1.3x and in landscape the title fits on one line.
- W6-L7 (Add from Today). Today "+": Today selected through search, form and the new plant page; Save a plant "W6 lab fern" (any species, Bathroom); the receipt; Back: Today. Plants "+": after Save, Back goes to the grid. Delete both plants (typed-name delete).
- W6-L8 (kept photo, K9). Today "+", Identify with a plantnet-lab photo, choose a species, "Keep this photo" on, then in one shell call tap Save and Journal (`emu sh "input tap X1 Y1; input tap X2 Y2"`, coordinates from `emu ui`). SQL: the new plant has a photo update and a cover; `photo-drafts` is empty. Delete the plant.
- W6-L9 (Planta, K8). `lab-w5b planta add`, `planta reset`; fetch, decide, import. "Open imported plant": More selected, Back to the same card. "Check sites": Sites, More selected, Back to the review. "View imported plants": the Plants grid at the top, empty search; More shows its list. End with `lab-w5b planta reset`.
- W6-L10 (reselect). As W6-T10 (a) to (e) live, plus: Today scrolled in Upcoming, tap Today: top of Upcoming. Members: create a Family invite; on its link page tap More: "Leave without sending the link?"; Stay keeps it; Leave shows the More list. Revoke that invite under Pending invites.
- W6-L11 (root Back). `emu key 4` at the More, Journal and Plants roots: Today, as left. At the Today root: the launcher (`emu sh dumpsys activity activities | grep -m1 ResumedActivity` names the launcher). Reopen: Today as left.
- W6-L12 (one Back style). On every shell page (W6-T20 list) and every More inner page: an arrow with content description "Back"; arrow and `emu key 4` land on the same page (`emu ui` title). No page above a root lacks the arrow.
- W6-L13 (notification). A Journal photo form with a note; send `OPEN_HOME`: Today root, no question; Journal: the note. From a Today row's plant, Edit with a change; send `OPEN_HOME`: "Discard changes?"; Keep editing; send again, Discard: Today root, Today view.
- W6-L14 (predictive back). Gesture navigation on. At the Today root, a slow edge swipe (`emu swipe 2 1000 500 1000 1500`) with `emu shot` taken during it shows the home preview; release goes home. On a plant page opened from Today, the same swipe returns to Today on release, nothing else. Back to three-button navigation.
- W6-L15 (process death). Today: a row's plant. Plants: Fernando from "fern". Journal: a photo form with a note. More: Members, a member page with a change. Kill. Each tab shows its page with its state; Back walks each stack as W6-P12; the bar highlight follows W6-P7.
- W6-L16 (writes in flight). Edit Monty, change the nickname, pause the app container (section 15), tap Save, tap Journal: the form stays with progress; unpause within 10 s: Monty's page with the new name, then the Journal. Plants: Monty's page, no draft. Change the name back.
- W6-L17 (members). Log out, log in as `synthetic-ux-family`: every tab at its root; no Members or Planta page restored. Log in as `synthetic-ux-sitter`: Today, Plants, More; a row opens the plant over Today; Find a plant lists only Spider Plant and Thyme. Back to `synthetic-ux-owner`.
- W6-L18 (cross-device, K4). Open Plants, then Today. `lab-w5b twin add`. Tap Plants: the second Spider Plant (Office) shows without a pull. `twin remove`.
- W6-L19 (look). Rotation, dark mode, 1.3x and landscape on Find a plant, a pushed plant page and a reselect: the bar highlight, arrows and titles as above; nothing on a page moves when a tab is tapped and tapped back (`emu ui` bounds).
- W6-L20 (regression). W3: graveyard Back (S16), plant page place keeping (C27, C34); W4: Add plant with Create a site, Identify, Journal "+"; W5a: T1 and T8 of the W5a spec; W5b: T1, T6, T11, T21 of the W5b spec apart from the W6 changes above; Today and Upcoming keep separate scroll (U15).

### Item to check map

| Item | Tests | Lab |
|---|---|---|
| Sol Navigation, Opus Navigation | all | all |
| Sol U8, F12; Opus F6 | T2, T13, T16 | L2, L15, L16 |
| Sol U15 (regression) | existing `T/TodayNavigationTest.kt` | L3, L20 |
| Sol U16, F10 | T1, T6, T10 | L1, L6 |
| Sol U17 | T3, T4, T5, T9 | L3, L4, L5, L9 |
| Opus U8 | T3, T5, T6, T7, T11, T20 | L3, L5, L6, L7, L11, L12 |
| Opus U12, F11 (regression) | T20 | L12 |
| Sol F28, Opus F12 | T9 | L9 |
| Audit F10 | T1 to T11, T20 | L1 to L12 |
| K1 | T4, T10 | L4, L10 |
| K2 | T3 | L3 |
| K3 | T1 | L1 |
| K4 | | L18 |
| K5 | T5 | L5 |
| K6 | T7 | L7 |
| K7 | T10, T17 | L10 |
| K8 | T9 | L9 |
| K9 | T14 | L8 |
| K10 | | L17 |
| K11 | T11 | L11 |
| K12 | T18 | L14 |
| K13 | T15 | L2 |
| K14 | T14 | |
| K15 | T13 | L15 |

## 7. Accepted limits

- W6-A1. Inside the app, Back changes the page when the gesture is released; there is no custom predictive animation. The system's back-to-home preview shows at Today's root.
- W6-A2. Hidden pages do not reload while hidden; they reload when shown again (W6-P22), so a page can show minutes-old data for a moment, with its status line rules.
- W6-A3. The same plant can be open in two tabs. A save in one shows in the other when that page next reads.
- W6-A4. Every notification opens Today; there are no deep links to a plant or task.
- W6-A5. A kept identify photo whose draft is lost with its task (the app swiped away before Save) stays in app storage until log out. Camera captures of a dropped draft stay until the existing startup sweep or log out (the shortcut at `A/photos/PhotoImages.kt:31`).
- W6-A6. A tab tap during a request that hangs waits up to 20 s for it to end (W6-P21).
- W6-A7. A receipt raised while More is shown appears when Today, Plants or the Journal is next shown, since More does not draw receipts.
- W6-A8. Back at Today's root leaves the app even with Upcoming selected (W6-Q3).
- W6-A9. Sitters keep the Plants tab and Today's search; both show only their shared plants (K10).
- W6-A10. Find a plant and the Plants tab keep separate queries.
- W6-A11. Tabs survive process death only while Android keeps the task's saved state; after the app is swiped away it opens at Today's root.
- W6-A12. A save that fails while a tab tap waits for it keeps its draft; More and Sites pages build their state again when shown, so its message shows again only on the next save (Sol W6-S13). A camera capture in a hidden draft whose page a role change removes stays until the startup sweep (Sol W6-S12, as W6-A5). Two add flows open at once, one over Today and one in Plants, share Identify's camera folder.

## 8. Implementation order

Each slice builds, passes unit, Robolectric screenshot and accessibility tests, and can be reviewed alone. Screenshots are updated, not deleted.

1. Tests first: W6-T1 to T20 written and failing on 1958ea0; the existing tests in section 5 rewritten to the new expectations.
2. Stacks in AppShell: the per-tab route lists, the state holder keyed by member id, the shell routes of W6-P2 replacing the 15 flags and `select()`'s reset, W6-P5 to P9, P12, P16, P17 (fixes K15).
3. PlantsScreen as a pushed page: W6-P3 (`returnToToday` goes), Find a plant (W6-P13), Add from Today (W6-P14), Planta's links (W6-P15).
4. Cleanup and writes: W6-P19 to P21 (the page check, the kept-photo upload in the Repository scope, the log-out cleanup, the busy report and the deferred switch).
5. Predictive back opt-in and the Back-style sweep (W6-P10, P11); android/README.md navigation paragraph updated.
6. Lab section 15 and a full run of W6-L1 to L20 by the implementer before the council round.

## 9. Open questions (each with my recommendation)

Decided 2026-11-23: each recommendation stands (owner instruction of 2026-11-20: proceed without asking; the goal is 9.5 and above).

- W6-Q1. Where does the Journal go? Options: keep it as the third tab; move it into Plants (a top bar action) and keep three tabs plus More. Recommendation: keep it as the third tab (W6-P8). Moving it would reopen two items W4 passed, and Planta's bar is no model for a More tab either.
- W6-Q2. What does Today's search icon open? Options: "Find a plant" over Today (W6-P13); switch to the Plants tab with its search focused, as now. Recommendation: over Today. Switching would have to close whatever the Plants tab keeps open (W6-P4) or land on it; over Today, Back returns to Today and the Plants tab stays as the user left it.
- W6-Q3. Back at Today's root with Upcoming selected: leave the app, or switch to the Today view first? Recommendation: leave the app (W6-A8). The toggle is a view switch on one page, not a page, and one rule for the root is easier to predict.
- W6-Q4. Opt in to predictive back now (targetSdk 35) or wait for targetSdk 36, which turns it on anyway? Recommendation: now (W6-P11). It is one manifest line, and W6-T18 and W6-L14 check it while the navigation code is fresh.
- W6-Q5. Reselecting Plants from a page above the grid: keep the grid's search, or clear it? Recommendation: keep it (W6-P6). Clearing is one more tap away (the field's clear button), and losing a query is what U16 complained about.
- W6-Q6. "View imported plants": close Import from Planta and clear the Plants search? Recommendation: yes to both (W6-P15). The import is finished, and a leftover query could hide the plants the button promises.

## 10. Not verified

- Anything live. I did not touch the lab or the emulator. C18 (reload on entry), K15 (Journal draft lost after process death) and the dispose cleanups in K13 are from code.
- Planta's cross-tab behaviour, and where Planta keeps its journal. I know its bar only from `user/planta-today.png`.
- The emulator's navigation mode and overlay names for W6-L14, and how `emu shot` captures a gesture in progress.
- That a paused container makes requests hang as W6-L16 needs.
- The saved-state size today and after W6. The 50 KB figure is Android's guidance as I remember it from the developer documentation on saving UI state; 200 KB is my budget. Neither is measured.
- That `rememberSaveableStateHolder` restores every page's state after process death in this app's setup; the Compose documentation says it does, and W6-T13 checks it.
- Whether the Material 3 1.3.2 task view sheet and dialogs behave well with `enableOnBackInvokedCallback` on (they should take Back first, as now; W6-L14 and W6-T20 cover it).
