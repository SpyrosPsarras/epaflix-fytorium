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
An individual plant in the household, with a species and a required site in the same household. Its display name is its non-blank nickname, then a vernacular name in the request language, then an English vernacular name, then its species' canonical name. Nickname, cultivar and birthday are optional. Birthday cannot be later than the server clock's UTC date. Health and care history are recorded. Active plants require a site; graveyard plants can keep their history without one.

**Pot attributes**:
Pot type is `plastic`, `terracotta`, `ceramic`, `glass`, `metal`, `wood`, `fabric`, `in_ground` or `other`. `other` records an unknown material without changing the schedule. Soil type is `all_purpose`, `cactus`, `orchid`, `aroid` or `other`. Pot size in cm is a positive whole number or unknown. Drainage is a boolean.

**Plant attributes**:
Plant size is `small`, `medium`, `large` or `very_large`. Growth stage is `seedling`, `young` or `mature`. Window distance in cm is a positive whole number or unknown. Grow light, near AC and near heater are booleans. Fertilizer type is `liquid`, `sticks` or `slow_release`.

**Species**:
A botanical taxon at species rank or below, in kingdom Plantae. A Planta import may use a genus when the owner cannot choose a species. Its **GBIF key** is the backbone `usageKey`, or the `acceptedUsageKey` for a synonym. Scientific name includes the taxonomic authorship; canonical name omits it. Multiple plants share one species. Its common names belong to the shared name index.

**Reference photo**:
A GBIF occurrence's first still image, licensed CC BY 4.0 or CC0, shown beside species candidates on web and Android with photographer credit linking to the source page. Postgres caches its sanitised thumbnail and credit for 30 days, including a no-photo result. A GBIF photo lookup, common-name lookup or image download failure shortens that cache lifetime to one hour before retrying. Owner and family read `/api/species/:gbifKey/photo` and `/photo/credit`; external image URLs stay server-side. Missing or failed photos show "No reference photo".

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
A care action for a plant or site. Built-in task types are `water`, `fertilize`, `clean_leaves`, `remove_dead`, `cut_back` and `repot`. Their labels are Water, Fertilize, Clean leaves, Remove dead flowers and leaves, Seasonal cut-back and Repot. The pure schedule engine returns tasks keyed by type, with an interval, due date or `not_applicable`, and the inputs and adjustments behind them. Today groups due and late tasks by type.

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

**Liquid fertilizer completion**:
Completing Fertilize with liquid fertilizer also records watering at the same time, even before watering is due. The water schedule restarts from that date. This applies to a single Done, the plant's Handle all and the Fertilize card's Handle all, with water recorded only once per plant per action. Undoing fertilize also undoes the water it added. After a single Done, undoing water alone leaves fertilize in place. Handle all water belongs to its batch, so single-event undo returns 409 and batch undo reverses both. Snooze, skip, sticks and slow-release fertilizer do not add watering. Web and Android refresh the water date without a manual reload. Each completion sends its own notice.

**Pruning month**:
Care-profile `pruningMonths` contains whole month numbers 1–12, default March. With no completion, cut-back is due on the first day of the next pruning month on or after addition. After completion, it is due in the first pruning month of the next year. Its custom interval replaces that annual anchoring.

**Other care rules**:
Clean leaves every 30 days indoors only. Remove dead flowers and leaves every 14 days. Repot every 730 days for pots up to 20 cm or unknown size, and 1095 days above 20 cm; never in-ground plants. Each fallback has a named, versioned rule and reason.

**Custom interval**:
Whole days from 1–365, stored per plant and task type in the plant's household. It replaces that task's computed interval after factors and clamps, with source `override`. Clearing restores the computed interval. Applicability and fertilizing season still apply. Fertilizing's default follows the current watering interval, including a watering override.

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

## Identification

**Identification**:
Ranked species suggestions from one to five photos of the same plant, not a saved plant. Identification photos have no location or camera metadata when sent to Pl@ntNet and are not kept as photo updates.

**Identification candidate**:
A suggested species with a GBIF key, scientific name, common names and score. Every candidate needs the owner's explicit pick followed by Confirm species, even when its score is high. A GBIF-mapped candidate keeps the Pl@ntNet name and score separate from the accepted GBIF name and match confidence.

**Identification score**:
Pl@ntNet's score for a candidate, displayed as a percentage. It is separate from GBIF name-match confidence and never removes the need for confirmation.

**Identification quota**:
The remaining daily identification requests reported by Pl@ntNet for this instance. Members see the last known count when it is 50 or fewer, and an exhausted quota means using name search or trying another day.
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

## Planta import

**Import run**:
An owner-only fetch from Planta's official public API using a one-time app code. The server never returns, logs or stores the code or tokens. Tokens exist only in server memory while fetching and are discarded before review. A new successful fetch replaces the prior review; a failed fetch keeps it.

