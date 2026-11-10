-- 网页对话按企业和创建人授权，不创建平台群、机器人或外部账号绑定。
CREATE TABLE collaboration_room (
  id varchar(100) NOT NULL PRIMARY KEY,
  enterprise_id varchar(100) NOT NULL,
  owner_user_id varchar(100) NOT NULL,
  title varchar(160) NOT NULL,
  lead_agent_id varchar(100) NOT NULL,
  current_task_id varchar(100) DEFAULT NULL,
  next_sequence bigint NOT NULL DEFAULT 1,
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  UNIQUE KEY uk_web_room_scope (enterprise_id,id),
  KEY ix_web_room_owner (enterprise_id,owner_user_id,updated_at,id),
  CONSTRAINT fk_web_room_owner FOREIGN KEY (enterprise_id,owner_user_id) REFERENCES enterprise_member(enterprise_id,user_id),
  CONSTRAINT fk_web_room_lead FOREIGN KEY (enterprise_id,lead_agent_id) REFERENCES resource(enterprise_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collaboration_room_member (
  id varchar(100) NOT NULL PRIMARY KEY,
  enterprise_id varchar(100) NOT NULL,
  room_id varchar(100) NOT NULL,
  agent_id varchar(100) NOT NULL,
  display_name varchar(160) NOT NULL,
  sort_order int NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  UNIQUE KEY uk_web_member_agent (enterprise_id,room_id,agent_id),
  UNIQUE KEY uk_web_member_scope (enterprise_id,room_id,id,agent_id),
  CONSTRAINT fk_web_member_room FOREIGN KEY (enterprise_id,room_id) REFERENCES collaboration_room(enterprise_id,id),
  CONSTRAINT fk_web_member_agent FOREIGN KEY (enterprise_id,agent_id) REFERENCES resource(enterprise_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE collaboration_room_entry (
  id varchar(100) NOT NULL PRIMARY KEY,
  enterprise_id varchar(100) NOT NULL,
  room_id varchar(100) NOT NULL,
  task_id varchar(100) DEFAULT NULL,
  participant_id varchar(100) DEFAULT NULL,
  author_name varchar(160) NOT NULL,
  kind varchar(40) NOT NULL,
  source_key varchar(191) NOT NULL,
  sequence_no bigint NOT NULL,
  body_encrypted json NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  UNIQUE KEY uk_web_entry_scope (enterprise_id,room_id,id),
  UNIQUE KEY uk_web_entry_order (enterprise_id,room_id,sequence_no),
  UNIQUE KEY uk_web_entry_source (enterprise_id,room_id,source_key),
  CONSTRAINT fk_web_entry_room FOREIGN KEY (enterprise_id,room_id) REFERENCES collaboration_room(enterprise_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE collaboration_task
  MODIFY conversation_id varchar(100) DEFAULT NULL,
  MODIFY origin_message_id varchar(100) DEFAULT NULL,
  MODIFY origin_user_binding_id varchar(100) DEFAULT NULL,
  MODIFY entry_bot_id varchar(100) DEFAULT NULL,
  ADD COLUMN origin_kind varchar(24) NOT NULL DEFAULT 'external_chat',
  ADD COLUMN web_room_id varchar(100) DEFAULT NULL,
  ADD COLUMN web_origin_entry_id varchar(100) DEFAULT NULL,
  ADD UNIQUE KEY uk_web_task_room (enterprise_id,web_room_id,id),
  ADD UNIQUE KEY uk_web_task_origin (enterprise_id,web_room_id,web_origin_entry_id),
  ADD CONSTRAINT fk_web_task_entry FOREIGN KEY (enterprise_id,web_room_id,web_origin_entry_id)
    REFERENCES collaboration_room_entry(enterprise_id,room_id,id),
  DROP CHECK ck_collaboration_task_output_visibility,
  ADD CONSTRAINT ck_collaboration_task_origin CHECK (
    (origin_kind='external_chat' AND conversation_id IS NOT NULL AND origin_message_id IS NOT NULL
      AND origin_user_binding_id IS NOT NULL AND entry_bot_id IS NOT NULL AND web_room_id IS NULL
      AND web_origin_entry_id IS NULL AND output_visibility IN ('summary_link','chat_full'))
    OR (origin_kind='web' AND web_room_id IS NOT NULL AND web_origin_entry_id IS NOT NULL
      AND conversation_id IS NULL AND origin_message_id IS NULL AND origin_user_binding_id IS NULL
      AND entry_bot_id IS NULL AND output_visibility='web_private')
  );
ALTER TABLE collaboration_participant
  MODIFY bot_binding_id varchar(100) DEFAULT NULL,
  ADD COLUMN web_room_id varchar(100) DEFAULT NULL,
  ADD COLUMN web_member_id varchar(100) DEFAULT NULL,
  ADD UNIQUE KEY uk_web_task_member (enterprise_id,task_id,web_member_id),
  ADD CONSTRAINT fk_web_participant_task FOREIGN KEY (enterprise_id,web_room_id,task_id)
    REFERENCES collaboration_task(enterprise_id,web_room_id,id),
  ADD CONSTRAINT fk_web_participant_member FOREIGN KEY (enterprise_id,web_room_id,web_member_id,agent_id)
    REFERENCES collaboration_room_member(enterprise_id,room_id,id,agent_id),
  ADD CONSTRAINT ck_web_participant_origin CHECK (
    (bot_binding_id IS NOT NULL AND web_room_id IS NULL AND web_member_id IS NULL)
    OR (bot_binding_id IS NULL AND web_room_id IS NOT NULL AND web_member_id IS NOT NULL)
  );
ALTER TABLE collaboration_room ADD CONSTRAINT fk_web_room_current FOREIGN KEY (enterprise_id,id,current_task_id)
  REFERENCES collaboration_task(enterprise_id,web_room_id,id);
ALTER TABLE collaboration_room_entry ADD CONSTRAINT fk_web_entry_task FOREIGN KEY (enterprise_id,room_id,task_id)
  REFERENCES collaboration_task(enterprise_id,web_room_id,id);
ALTER TABLE collaboration_room_entry ADD CONSTRAINT fk_web_entry_author FOREIGN KEY (enterprise_id,participant_id)
  REFERENCES collaboration_participant(enterprise_id,id);
