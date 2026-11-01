ALTER TABLE agent_run DROP CHECK ck_agent_run_4,
  ADD CONSTRAINT ck_agent_run_4 CHECK (status IN ('queued','running','waiting_approval','waiting_input','cancelling','completed','failed','cancelled'));
ALTER TABLE agent_run DROP CHECK ck_agent_run_5,
  ADD CONSTRAINT ck_agent_run_5 CHECK (execution_phase IN ('between_steps','model','tools','subagent','waiting_approval','waiting_input'));
ALTER TABLE run_attempt DROP CHECK ck_run_attempt_1,
  ADD CONSTRAINT ck_run_attempt_1 CHECK (status IN ('queued','running','waiting_approval','waiting_input','completed','failed','cancelled'));
ALTER TABLE run_step DROP CHECK ck_run_step_1,
  ADD CONSTRAINT ck_run_step_1 CHECK (status IN ('pending','running','waiting_approval','waiting_input','completed','failed','cancelled','skipped'));
ALTER TABLE tool_call DROP CHECK ck_tool_call_6,
  ADD CONSTRAINT ck_tool_call_6 CHECK (status IN ('prepared','waiting_approval','waiting_input','running','succeeded','failed','cancelled','unknown'));

CREATE TABLE run_question (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  run_id varchar(100) NOT NULL,
  tool_call_id varchar(100) NOT NULL,
  session_id varchar(255) NOT NULL,
  user_id varchar(100) NOT NULL,
  request_hash varchar(64) NOT NULL,
  question_encrypted longtext NOT NULL,
  answer_encrypted longtext DEFAULT NULL,
  status varchar(20) NOT NULL DEFAULT 'pending',
  task_id varchar(100) DEFAULT NULL,
  chat_conversation_id varchar(100) DEFAULT NULL,
  bot_binding_id varchar(100) DEFAULT NULL,
  free_text_selected_at datetime(3) DEFAULT NULL,
  expires_at datetime(3) NOT NULL,
  answered_at datetime(3) DEFAULT NULL,
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_question_tool (enterprise_id, tool_call_id),
  KEY ix_run_question_pending (enterprise_id, run_id, status),
  KEY ix_run_question_chat (enterprise_id, chat_conversation_id, user_id, status, created_at),
  KEY ix_run_question_expiry (status, expires_at),
  CONSTRAINT fk_run_question_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_run_question_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_run_question_run FOREIGN KEY (enterprise_id, run_id) REFERENCES agent_run(enterprise_id, id),
  CONSTRAINT fk_run_question_tool FOREIGN KEY (enterprise_id, tool_call_id) REFERENCES tool_call(enterprise_id, id),
  CONSTRAINT fk_run_question_task FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id),
  CONSTRAINT fk_run_question_chat FOREIGN KEY (enterprise_id, chat_conversation_id) REFERENCES chat_conversation(enterprise_id, id),
  CONSTRAINT fk_run_question_bot FOREIGN KEY (enterprise_id, bot_binding_id) REFERENCES chat_bot_binding(enterprise_id, id),
  CONSTRAINT ck_run_question_status CHECK (status IN ('pending','answered','cancelled','expired')),
  CONSTRAINT ck_run_question_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体向原发起人提出的问题和已确认回答';
