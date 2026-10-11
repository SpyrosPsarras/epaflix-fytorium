import { error, json } from '@sveltejs/kit';
import { complete } from '$lib/server/tasks';
import { taskTypes } from '$lib/schedule/engine';
import { checkOrigin } from '$lib/server/sites';
import type { RequestHandler } from './$types';
export const POST: RequestHandler = async ({ locals, params, request }) => {
  if (!locals.identity) error(401, 'Login required');
  checkOrigin(request);
  const taskType = taskTypes.find(type => type === params.taskType);
  if (!taskType) error(404, 'Task type not found');
  let input;
  try {
    if (request.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('Expected JSON');
    input = await request.json();
  } catch { error(400, 'Send a valid JSON object.'); }
  if (!input || typeof input !== 'object' || Array.isArray(input)) error(400, 'Send a valid JSON object.');
  if (typeof input.request_id !== 'string') error(422, 'Send a UUID request_id.');
  return json(await complete(locals.identity, params.id, taskType, input.request_id));
};
