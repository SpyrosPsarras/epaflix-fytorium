import { expect, test, vi } from 'vitest';
import type { ImportedFields } from '../../src/lib/import';

vi.mock('../../src/lib/server/config', () => ({ config: () => ({}) }));
import { importNickname } from '../../src/lib/server/planta/import';

const fields: ImportedFields = { nickname: null, cultivar: null, scientific_name: 'Testoria lucida', site_name: 'Synthetic site',
  pot_type: 'plastic', pot_size_cm: null, soil_type: 'all_purpose', care: {}, cover_url: null };
const species = { scientific_name: 'Testoria lucida Inventor', canonical_name: 'Testoria lucida' };

test('staging rows saved before custom and localized names keep their stored nickname at commit', () => {
  expect(importNickname({ ...fields, nickname: 'Kitchen fern' }, species)).toBe('Kitchen fern');
  expect(importNickname({ ...fields, nickname: 'Testoria-lucida' }, species)).toBeNull();
});

test('newer staging rows prefer the custom name, then a localized name that is not the species name', () => {
  const row = { ...fields, nickname: 'Kitchen fern', localized_name: 'Synthetic leaf' };
  expect(importNickname({ ...row, custom_name: 'My fern' }, species)).toBe('My fern');
  expect(importNickname(row, species)).toBe('Synthetic leaf');
  expect(importNickname({ ...row, localized_name: 'TESTORIA LUCIDA' }, species)).toBeNull();
});
