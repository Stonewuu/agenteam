ALTER TABLE audit_event
  ADD COLUMN authenticated_user_id varchar(100) NULL,
  ADD COLUMN impersonation_id varchar(64) NULL,
  ADD KEY ix_audit_impersonation (impersonation_id, created_at);

ALTER TABLE background_job
  ADD COLUMN authenticated_user_id varchar(100) NULL,
  ADD COLUMN impersonation_id varchar(64) NULL;
