export const nameLanguages = { en: 'English', nb: 'Norwegian', el: 'Greek', la: 'Latin' } as const;
export type NameLanguage = keyof typeof nameLanguages;
export function requestLanguage(header: string | null): 'en' | 'nb' | 'el' {
  const preferences = (header?.split(',') ?? []).map(entry => {
    const [tag, ...parameters] = entry.trim().toLowerCase().split(';');
    const quality = parameters.map(parameter => parameter.trim()).find(parameter => parameter.startsWith('q='));
    return { language: tag.trim().split('-')[0], weight: quality === undefined ? 1 : Number(quality.slice(2)) };
  }).filter(({ weight }) => weight > 0 && weight <= 1).sort((a, b) => b.weight - a.weight);
  for (const { language } of preferences) {
    if (language === 'no' || language === 'nn') return 'nb';
    if (language === 'en' || language === 'nb' || language === 'el') return language;
  }
  return 'en';
}
export function vernacularName(names: { name: string; language: NameLanguage }[]): string | null {
  return names.find(item => item.language === 'en' && nameValidationError(item.name, item.language) === undefined)?.name.trim() ?? null;
}
export function nameValidationError(name: unknown, language: unknown): string | undefined {
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 200 || /[\u0000-\u001f\u007f]/.test(name)) return 'Enter a name of 1–200 characters without control characters.';
  if (typeof language !== 'string' || !Object.hasOwn(nameLanguages, language)) return 'Choose English, Norwegian, Greek or Latin.';
}
export type SpeciesName = { id: string; name: string; language: NameLanguage; source: 'gbif' | 'wikidata' | 'nortaxa' | 'owner' };
