-- migrate:up
ALTER TABLE app_devices ADD CONSTRAINT app_devices_id_member UNIQUE (id, member_id);
ALTER TABLE push_subscriptions
  ADD COLUMN transport text NOT NULL DEFAULT 'web' CHECK (transport IN ('web', 'fcm')),
  ADD COLUMN enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN fcm_token text UNIQUE,
  ADD COLUMN app_device_id uuid UNIQUE,
  ALTER COLUMN endpoint DROP NOT NULL,
  ALTER COLUMN p256dh DROP NOT NULL,
  ALTER COLUMN auth DROP NOT NULL,
  ADD CONSTRAINT push_app_member FOREIGN KEY (app_device_id, member_id) REFERENCES app_devices(id, member_id) ON DELETE CASCADE,
  ADD CONSTRAINT push_transport_fields CHECK (
    (transport = 'web' AND endpoint IS NOT NULL AND p256dh IS NOT NULL AND auth IS NOT NULL AND fcm_token IS NULL AND app_device_id IS NULL)
    OR (transport = 'fcm' AND endpoint IS NULL AND p256dh IS NULL AND auth IS NULL AND fcm_token IS NOT NULL AND app_device_id IS NOT NULL)
  );
-- Revocation includes logout, refresh replay, expiry and member removal.
CREATE FUNCTION remove_revoked_fcm() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.revoked_at IS NOT NULL THEN DELETE FROM push_subscriptions WHERE app_device_id = NEW.id; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER app_device_push_revoked AFTER UPDATE OF revoked_at ON app_devices FOR EACH ROW EXECUTE FUNCTION remove_revoked_fcm();

-- migrate:down
DELETE FROM push_subscriptions WHERE transport = 'fcm';
DROP TRIGGER app_device_push_revoked ON app_devices;
DROP FUNCTION remove_revoked_fcm();
ALTER TABLE push_subscriptions DROP CONSTRAINT push_transport_fields, DROP CONSTRAINT push_app_member,
  DROP COLUMN app_device_id, DROP COLUMN fcm_token, DROP COLUMN enabled, DROP COLUMN transport,
  ALTER COLUMN endpoint SET NOT NULL, ALTER COLUMN p256dh SET NOT NULL, ALTER COLUMN auth SET NOT NULL;
ALTER TABLE app_devices DROP CONSTRAINT app_devices_id_member;
