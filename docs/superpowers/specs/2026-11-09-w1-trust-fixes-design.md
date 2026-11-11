# W1: trust fixes (undo, receipts, names, Planta names)

Part of the UI/UX programme to bring every item in the council audit to 10/10 for both reviewers (GPT-6.1 Sol and Claude Opus 5.5). Audit reports: `~/handoffs/fytorium-ux/council-sol.md`, `council-opus.md`, merged report `2026-11-09-fytorium-ux-audit.md`. Waves: W1 trust fixes, W2 Today and Upcoming layout, W3 Plants and plant page, W4 add, identify and journal, W5 More and settings, W6 navigation.

## Why

Spyros uses the Android app daily. Undo on one Today row reverted the whole card. Plants show names nobody uses ("Fruit Salad Plant", "Crassula ovata", a 7-name comma string). Snooze and Done vanish without a way back. He no longer trusts what a tap does. W1 makes every care action predictable and reversible, and gives every plant one familiar name everywhere.

Reproduced live on the lab build (`~/handoffs/fytorium-ux/live/`): one row Undo after Handle all reverted 9 plants; a single Done removed the row with no Undo; Snooze removed the row with no feedback; the English names below.

## Goals

### G1. Every care action gives a receipt with Undo (Sol U1, U2, U13, U14, U18; Opus U1, U3, U4)

- After Done, Snooze, Skip, a card's Handle all tasks, and a plant's Handle all tasks, Today/Upcoming and the plant page show a Material snackbar with the result and an Undo action. Wording:
  - "Watered Thyme" (task verb + plant name; custom tasks use "<task name> done for <plant>")
  - "Fertilized and watered Thyme" when liquid fertilizing also recorded water
  - "Snoozed Coleus until Thu 19 Nov", "Skipped Coleus until Thu 26 Nov" (household date, short weekday, day, month)
  - "Watered 9 plants" for a card batch; "Handled 3 tasks for Thyme" for a plant batch
- Snackbar Undo reverses exactly what the receipt names: the single event, or the whole batch.
- The snackbar stays until dismissed, replaced, or 10 seconds pass (`SnackbarDuration.Long` or a 10 s custom duration). A new action replaces it.
- After Undo, a short confirmation snackbar ("Undone") without an action.
- Failures show the server's message in the snackbar, without Undo.

### G2. No per-row Undo text; your Done rows come from the server (Sol U1, U2, U18; Opus U1, U3, U11)

Decided by Spyros after gate round 7: the server, not the phone, keeps your completed rows.

