# Android Today Planta Look Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android Today looks like Planta, plants show common names in the phone's language, each card has "Handle all tasks", and tapping a row opens the plant page.

**Architecture:** The server picks each plant's display name from the request's `Accept-Language` with one SQL function, so every screen and the delete check agree. A new household-wide endpoint completes all due tasks of one type as one undoable batch, reusing `careAction` and the task-batch undo. Android sends the phone language, restyles `TaskRow`/`TaskScreen`/theme, and reuses the existing Today → Plants navigation (`feedPlant` pattern in `AppShell.kt`) to open `PlantDetailScreen` with the tapped task.

**Tech Stack:** SvelteKit + TypeScript + Postgres (dbmate migrations, vitest API tests); Kotlin + Jetpack Compose Material 3 (Robolectric + Roborazzi tests).

**Spec:** `docs/superpowers/specs/2026-10-20-today-planta-look-design.md`

## Global Constraints

- Name languages: `en`, `nb`, `el`. Any other phone language → `en`. `no` and `nn` map to `nb`.
- Display name order: nickname (non-blank) → vernacular in request language → English vernacular → canonical name.
- Same-language tie-break: source `owner`, then `nortaxa`, `wikidata`, `gbif`; then name ascending.
- Late pill text: `{d}d late` for 1–59 days, `{d/30}mo late` from 60 days (integer division).
- Touch targets ≥ 48 dp; every control keeps a content description.
- No web UI changes except names. No comments in code. No co-author in commits.
- Server tests: `./scripts/container test:unit`, `./scripts/container test:api`, `./scripts/container typecheck`. Android: `./scripts/android test`, `./scripts/android lint`, `./scripts/android screenshots`.

## Review Focus

1. A Greek phone deleting a plant with no nickname types the Greek name it sees → deletion succeeds (Task 1 test).
2. Card "Handle all tasks" pressed twice with the same request id → one batch, no duplicate events (Task 2 test).
3. Card batch undo after one plant moved to the graveyard → 409 with the existing restore message (Task 2 test).
4. A task 59 vs 60 days late → `59d late` vs `2mo late`, on one line at the narrowest supported width (Task 3 test).
5. Back from a plant opened via Today → returns to Today, not to the Plants list (Task 5 test).

---

### Task 1: Localised plant display name on the server

**Files:**
- Create: `migrations/20261020090000_plant_display_name.sql`
- Modify: `src/lib/server/auth.ts` (Identity), `src/hooks.server.ts`, `src/lib/names.ts`, `src/lib/server/plants.ts` (`plantSelect`), `src/routes/api/plants/+server.ts`, `src/lib/server/tasks.ts` (lines ~102, ~198), `src/lib/server/plant-lifecycle.ts:38-40`, `src/lib/server/photos.ts:28`, `src/lib/server/notifications/completions.ts:10`, `src/lib/server/notifications/worker.ts:13`, `src/lib/server/weather/cache.ts:29`, `CONTEXT.md` (Plant entry)
- Test: `tests/unit/vernacular.test.ts`, `tests/api/names.test.ts`

**Interfaces:**
- Produces: SQL `plant_display_name(nickname text, gbif_key integer, language text) RETURNS text` (STABLE).
- Produces: `requestLanguage(header: string | null): 'en' | 'nb' | 'el'` in `src/lib/names.ts`.
- Produces: `Identity.language: 'en' | 'nb' | 'el'`.
- Produces: `plantSelectFor(language: 'en' | 'nb' | 'el'): string` replacing `plantSelect`. The language is inlined as a quoted literal only after checking it is one of the three values.

