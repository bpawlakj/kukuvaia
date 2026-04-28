-- Allow the EXECUTING phase — entered automatically when a draft plan is
-- approved, so the user can track per-step progress via `completeStep`
-- without replanning. Required because V16 defined the CHECK without it.
ALTER TABLE kukuvaia.plans DROP CONSTRAINT plans_phase_check;

ALTER TABLE kukuvaia.plans ADD CONSTRAINT plans_phase_check
    CHECK (phase IN ('discovery', 'drafting', 'approval', 'executing', 'done', 'abandoned'));
