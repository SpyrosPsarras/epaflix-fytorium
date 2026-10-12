import { error, redirect } from '@sveltejs/kit';
import type { Identity } from './auth';
import { db } from './db';
import { isUuid } from './plants';
import { taskTypes } from '$lib/schedule/engine';
import type { TaskList } from '$lib/tasks';

type Action = 'owner' | 'edit' | 'self' | 'tasks' | 'plant' | 'undo';
export type SitterProjection = 'self' | 'taskList' | 'plantHeader' | 'schedule' | 'task' | 'batch' | 'batchUndo';
// Every permission that admits sitters must name its response projection.
type Permission = 'owner' | 'edit' | { action: Exclude<Action, 'owner' | 'edit'>; projection: SitterProjection };
type RoutePolicy = Permission | { read: Permission; write: Permission };
export function requirePermission(identity: Identity | null, action: Action): Identity {
  if (!identity) error(401, 'Login required');
  if (action === 'owner' && identity.member.role !== 'owner') error(403, 'Only the owner can do this');
  if (action === 'edit' && identity.member.role === 'sitter') error(403, 'An owner or family member is required');
  return identity;
}

// The caller can reuse this SQL in a household-scoped query with p as the plant alias.
export function visiblePlantSql(memberParameter: string) {
  return `(p.status = 'active' AND EXISTS (SELECT 1 FROM members vm WHERE vm.id = ${memberParameter}
    AND vm.household_id = p.household_id AND vm.removed_at IS NULL
    AND (vm.role <> 'sitter' OR EXISTS (SELECT 1 FROM plant_shares ps
      WHERE ps.member_id = vm.id AND ps.plant_id = p.id AND ps.household_id = p.household_id))))`;
}
export async function visiblePlantsForIdentity(identity: Identity): Promise<string[]> {
  const result = await db().query(`SELECT p.id FROM plants p WHERE p.household_id = $1 AND ${visiblePlantSql('$2')}`, [identity.household.id, identity.member.id]);
  return result.rows.map(row => row.id);
}
export async function requireSharedPlant(identity: Identity, plantId: string) {
  if (identity.member.role !== 'sitter') return;
  if (!isUuid(plantId)) error(404, 'Plant not found');
  const result = await db().query(`SELECT p.id FROM plants p WHERE p.id = $1 AND p.household_id = $2 AND ${visiblePlantSql('$3')}`, [plantId, identity.household.id, identity.member.id]);
  if (!result.rowCount) error(404, 'Plant not found');
}
export async function requireOwnUndo(identity: Identity, id: string, batch = false) {
  if (identity.member.role !== 'sitter') return;
  if (!isUuid(id)) error(404, 'Care action not found');
  const result = await db().query(batch
    ? 'SELECT by_member, plant_id, action FROM care_actions WHERE request_id = $1 AND household_id = $2'
    : 'SELECT by_member, plant_id, previous_pot FROM care_events WHERE id = $1 AND household_id = $2', [id, identity.household.id]);
  const action = result.rows[0];
  if (!action || !action.plant_id) error(404, 'Care action not found');
  await requireSharedPlant(identity, action.plant_id);
  if (action.by_member !== identity.member.id || action.previous_pot || (batch && action.action !== 'complete-all')) error(403, 'Sitters can undo only their own task actions');
}

