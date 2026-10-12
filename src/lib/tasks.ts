import type { Task, TaskType } from './schedule/engine';
import { taskRules } from './schedule/rules';

const eventLabels = { completed: 'Completed', snoozed: 'Snoozed', skipped: 'Skipped' };
export function careEventLabel(kind: CareEvent['kind'], completedLabel = eventLabels.completed) {
  return kind === 'completed' ? completedLabel : eventLabels[kind];
}

export type PlantSchedule = Record<TaskType, Task> & { customTasks: Record<string, Task & { name: string; custom_task_id: string }> };
export function taskKey(task: { task_type: TaskType | null; custom_task_id?: string | null }): string {
  return task.custom_task_id ?? task.task_type!;
}
export function taskPresentation(task: { task_type: TaskType | null; custom_task_name?: string | null }) {
  return task.task_type ? taskRules[task.task_type] : { label: task.custom_task_name ?? 'Deleted custom task', action: task.custom_task_name ?? 'custom task' };
}

export type ListedTask = Task & { plant_id: string; plant_name: string; site_name: string; task_type: TaskType | null; custom_task_id?: string | null; custom_task_name?: string | null; completed_event?: CareEvent };
export type TaskViewSettings = { sort: 'task' | 'plant' | 'site'; filter: 'all' | 'indoor' | 'outdoor'; show_completed: boolean };
export type TaskList = { today: string; view: 'today' | 'upcoming'; settings: TaskViewSettings; groups: Record<string, ListedTask[]>; sections: { key: string; label: string; tasks: ListedTask[] }[] };
export type CareEvent = {
  id: string; plant_id: string; task_type: TaskType | null; custom_task_id?: string | null; custom_task_name?: string | null; kind: 'completed' | 'snoozed' | 'skipped'; occurred_at: string;
  batch_id: string | null; previous_pot: Record<string, unknown> | null;
  occurred_on: string; by_member: string | null; by_name: string; undone_at: string | null; undone_by: string | null; request_id: string;
};
