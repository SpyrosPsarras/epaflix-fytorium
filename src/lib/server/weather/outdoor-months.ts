import type { OutdoorMonths } from '$lib/weather';
import { hardinessRules } from '$lib/schedule/rules';
import { db } from '../db';
import { effectiveCareProfiles, getCareSpecies } from '../care/catalogue';
import { groundHardiness } from './hardiness';

export async function outdoorMonths(key: string, household: string): Promise<OutdoorMonths> {
  const species = await getCareSpecies(key, household);
  const [profiles, location, cache] = await Promise.all([
    effectiveCareProfiles([species.gbif_key], household),
    db().query('SELECT latitude FROM households WHERE id = $1', [household]),
    db().query("SELECT * FROM weather_cache WHERE household_id = $1 AND kind = 'climate'", [household]),
  ]);
  const hardiness = groundHardiness(profiles.get(species.gbif_key)!);
  const groundThresholdC = hardiness.value, potThresholdC = groundThresholdC + hardinessRules.potOffsetC;
  const climate = cache.rows[0];
  const minima: number[] | null = climate?.data ?? null;
  const months = (threshold: number) => minima?.flatMap((min, i) => min >= threshold + hardinessRules.outdoorMarginC ? [i + 1] : []) ?? null;
  return { locationSet: location.rows[0].latitude !== null, hardiness, groundThresholdC, potThresholdC,
    pot: months(potThresholdC), ground: months(groundThresholdC), marginC: hardinessRules.outdoorMarginC,
    ruleId: hardinessRules.outdoorMonthsId, potRuleId: hardinessRules.potId,
    fetchedAt: climate?.fetched_at?.toISOString() ?? null, unavailableSince: climate?.unavailable_since?.toISOString() ?? null };
}
