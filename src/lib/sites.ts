export const locations = ['indoor', 'outdoor'] as const;
export const lightLevels = ['full_shade', 'part_shade', 'part_sun', 'full_sun'] as const;
export const humidityLevels = ['dry', 'normal', 'humid'] as const;

export type SiteInput = {
  name: string;
  location: typeof locations[number];
  light: typeof lightLevels[number];
  grow_light: boolean;
  warm_min_c: number | null;
  warm_max_c: number | null;
  cold_min_c: number | null;
  cold_max_c: number | null;
  humidity: typeof humidityLevels[number];
  draft: boolean;
};

export type Site = SiteInput & { id: string };
export type ListedSite = Site & { plant_count: number };
