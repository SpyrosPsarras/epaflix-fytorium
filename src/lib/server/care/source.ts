import type { CareFields } from '../../care';

export type CareSpecies = { gbif_key: number; canonical_name: string; sourceKey?: string | null };
export type SourceLimits = { daily: number; perMinute: number; maxAttempts: number };
export type CareResult = { fields: Partial<CareFields>; fetchedAt: Date; key: string }
  | 'not_available' | 'auth_failed' | { retryLater: Date; key?: string; quota?: boolean };
export interface CareSource {
  readonly configured: boolean;
  fetch(species: CareSpecies): Promise<CareResult>;
}
