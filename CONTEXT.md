# Fytorium

Plant care for one household, with shared tasks and a care history.

## Language

**Household**:
The people sharing one collection of plants and one location for weather. One Fytorium instance serves one household.

**Member**:
A person with access to the household. An owner manages it, family members can edit everything, and sitters can only complete tasks for plants shared with them.
_Avoid_: User, account

**Site**:
A room, garden or terrace where plants live. A site belongs to the household. Its conditions are shared by its plants.

- Location: `indoor` or `outdoor`.
- Light: `full_shade`, `part_shade`, `part_sun` or `full_sun`, matching Perenual's four sunlight tiers.
- Grow light and draft: booleans.
- Humidity: `dry`, `normal` or `humid`.
- Warm-period and cold-period temperatures: optional minimum and maximum in whole °C. If both are known, minimum must not exceed maximum. The warm period is the heating-off or summer half of the year. The cold period is the heating-on or winter half.

A site with plants cannot be deleted. Move or remove its plants first.

**Plant**:
An individual plant in the household, with a species and a required site in the same household. Its display name is its nickname, or its species' canonical name if no nickname is set. Nickname, cultivar and birthday are optional. Birthday cannot be later than the server clock's UTC date. Health and care history are recorded. Active plants require a site; graveyard plants can keep their history without one.

**Pot attributes**:
Pot type is `plastic`, `terracotta`, `ceramic`, `glass`, `metal`, `wood`, `fabric` or `in_ground`. Soil type is `all_purpose`, `cactus`, `orchid`, `aroid` or `other`. Pot size in cm is a positive whole number or unknown. Drainage is a boolean.

**Plant attributes**:
Plant size is `small`, `medium`, `large` or `very_large`. Growth stage is `seedling`, `young` or `mature`. Window distance in cm is a positive whole number or unknown. Grow light, near AC and near heater are booleans. Fertilizer type is `liquid`, `sticks` or `slow_release`.

**Species**:
A botanical taxon at species rank or below, in kingdom Plantae. Its **GBIF key** is the backbone `usageKey`, or the `acceptedUsageKey` for a synonym. Scientific name includes the taxonomic authorship; canonical name omits it. Multiple plants share one species. Its common names belong to the shared name index.

**Name index**:
The instance-wide collection of species names, each tied to a GBIF key, language and name source.

**Vernacular name**:
A common name for a species in English, Norwegian or Greek. Different species can share a vernacular name.
_Avoid_: Localized name

**Name source**:
The origin of a species name: GBIF, Wikidata, NorTaxa or the owner. Owner names remain when external names change.

**Match confirmation**:
A vernacular-name hit needs confirmation unless the whole query matches exactly one species-rank taxon, ignoring case and accents. A stored species-rank scientific or canonical name matching the whole trimmed query, ignoring case, needs no confirmation. Local partial hits require confirmation and carry no match type or confidence. An EXACT species-rank GBIF match with confidence at least 90 needs no confirmation. Fuzzy, higher-rank match types, lower confidence, lower taxon ranks and alternatives require explicit confirmation of the chosen key. NONE, genus-or-higher taxa and nonplants are refused. The server keeps the candidates for five minutes, scoped to the household, and checks them when adding a plant. Names and confidence sent by the client are not authoritative.

**Care profile**:
The effective care values for a species, with a source for each value. Owner overrides take priority over Perenual, Open Plantbook and Fytorium rules.

**Task**:
A care action for a plant or site. Built-in task types are `water`, `fertilize`, `mist`, `clean_leaves`, `remove_dead`, `cut_back` and `repot`. Their labels are Water, Fertilize, Mist, Clean leaves, Remove dead flowers and leaves, Seasonal cut-back and Repot. The pure schedule engine returns tasks keyed by type, with an interval, due date or `not_applicable`, and the inputs and adjustments behind them. Today groups due and late tasks by type.

**Due date**:
A calendar date in the household time zone, configured by HOUSEHOLD_TIMEZONE and defaulting to Europe/Oslo. With no completion, watering is due on the date the plant was added. Other interval tasks are first due on added date plus interval. Otherwise tasks are due on the last effective completion date plus the current interval. Seasonal cut-back uses pruning months instead. Reads recompute due dates; no command stores or edits them. Anchors have source `history` in Based on.

**Late**:
The whole-calendar-day difference between today and a past due date. Due today is not late. Today shows this as "3d late", for example. DST does not add or remove calendar days.

**Care rule**:
A named, versioned fallback or adjustment, such as water.default@1. Watering starts at seven days when species data is missing. Site, pot and plant factors multiply that estimate, then the engine rounds and clamps it to 1–60 days. Unknown inputs are neutral and recorded as "not set". Each adjustment records its rule and reason.

**Month factor**:
Watering uses the month of the anchor date, not today. The northern-hemisphere table is December–February ×1.5, November/March ×1.25, October/April ×1.1 and May–September ×1.0. These are starting estimates for slower winter growth.

