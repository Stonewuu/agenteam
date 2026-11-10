ALTER TABLE agent_run
  DROP CHECK ck_agent_run_4,
  ADD CONSTRAINT ck_agent_run_4 CHECK (status IN ('waiting_turn','queued','running','waiting_approval','waiting_input','cancelling','completed','failed','cancelled')),
  ADD COLUMN queue_position BIGINT DEFAULT NULL COMMENT '补充消息提交时的会话顺序' AFTER queued_at,
  ADD INDEX idx_run_conversation_queue (enterprise_id, conversation_id, status, queue_position);
