type CommonName = { source: string; name: string };
const letters = (name: string) => name.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
export const squash = (name: string) => letters(name).replace(/[^\p{L}]/gu, '');
const alphabetical = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0;
const display = (name: string) => name.charAt(0).toUpperCase() + name.slice(1);

export function pickCommonName(species: string, candidates: CommonName[]): string | null {
  const excluded = new Set([squash(species), squash(species.trim().split(/\s+/)[0])]);
  const names = candidates.flatMap(row => row.name.split(/[,;]/).map(name => ({ source: row.source, name: name.trim() })))
    .filter(row => row.name && !excluded.has(squash(row.name)));
  for (const source of ['owner', 'nortaxa']) {
    const preferred = names.filter(row => row.source === source)
      .sort((a, b) => a.name.length - b.name.length || alphabetical(a.name, b.name));
    if (preferred.length) return display(preferred[0].name.replace(/-/g, ' '));
  }
  const external = names.filter(row => row.source === 'wikidata' || row.source === 'gbif');
  const groups = new Map<string, string[]>();
  for (const row of external) {
    const key = squash(row.name);
    const group = groups.get(key) ?? [];
    group.push(row.name);
    groups.set(key, group);
  }
  const frequency = (group: string[]) => {
    const words = letters(group[0]).match(/\p{L}{3,}/gu) ?? [];
    return words.length ? words.reduce((sum, word) => sum + external.filter(row => letters(row.name).includes(word)).length, 0) / words.length : 0;
  };
  const selected = [...groups].sort(([a, namesA], [b, namesB]) =>
    namesB.length - namesA.length || frequency(namesB) - frequency(namesA) || a.length - b.length || alphabetical(a, b))[0];
  if (!selected) return null;
  const spellings = new Map<string, number>();
  for (const name of selected[1]) {
    const spelling = name.replace(/-/g, ' ');
    spellings.set(spelling, (spellings.get(spelling) ?? 0) + 1);
  }
  return display([...spellings].sort(([a, countA], [b, countB]) =>
    countB - countA || Number(b.includes(' ')) - Number(a.includes(' ')) || alphabetical(a, b))[0][0]);
}