- Remove the separate "Undo" TextButton line under rows.
- `GET /api/tasks?view=today|upcoming` always lists every completion the requesting member made today (household date, not undone) as its own row with `completed_event`, whatever Show completed says. This includes batch members, linked water, and sitters' own completions on shared plants. Show completed now only adds completions made today by other members. Rename the setting "Show others' completed tasks" on web and Android, and update CONTEXT.md.
- A completion records the due date of the occurrence it completed (the task's due date at the moment of completion) on the care event, in a new nullable column filled by every completion path (single, plant batch, card batch, linked water, rain, repot, Planta import, custom tasks). The completed row sorts and sits in its Upcoming section at that stored date, so it never moves later in the day. Events from before the migration fall back to their occurrence date.
- Today's completions are listed from the care events themselves, not from the current schedule: a completion stays listed even if its task no longer applies (plant moved, site changed, custom task deleted or moved), using the names history already keeps. Ties keep the existing order.
- Web Today/Upcoming: a completed row that belongs to a batch never offers a plain "Undo". It shows the batch scope in the same words as the Android receipt ("Watered 9 plants") and its Undo button says it reverses the whole batch ("Undo all 9"). A standalone completion keeps its plain Undo.
- Android and web show the rows the server sends. No device keeps its own list of completions across refreshes, app resume or restarts. Between a write and the next refresh, the app patches only the acted row from the write's response.
- Tapping the filled check of a standalone completion undoes that one event (with an "Undone" snackbar). Tapping the filled check of a batched completion does not undo; it re-shows the batch receipt so the scope is explicit.
- Content descriptions: pending circle "Mark <task> done for <plant>, <site>"; filled check "<Done label> for <plant>, <site>. Double tap to undo" for standalone, "… Part of <N plants> batch" for batched.

### G3. Snooze and Skip are visibly different from Done (Sol U14; Opus U4)

- A snoozed or skipped task leaves Today immediately (it is no longer due) and shows the G1 receipt.
- In Upcoming, a task whose current due date comes from a snooze or skip shows a small "Snoozed" or "Skipped" label under the site name. Never a green check.

### G4. Only the acting row is busy (Opus U6)

- A write disables and shows a small progress indicator only in the row (or card button) that started it. Other rows stay enabled. Writes still run one at a time through the existing `workLock`; taps on other rows queue rather than being dropped.
- The pull-to-refresh spinner shows only for real refreshes, not for writes.

### G5. Undo from plant history keeps Today in sync (Sol U3)

- Undo in the plant page's care history removes that event (or its whole batch) from Today's local handled state, as row Undo does. No stale green check after returning to Today.

### G6. One familiar common name per species, everywhere (Sol U4, U29 naming part; Opus U2)

Pick one common name per species and language with a pure TypeScript function, store the result, and read it in SQL.

- `pickCommonName(species, candidates)` in `src/lib/server/` (no I/O, unit-tested). Input: canonical name and `{source, name}` rows for one language. Rules:
  1. Split every name on `,` and `;`, trim, drop empties.
  2. Squash key = lower-case, accents removed, every non-letter removed ("Spider-plant", "Spider Plant", "spiderplant" share `spiderplant`). Drop candidates whose squash key equals that of the canonical name or of its genus (first word).
  3. If any `owner` name remains, pick from those; else if any `nortaxa` name remains, pick from those (ties: shortest, then alphabetical).
  4. Otherwise group `wikidata` and `gbif` names by squash key. Highest row count wins. Ties: highest average word frequency (for each word of 3+ letters in the group's first name, count how many candidate names contain it; average over its words), then shortest key, then alphabetical.
  5. Display form: within the group, the most frequent spelling after replacing `-` with a space; ties prefer a spelling containing a space, then alphabetical.
  6. Upper-case the first letter of the result (all sources). No candidate: null.
- Store picks in a new table `species_common_names (gbif_key, language, name, PRIMARY KEY (gbif_key, language))` for `en`, `nb`, `el`. Recompute a species' rows whenever its `species_names` change (end of a source fetch in `names.ts`, owner name add or remove). Backfill: the names worker computes missing rows for existing species on its next run; a migration may not run TypeScript.
- New SQL function `species_common_name(gbif_key integer, language text)`: the stored pick for the requested language when it is `en`, `nb` or `el`, else `en`; fall back to the `en` pick; else NULL.
- Unit-test fixture: `tests/unit/fixtures/common-names.tsv` (columns: canonical name, language, source, name) holds the GBIF (CC BY 4.0) and Wikidata (CC0) rows exported from the lab, with an attribution header line. NorTaxa rows are excluded because NorTaxa's licence is unverified (README). Expected picks:

| Species | en | nb |
|---|---|---|
| Beaucarnea recurvata | Ponytail | (none) |
| Chlorophytum comosum | Spider Plant | (none) |
| Coleus scutellarioides | Painted nettle | (none) |
| Crassula ovata | Jade Plant | Pengetre |
| Dracaena trifasciata | Snake plant | Svigermorstunge |
| Fragaria ananassa | Strawberry | Hagejordbær |
| Monstera deliciosa | Swiss Cheese Plant | Adams ribbein |
| Paeonia lactiflora | Chinese peony | Silkepion |
| Thymus vulgaris | Thyme | Kryddertimian |

- `plant_display_name(nickname, gbif_key, language)` becomes: nickname if set, else `species_common_name`, else canonical name. All existing callers keep working.
- GBIF ingest (`src/lib/server/name-sources/gbif.ts`) stores split names too, so search finds "Snake plant". A data migration splits existing `species_names` rows containing `,` or `;` the same way (insert the parts, delete the joined row, keep the unique constraint).
- Species search candidates gain `common_name` (`species_common_name` in the requester's language). The reference-photo endpoint returns `common_name` from `species_common_name` when the species is stored, else `pickCommonName` over the fetched GBIF list.
- Android: the species search result card and reference photo tile show `common_name` as the headline and the canonical name under it. Remove "Matched … · en" and "Stored species · SPECIES" lines from the card (keep match type and confidence only for GBIF fuzzy matches, worded "Close match, 92%").
- Update the Plant display-name entry in `CONTEXT.md` and the name rule in `README.md` and `android/README.md` if they state it.

### G7. Planta import keeps Planta's name and your fixes (Opus U5)

- Import maps `nickname = names.custom ?? names.localizedName`, but ignores `localizedName` when its loose key equals the scientific or canonical name's loose key.
- A re-fetch updates the nickname only when Planta supplies one by that rule. Otherwise it keeps the plant's current nickname. Other Planta-sourced fields keep today's replace behaviour. Update the README sentence that says re-fetch replaces nickname even when edited.

## API contract (added after gate round 1)

The app must not guess server rules. The server adds these fields; owner, family and sitter projections all include them.

- C1. `POST /api/plants/:id/tasks/:taskType/complete` returns `linked_events`: the care events recorded together with this one (the water event of a liquid fertilize), or `[]`. Undo of the main event already reverses them; the receipt names them ("Fertilized and watered Thyme").
- C2. Every task row in `GET /api/tasks?view=today|upcoming` and `GET /api/plants/:id/tasks` has `postponed`: `"snoozed"`, `"skipped"` or `null`, true when the task's current due date comes from the latest effective snooze or skip.
- C3. Every care event with a `batch_id`, wherever it is returned (task rows' `completed_event`, batch responses, history), has `batch_plant_count` (distinct plants with events in that batch) and `batch_event_count`. Batch receipts use these, never the number of visible rows.
- C3b. Every care event with a `batch_id` also has `batch_action` (`"complete-all"`, `"complete-type"` or `"rain"`) and `batch_task` (the task key the card completed for `complete-type`: built-in type or custom task ID; `null` otherwise), and `linked_event_id` where it applies. Every batch receipt on every device is built by one Android function from these fields plus C3 counts: `complete-type` → "<Verb> N plants" ("Fertilized and watered N plants" when the batch's main task is liquid fertilize with linked water), `complete-all` → "Handled N tasks for <plant>", `rain` → "Watered N outdoor plants". The device that performed the action and any other device show the same words, including the rain card on Home.
- C4. Unstored species have no common name anywhere: search candidates and the reference-photo endpoint return `common_name: null` unless the species is stored. Names appear once the species is saved and its names are fetched. Planta staging candidates and photo-suggestion candidates use the requester's language and carry `common_name`.
- C5. Planta's `localizedName` is checked against the finally selected species at commit, not only at fetch.

Android consequences: liquid-fertilize wording comes from C1 (single) or, for a `complete-type` batch, from `batch_event_count > batch_plant_count` (a card completes one task per plant, so extra events are linked water; the fertilize event itself carries no `linked_event_id`); snoozed/skipped labels from C2; batch receipt counts from C3; the search card headline is the candidate's `common_name` or the canonical name, and the reference tile shows the same value (no separate photo-endpoint name override). Completed rows come from the server (G2); the app keeps no saved positions or completion cache. Plant-page care actions follow G4 (only the acting control is busy; others queue); busy state is keyed per control, so each history entry is its own control.

### Occurrence identity (added after gate round 5)

Since today's completion and a later postponed occurrence of the same plant and task can both be listed (C2, S13), a row is identified by its occurrence, never by plant and task alone.

- O1. A completed row's identity is its completed event ID. A pending row's identity is plant, task and the occurrence's due date as listed when the user acted. At most one pending row exists per plant and task.
- O2. Busy state, local patches, Undo targets and receipts use the row's identity on Android and on the web Today/Upcoming component. On Android, navigation to the plant page also uses it (which occurrence is selected). The web plant page has no selected-task concept and stays as it is (non-goal: no new web UI).
- O3. Acting on one row never changes another row: completing the postponed occurrence leaves the earlier completion's check, Undo target, batch receipt and saved position untouched, and both survive refresh.
- O4. Server rows win. After every refresh or reload the list is exactly what the server sent; a local patch lives only until then.
- Tests: server API tests for the listing rule (own completions listed with Show completed off; others' only with it on; sort position; undone ones gone; batches; linked water; sitters). Android tests and a web Playwright step in `tests/browser/task-handling.spec.ts`: Done on a previously skipped row shows Done and Undo at once; after the S13 sequence, completing the postponed occurrence keeps both completions with their own checks and Undo after refresh; a completion undone elsewhere disappears on refresh; only the acted row is busy; opening each row selects its own occurrence (Android).

## Council round 1 amendments (live re-score, 2026-11-09)

Sol scored its W1 items 10. Opus scored S3 9, S11 8, F2 9, F3 9, F12 7 (`council-w1-opus.md`, findings W1-opus-1..8). These close them.

- K1 (opus-1). Filled-check content descriptions name the task: standalone "<Task> done for <plant>, <site>. Double tap to undo"; batched "<Task> done for <plant>, <site>. Part of a batch of N plants. Double tap to see the batch" for card and rain batches, and "… Part of a batch of N tasks. …" for a plant's Handle all (one plant, several tasks). Same on Upcoming rows.
- K2 (opus-2). After any Undo (single, batch, snackbar, check, history), affected rows show their pre-action state at once (late badge, Snooze, pending circle) not only after the next refresh. Every Undo response (single event, batch, rain) includes the member's current Today and Upcoming lists, built by the same code and rules as `GET /api/tasks` (filters, sort, sections, sitter projection); Android replaces both lists from them, so the app never rebuilds listing rules or sort order itself. Any plant page that the Undo affected and that is open reloads its details. On the web, the acted row stays busy until its reload lands, so it never shows a half state; web does not patch rows (non-goal: no new web UI).
- K3 (opus-3, opus-4). A search result whose matched name differs from both its headline and its canonical name shows "Matches “<matched name>”" under the canonical name. The choose button uses the headline: "Choose Snake plant". Same rule for the reference tiles. Android only; the web search card is unchanged (non-goal).
- K4 (opus-5). Plant-page Care history shows a new event as soon as an action on that page succeeds. After Undo the entry stays, marked Undone, with no Undo control and no batch hint (history keeps undone events). History Undo buttons say what they reverse: "Undo watering", "Undo fertilizing and watering" when it also reverses linked water, or "Undo all N" for a batch.
- K5 (opus-6, replaces G7's re-fetch bullet). Planta import stores the name it last imported per plant. A re-fetch replaces the nickname only when Planta's name differs from that last imported name; otherwise the plant's current nickname, including an owner's Fytorium rename, stays. First imports behave as before. When the last imported name is unknown (plants imported before this change, whose review is no longer retained), the migration stores no name and the next re-fetch only records Planta's name and keeps the current nickname.
- K6 (opus-7). Android and web import screens state K5 in plain words; no screen says a re-fetch replaces the nickname including your edits.
- K7 (opus-8). An import review card's headline is the name the plant will have after import: the nickname K5 will set (a graveyard plant keeps its current name, because commit skips it), else the selected species' chosen common name, else the canonical name the plant will have after commit (the stored species' name when the species already exists).

## Council round 2 amendment (2026-11-10)

Opus round 2 closed W1-opus-1..8 and found W1-opus-9 (`council-w1-opus-r2.md`).

- K8 (opus-9). A standalone completion that also recorded linked water (liquid fertilize) names both in its filled-check content description: "Fertilize and water done for <plant>, <site>. Double tap to undo both". Every Undo confirmation names what it reversed instead of a bare "Undone": "Undid watering for <plant>", "Undid fertilizing and watering for <plant>", "Undid snooze for <plant>", "Undid watering for 9 plants" (same verbs and counts as the receipts). Android only; on the web the history and row labels already name it.

## Non-goals

- Today layout (FABs, feed band, row order after actions, title and switch): W2.
- Plant page layout, Plants grid: W3. Add plant form and identify: W4.
- Changing server undo semantics. Batches stay all-or-nothing; the UI now says so.
- Web UI changes beyond what shared server changes produce automatically.

## Anti-goals

- An Undo that reverses more than its label says.
- A plant showing different names on different screens of the same app, including search and reference tiles.
- A Latin name shown as the common name when a vernacular name exists in the requested or English language.
- Losing a completion's Undo because of a refresh, app resume or setting.
- Touch targets under 48 dp or controls without accessibility labels.

## Constraints

- Android: Kotlin, Compose, Material 3, existing `TaskRow`, `TaskScreen`, `Repository`, `CareCache`, `PlantViews`. No new dependencies.
- Server: SvelteKit, Postgres, dbmate migrations (`migrations/`, timestamped, with `migrate:down`).
- All builds and tests run in Docker via the repo scripts. In this programme they run in the lab pod through `/tmp/fytorium-ux/lab-run <dir> '<cmd>'` from the checkout root. CI order: `./scripts/container install --frozen-lockfile`, `./scripts/container typecheck`, `./scripts/container test:unit`, `./scripts/container test:api`; Android `./scripts/android test`, `./scripts/android lint`, `./scripts/android screenshots` (record with `ANDROID_RECORD_SCREENSHOTS=1`, then pull `android/app/src/test/screenshots` back with `/tmp/fytorium-ux/lab-run --pull <dir> android/app/src/test/screenshots`).
- Tests: extend existing suites (`tests/api/names.test.ts`, `tests/api/planta.test.ts`, Android `TaskScreenTest`, `TodayRedesignScreenTest`, `RepositoryTest`, screenshot tests). Update, never delete, existing tests and screenshots.

## Done when

- Every goal above has a test that fails on the old code and passes now.
- CI-equivalent commands pass in the lab.
- Both council members re-score, on the live lab build, the items this wave touches at 10/10 with no open finding: Sol S4, S18 (naming part), F4, F5, F6, F7, F14; Opus S3 (undo, snooze, busy and name parts), S11 (name part), F2, F3, F12.