- [ ] **Step 1: Write failing unit test** in `tests/unit/vernacular.test.ts`:
```ts
test('requestLanguage picks the first supported language and falls back to English', () => {
  expect(requestLanguage('el-GR,el;q=0.9,en;q=0.8')).toBe('el');
  expect(requestLanguage('nn-NO')).toBe('nb');
  expect(requestLanguage('no')).toBe('nb');
  expect(requestLanguage('de-DE,nb;q=0.5')).toBe('nb');
  expect(requestLanguage('fr-FR')).toBe('en');
  expect(requestLanguage(null)).toBe('en');
});
```
- [ ] **Step 2: Write failing API test** `plant names follow Accept-Language` in `tests/api/names.test.ts`. Use synthetic species with owner names `Synthetic berry` (en), `Syntetisk bær` (nb), `Συνθετικό μούρο` (el). Assert for `GET /api/plants`, `GET /api/tasks?view=today` (`plant_name`) and `GET /api/plants/:id` (`display_name`):
  - `Accept-Language: nb-NO` → `Syntetisk bær`; `el` → `Συνθετικό μούρο`; `fr` → `Synthetic berry`.
  - With nickname `Bob` → `Bob` for every language.
  - Species with no names → canonical name.
  - Delete with `Accept-Language: el` and `confirm_name: 'Συνθετικό μούρο'` succeeds; the canonical name and nickname are also accepted; another species' name is rejected with 422.
- [ ] **Step 3: Run** `./scripts/container test:unit` and `./scripts/container test:api` → both new tests FAIL.
- [ ] **Step 4: Implement.** Migration creates `plant_display_name` using `species_names` with the Global Constraints order and tie-break (`-- migrate:down` drops it). `hooks.server.ts` sets `identity.language = requestLanguage(request.headers.get('accept-language'))` wherever Identity is built. Request-scoped queries use `plantSelectFor(identity.language)` or `plant_display_name(..., <identity language>)`. Notifications worker, completions and weather cache have no request: use `'en'`. Delete check accepts `confirm_name` equal to the localised name, the non-blank nickname or the canonical name. Update the CONTEXT.md Plant sentence to the new order.
- [ ] **Step 5: Run** `./scripts/container typecheck`, `test:unit`, `test:api` → all PASS. Fix any existing test that asserted a canonical name where the fixture now has a vernacular name, by sending `Accept-Language` or asserting the new name, not by deleting it.
- [ ] **Step 6: Commit** `feat(names): show plant common names in the request language`, including the spec and this plan.

### Task 2: Complete all due tasks of one type

**Files:**
- Create: `src/routes/api/tasks/[taskType]/complete-all/+server.ts`
- Modify: `src/lib/server/tasks.ts` (new `completeType`, `undoBatch` action list), `src/lib/server/undo-policy.ts:58,63`, `src/lib/server/policy.ts` (route entry `'edit'`), `tests/api/openapi.ts` if it lists routes
- Test: `tests/api/task-handling.test.ts`, `tests/api/undo-permissions.test.ts`

**Interfaces:**
- Consumes: `careAction`, `recordEvent`, `schedulePlantTasks`, `taskIn`, `plantSelectFor` (Task 1).
- Produces: `POST /api/tasks/:taskType/complete-all` with body `{ request_id: uuid }` → `{ batch_id: string, events: CareEvent[] }`. `taskType` is a built-in task type or a custom task UUID; anything else → 404. Owner and family only (sitter → 403 through policy).
- Produces: care action name `complete-type` with payload `{ task_type }`, undoable through `POST /api/task-batches/:id/undo`.

- [ ] **Step 1: Write failing tests** in `tests/api/task-handling.test.ts`:
  - `complete-type completes only due or late tasks of that type across plants`: two plants with late `clean_leaves`, one with `clean_leaves` due in the future, one with late `water`. After the call, the two late clean tasks have `completed_event` with the same `batch_id`; the future clean and the water task are unchanged.
  - `complete-type is idempotent per request id`: the same `request_id` twice → same `batch_id`, same event count.
  - `complete-type batch undo reverses every event`: `POST /api/task-batches/:batch_id/undo` → both clean tasks due again.
  - `complete-type batch undo refuses graveyard plants`: move one plant to the graveyard, undo → 409 `Restore graveyard plants before undoing their care.`
  - Sitter → 403. Unknown type `nope` → 404.
