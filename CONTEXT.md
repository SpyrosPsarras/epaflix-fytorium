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
An individual plant in the household, with a species, site, pot, health and care history. A dead plant can remain in the graveyard with its history.

**Species**:
A botanical species identified by its GBIF ID, with scientific and common names. Multiple plants can belong to the same species.

**Care profile**:
The effective care values for a species, with a source for each value. Owner overrides take priority over Perenual, Open Plantbook and Fytorium rules.

**Task**:
A care action due for a plant or site, such as watering or a custom recurring action. Its due date comes from the schedule rather than manual editing.

**Care event**:
A dated record of a task being completed, snoozed, skipped or undone, including who acted.

**Photo update**:
A dated plant photo with a note and health value. One photo can be the plant's cover photo.
