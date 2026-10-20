import { error } from '@sveltejs/kit';
import type pg from 'pg';
import type { Identity } from './auth';
import { db } from './db';
import { isUuid } from './plants';

type Database = pg.Pool | pg.PoolClient;
type Denial = { status: 403 | 404 | 409; message: string };
type Pot = { pot_type: string; pot_size_cm: number | null; soil_type: string };
type UndoEvent = {
  id: string; plant_id: string; request_id: string; batch_id: string | null;
  by_member: string; undone_at: Date | null; previous_pot: Pot | null; event_order: string;
};
type UndoAction = {
  request_id: string; plant_id: string | null; batch_id: string | null;
  action: string; by_member: string; undone_at: Date | null; payload: Pot;
};
type UndoPlant = Pot & { id: string; status: string; shared: boolean };
type UndoContext = {
  identity: Identity; events: Map<string, UndoEvent>; actions: Map<string, UndoAction>;
  plants: Map<string, UndoPlant>; batchPlants: Map<string, Set<string>>; latestRepots: Map<string, string>;
};

// Commands load this context again while holding their plant and action locks.
async function undoContext(identity: Identity, events: UndoEvent[], batchIds: string[], database: Database): Promise<UndoContext> {
  const household = identity.household.id;
  const requestIds = [...new Set([...batchIds, ...events.map(event => event.request_id)])];
  const [actions, batchEvents] = await Promise.all([
    database.query<UndoAction>('SELECT * FROM care_actions WHERE request_id = ANY($1::uuid[]) AND household_id = $2', [requestIds, household]),
    database.query<{ batch_id: string; plant_id: string }>('SELECT DISTINCT batch_id, plant_id FROM care_events WHERE batch_id = ANY($1::uuid[]) AND household_id = $2', [batchIds, household]),
  ]);
  const batchPlants = new Map(batchIds.map(id => [id, new Set<string>()]));
  for (const event of batchEvents.rows) batchPlants.get(event.batch_id)!.add(event.plant_id);
  for (const action of actions.rows) if (action.plant_id) batchPlants.get(action.request_id)?.add(action.plant_id);
  const plantIds = [...new Set([...events.map(event => event.plant_id), ...[...batchPlants.values()].flatMap(ids => [...ids])])];
  const [plants, repots] = await Promise.all([
    database.query<UndoPlant>(`SELECT p.*, EXISTS (SELECT 1 FROM members m
      WHERE m.id = $3 AND m.household_id = p.household_id AND m.removed_at IS NULL
      AND (m.role <> 'sitter' OR EXISTS (SELECT 1 FROM plant_shares s
        WHERE s.member_id = m.id AND s.household_id = p.household_id AND s.plant_id = p.id))) AS shared
      FROM plants p WHERE p.id = ANY($1::uuid[]) AND p.household_id = $2`, [plantIds, household, identity.member.id]),
    database.query<{ plant_id: string; event_order: string }>(`SELECT plant_id, max(event_order) AS event_order FROM care_events
      WHERE plant_id = ANY($1::uuid[]) AND household_id = $2 AND previous_pot IS NOT NULL AND undone_at IS NULL GROUP BY plant_id`, [plantIds, household]),
  ]);
  return { identity, events: new Map(events.map(event => [event.id, event])), actions: new Map(actions.rows.map(action => [action.request_id, action])),
    plants: new Map(plants.rows.map(plant => [plant.id, plant])), batchPlants, latestRepots: new Map(repots.rows.map(event => [event.plant_id, event.event_order])) };
}

// One pure rule supplies both can_undo and the endpoint's status and message.
export function undoDenialFrom(context: UndoContext, id: string, batch = false): Denial | null {
  const deny = (status: Denial['status'], message: string): Denial => ({ status, message });
  const sitter = context.identity.member.role === 'sitter';
  const action = batch ? context.actions.get(id) : context.events.get(id);
  if (!action || (sitter && !action.plant_id)) return deny(404, sitter ? 'Care action not found' : batch ? 'Task batch not found' : 'Care event not found');
  const plant = action.plant_id ? context.plants.get(action.plant_id) : undefined;
  if (sitter) {
    if (!plant || plant.status !== 'active' || !plant.shared) return deny(404, 'Plant not found');
    if (action.by_member !== context.identity.member.id || ('previous_pot' in action && action.previous_pot) || (batch && 'action' in action && !['complete-all', 'complete-type'].includes(action.action))) {
      return deny(403, 'Sitters can undo only their own task actions');
    }
  }
  if (batch) {
    if (!('action' in action) || !['complete-all', 'complete-type', 'rain'].includes(action.action) || action.batch_id) return deny(404, 'Task batch not found');
    if (action.undone_at) return deny(409, 'Task batch already undone');
    if ([...context.batchPlants.get(id) ?? []].some(plantId => context.plants.get(plantId)?.status !== 'active')) {
      return deny(409, 'Restore graveyard plants before undoing their care.');
    }
  } else {
    if (action.batch_id) return deny(409, 'Undo this care action as one batch.');
    if (!plant) return deny(404, 'Plant not found');
    if (plant.status !== 'active') return deny(409, 'Graveyard plants have no tasks. Restore the plant first.');
    if (action.undone_at) return deny(409, 'Care event already undone');
    if ('previous_pot' in action && action.previous_pot) {
      const latest = context.latestRepots.get(plant.id);
      if (latest && BigInt(latest) > BigInt(action.event_order)) return deny(409, 'Undo the later repot first.');
      const repot = context.actions.get(action.request_id);
      const applied = repot?.action === 'repot' ? repot.payload : undefined;
      if (!applied || plant.pot_type !== applied.pot_type || plant.pot_size_cm !== applied.pot_size_cm || plant.soil_type !== applied.soil_type) {
        return deny(409, 'Pot fields changed after this repot. Undo would overwrite those edits.');
      }
    }
  }
  return null;
}

export async function requireUndo(identity: Identity, id: string, batch = false, database: Database = db()) {
  const events = !batch && isUuid(id)
    ? (await database.query<UndoEvent>('SELECT * FROM care_events WHERE id = $1 AND household_id = $2', [id, identity.household.id])).rows : [];
  const context = await undoContext(identity, events, batch && isUuid(id) ? [id] : [], database);
  const denial = undoDenialFrom(context, id, batch);
  if (denial) error(denial.status, denial.message);
}

export async function withUndoFlags<T extends UndoEvent>(identity: Identity, events: T[], database: Database = db()) {
  if (!events.length) return [];
  const batchIds = [...new Set(events.flatMap(event => event.batch_id ? [event.batch_id] : []))];
  const context = await undoContext(identity, events, batchIds, database);
  const batches = new Map(batchIds.map(id => [id, undoDenialFrom(context, id, true) === null]));
  return events.map(event => ({ ...event, can_undo: event.batch_id ? batches.get(event.batch_id)! : undoDenialFrom(context, event.id) === null }));
}

export async function withUndoFlag<T extends UndoEvent>(identity: Identity, event: T, database: Database = db()) {
  return (await withUndoFlags(identity, [event], database))[0];
}