**Staging**:
ImportStaging stores mapped fields and server-validated species candidates in Postgres, keyed by household and Planta plant ID. Exact matches arrive confirmed; uncertain matches require an explicit choice or a skip. The shared SpeciesPicker can supply a different species. Review and confirmed selections survive restart. Commit refuses outstanding reviews, processes each confirmed row transactionally and records its plant ID. Retrying the same review skips committed rows. Cover download or decode failures still commit the plant, with a persistent cover-not-imported reason shown in review. A fresh fetch retries an unsuccessful cover. Review exposes whether a cover exists, never its potentially signed URL. Owner-only `/api/import/planta/:id/cover` downloads through the public-address guard and returns a cached sanitised thumbnail, or 404 for missing or foreign covers. Web and Android show covers or placeholders, sort "Needs review" cards first and list the remaining names above the cards and beside the blocked import button. Names link or scroll to their cards on both platforms. Server commit eligibility is unchanged.

**Import photo suggestion**:
A Pl@ntNet comparison of a staged plant's Planta cover, labelled "Looks most like your photo". It helps the owner compare pictures but never confirms a species automatically; GBIF text-match confidence is not a visual identification score.

**Planta plant ID**:
The external identifier of one Planta inventory plant, unique within its household. A fresh fetch updates species, nickname, cultivar, site, pot and soil by this key, including owner edits to those fields. Other Fytorium fields stay local. A graveyard plant is skipped without changing its fields, site, species, history or photos. Only an explicit restore followed by a fresh fetch makes it eligible again. Stored species taxonomy is never replaced by import. The successful cover-import flag stays even if that photo is deleted; later fetches cannot replace the cover or download it again.

**Imported care event**:
A completed care event with source `planta`, one per plant and action. A fresh fetch replaces its last completed timestamp without duplicating the event or removing an undo. Imported events are standalone, never part of a handle-all batch; their identity remains the same across fetches. Local batches, snoozes and skips are unchanged. Missing dates do not erase events. Future or invalid dates are refused. The existing schedule engine anchors on these events, like ordinary completions.

**Imported site conditions**:
Planta supplies a site name, not its conditions. Import matches names case-insensitively or creates an indoor site with default conditions and an incomplete-conditions flag. Saving its settings clears the flag. Re-runs never reset site conditions. The pot and soil mapping table and the full re-run rule are in README.md.

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
One care action completing exactly a plant's due and late tasks today, never upcoming or not-applicable tasks. A Today card's Handle all tasks completes every due or late task of that card's type on the plants shown in the member's Today view. Either action forms one undoable care batch.

**Care batch**:
One `care_actions` request identity groups `care_events.batch_id` completions. Per-plant Handle all tasks, per-type Today card completion and rain watering share this mechanism and `POST /api/task-batches/:id/undo`. Single-event undo refuses batched events. Rain tap aliases live in `care_actions.batch_id`, and `weather_events.batch_id` links to the original `care_actions.request_id`. Undo marks the action and its completions undone without deleting history.

**Repot flow**:
A repot completion together with new pot type, pot size and soil type. Undo restores the previous pot data and schedule; multiple repot flows must be undone newest first.
## Household weather

**Location**:
One latitude and longitude pair on the household, with an optional owner-entered label. Only the owner sets it. Latitude is between -90 and 90, longitude between -180 and 180. Removing the location clears its cache and resolves its alerts. Coordinates go to Open-Meteo, never to logs. Changing only the label does not discard weather.

**Weather snapshot**:
The server builds today's snapshot from the household's Postgres forecast cache. It contains the mean of today's and the next six daily maximum temperatures, today's daylight hours and the fetch time. Missing days leave the affected input unknown. Only outdoor plants receive it. The pure engine applies water.weather_temperature@1, at least 28 °C ×0.75, at most 12 °C ×1.25, and otherwise neutral. water.weather_daylight@1 stretches days with at most nine daylight hours by ×1.25. These factors precede the existing round, clamp and custom interval, and retain source weather in Based on. Fertilizing's every-second-watering default follows that watering interval.

**Weather cache**:
Forecast and climate are separate Postgres records. The background loop claims a refresh using the shared clock, at most hourly for forecast and once per rolling month for climate. Failed climate fetches retry after six hours; the monthly limit applies to successful fetches. Failures keep the last good data and the first unavailable_since time until success. An eight-second timeout bounds each Open-Meteo request. Location revision and attempt time prevent stale responses replacing a newer location or refresh.

