import type { Task, TaskType } from './schedule/engine';

export type ListedTask = Task & { plant_id: string; plant_name: string; site_name: string; task_type: TaskType; completed_event?: CareEvent };
export type TaskViewSettings = { sort: 'task' | 'plant' | 'site'; filter: 'all' | 'indoor' | 'outdoor'; show_completed: boolean };
export type TaskList = { today: string; view: 'today' | 'upcoming'; settings: TaskViewSettings; groups: Record<TaskType, ListedTask[]>; sections: { key: string; label: string; tasks: ListedTask[] }[] };
export type CareEvent = {
  id: string; plant_id: string; task_type: TaskType; kind: 'completed' | 'snoozed' | 'skipped'; occurred_at: string;
  batch_id: string | null; previous_pot: Record<string, unknown> | null;
  occurred_on: string; by_member: string | null; by_name: string; undone_at: string | null; undone_by: string | null; request_id: string;
};
