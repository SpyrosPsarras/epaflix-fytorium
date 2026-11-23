export type CustomTask = { id: string; household_id: string; name: string; interval_days: number; plant_id: string | null; site_id: string | null; created_at: string; deleted_at: string | null;
  target_name?: string; target_site_name?: string | null; target_status?: 'active' | 'graveyard' | null; target_plant_count?: number | null };
