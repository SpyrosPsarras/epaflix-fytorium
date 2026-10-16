export const nameLanguages = { en: 'English', nb: 'Norwegian', el: 'Greek', la: 'Latin' } as const;
export type NameLanguage = keyof typeof nameLanguages;
export function vernacularName(names: { name: string; language: NameLanguage }[]): string | null {
  return names.find(item => item.language === 'en' && nameValidationError(item.name, item.language) === undefined)?.name.trim() ?? null;
}
export function nameValidationError(name: unknown, language: unknown): string | undefined {
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 200 || /[\u0000-\u001f\u007f]/.test(name)) return 'Enter a name of 1–200 characters without control characters.';
  if (typeof language !== 'string' || !Object.hasOwn(nameLanguages, language)) return 'Choose English, Norwegian, Greek or Latin.';
}
export type SpeciesName = { id: string; name: string; language: NameLanguage; source: 'gbif' | 'wikidata' | 'nortaxa' | 'owner' };
