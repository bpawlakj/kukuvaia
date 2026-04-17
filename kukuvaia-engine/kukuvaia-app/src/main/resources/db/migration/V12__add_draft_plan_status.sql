-- Add 'draft' status for plans created during planning mode but not yet approved.
ALTER TABLE kukuvaia.plans DROP CONSTRAINT plans_status_check;
ALTER TABLE kukuvaia.plans ADD CONSTRAINT plans_status_check
    CHECK (status IN ('draft', 'active', 'completed', 'abandoned'));
