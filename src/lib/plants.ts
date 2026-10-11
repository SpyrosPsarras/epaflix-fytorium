export const plantChoices = {
  pot_type: ['plastic', 'terracotta', 'ceramic', 'glass', 'metal', 'wood', 'fabric', 'in_ground'],
  soil_type: ['all_purpose', 'cactus', 'orchid', 'aroid', 'other'],
  plant_size: ['small', 'medium', 'large', 'very_large'],
  growth_stage: ['seedling', 'young', 'mature'],
  fertilizer_type: ['liquid', 'sticks', 'slow_release'],
} as const;
export const plantLabels = {
  pot_type: 'Pot type', soil_type: 'Soil type', plant_size: 'Plant size', growth_stage: 'Growth stage',
  fertilizer_type: 'Fertilizer type', pot_size_cm: 'Pot size in cm', window_distance_cm: 'Window distance in cm',
  drainage: 'Drainage', grow_light: 'Grow light', near_ac: 'Near AC', near_heater: 'Near heater',
} as const;
export const plantBooleans = ['drainage', 'grow_light', 'near_ac', 'near_heater'] as const;
export const plantNumbers = ['pot_size_cm', 'window_distance_cm'] as const;
export type PlantInput = { [K in keyof typeof plantChoices]: typeof plantChoices[K][number] } & {
  site_id: string; nickname: string | null; cultivar: string | null; birthday: string | null;
  pot_size_cm: number | null; window_distance_cm: number | null;
  drainage: boolean; grow_light: boolean; near_ac: boolean; near_heater: boolean;
};
export type Plant = PlantInput & {
  id: string; gbif_key: number; scientific_name: string; canonical_name: string; rank: string;
  display_name: string; site_name: string;
};
export type Candidate = {
  gbif_key: number; scientific_name: string; canonical_name: string; rank: string;
  match_type: string | null; confidence: number | null; requires_confirmation: boolean;
};
export type SpeciesSearch = { search_id: string; candidates: Candidate[] };