**Fertilizing season**:
March–September. A due date outside it moves to March 1 of the next season. A care-profile `fertilizingIntervalDays` takes priority over defaults. Liquid or unknown fertilizer defaults to twice the current watering interval, `fertilize.every_second_watering@1`. Sticks use 56 days and slow-release uses 90.

**Pruning month**:
Care-profile `pruningMonths` contains whole month numbers 1–12, default March. With no completion, cut-back is due on the first day of the next pruning month on or after addition. After completion, it is due in the first pruning month of the next year. Its custom interval replaces that annual anchoring.

**Other care rules**:
Mist every three days only indoors, with dry or normal site humidity and medium or high care-profile `humidityNeed`, default medium. Clean leaves every 30 days indoors only. Remove dead flowers and leaves every 14 days. Repot every 730 days for pots up to 20 cm or unknown size, and 1095 days above 20 cm; never in-ground plants. Each fallback has a named, versioned rule and reason.

**Custom interval**:
Whole days from 1–365, stored per plant and task type in the plant's household. It replaces that task's computed interval after factors and clamps, with source `override`. Clearing restores the computed interval. Applicability and fertilizing season still apply. Fertilizing's default follows the current watering interval, including a watering override.

**Misting setting**:
Both the plant and household have `misting_enabled`, default true. Both must be on for misting to apply. Turning the household switch off preserves each plant's setting. All members can edit household settings until role enforcement arrives in #16.

**Care event**:
A dated record of care, including the acting member and their display name. Completions, snoozes and skips exist for every applicable built-in or custom task. Exactly one of task_type or custom_task_id identifies its target. A client UUID request_id identifies one completion, so retries cannot write two events. The event timestamp comes from the server clock, not the client.

**Undo**:
Marks a care event with undone_at and undone_by without deleting the original action or who performed it. The engine ignores undone events and restores the previous due date. History keeps them visibly marked. Already-undone events and events from another household are refused.

**Photo update**:
A dated plant photo with an optional note and health value. The member chooses `taken_on`, defaulting to today in the household time zone. Past dates are allowed; future or invalid dates are refused. The server applies camera orientation, strips metadata and stores a re-encoded JPEG original and a thumbnail in private MinIO. Postgres stores their server-generated object keys, never image bytes. Uploads are limited to 15 MB and decoded before storage. App photo routes check the session and household before streaming bytes.

**Cover photo**:
One chosen photo update per plant, enforced by a partial unique index. It appears in plant and site lists and on the plant page. Plants without one show a placeholder. Choosing another replaces the cover. Deleting a photo update deletes both MinIO objects and its row, clearing the cover if needed.

**Health**:
An optional value: `poor`, `fair`, `good` or `very_good`. Plant settings set it directly. An arriving photo update with health replaces plant health only if no update has a later taken date. On equal taken dates the last arriving upload wins, even if the clock has not advanced. Backdated updates do not overwrite current health; updates without health leave it unchanged. Settings therefore win until another newest-dated photo supplies health. Deleting an update does not change plant health.

**Journal**:
All the household's photo updates, ordered by taken date then creation time, newest first, with ID as a stable tie-breaker. Each plant also has a View all updates page. No photo or journal entry is shared across households.

## Plant lifecycle

**Active plant**:
`status = active` is the default. The earlier required-site and occupied-site rules apply to active plants. Only active plants contribute to Today, plant lists, site lists and counts.

**Graveyard**:
`status = graveyard` records the first move date in `graveyard_on`, using the household calendar. Retries keep that date. The graveyard list shows dates and cover photos. Plant pages retain care history, species history and photos, but return no tasks. Complete, snooze, skip, handle-all, repot and undo are refused until restore. Care commands and undo lock the plant row before checking its status. Settings and photo updates remain available.

**Restore**:
Returns a graveyard plant to active and clears `graveyard_on`, without changing history, photos or custom intervals. Graveyard plants do not block site deletion. Site deletion clears their `site_id` in the same transaction and leaves the RESTRICT FK for active plants. A database CHECK requires every active plant to have a site. A detached graveyard plant stays viewable and editable, but restore requires choosing an existing household site.

**Full delete**:
A separate permanent action, available for active and graveyard plants, confirmed by typing the current display name. One database transaction collects photo keys, deletes photo rows and the plant, and cascades care events, custom intervals and species changes. It commits before deleting both objects per photo, so a database failure cannot destroy photos that still have rows. Storage failures do not undo deletion. The server logs the object keys without SDK errors or credentials for later operator cleanup.

**Change species**:
Uses the same five-minute household search record, candidate validation and explicit confirmation as add-plant. Client taxonomy is never authoritative. Species changes snapshot the previous and next scientific names, keys, member and time in `plant_species_changes`, visible in the plant's care history. All care events, photos and custom intervals stay. Schedule reads use the current species and recompute every task.

