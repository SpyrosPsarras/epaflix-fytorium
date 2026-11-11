import { config } from '../config';
import { language, sourceJson, sourceUrl, type NameSource } from './shared';
export const gbifNames: NameSource = {
  source: 'gbif',
  async fetchNames(species) {
    const url = sourceUrl(config().gbifBaseUrl, 'species/' + species.gbif_key + '/vernacularNames');
    url.searchParams.set('limit', '500');
    const payload = await sourceJson(url);
    if (!Array.isArray(payload.results)) throw new Error('Invalid GBIF names');
    return payload.results.flatMap((row: { vernacularName: string; language: string }) => {
      const lang = language(row.language);
      if (!lang) return [];
      if (typeof row.vernacularName !== 'string') throw new Error('Invalid GBIF name');
      return row.vernacularName.split(/[,;]/).map(name => name.trim()).filter(Boolean).map(name => ({ name, language: lang }));
    });
  },
};
