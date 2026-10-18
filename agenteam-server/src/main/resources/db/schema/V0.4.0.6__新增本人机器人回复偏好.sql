-- 个人偏好按企业、账号和原会话分别保存，同一群内的机器人共用设置。
CREATE TABLE chat_reply_preference (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  user_id varchar(100) NOT NULL,
  conversation_id varchar(100) NOT NULL,
  output_visibility varchar(32) NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_reply_preference_owner (enterprise_id, user_id, conversation_id),
  CONSTRAINT ck_chat_reply_preference_visibility CHECK (output_visibility IN ('summary_link', 'chat_full'))
);

-- 独立修改回复方式不改变用户输入版本，也不触发任务重新执行。
ALTER TABLE collaboration_task
  ADD COLUMN reply_mode_revision bigint NOT NULL DEFAULT 0,
  ADD COLUMN reply_mode_explicit boolean NOT NULL DEFAULT false;
