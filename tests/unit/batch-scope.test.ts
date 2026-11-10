import { expect, test } from 'vitest';
import { batchScope, undoLabel, type CareEvent } from '../../src/lib/tasks';

test.each([
  ['Watered 9 plants', 9, { batch_action: 'complete-type', batch_task: 'water', batch_plant_count: 9, batch_event_count: 9 }],
  ['Watered 1 plant', 1, { batch_action: 'complete-type', batch_task: 'water', batch_plant_count: 1, batch_event_count: 1 }],
  ['Fertilized and watered 9 plants', 9, { batch_action: 'complete-type', batch_task: 'fertilize', batch_plant_count: 9, batch_event_count: 18 }],
  ['Fertilized 9 plants', 9, { batch_action: 'complete-type', batch_task: 'fertilize', batch_plant_count: 9, batch_event_count: 9 }],
  ['Synthetic misting done for 2 plants', 2, { batch_action: 'complete-type', batch_task: '00000000-0000-4000-8000-000000000001', batch_plant_count: 2, batch_event_count: 2 }],
  ['Handled 3 tasks for Synthetic fern', 3, { batch_action: 'complete-all', batch_task: null, batch_plant_count: 1, batch_event_count: 3 }],
  ['Handled 1 task for Synthetic fern', 1, { batch_action: 'complete-all', batch_task: null, batch_plant_count: 1, batch_event_count: 1 }],
  ['Watered 3 outdoor plants', 3, { batch_action: 'rain', batch_task: null, batch_plant_count: 3, batch_event_count: 3 }],
] as const)('a batched row says "%s" and Undo all %d, from the whole batch', (text, count, fields) => {
  const event = { batch_id: '00000000-0000-4000-8000-000000000002', ...fields } as unknown as CareEvent;
  expect(batchScope(event, 'Synthetic fern', 'Synthetic misting')).toEqual({ text, count });
});

test.each([
  ['Undo watering', { task_type: 'water', kind: 'completed', batch_id: null }],
  ['Undo fertilizing snooze', { task_type: 'fertilize', kind: 'snoozed', batch_id: null }],
  ['Undo repotting skip', { task_type: 'repot', kind: 'skipped', batch_id: null }],
  ['Undo Synthetic misting', { task_type: null, custom_task_name: 'Synthetic misting', kind: 'completed', batch_id: null }],
  ['Undo all 3', { task_type: 'water', kind: 'completed', batch_id: '00000000-0000-4000-8000-000000000002', batch_action: 'complete-all', batch_plant_count: 1, batch_event_count: 3 }],
  ['Undo all 9', { task_type: 'water', kind: 'completed', batch_id: '00000000-0000-4000-8000-000000000002', batch_action: 'complete-type', batch_task: 'water', batch_plant_count: 9, batch_event_count: 9 }],
  ['Undo all 2', { task_type: 'fertilize', kind: 'completed', batch_id: '00000000-0000-4000-8000-000000000002', batch_action: 'complete-type', batch_task: 'fertilize', batch_plant_count: 2, batch_event_count: 4 }],
  ['Undo all 5', { task_type: 'water', kind: 'completed', batch_id: '00000000-0000-4000-8000-000000000002', batch_action: 'rain', batch_task: null, batch_plant_count: 5, batch_event_count: 5 }],
] as const)('a history Undo says "%s"', (label, fields) => {
  expect(undoLabel(fields as unknown as CareEvent, [])).toBe(label);
});

test('a history Undo of liquid fertilizing names the water it also reverses, until that water is undone on its own', () => {
  const fertilize = { id: 'fertilize', task_type: 'fertilize', kind: 'completed', batch_id: null, undone_at: null } as unknown as CareEvent;
  const water = { id: 'water', task_type: 'water', kind: 'completed', batch_id: null, linked_event_id: 'fertilize', undone_at: null } as unknown as CareEvent;
  expect(undoLabel(fertilize, [water, fertilize])).toBe('Undo fertilizing and watering');
  expect(undoLabel(water, [water, fertilize])).toBe('Undo watering');
  expect(undoLabel(fertilize, [{ ...water, undone_at: '2026-06-10T12:00:00Z' }, fertilize])).toBe('Undo fertilizing');
});
