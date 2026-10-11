export const healthValues = ['poor', 'fair', 'good', 'very_good'] as const;
export type Health = typeof healthValues[number];
export function healthLabel(health: Health | null) {
  return health?.replaceAll('_', ' ') ?? 'Not set';
}
export type PhotoUpdate = {
  id: string; plant_id: string; plant_name: string;
  original_key: string; thumb_key: string; note: string; health: Health | null;
  taken_on: string; created_at: string; created_by: string; is_cover: boolean;
};
