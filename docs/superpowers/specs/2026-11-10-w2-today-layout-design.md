# W2: Today and Upcoming layout

Wave W2 of the Android UI/UX programme (W1: trust fixes, PR #41). Goal: every Today/Upcoming item in both council audits reaches 10/10 for both reviewers on a live lab build. Built on top of W1.

Items in scope:
- Sol: S3 Today pending + feed, S5 empty day, S6 no plants / no shares, S7 Upcoming, S8 task view sheet, S9 feed cards, S10 rain card, S11 offline banner, F8 plan the next days, F9 filter/sort/show others, F27 rain watering, F29 sitter Today, F30 offline Today.
- Opus: S3 Today (layout), S4 Upcoming, S5 task view sheet, S6 home feed strip, S31 sitter Today/Upcoming, F2 daily care (layout), F14 offline Today.
- Carried over from W1 council notes: snackbar covers rows and the last row sits under the + button; late-tasks card repeats the list and goes stale after a plant-history Undo; empty band above the Today/Upcoming switch; refresh spinner flashes during fast taps.

Out of scope: plant page (W3), add/identify/search coverage (W4), More/settings screens (W5), cross-tab navigation and the search icon's tab jump (W6). Server changes only where a fix needs them.

## Why

Today is the screen Spyros opens every day. After W1 its behaviour is trustworthy, but its frame is not: floating buttons cover the rows you tap, the feed pushes tasks below the fold with an empty band and a card that repeats the list, filters are invisible once set, and the bell opens settings. Planta's Today (the reference screenshot) shows tasks first, one add button, and nothing covering a row.

## Design (proposed)

### Top of Today
- D1. Top bar: search icon (left), title "Today" (centre), filter icon and "+" Add plant icon (right). The notification bell leaves Today; notification settings stay under More.
- D2. Below it, the Today/Upcoming pill toggle in Planta's style: filled pill for the selected view, no check icon.
- D3. When a filter or sort other than the default is active, a small chip row under the toggle states it ("Indoor", "Sorted by plant", "Showing others' completed") with one "Reset" action.
- D4. No empty band: the toggle sits directly under the top bar or the feed strip, whatever the window insets are.

### Feed strip
- D5. Feed cards are compact (about 96 dp tall), horizontally scrollable, above the toggle, and never taller than one card height.
- D6. The late-tasks card is gone: the list below already shows late tasks with their late pills. Weather, cold alerts, rain and plant warnings stay.
- D7. Card copy is plain: "Too dark for Snake plant", "Missing pot size", "Frost tonight: bring Thyme inside". Rule IDs, input names and sources move behind a "Why?" link on the card.
- D8. The feed refreshes after every care write and Undo (including plant-page history), so no card is stale.
- D9. Rain card: "Rain today. Mark 3 outdoor plants watered" with its counted Undo receipt (from W1), placed first in the strip.

### Task list
- D10. No floating button. "Add plant" is a "+" icon in the top bar, right of the filter icon (owner and family; offline it looks disabled and explains on tap, as D20). Decided by Spyros after the first build: a floating button can cover a row's controls mid-list.
- D11. The list has normal bottom padding, and grows by the snackbar's height while a snackbar shows, so the snackbar never covers a row.
- D12. The receipt snackbar sits at the bottom of the screen above the navigation bar, full width minus margins.
- D13. A completed row never moves within its card after an action (W1 sorts it at its completed due date); the council verifies this live.
- D14. The pull-to-refresh spinner shows only for a pull gesture. Background refreshes (app resume, network change, after writes) show nothing.
- D15. Today and Upcoming keep separate scroll positions.

### Task view sheet
- D16. Choices apply as soon as you pick them, with a short "Saved" confirmation in the sheet; no separate Save button. Failures show inline and keep the previous choice.

### Empty and special states
- D17. Empty day: "Nothing to do today" only when no filter hides tasks; with a filter: "No indoor tasks today", plus "Show all". The next upcoming task line stays.
- D18. No plants: "Add your first plant" and, for owners, "Import from Planta". (Creating a site inside the add flow is W4.)
- D19. Sitter with no shared plants: "Nothing is shared with you yet. Ask the household owner to share plants."
- D20. Offline: one banner under the toggle ("Offline. Showing tasks from 08:12. Changes need a connection."). The "+" button looks disabled and explains on tap.

### Upcoming
- D21. Section headers read "Tomorrow", "Thursday 19 Nov", never ISO dates. The "Next 14 calendar days, including today" caption goes.

## Council round 1 amendments (live, 2026-11-10)

From `council-w2-opus.md` (W2-opus-1..9) and `council-w2-sol.md` (W2-sol-1..3).

- A1 (opus-1, S1). Nothing moves under the user's finger. A care action, Undo, a receipt showing or hiding, or a card's last task being completed never shifts any visible row on screen. When a card has no pending task left, its "Handle all tasks" button keeps its place as a disabled "All done" button. When the receipt space appears or disappears, the rows above it keep their screen position (the list compensates its scroll), while D11 still holds: the receipt never covers a row's controls. Test with a burst of taps at the end of a card and the end of the list.
- A2 (opus-2). The feed's error notice clears as soon as a feed refresh succeeds (reconnect, pull, care write), without an app restart.
- A3 (opus-3). Cold start while online never shows "Connect to load your tasks". Until the first load finishes, Today shows the cached list or a neutral loading state, and the feed strip's space is reserved while it loads, so rows do not jump when the strip arrives.
- A4 (opus-4, sol-2). Feed headlines lead with the plant name ("Jade Plant: missing pot size", "Thyme: frost today, bring inside"), so truncation never hides which plant; up to two lines, also at 1.3x text.
- A5 (opus-5). Plant warnings of the same kind form one card ("Missing pot size for 11 plants"); its Why? lists the plants, each opening its plant. A single plant keeps the A4 form.
- A6 (opus-6). Task view sheet: a failure message never offers "Try again" while the controls are disabled; offline, the sheet says "Task view needs a connection." The choices use one consistent control style (segmented buttons or radio rows), not the old "Label: value" pill buttons.
- A7 (opus-7). Why? uses plain labels and readable dates ("Checked 10 Nov 08:12"); no raw field names, JSON or ISO timestamps. Rule and source identifiers stay available as one small "Technical details" line at the end, for traceability.
- A8 (opus-8). Offline, the feed strip shows its cached cards with a one-line "Weather and tips need a connection" notice, or that notice alone if nothing is cached; it never disappears silently. Disabled task controls look disabled and do not announce "Double tap to undo".
- A9 (opus-9, amends D1). The top bar title follows the view: "Today" or "Upcoming".
- A10 (sol-1). Sorting by plant groups Today and Upcoming into one card per plant (heading: plant name, rows: its tasks); sorting by site, one card per site. Task-type sort keeps one card per task type.
- A11 (sol-3). Rain Undo's confirmation ("Undid watering for N outdoor plants") appears in the normal receipt snackbar and goes away like every other confirmation; no persistent text above the feed.

- A12 (R1, decided by Spyros). A task the member snoozed or skipped today stays in its place on Today for the rest of the household day, labelled "Snoozed until Thu 19 Nov" or "Skipped until Thu 26 Nov", with no check and no pending circle, and an Undo control that reverses that snooze or skip. The server lists these rows like the member's own completions (W1 G2: own actions of today always listed, at the due date they acted on; others' only with Show others' completed tasks), so they survive refresh and restart. The postponed occurrence still appears at its new date in Upcoming. Server API, OpenAPI, sitter projection and web Today follow the same listing rule; on the web the row shows the label and an Undo button. On Android the label shares the site line ("Garden · Snoozed until Thu 19 Nov"); the row's Undo is a 48 dp undo-arrow icon button (as in the layout Spyros approved), not a text button; on Today the visible label is a small snooze or skip glyph (the same symbols as the row's snooze button and the Skip action) followed by "until Wed 30 May" (content description "Snoozed until Wednesday 30 May" / "Skipped until …"); if space still runs out the site is dropped whole (never a lone "…" or a leading " · "); the label is never cut for any date at 1.0x and 1.3x text; the row keeps its normal 12 dp end padding, so the Undo icon lines up with the done circles; so snoozing never changes a row's height (A1), also with long names and at 1.3x text. A card's disabled button reads "All done" when at least one of its tasks was completed and none is pending, and "Nothing due today" when its remaining tasks were only snoozed or skipped.

## Gate round 1 amendments to A1-A12 (2026-11-10)

- A1b. A row's trailing controls (snooze slot and done/undo slot) keep the same total width in every state (pending, done, snoozed, skipped, busy, offline), so the name and label column never changes width and no name re-wraps after an action. A12's label must still fit for every date at 1.0x and 1.3x within that column: the site is dropped first, then the weekday ("until 30 May"); the content description always keeps the full date. Under task-type sort, Upcoming also shows each day's heading followed by one card per task type (consistent with Today).
- A10b. In Upcoming, each day keeps its heading (D21) and, under plant or site sort, holds one card per plant or site for that day. Under task-type sort it keeps one card per task type for that day, as today.
- A3b. Android keeps the last loaded feed in its offline cache (per member), so a cold start shows the same strip at once, including "no strip" when the last feed was empty; the loading placeholder shows only when no feed has ever been cached. When a refresh makes the strip appear, disappear or change height, the list keeps its visible rows in place (the same scroll compensation as A1).
- A12b. A snooze or skip stores the date it set on its care event (new nullable column `care_events.postponed_until`, filled by every snooze and skip path; reversible migration). The listing's `postponed_until` reads that stored value; events from before the migration fall back to the current calculation.

## Gate round 2 amendment (2026-11-10)

- A1c (replaces A3b's scroll compensation and A3's loading placeholder). While the user is on Today or Upcoming, only in-place changes apply at once: the acted row's state, labels, counts and receipts, and card or strip text that keeps the same height. Layout changes the user did not just cause at that spot are held: sections, cards or rows that a refresh adds or removes (for example the postponed occurrence appearing at its new date in Upcoming, or a next occurrence entering the window), and the feed strip appearing, disappearing or changing height. Held changes apply together at the next pull to refresh, Today/Upcoming switch, return to Today from another screen, or app resume, so the user sees them at a moment they chose. A cold start uses the cached lists and the cached feed (A3b); with no cached feed yet there is no strip and no placeholder until the next such moment. W1 O4 still holds at those moments: the list then equals what the server last sent. Three exceptions apply at once: a change of task view (the user caused it) and a new household day (yesterday's rows must not linger); and the offline, server-unavailable and feed-error notices, which tell the user that writes are disabled. While a row is held but the server no longer lists it, it stays in place and is disabled, so it cannot record a second action.

## Gate round 4 amendment (2026-11-10)

- A1d (tightens A1c). (1) A held row changes identity only through this device's own write or Undo: the repository records, from each response, which occurrence the acted row became (pending → event for an action, event → pending for an Undo), and the held list follows exactly those transitions until the next apply moment. Nothing is inferred from plant and task; a held row without an exact or recorded match stays in place, disabled. (2) Every row always reserves its subtitle line, even when it is empty (for example a sitter's row without a site), so a postponed label appearing or disappearing never changes a row's height. (3) Plant names, site names and card or section headings from a refresh are held like other layout changes and apply at the next moment; row state (check, pending circle, postponed label, busy, receipts) applies at once.

