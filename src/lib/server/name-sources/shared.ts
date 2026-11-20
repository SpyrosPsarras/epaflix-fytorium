import type { NameLanguage } from '$lib/names';
import { config } from '../config';
export type NameSpecies = { gbif_key: number; canonical_name: string };
export type FetchedName = { name: string; language: NameLanguage };
export type NameSource = { source: 'gbif' | 'wikidata' | 'nortaxa'; fetchNames: (species: NameSpecies) => Promise<FetchedName[]> };
export function sourceUrl(base: URL, path: string) {
  return new URL(path, base.href.endsWith('/') ? base.href : base.href + '/');
}
export function taxonomyFetch(url: URL, deadline?: AbortSignal) {
  const timeout = AbortSignal.timeout(3000);
  return fetch(url, { signal: deadline ? AbortSignal.any([deadline, timeout]) : timeout,
    headers: { accept: 'application/json', 'User-Agent': `Fytorium (+${config().origin.origin})` } });
}
export async function sourceJson(url: URL) {
  const response = await taxonomyFetch(url);
  if (!response.ok) throw new Error('Name source unavailable');
  return response.json();
}
export function language(value: string): NameLanguage | undefined {
  return ({ en: 'en', eng: 'en', nb: 'nb', no: 'nb', nob: 'nb', nor: 'nb', el: 'el', ell: 'el', gre: 'el' } as Record<string, NameLanguage>)[value];
}
