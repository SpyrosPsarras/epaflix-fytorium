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

## Non-goals
Plant page, add flow, More/settings, navigation model, web UI.

## Anti-goals
Anything covering a row's controls; a spinner or disabled state the user did not cause; a card that says the same as the list; a filter the user cannot see.

## Constraints
Kotlin + Compose + Material 3, existing TaskScreen/TaskRows/HomeFeed/Theme; no new dependencies; screenshot tests updated, not deleted; tests run in the lab as in W1.
