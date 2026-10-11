# Keep identity in GBIF and layer care values by source

GBIF's accepted backbone key identifies a species. Care data is incomplete and source access is restricted, so owner overrides take priority per field, followed by Perenual, Open Plantbook in #10, and Fytorium rules. External data stays in the household database, never in repository fixtures or catalogue exports.

## Context and decision

The research note `.scratch/research/plant-data-sources.md`, researched 2026-10-11, found no open equivalent of Planta's seasonal schedules. Perenual gives a watering benchmark and pruning months, but no numeric humidity or ordinary temperature range. The engine retains its written seasonal factors and task defaults. Unknown source fields do not replace those defaults. Watering ranges use their arithmetic midpoint, converted to days before the engine applies adjustments. Hardiness is retained as a zone range, not converted to Celsius, because its scale is not explicitly documented.

Each external adapter implements CareSource. A persistent queue controls individual outbound requests, not just species jobs, because search and detail each consume quota. Adding a plant only writes a job. A source refresh cannot update the separate override table. Every effective field records its source and fetch time, with null time for rules and unknown values.

## Measurement

Measured 2026-10-11 in a Node 22 Alpine container using the owner's free key injected into its environment by vault-run.py. There were exactly **16 real requests**, ten species-list queries and six details requests, spaced at least six seconds apart. Only IDs, response statuses, rate headers and field-presence summaries were retained in the gitignored scratch directory. No raw payloads were saved.

| Query, scientific name | Selected ID | List status | Details status |
|---|---:|---:|---:|
| Monstera deliciosa | 5257 | 200 | 429 |
| Epipremnum aureum | 2773 | 200 | 200 |
| Ficus elastica | 2961 | 200 | 200 |
| Sansevieria trifasciata | 7171 | 200 | 429 |
| Zamioculcas zamiifolia | 8386 | 200 | Not requested |
| Spathiphyllum wallisii | No result | 200 | Not requested |
| Chlorophytum comosum | 1847 | 200 | 200 |
| Goeppertia orbifolia | No result | 200 | Not requested |
| Phalaenopsis | 5837 | 200 | Not requested |
| Aloe vera | 728 | 200 | 200 |

The first matching scientific name was selected, otherwise the first list result. Phalaenopsis was a genus query, so that result is not evidence of exact species coverage. Four of ten queries yielded accessible details. All four successful details had nonempty watering benchmark, sunlight, pruning months, hardiness, pet and human toxicity, lifecycle, dimensions and indoor fields. Three had nonempty pest susceptibility. Presence is not a claim that every value is usable.

All requests advertised `x-ratelimit-limit: 100`. The 16 calls in this ticket account for the observed remaining-quota sequence from 96 on the first response to 81 on the last, including the two rejected detail requests. The research session had spent about three requests earlier that same day, including a species-list call and a details request for ID 5257 that returned 429, according to the project handoff notes. The exact earlier count is not recorded. The first response already charged the first ticket call, so 96 was not a call-free starting balance. The 429s for IDs 5257 and 7171 were interleaved with successful low-ID detail calls at the same spacing. Neither had Retry-After or a diagnostic message identifying the cause. This supports an ID restriction rather than daily exhaustion. **The exact cause of HTTP 429 remains uncertain**, including any undisclosed short-term throttle. The adapter avoids details above the documented free-tier ID limit of 3000 and marks those species unavailable. A 429 for an otherwise eligible request retains the job and backs off.

## Consequences and licensing

Most sampled houseplants need fallback rules now and Open Plantbook later. Rule-based schedules are estimates, and the UI must say so. The default quota is 100 requests per UTC day and a conservative per-minute rate. Retry attempts are capped; the owner can explicitly request a refresh after a terminal failure.

Perenual's terms limit use to personal use and forbid redistribution and derivative uses. Runtime personal caching is a licensing grey area, not permission to publish a dataset. Retain only the mapped care fields needed by the household, not raw responses. Open Plantbook has no stated data licence in the research, so #10 must also cache only for personal use. GBIF names require CC BY 4.0 attribution. No real source payload belongs in this repository.

## Sources

- Research note, primary sources accessed 2026-10-11: `.scratch/research/plant-data-sources.md`.
- Perenual fields and routes: https://perenual.com/docs/api
- Perenual quota and IDs 1–3000: https://perenual.com/subscription-api-pricing
- Perenual personal-use restrictions: https://perenual.com/terms-of-service
- Open Plantbook schema: https://open.plantbook.io/api/schema/
- Open Plantbook care additions: https://github.com/slaxor505/OpenPlantbook-client
- GBIF backbone identity and licence: https://api.gbif.org/v1/dataset/d7dddbf4-2cf0-4f39-9b2a-bb099caae36c
- GBIF name matching: https://api.gbif.org/v1/species/match?name=Monstera%20delicosa&kingdom=Plantae