## Gate round 5 amendment (2026-11-11)

- A1e. (1) Every Undo response (single event, batch, rain) names, for each event it reversed, the occurrence that Undo restored (plant, task and due date of the pending row it brought back, or null if none), computed by the server in the same transaction. Android records exactly those transitions; it never looks a pending row up by plant and task. (2) Every row reserves both trailing slots (snooze and done/undo) in every section of Today and Upcoming, so a restored overdue occurrence never changes a row's width. (3) The empty-collection state (no plants, no shared plants) is held like other layout changes and applies at the next moment. (4) A sitter's listing always contains only their own completions, snoozes and skips, whatever a saved Show others' completed tasks flag says; the setting does not apply to sitters.

## Gate round 6 amendment (2026-11-11)

- A1f. (1) Forward transitions use the event's own identity: every care event the app receives (owner, family and sitter projections) carries the `due_on` it acted on, and Android records the predecessor of each created event (single, linked water, card and plant batches, rain) as the pending occurrence with that event's plant, task and `due_on`. No pending row is ever looked up by plant and task alone; no batch fallback. (2) An Undo transaction computes `restored` from one consistent snapshot: it runs at REPEATABLE READ, so both schedule reads see the same non-event inputs (intervals, custom tasks, settings), and retries once on a serialization failure.