**Potted hardiness**:
The minimum survival estimate for a pot is the in-ground minimum plus 5 °C, hardiness.pot_offset@1. Roots in a pot have less soil insulation. Effective hardiness prefers the minimum survival temperature in °C, hardinessC, including an owner override or Open Plantbook value. Without that value, Perenual hardiness remains a separate zone range, whose minimum is interpreted as USDA using hardiness.usda_uncertain@1, explicitly uncertain because Perenual does not name the scale. With neither value, hardiness falls back to 5 °C in ground, hardiness.default@1, labelled as a rule. Alerts and outdoor months share this precedence and show the selected source and value. These estimates are not guarantees against frost damage.

**Weather event and alert**:
A household record with kind low_temperature or rain, optional plant, start and end dates, payload, creation time and resolution time. A low-temperature alert compares forecast daily minima with each active outdoor plant's applicable hardiness. Consecutive cold days form one cold spell; overlapping refreshes reuse its open event. Separate spells remain distinct. Warming forecasts, moved or graveyard plants and changed locations resolve the old events. Home and the plant page show open cold alerts.

**Rain report**:
At least 5 mm of precipitation today or yesterday qualifies, weather.rain@1. Mark outdoor plants watered is one client UUID request and one task batch. The tasks module writes a water completion for every active outdoor plant, with one batch ID and member attribution, in one transaction. Indoor and graveyard plants are untouched. Replaying a request returns its original batch even after undo. The qualifying rain events store the batch ID, so another UUID returns the existing batch without new care events. Each tap UUID records its batch and requesting member, including taps that reuse a batch, so retries stay idempotent after undo. One undo marks all its care events undone and makes the action available again. The latest open rain batch remains available to undo after a reload.

**Outdoor months**:
For each species, the months whose local climate mean daily minimum is at least applicable hardiness plus 2 °C, hardiness.outdoor_months@1. Pot and ground have separate rows on the Species care card. Climate uses Open-Meteo's CMCC_CM2_VHR4 modelled daily minima for 1991 to 2020, averaged by calendar month. No location prompts the owner to set one; a pending refresh is not an empty set of suitable months. Monthly means do not rule out an individual frost.

## Custom tasks

**Custom task**:
A household task with a trimmed name of 1–60 characters and a fixed interval of 1–365 whole days. It targets exactly one plant or site. The pure engine accepts customTasks with id, intervalDays and createdOn, and returns customTasks keyed by ID alongside the unchanged built-in task record. All tasks share the engine's anchoring and history step. First due date is max(plant added date, task created date) plus interval. Completion uses the completion date plus the current interval. Name and interval edits take effect on the next read.

**Plant task**:
A custom task belonging to one plant. Moving the plant to another site does not change it. A graveyard plant has no tasks; restoring it resumes scheduling from its retained history.

**Site task**:
A custom task applied on read to every active plant currently on its site, including later additions. Each plant has independent completion, snooze, skip and undo history. Moving off the site or into the graveyard stops that instance. A Today card's Handle all tasks completes every due or late instance of that custom task on plants shown in the member's Today view as one undoable batch. Per-plant Handle all tasks includes due custom tasks in that plant's batch.

Deleting a custom task sets deleted_at. Its definition and current name stay available to care history, but it disappears from schedules, Today, Upcoming and settings. Renaming also changes its historical display name. Target deletion retires custom tasks without discarding their definitions. Database triggers validate and lock the same-household target on creation or target changes, then retain the target UUID after retirement. This lets deleting a site keep custom history for its graveyard plants. Full plant deletion still cascades its care events.

## Notifications

**Push subscription**:
One member's browser device, stored with a unique HTTPS endpoint, validated p256dh/auth encryption keys, a user-agent label, creation time and last successful delivery time. Enabling a device asks permission only from a tap. Devices are private to their member. Removing one deletes its subscription; a 404 or 410 from its push provider removes it automatically. Public DNS addresses are checked and pinned before sending.

**Reminder time**:
A member's HH:MM in the household time zone. Daily reminders and weather alerts are independent member switches, both off by default. Device permission and server reminder settings are separate choices.

**Daily reminder**:
An encrypted push summary of the same due and late task list as Today, respecting that member's location filter and excluding completed tasks and graveyard plants. Its body contains task counts and up to three plant names. One database claim per member and local calendar date leases delivery for five minutes. `sent_at` is set after at least one device accepts, or records a skip if all devices expired. Sent claims never send again. Unsent expired claims recover after a crash or temporary failure on a later tick, only within the same local day. Empty lists send nothing. The `fytorium-daily` tag replaces yesterday's notification. A crash between provider acceptance and storing `sent_at` leaves an ambiguous outcome; a retry uses the same replacement tag.

