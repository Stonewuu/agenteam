-- 新增平台聊天接入与岗位协作，保留已有应用、个人会话及密钥格式。
ALTER TABLE enterprise_integration
  ADD COLUMN identity_scope varchar(16) NOT NULL DEFAULT 'tenant' COMMENT '验证平台企业或仅验证机器人身份',
  ADD COLUMN exclusive_bot_key varchar(191) COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN provider_code = 'wecom_aibot' AND status <> 'deleted' THEN external_app_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_integration_exclusive_bot (provider_code, region_code, exclusive_bot_key),
  DROP CHECK ck_integration_enabled,
  ADD CONSTRAINT ck_integration_identity_scope CHECK (identity_scope IN ('tenant', 'bot')),
  ADD CONSTRAINT ck_integration_enabled CHECK (status <> 'enabled' OR (identity_verified_at IS NOT NULL AND ((identity_scope = 'tenant' AND external_tenant_id IS NOT NULL) OR (identity_scope = 'bot' AND provider_code = 'wecom_aibot' AND login_enabled = 0 AND messaging_enabled = 0))));

ALTER TABLE user_channel_binding ADD COLUMN chat_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '成员明确允许使用此身份发起智能体执行', ADD CONSTRAINT ck_binding_chat_enabled CHECK (chat_enabled IN (0, 1));
ALTER TABLE agent_conversation ADD COLUMN source_type varchar(32) NOT NULL DEFAULT 'web', ADD COLUMN source_id varchar(100) DEFAULT NULL, ADD KEY idx_conversation_source (enterprise_id, source_type, source_id);
ALTER TABLE file_object DROP CHECK ck_file_object_3, ADD CONSTRAINT ck_file_object_3 CHECK (purpose IN ('attachment', 'knowledge', 'data_import', 'skill_import', 'export', 'artifact', 'collaboration_artifact'));

