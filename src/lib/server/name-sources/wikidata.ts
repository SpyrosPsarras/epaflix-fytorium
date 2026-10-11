import { config } from '../config';
import { sourceJson, sourceUrl, type NameSource, type FetchedName } from './shared';
export const wikidataNames: NameSource = {
  source: 'wikidata',
  async fetchNames(species) {
    const url = sourceUrl(config().wikidataBaseUrl, 'w/api.php');
    url.search = new URLSearchParams({ action: 'query', list: 'search', srsearch: 'haswbstatement:P846=' + species.gbif_key, srnamespace: '0', srlimit: '2', format: 'json' }).toString();
    const result = await sourceJson(url);
    if (!Array.isArray(result.query?.search)) throw new Error('Invalid Wikidata search');
    if (result.query.search.length !== 1) return [];
    const id = result.query.search[0].title;
    if (typeof id !== 'string' || !/^Q[1-9][0-9]*$/.test(id)) throw new Error('Invalid Wikidata item');
    url.search = new URLSearchParams({ action: 'wbgetentities', ids: id, props: 'labels|aliases', languages: 'en|nb|el', format: 'json' }).toString();
    const payload = await sourceJson(url);
    const entity = payload.entities?.[id];
    if (!entity || entity.missing !== undefined) throw new Error('Missing Wikidata item');
    const names: FetchedName[] = [];
    for (const lang of ['en', 'nb', 'el'] as const) {
      const label = entity.labels?.[lang];
      if (label) names.push({ name: label.value, language: lang });
      for (const alias of entity.aliases?.[lang] ?? []) names.push({ name: alias.value, language: lang });
    }
    return names;
  },
};
