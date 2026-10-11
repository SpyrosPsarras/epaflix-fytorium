import { afterEach, expect, test, vi } from 'vitest';
import sharp from 'sharp';
import { addPhoto } from '../../src/lib/server/photos';
import type { Identity } from '../../src/lib/server/auth';

const faults = vi.hoisted(() => ({ query: vi.fn(), removePhotos: vi.fn(), storePhoto: vi.fn(), release: vi.fn() }));
vi.mock('../../src/lib/server/db', () => ({ db: () => ({ connect: async () => ({ query: faults.query, release: faults.release }) }) }));
vi.mock('../../src/lib/server/plants', () => ({ getPlant: async () => ({}), isUuid: () => true }));
vi.mock('../../src/lib/server/photo-storage', () => ({ storePhoto: faults.storePhoto, removePhotos: faults.removePhotos }));
vi.mock('../../src/lib/server/config', () => ({ config: () => ({ householdTimezone: 'Europe/Oslo' }) }));
vi.mock('../../src/lib/server/clock', () => ({ now: () => new Date('2026-10-25T12:00:00Z') }));
vi.mock('../../src/lib/server/tasks', () => ({ calendarDate: () => '2026-10-25' }));
afterEach(() => vi.restoreAllMocks());

for (const cleanupFails of [false, true]) {
  test('upload preserves its DB error and attempts object cleanup when rollback fails' + (cleanupFails ? ' and storage cleanup fails' : ''), async () => {
    const original = new Error('Synthetic insert failure');
    faults.query.mockReset().mockImplementation(async (sql: string) => {
      if (sql.startsWith('INSERT')) throw original;
      if (sql === 'ROLLBACK') throw new Error('Synthetic rollback failure with secret');
      return { rowCount: 1, rows: [] };
    });
    faults.storePhoto.mockReset().mockResolvedValue(undefined);
    faults.removePhotos.mockReset().mockImplementation(async () => {
      if (cleanupFails) throw new Error('Synthetic storage failure with secret');
    });
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const bytes = await sharp({ create: { width: 2, height: 2, channels: 3, background: '#408040' } }).jpeg().toBuffer();
    const form = new FormData();
    form.set('photo', new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' }), 'synthetic.jpg');
    const identity = { household: { id: 'synthetic-household' }, member: { id: 'synthetic-member' } } as Identity;
    await expect(addPhoto(identity, 'synthetic-plant', new Request('http://test/upload', { method: 'POST', body: form }))).rejects.toBe(original);
    expect(faults.removePhotos).toHaveBeenCalledWith(faults.storePhoto.mock.calls.map(([key]) => key));
    expect(log.mock.calls.flat().join(' ')).not.toContain('secret');
    expect(log).toHaveBeenCalledTimes(cleanupFails ? 2 : 1);
  });
}
