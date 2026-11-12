# W3: Plants list and plant page

Wave W3 of the Android UI/UX programme. Goal: every Plants-list and plant-page item in both council audits reaches 10/10 for both reviewers on a live lab build. Built on W2 (PR #43).

Items in scope:
- Sol: S12 Plants list and search, S13 plant detail, S14 detail with selected task, S15 sitter detail, S16 graveyard, S17 plant menu, S21 edit plant, S22 birthday picker, S23 care intervals, S24 repot, S25 restore, S26 delete, S27 change species, S28 species names, S29 species care, S30 override dialog, S31 remove override; F10 find a plant, F11 act from the plant page, F12 edit, F14 fix a name, F15 intervals/overrides, F16 repot, F17 graveyard/restore/delete, F20 history.
- Opus: S7 Plants list, S8 graveyard, S9 plant page, S10 delete, S12 add/edit form (edit part), S13 care settings, S14 repot, S15 restore, S16 change species, S17 species names, S18 species care / unmatched; F6 edit, F7 history, F15 graveyard/restore/delete, F17 intervals.
- Carried from W1/W2 council notes: scientific name leads the plant page; "0d late" on future dates; Skip still offered after Skip; buttons without the task name; history ordered by occurrence date; "No cached details yet" while loading online.

Out of scope: adding a plant and identify (W4), More/settings (W5), cross-tab navigation and Back targets (W6, except Back within the plant page).

Earlier owner decision (programme start): expert content (Based on, GBIF key, rule IDs, Species care JSON, Unmatched species) stays on the phone, folded behind a collapsed "Details" section.

## Proposed design

### Plants list
- P1. A photo grid, 2 columns on phones: square cropped cover (initial on a tinted background when there is none), common/display name under it, site under that; late pill on the photo like Today. Grouped by site with site headings (Planta-style), sites ordered by name.
- P2. Search field at the top, focused when opened from Today's search icon; the query survives opening a plant and coming back.
- P3. Top bar: "Plants", "+" Add plant, overflow with Graveyard. No duplicate heading, no "Refresh plants" button (pull to refresh).
- P4. Empty states: no plants → "Add your first plant"; search with no match → "No plant called “…”".

### Plant page
- P5. Order: large cover photo (full width, 4:3, tap to open photo updates), name (nickname or common name) as title with the species' common/canonical name under it, site; then "Care" with one row per applicable task: icon, task name, "Due today" / "3 days late" / "in 5 days" / "Snoozed until Thu" / "Done today", and the same check/snooze/undo controls and receipts as Today (W1/W2 rules), on every entry route and for sitters. Not-applicable tasks listed once at the end as a single muted line.
- P6. "History": newest first by when it happened (recorded time, not the occurrence date), grouped by day ("Today", "Yesterday", "Mon 9 Nov"), each entry "Watered · Spyros" with Undo where allowed; undone entries muted and labelled "Undone". Species changes appear in the same timeline.
- P7. "Photos": a strip of the latest photo updates with "Add photo" and "See all".
- P8. "About this plant": pot, soil, size, stage, fertilizer, light/heater/AC as readable sentences; fields not set are not listed, with one "Add details" link to Edit.
- P9. "Details" (collapsed): scientific name with author, GBIF key and licence, Based on for each task, care sources, rule IDs. Species care and Species names live under Details too.
- P10. Top bar: Back, title, "Edit" icon, overflow (Change species, Care settings, Repot, Move to graveyard, Delete). Move to graveyard asks for confirmation and gives a receipt with Undo.
- P11. While loading online, the page shows the cached version or a neutral loading state, never "No cached details yet. Connect…".

### Forms and dialogs
- P12. Edit plant: name and site first; the rest under "More details"; numeric fields use the number keyboard; birthday picker blocks future dates; leaving with unsaved changes asks to keep editing or discard.
- P13. Care settings: each task shows its current interval ("Every 7 days, from species care") with an optional override field; one Save at the top of the sheet; the two editors are named "This plant's schedule" and "Care defaults for this species" with one sentence on their scope.
- P14. Species care overrides: no JSON; ranges as two number fields, months as month chips, text as text.
- P15. Repot, Restore, Delete, Change species: keep their working flows; every confirmation names its target; Restore offers creating a site if none fits.

## Decided by Spyros
- Plants list: photo grid grouped by site (P1).
- Plant page: one scrolling page in the P5-P9 order.

## Non-goals
Add plant and identify (W4), More/settings (W5), cross-tab navigation (W6), web UI beyond shared server changes.

## Anti-goals
Anything that moves a row under the user's finger (W2 A1 family applies to the plant page's Care rows); an Undo that names less than it reverses (W1); raw identifiers, JSON or ISO dates outside Details.

## Constraints
Kotlin + Compose + Material 3; reuse TaskRow, receipts and held-layout rules from W1/W2; no new dependencies; screenshot tests updated, not deleted; lab runs as in W2.
