import { dark, light } from '../theme';

const luminance = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

test.each([['light', light], ['dark', dark]] as const)('%s text colours reach 4.5:1 on their backgrounds', (_, colors) => {
  const pairs = Object.keys(colors).filter((key) => key.startsWith('on'))
    .map((key) => [key, key[2].toLowerCase() + key.slice(3)] as const)
    .filter(([, base]) => base in colors);
  expect(pairs.length).toBeGreaterThan(5);
  for (const [text, base] of pairs) {
    expect([text, contrast(colors[text as keyof typeof colors], colors[base as keyof typeof colors]) >= 4.5]).toEqual([text, true]);
  }
});
