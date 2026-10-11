import { error, json } from '@sveltejs/kit';
import { complete, readTaskInput } from '$lib/server/tasks';
import { taskTypes } from '$lib/schedule/engine';
import { isUuid } from '$lib/server/plants';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const taskType = taskTypes.find(type => type === params.taskType) ?? (isUuid(params.taskType) ? params.taskType.toLowerCase() : null);
  if (!taskType) error(404, 'Task type not found');
  const kind = params.action === 'complete' ? 'completed' : params.action === 'snooze' ? 'snoozed' : params.action === 'skip' ? 'skipped' : null;
  if (!kind) error(404, 'Task action not found');
  const input = await readTaskInput(request);
  if (typeof input.request_id !== 'string') error(422, 'Send a UUID request_id.');
  return json(await complete(locals.identity, params.id, taskType, input.request_id, kind));
};