// Public handlers retain their own origin, login-transaction, token and test-mode checks.
export function publicRoute(route: string) {
  return route.startsWith('/auth/') || route === '/invite/[token]' || route === '/healthz'
    || route === '/metrics' || route.startsWith('/__test/');
}
const policies: Record<string, RoutePolicy> = {
  '/': { action: 'tasks', projection: 'taskList' },
  '/upcoming': { action: 'tasks', projection: 'taskList' },
  '/api/tasks': { action: 'tasks', projection: 'taskList' },
  '/members': 'owner', '/import': 'owner', '/settings': 'owner',
  '/api/household/invites': 'owner', '/api/household/invites/[id]': 'owner',
  '/api/household/members': 'owner', '/api/household/members/[id]': 'owner',
  '/api/import/planta': 'owner', '/api/import/planta/[id]': 'owner', '/api/import/planta/commit': 'owner',
  '/api/household/settings': { read: 'edit', write: 'owner' },
  '/api/household/location': { read: 'edit', write: 'owner' },
  '/api/me': { action: 'self', projection: 'self' }, '/settings/notifications': { action: 'self', projection: 'self' },
  '/api/notifications/key': { action: 'self', projection: 'self' }, '/api/notifications/settings': { action: 'self', projection: 'self' },
  '/api/notifications/subscriptions': { action: 'self', projection: 'self' },
  '/api/notifications/subscriptions/[id]': { action: 'self', projection: 'self' }, '/api/notifications/test': { action: 'self', projection: 'self' },
  '/plants/[id]': { read: { action: 'plant', projection: 'plantHeader' }, write: 'edit' },
  '/api/plants/[id]': { read: { action: 'plant', projection: 'plantHeader' }, write: 'edit' },
  '/api/plants/[id]/tasks': { read: { action: 'plant', projection: 'schedule' }, write: 'edit' },
  '/api/plants/[id]/tasks/[taskType]/[action]': { action: 'plant', projection: 'task' },
  '/api/plants/[id]/tasks/complete-all': { action: 'plant', projection: 'batch' },
  '/api/care-events/[id]/undo': { action: 'undo', projection: 'task' },
  '/api/task-batches/[id]/undo': { action: 'undo', projection: 'batchUndo' },
  '/plants': 'edit', '/plants/new': 'edit', '/plants/graveyard': 'edit',
  '/plants/[id]/settings': 'edit', '/plants/[id]/updates': 'edit',
  '/plants/[id]/repot': 'edit', '/plants/[id]/species': 'edit',
  '/sites': 'edit', '/sites/new': 'edit', '/sites/[id]': 'edit', '/sites/[id]/edit': 'edit',
  '/species/[gbifKey]': 'edit', '/species/unmatched': 'edit', '/journal': 'edit',
  '/api/plants': 'edit', '/api/plants/[id]/care-settings': 'edit', '/api/plants/[id]/graveyard': 'edit',
  '/api/plants/[id]/history': 'edit', '/api/plants/[id]/photos': 'edit', '/api/plants/[id]/repot': 'edit',
  '/api/plants/[id]/restore': 'edit', '/api/plants/[id]/species': 'edit', '/api/plants/[id]/species-changes': 'edit',
  '/api/sites': 'edit', '/api/sites/[id]': 'edit', '/api/custom-tasks': 'edit', '/api/custom-tasks/[id]': 'edit',
  '/api/journal': 'edit', '/api/photos/[id]': 'edit', '/api/photos/[id]/[variant]': 'edit', '/api/photos/[id]/cover': 'edit',
  '/api/identification': 'edit', '/api/species': 'edit', '/api/species/search': 'edit', '/api/species/unmatched': 'edit',
  '/api/species/[gbifKey]': 'edit', '/api/species/[gbifKey]/care': 'edit',
  '/api/species/[gbifKey]/care/overrides/[field]': 'edit', '/api/species/[gbifKey]/care/refresh': 'edit',
  '/api/species/[gbifKey]/care/retry-match': 'edit', '/api/species/[gbifKey]/names': 'edit',
  '/api/species/[gbifKey]/names/[id]': 'edit', '/api/species/[gbifKey]/names/refresh': 'edit',
  '/api/species/[gbifKey]/outdoor-months': 'edit', '/api/tasks/settings': 'edit',
  '/api/weather': 'edit', '/api/weather/rain/water': 'edit',
};

function routePermission(route: string, method: string): Permission | undefined {
  const policy = Object.hasOwn(policies, route) ? policies[route] : undefined;
  return policy && typeof policy === 'object' && 'read' in policy
    ? method === 'GET' || method === 'HEAD' ? policy.read : policy.write
    : policy;
}
export function sitterProjection(route: string, method = 'GET'): SitterProjection {
  const permission = routePermission(route, method);
  if (!permission || typeof permission === 'string') error(500, 'Missing sitter projection');
  return permission.projection;
}

