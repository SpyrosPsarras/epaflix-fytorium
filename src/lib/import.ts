import type { Candidate, PlantInput } from './plants';
import type { TaskType } from './schedule/engine';
export type ImportedFields = {
  nickname: string | null; cultivar: string | null; scientific_name: string; site_name: string;
  pot_type: PlantInput['pot_type']; pot_size_cm: number | null; soil_type: PlantInput['soil_type'];
  care: Partial<Record<TaskType, string>>; cover_url: string | null;
};
export type ImportStaging = {
  id: string; planta_plant_id: string; mapped: ImportedFields; candidates: Candidate[]; selected: Candidate | null;
  status: 'review' | 'confirmed' | 'skipped' | 'committed'; plant_id: string | null;
};
