-- Remove "default" role — supervisor IS the default model.
-- Three canonical roles: worker, supervisor, advisor.
--
-- If "supervisor" role already exists, just delete "default".
-- If only "default" exists, rename it to "supervisor".

DELETE FROM kukuvaia.model_roles
WHERE role = 'default'
  AND EXISTS (SELECT 1 FROM kukuvaia.model_roles WHERE role = 'supervisor');

UPDATE kukuvaia.model_roles
SET role = 'supervisor', updated_at = NOW()
WHERE role = 'default';
