-- 当前平台的完整结构，仅用于空数据库初始化。
-- 字段、索引、外键和检查约束均在建表时一次创建。
-- 部分表相互引用，只在创建空表期间关闭当前连接的外键检查；写入初始数据前恢复。
SET
@agenteam_init_foreign_key_checks = @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `agent_conversation`
(
  `id`                  varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`             varchar(100) NOT NULL COMMENT '会话所有者',
  `agent_id`            varchar(100)          DEFAULT NULL COMMENT '智能体身份；单独测试工作流时为空',
  `preview_resource_id` varchar(100)          DEFAULT NULL COMMENT '单独测试的工作流资源；员工会话为空',
  `agent_version_id`    varchar(100)          DEFAULT NULL COMMENT '固定发布版本；草稿预览可为空',
  `hire_id`             varchar(100)          DEFAULT NULL COMMENT '正式会话使用的雇佣关系；预览可为空',
  `title`               varchar(255) NOT NULL COMMENT '会话标题',
  `title_customized`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否由用户改过标题',
  `mode`                varchar(20)  NOT NULL DEFAULT 'normal' COMMENT '正式或调试',
  `approval_policy`     varchar(20)  NOT NULL DEFAULT 'default' COMMENT '工具审批策略：默认、自动批准、完全访问',
  `status`              varchar(20)  NOT NULL DEFAULT 'active' COMMENT '会话可用状态',
  `favorite`            tinyint(1) NOT NULL DEFAULT '0' COMMENT '本人收藏',
  `active_run_id`       varchar(100)          DEFAULT NULL COMMENT '唯一未结束执行；修改前必须锁会话行',
  `last_sequence`       bigint       NOT NULL DEFAULT '0' COMMENT '会话内已保存的连续事件序号',
  `deleted_at`          datetime(3) DEFAULT NULL COMMENT '标记删除时间',
  `revision`            bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `model_profile_id`    varchar(100)          DEFAULT NULL,
  `reasoning_effort`    varchar(20)           DEFAULT NULL,
  `project_id`          varchar(100)          DEFAULT NULL,
  `event_delivery_mode` varchar(16)  NOT NULL DEFAULT 'database',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_conversation_1` (`enterprise_id`,`user_id`,`id`),
  UNIQUE KEY `uk_agent_conversation_2` (`enterprise_id`,`id`),
  KEY                   `idx_agent_conversation_1` (`enterprise_id`,`user_id`,`status`,`updated_at`,`id`),
  KEY                   `idx_agent_conversation_2` (`enterprise_id`,`user_id`,`agent_id`,`updated_at`,`id`),
  KEY                   `idx_agent_conversation_3` (`enterprise_id`,`agent_id`),
  KEY                   `idx_agent_conversation_4` (`enterprise_id`,`agent_id`,`agent_version_id`),
  KEY                   `idx_agent_conversation_5` (`enterprise_id`,`user_id`,`agent_id`,`hire_id`),
  KEY                   `idx_agent_conversation_6` (`enterprise_id`,`id`,`active_run_id`),
  KEY                   `idx_agent_conversation_retention` (`mode`,`created_at`,`id`),
  KEY                   `idx_agent_conversation_preview_resource` (`enterprise_id`,`preview_resource_id`),
  KEY                   `idx_conversation_project` (`enterprise_id`,`user_id`,`project_id`),
  CONSTRAINT `fk_agent_conversation_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_conversation_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_conversation_3` FOREIGN KEY (`enterprise_id`, `agent_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_conversation_4` FOREIGN KEY (`enterprise_id`, `user_id`, `agent_id`, `hire_id`) REFERENCES `agent_hire` (`enterprise_id`, `user_id`, `agent_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_conversation_5` FOREIGN KEY (`enterprise_id`, `id`, `active_run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_conversation_preview_resource` FOREIGN KEY (`enterprise_id`, `preview_resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_conversation_project` FOREIGN KEY (`enterprise_id`, `user_id`, `project_id`) REFERENCES `workspace_project` (`enterprise_id`, `user_id`, `id`),
  CONSTRAINT `ck_agent_conversation_1` CHECK ((`last_sequence` >= 0)),
  CONSTRAINT `ck_agent_conversation_2` CHECK (((`mode` <> _utf8mb4'normal') or
                                               ((`agent_id` is not null) and (`agent_version_id` is not null) and
                                                (`hire_id` is not null)))),
  CONSTRAINT `ck_agent_conversation_3` CHECK ((`mode` in (_utf8mb4'normal', _utf8mb4'preview'))),
  CONSTRAINT `ck_agent_conversation_4` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'archived', _utf8mb4'deleted'))),
  CONSTRAINT `ck_agent_conversation_preview_resource` CHECK ((
    ((`agent_id` is not null) and (`preview_resource_id` is null)) or
    ((`mode` = _utf8mb4'preview') and (`agent_id` is null) and (`preview_resource_id` is not null) and
     (`agent_version_id` is null) and (`hire_id` is null)))),
  CONSTRAINT `ck_conversation_approval_policy` CHECK ((`approval_policy` in (_utf8mb4'default', _utf8mb4'auto_approve',
                                                                             _utf8mb4'full_access')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人私有会话，固定使用的智能体版本与连续事件序号';

CREATE TABLE `agent_event`
(
  `event_id`              varchar(100) NOT NULL COMMENT '全局数据库事件主键，不作为新协议连续序号',
  `enterprise_id`         varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `stream_id`             varchar(64)           DEFAULT NULL COMMENT 'Redis 分发记录编号',
  `conversation_id`       varchar(100) NOT NULL COMMENT '所属会话',
  `run_id`                varchar(100) NOT NULL COMMENT '所属执行',
  `sequence_no`           bigint       NOT NULL COMMENT '本次执行内的事件顺序',
  `conversation_sequence` bigint       NOT NULL COMMENT '会话内连续事件序号',
  `protocol_version`      int          NOT NULL DEFAULT '1' COMMENT '当前事件结构版本',
  `event_type`            varchar(128) NOT NULL COMMENT '业务事件名称',
  `payload_json`          json         NOT NULL COMMENT '已脱敏、具有类型的事件正文',
  `payload_hash`          char(64)              DEFAULT NULL COMMENT '分发去重校验摘要',
  `published_at`          datetime(3) DEFAULT NULL COMMENT '已确认分发到近期事件存储的时间',
  `started_at`            datetime(3) DEFAULT NULL COMMENT '合并内容开始时间',
  `finished_at`           datetime(3) DEFAULT NULL COMMENT '合并内容结束时间',
  `created_at`            datetime(3) NOT NULL COMMENT '数据库提交批次时间',
  `storage_version`       tinyint      NOT NULL DEFAULT '1' COMMENT '数据库编码版本：1 为独立事件，2 为可还原的合并增量',
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_agent_event_1` (`enterprise_id`,`conversation_id`,`conversation_sequence`),
  UNIQUE KEY `uk_agent_event_2` (`run_id`,`sequence_no`),
  KEY                     `idx_agent_event_1` (`enterprise_id`,`conversation_id`,`created_at`),
  KEY                     `idx_agent_event_2` (`published_at`,`created_at`),
  KEY                     `idx_agent_event_3` (`stream_id`),
  KEY                     `idx_agent_event_4` (`enterprise_id`,`conversation_id`,`run_id`),
  KEY                     `idx_agent_event_storage` (`storage_version`,`event_type`,`created_at`),
  CONSTRAINT `fk_agent_event_1` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_event_1` CHECK ((`protocol_version` = 1)),
  CONSTRAINT `ck_agent_event_2` CHECK ((`conversation_sequence` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规范化事件、合并批次与分发状态';

CREATE TABLE `agent_hire`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       varchar(100) NOT NULL COMMENT '雇佣成员',
  `agent_id`      varchar(100) NOT NULL COMMENT '智能体身份',
  `status`        varchar(20)  NOT NULL DEFAULT 'active' COMMENT '使用关系状态',
  `hired_at`      datetime(3) NOT NULL COMMENT '本次恢复或建立雇佣时间',
  `last_used_at`  datetime(3) DEFAULT NULL COMMENT '最近实际开始执行时间',
  `paused_at`     datetime(3) DEFAULT NULL COMMENT '暂停时间',
  `terminated_at` datetime(3) DEFAULT NULL COMMENT '解除时间',
  `revision`      bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_hire_1` (`enterprise_id`,`user_id`,`agent_id`),
  UNIQUE KEY `uk_agent_hire_2` (`enterprise_id`,`user_id`,`agent_id`,`id`),
  UNIQUE KEY `uk_agent_hire_3` (`enterprise_id`,`user_id`,`id`),
  UNIQUE KEY `uk_agent_hire_4` (`enterprise_id`,`id`),
  KEY             `idx_agent_hire_1` (`enterprise_id`,`user_id`,`status`,`last_used_at`,`id`),
  KEY             `idx_agent_hire_2` (`enterprise_id`,`agent_id`),
  CONSTRAINT `fk_agent_hire_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_hire_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_hire_1` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'paused', _utf8mb4'terminated')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人在企业内使用数字员工的唯一关系';

CREATE TABLE `agent_hire_request`
(
  `id`             varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`        varchar(100) NOT NULL COMMENT '申请成员',
  `agent_id`       varchar(100) NOT NULL COMMENT '申请的智能体',
  `status`         varchar(20)  NOT NULL DEFAULT 'pending' COMMENT '申请状态',
  `pending_marker` int                   DEFAULT '1' COMMENT '待处理时为 1，终态为空，保证只有一条待处理申请',
  `request_note`   varchar(500) NOT NULL DEFAULT '' COMMENT '申请说明',
  `decision_note`  varchar(500)          DEFAULT NULL COMMENT '审批说明',
  `decided_by`     varchar(100)          DEFAULT NULL COMMENT '实际审批成员',
  `decided_at`     datetime(3) DEFAULT NULL COMMENT '审批时间',
  `expires_at`     datetime(3) NOT NULL COMMENT '申请失效时间',
  `revision`       bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_hire_request_2` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_agent_hire_request_1` (`enterprise_id`,`user_id`,`agent_id`,`pending_marker`),
  KEY              `idx_agent_hire_request_1` (`enterprise_id`,`status`,`created_at`,`id`),
  KEY              `idx_agent_hire_request_2` (`enterprise_id`,`agent_id`),
  KEY              `idx_agent_hire_request_3` (`enterprise_id`,`decided_by`),
  KEY              `idx_agent_hire_request_expiry` (`status`,`expires_at`,`id`),
  CONSTRAINT `fk_agent_hire_request_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_hire_request_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_hire_request_3` FOREIGN KEY (`enterprise_id`, `decided_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_hire_request_1` CHECK ((
    ((`status` = _utf8mb4'pending') and (`pending_marker` is not null) and (`pending_marker` = 1)) or
    ((`status` <> _utf8mb4'pending') and (`pending_marker` is null)))),
  CONSTRAINT `ck_agent_hire_request_2` CHECK ((`status` in (_utf8mb4'pending', _utf8mb4'approved', _utf8mb4'rejected',
                                                            _utf8mb4'withdrawn', _utf8mb4'expired')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雇佣申请及审批历史';

CREATE TABLE `agent_listing`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `agent_id`      varchar(100) NOT NULL COMMENT '类型必须为 agent 的资源',
  `listed`        tinyint      NOT NULL DEFAULT '0' COMMENT '是否允许新用户从广场发现并雇佣',
  `hire_policy`   varchar(20)  NOT NULL DEFAULT 'automatic' COMMENT '雇佣策略',
  `updated_by`    varchar(100) NOT NULL COMMENT '最后操作人',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `agent_id`),
  KEY             `idx_agent_listing_1` (`enterprise_id`,`updated_by`),
  CONSTRAINT `fk_agent_listing_1` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_listing_2` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_listing_1` CHECK ((`hire_policy` in (_utf8mb4'automatic', _utf8mb4'approval')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体在员工广场的上架和雇佣策略';

CREATE TABLE `agent_memory`
(
  `id`                varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`           varchar(100) NOT NULL COMMENT '偏好所属用户',
  `agent_id`          varchar(100) NOT NULL COMMENT '智能体身份',
  `memory_key`        varchar(50)  NOT NULL COMMENT '允许记忆的主题',
  `content`           varchar(500) NOT NULL COMMENT '本人明确确认的偏好',
  `source_message_id` varchar(100)          DEFAULT NULL COMMENT '来源消息逻辑引用，消息清理时置空',
  `expires_at`        datetime(3) NOT NULL COMMENT '偏好失效时间',
  `revision`          bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_1` (`enterprise_id`,`user_id`,`agent_id`,`memory_key`),
  UNIQUE KEY `uk_agent_memory_2` (`enterprise_id`,`id`),
  KEY                 `idx_agent_memory_1` (`expires_at`),
  KEY                 `idx_agent_memory_2` (`enterprise_id`,`source_message_id`),
  KEY                 `idx_agent_memory_3` (`enterprise_id`,`agent_id`),
  CONSTRAINT `fk_agent_memory_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_memory_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仅保存本人确认的偏好；删除时物理删除正文';

CREATE TABLE `agent_message`
(
  `id`              varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`   varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `conversation_id` varchar(100) NOT NULL COMMENT '所属会话',
  `run_id`          varchar(100)          DEFAULT NULL COMMENT '对应执行；插入事务内可为空',
  `attempt_no`      int          NOT NULL DEFAULT '0' COMMENT '所属尝试，用户输入为 0',
  `role`            varchar(20)  NOT NULL COMMENT '消息角色',
  `content`         longtext     NOT NULL COMMENT '可公开的正文',
  `status`          varchar(32)  NOT NULL COMMENT '消息状态',
  `blocks_json`     json         NOT NULL COMMENT '有稳定编号、父节点、顺序和版本的内容块',
  `context_json`    json         NOT NULL COMMENT '已验证的技能、知识和链接引用',
  `last_sequence`   bigint       NOT NULL DEFAULT '0' COMMENT '该消息最后保存的新协议序号',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_message_1` (`enterprise_id`,`conversation_id`,`id`),
  UNIQUE KEY `uk_agent_message_2` (`enterprise_id`,`id`),
  KEY               `idx_agent_message_1` (`enterprise_id`,`conversation_id`,`created_at`,`id`),
  KEY               `idx_agent_message_2` (`enterprise_id`,`run_id`,`attempt_no`),
  KEY               `idx_agent_message_3` (`enterprise_id`,`conversation_id`,`run_id`),
  CONSTRAINT `fk_agent_message_1` FOREIGN KEY (`enterprise_id`, `conversation_id`) REFERENCES `agent_conversation` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_message_2` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_message_1` CHECK ((`role` in (_utf8mb4'user', _utf8mb4'assistant', _utf8mb4'system'))),
  CONSTRAINT `ck_agent_message_2` CHECK ((`status` in (_utf8mb4'pending', _utf8mb4'streaming', _utf8mb4'completed',
                                                       _utf8mb4'failed', _utf8mb4'cancelled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息及已保存的结构化内容块';

CREATE TABLE `agent_run`
(
  `id`                        varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`             varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `conversation_id`           varchar(100) NOT NULL COMMENT '所属会话',
  `user_id`                   varchar(100) NOT NULL COMMENT '实际发起成员',
  `input_message_id`          varchar(100) NOT NULL COMMENT '固定输入消息',
  `output_message_id`         varchar(100) NOT NULL COMMENT '当前尝试的输出消息',
  `agent_version_id`          varchar(100)          DEFAULT NULL COMMENT '正式发布版本；草稿预览可为空',
  `mode`                      varchar(20)  NOT NULL DEFAULT 'interactive' COMMENT '执行来源',
  `status`                    varchar(32)  NOT NULL DEFAULT 'queued' COMMENT '执行状态',
  `execution_config_json`     json         NOT NULL COMMENT '固定配置、模型配置、依赖和知识处理版本清单；不得含明文凭据',
  `current_attempt_no`        int          NOT NULL DEFAULT '1' COMMENT '当前尝试编号',
  `max_attempts`              int          NOT NULL DEFAULT '1' COMMENT '含初次的最多尝试数',
  `lease_version`             bigint       NOT NULL DEFAULT '0' COMMENT '当前工作进程的递增租约版本',
  `used_steps`                int          NOT NULL DEFAULT '0' COMMENT '父子任务共同使用的步骤数，等待后继续不清零',
  `counted_tools_json`        json         NOT NULL DEFAULT (json_array()) COMMENT '已经计入步骤预算的框架调用编号',
  `active_millis`             bigint       NOT NULL DEFAULT '0' COMMENT '已完成活动时段的毫秒数，不包含等待确认',
  `active_segment_started_at` datetime(3) DEFAULT NULL COMMENT '当前活动时段开始时间',
  `execution_phase`           varchar(32)  NOT NULL DEFAULT 'between_steps' COMMENT '程序中断时正在做什么',
  `queued_at`                 datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本次进入队列的时间，确认后继续时更新',
  `has_step_errors`           tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否存在明确允许继续的步骤错误',
  `last_sequence`             bigint       NOT NULL DEFAULT '0' COMMENT '本执行最新会话序号',
  `started_at`                datetime(3) DEFAULT NULL COMMENT '首次实际开始时间',
  `finished_at`               datetime(3) DEFAULT NULL COMMENT '终态时间',
  `cancel_requested_at`       datetime(3) DEFAULT NULL COMMENT '停止请求时间',
  `next_attempt_at`           datetime(3) DEFAULT NULL COMMENT '自动重试时间',
  `error_code`                varchar(64)           DEFAULT NULL COMMENT '稳定错误代码',
  `error_message`             text COMMENT '脱敏用户错误说明',
  `created_at`                datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`                datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `active_conversation_key`   varchar(100) GENERATED ALWAYS AS ((case
                                                                   when (`status` in
                                                                         (_utf8mb4'queued', _utf8mb4'running',
                                                                          _utf8mb4'waiting_approval',
                                                                          _utf8mb4'cancelling')) then `conversation_id`
                                                                   else NULL end)) STORED COMMENT '数据库计算的活动对象编号，终态为空，禁止应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_1` (`enterprise_id`,`conversation_id`,`id`),
  UNIQUE KEY `uk_agent_run_2` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_agent_run_3` (`enterprise_id`,`active_conversation_key`),
  KEY                         `idx_agent_run_1` (`enterprise_id`,`status`,`created_at`,`id`),
  KEY                         `idx_agent_run_2` (`enterprise_id`,`user_id`,`status`),
  KEY                         `idx_agent_run_3` (`status`,`updated_at`),
  KEY                         `idx_agent_run_4` (`enterprise_id`,`user_id`,`conversation_id`),
  KEY                         `idx_agent_run_5` (`enterprise_id`,`conversation_id`,`input_message_id`),
  KEY                         `idx_agent_run_6` (`enterprise_id`,`conversation_id`,`output_message_id`),
  KEY                         `idx_agent_run_7` (`enterprise_id`,`agent_version_id`),
  CONSTRAINT `fk_agent_run_1` FOREIGN KEY (`enterprise_id`, `user_id`, `conversation_id`) REFERENCES `agent_conversation` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_run_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_run_3` FOREIGN KEY (`enterprise_id`, `conversation_id`, `input_message_id`) REFERENCES `agent_message` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_run_4` FOREIGN KEY (`enterprise_id`, `conversation_id`, `output_message_id`) REFERENCES `agent_message` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_agent_run_5` FOREIGN KEY (`enterprise_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_agent_run_1` CHECK ((`max_attempts` between 1 and 3)),
  CONSTRAINT `ck_agent_run_2` CHECK ((`current_attempt_no` between 1 and `max_attempts`)),
  CONSTRAINT `ck_agent_run_3` CHECK ((`mode` in (_utf8mb4'interactive', _utf8mb4'preview', _utf8mb4'scheduled',
                                                 _utf8mb4'manual_schedule'))),
  CONSTRAINT `ck_agent_run_4` CHECK ((`status` in (_utf8mb4'queued', _utf8mb4'running', _utf8mb4'waiting_approval',
                                                   _utf8mb4'cancelling', _utf8mb4'completed', _utf8mb4'failed',
                                                   _utf8mb4'cancelled'))),
  CONSTRAINT `ck_agent_run_5` CHECK ((`execution_phase` in
                                      (_utf8mb4'between_steps', _utf8mb4'model', _utf8mb4'tools', _utf8mb4'subagent',
                                       _utf8mb4'waiting_approval')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次顶层执行，拥有固定配置、排队状态和次数归属';

CREATE TABLE `announcement`
(
  `id`                   varchar(100) NOT NULL,
  `scope`                varchar(20)  NOT NULL,
  `enterprise_id`        varchar(100)          DEFAULT NULL,
  `title`                varchar(160) NOT NULL,
  `content`              mediumtext   NOT NULL,
  `content_format`       varchar(20)  NOT NULL DEFAULT 'plain_text',
  `level_code`           varchar(32)  NOT NULL,
  `level_priority`       int          NOT NULL,
  `enabled`              tinyint(1) NOT NULL DEFAULT '0',
  `content_version`      bigint       NOT NULL DEFAULT '1',
  `publication_sequence` bigint                DEFAULT NULL,
  `published_at`         datetime(3) DEFAULT NULL,
  `created_by`           varchar(100) NOT NULL,
  `updated_by`           varchar(100) NOT NULL,
  `revision`             bigint       NOT NULL DEFAULT '1',
  `created_at`           datetime(3) NOT NULL,
  `updated_at`           datetime(3) NOT NULL,
  `deleted_at`           datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY                    `idx_announcement_delivery` (`enabled`,`scope`,`enterprise_id`,`level_priority`,`publication_sequence`),
  KEY                    `idx_announcement_management` (`scope`,`enterprise_id`,`updated_at`,`id`),
  KEY                    `fk_announcement_enterprise` (`enterprise_id`),
  KEY                    `fk_announcement_creator` (`created_by`),
  KEY                    `fk_announcement_updater` (`updated_by`),
  KEY                    `fk_announcement_current_publication` (`publication_sequence`),
  CONSTRAINT `fk_announcement_creator` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`id`),
  CONSTRAINT `fk_announcement_current_publication` FOREIGN KEY (`publication_sequence`) REFERENCES `announcement_publication` (`sequence_no`),
  CONSTRAINT `fk_announcement_enterprise` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`),
  CONSTRAINT `fk_announcement_updater` FOREIGN KEY (`updated_by`) REFERENCES `app_user` (`id`),
  CONSTRAINT `ck_announcement_enabled` CHECK (((`enabled` = false) or
                                               ((`publication_sequence` is not null) and (`published_at` is not null)))),
  CONSTRAINT `ck_announcement_scope` CHECK ((((`scope` = _utf8mb4'platform') and (`enterprise_id` is null)) or
                                             ((`scope` = _utf8mb4'enterprise') and (`enterprise_id` is not null)))),
  CONSTRAINT `ck_announcement_version` CHECK (((`content_version` > 0) and (`revision` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `announcement_publication`
(
  `sequence_no`     bigint       NOT NULL AUTO_INCREMENT,
  `announcement_id` varchar(100) NOT NULL,
  `content_version` bigint       NOT NULL,
  `published_at`    datetime(3) NOT NULL,
  `publisher_name`  varchar(128) DEFAULT NULL,
  PRIMARY KEY (`sequence_no`),
  UNIQUE KEY `uk_announcement_publication` (`announcement_id`,`content_version`),
  CONSTRAINT `fk_announcement_publication` FOREIGN KEY (`announcement_id`) REFERENCES `announcement` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `announcement_read`
(
  `announcement_id` varchar(100) NOT NULL,
  `user_id`         varchar(100) NOT NULL,
  `content_version` bigint       NOT NULL,
  `read_at`         datetime(3) NOT NULL,
  PRIMARY KEY (`announcement_id`, `user_id`, `content_version`),
  KEY               `idx_announcement_read_user` (`user_id`),
  CONSTRAINT `fk_announcement_read_announcement` FOREIGN KEY (`announcement_id`) REFERENCES `announcement` (`id`),
  CONSTRAINT `fk_announcement_read_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `api_request`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) DEFAULT NULL COMMENT '企业动作的企业，全局动作为空',
  `user_id`       varchar(100) NOT NULL COMMENT '已认证发起用户',
  `scope_key`     varchar(128) NOT NULL COMMENT '服务端生成的用户全局或企业范围',
  `operation_key` char(64)     NOT NULL COMMENT '方法与目标路径的摘要',
  `request_key`   varchar(100) NOT NULL COMMENT '客户端同次操作的随机重复请求键',
  `request_hash`  char(64)     NOT NULL COMMENT '规范化请求正文摘要',
  `status`        varchar(20)  NOT NULL COMMENT '处理中或已完成',
  `http_status`   int          DEFAULT NULL COMMENT '已完成请求的 HTTP 状态',
  `response_json` json         DEFAULT NULL COMMENT '可安全重复返回的业务结果',
  `expires_at`    datetime(3) NOT NULL COMMENT '最少保留 24 小时',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_request_1` (`user_id`,`scope_key`,`operation_key`,`request_key`),
  UNIQUE KEY `uk_api_request_2` (`enterprise_id`,`id`),
  KEY             `idx_api_request_1` (`expires_at`),
  CONSTRAINT `fk_api_request_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_api_request_1` CHECK ((`status` in (_utf8mb4'processing', _utf8mb4'completed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='修改请求去重记录，事务结果可重复读取';

CREATE TABLE `app_user`
(
  `id`                  varchar(100) NOT NULL,
  `username`            varchar(128) NOT NULL,
  `username_normalized` varchar(128) NOT NULL COMMENT '去空白并转小写的登录比较值',
  `password_hash`       varchar(255) NOT NULL,
  `display_name`        varchar(128) NOT NULL,
  `email`               varchar(254)          DEFAULT NULL COMMENT '当前邮箱',
  `email_normalized`    varchar(254)          DEFAULT NULL COMMENT '用于唯一性比较的邮箱',
  `email_verified_at`   datetime(3) DEFAULT NULL COMMENT '邮箱验证完成时间',
  `status`              varchar(32)  NOT NULL DEFAULT 'active',
  `is_super_admin`      tinyint      NOT NULL DEFAULT '0',
  `last_enterprise_id`  varchar(100)          DEFAULT NULL,
  `session_version`     bigint       NOT NULL DEFAULT '1' COMMENT '登录会话撤销版本',
  `password_changed_at` datetime(3) DEFAULT NULL COMMENT '最近密码变更时间',
  `revision`            bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本',
  `created_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_user_username_normalized` (`username_normalized`),
  UNIQUE KEY `uk_app_user_email_normalized` (`email_normalized`),
  KEY                   `idx_app_user_status` (`status`),
  CONSTRAINT `ck_app_user_demo_identity` CHECK ((
    ((`id` <> _utf8mb4'system-demo-user') and (`username_normalized` <> _utf8mb4'test') and
     (lower(trim(`username`)) <> _utf8mb4'test')) or
    ((cast(`id` as char charset binary) = cast(_utf8mb4'system-demo-user' as char charset binary)) and
     (cast(`username` as char charset binary) = cast(_utf8mb4'test' as char charset binary)) and
     (cast(`username_normalized` as char charset binary) = cast(_utf8mb4'test' as char charset binary)) and
     (`is_super_admin` = 0)))),
  CONSTRAINT `ck_app_user_status` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `audit_event`
(
  `id`                   varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        varchar(100) DEFAULT NULL COMMENT '所属企业，全局身份动作可为空',
  `actor_user_id`        varchar(100) NOT NULL COMMENT '操作者逻辑编号，允许保留历史',
  `actor_name`           varchar(128) NOT NULL COMMENT '动作发生时显示名',
  `action`               varchar(128) NOT NULL COMMENT '正式操作名称',
  `object_type`          varchar(32)  NOT NULL COMMENT '目标对象类型',
  `object_id`            varchar(100) DEFAULT NULL COMMENT '目标逻辑编号',
  `result`               varchar(20)  NOT NULL COMMENT '成功或失败',
  `summary`              varchar(500) NOT NULL COMMENT '中文摘要',
  `detail_redacted_json` json         NOT NULL COMMENT '仅包含必要脱敏差异',
  `request_id`           varchar(100) NOT NULL COMMENT '服务端请求编号',
  `created_at`           datetime(3) NOT NULL COMMENT '动作时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_event_1` (`enterprise_id`,`id`),
  KEY                    `idx_audit_event_1` (`enterprise_id`,`created_at`,`id`),
  KEY                    `idx_audit_event_2` (`enterprise_id`,`actor_user_id`,`created_at`,`id`),
  CONSTRAINT `ck_audit_event_1` CHECK ((`result` in (_utf8mb4'success', _utf8mb4'failure')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加的管理与重要操作记录，清理业务对象不删除记录';

CREATE TABLE `auth_token`
(
  `id`           varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `user_id`      varchar(100) NOT NULL COMMENT '目标用户',
  `purpose`      varchar(32)  NOT NULL COMMENT '凭据用途',
  `token_hash`   char(64)     NOT NULL COMMENT '随机凭据的 SHA-256 摘要',
  `target_email` varchar(254) DEFAULT NULL COMMENT '邮箱验证的目标邮箱',
  `expires_at`   datetime(3) NOT NULL COMMENT '失效时间',
  `consumed_at`  datetime(3) DEFAULT NULL COMMENT '使用完成时间',
  `created_at`   datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_token_1` (`token_hash`),
  KEY            `idx_auth_token_1` (`user_id`,`purpose`,`created_at`),
  KEY            `idx_auth_token_2` (`expires_at`),
  CONSTRAINT `fk_auth_token_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_auth_token_1` CHECK ((`purpose` in (_utf8mb4'password_reset', _utf8mb4'email_verify')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='密码重置和邮箱验证的一次性凭据，仅保存摘要';

CREATE TABLE `background_job`
(
  `id`                      varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`           varchar(100)          DEFAULT NULL COMMENT '企业归属，全局邮件可为空',
  `owner_user_id`           varchar(100)          DEFAULT NULL COMMENT '业务身份，全局维护可为空',
  `kind`                    varchar(32)  NOT NULL COMMENT '经过注册的任务类型',
  `dedupe_key`              varchar(255) NOT NULL COMMENT '同一逻辑后台工作唯一键',
  `payload_json`            json         NOT NULL COMMENT '仅包含必要业务编号和经过验证的配置',
  `status`                  varchar(20)  NOT NULL DEFAULT 'queued' COMMENT '队列状态',
  `available_at`            datetime(3) NOT NULL COMMENT '可领取时间',
  `attempt_count`           int          NOT NULL DEFAULT '0' COMMENT '实际领取次数',
  `max_attempts`            int          NOT NULL DEFAULT '3' COMMENT '最大领取次数',
  `lease_owner`             varchar(128)          DEFAULT NULL COMMENT '工作进程实例编号',
  `lease_version`           bigint       NOT NULL DEFAULT '0' COMMENT '每次领取递增',
  `lease_until`             datetime(3) DEFAULT NULL COMMENT '租约到期时间',
  `heartbeat_at`            datetime(3) DEFAULT NULL COMMENT '最近续期时间',
  `result_file_id`          varchar(100)          DEFAULT NULL COMMENT '导出等任务的结果文件',
  `error_code`              varchar(64)           DEFAULT NULL COMMENT '错误代码',
  `error_summary`           varchar(500)          DEFAULT NULL COMMENT '脱敏错误',
  `active_export_owner_key` varchar(100) GENERATED ALWAYS AS ((case
                                                                 when ((`kind` = _utf8mb4'export') and
                                                                       (`status` in (_utf8mb4'queued', _utf8mb4'leased')))
                                                                   then `owner_user_id`
                                                                 else NULL end)) STORED COMMENT '数据库计算的活动导出发起人，结束后为空，禁止应用写入',
  `created_at`              datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`              datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_background_job_1` (`dedupe_key`),
  UNIQUE KEY `uk_background_job_2` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_background_job_active_export_owner` (`active_export_owner_key`),
  KEY                       `idx_background_job_1` (`status`,`available_at`,`id`),
  KEY                       `idx_background_job_2` (`status`,`lease_until`,`id`),
  KEY                       `idx_background_job_3` (`enterprise_id`,`owner_user_id`,`created_at`,`id`),
  KEY                       `idx_background_job_result_file` (`enterprise_id`,`result_file_id`),
  CONSTRAINT `ck_background_job_1` CHECK ((`kind` in (_utf8mb4'run', _utf8mb4'document_parse', _utf8mb4'file_scan',
                                                      _utf8mb4'mail', _utf8mb4'notification', _utf8mb4'event_publish',
                                                      _utf8mb4'export', _utf8mb4'cleanup'))),
  CONSTRAINT `ck_background_job_2` CHECK ((`status` in
                                           (_utf8mb4'queued', _utf8mb4'leased', _utf8mb4'completed', _utf8mb4'failed',
                                            _utf8mb4'cancelled'))),
  CONSTRAINT `ck_background_job_export_identity` CHECK (((`kind` <> _utf8mb4'export') or
                                                         ((`enterprise_id` is not null) and (`owner_user_id` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='执行、解析、邮件、事件分发和导出的持久任务';

CREATE TABLE `credential`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          varchar(80)  NOT NULL COMMENT '凭据名称',
  `kind`          varchar(20)  NOT NULL COMMENT '凭据用途',
  `ciphertext`    mediumblob   NOT NULL COMMENT '加密后的秘密正文',
  `nonce`         varbinary(12) NOT NULL COMMENT '本次加密随机数',
  `auth_tag`      varbinary(16) NOT NULL COMMENT '认证加密校验值',
  `key_version`   varchar(64)  NOT NULL COMMENT '部署密钥服务的密钥版本',
  `status`        varchar(20)  NOT NULL DEFAULT 'active' COMMENT '凭据状态',
  `updated_by`    varchar(100) NOT NULL COMMENT '最后维护成员',
  `revision`      bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_1` (`enterprise_id`,`id`),
  KEY             `idx_credential_1` (`enterprise_id`,`updated_by`),
  CONSTRAINT `fk_credential_1` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_credential_1` CHECK ((`kind` in
                                       (_utf8mb4'bearer', _utf8mb4'basic', _utf8mb4'database', _utf8mb4'api_key'))),
  CONSTRAINT `ck_credential_2` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'revoked')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加密凭据与密钥轮换信息，无明文读取接口';

CREATE TABLE `data_collection`
(
  `id`                varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`       varchar(100) NOT NULL COMMENT '数据源资源',
  `name`              varchar(80)  NOT NULL COMMENT '集合显示名',
  `source_name`       varchar(128) NOT NULL COMMENT '服务端验证的表名、接口映射或文件集合名',
  `active_generation` int          NOT NULL DEFAULT '1' COMMENT '当前可查询结构与数据版本',
  `file_id`           varchar(100)          DEFAULT NULL COMMENT '文件类型的数据来源',
  `row_count`         bigint                DEFAULT NULL COMMENT '已导入文件的真实行数；远程集合未知时为空',
  `status`            varchar(20)  NOT NULL DEFAULT 'active' COMMENT '可用、处理中或停用',
  `revision`          bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_data_collection_1` (`enterprise_id`,`resource_id`,`source_name`),
  UNIQUE KEY `uk_data_collection_2` (`enterprise_id`,`id`),
  KEY                 `idx_data_collection_1` (`enterprise_id`,`file_id`),
  CONSTRAINT `fk_data_collection_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_data_collection_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_data_collection_1` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'processing', _utf8mb4'disabled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源内允许查询的集合与当前版本';

CREATE TABLE `data_field`
(
  `enterprise_id` varchar(100)                                                NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` varchar(100)                                                NOT NULL COMMENT '集合编号',
  `generation`    int                                                         NOT NULL COMMENT '结构版本',
  `name`          varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '真实字段名',
  `label`         varchar(80)                                                 NOT NULL COMMENT '中文展示名',
  `value_type`    varchar(20)                                                 NOT NULL COMMENT '字段值类型',
  `readable`      tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否允许读取',
  `filterable`    tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否允许筛选',
  `sortable`      tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否允许排序',
  `sensitive`     tinyint(1) NOT NULL DEFAULT '0' COMMENT '展示与日志是否隐藏明细',
  `nullable`      tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否接受空值',
  `ordinal`       int                                                         NOT NULL COMMENT '字段显示顺序',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`, `name`),
  CONSTRAINT `fk_data_field_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_data_field_1` CHECK ((`value_type` in
                                       (_utf8mb4'string', _utf8mb4'integer', _utf8mb4'decimal', _utf8mb4'boolean',
                                        _utf8mb4'date', _utf8mb4'datetime', _utf8mb4'object')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集合处理版本中的字段类型和访问限制';

CREATE TABLE `data_generation`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` varchar(100) NOT NULL COMMENT '所属集合',
  `generation`    int          NOT NULL COMMENT '该集合的处理版本',
  `source_hash`   char(64)     NOT NULL COMMENT '登记本版本时连接类型、地址、参数映射和凭据编号的摘要',
  `file_id`       varchar(100) DEFAULT NULL COMMENT '该版本的原文件，远程集合为空',
  `row_count`     bigint       DEFAULT NULL COMMENT '已验证的文件行数，远程集合未知时为空',
  `created_at`    datetime(3) NOT NULL COMMENT '该版本完整提交时间',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`),
  KEY             `idx_data_generation_1` (`enterprise_id`,`file_id`),
  CONSTRAINT `fk_data_generation_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_data_generation_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_data_generation_1` CHECK ((`generation` >= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集合版本对应的真实文件和导入统计';

CREATE TABLE `data_record`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` varchar(100) NOT NULL COMMENT '集合编号',
  `generation`    int          NOT NULL COMMENT '导入版本',
  `row_no`        bigint       NOT NULL COMMENT '行顺序，从 1 开始',
  `values_json`   json         NOT NULL COMMENT '按该版本字段顺序保存已验证值的数组，不重复保存字段名',
  `created_at`    datetime(3) NOT NULL COMMENT '导入时间',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`, `row_no`),
  CONSTRAINT `fk_data_record_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件数据源中经过类型校验的一行数据';

CREATE TABLE `demo_account`
(
  `id`                   int         NOT NULL,
  `initialized`          tinyint(1) NOT NULL DEFAULT '0',
  `enabled`              tinyint(1) NOT NULL DEFAULT '0',
  `cleanup_status`       varchar(16) NOT NULL DEFAULT 'idle',
  `cleanup_requested_at` datetime(3) DEFAULT NULL,
  `last_cleaned_at`      datetime(3) DEFAULT NULL,
  `last_cleaned_date`    date                 DEFAULT NULL,
  `password_changed_at`  datetime(3) DEFAULT NULL,
  `revision`             bigint      NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_demo_account_singleton` CHECK ((`id` = 1)),
  CONSTRAINT `ck_demo_cleanup_status` CHECK ((`cleanup_status` in (_utf8mb4'idle', _utf8mb4'pending', _utf8mb4'failed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `enterprise`
(
  `id`                     varchar(100) NOT NULL,
  `name`                   varchar(80)  NOT NULL,
  `status`                 varchar(32)  NOT NULL DEFAULT 'active',
  `created_at`             datetime(3) NOT NULL,
  `updated_at`             datetime(3) NOT NULL,
  `description`            varchar(500) NOT NULL DEFAULT '',
  `contact_email`          varchar(254)          DEFAULT NULL,
  `timezone`               varchar(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  `quota_timezone`         varchar(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  `pending_quota_timezone` varchar(64)           DEFAULT NULL,
  `quota_period_start`     datetime(3) NOT NULL COMMENT '当前次数周期的实际起点',
  `quota_period_end`       datetime(3) NOT NULL COMMENT '当前次数周期的结束时间，不包含边界时刻',
  `permission_version`     bigint       NOT NULL DEFAULT '1',
  `retention_days`         int          NOT NULL DEFAULT '180',
  `created_by`             varchar(100) NOT NULL,
  `revision`               bigint       NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY                      `idx_enterprise_status_created` (`status`,`created_at`),
  KEY                      `fk_enterprise_1` (`created_by`),
  CONSTRAINT `fk_enterprise_1` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_enterprise_1` CHECK ((`retention_days` between 90 and 730)),
  CONSTRAINT `ck_enterprise_2` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled'))),
  CONSTRAINT `ck_enterprise_3` CHECK ((`quota_period_end` > `quota_period_start`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `enterprise_invitation`
(
  `id`                varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `email`             varchar(254)          DEFAULT NULL COMMENT '邮箱邀请的受邀邮箱，分享邀请为空',
  `email_normalized`  varchar(254)          DEFAULT NULL COMMENT '规范化受邀邮箱',
  `pending_email`     varchar(254)          DEFAULT NULL COMMENT '待处理时为规范化邮箱，终态置空',
  `display_name`      varchar(128)          DEFAULT NULL COMMENT '建议成员显示名',
  `team_ids_json`     json         NOT NULL COMMENT '邀请指定的团队编号数组',
  `role_ids_json`     json         NOT NULL COMMENT '邀请指定的角色编号数组',
  `note`              varchar(200) NOT NULL DEFAULT '' COMMENT '邀请说明',
  `token_hash`        char(64)     NOT NULL COMMENT '邀请随机凭据摘要',
  `status`            varchar(32)  NOT NULL DEFAULT 'pending' COMMENT '邀请状态',
  `delivery_status`   varchar(32)  NOT NULL DEFAULT 'pending' COMMENT '邮件服务处理结果',
  `delivery_attempts` int          NOT NULL DEFAULT '0' COMMENT '邮件发送尝试次数',
  `delivery_error`    varchar(500)          DEFAULT NULL COMMENT '脱敏的最近发送错误',
  `created_by`        varchar(100) NOT NULL COMMENT '邀请发起成员',
  `accepted_user_id`  varchar(100)          DEFAULT NULL COMMENT '最终接受邀请的用户',
  `expires_at`        datetime(3) NOT NULL COMMENT '邀请失效时间',
  `accepted_at`       datetime(3) DEFAULT NULL COMMENT '接受时间',
  `revision`          bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enterprise_invitation_2` (`token_hash`),
  UNIQUE KEY `uk_enterprise_invitation_3` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_enterprise_invitation_1` (`enterprise_id`,`pending_email`),
  KEY                 `idx_enterprise_invitation_1` (`enterprise_id`,`status`,`created_at`,`id`),
  KEY                 `idx_enterprise_invitation_2` (`status`,`expires_at`),
  KEY                 `idx_enterprise_invitation_3` (`enterprise_id`,`created_by`),
  KEY                 `idx_enterprise_invitation_4` (`accepted_user_id`),
  CONSTRAINT `fk_enterprise_invitation_1` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_enterprise_invitation_2` FOREIGN KEY (`accepted_user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_enterprise_invitation_1` CHECK ((
    ((`email` is null) and (`email_normalized` is null) and (`pending_email` is null)) or
    ((`email` is not null) and (`email_normalized` is not null) and
     (((`status` = _utf8mb4'pending') and (`pending_email` is not null) and (`pending_email` = `email_normalized`)) or
      ((`status` <> _utf8mb4'pending') and (`pending_email` is null)))))),
  CONSTRAINT `ck_enterprise_invitation_2` CHECK ((`status` in (_utf8mb4'pending', _utf8mb4'accepted', _utf8mb4'expired',
                                                               _utf8mb4'revoked'))),
  CONSTRAINT `ck_enterprise_invitation_3` CHECK ((`delivery_status` in
                                                  (_utf8mb4'pending', _utf8mb4'sent', _utf8mb4'failed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加入企业的邀请与真实邮件发送状态';

CREATE TABLE `enterprise_member`
(
  `enterprise_id`         varchar(100) NOT NULL,
  `user_id`               varchar(100) NOT NULL,
  `display_name`          varchar(128) NOT NULL,
  `status`                varchar(32)  NOT NULL DEFAULT 'active',
  `joined_at`             datetime(3) NOT NULL,
  `updated_at`            datetime(3) NOT NULL,
  `notification_sequence` bigint       NOT NULL DEFAULT '0',
  `revision`              bigint       NOT NULL DEFAULT '1',
  `created_at`            datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`enterprise_id`, `user_id`),
  KEY                     `idx_enterprise_member_user` (`user_id`),
  KEY                     `idx_enterprise_member_status` (`enterprise_id`,`status`),
  CONSTRAINT `fk_enterprise_member_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_enterprise_member_2` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_demo_member_enterprise` CHECK (((`user_id` <> _utf8mb4'system-demo-user') or
                                                 (`enterprise_id` = _utf8mb4'system-demo-enterprise'))),
  CONSTRAINT `ck_enterprise_member_1` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled', _utf8mb4'removed')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `enterprise_team`
(
  `id`            varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `name`          varchar(50)  NOT NULL,
  `description`   varchar(500) NOT NULL DEFAULT '',
  `status`        varchar(32)  NOT NULL DEFAULT 'active',
  `created_at`    datetime(3) NOT NULL,
  `updated_at`    datetime(3) NOT NULL,
  `name_key`      varchar(50)  NOT NULL,
  `owner_user_id` varchar(100) NOT NULL,
  `deleted_at`    datetime(3) DEFAULT NULL,
  `deleted_token` varchar(100) NOT NULL DEFAULT '',
  `revision`      bigint       NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enterprise_team_1` (`enterprise_id`,`name_key`,`deleted_token`),
  UNIQUE KEY `uk_enterprise_team_2` (`enterprise_id`,`id`),
  KEY             `idx_enterprise_team_enterprise` (`enterprise_id`,`status`),
  KEY             `fk_enterprise_team_1` (`enterprise_id`,`owner_user_id`),
  CONSTRAINT `fk_enterprise_team_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_enterprise_team_1` CHECK ((((`deleted_at` is null) and (`deleted_token` = _utf8mb4'')) or
                                            ((`deleted_at` is not null) and (`deleted_token` = `id`)))),
  CONSTRAINT `ck_enterprise_team_2` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `enterprise_team_member`
(
  `enterprise_id` varchar(100) NOT NULL,
  `team_id`       varchar(100) NOT NULL,
  `user_id`       varchar(100) NOT NULL,
  `joined_at`     datetime(3) NOT NULL,
  PRIMARY KEY (`enterprise_id`, `team_id`, `user_id`),
  KEY             `idx_enterprise_team_member_user` (`user_id`),
  KEY             `fk_enterprise_team_member_2` (`enterprise_id`,`user_id`),
  CONSTRAINT `fk_enterprise_team_member_1` FOREIGN KEY (`enterprise_id`, `team_id`) REFERENCES `enterprise_team` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_enterprise_team_member_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `file_data_profile`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       varchar(100) NOT NULL COMMENT '数据原文件',
  `sha256`        char(64)     NOT NULL COMMENT '已解析原文件摘要',
  `columns_json`  json         NOT NULL COMMENT '按原文件顺序记录字段名、推断类型和是否有空值',
  `row_count`     bigint       NOT NULL COMMENT '完整解析后的数据行数',
  `created_at`    datetime(3) NOT NULL COMMENT '结果保存时间',
  PRIMARY KEY (`enterprise_id`, `file_id`),
  CONSTRAINT `fk_file_data_profile_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_data_profile_1` CHECK ((`row_count` between 0 and 100000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CSV 扫描和完整解析后的字段与行数';

CREATE TABLE `file_data_row`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       varchar(100) NOT NULL COMMENT '数据原文件',
  `row_no`        bigint       NOT NULL COMMENT '原文件中除表头外的行序号，从一开始',
  `values_json`   json         NOT NULL COMMENT '按列顺序保留字符串和空值的数组',
  PRIMARY KEY (`enterprise_id`, `file_id`, `row_no`),
  CONSTRAINT `fk_file_data_row_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_data_row_1` CHECK ((`row_no` between 1 and 100000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='尚未按集合字段确认类型的 CSV 原始行';

CREATE TABLE `file_object`
(
  `id`                  varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `owner_user_id`       varchar(100) NOT NULL COMMENT '上传成员',
  `resource_id`         varchar(100)          DEFAULT NULL COMMENT '准备上传或生成文件时指定的来源资源',
  `resource_version_id` varchar(100)          DEFAULT NULL COMMENT '导出内容的固定来源版本',
  `run_id`              varchar(100)          DEFAULT NULL COMMENT '生成结果附件的执行，普通上传为空',
  `purpose`             varchar(32)  NOT NULL COMMENT '文件用途',
  `original_name`       varchar(255) NOT NULL COMMENT '原文件名，下载时安全编码',
  `media_type`          varchar(128) NOT NULL COMMENT '实际检测的文件类型',
  `expected_size_bytes` bigint                DEFAULT NULL COMMENT '准备上传时声明的大小，平台生成文件为空',
  `expected_sha256`     char(64)              DEFAULT NULL COMMENT '准备上传时声明的内容摘要',
  `upload_expires_at`   datetime(3) DEFAULT NULL COMMENT '上传地址的截止时间，平台生成文件为空',
  `upload_lease_id`     varchar(100)          DEFAULT NULL COMMENT '当前上传占用编号，避免并发覆盖同一文件',
  `upload_lease_until`  datetime(3) DEFAULT NULL COMMENT '当前上传占用的截止时间',
  `size_bytes`          bigint       NOT NULL COMMENT '文件真实字节数',
  `sha256`              char(64)     NOT NULL COMMENT '内容摘要',
  `storage_key`         varchar(500) NOT NULL COMMENT '包含企业的随机存储路径',
  `status`              varchar(32)  NOT NULL DEFAULT 'pending' COMMENT '上传和检查结果',
  `error_code`          varchar(64)           DEFAULT NULL COMMENT '可映射给用户的错误代码',
  `expires_at`          datetime(3) DEFAULT NULL COMMENT '临时文件或导出的清理时间',
  `deleted_at`          datetime(3) DEFAULT NULL COMMENT '不可再下载时间',
  `created_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_object_1` (`storage_key`),
  UNIQUE KEY `uk_file_object_2` (`enterprise_id`,`id`),
  KEY                   `idx_file_object_1` (`enterprise_id`,`owner_user_id`,`purpose`,`created_at`,`id`),
  KEY                   `idx_file_object_2` (`status`,`expires_at`),
  KEY                   `idx_file_object_3` (`enterprise_id`,`resource_id`),
  KEY                   `idx_file_object_4` (`enterprise_id`,`resource_id`,`resource_version_id`),
  KEY                   `idx_file_object_5` (`enterprise_id`,`run_id`),
  CONSTRAINT `fk_file_object_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_file_object_2` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_file_object_3` FOREIGN KEY (`enterprise_id`, `resource_id`, `resource_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_file_object_4` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_object_1` CHECK ((`size_bytes` >= 0)),
  CONSTRAINT `ck_file_object_2` CHECK (((`expected_size_bytes` is null) or
                                        (`expected_size_bytes` between 1 and 20971520))),
  CONSTRAINT `ck_file_object_3` CHECK ((`purpose` in (_utf8mb4'attachment', _utf8mb4'knowledge', _utf8mb4'data_import',
                                                      _utf8mb4'skill_import', _utf8mb4'export', _utf8mb4'artifact'))),
  CONSTRAINT `ck_file_object_4` CHECK ((`status` in
                                        (_utf8mb4'pending', _utf8mb4'uploaded', _utf8mb4'scanning', _utf8mb4'ready',
                                         _utf8mb4'rejected', _utf8mb4'deleted')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业文件及检查状态，不向普通响应直接暴露存储密钥';

CREATE TABLE `file_text`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       varchar(100) NOT NULL COMMENT '原始附件文件',
  `sha256`        char(64)     NOT NULL COMMENT '已解析原文件摘要',
  `content_text`  longtext     NOT NULL COMMENT '供对话读取的文字，最多五万字符',
  `truncated`     tinyint(1) NOT NULL DEFAULT '0' COMMENT '完整文字超过对话附件读取上限',
  `created_at`    datetime(3) NOT NULL COMMENT '解析结果保存时间',
  PRIMARY KEY (`enterprise_id`, `file_id`),
  CONSTRAINT `fk_file_text_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_file_text_1` CHECK ((char_length(`content_text`) between 1 and 50000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话附件通过扫描和解析后保存的受限文字';

CREATE TABLE `knowledge_chunk`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `document_id`   varchar(100) NOT NULL COMMENT '知识资料',
  `file_id`       varchar(100) NOT NULL COMMENT '该处理版本的原文件',
  `generation`    int          NOT NULL COMMENT '处理版本',
  `ordinal`       int          NOT NULL COMMENT '块顺序，从 1 开始',
  `locator_json`  json         NOT NULL COMMENT '页码、章节与原文位置',
  `content_text`  text         NOT NULL COMMENT '不超过 1200 字符的文本',
  `search_terms`  mediumtext COMMENT '含知识库摘要的相邻字符检索词，旧处理版本切换后清空',
  `content_hash`  char(64)     NOT NULL COMMENT '文本摘要',
  `created_at`    datetime(3) NOT NULL COMMENT '写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chunk_1` (`enterprise_id`,`document_id`,`generation`,`ordinal`),
  UNIQUE KEY `uk_knowledge_chunk_2` (`enterprise_id`,`id`),
  KEY             `idx_knowledge_chunk_1` (`enterprise_id`,`document_id`,`generation`,`id`),
  KEY             `idx_knowledge_chunk_2` (`enterprise_id`,`file_id`),
  FULLTEXT KEY `ft_knowledge_chunk_1` (`search_terms`),
  CONSTRAINT `fk_knowledge_chunk_1` FOREIGN KEY (`enterprise_id`, `document_id`) REFERENCES `knowledge_document` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_chunk_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_knowledge_chunk_1` CHECK ((`generation` >= 1)),
  CONSTRAINT `ck_knowledge_chunk_2` CHECK ((`ordinal` >= 1)),
  CONSTRAINT `ck_knowledge_chunk_3` CHECK ((char_length(`content_text`) between 1 and 1200))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='某次资料处理版本的可检索文本块';

CREATE TABLE `knowledge_document`
(
  `id`                 varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`      varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`        varchar(100) NOT NULL COMMENT '知识库资源',
  `name`               varchar(255) NOT NULL COMMENT '资料名称',
  `file_id`            varchar(100) NOT NULL COMMENT '本次等待处理或最近上传的文件',
  `active_file_id`     varchar(100)          DEFAULT NULL COMMENT '当前可用处理版本的原文件',
  `active_generation`  int          NOT NULL DEFAULT '0' COMMENT '当前可检索处理版本；0 表示尚无',
  `pending_generation` int          NOT NULL DEFAULT '0' COMMENT '正在处理的目标版本；无任务时为 0',
  `last_generation`    int          NOT NULL DEFAULT '0' COMMENT '已经分配的最大处理版本，失败后也不复用',
  `status`             varchar(32)  NOT NULL DEFAULT 'uploaded' COMMENT '资料处理状态',
  `chunk_count`        int          NOT NULL DEFAULT '0' COMMENT '当前有效文本块数量',
  `page_count`         int                   DEFAULT NULL COMMENT '有实际页码时的页数',
  `error_code`         varchar(64)           DEFAULT NULL COMMENT '错误代码',
  `error_summary`      varchar(500)          DEFAULT NULL COMMENT '可公开处理错误',
  `processed_at`       datetime(3) DEFAULT NULL COMMENT '最近成功处理时间',
  `deleted_at`         datetime(3) DEFAULT NULL COMMENT '删除时间',
  `revision`           bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`         datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`         datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_1` (`enterprise_id`,`id`),
  KEY                  `idx_knowledge_document_1` (`enterprise_id`,`resource_id`,`status`,`updated_at`,`id`),
  KEY                  `idx_knowledge_document_2` (`status`,`deleted_at`),
  KEY                  `idx_knowledge_document_3` (`enterprise_id`,`file_id`),
  KEY                  `idx_knowledge_document_4` (`enterprise_id`,`active_file_id`),
  CONSTRAINT `fk_knowledge_document_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_document_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_knowledge_document_3` FOREIGN KEY (`enterprise_id`, `active_file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_knowledge_document_1` CHECK (((`active_generation` >= 0) and (`pending_generation` >= 0) and
                                               (`last_generation` >= `active_generation`) and
                                               (`last_generation` >= `pending_generation`))),
  CONSTRAINT `ck_knowledge_document_2` CHECK ((`status` in (_utf8mb4'uploaded', _utf8mb4'scanning', _utf8mb4'queued',
                                                            _utf8mb4'processing', _utf8mb4'ready', _utf8mb4'failed',
                                                            _utf8mb4'deleted')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库资料与原子切换的处理版本';

CREATE TABLE `message_attachment`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `message_id`    varchar(100) NOT NULL COMMENT '所属消息',
  `file_id`       varchar(100) NOT NULL COMMENT '附件文件',
  `ordinal`       int          NOT NULL COMMENT '用户选择的顺序',
  `created_at`    datetime(3) NOT NULL COMMENT '关联时间',
  PRIMARY KEY (`enterprise_id`, `message_id`, `file_id`),
  KEY             `idx_message_attachment_1` (`enterprise_id`,`file_id`,`message_id`),
  CONSTRAINT `fk_message_attachment_1` FOREIGN KEY (`enterprise_id`, `message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_message_attachment_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息与已验证文件关联';

CREATE TABLE `message_feedback`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `message_id`    varchar(100) NOT NULL COMMENT '消息',
  `user_id`       varchar(100) NOT NULL COMMENT '反馈成员',
  `value`         varchar(20)  NOT NULL COMMENT '赞同或不同意',
  `comment`       varchar(500) NOT NULL DEFAULT '' COMMENT '可选说明',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `message_id`, `user_id`),
  KEY             `idx_message_feedback_1` (`enterprise_id`,`user_id`),
  CONSTRAINT `fk_message_feedback_1` FOREIGN KEY (`enterprise_id`, `message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_message_feedback_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_message_feedback_1` CHECK ((`value` in (_utf8mb4'positive', _utf8mb4'negative')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人对一条消息的当前反馈，不构成评测评分';

CREATE TABLE `model_profile`
(
  `id`                varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `provider_id`       varchar(100) NOT NULL COMMENT '所属模型提供方',
  `name`              varchar(80)  NOT NULL COMMENT '可选择的显示名称',
  `model_name`        varchar(128) NOT NULL COMMENT '上游实际模型名',
  `capabilities_json` json         NOT NULL COMMENT '管理员声明的输入类型、工具调用和参数上限',
  `enabled`           tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否允许继续使用',
  `revision`          bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_profile_1` (`enterprise_id`,`name`),
  UNIQUE KEY `uk_model_profile_2` (`enterprise_id`,`id`),
  KEY                 `idx_model_profile_1` (`enterprise_id`,`provider_id`),
  CONSTRAINT `fk_model_profile_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_model_profile_2` FOREIGN KEY (`enterprise_id`, `provider_id`) REFERENCES `model_provider` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员配置的模型；已被资源或对话使用后不改写模型身份与能力';

CREATE TABLE `model_provider`
(
  `id`            varchar(100)  NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100)  NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          varchar(80)   NOT NULL COMMENT '提供方名称',
  `protocol`      varchar(32)   NOT NULL COMMENT '实际接入的模型协议',
  `base_url`      varchar(2048) NOT NULL COMMENT '模型服务地址',
  `api_key`       text          NOT NULL COMMENT '模型访问密钥，当前以明文保存，不返回到管理列表',
  `enabled`       tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否允许使用该提供方',
  `revision`      bigint        NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_1` (`enterprise_id`,`name`),
  UNIQUE KEY `uk_model_provider_2` (`enterprise_id`,`id`),
  CONSTRAINT `fk_model_provider_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_model_provider_1` CHECK ((`protocol` = _utf8mb4'openai'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员维护的模型提供方；开发阶段密钥按要求明文保存';

CREATE TABLE `notification`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       varchar(100) NOT NULL COMMENT '接收成员',
  `sequence_no`   bigint       NOT NULL COMMENT '本企业本成员通知序号；同一事务锁成员行并分配，用于全部已读边界',
  `event_key`     varchar(128) NOT NULL COMMENT '相同业务事件对同一用户只创建一次',
  `category`      varchar(32)  NOT NULL COMMENT '通知类别',
  `title`         varchar(100) NOT NULL COMMENT '中文通知标题',
  `body`          varchar(500) NOT NULL COMMENT '不含凭据或越权正文的说明',
  `target_type`   varchar(32)  DEFAULT NULL COMMENT '允许的目标类型',
  `target_id`     varchar(100) DEFAULT NULL COMMENT '目标逻辑引用，点击时重新鉴权',
  `read_at`       datetime(3) DEFAULT NULL COMMENT '本人标记已读时间',
  `created_at`    datetime(3) NOT NULL COMMENT '实际创建时间，不回填为旧事件时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_1` (`enterprise_id`,`user_id`,`sequence_no`),
  UNIQUE KEY `uk_notification_2` (`enterprise_id`,`user_id`,`event_key`),
  UNIQUE KEY `uk_notification_3` (`enterprise_id`,`id`),
  KEY             `idx_notification_1` (`enterprise_id`,`user_id`,`read_at`,`sequence_no`),
  CONSTRAINT `fk_notification_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_notification_1` CHECK ((`sequence_no` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人站内通知及读取边界';

CREATE TABLE `plugin_tool`
(
  `id`                     varchar(100)                                                NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          varchar(100)                                                NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `plugin_version_id`      varchar(100)                                                NOT NULL COMMENT '插件发布版本',
  `entry_id`               varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin          DEFAULT NULL COMMENT '合集内稳定的工具条目编号',
  `source_json`            json                                                                 DEFAULT NULL COMMENT '固定的工具来源、版本及连接配置；仅保存凭据引用',
  `name`                   varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '区分大小写的服务端正式工具名称',
  `description`            varchar(500)                                                NOT NULL COMMENT '公开说明',
  `schema_hash`            char(64)                                                    NOT NULL COMMENT '工具结构摘要',
  `input_schema_json`      json                                                        NOT NULL COMMENT '参数验证结构',
  `output_schema_json`     json                                                                 DEFAULT NULL COMMENT '返回结构',
  `annotations_json`       json                                                        NOT NULL COMMENT '用于比较结构的服务端行为提示，不作为可信授权',
  `operation_class`        varchar(20)                                                 NOT NULL COMMENT '只读、写入或未知',
  `enabled`                tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否允许调用，紧急停用可立即改变',
  `supports_deduplication` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否经过验证支持请求去重',
  `supports_result_query`  tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否支持按操作编号查结果',
  `supports_cancel`        tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否可请求取消',
  `redact_paths_json`      json                                                        NOT NULL COMMENT '必须隐藏的参数和结果字段',
  `timeout_seconds`        int                                                         NOT NULL DEFAULT '30' COMMENT '工具调用上限',
  `created_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plugin_tool_2` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_plugin_tool_entry` (`enterprise_id`,`plugin_version_id`,`entry_id`),
  CONSTRAINT `fk_plugin_tool_1` FOREIGN KEY (`enterprise_id`, `plugin_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_plugin_tool_1` CHECK ((`timeout_seconds` between 1 and 120)),
  CONSTRAINT `ck_plugin_tool_2` CHECK ((`operation_class` in
                                        (_utf8mb4'read', _utf8mb4'write', _utf8mb4'destructive', _utf8mb4'unknown')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='插件发布版本中的固定工具结构和运行约束';

CREATE TABLE `quota_bucket`
(
  `id`             varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `policy_id`      varchar(100) NOT NULL COMMENT '用量规则',
  `period_start`   datetime(3) NOT NULL COMMENT '周期起点，UTC',
  `period_end`     datetime(3) NOT NULL COMMENT '周期结束，不包含边界时刻',
  `timezone`       varchar(64)  NOT NULL COMMENT '计算本周期时使用的时区',
  `used_count`     bigint       NOT NULL DEFAULT '0' COMMENT '已经实际开始的次数',
  `reserved_count` bigint       NOT NULL DEFAULT '0' COMMENT '已接受但尚未实际开始的次数',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quota_bucket_1` (`enterprise_id`,`policy_id`,`period_start`),
  UNIQUE KEY `uk_quota_bucket_2` (`enterprise_id`,`id`),
  KEY              `idx_quota_bucket_1` (`enterprise_id`,`period_start`,`id`),
  CONSTRAINT `fk_quota_bucket_1` FOREIGN KEY (`enterprise_id`, `policy_id`) REFERENCES `quota_policy` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quota_bucket_1` CHECK ((`used_count` >= 0)),
  CONSTRAINT `ck_quota_bucket_2` CHECK ((`reserved_count` >= 0)),
  CONSTRAINT `ck_quota_bucket_3` CHECK ((`period_end` > `period_start`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='某规则某个自然月的已用及预留次数';

CREATE TABLE `quota_entry`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`        varchar(100) NOT NULL COMMENT '顶层执行逻辑引用，历史清理不删除计数',
  `bucket_id`     varchar(100) NOT NULL COMMENT '所影响计数桶',
  `state`         varchar(20)  NOT NULL DEFAULT 'reserved' COMMENT '预留、已使用或已释放',
  `quantity`      int          NOT NULL DEFAULT '1' COMMENT '固定为一次',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `run_id`, `bucket_id`),
  KEY             `idx_quota_entry_1` (`enterprise_id`,`bucket_id`,`state`),
  CONSTRAINT `fk_quota_entry_1` FOREIGN KEY (`enterprise_id`, `bucket_id`) REFERENCES `quota_bucket` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quota_entry_1` CHECK ((`quantity` = 1)),
  CONSTRAINT `ck_quota_entry_2` CHECK ((`state` in (_utf8mb4'reserved', _utf8mb4'consumed', _utf8mb4'released')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一个顶层执行对一个计数桶最多计一次';

CREATE TABLE `quota_policy`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `subject_type`  varchar(20)  NOT NULL COMMENT '约束主体',
  `subject_id`    varchar(100) NOT NULL COMMENT '主体编号，必须在业务事务中验证企业归属',
  `monthly_limit` bigint                DEFAULT NULL COMMENT '0 禁止新执行，空值表示该层不限',
  `enabled`       tinyint      NOT NULL DEFAULT '1' COMMENT '是否应用此规则',
  `revision`      bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quota_policy_1` (`enterprise_id`,`subject_type`,`subject_id`),
  UNIQUE KEY `uk_quota_policy_2` (`enterprise_id`,`id`),
  CONSTRAINT `fk_quota_policy_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_quota_policy_1` CHECK (((`monthly_limit` is null) or (`monthly_limit` between 0 and 1000000000))),
  CONSTRAINT `ck_quota_policy_2` CHECK ((`subject_type` in (_utf8mb4'enterprise', _utf8mb4'team', _utf8mb4'role')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业、团队或角色的月度执行次数上限';

CREATE TABLE `resource`
(
  `id`                   varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `kind`                 varchar(20)  NOT NULL COMMENT '资源类型',
  `name`                 varchar(80)  NOT NULL COMMENT '当前草稿名称',
  `subtype`              varchar(32)           DEFAULT NULL COMMENT '从已验证草稿派生的智能体、插件或数据源类型，不由客户端独立修改',
  `description`          varchar(500) NOT NULL DEFAULT '' COMMENT '当前草稿简介',
  `owner_user_id`        varchar(100) NOT NULL COMMENT '资源所有者',
  `source`               varchar(20)  NOT NULL DEFAULT 'created' COMMENT '资源来源',
  `source_reference`     varchar(128)          DEFAULT NULL COMMENT '内置模板或导入格式标识，不包含外部凭据',
  `status`               varchar(20)  NOT NULL DEFAULT 'active' COMMENT '可用、停用或已删除',
  `published_version_id` varchar(100)          DEFAULT NULL COMMENT '当前发布版本；为空表示尚未发布',
  `next_version_no`      int          NOT NULL DEFAULT '1' COMMENT '下一次发布分配的序号',
  `deleted_at`           datetime(3) DEFAULT NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token`        varchar(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`             bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`           datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`           datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_1` (`enterprise_id`,`id`),
  KEY                    `idx_resource_1` (`enterprise_id`,`kind`,`status`,`updated_at`,`id`),
  KEY                    `idx_resource_2` (`enterprise_id`,`owner_user_id`,`kind`,`updated_at`,`id`),
  KEY                    `idx_resource_3` (`enterprise_id`,`id`,`published_version_id`),
  CONSTRAINT `fk_resource_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_2` FOREIGN KEY (`enterprise_id`, `id`, `published_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_resource_1` CHECK ((`next_version_no` >= 1)),
  CONSTRAINT `ck_resource_2` CHECK ((((`status` = _utf8mb4'deleted') and (`deleted_at` is not null)) or
                                     ((`status` <> _utf8mb4'deleted') and (`deleted_at` is null)))),
  CONSTRAINT `ck_resource_3` CHECK ((((`deleted_at` is null) and (`deleted_token` = _utf8mb4'')) or
                                     ((`deleted_at` is not null) and (`deleted_token` = `id`)))),
  CONSTRAINT `ck_resource_4` CHECK ((`kind` in (_utf8mb4'agent', _utf8mb4'skill', _utf8mb4'plugin', _utf8mb4'workflow',
                                                _utf8mb4'knowledge', _utf8mb4'data'))),
  CONSTRAINT `ck_resource_5` CHECK ((`source` in (_utf8mb4'created', _utf8mb4'imported', _utf8mb4'builtin'))),
  CONSTRAINT `ck_resource_6` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled', _utf8mb4'deleted')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='六类能力资源的身份、所有权、当前发布指针和可用状态';

CREATE TABLE `resource_dependency`
(
  `enterprise_id`         varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `parent_version_id`     varchar(100) NOT NULL COMMENT '引用方发布版本',
  `dependency_version_id` varchar(100) NOT NULL COMMENT '被引用的固定版本',
  `binding_key`           varchar(100) NOT NULL COMMENT '配置字段或工作流节点中的引用位置',
  `dependency_kind`       varchar(20)  NOT NULL COMMENT '依赖类型',
  `ordinal`               int          NOT NULL DEFAULT '0' COMMENT '同类依赖顺序',
  PRIMARY KEY (`enterprise_id`, `parent_version_id`, `dependency_version_id`, `binding_key`),
  KEY                     `idx_resource_dependency_1` (`enterprise_id`,`dependency_version_id`,`parent_version_id`),
  CONSTRAINT `fk_resource_dependency_1` FOREIGN KEY (`enterprise_id`, `parent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_dependency_2` FOREIGN KEY (`enterprise_id`, `dependency_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_resource_dependency_1` CHECK ((`parent_version_id` <> `dependency_version_id`)),
  CONSTRAINT `ck_resource_dependency_2` CHECK ((`dependency_kind` in
                                                (_utf8mb4'agent', _utf8mb4'skill', _utf8mb4'plugin', _utf8mb4'workflow',
                                                 _utf8mb4'knowledge', _utf8mb4'data')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发布版本的固定依赖与使用位置';

CREATE TABLE `resource_draft`
(
  `enterprise_id`   varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`     varchar(100) NOT NULL COMMENT '资源编号',
  `schema_version`  int          NOT NULL DEFAULT '1' COMMENT '配置结构版本，首版为 1',
  `config_json`     json         NOT NULL COMMENT '通过对应资源配置结构验证的正文',
  `validation_json` json                  DEFAULT NULL COMMENT '与配置摘要绑定的检查和工具发现结果，不接受客户端直接修改',
  `config_hash`     char(64)     NOT NULL COMMENT '规范化配置摘要',
  `updated_by`      varchar(100) NOT NULL COMMENT '最后编辑成员',
  `created_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`      datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `resource_id`),
  KEY               `idx_resource_draft_1` (`enterprise_id`,`updated_by`),
  CONSTRAINT `fk_resource_draft_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_draft_2` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源当前可编辑配置；修改时同步递增资源主表版本';

CREATE TABLE `resource_grant`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`   varchar(100) NOT NULL COMMENT '资源编号',
  `subject_type`  varchar(20)  NOT NULL COMMENT '被授权主体类型',
  `subject_id`    varchar(100) NOT NULL COMMENT '主体编号，业务事务验证其属于本企业',
  `capability`    varchar(20)  NOT NULL COMMENT '授权能力',
  `created_by`    varchar(100) NOT NULL COMMENT '授权操作者',
  `created_at`    datetime(3) NOT NULL COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_grant_1` (`enterprise_id`,`resource_id`,`subject_type`,`subject_id`,`capability`),
  UNIQUE KEY `uk_resource_grant_2` (`enterprise_id`,`id`),
  KEY             `idx_resource_grant_1` (`enterprise_id`,`subject_type`,`subject_id`,`capability`,`resource_id`),
  KEY             `idx_resource_grant_2` (`enterprise_id`,`created_by`),
  CONSTRAINT `fk_resource_grant_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_grant_2` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_resource_grant_1` CHECK ((`subject_type` in (_utf8mb4'enterprise', _utf8mb4'team', _utf8mb4'user'))),
  CONSTRAINT `ck_resource_grant_2` CHECK ((`capability` in (_utf8mb4'view', _utf8mb4'use', _utf8mb4'edit')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源对企业、团队或用户的明确授权';

CREATE TABLE `resource_tag`
(
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`   varchar(100) NOT NULL COMMENT '资源',
  `tag_id`        varchar(100) NOT NULL COMMENT '标签',
  PRIMARY KEY (`enterprise_id`, `resource_id`, `tag_id`),
  KEY             `idx_resource_tag_1` (`enterprise_id`,`tag_id`,`resource_id`),
  CONSTRAINT `fk_resource_tag_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_tag_2` FOREIGN KEY (`enterprise_id`, `tag_id`) REFERENCES `tag` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源与标签的多对多关联';

CREATE TABLE `resource_version`
(
  `id`             varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`    varchar(100) NOT NULL COMMENT '所属资源',
  `version_no`     int          NOT NULL COMMENT '资源内递增发布序号',
  `name`           varchar(80)  NOT NULL COMMENT '发布时公开名称',
  `description`    varchar(500) NOT NULL DEFAULT '' COMMENT '发布时简介',
  `schema_version` int          NOT NULL DEFAULT '1' COMMENT '配置结构版本',
  `config_json`    json         NOT NULL COMMENT '完整固定配置',
  `config_hash`    char(64)     NOT NULL COMMENT '规范化配置摘要',
  `release_note`   varchar(500) NOT NULL COMMENT '发布说明',
  `status`         varchar(20)  NOT NULL DEFAULT 'available' COMMENT '版本使用资格',
  `published_by`   varchar(100) NOT NULL COMMENT '发布成员',
  `published_at`   datetime(3) NOT NULL COMMENT '发布时间',
  `revoked_at`     datetime(3) DEFAULT NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_version_1` (`enterprise_id`,`resource_id`,`version_no`),
  UNIQUE KEY `uk_resource_version_2` (`enterprise_id`,`resource_id`,`id`),
  UNIQUE KEY `uk_resource_version_3` (`enterprise_id`,`id`),
  KEY              `idx_resource_version_1` (`enterprise_id`,`resource_id`,`published_at`,`id`),
  KEY              `idx_resource_version_2` (`enterprise_id`,`published_by`),
  CONSTRAINT `fk_resource_version_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_resource_version_2` FOREIGN KEY (`enterprise_id`, `published_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_resource_version_1` CHECK ((`version_no` >= 1)),
  CONSTRAINT `ck_resource_version_2` CHECK ((`status` in (_utf8mb4'available', _utf8mb4'revoked')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可改写的发布配置；撤销资格不修改原正文';

CREATE TABLE `run_approval`
(
  `id`               varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`    varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`           varchar(100) NOT NULL COMMENT '等待决定的执行',
  `step_id`          varchar(100) NOT NULL COMMENT '相关步骤',
  `tool_call_id`     varchar(100)          DEFAULT NULL COMMENT '外部工具调用；纯流程确认时为空',
  `approver_user_id` varchar(100) NOT NULL COMMENT '唯一允许处理的原发起成员',
  `request_hash`     char(64)     NOT NULL COMMENT '固定操作和参数摘要',
  `summary_json`     json         NOT NULL COMMENT '可展示的目标、影响和内容摘要',
  `status`           varchar(20)  NOT NULL DEFAULT 'pending' COMMENT '确认状态',
  `expires_at`       datetime(3) NOT NULL COMMENT '失效时间',
  `decided_at`       datetime(3) DEFAULT NULL COMMENT '决定时间',
  `decision_note`    varchar(500)          DEFAULT NULL COMMENT '拒绝或撤销说明',
  `revision`         bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`       datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_approval_1` (`enterprise_id`,`run_id`,`step_id`),
  UNIQUE KEY `uk_run_approval_3` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_run_approval_2` (`enterprise_id`,`tool_call_id`),
  KEY                `idx_run_approval_1` (`enterprise_id`,`approver_user_id`,`status`,`created_at`,`id`),
  KEY                `idx_run_approval_2` (`status`,`expires_at`),
  CONSTRAINT `fk_run_approval_1` FOREIGN KEY (`enterprise_id`, `run_id`, `step_id`) REFERENCES `run_step` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_run_approval_2` FOREIGN KEY (`enterprise_id`, `tool_call_id`) REFERENCES `tool_call` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_run_approval_3` FOREIGN KEY (`enterprise_id`, `approver_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_run_approval_1` CHECK ((`status` in
                                         (_utf8mb4'pending', _utf8mb4'approved', _utf8mb4'rejected', _utf8mb4'expired',
                                          _utf8mb4'revoked')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次确定操作的用户决定，不产生通用允许规则';

CREATE TABLE `run_attempt`
(
  `id`                varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`            varchar(100) NOT NULL COMMENT '顶层执行',
  `attempt_no`        int          NOT NULL COMMENT '尝试编号，从 1 开始',
  `output_message_id` varchar(100) NOT NULL COMMENT '本次独立助手消息',
  `status`            varchar(32)  NOT NULL COMMENT '尝试状态',
  `started_at`        datetime(3) DEFAULT NULL COMMENT '开始时间',
  `finished_at`       datetime(3) DEFAULT NULL COMMENT '结束时间',
  `error_code`        varchar(64)  DEFAULT NULL COMMENT '失败代码',
  `error_summary`     varchar(500) DEFAULT NULL COMMENT '脱敏失败原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_attempt_1` (`enterprise_id`,`run_id`,`attempt_no`),
  UNIQUE KEY `uk_run_attempt_2` (`enterprise_id`,`run_id`,`id`),
  UNIQUE KEY `uk_run_attempt_3` (`enterprise_id`,`id`),
  KEY                 `idx_run_attempt_1` (`enterprise_id`,`output_message_id`),
  CONSTRAINT `fk_run_attempt_1` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_run_attempt_2` FOREIGN KEY (`enterprise_id`, `output_message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_run_attempt_1` CHECK ((`status` in (_utf8mb4'queued', _utf8mb4'running', _utf8mb4'waiting_approval',
                                                     _utf8mb4'completed', _utf8mb4'failed', _utf8mb4'cancelled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='执行的每次尝试，保留失败输出但只计一次顶层用量';

CREATE TABLE `run_checkpoint`
(
  `enterprise_id`       varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`              varchar(100) NOT NULL COMMENT '执行编号',
  `checkpoint_no`       bigint       NOT NULL COMMENT '检查点版本',
  `lease_version`       bigint       NOT NULL COMMENT '写入进程租约版本',
  `state_json`          json         NOT NULL COMMENT '步骤、审批、固定能力清单和已完成操作编号',
  `framework_state_key` varchar(500) DEFAULT NULL COMMENT '加密框架状态的隔离存储路径',
  `state_hash`          char(64)     NOT NULL COMMENT '检查点摘要',
  `updated_at`          datetime(3) NOT NULL COMMENT '保存时间',
  PRIMARY KEY (`enterprise_id`, `run_id`),
  CONSTRAINT `fk_run_checkpoint_1` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可恢复执行的最新完整检查点';

CREATE TABLE `run_step`
(
  `id`             varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`         varchar(100) NOT NULL COMMENT '顶层执行',
  `attempt_id`     varchar(100) NOT NULL COMMENT '所属尝试',
  `parent_step_id` varchar(100)          DEFAULT NULL COMMENT '同一尝试中的父步骤',
  `step_key`       varchar(128) NOT NULL COMMENT '尝试内稳定步骤标识',
  `kind`           varchar(32)  NOT NULL COMMENT '步骤类型',
  `title`          varchar(200) NOT NULL COMMENT '公开步骤标题',
  `display_order`  bigint       NOT NULL COMMENT '首次创建时保存的顺序',
  `status`         varchar(32)  NOT NULL DEFAULT 'pending' COMMENT '步骤状态',
  `input_json`     json         NOT NULL COMMENT '内部已验证输入，访问须鉴权',
  `output_json`    json                  DEFAULT NULL COMMENT '已保存结果',
  `public_summary` varchar(500)          DEFAULT NULL COMMENT '可展示摘要',
  `workflow_json`  json                  DEFAULT NULL COMMENT '真实工作流调用、固定资源与节点；其他步骤为空',
  `lease_version`  bigint       NOT NULL COMMENT '防止失去租约的进程写入',
  `started_at`     datetime(3) DEFAULT NULL COMMENT '开始时间',
  `finished_at`    datetime(3) DEFAULT NULL COMMENT '结束时间',
  `created_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_step_1` (`enterprise_id`,`attempt_id`,`step_key`),
  UNIQUE KEY `uk_run_step_2` (`enterprise_id`,`attempt_id`,`id`),
  UNIQUE KEY `uk_run_step_3` (`enterprise_id`,`run_id`,`id`),
  UNIQUE KEY `uk_run_step_4` (`enterprise_id`,`id`),
  KEY              `idx_run_step_1` (`enterprise_id`,`run_id`,`display_order`,`id`),
  KEY              `idx_run_step_2` (`enterprise_id`,`run_id`,`attempt_id`),
  KEY              `idx_run_step_3` (`enterprise_id`,`attempt_id`,`parent_step_id`),
  CONSTRAINT `fk_run_step_1` FOREIGN KEY (`enterprise_id`, `run_id`, `attempt_id`) REFERENCES `run_attempt` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_run_step_2` FOREIGN KEY (`enterprise_id`, `attempt_id`, `parent_step_id`) REFERENCES `run_step` (`enterprise_id`, `attempt_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_run_step_1` CHECK ((`status` in (_utf8mb4'pending', _utf8mb4'running', _utf8mb4'waiting_approval',
                                                  _utf8mb4'completed', _utf8mb4'failed', _utf8mb4'cancelled',
                                                  _utf8mb4'skipped')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程节点、模型、工具及子智能体的稳定层级和状态';

CREATE TABLE `scheduled_occurrence`
(
  `id`                  varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `schedule_id`         varchar(100) NOT NULL COMMENT '定时任务',
  `trigger_kind`        varchar(20)  NOT NULL COMMENT '自动或手动',
  `occurrence_key`      varchar(128) NOT NULL COMMENT '自动为预定时刻，手动为请求编号',
  `scheduled_for`       datetime(3) NOT NULL COMMENT '该次计划时间',
  `run_id`              varchar(100) DEFAULT NULL COMMENT '实际执行；错过或被阻止时为空',
  `conversation_id`     varchar(100) DEFAULT NULL COMMENT '本次独立会话',
  `status`              varchar(32)  NOT NULL COMMENT '发生状态',
  `reason_code`         varchar(64)  DEFAULT NULL COMMENT '错过、跳过或阻止原因',
  `started_at`          datetime(3) DEFAULT NULL COMMENT '开始时间',
  `finished_at`         datetime(3) DEFAULT NULL COMMENT '结束时间',
  `created_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `active_schedule_key` varchar(100) GENERATED ALWAYS AS ((case
                                                             when (`status` in (_utf8mb4'queued', _utf8mb4'running',
                                                                                _utf8mb4'waiting_approval'))
                                                               then `schedule_id`
                                                             else NULL end)) STORED COMMENT '数据库计算的活动对象编号，终态为空，禁止应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_occurrence_1` (`enterprise_id`,`schedule_id`,`occurrence_key`),
  UNIQUE KEY `uk_scheduled_occurrence_2` (`enterprise_id`,`schedule_id`,`id`),
  UNIQUE KEY `uk_scheduled_occurrence_4` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_scheduled_occurrence_3` (`enterprise_id`,`run_id`),
  UNIQUE KEY `uk_scheduled_occurrence_5` (`enterprise_id`,`active_schedule_key`),
  KEY                   `idx_scheduled_occurrence_1` (`enterprise_id`,`schedule_id`,`scheduled_for`,`id`),
  KEY                   `idx_scheduled_occurrence_2` (`enterprise_id`,`conversation_id`,`run_id`),
  CONSTRAINT `fk_scheduled_occurrence_1` FOREIGN KEY (`enterprise_id`, `schedule_id`) REFERENCES `scheduled_task` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_scheduled_occurrence_2` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_scheduled_occurrence_1` CHECK ((((`run_id` is null) and (`conversation_id` is null)) or
                                                 ((`run_id` is not null) and (`conversation_id` is not null)))),
  CONSTRAINT `ck_scheduled_occurrence_2` CHECK ((`trigger_kind` in (_utf8mb4'scheduled', _utf8mb4'manual'))),
  CONSTRAINT `ck_scheduled_occurrence_3` CHECK ((`status` in
                                                 (_utf8mb4'queued', _utf8mb4'running', _utf8mb4'waiting_approval',
                                                  _utf8mb4'completed', _utf8mb4'failed', _utf8mb4'cancelled',
                                                  _utf8mb4'skipped', _utf8mb4'missed', _utf8mb4'blocked')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每次到期或手动触发的唯一记录';

CREATE TABLE `scheduled_task`
(
  `id`                   varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `owner_user_id`        varchar(100) NOT NULL COMMENT '创建人和通知接收人',
  `hire_id`              varchar(100) NOT NULL COMMENT '本人雇佣关系',
  `agent_version_id`     varchar(100) NOT NULL COMMENT '固定智能体版本',
  `name`                 varchar(80)  NOT NULL COMMENT '计划名称',
  `input_text`           longtext     NOT NULL COMMENT '固定输入，最多 20000 字符，兼容每字符四字节',
  `frequency`            varchar(20)  NOT NULL COMMENT '重复方式',
  `local_date`           date                  DEFAULT NULL COMMENT '一次性计划的本地日期',
  `local_time`           time         NOT NULL COMMENT '本地执行时间，精确到分钟',
  `weekdays_json`        json         NOT NULL COMMENT '每周使用 1 至 7 的整数数组，其他规则为空数组',
  `month_day`            int                   DEFAULT NULL COMMENT '每月第几日',
  `timezone`             varchar(64)  NOT NULL COMMENT '计划时区',
  `enabled`              tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否允许未来触发',
  `max_retries`          int          NOT NULL DEFAULT '0' COMMENT '自动重试次数',
  `next_run_at`          datetime(3) DEFAULT NULL COMMENT '下一次实际 UTC 时间',
  `last_checked_at`      datetime(3) NOT NULL COMMENT '最近确认时间规则的时刻；创建、修改和调度检查都会更新',
  `pause_reason`         varchar(64)           DEFAULT NULL COMMENT '自动暂停的业务原因',
  `active_occurrence_id` varchar(100)          DEFAULT NULL COMMENT '唯一未结束发生记录',
  `deleted_at`           datetime(3) DEFAULT NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token`        varchar(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`             bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`           datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`           datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_task_1` (`enterprise_id`,`id`),
  KEY                    `idx_scheduled_task_1` (`enabled`,`next_run_at`,`id`),
  KEY                    `idx_scheduled_task_2` (`enterprise_id`,`owner_user_id`,`updated_at`,`id`),
  KEY                    `idx_scheduled_task_3` (`enabled`,`last_checked_at`,`id`),
  KEY                    `idx_scheduled_task_4` (`enterprise_id`,`owner_user_id`,`hire_id`),
  KEY                    `idx_scheduled_task_5` (`enterprise_id`,`agent_version_id`),
  KEY                    `idx_scheduled_task_6` (`enterprise_id`,`id`,`active_occurrence_id`),
  CONSTRAINT `fk_scheduled_task_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_scheduled_task_2` FOREIGN KEY (`enterprise_id`, `owner_user_id`, `hire_id`) REFERENCES `agent_hire` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_scheduled_task_3` FOREIGN KEY (`enterprise_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_scheduled_task_4` FOREIGN KEY (`enterprise_id`, `id`, `active_occurrence_id`) REFERENCES `scheduled_occurrence` (`enterprise_id`, `schedule_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_scheduled_task_1` CHECK ((`max_retries` between 0 and 2)),
  CONSTRAINT `ck_scheduled_task_2` CHECK (((`month_day` is null) or (`month_day` between 1 and 31))),
  CONSTRAINT `ck_scheduled_task_3` CHECK (((`enabled` = false) or (`next_run_at` is not null))),
  CONSTRAINT `ck_scheduled_task_4` CHECK (((second(`local_time`) = 0) and (`local_time` >= '00:00:00') and
                                           (`local_time` <= '23:59:00'))),
  CONSTRAINT `ck_scheduled_task_5` CHECK (((json_type(`weekdays_json`) = _utf8mb4'ARRAY') and
                                           (json_length(`weekdays_json`) <= 7))),
  CONSTRAINT `ck_scheduled_task_6` CHECK ((
    ((`frequency` = _utf8mb4'once') and (`local_date` is not null) and (`month_day` is null) and
     (json_length(`weekdays_json`) = 0)) or
    ((`frequency` = _utf8mb4'daily') and (`local_date` is null) and (`month_day` is null) and
     (json_length(`weekdays_json`) = 0)) or
    ((`frequency` = _utf8mb4'weekly') and (`local_date` is null) and (`month_day` is null) and
     (json_length(`weekdays_json`) between 1 and 7)) or
    ((`frequency` = _utf8mb4'monthly') and (`local_date` is null) and (`month_day` is not null) and
     (json_length(`weekdays_json`) = 0)))),
  CONSTRAINT `ck_scheduled_task_7` CHECK ((((`deleted_at` is null) and (`deleted_token` = _utf8mb4'')) or
                                           ((`deleted_at` is not null) and (`deleted_token` = `id`)))),
  CONSTRAINT `ck_scheduled_task_8` CHECK ((`frequency` in
                                           (_utf8mb4'once', _utf8mb4'daily', _utf8mb4'weekly', _utf8mb4'monthly')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='固定版本与本地时间规则的个人定时任务';

CREATE TABLE `sys_permission`
(
  `code`       varchar(128) NOT NULL,
  `name`       varchar(128) NOT NULL,
  `scope`      varchar(32)  NOT NULL,
  `menu_key`   varchar(128)          DEFAULT NULL,
  `menu_label` varchar(128)          DEFAULT NULL,
  `menu_path`  varchar(255)          DEFAULT NULL,
  `sort_no`    int          NOT NULL DEFAULT '0',
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sys_role`
(
  `id`            varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `code`          varchar(64)  NOT NULL,
  `name`          varchar(50)  NOT NULL,
  `builtin`       tinyint      NOT NULL DEFAULT '0',
  `created_at`    datetime(3) NOT NULL,
  `updated_at`    datetime(3) NOT NULL,
  `name_key`      varchar(50)  NOT NULL,
  `description`   varchar(500) NOT NULL DEFAULT '',
  `data_scope`    varchar(32)  NOT NULL DEFAULT 'enterprise',
  `status`        varchar(32)  NOT NULL DEFAULT 'active',
  `deleted_at`    datetime(3) DEFAULT NULL,
  `deleted_token` varchar(100) NOT NULL DEFAULT '',
  `revision`      bigint       NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_1` (`enterprise_id`,`code`,`deleted_token`),
  UNIQUE KEY `uk_sys_role_2` (`enterprise_id`,`name_key`,`deleted_token`),
  UNIQUE KEY `uk_sys_role_3` (`enterprise_id`,`id`),
  KEY             `idx_sys_role_enterprise` (`enterprise_id`),
  CONSTRAINT `fk_sys_role_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_sys_role_1` CHECK ((((`deleted_at` is null) and (`deleted_token` = _utf8mb4'')) or
                                     ((`deleted_at` is not null) and (`deleted_token` = `id`)))),
  CONSTRAINT `ck_sys_role_2` CHECK ((`data_scope` in (_utf8mb4'own', _utf8mb4'team', _utf8mb4'enterprise'))),
  CONSTRAINT `ck_sys_role_3` CHECK ((`status` in (_utf8mb4'active', _utf8mb4'disabled')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sys_role_permission`
(
  `enterprise_id`   varchar(100) NOT NULL,
  `role_id`         varchar(100) NOT NULL,
  `permission_code` varchar(128) NOT NULL,
  PRIMARY KEY (`enterprise_id`, `role_id`, `permission_code`),
  KEY               `idx_sys_role_permission_code` (`permission_code`),
  CONSTRAINT `fk_sys_role_permission_1` FOREIGN KEY (`enterprise_id`, `role_id`) REFERENCES `sys_role` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_sys_role_permission_2` FOREIGN KEY (`permission_code`) REFERENCES `sys_permission` (`code`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sys_user_role`
(
  `user_id`       varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `role_id`       varchar(100) NOT NULL,
  `created_at`    datetime(3) NOT NULL,
  PRIMARY KEY (`enterprise_id`, `user_id`, `role_id`),
  KEY             `idx_sys_user_role_enterprise` (`enterprise_id`,`user_id`),
  KEY             `idx_sys_user_role_role` (`role_id`),
  KEY             `fk_sys_user_role_2` (`enterprise_id`,`role_id`),
  CONSTRAINT `fk_sys_user_role_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_sys_user_role_2` FOREIGN KEY (`enterprise_id`, `role_id`) REFERENCES `sys_role` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `system_super_admin_lock`
(
  `id`         tinyint      NOT NULL,
  `user_id`    varchar(100) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_super_admin_lock_user` (`user_id`),
  CONSTRAINT `fk_system_super_admin_lock_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_system_super_admin_lock_1` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tag`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          varchar(20)  NOT NULL COMMENT '标签名',
  `name_key`      varchar(20)  NOT NULL COMMENT '规范化标签名',
  `deleted_at`    datetime(3) DEFAULT NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token` varchar(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`      bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_1` (`enterprise_id`,`name_key`,`deleted_token`),
  UNIQUE KEY `uk_tag_2` (`enterprise_id`,`id`),
  CONSTRAINT `fk_tag_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_tag_1` CHECK ((((`deleted_at` is null) and (`deleted_token` = _utf8mb4'')) or
                                ((`deleted_at` is not null) and (`deleted_token` = `id`))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业内共享标签，不授予数据权限';

CREATE TABLE `todo_history`
(
  `id`            varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `todo_id`       varchar(100) NOT NULL COMMENT '待办',
  `actor_user_id` varchar(100) NOT NULL COMMENT '实际操作者',
  `action`        varchar(64)  NOT NULL COMMENT '创建、转交、完成、重开、取消等动作',
  `before_json`   json DEFAULT NULL COMMENT '变更前的必要摘要',
  `after_json`    json         NOT NULL COMMENT '变更后的必要摘要',
  `created_at`    datetime(3) NOT NULL COMMENT '动作时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_todo_history_1` (`enterprise_id`,`id`),
  KEY             `idx_todo_history_1` (`enterprise_id`,`todo_id`,`created_at`,`id`),
  KEY             `idx_todo_history_2` (`enterprise_id`,`actor_user_id`),
  CONSTRAINT `fk_todo_history_1` FOREIGN KEY (`enterprise_id`, `todo_id`) REFERENCES `todo_item` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_todo_history_2` FOREIGN KEY (`enterprise_id`, `actor_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待办修改和状态转换的不可变历史';

CREATE TABLE `todo_item`
(
  `id`                     varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `title`                  varchar(200) NOT NULL COMMENT '待办标题',
  `description`            text         NOT NULL COMMENT '用户确认的说明',
  `created_by`             varchar(100) NOT NULL COMMENT '创建人',
  `owner_user_id`          varchar(100) NOT NULL COMMENT '当前负责人',
  `team_id`                varchar(100)          DEFAULT NULL COMMENT '团队待办范围；个人待办为空',
  `due_date`               date                  DEFAULT NULL COMMENT '企业时区下的截止日期，可不填',
  `priority`               varchar(20)  NOT NULL DEFAULT 'normal' COMMENT '普通或重要',
  `status`                 varchar(20)  NOT NULL DEFAULT 'pending' COMMENT '待办状态',
  `source_type`            varchar(20)  NOT NULL DEFAULT 'manual' COMMENT '来源类型',
  `source_conversation_id` varchar(100)          DEFAULT NULL COMMENT '来源会话逻辑引用，不授予访问权，清理时置空',
  `source_message_id`      varchar(100)          DEFAULT NULL COMMENT '来源消息逻辑引用',
  `source_run_id`          varchar(100)          DEFAULT NULL COMMENT '来源执行逻辑引用',
  `completed_at`           datetime(3) DEFAULT NULL COMMENT '本次完成时间，重开时清空',
  `deleted_at`             datetime(3) DEFAULT NULL COMMENT '删除时间',
  `revision`               bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_todo_item_1` (`enterprise_id`,`id`),
  KEY                      `idx_todo_item_1` (`enterprise_id`,`owner_user_id`,`status`,`due_date`,`id`),
  KEY                      `idx_todo_item_2` (`enterprise_id`,`team_id`,`status`,`updated_at`,`id`),
  KEY                      `idx_todo_item_3` (`enterprise_id`,`created_by`,`updated_at`,`id`),
  KEY                      `idx_todo_item_4` (`enterprise_id`,`owner_user_id`,`updated_at`,`id`),
  KEY                      `idx_todo_item_5` (`enterprise_id`,`source_conversation_id`),
  CONSTRAINT `fk_todo_item_1` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_todo_item_2` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_todo_item_3` FOREIGN KEY (`enterprise_id`, `team_id`) REFERENCES `enterprise_team` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_todo_item_1` CHECK ((((`status` = _utf8mb4'completed') and (`completed_at` is not null)) or
                                      ((`status` <> _utf8mb4'completed') and (`completed_at` is null)))),
  CONSTRAINT `ck_todo_item_2` CHECK (((`source_type` <> _utf8mb4'manual') or
                                      ((`source_conversation_id` is null) and (`source_message_id` is null) and
                                       (`source_run_id` is null)))),
  CONSTRAINT `ck_todo_item_3` CHECK (((`source_conversation_id` is not null) or
                                      ((`source_message_id` is null) and (`source_run_id` is null)))),
  CONSTRAINT `ck_todo_item_4` CHECK ((`priority` in (_utf8mb4'normal', _utf8mb4'high'))),
  CONSTRAINT `ck_todo_item_5` CHECK ((`status` in (_utf8mb4'pending', _utf8mb4'in_progress', _utf8mb4'completed',
                                                   _utf8mb4'cancelled'))),
  CONSTRAINT `ck_todo_item_6` CHECK ((`source_type` in (_utf8mb4'manual', _utf8mb4'message', _utf8mb4'workflow')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户确认创建的待办、负责人和来源';

CREATE TABLE `tool_call`
(
  `id`                     varchar(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          varchar(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`                 varchar(100)                                                DEFAULT NULL COMMENT '关联执行；管理员手动查询时可为空',
  `attempt_id`             varchar(100)                                                DEFAULT NULL COMMENT '所属执行尝试；管理员手动查询时为空',
  `step_id`                varchar(100)                                                DEFAULT NULL COMMENT '关联步骤',
  `actor_user_id`          varchar(100) NOT NULL COMMENT '实际操作成员',
  `resource_id`            varchar(100) NOT NULL COMMENT '实际使用的能力资源',
  `resource_kind`          varchar(20)  NOT NULL COMMENT '工具所属资源类型',
  `resource_version_id`    varchar(100)                                                DEFAULT NULL COMMENT '固定发布版本；管理查询使用当前草稿时为空',
  `draft_revision`         bigint                                                      DEFAULT NULL COMMENT '手动查询使用的草稿修改版本',
  `tool_name`              varchar(128) NOT NULL COMMENT '真实工具名称',
  `operation_id`           varchar(100) NOT NULL COMMENT '固定外部请求去重编号',
  `plugin_tool_id`         varchar(100)                                                DEFAULT NULL COMMENT '固定插件工具，数据查询时为空',
  `framework_call_id`      varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'AgentScope 提供的原始工具调用编号',
  `framework_session_id`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT 'AgentScope 提供的父会话或子会话编号',
  `argument_hash`          char(64)     NOT NULL COMMENT '规范化原始参数摘要，用于阻止重复拒绝后再次提问',
  `request_encrypted_json` json         NOT NULL COMMENT '绑定企业与调用编号的加密原始参数',
  `result_encrypted_json`  json                                                        DEFAULT NULL COMMENT '已完成调用交还框架的加密结果',
  `lease_version`          bigint       NOT NULL                                       DEFAULT '0' COMMENT '当前操作进程的领取版本',
  `submitted_at`           datetime(3) DEFAULT NULL COMMENT '即将发送业务请求时保存；此后中断不能假定未执行',
  `operation_class`        varchar(20)  NOT NULL COMMENT '操作类别',
  `status`                 varchar(32)  NOT NULL                                       DEFAULT 'prepared' COMMENT '调用结果',
  `request_hash`           char(64)     NOT NULL COMMENT '固定参数摘要',
  `request_redacted_json`  json         NOT NULL COMMENT '脱敏请求信息',
  `result_redacted_json`   json                                                        DEFAULT NULL COMMENT '脱敏结果',
  `attempt_count`          int          NOT NULL                                       DEFAULT '0' COMMENT '同一逻辑操作的请求次数',
  `query_count`            int          NOT NULL                                       DEFAULT '0' COMMENT '按照原操作编号查询结果的请求次数',
  `last_query_at`          datetime(3) DEFAULT NULL COMMENT '最近一次准备发送结果查询的时间',
  `error_code`             varchar(64)                                                 DEFAULT NULL COMMENT '稳定错误代码',
  `error_summary`          varchar(500)                                                DEFAULT NULL COMMENT '脱敏错误',
  `started_at`             datetime(3) DEFAULT NULL COMMENT '首次请求时间',
  `finished_at`            datetime(3) DEFAULT NULL COMMENT '已知终态时间',
  `duration_ms`            bigint                                                      DEFAULT NULL COMMENT '实际耗时，未知为空',
  `created_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_call_1` (`enterprise_id`,`operation_id`),
  UNIQUE KEY `uk_tool_call_3` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_tool_call_2` (`enterprise_id`,`attempt_id`,`framework_session_id`,`framework_call_id`),
  KEY                      `idx_tool_call_1` (`enterprise_id`,`created_at`,`id`),
  KEY                      `idx_tool_call_2` (`enterprise_id`,`run_id`,`created_at`,`id`),
  KEY                      `idx_tool_call_3` (`status`,`created_at`),
  KEY                      `idx_tool_call_4` (`enterprise_id`,`actor_user_id`),
  KEY                      `idx_tool_call_5` (`enterprise_id`,`resource_id`,`resource_version_id`),
  KEY                      `idx_tool_call_6` (`enterprise_id`,`attempt_id`,`step_id`),
  KEY                      `idx_tool_call_7` (`enterprise_id`,`plugin_tool_id`),
  KEY                      `idx_tool_call_8` (`enterprise_id`,`run_id`,`attempt_id`),
  CONSTRAINT `fk_tool_call_1` FOREIGN KEY (`enterprise_id`, `actor_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_tool_call_2` FOREIGN KEY (`enterprise_id`, `resource_id`, `resource_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_tool_call_3` FOREIGN KEY (`enterprise_id`, `attempt_id`, `step_id`) REFERENCES `run_step` (`enterprise_id`, `attempt_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_tool_call_4` FOREIGN KEY (`enterprise_id`, `plugin_tool_id`) REFERENCES `plugin_tool` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_tool_call_5` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_tool_call_6` FOREIGN KEY (`enterprise_id`, `run_id`, `attempt_id`) REFERENCES `run_attempt` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_tool_call_1` CHECK ((((`run_id` is null) and (`step_id` is null) and (`attempt_id` is null)) or
                                      ((`run_id` is not null) and (`step_id` is not null) and
                                       (`attempt_id` is not null)))),
  CONSTRAINT `ck_tool_call_2` CHECK ((((`resource_version_id` is not null) and (`draft_revision` is null)) or
                                      ((`resource_kind` = _utf8mb4'data') and (`run_id` is null) and
                                       (`resource_version_id` is null) and (`draft_revision` is not null) and
                                       (`draft_revision` > 0)) or
                                      ((`resource_kind` in (_utf8mb4'agent', _utf8mb4'workflow')) and
                                       (`run_id` is not null) and (`resource_version_id` is null) and
                                       (`draft_revision` is null)))),
  CONSTRAINT `ck_tool_call_3` CHECK ((((`resource_kind` = _utf8mb4'plugin') and (`plugin_tool_id` is not null)) or
                                      ((`resource_kind` in (_utf8mb4'knowledge', _utf8mb4'data')) and
                                       (`plugin_tool_id` is null)) or
                                      ((`resource_kind` in (_utf8mb4'agent', _utf8mb4'workflow')) and
                                       (`run_id` is not null) and (`plugin_tool_id` is null)))),
  CONSTRAINT `ck_tool_call_4` CHECK ((`resource_kind` in
                                      (_utf8mb4'plugin', _utf8mb4'knowledge', _utf8mb4'data', _utf8mb4'agent',
                                       _utf8mb4'workflow'))),
  CONSTRAINT `ck_tool_call_5` CHECK ((`operation_class` in
                                      (_utf8mb4'read', _utf8mb4'write', _utf8mb4'destructive', _utf8mb4'unknown'))),
  CONSTRAINT `ck_tool_call_6` CHECK ((`status` in (_utf8mb4'prepared', _utf8mb4'waiting_approval', _utf8mb4'running',
                                                   _utf8mb4'succeeded', _utf8mb4'failed', _utf8mb4'cancelled',
                                                   _utf8mb4'unknown')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具真实调用、固定操作编号及结果未知状态';

CREATE TABLE `user_preference`
(
  `user_id`                       varchar(100) NOT NULL COMMENT '全局用户',
  `theme`                         varchar(20)  NOT NULL DEFAULT 'system' COMMENT '主题选择',
  `task_completion_notifications` tinyint      NOT NULL DEFAULT '1' COMMENT '是否接收任务完成提醒',
  `memory_enabled`                tinyint      NOT NULL DEFAULT '0' COMMENT '本人是否允许使用显式保存的偏好',
  `response_language`             varchar(20)  NOT NULL DEFAULT 'zh-CN' COMMENT '用户选择的模型回复语言',
  `revision`                      bigint       NOT NULL DEFAULT '1' COMMENT '并发修改版本，每次成功修改加一',
  `created_at`                    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`                    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_preference_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_user_preference_1` CHECK ((`theme` in (_utf8mb4'system', _utf8mb4'light', _utf8mb4'dark')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户本人界面和显式记忆偏好';

CREATE TABLE `user_workspace`
(
  `id`             varchar(100) NOT NULL,
  `enterprise_id`  varchar(100) NOT NULL,
  `user_id`        varchar(100) NOT NULL,
  `directory_path` varchar(128) NOT NULL COMMENT '持久根目录内的相对目录',
  `initialized_at` datetime(3) DEFAULT NULL,
  `created_at`     datetime(3) NOT NULL,
  `updated_at`     datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_workspace_owner` (`enterprise_id`,`user_id`),
  UNIQUE KEY `uk_user_workspace_identity` (`enterprise_id`,`user_id`,`id`),
  UNIQUE KEY `uk_user_workspace_enterprise` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_user_workspace_directory` (`directory_path`),
  CONSTRAINT `fk_user_workspace_member` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `workspace_project`
(
  `id`                     varchar(100)                                           NOT NULL,
  `enterprise_id`          varchar(100)                                           NOT NULL,
  `user_id`                varchar(100)                                           NOT NULL,
  `workspace_id`           varchar(100)                                           NOT NULL,
  `name`                   varchar(100)                                           NOT NULL,
  `directory_path`         varchar(512)                                           NOT NULL COMMENT '用户工作空间内的项目相对目录',
  `directory_key`          varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '用于检查跨平台目录冲突的小写路径',
  `legacy_conversation_id` varchar(100) DEFAULT NULL COMMENT '需要迁入工作文件的旧会话',
  `initialized_at`         datetime(3) DEFAULT NULL,
  `created_at`             datetime(3) NOT NULL,
  `updated_at`             datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_project_owner` (`enterprise_id`,`user_id`,`id`),
  UNIQUE KEY `uk_workspace_project_enterprise` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_workspace_project_directory` (`workspace_id`,`directory_key`),
  UNIQUE KEY `uk_workspace_project_legacy` (`legacy_conversation_id`),
  KEY                      `idx_workspace_project_owner_created` (`enterprise_id`,`user_id`,`created_at`,`id`),
  KEY                      `fk_workspace_project_workspace` (`enterprise_id`,`user_id`,`workspace_id`),
  CONSTRAINT `fk_workspace_project_workspace` FOREIGN KEY (`enterprise_id`, `user_id`, `workspace_id`) REFERENCES `user_workspace` (`enterprise_id`, `user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `workspace_project_file`
(
  `id`            varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `user_id`       varchar(100) NOT NULL,
  `project_id`    varchar(100) NOT NULL,
  `file_id`       varchar(100) NOT NULL,
  `created_at`    datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_project_file_enterprise` (`enterprise_id`,`id`),
  UNIQUE KEY `uk_workspace_project_file` (`project_id`,`file_id`),
  KEY             `idx_workspace_project_file_owner` (`enterprise_id`,`user_id`,`file_id`),
  KEY             `idx_workspace_project_file_project` (`enterprise_id`,`user_id`,`project_id`),
  KEY             `idx_workspace_project_file_source` (`enterprise_id`,`file_id`),
  CONSTRAINT `fk_workspace_project_file_project` FOREIGN KEY (`enterprise_id`, `user_id`, `project_id`) REFERENCES `workspace_project` (`enterprise_id`, `user_id`, `id`),
  CONSTRAINT `fk_workspace_project_file_source` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 全部表及其引用目标已存在，恢复原有外键检查设置。
SET SESSION FOREIGN_KEY_CHECKS = @agenteam_init_foreign_key_checks;

-- 唯一演示控制记录；不创建演示用户或企业。
INSERT INTO `demo_account` (`id`)
VALUES (1);

-- 权限目录一次写入，内容与原始全部脚本执行后的结果一致。
INSERT INTO `sys_permission` (`code`, `name`, `scope`, `menu_key`, `menu_label`, `menu_path`, `sort_no`)
VALUES ('admin.view', '进入管理端', 'admin', 'admin', '企业管理', '/admin', 20),
       ('agent.create', '创建智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('agent.delete', '删除智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('agent.edit', '编辑智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('agent.hire', '管理本人雇佣', 'user', NULL, NULL, NULL, 0),
       ('agent.hire_approve', '审批雇佣申请', 'admin', NULL, NULL, NULL, 0),
       ('agent.market_view', '查看员工广场', 'user', NULL, NULL, NULL, 0),
       ('agent.preview', '预览智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('agent.publish', '发布智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('agent.run', '运行数字员工', 'user', NULL, NULL, NULL, 0),
       ('agent.view', '查看智能体', 'capabilities', NULL, NULL, NULL, 0),
       ('announcement.manage', '管理企业公告', 'admin', NULL, NULL, NULL, 0),
       ('announcement.view', '查看企业公告配置', 'admin', 'announcements', '企业公告', '/admin?tab=announcements', 85),
       ('audit.export', '导出审计记录', 'admin', NULL, NULL, NULL, 0),
       ('audit.view', '查看审计记录', 'admin', NULL, NULL, NULL, 0),
       ('capabilities.view', '进入能力中心', 'capabilities', NULL, NULL, NULL, 0),
       ('conversation.export', '下载本人对话结果', 'user', NULL, NULL, NULL, 0),
       ('conversation.manage', '管理本人对话', 'user', NULL, NULL, NULL, 0),
       ('conversation.view', '查看本人对话', 'user', NULL, NULL, NULL, 0),
       ('credential.manage', '管理凭据引用与轮换', 'admin', NULL, NULL, NULL, 0),
       ('credential.use', '在资源中使用连接凭据', 'capabilities', NULL, NULL, NULL, 0),
       ('credential.view', '查看连接凭据', 'admin', 'credentials', '连接凭据', '/admin?tab=credentials', 79),
       ('data.create', '创建数据源', 'capabilities', NULL, NULL, NULL, 0),
       ('data.delete', '删除数据源', 'capabilities', NULL, NULL, NULL, 0),
       ('data.edit', '编辑数据源', 'capabilities', NULL, NULL, NULL, 0),
       ('data.publish', '发布数据源', 'capabilities', NULL, NULL, NULL, 0),
       ('data.query', '查询授权数据', 'capabilities', NULL, NULL, NULL, 0),
       ('data.view', '查看数据源', 'capabilities', NULL, NULL, NULL, 0),
       ('enterprise.manage', '管理企业资料', 'admin', NULL, NULL, NULL, 0),
       ('enterprise.members.manage', '管理成员与邀请', 'admin', 'members', '成员管理', '/admin?tab=members', 31),
       ('enterprise.members.view', '查看成员与邀请', 'admin', 'members', '成员管理', '/admin?tab=members', 30),
       ('enterprise.permissions.view', '查看权限目录', 'admin', 'permissions', '权限说明', '/admin?tab=permissions',
        60),
       ('enterprise.roles.manage', '管理角色', 'admin', 'roles', '角色管理', '/admin?tab=roles', 51),
       ('enterprise.roles.view', '查看角色', 'admin', 'roles', '角色管理', '/admin?tab=roles', 50),
       ('enterprise.teams.manage', '管理团队', 'admin', 'teams', '团队管理', '/admin?tab=teams', 41),
       ('enterprise.teams.view', '查看团队', 'admin', 'teams', '团队管理', '/admin?tab=teams', 40),
       ('enterprise.view', '查看企业资料', 'admin', NULL, NULL, NULL, 0),
       ('knowledge.create', '创建知识库', 'capabilities', NULL, NULL, NULL, 0),
       ('knowledge.delete', '删除知识库', 'capabilities', NULL, NULL, NULL, 0),
       ('knowledge.edit', '编辑知识库', 'capabilities', NULL, NULL, NULL, 0),
       ('knowledge.publish', '发布知识库', 'capabilities', NULL, NULL, NULL, 0),
       ('knowledge.search', '检索授权知识', 'capabilities', NULL, NULL, NULL, 0),
       ('knowledge.view', '查看知识库', 'capabilities', NULL, NULL, NULL, 0),
       ('model.manage', '管理模型提供方与模型', 'admin', NULL, NULL, NULL, 0),
       ('model.view', '查看模型配置', 'admin', 'models', '模型配置', '/admin?tab=models', 69),
       ('plugin.create', '创建插件', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.delete', '删除插件', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.edit', '编辑插件', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.invoke', '在任务中调用授权工具', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.publish', '发布插件', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.test', '检查插件连接', 'capabilities', NULL, NULL, NULL, 0),
       ('plugin.view', '查看插件', 'capabilities', NULL, NULL, NULL, 0),
       ('resource.grants.manage', '管理资源授权', 'admin', NULL, NULL, NULL, 0),
       ('resource.grants.view', '查看资源使用范围', 'admin', NULL, NULL, NULL, 0),
       ('resource.manage_all', '维护企业全部能力资源', 'admin', NULL, NULL, NULL, 0),
       ('schedule.manage', '管理本人定时任务', 'user', NULL, NULL, NULL, 0),
       ('schedule.view', '查看本人定时任务', 'user', NULL, NULL, NULL, 0),
       ('skill.create', '创建技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.delete', '删除技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.edit', '编辑技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.export', '导出技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.import', '导入技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.publish', '发布技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.use', '在任务中使用技能', 'capabilities', NULL, NULL, NULL, 0),
       ('skill.view', '查看技能', 'capabilities', NULL, NULL, NULL, 0),
       ('tag.manage', '管理标签', 'capabilities', NULL, NULL, NULL, 0),
       ('todo.manage', '管理本人待办', 'user', NULL, NULL, NULL, 0),
       ('todo.team_manage', '维护授权团队待办', 'user', NULL, NULL, NULL, 0),
       ('todo.team_view', '查看授权团队待办', 'user', NULL, NULL, NULL, 0),
       ('todo.view', '查看本人待办', 'user', NULL, NULL, NULL, 0),
       ('tool_log.details', '查看脱敏调用详情', 'admin', NULL, NULL, NULL, 0),
       ('tool_log.export', '导出调用记录', 'admin', NULL, NULL, NULL, 0),
       ('tool_log.view', '查看调用记录摘要', 'admin', NULL, NULL, NULL, 0),
       ('usage.manage', '调整用量上限', 'admin', NULL, NULL, NULL, 0),
       ('usage.view', '查看企业用量', 'admin', NULL, NULL, NULL, 0),
       ('workflow.create', '创建工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workflow.delete', '删除工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workflow.edit', '编辑工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workflow.preview', '测试工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workflow.publish', '发布工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workflow.view', '查看工作流', 'capabilities', NULL, NULL, NULL, 0),
       ('workspace.view', '进入用户工作台', 'user', 'workspace', '对话工作台', '/', 10);
