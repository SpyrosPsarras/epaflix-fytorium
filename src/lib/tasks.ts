import type { Task, TaskType } from './schedule/engine';
import { taskRules } from './schedule/rules';

const eventLabels = { completed: 'Completed', snoozed: 'Snoozed', skipped: 'Skipped' };
export function careEventLabel(kind: CareEvent['kind'], completedLabel = eventLabels.completed) {
  return kind === 'completed' ? completedLabel : eventLabels[kind];
}

export type ScheduledTask = Task & { postponed: 'snoozed' | 'skipped' | null };
export type PlantSchedule = Record<TaskType, ScheduledTask> & { customTasks: Record<string, ScheduledTask & { name: string; custom_task_id: string }> };
export function taskKey(task: { task_type: TaskType | null; custom_task_id?: string | null }): string {
  return task.custom_task_id ?? task.task_type!;
}
export function taskPresentation(task: { task_type: TaskType | null; custom_task_name?: string | null }) {
  return task.task_type ? taskRules[task.task_type] : { label: task.custom_task_name ?? 'Deleted custom task', action: task.custom_task_name ?? 'custom task' };
}

const doneVerbs: Record<TaskType, string> = { water: 'Watered', fertilize: 'Fertilized', clean_leaves: 'Cleaned leaves of',
  remove_dead: 'Removed dead flowers and leaves from', cut_back: 'Cut back', repot: 'Repotted' };
const counted = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`;
// Undo reverses the whole server batch, so the words come from its batch fields (as the Android receipt does), never from the visible rows.
export function batchScope(event: CareEvent, plantName: string, customTaskName: string | null) {
  const plants = event.batch_plant_count!, events = event.batch_event_count!;
  if (event.batch_action === 'complete-all') return { text: `Handled ${counted(events, 'task')} for ${plantName}`, count: events };
  if (event.batch_action === 'rain') return { text: `Watered ${counted(plants, 'outdoor plant')}`, count: plants };
  const subject = counted(plants, 'plant');
  const verb = doneVerbs[event.batch_task as TaskType];
  // A card completes one task per plant, so any further events are the water linked to liquid fertilizing.
  const text = event.batch_task === 'fertilize' && events > plants ? `Fertilized and watered ${subject}`
    : verb ? `${verb} ${subject}` : `${customTaskName ?? 'Deleted custom task'} done for ${subject}`;
  return { text, count: plants };
}

const postponedUndo = { completed: '', snoozed: ' snooze', skipped: ' skip' };
// Undoing an event also undoes the water linked to it, unless that water was undone on its own.
export function undoLabel(event: CareEvent, history: CareEvent[]) {
  if (event.batch_id) return 'Undo all ' + batchScope(event, '', null).count;
  const watered = history.some(other => other.linked_event_id === event.id && !other.undone_at);
  return 'Undo ' + taskPresentation(event).action + (watered ? ' and watering' : postponedUndo[event.kind]);
}

export type ListedTask = ScheduledTask & { plant_id: string; plant_name: string; site_name: string; cover_photo_id?: string | null; task_type: TaskType | null; custom_task_id?: string | null; custom_task_name?: string | null; completed_event?: CareEvent };
export type TaskViewSettings = { sort: 'task' | 'plant' | 'site'; filter: 'all' | 'indoor' | 'outdoor'; show_completed: boolean };
export type TaskList = { today: string; view: 'today' | 'upcoming'; settings: TaskViewSettings; groups: Record<string, ListedTask[]>; sections: { key: string; label: string; tasks: ListedTask[] }[] };
export type CareEvent = {
  id: string; plant_id: string; task_type: TaskType | null; custom_task_id?: string | null; custom_task_name?: string | null; kind: 'completed' | 'snoozed' | 'skipped'; occurred_at: string;
  linked_event_id?: string | null; batch_plant_count?: number; batch_event_count?: number;
  batch_action?: 'complete-all' | 'complete-type' | 'rain'; batch_task?: string | null; can_undo: boolean; batch_id: string | null; previous_pot: Record<string, unknown> | null;
  occurred_on: string; due_on?: string | null; by_member: string | null; by_name: string; undone_at: string | null; undone_by: string | null; request_id: string;
};
