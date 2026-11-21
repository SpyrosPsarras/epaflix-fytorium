import { config } from '../config';
import { now } from '../clock';
import { commonNameLanguages, nameValidationError } from '$lib/names';
import { sourceUrl, taxonomyFetch, type NameSource, type FetchedName } from './shared';

// shortcut: limits are per process; share the counters before scaling to multiple replicas.
// Each request keeps its test-clock and real time: tests move the clock, and a frozen lab clock must not hold the limit once a real minute passes.
let requests: { at: number; real: number }[] = [];
let retryAt = 0;
let retryReal = 0;
export async function wikidataJson(params: Record<string, string>, deadline?: AbortSignal) {
  const time = now().getTime();
  const real = Date.now();
  if (config().testClockEnabled && requests.some(request => request.at > time)) { requests = []; retryAt = 0; }
  requests = requests.filter(request => time - request.at < 60000 && real - request.real < 60000);
  if ((time < retryAt && real < retryReal) || requests.length >= 20) throw new Error('Wikidata request limit');
  requests.push({ at: time, real });
  const url = sourceUrl(config().wikidataBaseUrl, 'w/api.php');
  url.search = new URLSearchParams({ ...params, format: 'json' }).toString();
  const response = await taxonomyFetch(url, deadline);
  if (response.status === 429) {
    const retry = response.headers.get('retry-after');
    const received = now().getTime();
    const next = retry && /^\d+$/.test(retry) ? received + Number(retry) * 1000 : Date.parse(retry ?? '');
    retryAt = Math.max(retryAt, Number.isFinite(next) ? next : received + 60000);
    retryReal = Date.now() + (retryAt - received);
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
