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
An individual plant in the household, with a species and a required site in the same household. Its display name is its nickname, or its species' canonical name if no nickname is set. Nickname, cultivar and birthday are optional. Birthday cannot be later than the server clock's UTC date. Health, graveyard and care history arrive in later tickets.

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
A care action for a plant or site. Task types are `water`, `fertilize`, `mist`, `clean_leaves`, `remove_dead`, `cut_back` and `repot`. Their labels are Water, Fertilize, Mist, Clean leaves, Remove dead flowers and leaves, Seasonal cut-back and Repot. The pure schedule engine returns tasks keyed by type, with an interval, due date or `not_applicable`, and the inputs and adjustments behind them. Today groups due and late tasks by type.

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
A dated record of care, including the acting member and their display name. Completions exist for every task type; snooze and skip arrive later. A client UUID request_id identifies one completion, so retries cannot write two events. The event timestamp comes from the server clock, not the client.

**Undo**:
Marks a care event with undone_at and undone_by without deleting the original action or who performed it. The engine ignores undone events and restores the previous due date. History keeps them visibly marked. Already-undone events and events from another household are refused.

**Photo update**:
A dated plant photo with a note and health value. One photo can be the plant's cover photo.
