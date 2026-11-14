import { error, json } from '@sveltejs/kit';
import { getSpecies } from '$lib/server/names';
import { pickCommonName } from '$lib/server/common-name';
import { db } from '$lib/server/db';
import { commonNameLanguages, type SpeciesName } from '$lib/names';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals, params }) => {
  if (!locals.identity) error(401, 'Login required');
  const species = await getSpecies(params.gbifKey);
  const shown = await db().query<{ common_name: string | null; unnamed: number }>(
    "SELECT species_common_name($1, $2) AS common_name, (SELECT count(*)::int FROM plants WHERE gbif_key = $1 AND household_id = $3 AND nullif(btrim(nickname), '') IS NULL) AS unnamed",
    [species.gbif_key, locals.identity.language, locals.identity.household.id]);
  const { common_name, unnamed } = shown.rows[0];
  const chosen = (names: SpeciesName[], language: string) => pickCommonName(species.canonical_name, names.filter(name => name.language === language));
  // plant_display_name falls back from the reader's language to English, then to the canonical name.
  const displayed = (names: SpeciesName[], language: string) => chosen(names, language) ?? chosen(names, 'en') ?? species.canonical_name;
  const renames = (name: SpeciesName) => {
    const rest = species.names.filter(other => other !== name);
    return commonNameLanguages.some(language => displayed(species.names, language) !== displayed(rest, language));
  };
  return json({ ...species, common_name, names: species.names.map(name => name.source === 'owner' ? { ...name, renamed_plant_count: renames(name) ? unnamed : 0 } : name) });
};
