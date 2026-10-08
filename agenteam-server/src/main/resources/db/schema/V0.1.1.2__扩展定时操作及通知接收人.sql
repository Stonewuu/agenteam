-- 将本人定时任务扩展为已注册的操作，保留已有智能体记录和关联。
ALTER TABLE scheduled_task
  ADD COLUMN action_type varchar(64) COLLATE utf8mb4_bin NOT NULL DEFAULT 'agent.run' COMMENT '服务端注册的操作类型',
  ADD COLUMN action_schema_version int NOT NULL DEFAULT 1 COMMENT '操作参数版本',
  ADD COLUMN action_config_json json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '智能体为空对象；通知保存标题和正文，接收人另表保存',
  MODIFY COLUMN hire_id varchar(100) DEFAULT NULL COMMENT '仅智能体操作使用的本人雇佣关系',
  MODIFY COLUMN agent_version_id varchar(100) DEFAULT NULL COMMENT '仅智能体操作使用的固定版本',
  MODIFY COLUMN input_text longtext DEFAULT NULL COMMENT '仅智能体操作使用的固定输入',
  ADD CONSTRAINT ck_schedule_action_config CHECK (action_schema_version > 0 AND JSON_TYPE(action_config_json) = 'OBJECT'),
  ADD CONSTRAINT ck_schedule_action_agent CHECK (
    (action_type = 'agent.run' AND hire_id IS NOT NULL AND agent_version_id IS NOT NULL AND input_text IS NOT NULL)
    OR (action_type <> 'agent.run' AND hire_id IS NULL AND agent_version_id IS NULL AND input_text IS NULL)
  ),
  ADD KEY idx_schedule_action_owner (enterprise_id, owner_user_id, action_type, enabled);

CREATE TABLE scheduled_notification_target (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  schedule_id varchar(100) NOT NULL,
  recipient_user_id varchar(100) NOT NULL,
  connection_id varchar(100) DEFAULT NULL COMMENT '为空表示站内通知；非空表示额外外部渠道',
  channel_key varchar(100) GENERATED ALWAYS AS (COALESCE(connection_id, 'in_app')) STORED COMMENT '使站内渠道也能参与唯一约束',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_notify_target (enterprise_id, schedule_id, recipient_user_id, channel_key),
  KEY idx_schedule_target_connection (enterprise_id, connection_id, schedule_id),
  CONSTRAINT fk_schedule_target_schedule FOREIGN KEY (enterprise_id, schedule_id) REFERENCES scheduled_task(enterprise_id, id),
  CONSTRAINT fk_schedule_target_member FOREIGN KEY (enterprise_id, recipient_user_id) REFERENCES enterprise_member(enterprise_id, user_id),
  CONSTRAINT fk_schedule_target_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知计划中显式保存的接收人及渠道；每个接收人还需一条站内记录';

ALTER TABLE scheduled_occurrence
  ADD COLUMN action_type varchar(64) COLLATE utf8mb4_bin NOT NULL DEFAULT 'agent.run',
  ADD COLUMN action_schema_version int NOT NULL DEFAULT 1,
  ADD COLUMN schedule_revision bigint DEFAULT NULL COMMENT '触发时计划版本；无法证明的历史值为空',
  ADD COLUMN action_snapshot_json json DEFAULT NULL COMMENT '本次不可变配置，包含接收人及当时绑定编号，不含密钥',
  ADD COLUMN action_result_json json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '准备完成标记及未创建通知的被阻止接收人，供恢复与汇总使用',
  ADD COLUMN snapshot_origin varchar(32) NOT NULL DEFAULT 'legacy_unavailable' COMMENT '历史无法恢复或新触发时捕获',
  ADD COLUMN action_job_id varchar(100) DEFAULT NULL COMMENT '负责提交本次操作的后台工作',
  ADD COLUMN error_summary varchar(500) DEFAULT NULL COMMENT '本次操作的脱敏错误说明',
  ADD KEY idx_occurrence_reconcile (action_type, status, updated_at, id),
  ADD CONSTRAINT fk_occurrence_action_job FOREIGN KEY (enterprise_id, action_job_id) REFERENCES background_job(enterprise_id, id),
  ADD CONSTRAINT ck_occurrence_snapshot CHECK (
    (snapshot_origin = 'legacy_unavailable' AND action_type = 'agent.run' AND action_snapshot_json IS NULL AND schedule_revision IS NULL)
    OR (snapshot_origin = 'captured' AND action_snapshot_json IS NOT NULL AND JSON_TYPE(action_snapshot_json) = 'OBJECT' AND schedule_revision IS NOT NULL)
  ),
  ADD CONSTRAINT ck_occurrence_action_version CHECK (action_schema_version > 0 AND JSON_TYPE(action_result_json) = 'OBJECT'),
  ADD CONSTRAINT ck_occurrence_nonagent_reference CHECK (action_type = 'agent.run' OR (run_id IS NULL AND conversation_id IS NULL)),
  DROP CHECK ck_scheduled_occurrence_3,
  ADD CONSTRAINT ck_scheduled_occurrence_3 CHECK (status IN ('queued', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled', 'skipped', 'missed', 'blocked', 'partially_failed', 'unknown'));

-- 活动生成列继续只包含 queued/running/waiting_approval。
-- 外部发送重试期间通知发生记录保持 running，不新增漏出活动唯一约束的状态。