// Called before every handler and page load, including internal fetches. Unlisted routes are denied.
export async function authorizeRoute(identity: Identity | null, route: string, method: string, params: Record<string, string>) {
  if (publicRoute(route)) return;
  const permission = routePermission(route, method);
  if (!permission) {
    console.warn(`Missing authorization policy for route ${route}`);
    error(404, 'Not found');
  }
  // The landing page shows only a login link without an identity.
  if (route === '/' && !identity && (method === 'GET' || method === 'HEAD')) return;
  if (!identity && (route === '/species/[gbifKey]' || route === '/upcoming')) redirect(303, '/auth/login');
  const action = typeof permission === 'string' ? permission : permission.action;
  const member = requirePermission(identity, action);
  if (action === 'plant') await requireSharedPlant(member, params.id);
  if (action === 'undo') await requireOwnUndo(member, params.id, route === '/api/task-batches/[id]/undo');
  return typeof permission === 'string' ? undefined : permission.projection;
}

function fields(resource: Record<string, unknown>, names: string[]) {
  return Object.fromEntries(names.filter(name => Object.hasOwn(resource, name)).map(name => [name, resource[name]]));
}
function record(resource: unknown): Record<string, unknown> {
  return resource && typeof resource === 'object' && !Array.isArray(resource) ? resource as Record<string, unknown> : {};
}
function sitterEvent(resource: unknown) {
  return fields(record(resource), ['id', 'plant_id', 'task_type', 'custom_task_id', 'custom_task_name', 'kind',
    'occurred_at', 'occurred_on', 'batch_id', 'by_member', 'by_name', 'undone_at', 'undone_by', 'request_id']);
}
function sitterTask(resource: unknown) {
  const task = record(resource);
  return { ...fields(task, ['status', 'dueOn', 'intervalDays', 'lateDays', 'name', 'custom_task_id', 'custom_task_name', 'task_type', 'plant_id', 'plant_name']),
    basedOn: [], ...(Object.hasOwn(task, 'site_name') ? { site_name: '' } : {}),
    ...(task.completed_event ? { completed_event: sitterEvent(task.completed_event) } : {}) };
}
function sitterSchedule(resource: unknown) {
  const schedule = record(resource);
  return { ...Object.fromEntries(taskTypes.filter(type => Object.hasOwn(schedule, type)).map(type => [type, sitterTask(schedule[type])])),
    customTasks: Object.fromEntries(Object.entries(record(schedule.customTasks)).map(([id, task]) => [id, sitterTask(task)])) };
}

// Build every sitter response from named fields. New source fields are private until added here.
export function sitterView(resource: TaskList, projection: SitterProjection): TaskList;
export function sitterView(resource: Record<string, unknown>, projection: SitterProjection): Record<string, unknown>;
export function sitterView(resource: unknown, projection: SitterProjection): unknown;
export function sitterView(resource: unknown, projection: SitterProjection): unknown {
  const value = record(resource);
  switch (projection) {
  // These endpoints contain only the member's own identity and notification settings.
  case 'self': return resource;
  case 'taskList': {
    const tasks = (rows: unknown) => Array.isArray(rows) ? rows.map(sitterTask) : [];
    return { ...fields(value, ['today', 'view']), settings: fields(record(value.settings), ['sort', 'filter', 'show_completed']),
      groups: Object.fromEntries(Object.entries(record(value.groups)).map(([key, rows]) => [key, tasks(rows)])),
      sections: (Array.isArray(value.sections) ? value.sections : []).map(section => ({
        ...fields(record(section), ['key', 'label']), tasks: tasks(record(section).tasks),
      })) };
  }
  case 'plantHeader': return fields(value, ['id', 'display_name', 'nickname', 'scientific_name', 'canonical_name', 'cultivar', 'birthday', 'status']);
  case 'task': return { event: sitterEvent(value.event), task: sitterTask(value.task) };
  case 'batch': return { events: (Array.isArray(value.events) ? value.events : []).map(sitterEvent), tasks: sitterSchedule(value.tasks) };
  case 'batchUndo': return fields(value, ['batchId', 'undoneAt']);
  case 'schedule': return sitterSchedule(value);
  default: error(500, 'Missing sitter projection');
  }
}
