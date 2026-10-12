import { json } from '@sveltejs/kit';
import { members } from '$lib/server/household';
import { requirePermission } from '$lib/server/policy';
import type { RequestHandler } from './$types';
export const GET: RequestHandler = async ({ locals }) => json(await members(requirePermission(locals.identity, 'owner')));
