import type { Task, TaskType } from './schedule/engine';

export type ListedTask = Task & { plant_id: string; plant_name: string; site_name: string; task_type: TaskType };
export type TaskList = { today: string; groups: Record<TaskType, ListedTask[]> };
export type CareEvent = {
  id: string; plant_id: string; task_type: TaskType; kind: 'completed'; occurred_at: string;
  occurred_on: string; by_member: string | null; by_name: string; undone_at: string | null; undone_by: string | null; request_id: string;
};
