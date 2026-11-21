import { config } from '../config';
import { now } from '../clock';
import { commonNameLanguages, nameValidationError } from '$lib/names';
import { sourceUrl, taxonomyFetch, type NameSource, type FetchedName } from './shared';

// shortcut: limits are per process; share the counters before scaling to multiple replicas.
let requests: number[] = [];
let retryAt = 0;
export async function wikidataJson(params: Record<string, string>, deadline?: AbortSignal) {
  const time = now().getTime();
  if (config().testClockEnabled && requests.some(at => at > time)) { requests = []; retryAt = 0; }
  requests = requests.filter(at => time - at < 60000);
  if (time < retryAt || requests.length >= 20) throw new Error('Wikidata request limit');
  requests.push(time);
  const url = sourceUrl(config().wikidataBaseUrl, 'w/api.php');
  url.search = new URLSearchParams({ ...params, format: 'json' }).toString();
  const response = await taxonomyFetch(url, deadline);
  if (response.status === 429) {
    const retry = response.headers.get('retry-after');
    const received = now().getTime();
    const next = retry && /^\d+$/.test(retry) ? received + Number(retry) * 1000 : Date.parse(retry ?? '');
    retryAt = Math.max(retryAt, Number.isFinite(next) ? next : received + 60000);
  }
  if (!response.ok) throw new Error('Wikidata unavailable');
  return response.json();
}

type WikidataEntity = { labels?: Record<string, { value?: unknown }>; aliases?: Record<string, { value?: unknown }[]> };
export function wikidataNamesFrom(entity: WikidataEntity): FetchedName[] {
  return commonNameLanguages.flatMap(language => {
    const aliases = entity.aliases?.[language];
    const values = [entity.labels?.[language], ...(Array.isArray(aliases) ? aliases : [])];
    return values.flatMap(row => typeof row?.value === 'string' && !nameValidationError(row.value, language)
      ? [{ name: row.value.trim(), language }] : []);
  });
}
export const wikidataNames: NameSource = {
  source: 'wikidata',
  async fetchNames(species) {
    const result = await wikidataJson({ action: 'query', list: 'search', srsearch: 'haswbstatement:P846=' + species.gbif_key, srnamespace: '0', srlimit: '2' });
    if (!Array.isArray(result.query?.search)) throw new Error('Invalid Wikidata search');
    if (result.query.search.length !== 1) return [];
    const id = result.query.search[0].title;
    if (typeof id !== 'string' || !/^Q[1-9][0-9]*$/.test(id)) throw new Error('Invalid Wikidata item');
    const payload = await wikidataJson({ action: 'wbgetentities', ids: id, props: 'labels|aliases', languages: commonNameLanguages.join('|') });
    const entity = payload.entities?.[id];
    if (!entity || entity.missing !== undefined) throw new Error('Missing Wikidata item');
    return wikidataNamesFrom(entity);
  },
};