CREATE TABLE chat_bot_binding (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `connection_id` varchar(100) NOT NULL,
  `platform_bot_id` varchar(191) COLLATE utf8mb4_bin NOT NULL,
  `agent_id` varchar(100) NOT NULL,
  `version_policy` varchar(32) NOT NULL DEFAULT 'pinned',
  `pinned_version_id` varchar(100) DEFAULT NULL,
  `role_name` varchar(80) NOT NULL,
  `role_description` varchar(500) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'draft',
  `transport` varchar(32) NOT NULL DEFAULT 'websocket',
  `public_callback_key` varchar(64) DEFAULT NULL,
  `chat_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `capabilities_json` json NOT NULL DEFAULT (JSON_OBJECT()),
  `config_json` json NOT NULL DEFAULT (JSON_OBJECT()),
  `checked_revision` bigint NOT NULL DEFAULT 0,
  `last_connected_at` datetime(3) DEFAULT NULL,
  `last_message_at` datetime(3) DEFAULT NULL,
  `lease_owner` varchar(100) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_bot_binding_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_bot_binding_0 (`connection_id`),
  UNIQUE KEY uk_chat_bot_binding_1 (`public_callback_key`),
  KEY idx_chat_bot_binding_0 (`status`, `lease_until`),
  CONSTRAINT fk_chat_bot_binding_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_bot_binding_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_bot_binding_version_policy CHECK (`version_policy` IN ('pinned', 'latest_on_new_task')),
  CONSTRAINT ck_chat_bot_binding_status CHECK (`status` IN ('draft', 'enabled', 'disabled', 'deleted')),
  CONSTRAINT ck_chat_bot_binding_transport CHECK (`transport` IN ('websocket', 'webhook')),
  CONSTRAINT ck_chat_bot_binding_chat_enabled CHECK (`chat_enabled` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台机器人的智能体绑定与连接租约';

CREATE TABLE chat_conversation (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `platform_code` varchar(32) NOT NULL,
  `platform_scope_key` varchar(191) COLLATE utf8mb4_bin NOT NULL,
  `external_conversation_id` varchar(191) COLLATE utf8mb4_bin NOT NULL,
  `conversation_kind` varchar(32) NOT NULL DEFAULT 'group',
  `direct_binding_id` varchar(100) DEFAULT NULL,
  `name` varchar(128) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'discovered',
  `ingress_mode` varchar(32) NOT NULL DEFAULT 'single_bot',
  `ingress_bot_id` varchar(100) DEFAULT NULL,
  `default_bot_id` varchar(100) DEFAULT NULL,
  `trigger_mode` varchar(32) NOT NULL DEFAULT 'mention',
  `result_policy` varchar(32) NOT NULL DEFAULT 'summary_link',
  `policy_json` json NOT NULL DEFAULT (JSON_OBJECT()),
  `last_member_check_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_conversation_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_conversation_0 (`enterprise_id`, `platform_code`, `platform_scope_key`, `external_conversation_id`),
  KEY idx_chat_conversation_0 (`enterprise_id`, `status`, `updated_at`),
  CONSTRAINT fk_chat_conversation_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_conversation_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_conversation_conversation_kind CHECK (`conversation_kind` IN ('group', 'direct')),
  CONSTRAINT ck_chat_conversation_status CHECK (`status` IN ('discovered', 'enabled', 'disabled', 'archived')),
  CONSTRAINT ck_chat_conversation_ingress_mode CHECK (`ingress_mode` IN ('single_bot', 'multi_bot')),
  CONSTRAINT ck_chat_conversation_trigger_mode CHECK (`trigger_mode` IN ('mention', 'default_entry')),
  CONSTRAINT ck_chat_conversation_result_policy CHECK (`result_policy` IN ('summary_link', 'full_allowed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部群聊或私聊及明确启用范围';

CREATE TABLE chat_conversation_bot (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `conversation_id` varchar(100) NOT NULL,
  `bot_binding_id` varchar(100) NOT NULL,
  `external_chat_id` varchar(191) COLLATE utf8mb4_bin NOT NULL,
  `membership_status` varchar(32) NOT NULL DEFAULT 'unknown',
  `receive_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `send_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `collaboration_enabled` tinyint(1) NOT NULL DEFAULT 0,
  `verified_at` datetime(3) DEFAULT NULL,
  `verification_source` varchar(32) NOT NULL DEFAULT 'platform_query',
  `last_error_code` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_conversation_bot_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_conversation_bot_0 (`enterprise_id`, `conversation_id`, `bot_binding_id`),
  KEY idx_chat_conversation_bot_0 (`enterprise_id`, `bot_binding_id`, `external_chat_id`),
  CONSTRAINT fk_chat_conversation_bot_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_conversation_bot_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_conversation_bot_membership_status CHECK (`membership_status` IN ('unknown', 'present', 'left', 'blocked')),
  CONSTRAINT ck_chat_conversation_bot_receive_enabled CHECK (`receive_enabled` IN (0, 1)),
  CONSTRAINT ck_chat_conversation_bot_send_enabled CHECK (`send_enabled` IN (0, 1)),
  CONSTRAINT ck_chat_conversation_bot_collaboration_enabled CHECK (`collaboration_enabled` IN (0, 1)),
  CONSTRAINT ck_chat_conversation_bot_verification_source CHECK (`verification_source` IN ('platform_query', 'platform_event', 'verified_message', 'manual_test'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话内每个机器人的实际地址与资格';

CREATE TABLE chat_inbound_receipt (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `bot_binding_id` varchar(100) NOT NULL,
  `connection_revision` bigint NOT NULL DEFAULT 0,
  `connection_lease_version` bigint NOT NULL DEFAULT 0,
  `platform_event_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `receipt_key` varchar(64) NOT NULL,
  `event_type` varchar(80) NOT NULL,
  `platform_message_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `received_at` datetime(3) NOT NULL,
  `payload_encrypted` json NOT NULL,
  `message_id` varchar(100) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'received',
  `attempt_count` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(3) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `lease_owner` varchar(100) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_inbound_receipt_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_inbound_receipt_0 (`enterprise_id`, `bot_binding_id`, `receipt_key`),
  KEY idx_chat_inbound_receipt_0 (`status`, `next_attempt_at`, `lease_until`, `id`),
  CONSTRAINT fk_chat_inbound_receipt_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_inbound_receipt_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_inbound_receipt_status CHECK (`status` IN ('received', 'processing', 'normalized', 'ignored', 'retry_wait', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='先保存后确认的消息接收记录';

CREATE TABLE chat_message (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `conversation_id` varchar(100) NOT NULL,
  `task_id` varchar(100) DEFAULT NULL,
  `canonical_key` varchar(64) NOT NULL,
  `platform_message_id` varchar(191) COLLATE utf8mb4_bin NOT NULL,
  `thread_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `reply_to_platform_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `sender_kind` varchar(32) NOT NULL DEFAULT 'user',
  `sender_user_id` varchar(100) DEFAULT NULL,
  `origin_binding_id` varchar(100) DEFAULT NULL,
  `sender_identities_encrypted` json NOT NULL,
  `message_type` varchar(32) NOT NULL,
  `content_encrypted` json NOT NULL,
  `mentions_json` json NOT NULL DEFAULT (JSON_ARRAY()),
  `platform_created_at` datetime(3) NOT NULL,
  `received_at` datetime(3) NOT NULL,
  `routing_status` varchar(32) NOT NULL DEFAULT 'pending',
  `content_hash` varchar(64) NOT NULL,
  `content_version` bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_message_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_message_0 (`enterprise_id`, `conversation_id`, `canonical_key`),
  KEY idx_chat_message_0 (`enterprise_id`, `task_id`, `platform_created_at`, `id`),
  KEY idx_chat_message_1 (`enterprise_id`, `conversation_id`, `platform_message_id`),
  KEY idx_chat_message_2 (`routing_status`, `received_at`, `id`),
  CONSTRAINT fk_chat_message_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_message_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_message_sender_kind CHECK (`sender_kind` IN ('user', 'self_bot', 'other_bot', 'unknown')),
  CONSTRAINT ck_chat_message_routing_status CHECK (`routing_status` IN ('pending', 'awaiting_binding', 'awaiting_clarification', 'routed', 'ignored', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='去重后的会话消息及来源身份';

CREATE TABLE chat_message_resource (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `message_id` varchar(100) NOT NULL,
  `resource_ordinal` int NOT NULL DEFAULT 0,
  `source_bot_id` varchar(100) NOT NULL,
  `platform_resource_key` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `fetch_material_encrypted` json DEFAULT NULL,
  `file_id` varchar(100) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'pending',
  `size_bytes` bigint NOT NULL DEFAULT 0,
  `sha256` varchar(64) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(3) DEFAULT NULL,
  `lease_owner` varchar(100) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_message_resource_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_message_resource_0 (`enterprise_id`, `message_id`, `resource_ordinal`),
  KEY idx_chat_message_resource_0 (`status`, `next_attempt_at`, `lease_until`),
  CONSTRAINT fk_chat_message_resource_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_message_resource_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_message_resource_status CHECK (`status` IN ('pending', 'downloading', 'ready', 'unsupported', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息附件的受控下载及本地文件关联';

CREATE TABLE chat_outbound_message (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `conversation_id` varchar(100) NOT NULL,
  `task_id` varchar(100) DEFAULT NULL,
  `action_id` varchar(100) DEFAULT NULL,
  `bot_binding_id` varchar(100) NOT NULL,
  `kind` varchar(32) NOT NULL,
  `dedupe_key` varchar(64) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `task_sequence` bigint NOT NULL DEFAULT 0,
  `part_no` int NOT NULL DEFAULT 0,
  `depends_on_outbound_id` varchar(100) DEFAULT NULL,
  `supersedes_outbound_id` varchar(100) DEFAULT NULL,
  `payload_encrypted` json NOT NULL,
  `payload_hash` varchar(64) NOT NULL,
  `rendered_payload_encrypted` json DEFAULT NULL,
  `rendered_hash` varchar(64) DEFAULT NULL,
  `prepared_at` datetime(3) DEFAULT NULL,
  `input_revision` bigint NOT NULL DEFAULT 0,
  `status` varchar(32) NOT NULL DEFAULT 'pending',
  `attempt_count` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(3) DEFAULT NULL,
  `first_submitted_at` datetime(3) DEFAULT NULL,
  `platform_message_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `platform_thread_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `lease_owner` varchar(100) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_outbound_message_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_outbound_message_0 (`enterprise_id`, `dedupe_key`),
  UNIQUE KEY uk_chat_outbound_message_1 (`enterprise_id`, `bot_binding_id`, `request_id`),
  KEY idx_chat_outbound_message_0 (`status`, `next_attempt_at`, `lease_until`, `id`),
  KEY idx_chat_outbound_message_1 (`enterprise_id`, `task_id`, `task_sequence`, `part_no`),
  CONSTRAINT fk_chat_outbound_message_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_outbound_message_revision CHECK (revision > 0),
  CONSTRAINT ck_chat_outbound_message_status CHECK (`status` IN ('pending', 'retry_wait', 'leased', 'sending', 'sent', 'unknown', 'failed', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立于业务执行的群消息发送工作';

CREATE TABLE chat_outbound_attempt (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `outbound_id` varchar(100) NOT NULL,
  `attempt_no` int NOT NULL DEFAULT 0,
  `started_at` datetime(3) NOT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `submitted_at` datetime(3) DEFAULT NULL,
  `outcome` varchar(32) DEFAULT NULL,
  `http_status` int NOT NULL DEFAULT 0,
  `platform_code` varchar(64) DEFAULT NULL,
  `diagnostic_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `retry_after_ms` bigint NOT NULL DEFAULT 0,
  `error_summary` varchar(500) DEFAULT NULL,
  `response_message_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_chat_outbound_attempt_scope (enterprise_id, id),
  UNIQUE KEY uk_chat_outbound_attempt_0 (`enterprise_id`, `outbound_id`, `attempt_no`),
  CONSTRAINT fk_chat_outbound_attempt_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_chat_outbound_attempt_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台发送尝试及脱敏排障信息';

CREATE TABLE collaboration_task (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `conversation_id` varchar(100) NOT NULL,
  `origin_message_id` varchar(100) NOT NULL,
  `initiator_user_id` varchar(100) NOT NULL,
  `origin_user_binding_id` varchar(100) NOT NULL,
  `entry_bot_id` varchar(100) NOT NULL,
  `owner_participant_id` varchar(100) DEFAULT NULL,
  `owner_work_id` varchar(100) DEFAULT NULL,
  `short_code` varchar(20) NOT NULL,
  `objective_encrypted` json NOT NULL,
  `input_revision` bigint NOT NULL DEFAULT 1,
  `status` varchar(32) NOT NULL DEFAULT 'active',
  `output_visibility` varchar(32) NOT NULL DEFAULT 'summary_link',
  `roster_revision` bigint NOT NULL DEFAULT 1,
  `policy_snapshot` json NOT NULL,
  `waived_requested_roles_json` json NOT NULL DEFAULT (JSON_ARRAY()),
  `next_sequence` bigint NOT NULL DEFAULT 1,
  `deadline_at` datetime(3) NOT NULL,
  `work_count` int NOT NULL DEFAULT 0,
  `handoff_count` int NOT NULL DEFAULT 0,
  `run_count` int NOT NULL DEFAULT 0,
  `final_artifact_id` varchar(100) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_task_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_task_0 (`enterprise_id`, `origin_message_id`),
  UNIQUE KEY uk_collaboration_task_1 (`enterprise_id`, `conversation_id`, `short_code`),
  KEY idx_collaboration_task_0 (`enterprise_id`, `initiator_user_id`, `status`, `created_at`),
  KEY idx_collaboration_task_1 (`status`, `deadline_at`),
  CONSTRAINT fk_collaboration_task_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_task_revision CHECK (revision > 0),
  CONSTRAINT ck_collaboration_task_status CHECK (`status` IN ('active', 'waiting_input', 'waiting_approval', 'blocked', 'cancelling', 'completed', 'failed', 'cancelled')),
  CONSTRAINT ck_collaboration_task_output_visibility CHECK (`output_visibility` IN ('summary_link', 'chat_full'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原发起人的协作总任务';

CREATE TABLE collaboration_participant (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `bot_binding_id` varchar(100) NOT NULL,
  `agent_id` varchar(100) NOT NULL,
  `agent_version_id` varchar(100) NOT NULL,
  `binding_revision` bigint NOT NULL DEFAULT 1,
  `role_name` varchar(80) NOT NULL,
  `description_snapshot` varchar(500) NOT NULL,
  `availability` varchar(32) NOT NULL DEFAULT 'available',
  `explicitly_requested` tinyint(1) NOT NULL DEFAULT 0,
  `conversation_id` varchar(100) DEFAULT NULL,
  `project_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_participant_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_participant_0 (`enterprise_id`, `task_id`, `bot_binding_id`),
  UNIQUE KEY uk_collaboration_participant_1 (`enterprise_id`, `conversation_id`),
  CONSTRAINT fk_collaboration_participant_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_participant_revision CHECK (revision > 0),
  CONSTRAINT ck_collaboration_participant_availability CHECK (`availability` IN ('available', 'revoked', 'left')),
  CONSTRAINT ck_collaboration_participant_explicitly_requested CHECK (`explicitly_requested` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务中固定版本的岗位与独立会话';

CREATE TABLE collaboration_work_item (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `participant_id` varchar(100) NOT NULL,
  `requester_work_id` varchar(100) DEFAULT NULL,
  `origin_action_id` varchar(100) NOT NULL,
  `purpose` varchar(32) NOT NULL DEFAULT 'lead',
  `required` tinyint(1) NOT NULL DEFAULT 1,
  `instruction_encrypted` json NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'ready',
  `resume_reason` varchar(32) NOT NULL DEFAULT 'initial',
  `input_revision` bigint NOT NULL DEFAULT 1,
  `context_id` varchar(100) DEFAULT NULL,
  `run_round` int NOT NULL DEFAULT 0,
  `active_run_id` varchar(100) DEFAULT NULL,
  `result_artifact_id` varchar(100) DEFAULT NULL,
  `replacement_work_id` varchar(100) DEFAULT NULL,
  `next_attempt_at` datetime(3) DEFAULT NULL,
  `deadline_at` datetime(3) NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `lease_owner` varchar(100) DEFAULT NULL,
  `lease_until` datetime(3) DEFAULT NULL,
  `lease_version` bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_work_item_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_work_item_0 (`enterprise_id`, `origin_action_id`),
  KEY idx_collaboration_work_item_0 (`status`, `next_attempt_at`, `lease_until`, `id`),
  KEY idx_collaboration_work_item_1 (`enterprise_id`, `task_id`, `participant_id`, `status`),
  CONSTRAINT fk_collaboration_work_item_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_work_item_revision CHECK (revision > 0),
  CONSTRAINT ck_collaboration_work_item_purpose CHECK (`purpose` IN ('lead', 'assist', 'continuation')),
  CONSTRAINT ck_collaboration_work_item_required CHECK (`required` IN (0, 1)),
  CONSTRAINT ck_collaboration_work_item_status CHECK (`status` IN ('blocked', 'ready', 'running', 'waiting_dependency', 'waiting_input', 'waiting_approval', 'completed', 'handed_off', 'failed', 'cancelled', 'superseded')),
  CONSTRAINT ck_collaboration_work_item_resume_reason CHECK (`resume_reason` IN ('initial', 'dependencies_ready', 'input_changed', 'retry', 'role_assignment_missing'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可排队恢复的岗位工作';

CREATE TABLE collaboration_work_dependency (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `work_id` varchar(100) NOT NULL,
  `depends_on_work_id` varchar(100) NOT NULL,
  `phase` varchar(32) NOT NULL DEFAULT 'start',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_work_dependency_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_work_dependency_0 (`enterprise_id`, `work_id`, `depends_on_work_id`, `phase`),
  KEY idx_collaboration_work_dependency_0 (`enterprise_id`, `depends_on_work_id`),
  CONSTRAINT fk_collaboration_work_dependency_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_work_dependency_revision CHECK (revision > 0),
  CONSTRAINT ck_collaboration_work_dependency_phase CHECK (`phase` IN ('start', 'resume'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工作启动或恢复需要完成的工作';

CREATE TABLE collaboration_action (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `work_id` varchar(100) DEFAULT NULL,
  `source_run_id` varchar(100) DEFAULT NULL,
  `source_tool_call_id` varchar(100) DEFAULT NULL,
  `operation_key` varchar(191) NOT NULL,
  `action_type` varchar(32) NOT NULL,
  `input_revision` bigint NOT NULL DEFAULT 1,
  `sequence` bigint NOT NULL DEFAULT 1,
  `arguments_hash` varchar(64) NOT NULL,
  `payload_encrypted` json NOT NULL,
  `result_encrypted` json NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_action_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_action_0 (`enterprise_id`, `operation_key`),
  UNIQUE KEY uk_collaboration_action_1 (`enterprise_id`, `task_id`, `sequence`),
  CONSTRAINT fk_collaboration_action_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_action_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与工具结果一起提交的协作事实';

CREATE TABLE collaboration_context (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `work_id` varchar(100) NOT NULL,
  `run_round` int NOT NULL DEFAULT 1,
  `input_revision` bigint NOT NULL DEFAULT 1,
  `schema_version` int NOT NULL DEFAULT 1,
  `content_encrypted` json NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `source_refs_json` json NOT NULL,
  `size_chars` int NOT NULL DEFAULT 0,
  `omitted_message_count` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_context_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_context_0 (`enterprise_id`, `work_id`, `run_round`),
  CONSTRAINT fk_collaboration_context_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_context_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本轮不可变的任务背景与成果来源';

CREATE TABLE collaboration_artifact (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `producer_work_id` varchar(100) NOT NULL,
  `producer_run_id` varchar(100) DEFAULT NULL,
  `artifact_key` varchar(100) NOT NULL,
  `artifact_version` int NOT NULL DEFAULT 1,
  `kind` varchar(32) NOT NULL DEFAULT 'text',
  `schema_name` varchar(80) DEFAULT NULL,
  `title` varchar(160) NOT NULL,
  `summary_encrypted` json NOT NULL,
  `content_encrypted` json DEFAULT NULL,
  `file_id` varchar(100) DEFAULT NULL,
  `sha256` varchar(64) NOT NULL,
  `input_revision` bigint NOT NULL DEFAULT 1,
  `visibility` varchar(32) NOT NULL DEFAULT 'task_private',
  `status` varchar(32) NOT NULL DEFAULT 'ready',
  `supersedes_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_artifact_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_artifact_0 (`enterprise_id`, `task_id`, `artifact_key`, `artifact_version`),
  CONSTRAINT fk_collaboration_artifact_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_artifact_revision CHECK (revision > 0),
  CONSTRAINT ck_collaboration_artifact_kind CHECK (`kind` IN ('text', 'json', 'file')),
  CONSTRAINT ck_collaboration_artifact_visibility CHECK (`visibility` IN ('task_private', 'chat_shared')),
  CONSTRAINT ck_collaboration_artifact_status CHECK (`status` IN ('ready', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变版本的文字数据或文件成果';

CREATE TABLE collaboration_run_link (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `task_id` varchar(100) NOT NULL,
  `work_id` varchar(100) NOT NULL,
  `participant_id` varchar(100) NOT NULL,
  `run_round` int NOT NULL DEFAULT 1,
  `run_id` varchar(100) NOT NULL,
  `context_id` varchar(100) NOT NULL,
  `input_revision` bigint NOT NULL DEFAULT 1,
  `submission_key` varchar(191) NOT NULL,
  `terminal_observed_at` datetime(3) DEFAULT NULL,
  `last_status` varchar(32) NOT NULL,
  `requested_stop_reason` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_collaboration_run_link_scope (enterprise_id, id),
  UNIQUE KEY uk_collaboration_run_link_0 (`enterprise_id`, `run_id`),
  UNIQUE KEY uk_collaboration_run_link_1 (`enterprise_id`, `work_id`, `run_round`),
  UNIQUE KEY uk_collaboration_run_link_2 (`enterprise_id`, `submission_key`),
  KEY idx_collaboration_run_link_0 (`terminal_observed_at`, `updated_at`, `id`),
  CONSTRAINT fk_collaboration_run_link_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT ck_collaboration_run_link_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='岗位轮次与现有执行之间的唯一关联';

ALTER TABLE chat_bot_binding ADD CONSTRAINT fk_chat_bot_binding_1 FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id);
ALTER TABLE chat_bot_binding ADD CONSTRAINT fk_chat_bot_binding_2 FOREIGN KEY (enterprise_id, agent_id) REFERENCES resource(enterprise_id, id);
ALTER TABLE chat_bot_binding ADD CONSTRAINT fk_chat_bot_binding_3 FOREIGN KEY (enterprise_id, pinned_version_id) REFERENCES resource_version(enterprise_id, id);
ALTER TABLE chat_conversation ADD CONSTRAINT fk_chat_conversation_1 FOREIGN KEY (enterprise_id, direct_binding_id) REFERENCES user_channel_binding(enterprise_id, id);
ALTER TABLE chat_conversation ADD CONSTRAINT fk_chat_conversation_2 FOREIGN KEY (enterprise_id, ingress_bot_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_conversation ADD CONSTRAINT fk_chat_conversation_3 FOREIGN KEY (enterprise_id, default_bot_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_conversation_bot ADD CONSTRAINT fk_chat_conversation_bot_1 FOREIGN KEY (enterprise_id, conversation_id) REFERENCES chat_conversation(enterprise_id, id);
ALTER TABLE chat_conversation_bot ADD CONSTRAINT fk_chat_conversation_bot_2 FOREIGN KEY (enterprise_id, bot_binding_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_inbound_receipt ADD CONSTRAINT fk_chat_inbound_receipt_1 FOREIGN KEY (enterprise_id, bot_binding_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_inbound_receipt ADD CONSTRAINT fk_chat_inbound_receipt_2 FOREIGN KEY (enterprise_id, message_id) REFERENCES chat_message(enterprise_id, id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_1 FOREIGN KEY (enterprise_id, conversation_id) REFERENCES chat_conversation(enterprise_id, id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_2 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_3 FOREIGN KEY (enterprise_id, origin_binding_id) REFERENCES user_channel_binding(enterprise_id, id);
ALTER TABLE chat_message_resource ADD CONSTRAINT fk_chat_message_resource_1 FOREIGN KEY (enterprise_id, message_id) REFERENCES chat_message(enterprise_id, id);
ALTER TABLE chat_message_resource ADD CONSTRAINT fk_chat_message_resource_2 FOREIGN KEY (enterprise_id, source_bot_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_message_resource ADD CONSTRAINT fk_chat_message_resource_3 FOREIGN KEY (enterprise_id, file_id) REFERENCES file_object(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_1 FOREIGN KEY (enterprise_id, conversation_id) REFERENCES chat_conversation(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_2 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_3 FOREIGN KEY (enterprise_id, action_id) REFERENCES collaboration_action(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_4 FOREIGN KEY (enterprise_id, bot_binding_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_5 FOREIGN KEY (enterprise_id, depends_on_outbound_id) REFERENCES chat_outbound_message(enterprise_id, id);
ALTER TABLE chat_outbound_message ADD CONSTRAINT fk_chat_outbound_message_6 FOREIGN KEY (enterprise_id, supersedes_outbound_id) REFERENCES chat_outbound_message(enterprise_id, id);
ALTER TABLE chat_outbound_attempt ADD CONSTRAINT fk_chat_outbound_attempt_1 FOREIGN KEY (enterprise_id, outbound_id) REFERENCES chat_outbound_message(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_1 FOREIGN KEY (enterprise_id, conversation_id) REFERENCES chat_conversation(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_2 FOREIGN KEY (enterprise_id, origin_message_id) REFERENCES chat_message(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_3 FOREIGN KEY (enterprise_id, origin_user_binding_id) REFERENCES user_channel_binding(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_4 FOREIGN KEY (enterprise_id, entry_bot_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_5 FOREIGN KEY (enterprise_id, owner_participant_id) REFERENCES collaboration_participant(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_6 FOREIGN KEY (enterprise_id, owner_work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_task ADD CONSTRAINT fk_collaboration_task_7 FOREIGN KEY (enterprise_id, final_artifact_id) REFERENCES collaboration_artifact(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_2 FOREIGN KEY (enterprise_id, bot_binding_id) REFERENCES chat_bot_binding(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_3 FOREIGN KEY (enterprise_id, agent_id) REFERENCES resource(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_4 FOREIGN KEY (enterprise_id, agent_version_id) REFERENCES resource_version(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_5 FOREIGN KEY (enterprise_id, conversation_id) REFERENCES agent_conversation(enterprise_id, id);
ALTER TABLE collaboration_participant ADD CONSTRAINT fk_collaboration_participant_6 FOREIGN KEY (enterprise_id, project_id) REFERENCES workspace_project(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_2 FOREIGN KEY (enterprise_id, participant_id) REFERENCES collaboration_participant(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_3 FOREIGN KEY (enterprise_id, requester_work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_4 FOREIGN KEY (enterprise_id, origin_action_id) REFERENCES collaboration_action(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_5 FOREIGN KEY (enterprise_id, context_id) REFERENCES collaboration_context(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_6 FOREIGN KEY (enterprise_id, active_run_id) REFERENCES agent_run(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_7 FOREIGN KEY (enterprise_id, result_artifact_id) REFERENCES collaboration_artifact(enterprise_id, id);
ALTER TABLE collaboration_work_item ADD CONSTRAINT fk_collaboration_work_item_8 FOREIGN KEY (enterprise_id, replacement_work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_work_dependency ADD CONSTRAINT fk_collaboration_work_dependency_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_work_dependency ADD CONSTRAINT fk_collaboration_work_dependency_2 FOREIGN KEY (enterprise_id, work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_work_dependency ADD CONSTRAINT fk_collaboration_work_dependency_3 FOREIGN KEY (enterprise_id, depends_on_work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_action ADD CONSTRAINT fk_collaboration_action_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_action ADD CONSTRAINT fk_collaboration_action_2 FOREIGN KEY (enterprise_id, work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_action ADD CONSTRAINT fk_collaboration_action_3 FOREIGN KEY (enterprise_id, source_run_id) REFERENCES agent_run(enterprise_id, id);
ALTER TABLE collaboration_context ADD CONSTRAINT fk_collaboration_context_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_context ADD CONSTRAINT fk_collaboration_context_2 FOREIGN KEY (enterprise_id, work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT fk_collaboration_artifact_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT fk_collaboration_artifact_2 FOREIGN KEY (enterprise_id, producer_work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT fk_collaboration_artifact_3 FOREIGN KEY (enterprise_id, producer_run_id) REFERENCES agent_run(enterprise_id, id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT fk_collaboration_artifact_4 FOREIGN KEY (enterprise_id, file_id) REFERENCES file_object(enterprise_id, id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT fk_collaboration_artifact_5 FOREIGN KEY (enterprise_id, supersedes_id) REFERENCES collaboration_artifact(enterprise_id, id);
ALTER TABLE collaboration_run_link ADD CONSTRAINT fk_collaboration_run_link_1 FOREIGN KEY (enterprise_id, task_id) REFERENCES collaboration_task(enterprise_id, id);
ALTER TABLE collaboration_run_link ADD CONSTRAINT fk_collaboration_run_link_2 FOREIGN KEY (enterprise_id, work_id) REFERENCES collaboration_work_item(enterprise_id, id);
ALTER TABLE collaboration_run_link ADD CONSTRAINT fk_collaboration_run_link_3 FOREIGN KEY (enterprise_id, participant_id) REFERENCES collaboration_participant(enterprise_id, id);
ALTER TABLE collaboration_run_link ADD CONSTRAINT fk_collaboration_run_link_4 FOREIGN KEY (enterprise_id, run_id) REFERENCES agent_run(enterprise_id, id);
ALTER TABLE collaboration_run_link ADD CONSTRAINT fk_collaboration_run_link_5 FOREIGN KEY (enterprise_id, context_id) REFERENCES collaboration_context(enterprise_id, id);

ALTER TABLE collaboration_task ADD CONSTRAINT fk_collab_task_member FOREIGN KEY (enterprise_id, initiator_user_id) REFERENCES enterprise_member(enterprise_id, user_id);
ALTER TABLE collaboration_work_dependency ADD CONSTRAINT ck_collab_dependency_self CHECK (work_id <> depends_on_work_id);
ALTER TABLE collaboration_artifact ADD CONSTRAINT ck_collab_artifact_content CHECK ((kind = 'file' AND file_id IS NOT NULL AND content_encrypted IS NULL) OR (kind <> 'file' AND content_encrypted IS NOT NULL AND file_id IS NULL));
