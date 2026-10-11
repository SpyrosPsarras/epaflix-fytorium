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
A botanical taxon at species rank or below, in kingdom Plantae. Its **GBIF key** is the backbone `usageKey`, or the `acceptedUsageKey` for a synonym. Scientific name includes the taxonomic authorship; canonical name omits it. Multiple plants share one species row. Common names and the multilingual name index arrive in #11.

**Match confirmation**:
A stored species-rank scientific or canonical name matching the whole trimmed query, ignoring case, needs no confirmation. Local partial hits require confirmation and carry no match type or confidence. An EXACT species-rank GBIF match with confidence at least 90 needs no confirmation. Fuzzy, higher-rank match types, lower confidence, lower taxon ranks and alternatives require explicit confirmation of the chosen key. NONE, genus-or-higher taxa and nonplants are refused. The server keeps the candidates for five minutes, scoped to the household, and checks them when adding a plant. Names and confidence sent by the client are not authoritative.

**Care profile**:
The effective care values for a species, with a source for each value. Owner overrides take priority over Perenual, Open Plantbook and Fytorium rules.

**Task**:
A care action due for a plant or site, such as watering or a custom recurring action. Its due date comes from the schedule rather than manual editing.

**Care event**:
A dated record of a task being completed, snoozed, skipped or undone, including who acted.

**Photo update**:
A dated plant photo with a note and health value. One photo can be the plant's cover photo.
