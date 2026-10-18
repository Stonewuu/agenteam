-- 本开发版本新增标题任务，不改动已发布版本的迁移脚本。
ALTER TABLE background_job
  DROP CHECK ck_background_job_1,
  ADD CONSTRAINT ck_background_job_1 CHECK (kind IN (
    'run', 'document_parse', 'file_scan', 'mail', 'notification', 'event_publish',
    'export', 'cleanup', 'scheduled_action', 'channel_delivery', 'conversation_title'
  ));

ALTER TABLE model_usage_record
  DROP CHECK ck_model_usage_purpose,
  ADD CONSTRAINT ck_model_usage_purpose CHECK (purpose IN (
    'agent', 'subagent', 'workflow', 'compaction', 'preview', 'title'
  ));
