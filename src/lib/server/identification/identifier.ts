import type { CommonNameLanguage } from '$lib/names';

export type Organ = 'auto' | 'leaf' | 'flower' | 'fruit' | 'bark';
export type IdentificationCandidate = {
  gbifKey?: number; scientificName: string; canonicalName: string; score: number; commonNames: string[];
};
export interface Identifier {
  identify(images: Buffer[], options?: { organs?: Organ[]; language?: CommonNameLanguage }): Promise<{ candidates: IdentificationCandidate[]; remaining?: number }>;
}
