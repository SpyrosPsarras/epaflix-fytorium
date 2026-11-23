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

## Council round 1 amendments (2026-11-12)

From the live council round 1 (council-w3-sol.md, council-w3-opus.md). C1-C17 are required for 10/10.

### Plant page
- C1 (W3-sol-1, Opus obs. 4; placement decided by Spyros 2026-11-12). Connection changes never move or cover content. The offline / server-unavailable notice on Today, the Plants and Graveyard grids and the plant page becomes a second line under the top bar title ("Offline · updated 13:29", "Fytorium isn't responding"), inside a top bar whose height does not change; tapping it shows the full explanation. On the plant page the line sits under whatever the bar shows (C3). The feed's "Weather and tips need a connection" line moves into the same subtitle explanation. Disabled Edit and overflow icons explain why on tap, like the rows.
  - C1b (gate, 2026-11-12; scope clarified 2026-11-13). The same subtitle carries every page-level status message that would otherwise appear above or inside scrolling content, on Today, the Plants and Graveyard grids, the plant page and the plant form pages (Edit, Repot, This plant's schedule, Restore, Change species, Species names, Species care): connection and server-unavailable states, plant-request refusals (read or write; the explanation offers Try again), and Today's care and feed messages (e.g. a refused refresh, a rain-watering conflict). Nothing is inserted above or inside the scrolling content when such a state starts or ends. Field-level validation and the result of a save the user just tapped stay inside the form, next to its fields. The full-screen "Update Fytorium" stop for an app the server no longer accepts (W1) is unchanged and is not a subtitle state.
- C2 (W3-sol-4). Entering the page applies its first fresh read once: when the page opens from cache, the first successful plant read after entry sets the held Care, header and history, provided the user has not scrolled or acted on the page since it appeared (the W2 A14 rule). Otherwise the existing hold applies.
- C3 (W3-opus-3). The name shows once at rest: the headline under the cover; the top bar title appears only after the headline scrolls out of view.
- C4 (W3-opus-4). Lateness uses the same late colour and pill style as Today. Due dates more than 14 days away read as a date ("Due 3 Mar 2027"); 14 days or less keep "Due in N days".
- C5 (Opus obs. 5). With nothing due today, Handle all reads "Nothing due today" instead of "All done" unless every task due today is done.
- C6 (W3-opus-5). Skip and snooze receipts and their Undo confirmations name the task, on the plant page and Today: "Skipped cleaning leaves for Spider Plant until Tue 15 Dec", "Undid skip of cleaning leaves for Spider Plant".
- C7 (W3-opus-6). A Based on entry never repeats the same sentence; drop the reason line when it equals the explanation.
- C8 (W3-opus-1). Plants and Graveyard tiles never clip their text: clip only the cover.
- C9 (W3-opus-2). A new or changed search query scrolls the grid to the top.

### Photos
- C10 (W3-sol-2). System Back from an original photo returns to the updates gallery, like the toolbar arrow.
- C11 (W3-sol-3). Photo update cards show readable dates ("15 Nov 2026").

### Forms
- C12 (W3-opus-7). "This plant's schedule" and the Repot form ask before discarding typed changes on Back, with the same dialog as Edit plant.
- C13 (W3-opus-8). Edit plant's Name field shows the current display name as a muted placeholder when no nickname is set, with "Leave empty to keep using the species name" under it. The field's value stays empty: TalkBack reads it as empty with the current name as guidance, and typing starts from an empty field.
- C14 (W3-opus-12). The delete confirmation ignores case and outer spaces, and its hint says what to type ("Type Opus W3 plant to confirm").
- C15 (W3-opus-13). A graveyard plant's page shows a "Restore <name>" button where Care would be. "Create a site" in Restore opens the create-site form directly and returns to Restore after saving.

### Species
- C16 (W3-opus-9). Change species review shows common names first on both sides ("Jade Plant (Crassula ovata)" -> "Snake plant (Dracaena trifasciata)"), no author citations outside Details; saving gives a receipt "Changed <plant> to <species>"; the history line uses common names.
- C17 (W3-opus-10, W3-opus-11). Species names: the top shows the name plants of this species use now and why (household name, or chosen source name), then the household name form; household names can be removed with a confirmation that says how many plants it renames; add and remove give receipts; the list keeps its scroll position. Names from sources are folded under "Other names from sources (N)". Species care: leads with the effective care values; source, GBIF key, fetch status and Refresh/Retry fold under a collapsed "Sources"; fetch times read as dates; field cards show their source only when known; range validation reads "Minimum must be lower than maximum" and clears as soon as the values are valid; the title is the common name with the scientific name (no author) under it.

Out of scope, noted by the council: species search keyboard (W4); tab navigation and cross-device list refresh (W6); Sites and More (W5); junk source names in the data (a later data-quality task).

## Council round 2 amendments (2026-11-14)

From council-w3-opus-r2.md (Sol scored every item 10 in round 2). C18-C24 are required for 10/10.

- C18 (W3-opus-14, S2). Undo of this device's own skip or snooze always restores the row it reversed, on the plant page and Today, including when the skip's new date equals the date the task already had and when another of this device's actions changed the same task in between (e.g. snooze Water, then a liquid fertilize that also waters, then Undo snooze). "Changed elsewhere" appears only when another device changed it. Skip is not offered when it would not change the task's date.
- C19 (W3-opus-15). Every way of leaving a form with typed changes asks first, including links inside the form (This plant's schedule -> Care defaults for this species). Back from a page opened from a form returns to that form with its typed values.
- C20 (W3-opus-16). A site created from Restore is selected when the form returns.
- C21 (W3-opus-17). When Fytorium answers a plant request again, pages whose own reads failed because it was not responding reload them on their own; the "isn't responding" line clears without a tap once they load.
- C22 (W3-opus-18). Species names speaks plainly: no job or retry wording (e.g. "Some names are still loading." only while a fetch runs, nothing after it gives up); the remove confirmation names the name plants will show instead ("1 plant will show Spider Plant instead").
- C23 (W3-opus-19). A failed save's message inside a form never moves the Save button: it appears below the button or in space reserved for it.
- C24 (W3-opus-20). Species care leads with the care values Fytorium knows, in plain words, with no rule IDs or source labels outside the folded Sources; fields with no value are folded under "No value yet (N)", each still overridable there. A household override reads as "Your value" (not "fetched"). Saving or removing an override gives the standard receipt over the bar ("Saved watering interval for Spider Plant"), not a top-bar status. More -> Unmatched species follows the same rules: common name first with the scientific name under it, plain status ("No care data found yet"), no source-service headings.

## Council round 3 amendments (2026-11-15)

From council-w3-sol-r3.md and council-w3-opus-r3.md on 508297f. C25-C30 are required for 10/10.

- C25 (W3-sol-4, S2). At an apply moment (entering or returning to the plant page, resume, pull), Care rows take the plant's fresh schedule and history: a listed completion, skip or snooze that the fresh history shows undone or changed elsewhere is replaced by the fresh state for that task (e.g. Water "until Fri 20 Nov" becomes "Due today"), on every entry route including the selected-task route from Today. Between apply moments the existing hold applies (disabled in place, "changed elsewhere").
- C26 (W3-sol-5). Every link that leaves a form with unsaved values asks first, like This plant's schedule -> Care defaults: Restore -> Create a site included. Returning keeps the form's values.
- C27 (W3-opus-21). Back to the plant page from a page it opened (Care defaults, Species names, photos, forms) returns to the same scroll position with Details folded or unfolded as the user left it.
- C28 (W3-opus-22). This device's own rename shows at once everywhere on the page, including the Care rows' and other controls' spoken names.
- C29 (W3-opus-23). Repot shows a refused pot or soil type under the Repot button (C23 allows below the button); no space is reserved under the choices.
- C30 (gate carry-over). An automatic retry of a failed sites read never clears a field or save error the user has not acted on (retry only reads that left Save disabled, or keep the form's errors through the retry).

## Council round 4 amendments (2026-11-15)

From council-w3-opus-r4.md on 3e05fae (Sol scored every item 10 on the same build). C31-C32 are required for 10/10.

- C31 (W3-opus-24, S2). After a species change, Species names (and any page that shows the plant's species) shows the new species' real names straight away: choosing a species never leaves a placeholder species in place of the loaded one, and the page loads the chosen species' names whenever the shown species is not loaded (not only when its key changes).
- C32 (W3-opus-25). Sentences that end with a species name never print a doubled full stop when the name already ends with one (e.g. an author abbreviation "Mabb.").

## Council round 5 amendment (2026-11-16)

- C33 (W3-opus-26). Species names opens at the top for each plant and species; it keeps its scroll place only when returning to the same plant's Species names from a page it opened (C27). It never inherits the place from another plant's or species' names page.

## Council round 6 amendment (2026-11-17)

- C34 (W3-opus-27). C27 holds after content above the place changes: returning to the plant page after a Repot from its menu, or after a species change picked up while on a page it opened, restores the place by what was on screen (the item the user was looking at), not only by a scroll offset, so the page does not come back a screen higher at the end of the page with Details open.

## Council round 7 amendment (2026-11-17)

- C35 (W3-opus-28). C34 also holds across a configuration change (rotation, dark mode) or process recreation while a page opened from the plant page is showing: the plant page's place is saved as the key of the item the user was looking at plus its offset, and restored by that key, so content that changed above it cannot shift the place.
- Accepted limit (Spyros, 2026-11-17): a gallery photo chosen from a provider that offers no persistent read grant is held only by its temporary grant; if Android kills the app before that Add photo draft is saved, the restored draft shows the photo but cannot upload it, and the user chooses it again. Camera photos and every other draft value survive. Upgrade path: copy such inputs into app-private draft storage.

## Council round 8 amendment (2026-11-17)

- C36 (W3-opus-29, S2). The permanent-delete confirmation always opens empty: cancelling, Back or dismissing the dialog clears the typed name, so its delete button ("Delete" since C39) is never enabled when the dialog appears. The typed name still survives rotation and process recreation while the dialog stays open (C19).

## Council round 9 amendment (2026-11-17)

- C37 (W3-opus-30). Every W3 dialog stays usable in landscape and at 1.3x text: its body scrolls inside the dialog when it does not fit, text fields keep their full height and show what is typed, and the buttons stay visible. Applies to the delete-plant confirmation and every other dialog reachable from the Plants tab and the plant page.

## Council round 10 amendment (2026-11-19)

- C38 (W3-opus-31, S18). The remove-override question names the field in its body, which is never cut: title "Remove this override?", body "Every <species> goes back to the default for <field>." with the field label in full.
- C39 (W3-opus-32, S10, F15; body order superseded by C42). The delete-plant confirmation shows "Type <name> to confirm." and the full "Plant name" field together, without scrolling, when it opens in landscape (800×360 dp) at 1.0 and 1.3x text with a name up to 60 characters. The buttons are "Cancel" and "Delete" in one row; the body order is the instruction, the field, then what is deleted. The dialog keeps one width while typing and may be wider than 320 dp when the window allows (up to 560 dp). Every W3 dialog body that scrolls shows a divider on each edge that has more content, so a cut-off body never looks complete.

## Council round 11 amendment (2026-11-19)

- C40 (W3-opus-33, S18). Care defaults for this species keeps its place through rotation, theme and font changes and process recreation, as the plant page does (C35): the place is restored once the species card has loaded again, and open folds stay open.
- C41 (W3-opus-34, S18). A dialog body's edge divider shows only when real content lies beyond that edge; space reserved for an error that is not showing never counts. The divider sits in the gap between the body and what follows, never across the last visible text line, and has at least 3:1 contrast against the dialog surface. The remove-override button reads "Remove".
- C42 (W3-sol-11, S26, F17). The delete-plant confirmation shows the whole "Plant name" field on open for any name, whatever its length or letter width. The field comes first in the body, directly followed by "Type <name> to confirm." and then the warning. For names up to 60 characters of ordinary text the full instruction also shows on open (C39); a longer or wider name lets the instruction continue below, marked by the bottom divider (C41).