## Council round 2 amendments (live, 2026-11-11)

From `council-w2-opus-r2.md` (r2-1..r2-8) and `council-w2-sol-r2.md` (W2-sol-4).

- A13a (r2-1; decided by Spyros). The receipt snackbar is drawn over the bottom navigation bar, in its place, for its duration (swipe dismisses it early). The list's viewport never changes size for a receipt, so no row is hidden or moved; D11/D12 are amended accordingly and the receipt-space reservation is removed.
- A13b (r2-2). Under plant sort a card's heading is the plant name; its rows show the task (and site) without repeating the plant name; its disabled button reads "All done" / "Nothing due today" without the plant name (content description keeps it). Under site sort rows keep the plant name and drop the site. A one-task card is no taller than one row plus heading and button.
- A13c (r2-3, W2-sol-4). The task view sheet's content, including "Saved" and blocked messages, sits above the navigation bar insets. Disabled segmented buttons keep their selection visible and dim all segments equally. The blocked message follows the live connection state and clears as soon as writes are possible again.
- A13d (r2-4). A grouped warning's Why? says why it matters and how to fix it in one plain sentence ("Pot size sets how often to water. Add it on each plant's page."), and lists each plant with its site so same-named plants are distinguishable. A household-level fact (weather location) is one card ("Set your household location for frost alerts") opening weather settings, never "for N plants".
- A13e (r2-5, r2-6). A "could not reach" or "isn't responding" notice appears only after a request has actually failed and does not toggle during a reconnect (the D20 offline banner still appears as soon as the device loses its network); a cold start with a cached list renders that list in the first frame, never "Loading tasks…".
- A13f (r2-7). When a row's reserved subtitle line is empty, the plant name is vertically centred on the photo; the row height stays as A1d(2) requires.
- A13g (r2-8). Tapping a disabled check or snooze control explains why, like the "+" ("Changes need a connection.", "Changes need Fytorium to respond.", "Update Fytorium to make changes.").

## Gate amendment to A13 (2026-11-11)

- A13h. (a) The receipt, any explanation and its action fit inside the navigation bar's height at 1.0x and 1.3x: text takes at most two lines, ends with "…" if longer, and its full wording is the content description and the TalkBack announcement. (b) For a family member the household-location card reads "Ask the owner to set the household location for frost alerts" and still opens weather settings; the owner's wording stays. (c) A feed error message is cleared when the device goes offline; after reconnecting, a feed error shows only if a feed refresh fails again. (d) Tapping a held row's disabled control explains it: "This task changed elsewhere. Pull to refresh.", with no write.

## Non-goals
Plant page, add flow, More/settings, navigation model, web UI.

## Anti-goals
Anything covering a row's controls; a spinner or disabled state the user did not cause; a card that says the same as the list; a filter the user cannot see.

## Constraints
Kotlin + Compose + Material 3, existing TaskScreen/TaskRows/HomeFeed/Theme; no new dependencies; screenshot tests updated, not deleted; tests run in the lab as in W1.
