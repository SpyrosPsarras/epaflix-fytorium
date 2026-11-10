import { expect, test } from 'vitest';
import { batchScope, type CareEvent } from '../../src/lib/tasks';

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
