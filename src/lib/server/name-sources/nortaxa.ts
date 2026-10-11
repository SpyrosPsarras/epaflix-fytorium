import { config } from '../config';
import { language, sourceJson, sourceUrl, type NameSource } from './shared';
export const nortaxaNames: NameSource = {
  source: 'nortaxa',
  async fetchNames(species) {
    const url = sourceUrl(config().nortaxaBaseUrl, 'api/v1/TaxonName/Search');
    url.search = new URLSearchParams({ Search: species.canonical_name, IncludeVernacularNames: 'true', MaxResults: '200' }).toString();
    const payload = await sourceJson(url);
    if (!Array.isArray(payload)) throw new Error('Invalid NorTaxa names');
    const matches = payload.filter(row => [row.acceptedScientificName, ...(row.scientificNameSynonyms ?? [])]
      .some(name => typeof name?.name === 'string' && name.name.toLowerCase() === species.canonical_name.toLowerCase()));
    const taxa = new Set(matches.map(row => row.taxonId));
    if (taxa.size !== 1 || !Number.isInteger(matches[0]?.taxonId) || payload.length >= 200) return [];
    return matches.flatMap(row => [...(row.preferredVernacularNames ?? []), ...(row.vernacularNameSynonyms ?? [])])
      .flatMap((row: { name: string; nameLanguageIso: string }) => language(row.nameLanguageIso) === 'nb' ? [{ name: row.name, language: 'nb' as const }] : []);
  },
};