- [ ] **Step 2: Run** `./scripts/container test:api` → new tests FAIL.
- [ ] **Step 3: Implement** `completeType(identity: Identity, taskType: string, requestId: string)` in `tasks.ts`. Inside `careAction(identity, null, requestId, 'complete-type', { task_type }, ...)`, lock active household plants with `plantSelectFor(identity.language) ... ORDER BY p.id FOR UPDATE OF p`, schedule them, and `recordEvent(..., randomUUID(), at, requestId)` for each due-or-late task of that type. Add `'complete-type'` wherever `'complete-all'` is allowed for batches (`undoBatch` SQL, `undo-policy.ts` both lines).
- [ ] **Step 4: Run** `./scripts/container typecheck` and `test:api` → PASS.
- [ ] **Step 5: Commit** `feat(tasks): complete all due tasks of one type as one batch`.

### Task 3: Android theme, late pill and row look

**Files:**
- Modify: `android/app/src/main/java/com/epaflix/fytorium/Theme.kt`, `TaskRows.kt`, `ApiClient.kt:75`
- Test: `android/app/src/test/java/com/epaflix/fytorium/TodayRedesignScreenTest.kt`, `TaskScreenTest.kt`, `NetworkConfigTest.kt` (or the existing ApiClient test)

**Interfaces:**
- Produces: `fun lateLabel(days: Int): String` in `TaskRows.kt`.
- Produces: `fun phoneLanguage(locale: Locale = Locale.getDefault()): String` returning `locale.toLanguageTag()`; every request sends header `Accept-Language` with it.

- [ ] **Step 1: Write failing tests:**
  - `lateLabel`: `1 → "1d late"`, `59 → "59d late"`, `60 → "2mo late"`, `89 → "2mo late"`, `90 → "3mo late"`.
  - A Robolectric test at the narrowest supported width (`@Config(qualifiers = "w320dp")`): a row with `lateDays = 46` shows `"46d late"` in one line (`assertHeightIsAtMost` of one line height, or text layout `lineCount == 1`).
  - ApiClient sends `Accept-Language: el-GR` when `Locale.setDefault(Locale("el", "GR"))`.
- [ ] **Step 2: Run** `./scripts/android test` → new tests FAIL.
- [ ] **Step 3: Implement.**
  - Theme: replace the orange primary/secondary family with a soft green, light and dark, close to Planta's (dark primary ≈ `0xFFB5D99C`, light primary ≈ `0xFF3F6A2A`). Pick the containers from the same green. Keep `error` red for the pill.
  - Row: no divider; photo 56 dp circle; pill `lateLabel(...)`, `maxLines = 1`, `softWrap = false`, centred text, overlapping the bottom ~25% of the photo; name `titleMedium` larger, site muted; snooze icon `Icons.Outlined.NotificationsPaused` tinted primary; completion circle outlined in primary.
  - Remove the expandable details panel and the per-plant "Handle all tasks for …" button from `TaskRow`. Keep Undo on the row.
  - Row click target becomes `onOpen: () -> Unit` with click label `Open ${task.plantName}`. Task 5 wires it.
- [ ] **Step 4: Run** `./scripts/android test` → PASS. Run `./scripts/android screenshots` and update the recorded Today/Upcoming images. Run `./scripts/android lint` → no new warnings.
- [ ] **Step 5: Commit** `feat(android): Planta look for task rows and green theme`.

### Task 4: Today cards: header, top cleanup, per-card Handle all

**Files:**
- Modify: `android/app/src/main/java/com/epaflix/fytorium/TaskScreen.kt`, `TodayPresentation.kt`, `Repository.kt`, `CareController.kt`, `AppShell.kt`
- Test: `TodayRedesignScreenTest.kt`, `RepositoryTest.kt`

**Interfaces:**
- Consumes: Task 2 endpoint; `TaskRow(onOpen)` from Task 3.
- Produces: `Repository.handleType(taskKey: String): Boolean` and `CareController.handleType(taskKey: String)`. Request id `requestId("type:$taskKey")`, cleared on success. On success, put returned events into `state.handled` keyed `plantId:key` and refresh Today.
- Produces: `TaskScreen(..., onHandleType: (String) -> Unit = {})` replacing `onHandleAll`.