Changing species and adding a plant share species saving, care-fetch queueing and name-index population. Schedule reads use the catalogue's effective care values, including household overrides and cached sources, without storing due dates.

## Open Plantbook matching and care

**Open Plantbook source**:
`open_plantbook` supplies care below Perenual and above rules. Each retained field includes fetched-at. An owner override wins over both sources.

**Pid**:
Open Plantbook's plant identifier. Its display_pid is matched through GBIF to the species' accepted key. Only one confirmation-grade EXACT species-rank candidate with confidence at least 90 and no alternatives confirms it. The pid is not a GBIF key.

**Match status**:
`matched` records a pid; `no_match` means no confirmed candidate; `ambiguous` means multiple pids or an uncertain/incomplete match. Owners see failures on the care card and the household-scoped unmatched list and can retry. A retry clears only Plantbook's matching metadata and cache, not overrides.

**Humidity need mapping**:
`open_plantbook.humidity@1` uses the arithmetic midpoint of a valid 0–100% environmental humidity range. Low is below 40%, medium is 40% to below 60%, high is at least 60%. The effective need controls misting and retains its source in Based on.

**Other Plantbook mappings**:
`open_plantbook.light@1` uses midpoint lux in 0–100000: below 2000 full_shade, below 5000 part_shade, below 20000 part_sun, otherwise full_sun. `temperature@1` retains an ordered −60 to 60°C range. `survival@1` retains a −60 to 60°C minimum as hardinessC, separate from Perenual hardiness zones. `soil_moisture@1` retains an ordered 0–100% sensor range, not a watering rule. `care_text@1` keeps plain text or recognised care strings, at most 4000 characters; the provider's care extension has no documented schema. These rule names share the open_plantbook prefix. Unknown or invalid values leave fallback fields intact.

## Catalogue care data

**Care source**:
An origin of species care values. Perenual and Open Plantbook supply external values; Fytorium rules supply estimates where data is absent. Unknown means no source supplies a value.

**Override**:
A household correction to one species care field. It takes priority over all source data and remains until a member removes it. A plant custom interval is a separate correction to one plant task.

**Source cache**:
The household's retained care values from one source for a species, with the source key and fetch time. It is not a distributable catalogue.

**Fetch queue**:
Species care requests waiting for a source quota or retry time. A plant does not wait for a fetch to receive a care profile.

## Task handling

**Snooze**:
A snoozed care event that moves a task's due date to the action's household calendar date plus two days, without changing its interval or counting as a completion. The latest effective snooze or skip after the last completion applies; undo restores the previous due date.

**Skip**:
A skipped care event that moves a task's due date to the action's household calendar date plus its current interval, without recording a completion. A later completion supersedes both skip and snooze; neither makes a not-applicable task applicable.

**Upcoming**:
Care tasks grouped by household calendar day for fourteen days including today, with overdue tasks in today's group. Not-applicable tasks are excluded; today's due and late tasks agree with Today.

**Task view settings**:
A member's choice of task-type, plant or site sort, all/indoor/outdoor filter, and whether to show today's completed tasks with Undo. The choice belongs to the member across sessions, not the device.

**Handle all tasks**:
One care action completing exactly a plant's due and late tasks today, never upcoming or not-applicable tasks. The completions form one batch; undoing any of its events undoes the whole batch.

**Repot flow**:
A repot completion together with new pot type, pot size and soil type. Undo restores the previous pot data and schedule; multiple repot flows must be undone newest first.

## Custom tasks

**Custom task**:
A household task with a trimmed name of 1–60 characters and a fixed interval of 1–365 whole days. It targets exactly one plant or site. The pure engine accepts customTasks with id, intervalDays and createdOn, and returns customTasks keyed by ID alongside the unchanged built-in task record. All tasks share the engine's anchoring and history step. First due date is max(plant added date, task created date) plus interval. Completion uses the completion date plus the current interval. Name and interval edits take effect on the next read.

**Plant task**:
A custom task belonging to one plant. Moving the plant to another site does not change it. A graveyard plant has no tasks; restoring it resumes scheduling from its retained history.

**Site task**:
A custom task applied on read to every active plant currently on its site, including later additions. Each plant has independent completion, snooze, skip and undo history. Moving off the site or into the graveyard stops that instance. There is no separate whole-site completion action. Handle all tasks includes due custom tasks for one plant in its existing undoable batch.

Deleting a custom task sets deleted_at. Its definition and current name stay available to care history, but it disappears from schedules, Today, Upcoming and settings. Renaming also changes its historical display name. Target deletion retires custom tasks without discarding their definitions. Database triggers validate and lock the same-household target on creation or target changes, then retain the target UUID after retirement. This lets deleting a site keep custom history for its graveyard plants. Full plant deletion still cascades its care events.
