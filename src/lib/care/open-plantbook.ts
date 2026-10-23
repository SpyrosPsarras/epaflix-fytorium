import { validCareValue, type CareFields } from '../care';

export function mapOpenPlantbook(payload: unknown): Partial<CareFields> {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return {};
  const data = payload as Record<string, unknown>;
  const fields: Record<string, unknown> = {};
  function range(name: string, lower: number, upper: number) {
    const min = data['min_' + name];
    const max = data['max_' + name];
    return typeof min === 'number' && typeof max === 'number' && Number.isFinite(min) && Number.isFinite(max) && min >= lower && max <= upper && min <= max ? { min, max } : null;
  }
  // open_plantbook.light@1: midpoint bands in lux, not daily light integral.
  const light = range('light_lux', 0, 100000);
  if (light) {
    const midpoint = (light.min + light.max) / 2;
    fields.light = midpoint < 2000 ? 'full_shade' : midpoint < 5000 ? 'part_shade' : midpoint < 20000 ? 'part_sun' : 'full_sun';
  }
  // open_plantbook.temperature@1 and survival@1 retain Celsius, never USDA zones.
  fields.temperatureRangeC = range('temp', -60, 60);
  fields.hardinessC = data.min_temp_survival;
  fields.soilMoistureRangePercent = range('soil_moist', 0, 100);
  // The care extension has no documented schema. Keep recognised text only.
  if (typeof data.care === 'string') fields.careText = data.care.trim();
  else if (data.care && typeof data.care === 'object' && !Array.isArray(data.care)) {
    const care = data.care as Record<string, unknown>;
    fields.careText = ['soil', 'sunlight', 'watering', 'fertilization', 'pruning'].flatMap(name => typeof care[name] === 'string' && care[name].trim() ? [name + ': ' + care[name].trim()] : []).join('\n');
  }
  return Object.fromEntries(Object.entries(fields).filter(([field, value]) => validCareValue(field, value)));
}