- [ ] **Step 1: Write failing tests:**
  - Today shows the centred top-bar title `Today`, and no greeting, tip or summary text (assert `onNodeWithText("Good morning")` and the tip and summary nodes do not exist). The Today/Upcoming switch still exists.
  - Each Today card for owner/family with ≥1 pending task shows the button `Handle all tasks`; tapping it calls `onHandleType(section.key)`. Hidden for a sitter, hidden in Upcoming, hidden when every task in the card is handled.
  - `RepositoryTest`: `handleType("clean_leaves")` POSTs `/api/tasks/clean_leaves/complete-all` with a UUID `request_id`, reuses it on retry, and every returned row then exposes `undo` through `task.actions()`.
- [ ] **Step 2: Run** `./scripts/android test` → FAIL.
- [ ] **Step 3: Implement.** Remove `greeting`, `tip` and `summary` from `TodayPresentation` and its use. Card: large bold title, rows without dividers, then a centred filled pill button `Handle all tasks` (`FilledTonalButton`, min height 48 dp). Wire `onHandleType` through `AppShell` to `CareController.handleType`.
- [ ] **Step 4: Run** `./scripts/android test`, `screenshots` (update images), `lint` → PASS.
- [ ] **Step 5: Commit** `feat(android): Today cards with Handle all tasks per task type`.

### Task 5: Tap opens the plant page with the tapped task

**Files:**
- Modify: `android/app/src/main/java/com/epaflix/fytorium/AppShell.kt`, `TaskScreen.kt`, `plants/PlantsScreen.kt`, `plants/PlantViews.kt`
- Test: `PlantsScreenTest.kt`, `TodayRedesignScreenTest.kt`, `androidTest/.../TodayScenario.kt` (adjust taps that opened the old panel)

**Interfaces:**
- Consumes: `TaskRow(onOpen)`; existing `CareController.act(task, action, pot)`.
- Produces: `TaskScreen(..., onOpenTask: (CareTask) -> Unit = {})`.
- Produces: `PlantsScreen(..., initialTask: CareTask? = null, onTaskAction: (CareTask, String) -> Unit = { _, _ -> })`. When `initialTask != null`, Back from `detail` calls `onBack()`.
- Produces: `PlantDetailScreen(..., todayTask: CareTask? = null, onTaskAction: (String) -> Unit = {})`.

- [ ] **Step 1: Write failing tests:**
  - Tapping a Today row calls `onOpenTask` with that task.
  - `PlantDetailScreen` with `todayTask` (owner) shows the task label, `Skip`, and for `repot` the `Repot with new pot and soil` button, plus the task's `Based on` provenance. `Skip` calls `onTaskAction("skip")`; the repot button runs the existing `repot-form` command.
  - The same for a sitter shows the task and `Skip`, no repot button, and still no other owner sections.
  - `PlantsScreen(initialPlant = id, initialTask = task)`: Back calls `onBack` once (assert a counter).
- [ ] **Step 2: Run** `./scripts/android test` → FAIL.
- [ ] **Step 3: Implement.** In `AppShell`, add `todayTask` state next to `feedPlant`; `onOpenTask = { todayTask = it; feedPlant = it.plantId; tab = "Plants" }`; pass `initialTask = todayTask` and `onTaskAction = { task, action -> controller?.act(task, action, null) }`; clear `todayTask` in `onBack` and on tab change. Hoist the Today `ScrollState` into `AppShell` with `rememberSaveable(saver = ScrollState.Saver)` so returning keeps the position. Render the `todayTask` block at the top of `PlantDetailScreen`, for sitters too, with actions from `todayTask.actions(today, member)`.
- [ ] **Step 4: Run** `./scripts/android test`, `screenshots`, `lint` → PASS. With the stack up (`./scripts/stack up`), run `./scripts/android e2e` → PASS.
- [ ] **Step 5: Commit** `feat(android): tapping a Today task opens the plant page`.

### Final

- [ ] `git fetch origin && git rebase origin/main`, rerun `./scripts/container test:api` and `./scripts/android test` → PASS.
