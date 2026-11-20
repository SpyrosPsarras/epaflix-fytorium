-- migrate:up
ALTER TABLE plants
  ALTER COLUMN pot_type DROP NOT NULL, ALTER COLUMN pot_type DROP DEFAULT,
  ALTER COLUMN soil_type DROP NOT NULL, ALTER COLUMN soil_type DROP DEFAULT,
  ALTER COLUMN plant_size DROP NOT NULL, ALTER COLUMN plant_size DROP DEFAULT,
  ALTER COLUMN growth_stage DROP NOT NULL, ALTER COLUMN growth_stage DROP DEFAULT,
  ALTER COLUMN fertilizer_type DROP NOT NULL, ALTER COLUMN fertilizer_type DROP DEFAULT,
  ALTER COLUMN drainage DROP NOT NULL, ALTER COLUMN drainage DROP DEFAULT;

-- migrate:down
UPDATE import_staging SET mapped = mapped || jsonb_build_object(
  'pot_type', COALESCE(mapped->>'pot_type', 'other'),
  'soil_type', COALESCE(mapped->>'soil_type', 'other')
)
WHERE mapped->>'pot_type' IS NULL OR mapped->>'soil_type' IS NULL;

UPDATE care_events SET previous_pot = previous_pot || jsonb_build_object(
  'pot_type', COALESCE(previous_pot->>'pot_type', 'plastic'),
  'soil_type', COALESCE(previous_pot->>'soil_type', 'all_purpose')
)
WHERE previous_pot IS NOT NULL AND (previous_pot->>'pot_type' IS NULL OR previous_pot->>'soil_type' IS NULL);

UPDATE care_actions SET payload = payload || jsonb_build_object(
  'pot_type', COALESCE(payload->>'pot_type', 'plastic'),
  'soil_type', COALESCE(payload->>'soil_type', 'all_purpose')
)
WHERE action = 'repot' AND (payload->>'pot_type' IS NULL OR payload->>'soil_type' IS NULL);

UPDATE plants SET pot_type = COALESCE(pot_type, 'plastic'), soil_type = COALESCE(soil_type, 'all_purpose'),
  plant_size = COALESCE(plant_size, 'small'), growth_stage = COALESCE(growth_stage, 'young'),
  fertilizer_type = COALESCE(fertilizer_type, 'liquid'), drainage = COALESCE(drainage, false)
WHERE pot_type IS NULL OR soil_type IS NULL OR plant_size IS NULL OR growth_stage IS NULL OR fertilizer_type IS NULL OR drainage IS NULL;
ALTER TABLE plants
  ALTER COLUMN pot_type SET NOT NULL, ALTER COLUMN pot_type SET DEFAULT 'plastic',
  ALTER COLUMN soil_type SET NOT NULL, ALTER COLUMN soil_type SET DEFAULT 'all_purpose',
  ALTER COLUMN plant_size SET NOT NULL, ALTER COLUMN plant_size SET DEFAULT 'small',
  ALTER COLUMN growth_stage SET NOT NULL, ALTER COLUMN growth_stage SET DEFAULT 'young',
  ALTER COLUMN fertilizer_type SET NOT NULL, ALTER COLUMN fertilizer_type SET DEFAULT 'liquid',
  ALTER COLUMN drainage SET NOT NULL, ALTER COLUMN drainage SET DEFAULT false;