**Weather alert notification**:
An encrypted push for an open rain or low-temperature weather event, sent only to owner and family members with weather alerts on. The push worker reads open events every thirty seconds, independently of weather reconciliation. One database claim per event and member has the same five-minute lease and `sent_at` completion or expired-device skip as daily reminders. Unsent expired claims retry while the event remains open, at most six attempts per event and member. The five-minute initial lease doubles per retry; the sixth failure stops and logs without private data. Sent claims never send again. Members who enable alerts later receive still-open events. Resolved events send nothing. Each event has its own notification tag. Both daily reminders and weather alert notifications open Today when tapped. Payloads contain no member identity or coordinates; Android's push provider sees timing and size, not the encrypted text.

## Household access

**Role**:
Owner, family or sitter. Owners alone manage invites, member removal and plant shares, household settings including location, and Planta import. Family can read and edit all other household data, including species names, care values and unmatched species.

**Invite**:
An owner's single-use link admitting a new OIDC identity or reactivating a removed member as family or sitter, with chosen plants for a sitter. It expires after seven days and can be revoked. Reactivation applies the invite's role and shares in the same household; an invite cannot move a member between households or change an active member's role.

**Share**:
Permission for a sitter to see one active household plant's Today and Upcoming tasks and basic page, and to complete, snooze, skip, handle all due tasks or undo their own care. It grants no access to plant lists, sites, photos, journal, history, care explanations or household editing; graveyard and deleted plants are invisible, and personal notifications are the only settings exception.

**Removal**:
Withdrawal of a member's access, devices and shares, taking effect on their next request while preserving their name in care history. A removed identity cannot log in again without a valid invite. A fresh invite for the same household restores access with its chosen role and shares, not old sessions or devices. An owner cannot remove themself or the last owner.

**Completion notice**:
An optional push telling a member that someone else completed a task, off by default and limited to shared active plants for sitters. It excludes the actor, snoozes, skips and imports, and enabling it does not replay past care. Delivery uses the shared public-URL guard and at most six attempts per event and member, with leases doubling from five minutes on each retry.

## Home feed and warnings

**Home feed**:
Cards in a horizontal strip above Today put late care and setup problems first. The server computes the feed on read using the batched task list, effective care profiles, sites and weather report. GET /api/feed uses fifteen database queries including session lookup, independent of plant count and Today filters.

**Feed card**:
One of late_tasks, current_weather, weather_alerts or plant_warnings. Late tasks show a count and every linked plant name. A single late plant is the card's destination; several plants get a Today see-all link. Current weather shows today's cached minimum and maximum with fetch time, or the waiting/unavailable notice when no current data exists, linking to weather settings. Cold alerts link to their plant and retain hardiness source, value and rule IDs. Rain cards link to weather settings and contain batch watering and undo controls; they disappear without active outdoor plants. Home computes schedules once and reuses the batch for the feed and member-filtered Today. Each plant with warnings has one card, linking to that plant. Empty card types disappear. Owner and family members can change their four switches, which default on and persist in Postgres across sessions. Sitters receive only late-care cards for shared active plants through the named sitterView home and feed projections, never weather, rain or plant warnings. Rain batch watering requires edit access and refuses sitters. Switching one off changes only that member's feed, not the warnings or alerts themselves.

**Plant warning**:
A pure, computed-on-read result with kind, reason and the inputs and sources used. Kinds are too_dark, too_bright, no_drainage and missing_info. Graveyard plants return none. warning.drainage@1 warns when drainage is false and the pot is not in_ground. warning.missing_info@1 groups missing site light, pot type, pot size, species care data beyond rules, and household location for outdoor plants. Species data exists when at least one non-null effective field comes from an override, Perenual or Open Plantbook. Rules and unknown values alone do not count. Pot size is required for this check even for in-ground plants.

**Effective light**:
warning.effective_light@1 uses the ordered scale full_shade, part_shade, part_sun, full_sun. A site or plant grow light adds one step, counted once when both are on. Window distance above 200 cm subtracts one step. Unknown distance is neutral. Both adjustments apply together, then clamp to the scale's endpoints. A gap of at least one step from the effective care profile's light need warns too dark or too bright. Missing site or species light cannot produce a light mismatch. These estimates prompt a setup check, not a measurement of actual light.

## App access

**Device**:
One member's registered Fytorium app installation. Browser sessions are not devices. Its access can be withdrawn without removing the member or their other devices.

**Device token**:
A credential belonging to one app device that grants only its member's current household role and shares. Revoking the device or removing the member ends that access; a fresh invite does not restore old device tokens.
_Avoid_: App password, API key

## Native client compatibility

**App metadata**:
The member's role, the household's calendar date and timezone, the server version and the optional minimum app version. These facts are available to every member, including sitters; the calendar date agrees with schedules and photo validation.

**Minimum app version**:
The oldest native app release the household server supports, or no minimum when every release remains supported. It limits native app requests, not web access, and never prevents an old app from checking compatibility or logging out.

**Visible plant count**:
The number of active household plants a member may read, independent of task preferences. For sitters, it includes only their shared active plants; graveyard plants never count.
