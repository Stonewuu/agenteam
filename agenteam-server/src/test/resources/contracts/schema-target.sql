-- AgenTeam 首版完整目标结构。
-- 仅在空的验证数据库执行，不得作为现有数据库增量升级脚本。
-- 本附件由 scripts/generate_contracts.py 生成；当前开发结构与初始化方式参见数据库分册。
SET NAMES utf8mb4;
SET
time_zone = '+00:00';

-- 全局登录身份，不因企业成员被移除而删除
CREATE TABLE `app_user`
(
  `id`                  VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `username`            VARCHAR(128) NOT NULL COMMENT '登录用户名原始展示值',
  `username_normalized` VARCHAR(128) NOT NULL COMMENT '去空白并转小写的登录比较值',
  `password_hash`       VARCHAR(255) NOT NULL COMMENT '带算法标记的密码散列，禁止保存明文',
  `display_name`        VARCHAR(128) NOT NULL COMMENT '全局显示名',
  `email`               VARCHAR(254) NULL COMMENT '当前邮箱',
  `email_normalized`    VARCHAR(254) NULL COMMENT '用于唯一性比较的邮箱',
  `email_verified_at`   DATETIME(3) NULL COMMENT '邮箱验证完成时间',
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '全局用户状态',
  `is_super_admin`      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '唯一系统管理员标记，须同时受单例锁约束',
  `last_enterprise_id`  VARCHAR(100) NULL COMMENT '上次使用企业偏好，不作为访问授权',
  `session_version`     BIGINT       NOT NULL DEFAULT 1 COMMENT '登录会话撤销版本',
  `password_changed_at` DATETIME(3) NULL COMMENT '最近密码变更时间',
  `revision`            BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_user_1` (`username_normalized`),
  UNIQUE KEY `uk_app_user_2` (`email_normalized`),
  KEY                   `idx_app_user_1` (`status`, `id`),
  CONSTRAINT `ck_app_user_1` CHECK (`status` IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局登录身份，不因企业成员被移除而删除';

-- 唯一超级管理员约束
CREATE TABLE `system_super_admin_lock`
(
  `id`         TINYINT      NOT NULL COMMENT '只能为 1',
  `user_id`    VARCHAR(100) NOT NULL COMMENT '唯一管理员用户编号',
  `created_at` DATETIME(3) NOT NULL COMMENT '初始化时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_super_admin_lock_1` (`user_id`),
  CONSTRAINT `ck_system_super_admin_lock_1` CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='唯一超级管理员约束';

-- 企业资料与全企业策略
CREATE TABLE `enterprise`
(
  `id`                     VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `name`                   VARCHAR(80)  NOT NULL COMMENT '企业名称',
  `description`            VARCHAR(500) NOT NULL DEFAULT '' COMMENT '企业简介',
  `contact_email`          VARCHAR(254) NULL COMMENT '联系邮箱',
  `timezone`               VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '业务显示时区',
  `quota_timezone`         VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '当前用量周期时区',
  `pending_quota_timezone` VARCHAR(64) NULL COMMENT '下个自然月生效的用量时区',
  `quota_period_start`     DATETIME(3) NOT NULL COMMENT '当前次数周期的实际起点',
  `quota_period_end`       DATETIME(3) NOT NULL COMMENT '当前次数周期的结束时间，不包含边界时刻',
  `status`                 VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '企业状态',
  `permission_version`     BIGINT       NOT NULL DEFAULT 1 COMMENT '角色和授权变更后递增',
  `retention_days`         INT          NOT NULL DEFAULT 180 COMMENT '会话最后更新后的保留天数',
  `created_by`             VARCHAR(100) NOT NULL COMMENT '创建该企业的用户',
  `revision`               BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  KEY                      `idx_enterprise_1` (`status`, `created_at`, `id`),
  KEY                      `idx_enterprise_2` (`created_by`),
  CONSTRAINT `ck_enterprise_1` CHECK (retention_days BETWEEN 90 AND 730),
  CONSTRAINT `ck_enterprise_2` CHECK (quota_period_end > quota_period_start),
  CONSTRAINT `ck_enterprise_3` CHECK (`status` IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业资料与全企业策略';

-- 用户在指定企业的成员身份与停用状态
CREATE TABLE `enterprise_member`
(
  `enterprise_id`         VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`               VARCHAR(100) NOT NULL COMMENT '全局用户编号',
  `display_name`          VARCHAR(128) NOT NULL COMMENT '企业内显示名',
  `status`                VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '成员状态',
  `notification_sequence` BIGINT       NOT NULL DEFAULT 0 COMMENT '本成员最后提交的通知序号；通知事务锁成员行后递增',
  `joined_at`             DATETIME(3) NOT NULL COMMENT '最近一次有效加入时间',
  `revision`              BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `user_id`),
  KEY                     `idx_enterprise_member_1` (`user_id`, `status`, `joined_at`),
  KEY                     `idx_enterprise_member_2` (`enterprise_id`, `status`, `user_id`),
  CONSTRAINT `ck_enterprise_member_1` CHECK (`status` IN ('active', 'disabled', 'removed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户在指定企业的成员身份与停用状态';

-- 企业内团队及负责人
CREATE TABLE `enterprise_team`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          VARCHAR(50)  NOT NULL COMMENT '团队名称',
  `name_key`      VARCHAR(50)  NOT NULL COMMENT '规范化团队名称',
  `description`   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '团队简介',
  `owner_user_id` VARCHAR(100) NOT NULL COMMENT '负责人，必须为有效企业成员',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '团队状态',
  `deleted_at`    DATETIME(3) NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enterprise_team_1` (`enterprise_id`, `name_key`, `deleted_token`),
  UNIQUE KEY `uk_enterprise_team_2` (`enterprise_id`, `id`),
  KEY             `idx_enterprise_team_1` (`enterprise_id`, `status`, `updated_at`, `id`),
  KEY             `idx_enterprise_team_2` (`enterprise_id`, `owner_user_id`),
  CONSTRAINT `ck_enterprise_team_1` CHECK ((deleted_at IS NULL AND deleted_token = '') OR
                                           (deleted_at IS NOT NULL AND deleted_token = id)),
  CONSTRAINT `ck_enterprise_team_2` CHECK (`status` IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业内团队及负责人';

-- 团队成员关系，直接携带企业防止跨企业关联
CREATE TABLE `enterprise_team_member`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `team_id`       VARCHAR(100) NOT NULL COMMENT '团队编号',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '成员用户编号',
  `joined_at`     DATETIME(3) NOT NULL COMMENT '加入团队时间',
  PRIMARY KEY (`enterprise_id`, `team_id`, `user_id`),
  KEY             `idx_enterprise_team_member_1` (`enterprise_id`, `user_id`, `team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='团队成员关系，直接携带企业防止跨企业关联';

-- 平台提供的操作权限目录
CREATE TABLE `sys_permission`
(
  `code`       VARCHAR(128) NOT NULL COMMENT '正式权限代码',
  `name`       VARCHAR(128) NOT NULL COMMENT '中文名称',
  `scope`      VARCHAR(32)  NOT NULL COMMENT '所属使用区域',
  `menu_key`   VARCHAR(128) NULL COMMENT '对应菜单标识',
  `menu_label` VARCHAR(128) NULL COMMENT '中文菜单名',
  `menu_path`  VARCHAR(255) NULL COMMENT '不含具体企业编号的菜单路径模板',
  `sort_no`    INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
  PRIMARY KEY (`code`),
  CONSTRAINT `ck_sys_permission_1` CHECK (`scope` IN ('user', 'capabilities', 'admin'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台提供的操作权限目录';

-- 每企业独立角色；不再使用可变的全局角色共享行
CREATE TABLE `sys_role`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `code`          VARCHAR(64)  NOT NULL COMMENT '企业内角色代码',
  `name`          VARCHAR(50)  NOT NULL COMMENT '角色名称',
  `name_key`      VARCHAR(50)  NOT NULL COMMENT '规范化名称',
  `description`   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '职责说明',
  `builtin`       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否为不可直接编辑的内置角色',
  `data_scope`    VARCHAR(32)  NOT NULL DEFAULT 'enterprise' COMMENT '本角色授予操作的数据范围',
  `status`        VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '角色是否有效',
  `deleted_at`    DATETIME(3) NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_1` (`enterprise_id`, `code`, `deleted_token`),
  UNIQUE KEY `uk_sys_role_2` (`enterprise_id`, `name_key`, `deleted_token`),
  UNIQUE KEY `uk_sys_role_3` (`enterprise_id`, `id`),
  CONSTRAINT `ck_sys_role_1` CHECK ((deleted_at IS NULL AND deleted_token = '') OR
                                    (deleted_at IS NOT NULL AND deleted_token = id)),
  CONSTRAINT `ck_sys_role_2` CHECK (`data_scope` IN ('own', 'team', 'enterprise')),
  CONSTRAINT `ck_sys_role_3` CHECK (`status` IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每企业独立角色；不再使用可变的全局角色共享行';

-- 角色与操作权限关联
CREATE TABLE `sys_role_permission`
(
  `enterprise_id`   VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `role_id`         VARCHAR(100) NOT NULL COMMENT '企业角色',
  `permission_code` VARCHAR(128) NOT NULL COMMENT '权限代码',
  PRIMARY KEY (`enterprise_id`, `role_id`, `permission_code`),
  KEY               `idx_sys_role_permission_1` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色与操作权限关联';

-- 企业成员的角色分配
CREATE TABLE `sys_user_role`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '成员用户',
  `role_id`       VARCHAR(100) NOT NULL COMMENT '本企业角色',
  `created_at`    DATETIME(3) NOT NULL COMMENT '分配时间',
  PRIMARY KEY (`enterprise_id`, `user_id`, `role_id`),
  KEY             `idx_sys_user_role_1` (`enterprise_id`, `role_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业成员的角色分配';

-- 用户本人界面和显式记忆偏好
CREATE TABLE `user_preference`
(
  `user_id`                       VARCHAR(100) NOT NULL COMMENT '全局用户',
  `theme`                         VARCHAR(20)  NOT NULL DEFAULT 'system' COMMENT '主题选择',
  `task_completion_notifications` BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否接收任务完成提醒',
  `memory_enabled`                BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '本人是否允许使用显式保存的偏好',
  `revision`                      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`                    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`                    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `ck_user_preference_1` CHECK (`theme` IN ('system', 'light', 'dark'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户本人界面和显式记忆偏好';

-- 密码重置和邮箱验证的一次性凭据，仅保存摘要
CREATE TABLE `auth_token`
(
  `id`           VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `user_id`      VARCHAR(100) NOT NULL COMMENT '目标用户',
  `purpose`      VARCHAR(32)  NOT NULL COMMENT '凭据用途',
  `token_hash`   CHAR(64)     NOT NULL COMMENT '随机凭据的 SHA-256 摘要',
  `target_email` VARCHAR(254) NULL COMMENT '邮箱验证的目标邮箱',
  `expires_at`   DATETIME(3) NOT NULL COMMENT '失效时间',
  `consumed_at`  DATETIME(3) NULL COMMENT '使用完成时间',
  `created_at`   DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_token_1` (`token_hash`),
  KEY            `idx_auth_token_1` (`user_id`, `purpose`, `created_at`),
  KEY            `idx_auth_token_2` (`expires_at`),
  CONSTRAINT `ck_auth_token_1` CHECK (`purpose` IN ('password_reset', 'email_verify'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='密码重置和邮箱验证的一次性凭据，仅保存摘要';

-- 加入企业的邀请与真实邮件发送状态
CREATE TABLE `enterprise_invitation`
(
  `id`                VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `email`             VARCHAR(254) NOT NULL COMMENT '受邀邮箱',
  `email_normalized`  VARCHAR(254) NOT NULL COMMENT '规范化受邀邮箱',
  `pending_email`     VARCHAR(254) NULL COMMENT '待处理时为规范化邮箱，终态置空',
  `display_name`      VARCHAR(128) NULL COMMENT '建议成员显示名',
  `team_ids_json`     JSON         NOT NULL COMMENT '邀请指定的团队编号数组',
  `role_ids_json`     JSON         NOT NULL COMMENT '邀请指定的角色编号数组',
  `note`              VARCHAR(200) NOT NULL DEFAULT '' COMMENT '邀请说明',
  `token_hash`        CHAR(64)     NOT NULL COMMENT '邀请随机凭据摘要',
  `status`            VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '邀请状态',
  `delivery_status`   VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '邮件服务处理结果',
  `delivery_attempts` INT          NOT NULL DEFAULT 0 COMMENT '邮件发送尝试次数',
  `delivery_error`    VARCHAR(500) NULL COMMENT '脱敏的最近发送错误',
  `created_by`        VARCHAR(100) NOT NULL COMMENT '邀请发起成员',
  `accepted_user_id`  VARCHAR(100) NULL COMMENT '最终接受邀请的用户',
  `expires_at`        DATETIME(3) NOT NULL COMMENT '邀请失效时间',
  `accepted_at`       DATETIME(3) NULL COMMENT '接受时间',
  `revision`          BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enterprise_invitation_1` (`enterprise_id`, `pending_email`),
  UNIQUE KEY `uk_enterprise_invitation_2` (`token_hash`),
  UNIQUE KEY `uk_enterprise_invitation_3` (`enterprise_id`, `id`),
  KEY                 `idx_enterprise_invitation_1` (`enterprise_id`, `status`, `created_at`, `id`),
  KEY                 `idx_enterprise_invitation_2` (`status`, `expires_at`),
  KEY                 `idx_enterprise_invitation_3` (`enterprise_id`, `created_by`),
  KEY                 `idx_enterprise_invitation_4` (`accepted_user_id`),
  CONSTRAINT `ck_enterprise_invitation_1` CHECK ((status = 'pending' AND pending_email IS NOT NULL AND
                                                  pending_email = email_normalized) OR
                                                 (status <> 'pending' AND pending_email IS NULL)),
  CONSTRAINT `ck_enterprise_invitation_2` CHECK (`status` IN ('pending', 'accepted', 'expired', 'revoked')),
  CONSTRAINT `ck_enterprise_invitation_3` CHECK (`delivery_status` IN ('pending', 'sent', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加入企业的邀请与真实邮件发送状态';

-- 六类能力资源的身份、所有权、当前发布指针和可用状态
CREATE TABLE `resource`
(
  `id`                   VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `kind`                 VARCHAR(20)  NOT NULL COMMENT '资源类型',
  `name`                 VARCHAR(80)  NOT NULL COMMENT '当前草稿名称',
  `subtype`              VARCHAR(32) NULL COMMENT '从已验证草稿派生的智能体、插件或数据源类型，不由客户端独立修改',
  `description`          VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前草稿简介',
  `owner_user_id`        VARCHAR(100) NOT NULL COMMENT '资源所有者',
  `source`               VARCHAR(20)  NOT NULL DEFAULT 'created' COMMENT '资源来源',
  `source_reference`     VARCHAR(128) NULL COMMENT '内置模板或导入格式标识，不包含外部凭据',
  `status`               VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '可用、停用或已删除',
  `published_version_id` VARCHAR(100) NULL COMMENT '当前发布版本；为空表示尚未发布',
  `next_version_no`      INT          NOT NULL DEFAULT 1 COMMENT '下一次发布分配的序号',
  `deleted_at`           DATETIME(3) NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token`        VARCHAR(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`             BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_1` (`enterprise_id`, `id`),
  KEY                    `idx_resource_1` (`enterprise_id`, `kind`, `status`, `updated_at`, `id`),
  KEY                    `idx_resource_2` (`enterprise_id`, `owner_user_id`, `kind`, `updated_at`, `id`),
  KEY                    `idx_resource_3` (`enterprise_id`, `id`, `published_version_id`),
  CONSTRAINT `ck_resource_1` CHECK (next_version_no >= 1),
  CONSTRAINT `ck_resource_2` CHECK ((status = 'deleted' AND deleted_at IS NOT NULL) OR
                                    (status <> 'deleted' AND deleted_at IS NULL)),
  CONSTRAINT `ck_resource_3` CHECK ((deleted_at IS NULL AND deleted_token = '') OR
                                    (deleted_at IS NOT NULL AND deleted_token = id)),
  CONSTRAINT `ck_resource_4` CHECK (`kind` IN ('agent', 'skill', 'plugin', 'workflow', 'knowledge', 'data')),
  CONSTRAINT `ck_resource_5` CHECK (`source` IN ('created', 'imported', 'builtin')),
  CONSTRAINT `ck_resource_6` CHECK (`status` IN ('active', 'disabled', 'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='六类能力资源的身份、所有权、当前发布指针和可用状态';

-- 资源当前可编辑配置；修改时同步递增资源主表版本
CREATE TABLE `resource_draft`
(
  `enterprise_id`   VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`     VARCHAR(100) NOT NULL COMMENT '资源编号',
  `schema_version`  INT          NOT NULL DEFAULT 1 COMMENT '配置结构版本，首版为 1',
  `config_json`     JSON         NOT NULL COMMENT '通过对应资源配置结构验证的正文',
  `validation_json` JSON NULL COMMENT '与配置摘要绑定的检查和工具发现结果，不接受客户端直接修改',
  `config_hash`     CHAR(64)     NOT NULL COMMENT '规范化配置摘要',
  `updated_by`      VARCHAR(100) NOT NULL COMMENT '最后编辑成员',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `resource_id`),
  KEY               `idx_resource_draft_1` (`enterprise_id`, `updated_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源当前可编辑配置；修改时同步递增资源主表版本';

-- 不可改写的发布配置；撤销资格不修改原正文
CREATE TABLE `resource_version`
(
  `id`             VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`    VARCHAR(100) NOT NULL COMMENT '所属资源',
  `version_no`     INT          NOT NULL COMMENT '资源内递增发布序号',
  `name`           VARCHAR(80)  NOT NULL COMMENT '发布时公开名称',
  `description`    VARCHAR(500) NOT NULL DEFAULT '' COMMENT '发布时简介',
  `schema_version` INT          NOT NULL DEFAULT 1 COMMENT '配置结构版本',
  `config_json`    JSON         NOT NULL COMMENT '完整固定配置',
  `config_hash`    CHAR(64)     NOT NULL COMMENT '规范化配置摘要',
  `release_note`   VARCHAR(500) NOT NULL COMMENT '发布说明',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'available' COMMENT '版本使用资格',
  `published_by`   VARCHAR(100) NOT NULL COMMENT '发布成员',
  `published_at`   DATETIME(3) NOT NULL COMMENT '发布时间',
  `revoked_at`     DATETIME(3) NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_version_1` (`enterprise_id`, `resource_id`, `version_no`),
  UNIQUE KEY `uk_resource_version_2` (`enterprise_id`, `resource_id`, `id`),
  UNIQUE KEY `uk_resource_version_3` (`enterprise_id`, `id`),
  KEY              `idx_resource_version_1` (`enterprise_id`, `resource_id`, `published_at`, `id`),
  KEY              `idx_resource_version_2` (`enterprise_id`, `published_by`),
  CONSTRAINT `ck_resource_version_1` CHECK (version_no >= 1),
  CONSTRAINT `ck_resource_version_2` CHECK (`status` IN ('available', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可改写的发布配置；撤销资格不修改原正文';

-- 发布版本的固定依赖与使用位置
CREATE TABLE `resource_dependency`
(
  `enterprise_id`         VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `parent_version_id`     VARCHAR(100) NOT NULL COMMENT '引用方发布版本',
  `dependency_version_id` VARCHAR(100) NOT NULL COMMENT '被引用的固定版本',
  `binding_key`           VARCHAR(100) NOT NULL COMMENT '配置字段或工作流节点中的引用位置',
  `dependency_kind`       VARCHAR(20)  NOT NULL COMMENT '依赖类型',
  `ordinal`               INT          NOT NULL DEFAULT 0 COMMENT '同类依赖顺序',
  PRIMARY KEY (`enterprise_id`, `parent_version_id`, `dependency_version_id`, `binding_key`),
  KEY                     `idx_resource_dependency_1` (`enterprise_id`, `dependency_version_id`, `parent_version_id`),
  CONSTRAINT `ck_resource_dependency_1` CHECK (parent_version_id <> dependency_version_id),
  CONSTRAINT `ck_resource_dependency_2` CHECK (`dependency_kind` IN
                                               ('agent', 'skill', 'plugin', 'workflow', 'knowledge', 'data'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发布版本的固定依赖与使用位置';

-- 资源对企业、团队或用户的明确授权
CREATE TABLE `resource_grant`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`   VARCHAR(100) NOT NULL COMMENT '资源编号',
  `subject_type`  VARCHAR(20)  NOT NULL COMMENT '被授权主体类型',
  `subject_id`    VARCHAR(100) NOT NULL COMMENT '主体编号，业务事务验证其属于本企业',
  `capability`    VARCHAR(20)  NOT NULL COMMENT '授权能力',
  `created_by`    VARCHAR(100) NOT NULL COMMENT '授权操作者',
  `created_at`    DATETIME(3) NOT NULL COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_grant_1` (`enterprise_id`, `resource_id`, `subject_type`, `subject_id`, `capability`),
  UNIQUE KEY `uk_resource_grant_2` (`enterprise_id`, `id`),
  KEY             `idx_resource_grant_1` (`enterprise_id`, `subject_type`, `subject_id`, `capability`, `resource_id`),
  KEY             `idx_resource_grant_2` (`enterprise_id`, `created_by`),
  CONSTRAINT `ck_resource_grant_1` CHECK (`subject_type` IN ('enterprise', 'team', 'user')),
  CONSTRAINT `ck_resource_grant_2` CHECK (`capability` IN ('view', 'use', 'edit'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源对企业、团队或用户的明确授权';

-- 企业内共享标签，不授予数据权限
CREATE TABLE `tag`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          VARCHAR(20)  NOT NULL COMMENT '标签名',
  `name_key`      VARCHAR(20)  NOT NULL COMMENT '规范化标签名',
  `deleted_at`    DATETIME(3) NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_1` (`enterprise_id`, `name_key`, `deleted_token`),
  UNIQUE KEY `uk_tag_2` (`enterprise_id`, `id`),
  CONSTRAINT `ck_tag_1` CHECK ((deleted_at IS NULL AND deleted_token = '') OR
                               (deleted_at IS NOT NULL AND deleted_token = id))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业内共享标签，不授予数据权限';

-- 资源与标签的多对多关联
CREATE TABLE `resource_tag`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`   VARCHAR(100) NOT NULL COMMENT '资源',
  `tag_id`        VARCHAR(100) NOT NULL COMMENT '标签',
  PRIMARY KEY (`enterprise_id`, `resource_id`, `tag_id`),
  KEY             `idx_resource_tag_1` (`enterprise_id`, `tag_id`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源与标签的多对多关联';

-- 智能体在员工广场的上架和雇佣策略
CREATE TABLE `agent_listing`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `agent_id`      VARCHAR(100) NOT NULL COMMENT '类型必须为 agent 的资源',
  `listed`        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否允许新用户从广场发现并雇佣',
  `hire_policy`   VARCHAR(20)  NOT NULL DEFAULT 'automatic' COMMENT '雇佣策略',
  `updated_by`    VARCHAR(100) NOT NULL COMMENT '最后操作人',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `agent_id`),
  KEY             `idx_agent_listing_1` (`enterprise_id`, `updated_by`),
  CONSTRAINT `ck_agent_listing_1` CHECK (`hire_policy` IN ('automatic', 'approval'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体在员工广场的上架和雇佣策略';

-- 本人在企业内使用数字员工的唯一关系
CREATE TABLE `agent_hire`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '雇佣成员',
  `agent_id`      VARCHAR(100) NOT NULL COMMENT '智能体身份',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '使用关系状态',
  `hired_at`      DATETIME(3) NOT NULL COMMENT '本次恢复或建立雇佣时间',
  `last_used_at`  DATETIME(3) NULL COMMENT '最近实际开始执行时间',
  `paused_at`     DATETIME(3) NULL COMMENT '暂停时间',
  `terminated_at` DATETIME(3) NULL COMMENT '解除时间',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_hire_1` (`enterprise_id`, `user_id`, `agent_id`),
  UNIQUE KEY `uk_agent_hire_2` (`enterprise_id`, `user_id`, `agent_id`, `id`),
  UNIQUE KEY `uk_agent_hire_3` (`enterprise_id`, `user_id`, `id`),
  UNIQUE KEY `uk_agent_hire_4` (`enterprise_id`, `id`),
  KEY             `idx_agent_hire_1` (`enterprise_id`, `user_id`, `status`, `last_used_at`, `id`),
  KEY             `idx_agent_hire_2` (`enterprise_id`, `agent_id`),
  CONSTRAINT `ck_agent_hire_1` CHECK (`status` IN ('active', 'paused', 'terminated'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人在企业内使用数字员工的唯一关系';

-- 雇佣申请及审批历史
CREATE TABLE `agent_hire_request`
(
  `id`             VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`        VARCHAR(100) NOT NULL COMMENT '申请成员',
  `agent_id`       VARCHAR(100) NOT NULL COMMENT '申请的智能体',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '申请状态',
  `pending_marker` INT NULL DEFAULT 1 COMMENT '待处理时为 1，终态为空，保证只有一条待处理申请',
  `request_note`   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '申请说明',
  `decision_note`  VARCHAR(500) NULL COMMENT '审批说明',
  `decided_by`     VARCHAR(100) NULL COMMENT '实际审批成员',
  `decided_at`     DATETIME(3) NULL COMMENT '审批时间',
  `expires_at`     DATETIME(3) NOT NULL COMMENT '申请失效时间',
  `revision`       BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_hire_request_1` (`enterprise_id`, `user_id`, `agent_id`, `pending_marker`),
  UNIQUE KEY `uk_agent_hire_request_2` (`enterprise_id`, `id`),
  KEY              `idx_agent_hire_request_1` (`enterprise_id`, `status`, `created_at`, `id`),
  KEY              `idx_agent_hire_request_2` (`status`, `expires_at`, `id`),
  KEY              `idx_agent_hire_request_3` (`enterprise_id`, `agent_id`),
  KEY              `idx_agent_hire_request_4` (`enterprise_id`, `decided_by`),
  CONSTRAINT `ck_agent_hire_request_1` CHECK ((status = 'pending' AND pending_marker IS NOT NULL AND pending_marker = 1) OR
                                              (status <> 'pending' AND pending_marker IS NULL)),
  CONSTRAINT `ck_agent_hire_request_2` CHECK (`status` IN ('pending', 'approved', 'rejected', 'withdrawn', 'expired'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雇佣申请及审批历史';

-- 加密凭据与密钥轮换信息，无明文读取接口
CREATE TABLE `credential`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          VARCHAR(80)  NOT NULL COMMENT '凭据名称',
  `kind`          VARCHAR(20)  NOT NULL COMMENT '凭据用途',
  `ciphertext`    MEDIUMBLOB   NOT NULL COMMENT '加密后的秘密正文',
  `nonce`         VARBINARY(12) NOT NULL COMMENT '本次加密随机数',
  `auth_tag`      VARBINARY(16) NOT NULL COMMENT '认证加密校验值',
  `key_version`   VARCHAR(64)  NOT NULL COMMENT '部署密钥服务的密钥版本',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '凭据状态',
  `updated_by`    VARCHAR(100) NOT NULL COMMENT '最后维护成员',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_credential_1` (`enterprise_id`, `id`),
  KEY             `idx_credential_1` (`enterprise_id`, `updated_by`),
  CONSTRAINT `ck_credential_1` CHECK (`kind` IN ('bearer', 'basic', 'database', 'api_key')),
  CONSTRAINT `ck_credential_2` CHECK (`status` IN ('active', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加密凭据与密钥轮换信息，无明文读取接口';

-- 管理员维护的模型提供方；开发阶段密钥按要求明文保存
CREATE TABLE `model_provider`
(
  `id`            VARCHAR(100)  NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100)  NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `name`          VARCHAR(80)   NOT NULL COMMENT '提供方名称',
  `protocol`      VARCHAR(32)   NOT NULL COMMENT '实际接入的模型协议',
  `base_url`      VARCHAR(2048) NOT NULL COMMENT '模型服务地址',
  `api_key`       TEXT          NOT NULL COMMENT '模型访问密钥，当前以明文保存，不返回到管理列表',
  `enabled`       BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '是否允许使用该提供方',
  `revision`      BIGINT        NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_provider_1` (`enterprise_id`, `name`),
  UNIQUE KEY `uk_model_provider_2` (`enterprise_id`, `id`),
  CONSTRAINT `ck_model_provider_1` CHECK (`protocol` IN ('openai'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员维护的模型提供方；开发阶段密钥按要求明文保存';

-- 管理员配置的模型；已被资源或对话使用后不改写模型身份与能力
CREATE TABLE `model_profile`
(
  `id`                VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `provider_id`       VARCHAR(100) NOT NULL COMMENT '所属模型提供方',
  `name`              VARCHAR(80)  NOT NULL COMMENT '可选择的显示名称',
  `model_name`        VARCHAR(128) NOT NULL COMMENT '上游实际模型名',
  `capabilities_json` JSON         NOT NULL COMMENT '管理员声明的输入类型、工具调用和参数上限',
  `enabled`           BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否允许继续使用',
  `revision`          BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_profile_1` (`enterprise_id`, `name`),
  UNIQUE KEY `uk_model_profile_2` (`enterprise_id`, `id`),
  KEY                 `idx_model_profile_1` (`enterprise_id`, `provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员配置的模型；已被资源或对话使用后不改写模型身份与能力';

-- 插件发布版本中的固定工具结构和运行约束
CREATE TABLE `plugin_tool`
(
  `id`                     VARCHAR(100)                          NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          VARCHAR(100)                          NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `plugin_version_id`      VARCHAR(100)                          NOT NULL COMMENT '插件发布版本',
  `name`                   VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '区分大小写的服务端正式工具名称',
  `description`            VARCHAR(500)                          NOT NULL COMMENT '公开说明',
  `schema_hash`            CHAR(64)                              NOT NULL COMMENT '工具结构摘要',
  `input_schema_json`      JSON                                  NOT NULL COMMENT '参数验证结构',
  `output_schema_json`     JSON NULL COMMENT '返回结构',
  `annotations_json`       JSON                                  NOT NULL COMMENT '用于比较结构的服务端行为提示，不作为可信授权',
  `operation_class`        VARCHAR(20)                           NOT NULL COMMENT '只读、写入或未知',
  `enabled`                BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否允许调用，紧急停用可立即改变',
  `supports_deduplication` BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否经过验证支持请求去重',
  `supports_result_query`  BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否支持按操作编号查结果',
  `supports_cancel`        BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否可请求取消',
  `redact_paths_json`      JSON                                  NOT NULL COMMENT '必须隐藏的参数和结果字段',
  `timeout_seconds`        INT                                   NOT NULL DEFAULT 30 COMMENT '工具调用上限',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  `entry_id`               VARCHAR(100) COLLATE utf8mb4_0900_bin NULL COMMENT '合集内稳定工具条目编号，旧发布记录可为空',
  `source_json`            JSON NULL COMMENT '工具实际来源、固定版本及执行配置，旧发布记录可为空',
  UNIQUE KEY `uk_plugin_tool_entry` (`enterprise_id`, `plugin_version_id`, `entry_id`),
  UNIQUE KEY `uk_plugin_tool_2` (`enterprise_id`, `id`),
  CONSTRAINT `ck_plugin_tool_1` CHECK (timeout_seconds BETWEEN 1 AND 120),
  CONSTRAINT `ck_plugin_tool_2` CHECK (`operation_class` IN ('read', 'write', 'destructive', 'unknown'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='插件发布版本中的固定工具结构和运行约束';

-- 企业文件及检查状态，不向普通响应直接暴露存储密钥
CREATE TABLE `file_object`
(
  `id`                  VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `owner_user_id`       VARCHAR(100) NOT NULL COMMENT '上传成员',
  `resource_id`         VARCHAR(100) NULL COMMENT '准备上传或生成文件时指定的来源资源',
  `resource_version_id` VARCHAR(100) NULL COMMENT '导出内容的固定来源版本',
  `run_id`              VARCHAR(100) NULL COMMENT '生成结果附件的执行，普通上传为空',
  `purpose`             VARCHAR(32)  NOT NULL COMMENT '文件用途',
  `original_name`       VARCHAR(255) NOT NULL COMMENT '原文件名，下载时安全编码',
  `media_type`          VARCHAR(128) NOT NULL COMMENT '实际检测的文件类型',
  `expected_size_bytes` BIGINT NULL COMMENT '准备上传时声明的大小，平台生成文件为空',
  `expected_sha256`     CHAR(64) NULL COMMENT '准备上传时声明的内容摘要',
  `upload_expires_at`   DATETIME(3) NULL COMMENT '上传地址的截止时间，平台生成文件为空',
  `upload_lease_id`     VARCHAR(100) NULL COMMENT '当前上传占用编号，避免并发覆盖同一文件',
  `upload_lease_until`  DATETIME(3) NULL COMMENT '当前上传占用的截止时间',
  `size_bytes`          BIGINT       NOT NULL COMMENT '文件真实字节数',
  `sha256`              CHAR(64)     NOT NULL COMMENT '内容摘要',
  `storage_key`         VARCHAR(500) NOT NULL COMMENT '包含企业的随机存储路径',
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '上传和检查结果',
  `error_code`          VARCHAR(64) NULL COMMENT '可映射给用户的错误代码',
  `expires_at`          DATETIME(3) NULL COMMENT '临时文件或导出的清理时间',
  `deleted_at`          DATETIME(3) NULL COMMENT '不可再下载时间',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_object_1` (`storage_key`),
  UNIQUE KEY `uk_file_object_2` (`enterprise_id`, `id`),
  KEY                   `idx_file_object_1` (`enterprise_id`, `owner_user_id`, `purpose`, `created_at`, `id`),
  KEY                   `idx_file_object_2` (`status`, `expires_at`),
  KEY                   `idx_file_object_3` (`enterprise_id`, `resource_id`),
  KEY                   `idx_file_object_4` (`enterprise_id`, `resource_id`, `resource_version_id`),
  KEY                   `idx_file_object_5` (`enterprise_id`, `run_id`),
  CONSTRAINT `ck_file_object_1` CHECK (size_bytes >= 0),
  CONSTRAINT `ck_file_object_2` CHECK (expected_size_bytes IS NULL OR expected_size_bytes BETWEEN 1 AND 20971520),
  CONSTRAINT `ck_file_object_3` CHECK (`purpose` IN ('attachment', 'knowledge', 'data_import', 'skill_import', 'export',
                                                     'artifact')),
  CONSTRAINT `ck_file_object_4` CHECK (`status` IN ('pending', 'uploaded', 'scanning', 'ready', 'rejected', 'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业文件及检查状态，不向普通响应直接暴露存储密钥';

-- 对话附件通过扫描和解析后保存的受限文字
CREATE TABLE `file_text`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '原始附件文件',
  `sha256`        CHAR(64)     NOT NULL COMMENT '已解析原文件摘要',
  `content_text`  LONGTEXT     NOT NULL COMMENT '供对话读取的文字，最多五万字符',
  `truncated`     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '完整文字超过对话附件读取上限',
  `created_at`    DATETIME(3) NOT NULL COMMENT '解析结果保存时间',
  PRIMARY KEY (`enterprise_id`, `file_id`),
  CONSTRAINT `ck_file_text_1` CHECK (CHAR_LENGTH(content_text) BETWEEN 1 AND 50000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话附件通过扫描和解析后保存的受限文字';

-- CSV 扫描和完整解析后的字段与行数
CREATE TABLE `file_data_profile`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '数据原文件',
  `sha256`        CHAR(64)     NOT NULL COMMENT '已解析原文件摘要',
  `columns_json`  JSON         NOT NULL COMMENT '按原文件顺序记录字段名、推断类型和是否有空值',
  `row_count`     BIGINT       NOT NULL COMMENT '完整解析后的数据行数',
  `created_at`    DATETIME(3) NOT NULL COMMENT '结果保存时间',
  PRIMARY KEY (`enterprise_id`, `file_id`),
  CONSTRAINT `ck_file_data_profile_1` CHECK (row_count BETWEEN 0 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CSV 扫描和完整解析后的字段与行数';

-- 尚未按集合字段确认类型的 CSV 原始行
CREATE TABLE `file_data_row`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '数据原文件',
  `row_no`        BIGINT       NOT NULL COMMENT '原文件中除表头外的行序号，从一开始',
  `values_json`   JSON         NOT NULL COMMENT '按列顺序保留字符串和空值的数组',
  PRIMARY KEY (`enterprise_id`, `file_id`, `row_no`),
  CONSTRAINT `ck_file_data_row_1` CHECK (row_no BETWEEN 1 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='尚未按集合字段确认类型的 CSV 原始行';

-- 知识库资料与原子切换的处理版本
CREATE TABLE `knowledge_document`
(
  `id`                 VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`      VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`        VARCHAR(100) NOT NULL COMMENT '知识库资源',
  `name`               VARCHAR(255) NOT NULL COMMENT '资料名称',
  `file_id`            VARCHAR(100) NOT NULL COMMENT '本次等待处理或最近上传的文件',
  `active_file_id`     VARCHAR(100) NULL COMMENT '当前可用处理版本的原文件',
  `active_generation`  INT          NOT NULL DEFAULT 0 COMMENT '当前可检索处理版本；0 表示尚无',
  `pending_generation` INT          NOT NULL DEFAULT 0 COMMENT '正在处理的目标版本；无任务时为 0',
  `last_generation`    INT          NOT NULL DEFAULT 0 COMMENT '已经分配的最大处理版本，失败后也不复用',
  `status`             VARCHAR(32)  NOT NULL DEFAULT 'uploaded' COMMENT '资料处理状态',
  `chunk_count`        INT          NOT NULL DEFAULT 0 COMMENT '当前有效文本块数量',
  `page_count`         INT NULL COMMENT '有实际页码时的页数',
  `error_code`         VARCHAR(64) NULL COMMENT '错误代码',
  `error_summary`      VARCHAR(500) NULL COMMENT '可公开处理错误',
  `processed_at`       DATETIME(3) NULL COMMENT '最近成功处理时间',
  `deleted_at`         DATETIME(3) NULL COMMENT '删除时间',
  `revision`           BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_document_1` (`enterprise_id`, `id`),
  KEY                  `idx_knowledge_document_1` (`enterprise_id`, `resource_id`, `status`, `updated_at`, `id`),
  KEY                  `idx_knowledge_document_2` (`status`, `deleted_at`),
  KEY                  `idx_knowledge_document_3` (`enterprise_id`, `file_id`),
  KEY                  `idx_knowledge_document_4` (`enterprise_id`, `active_file_id`),
  CONSTRAINT `ck_knowledge_document_1` CHECK (active_generation >= 0 AND pending_generation >= 0 AND
                                              last_generation >= active_generation AND
                                              last_generation >= pending_generation),
  CONSTRAINT `ck_knowledge_document_2` CHECK (`status` IN
                                              ('uploaded', 'scanning', 'queued', 'processing', 'ready', 'failed',
                                               'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库资料与原子切换的处理版本';

-- 某次资料处理版本的可检索文本块
CREATE TABLE `knowledge_chunk`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `document_id`   VARCHAR(100) NOT NULL COMMENT '知识资料',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '该处理版本的原文件',
  `generation`    INT          NOT NULL COMMENT '处理版本',
  `ordinal`       INT          NOT NULL COMMENT '块顺序，从 1 开始',
  `locator_json`  JSON         NOT NULL COMMENT '页码、章节与原文位置',
  `content_text`  TEXT         NOT NULL COMMENT '不超过 1200 字符的文本',
  `search_terms`  MEDIUMTEXT NULL COMMENT '含知识库摘要的相邻字符检索词，旧处理版本切换后清空',
  `content_hash`  CHAR(64)     NOT NULL COMMENT '文本摘要',
  `created_at`    DATETIME(3) NOT NULL COMMENT '写入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chunk_1` (`enterprise_id`, `document_id`, `generation`, `ordinal`),
  UNIQUE KEY `uk_knowledge_chunk_2` (`enterprise_id`, `id`),
  KEY             `idx_knowledge_chunk_1` (`enterprise_id`, `document_id`, `generation`, `id`),
  KEY             `idx_knowledge_chunk_2` (`enterprise_id`, `file_id`),
  FULLTEXT KEY `ft_knowledge_chunk_1` (`search_terms`),
  CONSTRAINT `ck_knowledge_chunk_1` CHECK (generation >= 1),
  CONSTRAINT `ck_knowledge_chunk_2` CHECK (ordinal >= 1),
  CONSTRAINT `ck_knowledge_chunk_3` CHECK (CHAR_LENGTH(content_text) BETWEEN 1 AND 1200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='某次资料处理版本的可检索文本块';

-- 数据源内允许查询的集合与当前版本
CREATE TABLE `data_collection`
(
  `id`                VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `resource_id`       VARCHAR(100) NOT NULL COMMENT '数据源资源',
  `name`              VARCHAR(80)  NOT NULL COMMENT '集合显示名',
  `source_name`       VARCHAR(128) NOT NULL COMMENT '服务端验证的表名、接口映射或文件集合名',
  `active_generation` INT          NOT NULL DEFAULT 1 COMMENT '当前可查询结构与数据版本',
  `file_id`           VARCHAR(100) NULL COMMENT '文件类型的数据来源',
  `row_count`         BIGINT NULL COMMENT '已导入文件的真实行数；远程集合未知时为空',
  `status`            VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '可用、处理中或停用',
  `revision`          BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_data_collection_1` (`enterprise_id`, `resource_id`, `source_name`),
  UNIQUE KEY `uk_data_collection_2` (`enterprise_id`, `id`),
  KEY                 `idx_data_collection_1` (`enterprise_id`, `file_id`),
  CONSTRAINT `ck_data_collection_1` CHECK (`status` IN ('active', 'processing', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源内允许查询的集合与当前版本';

-- 集合处理版本中的字段类型和访问限制
CREATE TABLE `data_field`
(
  `enterprise_id` VARCHAR(100)                          NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` VARCHAR(100)                          NOT NULL COMMENT '集合编号',
  `generation`    INT                                   NOT NULL COMMENT '结构版本',
  `name`          VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '真实字段名',
  `label`         VARCHAR(80)                           NOT NULL COMMENT '中文展示名',
  `value_type`    VARCHAR(20)                           NOT NULL COMMENT '字段值类型',
  `readable`      BOOLEAN                               NOT NULL DEFAULT TRUE COMMENT '是否允许读取',
  `filterable`    BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否允许筛选',
  `sortable`      BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '是否允许排序',
  `sensitive`     BOOLEAN                               NOT NULL DEFAULT FALSE COMMENT '展示与日志是否隐藏明细',
  `nullable`      BOOLEAN                               NOT NULL DEFAULT TRUE COMMENT '是否接受空值',
  `ordinal`       INT                                   NOT NULL COMMENT '字段显示顺序',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`, `name`),
  CONSTRAINT `ck_data_field_1` CHECK (`value_type` IN
                                      ('string', 'integer', 'decimal', 'boolean', 'date', 'datetime', 'object'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集合处理版本中的字段类型和访问限制';

-- 集合版本对应的真实文件和导入统计
CREATE TABLE `data_generation`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` VARCHAR(100) NOT NULL COMMENT '所属集合',
  `generation`    INT          NOT NULL COMMENT '该集合的处理版本',
  `source_hash`   CHAR(64)     NOT NULL COMMENT '登记本版本时连接类型、地址、参数映射和凭据编号的摘要',
  `file_id`       VARCHAR(100) NULL COMMENT '该版本的原文件，远程集合为空',
  `row_count`     BIGINT NULL COMMENT '已验证的文件行数，远程集合未知时为空',
  `created_at`    DATETIME(3) NOT NULL COMMENT '该版本完整提交时间',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`),
  KEY             `idx_data_generation_1` (`enterprise_id`, `file_id`),
  CONSTRAINT `ck_data_generation_1` CHECK (generation >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集合版本对应的真实文件和导入统计';

-- 文件数据源中经过类型校验的一行数据
CREATE TABLE `data_record`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `collection_id` VARCHAR(100) NOT NULL COMMENT '集合编号',
  `generation`    INT          NOT NULL COMMENT '导入版本',
  `row_no`        BIGINT       NOT NULL COMMENT '行顺序，从 1 开始',
  `values_json`   JSON         NOT NULL COMMENT '按该版本字段顺序保存已验证值的数组，不重复保存字段名',
  `created_at`    DATETIME(3) NOT NULL COMMENT '导入时间',
  PRIMARY KEY (`enterprise_id`, `collection_id`, `generation`, `row_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件数据源中经过类型校验的一行数据';

-- 仅保存本人确认的偏好；删除时物理删除正文
CREATE TABLE `agent_memory`
(
  `id`                VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`           VARCHAR(100) NOT NULL COMMENT '偏好所属用户',
  `agent_id`          VARCHAR(100) NOT NULL COMMENT '智能体身份',
  `memory_key`        VARCHAR(50)  NOT NULL COMMENT '允许记忆的主题',
  `content`           VARCHAR(500) NOT NULL COMMENT '本人明确确认的偏好',
  `source_message_id` VARCHAR(100) NULL COMMENT '来源消息逻辑引用，消息清理时置空',
  `expires_at`        DATETIME(3) NOT NULL COMMENT '偏好失效时间',
  `revision`          BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_memory_1` (`enterprise_id`, `user_id`, `agent_id`, `memory_key`),
  UNIQUE KEY `uk_agent_memory_2` (`enterprise_id`, `id`),
  KEY                 `idx_agent_memory_1` (`expires_at`),
  KEY                 `idx_agent_memory_2` (`enterprise_id`, `source_message_id`),
  KEY                 `idx_agent_memory_3` (`enterprise_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仅保存本人确认的偏好；删除时物理删除正文';

-- 本人私有会话，固定使用的智能体版本与连续事件序号
CREATE TABLE `agent_conversation`
(
  `id`                  VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`             VARCHAR(100) NOT NULL COMMENT '会话所有者',
  `agent_id`            VARCHAR(100) NULL COMMENT '智能体身份；单独测试工作流时为空',
  `preview_resource_id` VARCHAR(100) NULL COMMENT '单独测试的工作流资源；员工会话为空',
  `agent_version_id`    VARCHAR(100) NULL COMMENT '固定发布版本；草稿预览可为空',
  `hire_id`             VARCHAR(100) NULL COMMENT '正式会话使用的雇佣关系；预览可为空',
  `title`               VARCHAR(255) NOT NULL COMMENT '会话标题',
  `title_customized`    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否由用户改过标题',
  `mode`                VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT '正式或调试',
  `approval_policy`     VARCHAR(20)  NOT NULL DEFAULT 'default' COMMENT '工具审批策略：默认、自动批准、完全访问',
  `model_profile_id`    VARCHAR(100) NULL COMMENT '当前对话选择的模型；旧会话为空时使用固定员工版本的默认模型',
  `reasoning_effort`    VARCHAR(20) NULL COMMENT '当前对话的思考等级；为空时采用模型默认行为',
  `status`              VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '会话可用状态',
  `favorite`            BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '本人收藏',
  `active_run_id`       VARCHAR(100) NULL COMMENT '唯一未结束执行；修改前必须锁会话行',
  `last_sequence`       BIGINT       NOT NULL DEFAULT 0 COMMENT '会话内已保存的连续事件序号',
  `event_delivery_mode` VARCHAR(16)  NOT NULL DEFAULT 'database',
  `deleted_at`          DATETIME(3) NULL COMMENT '标记删除时间',
  `revision`            BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_conversation_1` (`enterprise_id`, `user_id`, `id`),
  UNIQUE KEY `uk_agent_conversation_2` (`enterprise_id`, `id`),
  KEY                   `idx_agent_conversation_1` (`enterprise_id`, `user_id`, `status`, `updated_at`, `id`),
  KEY                   `idx_agent_conversation_2` (`enterprise_id`, `user_id`, `agent_id`, `updated_at`, `id`),
  KEY                   `idx_agent_conversation_3` (`mode`, `created_at`, `id`),
  KEY                   `idx_agent_conversation_4` (`enterprise_id`, `agent_id`),
  KEY                   `idx_agent_conversation_5` (`enterprise_id`, `preview_resource_id`),
  KEY                   `idx_agent_conversation_6` (`enterprise_id`, `agent_id`, `agent_version_id`),
  KEY                   `idx_agent_conversation_7` (`enterprise_id`, `user_id`, `agent_id`, `hire_id`),
  KEY                   `idx_agent_conversation_8` (`enterprise_id`, `id`, `active_run_id`),
  CONSTRAINT `ck_conversation_approval_policy` CHECK (`approval_policy` IN ('default', 'auto_approve', 'full_access')),
  CONSTRAINT `ck_agent_conversation_1` CHECK (last_sequence >= 0),
  CONSTRAINT `ck_agent_conversation_2` CHECK (mode <> 'normal' OR
                                              (agent_id IS NOT NULL AND agent_version_id IS NOT NULL AND hire_id IS NOT NULL)),
  CONSTRAINT `ck_agent_conversation_3` CHECK ((agent_id IS NOT NULL AND preview_resource_id IS NULL) OR
                                              (mode = 'preview' AND agent_id IS NULL AND
                                               preview_resource_id IS NOT NULL AND agent_version_id IS NULL AND
                                               hire_id IS NULL)),
  CONSTRAINT `ck_agent_conversation_4` CHECK (`mode` IN ('normal', 'preview')),
  CONSTRAINT `ck_agent_conversation_5` CHECK (`status` IN ('active', 'archived', 'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人私有会话，固定使用的智能体版本与连续事件序号';

-- 会话消息及已保存的结构化内容块
CREATE TABLE `agent_message`
(
  `id`              VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`   VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `conversation_id` VARCHAR(100) NOT NULL COMMENT '所属会话',
  `run_id`          VARCHAR(100) NULL COMMENT '对应执行；插入事务内可为空',
  `attempt_no`      INT          NOT NULL DEFAULT 0 COMMENT '所属尝试，用户输入为 0',
  `role`            VARCHAR(20)  NOT NULL COMMENT '消息角色',
  `content`         LONGTEXT     NOT NULL COMMENT '可公开的正文',
  `status`          VARCHAR(32)  NOT NULL COMMENT '消息状态',
  `blocks_json`     JSON         NOT NULL COMMENT '有稳定编号、父节点、顺序和版本的内容块',
  `context_json`    JSON         NOT NULL COMMENT '已验证的技能、知识和链接引用',
  `last_sequence`   BIGINT       NOT NULL DEFAULT 0 COMMENT '该消息最后保存的新协议序号',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_message_1` (`enterprise_id`, `conversation_id`, `id`),
  UNIQUE KEY `uk_agent_message_2` (`enterprise_id`, `id`),
  KEY               `idx_agent_message_1` (`enterprise_id`, `conversation_id`, `created_at`, `id`),
  KEY               `idx_agent_message_2` (`enterprise_id`, `run_id`, `attempt_no`),
  KEY               `idx_agent_message_3` (`enterprise_id`, `conversation_id`, `run_id`),
  CONSTRAINT `ck_agent_message_1` CHECK (`role` IN ('user', 'assistant', 'system')),
  CONSTRAINT `ck_agent_message_2` CHECK (`status` IN ('pending', 'streaming', 'completed', 'failed', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息及已保存的结构化内容块';

-- 一次顶层执行，拥有固定配置、排队状态和次数归属
CREATE TABLE `agent_run`
(
  `id`                        VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`             VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `conversation_id`           VARCHAR(100) NOT NULL COMMENT '所属会话',
  `user_id`                   VARCHAR(100) NOT NULL COMMENT '实际发起成员',
  `input_message_id`          VARCHAR(100) NOT NULL COMMENT '固定输入消息',
  `output_message_id`         VARCHAR(100) NOT NULL COMMENT '当前尝试的输出消息',
  `agent_version_id`          VARCHAR(100) NULL COMMENT '正式发布版本；草稿预览可为空',
  `mode`                      VARCHAR(20)  NOT NULL DEFAULT 'interactive' COMMENT '执行来源',
  `status`                    VARCHAR(32)  NOT NULL DEFAULT 'queued' COMMENT '执行状态',
  `execution_config_json`     JSON         NOT NULL COMMENT '固定配置、模型配置、依赖和知识处理版本清单；不得含明文凭据',
  `current_attempt_no`        INT          NOT NULL DEFAULT 1 COMMENT '当前尝试编号',
  `max_attempts`              INT          NOT NULL DEFAULT 1 COMMENT '含初次的最多尝试数',
  `lease_version`             BIGINT       NOT NULL DEFAULT 0 COMMENT '当前工作进程的递增租约版本',
  `used_steps`                INT          NOT NULL DEFAULT 0 COMMENT '父子任务共同使用的步骤数，等待后继续不清零',
  `counted_tools_json`        JSON         NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '已经计入步骤预算的框架调用编号',
  `active_millis`             BIGINT       NOT NULL DEFAULT 0 COMMENT '已完成活动时段的毫秒数，不包含等待确认',
  `active_segment_started_at` DATETIME(3) NULL COMMENT '当前活动时段开始时间',
  `execution_phase`           VARCHAR(32)  NOT NULL DEFAULT 'between_steps' COMMENT '程序中断时正在做什么',
  `queued_at`                 DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本次进入队列的时间，确认后继续时更新',
  `has_step_errors`           BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否存在明确允许继续的步骤错误',
  `last_sequence`             BIGINT       NOT NULL DEFAULT 0 COMMENT '本执行最新会话序号',
  `started_at`                DATETIME(3) NULL COMMENT '首次实际开始时间',
  `finished_at`               DATETIME(3) NULL COMMENT '终态时间',
  `cancel_requested_at`       DATETIME(3) NULL COMMENT '停止请求时间',
  `next_attempt_at`           DATETIME(3) NULL COMMENT '自动重试时间',
  `error_code`                VARCHAR(64) NULL COMMENT '稳定错误代码',
  `error_message`             TEXT NULL COMMENT '脱敏用户错误说明',
  `created_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `active_conversation_key`   VARCHAR(100) GENERATED ALWAYS AS (CASE
                                                                  WHEN status IN ('queued', 'running', 'waiting_approval', 'cancelling')
                                                                    THEN conversation_id
                                                                  ELSE NULL END) STORED COMMENT '数据库计算的活动对象编号，终态为空，禁止应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_1` (`enterprise_id`, `conversation_id`, `id`),
  UNIQUE KEY `uk_agent_run_2` (`enterprise_id`, `id`),
  UNIQUE KEY `uk_agent_run_3` (`enterprise_id`, `active_conversation_key`),
  KEY                         `idx_agent_run_1` (`enterprise_id`, `status`, `created_at`, `id`),
  KEY                         `idx_agent_run_2` (`enterprise_id`, `user_id`, `status`),
  KEY                         `idx_agent_run_3` (`status`, `updated_at`),
  KEY                         `idx_agent_run_4` (`enterprise_id`, `user_id`, `conversation_id`),
  KEY                         `idx_agent_run_5` (`enterprise_id`, `conversation_id`, `input_message_id`),
  KEY                         `idx_agent_run_6` (`enterprise_id`, `conversation_id`, `output_message_id`),
  KEY                         `idx_agent_run_7` (`enterprise_id`, `agent_version_id`),
  CONSTRAINT `ck_agent_run_1` CHECK (max_attempts BETWEEN 1 AND 3),
  CONSTRAINT `ck_agent_run_2` CHECK (current_attempt_no BETWEEN 1 AND max_attempts),
  CONSTRAINT `ck_agent_run_3` CHECK (`mode` IN ('interactive', 'preview', 'scheduled', 'manual_schedule')),
  CONSTRAINT `ck_agent_run_4` CHECK (`status` IN
                                     ('queued', 'running', 'waiting_approval', 'cancelling', 'completed', 'failed',
                                      'cancelled')),
  CONSTRAINT `ck_agent_run_5` CHECK (`execution_phase` IN
                                     ('between_steps', 'model', 'tools', 'subagent', 'waiting_approval'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次顶层执行，拥有固定配置、排队状态和次数归属';

-- 规范化事件、合并批次与分发状态
CREATE TABLE `agent_event`
(
  `event_id`              VARCHAR(100) NOT NULL COMMENT '全局数据库事件主键，不作为新协议连续序号',
  `enterprise_id`         VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `stream_id`             VARCHAR(64) NULL COMMENT 'Redis 分发记录编号',
  `conversation_id`       VARCHAR(100) NOT NULL COMMENT '所属会话',
  `run_id`                VARCHAR(100) NOT NULL COMMENT '所属执行',
  `sequence_no`           BIGINT       NOT NULL COMMENT '本行最后一个事件在本次执行内的顺序',
  `conversation_sequence` BIGINT       NOT NULL COMMENT '本行最后一个事件在会话内的连续序号',
  `protocol_version`      INT          NOT NULL DEFAULT 1 COMMENT '当前事件结构版本',
  `storage_version`       TINYINT      NOT NULL DEFAULT 1 COMMENT '数据库编码版本：1 为独立事件，2 为可还原的合并增量',
  `event_type`            VARCHAR(128) NOT NULL COMMENT '业务事件名称',
  `payload_json`          JSON         NOT NULL COMMENT '已脱敏、具有类型的事件正文',
  `payload_hash`          CHAR(64) NULL COMMENT '数据库记录内容摘要；合并增量还原后另算分发摘要',
  `published_at`          DATETIME(3) NULL COMMENT '已确认分发到近期事件存储的时间',
  `started_at`            DATETIME(3) NULL COMMENT '合并内容开始时间',
  `finished_at`           DATETIME(3) NULL COMMENT '合并内容结束时间',
  `created_at`            DATETIME(3) NOT NULL COMMENT '数据库提交批次时间',
  PRIMARY KEY (`event_id`),
  UNIQUE KEY `uk_agent_event_1` (`enterprise_id`, `conversation_id`, `conversation_sequence`),
  UNIQUE KEY `uk_agent_event_2` (`run_id`, `sequence_no`),
  KEY                     `idx_agent_event_1` (`enterprise_id`, `conversation_id`, `created_at`),
  KEY                     `idx_agent_event_2` (`published_at`, `created_at`),
  KEY                     `idx_agent_event_3` (`stream_id`),
  KEY                     `idx_agent_event_4` (`enterprise_id`, `conversation_id`, `run_id`),
  KEY                     `idx_agent_event_storage` (`storage_version`, `event_type`, `created_at`),
  CONSTRAINT `ck_agent_event_1` CHECK (protocol_version = 1),
  CONSTRAINT `ck_agent_event_2` CHECK (conversation_sequence > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规范化事件、合并批次与分发状态';

-- 执行的每次尝试，保留失败输出但只计一次顶层用量
CREATE TABLE `run_attempt`
(
  `id`                VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`     VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`            VARCHAR(100) NOT NULL COMMENT '顶层执行',
  `attempt_no`        INT          NOT NULL COMMENT '尝试编号，从 1 开始',
  `output_message_id` VARCHAR(100) NOT NULL COMMENT '本次独立助手消息',
  `status`            VARCHAR(32)  NOT NULL COMMENT '尝试状态',
  `started_at`        DATETIME(3) NULL COMMENT '开始时间',
  `finished_at`       DATETIME(3) NULL COMMENT '结束时间',
  `error_code`        VARCHAR(64) NULL COMMENT '失败代码',
  `error_summary`     VARCHAR(500) NULL COMMENT '脱敏失败原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_attempt_1` (`enterprise_id`, `run_id`, `attempt_no`),
  UNIQUE KEY `uk_run_attempt_2` (`enterprise_id`, `run_id`, `id`),
  UNIQUE KEY `uk_run_attempt_3` (`enterprise_id`, `id`),
  KEY                 `idx_run_attempt_1` (`enterprise_id`, `output_message_id`),
  CONSTRAINT `ck_run_attempt_1` CHECK (`status` IN
                                       ('queued', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='执行的每次尝试，保留失败输出但只计一次顶层用量';

-- 流程节点、模型、工具及子智能体的稳定层级和状态
CREATE TABLE `run_step`
(
  `id`             VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`         VARCHAR(100) NOT NULL COMMENT '顶层执行',
  `attempt_id`     VARCHAR(100) NOT NULL COMMENT '所属尝试',
  `parent_step_id` VARCHAR(100) NULL COMMENT '同一尝试中的父步骤',
  `step_key`       VARCHAR(128) NOT NULL COMMENT '尝试内稳定步骤标识',
  `kind`           VARCHAR(32)  NOT NULL COMMENT '步骤类型',
  `title`          VARCHAR(200) NOT NULL COMMENT '公开步骤标题',
  `display_order`  BIGINT       NOT NULL COMMENT '首次创建时保存的顺序',
  `status`         VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '步骤状态',
  `input_json`     JSON         NOT NULL COMMENT '内部已验证输入，访问须鉴权',
  `output_json`    JSON NULL COMMENT '已保存结果',
  `public_summary` VARCHAR(500) NULL COMMENT '可展示摘要',
  `workflow_json`  JSON NULL COMMENT '真实工作流调用、固定资源与节点；其他步骤为空',
  `lease_version`  BIGINT       NOT NULL COMMENT '防止失去租约的进程写入',
  `started_at`     DATETIME(3) NULL COMMENT '开始时间',
  `finished_at`    DATETIME(3) NULL COMMENT '结束时间',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_step_1` (`enterprise_id`, `attempt_id`, `step_key`),
  UNIQUE KEY `uk_run_step_2` (`enterprise_id`, `attempt_id`, `id`),
  UNIQUE KEY `uk_run_step_3` (`enterprise_id`, `run_id`, `id`),
  UNIQUE KEY `uk_run_step_4` (`enterprise_id`, `id`),
  KEY              `idx_run_step_1` (`enterprise_id`, `run_id`, `display_order`, `id`),
  KEY              `idx_run_step_2` (`enterprise_id`, `run_id`, `attempt_id`),
  KEY              `idx_run_step_3` (`enterprise_id`, `attempt_id`, `parent_step_id`),
  CONSTRAINT `ck_run_step_1` CHECK (`status` IN
                                    ('pending', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled',
                                     'skipped'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程节点、模型、工具及子智能体的稳定层级和状态';

-- 工具真实调用、固定操作编号及结果未知状态
CREATE TABLE `tool_call`
(
  `id`                     VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`                 VARCHAR(100) NULL COMMENT '关联执行；管理员手动查询时可为空',
  `attempt_id`             VARCHAR(100) NULL COMMENT '所属执行尝试；管理员手动查询时为空',
  `step_id`                VARCHAR(100) NULL COMMENT '关联步骤',
  `actor_user_id`          VARCHAR(100) NOT NULL COMMENT '实际操作成员',
  `resource_id`            VARCHAR(100) NOT NULL COMMENT '实际使用的能力资源',
  `resource_kind`          VARCHAR(20)  NOT NULL COMMENT '工具所属资源类型',
  `resource_version_id`    VARCHAR(100) NULL COMMENT '固定发布版本；管理查询使用当前草稿时为空',
  `draft_revision`         BIGINT NULL COMMENT '手动查询使用的草稿修改版本',
  `tool_name`              VARCHAR(128) NOT NULL COMMENT '真实工具名称',
  `operation_id`           VARCHAR(100) NOT NULL COMMENT '固定外部请求去重编号',
  `plugin_tool_id`         VARCHAR(100) NULL COMMENT '固定插件工具，数据查询时为空',
  `framework_call_id`      VARCHAR(128) COLLATE utf8mb4_0900_bin NULL COMMENT 'AgentScope 提供的原始工具调用编号',
  `framework_session_id`   VARCHAR(255) COLLATE utf8mb4_0900_bin NULL COMMENT 'AgentScope 提供的父会话或子会话编号',
  `argument_hash`          CHAR(64)     NOT NULL COMMENT '规范化原始参数摘要，用于阻止重复拒绝后再次提问',
  `request_encrypted_json` JSON         NOT NULL COMMENT '绑定企业与调用编号的加密原始参数',
  `result_encrypted_json`  JSON NULL COMMENT '已完成调用交还框架的加密结果',
  `lease_version`          BIGINT       NOT NULL DEFAULT 0 COMMENT '当前操作进程的领取版本',
  `submitted_at`           DATETIME(3) NULL COMMENT '即将发送业务请求时保存；此后中断不能假定未执行',
  `operation_class`        VARCHAR(20)  NOT NULL COMMENT '操作类别',
  `status`                 VARCHAR(32)  NOT NULL DEFAULT 'prepared' COMMENT '调用结果',
  `request_hash`           CHAR(64)     NOT NULL COMMENT '固定参数摘要',
  `request_redacted_json`  JSON         NOT NULL COMMENT '脱敏请求信息',
  `result_redacted_json`   JSON NULL COMMENT '脱敏结果',
  `attempt_count`          INT          NOT NULL DEFAULT 0 COMMENT '同一逻辑操作的请求次数',
  `query_count`            INT          NOT NULL DEFAULT 0 COMMENT '按照原操作编号查询结果的请求次数',
  `last_query_at`          DATETIME(3) NULL COMMENT '最近一次准备发送结果查询的时间',
  `error_code`             VARCHAR(64) NULL COMMENT '稳定错误代码',
  `error_summary`          VARCHAR(500) NULL COMMENT '脱敏错误',
  `started_at`             DATETIME(3) NULL COMMENT '首次请求时间',
  `finished_at`            DATETIME(3) NULL COMMENT '已知终态时间',
  `duration_ms`            BIGINT NULL COMMENT '实际耗时，未知为空',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_call_1` (`enterprise_id`, `operation_id`),
  UNIQUE KEY `uk_tool_call_2` (`enterprise_id`, `attempt_id`, `framework_session_id`, `framework_call_id`),
  UNIQUE KEY `uk_tool_call_3` (`enterprise_id`, `id`),
  KEY                      `idx_tool_call_1` (`enterprise_id`, `created_at`, `id`),
  KEY                      `idx_tool_call_2` (`enterprise_id`, `run_id`, `created_at`, `id`),
  KEY                      `idx_tool_call_3` (`status`, `created_at`),
  KEY                      `idx_tool_call_4` (`enterprise_id`, `actor_user_id`),
  KEY                      `idx_tool_call_5` (`enterprise_id`, `resource_id`, `resource_version_id`),
  KEY                      `idx_tool_call_6` (`enterprise_id`, `attempt_id`, `step_id`),
  KEY                      `idx_tool_call_7` (`enterprise_id`, `plugin_tool_id`),
  KEY                      `idx_tool_call_8` (`enterprise_id`, `run_id`, `attempt_id`),
  CONSTRAINT `ck_tool_call_1` CHECK ((run_id IS NULL AND step_id IS NULL AND attempt_id IS NULL) OR
                                     (run_id IS NOT NULL AND step_id IS NOT NULL AND attempt_id IS NOT NULL)),
  CONSTRAINT `ck_tool_call_2` CHECK ((resource_version_id IS NOT NULL AND draft_revision IS NULL) OR
                                     (resource_kind = 'data' AND run_id IS NULL AND resource_version_id IS NULL AND
                                      draft_revision IS NOT NULL AND draft_revision > 0)),
  CONSTRAINT `ck_tool_call_3` CHECK ((resource_kind = 'plugin' AND plugin_tool_id IS NOT NULL) OR
                                     (resource_kind IN ('knowledge', 'data') AND plugin_tool_id IS NULL)),
  CONSTRAINT `ck_tool_call_4` CHECK (`resource_kind` IN ('plugin', 'knowledge', 'data')),
  CONSTRAINT `ck_tool_call_5` CHECK (`operation_class` IN ('read', 'write', 'destructive', 'unknown')),
  CONSTRAINT `ck_tool_call_6` CHECK (`status` IN
                                     ('prepared', 'waiting_approval', 'running', 'succeeded', 'failed', 'cancelled',
                                      'unknown'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具真实调用、固定操作编号及结果未知状态';

-- 一次确定操作的用户决定，不产生通用允许规则
CREATE TABLE `run_approval`
(
  `id`               VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`    VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`           VARCHAR(100) NOT NULL COMMENT '等待决定的执行',
  `step_id`          VARCHAR(100) NOT NULL COMMENT '相关步骤',
  `tool_call_id`     VARCHAR(100) NULL COMMENT '外部工具调用；纯流程确认时为空',
  `approver_user_id` VARCHAR(100) NOT NULL COMMENT '唯一允许处理的原发起成员',
  `request_hash`     CHAR(64)     NOT NULL COMMENT '固定操作和参数摘要',
  `summary_json`     JSON         NOT NULL COMMENT '可展示的目标、影响和内容摘要',
  `status`           VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '确认状态',
  `expires_at`       DATETIME(3) NOT NULL COMMENT '失效时间',
  `decided_at`       DATETIME(3) NULL COMMENT '决定时间',
  `decision_note`    VARCHAR(500) NULL COMMENT '拒绝或撤销说明',
  `revision`         BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_approval_1` (`enterprise_id`, `run_id`, `step_id`),
  UNIQUE KEY `uk_run_approval_2` (`enterprise_id`, `tool_call_id`),
  UNIQUE KEY `uk_run_approval_3` (`enterprise_id`, `id`),
  KEY                `idx_run_approval_1` (`enterprise_id`, `approver_user_id`, `status`, `created_at`, `id`),
  KEY                `idx_run_approval_2` (`status`, `expires_at`),
  CONSTRAINT `ck_run_approval_1` CHECK (`status` IN ('pending', 'approved', 'rejected', 'expired', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次确定操作的用户决定，不产生通用允许规则';

-- 可恢复执行的最新完整检查点
CREATE TABLE `run_checkpoint`
(
  `enterprise_id`       VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`              VARCHAR(100) NOT NULL COMMENT '执行编号',
  `checkpoint_no`       BIGINT       NOT NULL COMMENT '检查点版本',
  `lease_version`       BIGINT       NOT NULL COMMENT '写入进程租约版本',
  `state_json`          JSON         NOT NULL COMMENT '步骤、审批、固定能力清单和已完成操作编号',
  `framework_state_key` VARCHAR(500) NULL COMMENT '加密框架状态的隔离存储路径',
  `state_hash`          CHAR(64)     NOT NULL COMMENT '检查点摘要',
  `updated_at`          DATETIME(3) NOT NULL COMMENT '保存时间',
  PRIMARY KEY (`enterprise_id`, `run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可恢复执行的最新完整检查点';

-- 消息与已验证文件关联
CREATE TABLE `message_attachment`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `message_id`    VARCHAR(100) NOT NULL COMMENT '所属消息',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '附件文件',
  `ordinal`       INT          NOT NULL COMMENT '用户选择的顺序',
  `created_at`    DATETIME(3) NOT NULL COMMENT '关联时间',
  PRIMARY KEY (`enterprise_id`, `message_id`, `file_id`),
  KEY             `idx_message_attachment_1` (`enterprise_id`, `file_id`, `message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息与已验证文件关联';

-- 本人对一条消息的当前反馈，不构成评测评分
CREATE TABLE `message_feedback`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `message_id`    VARCHAR(100) NOT NULL COMMENT '消息',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '反馈成员',
  `value`         VARCHAR(20)  NOT NULL COMMENT '赞同或不同意',
  `comment`       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '可选说明',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `message_id`, `user_id`),
  KEY             `idx_message_feedback_1` (`enterprise_id`, `user_id`),
  CONSTRAINT `ck_message_feedback_1` CHECK (`value` IN ('positive', 'negative'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人对一条消息的当前反馈，不构成评测评分';

-- 固定版本与本地时间规则的个人定时任务
CREATE TABLE `scheduled_task`
(
  `id`                   VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `owner_user_id`        VARCHAR(100) NOT NULL COMMENT '创建人和通知接收人',
  `hire_id`              VARCHAR(100) NOT NULL COMMENT '本人雇佣关系',
  `agent_version_id`     VARCHAR(100) NOT NULL COMMENT '固定智能体版本',
  `name`                 VARCHAR(80)  NOT NULL COMMENT '计划名称',
  `input_text`           LONGTEXT     NOT NULL COMMENT '固定输入，最多 20000 字符，兼容每字符四字节',
  `frequency`            VARCHAR(20)  NOT NULL COMMENT '重复方式',
  `local_date`           DATE NULL COMMENT '一次性计划的本地日期',
  `local_time`           TIME         NOT NULL COMMENT '本地执行时间，精确到分钟',
  `weekdays_json`        JSON         NOT NULL COMMENT '每周使用 1 至 7 的整数数组，其他规则为空数组',
  `month_day`            INT NULL COMMENT '每月第几日',
  `timezone`             VARCHAR(64)  NOT NULL COMMENT '计划时区',
  `enabled`              BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否允许未来触发',
  `max_retries`          INT          NOT NULL DEFAULT 0 COMMENT '自动重试次数',
  `next_run_at`          DATETIME(3) NULL COMMENT '下一次实际 UTC 时间',
  `last_checked_at`      DATETIME(3) NOT NULL COMMENT '最近确认时间规则的时刻；创建、修改和调度检查都会更新',
  `pause_reason`         VARCHAR(64) NULL COMMENT '自动暂停的业务原因',
  `active_occurrence_id` VARCHAR(100) NULL COMMENT '唯一未结束发生记录',
  `deleted_at`           DATETIME(3) NULL COMMENT '标记删除时间；为空表示未删除',
  `deleted_token`        VARCHAR(100) NOT NULL DEFAULT '' COMMENT '未删除时为空字符串，删除后为本行编号，允许名称再次使用',
  `revision`             BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_task_1` (`enterprise_id`, `id`),
  KEY                    `idx_scheduled_task_1` (`enabled`, `next_run_at`, `id`),
  KEY                    `idx_scheduled_task_2` (`enterprise_id`, `owner_user_id`, `updated_at`, `id`),
  KEY                    `idx_scheduled_task_3` (`enabled`, `last_checked_at`, `id`),
  KEY                    `idx_scheduled_task_4` (`enterprise_id`, `owner_user_id`, `hire_id`),
  KEY                    `idx_scheduled_task_5` (`enterprise_id`, `agent_version_id`),
  KEY                    `idx_scheduled_task_6` (`enterprise_id`, `id`, `active_occurrence_id`),
  CONSTRAINT `ck_scheduled_task_1` CHECK (max_retries BETWEEN 0 AND 2),
  CONSTRAINT `ck_scheduled_task_2` CHECK (month_day IS NULL OR month_day BETWEEN 1 AND 31),
  CONSTRAINT `ck_scheduled_task_3` CHECK (enabled = FALSE OR next_run_at IS NOT NULL),
  CONSTRAINT `ck_scheduled_task_4` CHECK (SECOND (local_time) = 0 AND local_time >= '00:00:00' AND local_time <= '23:59:00'
) ,
    CONSTRAINT `ck_scheduled_task_5` CHECK (JSON_TYPE(weekdays_json) = 'ARRAY' AND JSON_LENGTH(weekdays_json) <= 7),
    CONSTRAINT `ck_scheduled_task_6` CHECK ((frequency = 'once' AND local_date IS NOT NULL AND month_day IS NULL AND JSON_LENGTH(weekdays_json) = 0) OR (frequency = 'daily' AND local_date IS NULL AND month_day IS NULL AND JSON_LENGTH(weekdays_json) = 0) OR (frequency = 'weekly' AND local_date IS NULL AND month_day IS NULL AND JSON_LENGTH(weekdays_json) BETWEEN 1 AND 7) OR (frequency = 'monthly' AND local_date IS NULL AND month_day IS NOT NULL AND JSON_LENGTH(weekdays_json) = 0)),
    CONSTRAINT `ck_scheduled_task_7` CHECK ((deleted_at IS NULL AND deleted_token = '') OR (deleted_at IS NOT NULL AND deleted_token = id)),
    CONSTRAINT `ck_scheduled_task_8` CHECK (`frequency` IN ('once', 'daily', 'weekly', 'monthly'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='固定版本与本地时间规则的个人定时任务';

-- 每次到期或手动触发的唯一记录
CREATE TABLE `scheduled_occurrence`
(
  `id`                  VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`       VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `schedule_id`         VARCHAR(100) NOT NULL COMMENT '定时任务',
  `trigger_kind`        VARCHAR(20)  NOT NULL COMMENT '自动或手动',
  `occurrence_key`      VARCHAR(128) NOT NULL COMMENT '自动为预定时刻，手动为请求编号',
  `scheduled_for`       DATETIME(3) NOT NULL COMMENT '该次计划时间',
  `run_id`              VARCHAR(100) NULL COMMENT '实际执行；错过或被阻止时为空',
  `conversation_id`     VARCHAR(100) NULL COMMENT '本次独立会话',
  `status`              VARCHAR(32)  NOT NULL COMMENT '发生状态',
  `reason_code`         VARCHAR(64) NULL COMMENT '错过、跳过或阻止原因',
  `started_at`          DATETIME(3) NULL COMMENT '开始时间',
  `finished_at`         DATETIME(3) NULL COMMENT '结束时间',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `active_schedule_key` VARCHAR(100) GENERATED ALWAYS AS (CASE
                                                            WHEN status IN ('queued', 'running', 'waiting_approval')
                                                              THEN schedule_id
                                                            ELSE NULL END) STORED COMMENT '数据库计算的活动对象编号，终态为空，禁止应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheduled_occurrence_1` (`enterprise_id`, `schedule_id`, `occurrence_key`),
  UNIQUE KEY `uk_scheduled_occurrence_2` (`enterprise_id`, `schedule_id`, `id`),
  UNIQUE KEY `uk_scheduled_occurrence_3` (`enterprise_id`, `run_id`),
  UNIQUE KEY `uk_scheduled_occurrence_4` (`enterprise_id`, `id`),
  UNIQUE KEY `uk_scheduled_occurrence_5` (`enterprise_id`, `active_schedule_key`),
  KEY                   `idx_scheduled_occurrence_1` (`enterprise_id`, `schedule_id`, `scheduled_for`, `id`),
  KEY                   `idx_scheduled_occurrence_2` (`enterprise_id`, `conversation_id`, `run_id`),
  CONSTRAINT `ck_scheduled_occurrence_1` CHECK ((run_id IS NULL AND conversation_id IS NULL) OR
                                                (run_id IS NOT NULL AND conversation_id IS NOT NULL)),
  CONSTRAINT `ck_scheduled_occurrence_2` CHECK (`trigger_kind` IN ('scheduled', 'manual')),
  CONSTRAINT `ck_scheduled_occurrence_3` CHECK (`status` IN
                                                ('queued', 'running', 'waiting_approval', 'completed', 'failed',
                                                 'cancelled', 'skipped', 'missed', 'blocked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每次到期或手动触发的唯一记录';

-- 用户确认创建的待办、负责人和来源
CREATE TABLE `todo_item`
(
  `id`                     VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `title`                  VARCHAR(200) NOT NULL COMMENT '待办标题',
  `description`            TEXT         NOT NULL COMMENT '用户确认的说明',
  `created_by`             VARCHAR(100) NOT NULL COMMENT '创建人',
  `owner_user_id`          VARCHAR(100) NOT NULL COMMENT '当前负责人',
  `team_id`                VARCHAR(100) NULL COMMENT '团队待办范围；个人待办为空',
  `due_date`               DATE NULL COMMENT '企业时区下的截止日期，可不填',
  `priority`               VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT '普通或重要',
  `status`                 VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '待办状态',
  `source_type`            VARCHAR(20)  NOT NULL DEFAULT 'manual' COMMENT '来源类型',
  `source_conversation_id` VARCHAR(100) NULL COMMENT '来源会话逻辑引用，不授予访问权，清理时置空',
  `source_message_id`      VARCHAR(100) NULL COMMENT '来源消息逻辑引用',
  `source_run_id`          VARCHAR(100) NULL COMMENT '来源执行逻辑引用',
  `completed_at`           DATETIME(3) NULL COMMENT '本次完成时间，重开时清空',
  `deleted_at`             DATETIME(3) NULL COMMENT '删除时间',
  `revision`               BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_todo_item_1` (`enterprise_id`, `id`),
  KEY                      `idx_todo_item_1` (`enterprise_id`, `owner_user_id`, `status`, `due_date`, `id`),
  KEY                      `idx_todo_item_2` (`enterprise_id`, `team_id`, `status`, `updated_at`, `id`),
  KEY                      `idx_todo_item_3` (`enterprise_id`, `created_by`, `updated_at`, `id`),
  KEY                      `idx_todo_item_4` (`enterprise_id`, `owner_user_id`, `updated_at`, `id`),
  KEY                      `idx_todo_item_5` (`enterprise_id`, `source_conversation_id`),
  CONSTRAINT `ck_todo_item_1` CHECK ((status = 'completed' AND completed_at IS NOT NULL) OR
                                     (status <> 'completed' AND completed_at IS NULL)),
  CONSTRAINT `ck_todo_item_2` CHECK (source_type <> 'manual' OR
                                     (source_conversation_id IS NULL AND source_message_id IS NULL AND
                                      source_run_id IS NULL)),
  CONSTRAINT `ck_todo_item_3` CHECK (source_conversation_id IS NOT NULL OR
                                     (source_message_id IS NULL AND source_run_id IS NULL)),
  CONSTRAINT `ck_todo_item_4` CHECK (`priority` IN ('normal', 'high')),
  CONSTRAINT `ck_todo_item_5` CHECK (`status` IN ('pending', 'in_progress', 'completed', 'cancelled')),
  CONSTRAINT `ck_todo_item_6` CHECK (`source_type` IN ('manual', 'message', 'workflow'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户确认创建的待办、负责人和来源';

-- 待办修改和状态转换的不可变历史
CREATE TABLE `todo_history`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `todo_id`       VARCHAR(100) NOT NULL COMMENT '待办',
  `actor_user_id` VARCHAR(100) NOT NULL COMMENT '实际操作者',
  `action`        VARCHAR(64)  NOT NULL COMMENT '创建、转交、完成、重开、取消等动作',
  `before_json`   JSON NULL COMMENT '变更前的必要摘要',
  `after_json`    JSON         NOT NULL COMMENT '变更后的必要摘要',
  `created_at`    DATETIME(3) NOT NULL COMMENT '动作时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_todo_history_1` (`enterprise_id`, `id`),
  KEY             `idx_todo_history_1` (`enterprise_id`, `todo_id`, `created_at`, `id`),
  KEY             `idx_todo_history_2` (`enterprise_id`, `actor_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='待办修改和状态转换的不可变历史';

-- 企业、团队或角色的月度执行次数上限
CREATE TABLE `quota_policy`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `subject_type`  VARCHAR(20)  NOT NULL COMMENT '约束主体',
  `subject_id`    VARCHAR(100) NOT NULL COMMENT '主体编号，必须在业务事务中验证企业归属',
  `monthly_limit` BIGINT NULL COMMENT '0 禁止新执行，空值表示该层不限',
  `enabled`       BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否应用此规则',
  `revision`      BIGINT       NOT NULL DEFAULT 1 COMMENT '并发修改版本，每次成功修改加一',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quota_policy_1` (`enterprise_id`, `subject_type`, `subject_id`),
  UNIQUE KEY `uk_quota_policy_2` (`enterprise_id`, `id`),
  CONSTRAINT `ck_quota_policy_1` CHECK (monthly_limit IS NULL OR monthly_limit BETWEEN 0 AND 1000000000),
  CONSTRAINT `ck_quota_policy_2` CHECK (`subject_type` IN ('enterprise', 'team', 'role'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业、团队或角色的月度执行次数上限';

-- 某规则某个自然月的已用及预留次数
CREATE TABLE `quota_bucket`
(
  `id`             VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `policy_id`      VARCHAR(100) NOT NULL COMMENT '用量规则',
  `period_start`   DATETIME(3) NOT NULL COMMENT '周期起点，UTC',
  `period_end`     DATETIME(3) NOT NULL COMMENT '周期结束，不包含边界时刻',
  `timezone`       VARCHAR(64)  NOT NULL COMMENT '计算本周期时使用的时区',
  `used_count`     BIGINT       NOT NULL DEFAULT 0 COMMENT '已经实际开始的次数',
  `reserved_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '已接受但尚未实际开始的次数',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quota_bucket_1` (`enterprise_id`, `policy_id`, `period_start`),
  UNIQUE KEY `uk_quota_bucket_2` (`enterprise_id`, `id`),
  KEY              `idx_quota_bucket_1` (`enterprise_id`, `period_start`, `id`),
  CONSTRAINT `ck_quota_bucket_1` CHECK (used_count >= 0),
  CONSTRAINT `ck_quota_bucket_2` CHECK (reserved_count >= 0),
  CONSTRAINT `ck_quota_bucket_3` CHECK (period_end > period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='某规则某个自然月的已用及预留次数';

-- 一个顶层执行对一个计数桶最多计一次
CREATE TABLE `quota_entry`
(
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `run_id`        VARCHAR(100) NOT NULL COMMENT '顶层执行逻辑引用，历史清理不删除计数',
  `bucket_id`     VARCHAR(100) NOT NULL COMMENT '所影响计数桶',
  `state`         VARCHAR(20)  NOT NULL DEFAULT 'reserved' COMMENT '预留、已使用或已释放',
  `quantity`      INT          NOT NULL DEFAULT 1 COMMENT '固定为一次',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`enterprise_id`, `run_id`, `bucket_id`),
  KEY             `idx_quota_entry_1` (`enterprise_id`, `bucket_id`, `state`),
  CONSTRAINT `ck_quota_entry_1` CHECK (quantity = 1),
  CONSTRAINT `ck_quota_entry_2` CHECK (`state` IN ('reserved', 'consumed', 'released'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一个顶层执行对一个计数桶最多计一次';

-- 本人站内通知及读取边界
CREATE TABLE `notification`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '接收成员',
  `sequence_no`   BIGINT       NOT NULL COMMENT '本企业本成员通知序号；同一事务锁成员行并分配，用于全部已读边界',
  `event_key`     VARCHAR(128) NOT NULL COMMENT '相同业务事件对同一用户只创建一次',
  `category`      VARCHAR(32)  NOT NULL COMMENT '通知类别',
  `title`         VARCHAR(100) NOT NULL COMMENT '中文通知标题',
  `body`          VARCHAR(500) NOT NULL COMMENT '不含凭据或越权正文的说明',
  `target_type`   VARCHAR(32) NULL COMMENT '允许的目标类型',
  `target_id`     VARCHAR(100) NULL COMMENT '目标逻辑引用，点击时重新鉴权',
  `read_at`       DATETIME(3) NULL COMMENT '本人标记已读时间',
  `created_at`    DATETIME(3) NOT NULL COMMENT '实际创建时间，不回填为旧事件时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_1` (`enterprise_id`, `user_id`, `sequence_no`),
  UNIQUE KEY `uk_notification_2` (`enterprise_id`, `user_id`, `event_key`),
  UNIQUE KEY `uk_notification_3` (`enterprise_id`, `id`),
  KEY             `idx_notification_1` (`enterprise_id`, `user_id`, `read_at`, `sequence_no`),
  CONSTRAINT `ck_notification_1` CHECK (sequence_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本人站内通知及读取边界';

-- 只追加的管理与重要操作记录，清理业务对象不删除记录
CREATE TABLE `audit_event`
(
  `id`                   VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`        VARCHAR(100) NULL COMMENT '所属企业，全局身份动作可为空',
  `actor_user_id`        VARCHAR(100) NOT NULL COMMENT '操作者逻辑编号，允许保留历史',
  `actor_name`           VARCHAR(128) NOT NULL COMMENT '动作发生时显示名',
  `action`               VARCHAR(128) NOT NULL COMMENT '正式操作名称',
  `object_type`          VARCHAR(32)  NOT NULL COMMENT '目标对象类型',
  `object_id`            VARCHAR(100) NULL COMMENT '目标逻辑编号',
  `result`               VARCHAR(20)  NOT NULL COMMENT '成功或失败',
  `summary`              VARCHAR(500) NOT NULL COMMENT '中文摘要',
  `detail_redacted_json` JSON         NOT NULL COMMENT '仅包含必要脱敏差异',
  `request_id`           VARCHAR(100) NOT NULL COMMENT '服务端请求编号',
  `created_at`           DATETIME(3) NOT NULL COMMENT '动作时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_event_1` (`enterprise_id`, `id`),
  KEY                    `idx_audit_event_1` (`enterprise_id`, `created_at`, `id`),
  KEY                    `idx_audit_event_2` (`enterprise_id`, `actor_user_id`, `created_at`, `id`),
  CONSTRAINT `ck_audit_event_1` CHECK (`result` IN ('success', 'failure'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只追加的管理与重要操作记录，清理业务对象不删除记录';

-- 修改请求去重记录，事务结果可重复读取
CREATE TABLE `api_request`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NULL COMMENT '企业动作的企业，全局动作为空',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '已认证发起用户',
  `scope_key`     VARCHAR(128) NOT NULL COMMENT '服务端生成的用户全局或企业范围',
  `operation_key` CHAR(64)     NOT NULL COMMENT '方法与目标路径的摘要',
  `request_key`   VARCHAR(100) NOT NULL COMMENT '客户端同次请求键的大小写敏感摘要',
  `request_hash`  CHAR(64)     NOT NULL COMMENT '规范化正文与修改版本的带密钥摘要',
  `status`        VARCHAR(20)  NOT NULL COMMENT '处理中或已完成',
  `http_status`   INT NULL COMMENT '已完成请求的 HTTP 状态',
  `response_json` JSON NULL COMMENT '可安全重复返回的业务结果',
  `expires_at`    DATETIME(3) NOT NULL COMMENT '最少保留 24 小时',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_request_1` (`user_id`, `scope_key`, `operation_key`, `request_key`),
  UNIQUE KEY `uk_api_request_2` (`enterprise_id`, `id`),
  KEY             `idx_api_request_1` (`expires_at`),
  CONSTRAINT `ck_api_request_1` CHECK (`status` IN ('processing', 'completed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='修改请求去重记录，事务结果可重复读取';

-- 执行、解析、邮件、事件分发和导出的持久任务
CREATE TABLE `background_job`
(
  `id`                      VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`           VARCHAR(100) NULL COMMENT '企业归属，全局邮件可为空',
  `owner_user_id`           VARCHAR(100) NULL COMMENT '业务身份，全局维护可为空',
  `kind`                    VARCHAR(32)  NOT NULL COMMENT '经过注册的任务类型',
  `dedupe_key`              VARCHAR(255) NOT NULL COMMENT '同一逻辑后台工作唯一键',
  `payload_json`            JSON         NOT NULL COMMENT '仅包含必要业务编号和经过验证的配置',
  `status`                  VARCHAR(20)  NOT NULL DEFAULT 'queued' COMMENT '队列状态',
  `available_at`            DATETIME(3) NOT NULL COMMENT '可领取时间',
  `attempt_count`           INT          NOT NULL DEFAULT 0 COMMENT '实际领取次数',
  `max_attempts`            INT          NOT NULL DEFAULT 3 COMMENT '最大领取次数',
  `lease_owner`             VARCHAR(128) NULL COMMENT '工作进程实例编号',
  `lease_version`           BIGINT       NOT NULL DEFAULT 0 COMMENT '每次领取递增',
  `lease_until`             DATETIME(3) NULL COMMENT '租约到期时间',
  `heartbeat_at`            DATETIME(3) NULL COMMENT '最近续期时间',
  `result_file_id`          VARCHAR(100) NULL COMMENT '导出等任务的结果文件',
  `error_code`              VARCHAR(64) NULL COMMENT '错误代码',
  `error_summary`           VARCHAR(500) NULL COMMENT '脱敏错误',
  `created_at`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，使用协调世界时',
  `updated_at`              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后一次业务修改时间',
  `active_export_owner_key` VARCHAR(100) GENERATED ALWAYS AS (CASE
                                                                WHEN kind = 'export' AND status IN ('queued', 'leased')
                                                                  THEN owner_user_id
                                                                ELSE NULL END) STORED COMMENT '数据库计算的活动导出发起人，结束后为空，禁止应用写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_background_job_1` (`dedupe_key`),
  UNIQUE KEY `uk_background_job_2` (`enterprise_id`, `id`),
  UNIQUE KEY `uk_background_job_3` (`active_export_owner_key`),
  KEY                       `idx_background_job_1` (`status`, `available_at`, `id`),
  KEY                       `idx_background_job_2` (`status`, `lease_until`, `id`),
  KEY                       `idx_background_job_3` (`enterprise_id`, `owner_user_id`, `created_at`, `id`),
  KEY                       `idx_background_job_4` (`enterprise_id`, `result_file_id`),
  CONSTRAINT `ck_background_job_1` CHECK (kind <> 'export' OR
                                          (enterprise_id IS NOT NULL AND owner_user_id IS NOT NULL)),
  CONSTRAINT `ck_background_job_2` CHECK (`kind` IN ('run', 'document_parse', 'file_scan', 'mail', 'notification',
                                                     'event_publish', 'export', 'cleanup')),
  CONSTRAINT `ck_background_job_3` CHECK (`status` IN ('queued', 'leased', 'completed', 'failed', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='执行、解析、邮件、事件分发和导出的持久任务';

-- 全部表创建后再添加外键，允许资源发布指针与执行消息存在合法循环引用。
ALTER TABLE `system_super_admin_lock`
  ADD CONSTRAINT `fk_system_super_admin_lock_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise`
  ADD CONSTRAINT `fk_enterprise_1` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_member`
  ADD CONSTRAINT `fk_enterprise_member_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_member`
  ADD CONSTRAINT `fk_enterprise_member_2` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_team`
  ADD CONSTRAINT `fk_enterprise_team_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_team_member`
  ADD CONSTRAINT `fk_enterprise_team_member_1` FOREIGN KEY (`enterprise_id`, `team_id`) REFERENCES `enterprise_team` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_team_member`
  ADD CONSTRAINT `fk_enterprise_team_member_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role`
  ADD CONSTRAINT `fk_sys_role_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_permission`
  ADD CONSTRAINT `fk_sys_role_permission_1` FOREIGN KEY (`enterprise_id`, `role_id`) REFERENCES `sys_role` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_role_permission`
  ADD CONSTRAINT `fk_sys_role_permission_2` FOREIGN KEY (`permission_code`) REFERENCES `sys_permission` (`code`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_user_role`
  ADD CONSTRAINT `fk_sys_user_role_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sys_user_role`
  ADD CONSTRAINT `fk_sys_user_role_2` FOREIGN KEY (`enterprise_id`, `role_id`) REFERENCES `sys_role` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_preference`
  ADD CONSTRAINT `fk_user_preference_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `auth_token`
  ADD CONSTRAINT `fk_auth_token_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_invitation`
  ADD CONSTRAINT `fk_enterprise_invitation_1` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `enterprise_invitation`
  ADD CONSTRAINT `fk_enterprise_invitation_2` FOREIGN KEY (`accepted_user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource`
  ADD CONSTRAINT `fk_resource_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource`
  ADD CONSTRAINT `fk_resource_2` FOREIGN KEY (`enterprise_id`, `id`, `published_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_draft`
  ADD CONSTRAINT `fk_resource_draft_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_draft`
  ADD CONSTRAINT `fk_resource_draft_2` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_version`
  ADD CONSTRAINT `fk_resource_version_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_version`
  ADD CONSTRAINT `fk_resource_version_2` FOREIGN KEY (`enterprise_id`, `published_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_dependency`
  ADD CONSTRAINT `fk_resource_dependency_1` FOREIGN KEY (`enterprise_id`, `parent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_dependency`
  ADD CONSTRAINT `fk_resource_dependency_2` FOREIGN KEY (`enterprise_id`, `dependency_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_grant`
  ADD CONSTRAINT `fk_resource_grant_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_grant`
  ADD CONSTRAINT `fk_resource_grant_2` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tag`
  ADD CONSTRAINT `fk_tag_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_tag`
  ADD CONSTRAINT `fk_resource_tag_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `resource_tag`
  ADD CONSTRAINT `fk_resource_tag_2` FOREIGN KEY (`enterprise_id`, `tag_id`) REFERENCES `tag` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_listing`
  ADD CONSTRAINT `fk_agent_listing_1` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_listing`
  ADD CONSTRAINT `fk_agent_listing_2` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_hire`
  ADD CONSTRAINT `fk_agent_hire_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_hire`
  ADD CONSTRAINT `fk_agent_hire_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_hire_request`
  ADD CONSTRAINT `fk_agent_hire_request_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_hire_request`
  ADD CONSTRAINT `fk_agent_hire_request_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_hire_request`
  ADD CONSTRAINT `fk_agent_hire_request_3` FOREIGN KEY (`enterprise_id`, `decided_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `credential`
  ADD CONSTRAINT `fk_credential_1` FOREIGN KEY (`enterprise_id`, `updated_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `model_provider`
  ADD CONSTRAINT `fk_model_provider_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `model_profile`
  ADD CONSTRAINT `fk_model_profile_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `model_profile`
  ADD CONSTRAINT `fk_model_profile_2` FOREIGN KEY (`enterprise_id`, `provider_id`) REFERENCES `model_provider` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `plugin_tool`
  ADD CONSTRAINT `fk_plugin_tool_1` FOREIGN KEY (`enterprise_id`, `plugin_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_object`
  ADD CONSTRAINT `fk_file_object_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_object`
  ADD CONSTRAINT `fk_file_object_2` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_object`
  ADD CONSTRAINT `fk_file_object_3` FOREIGN KEY (`enterprise_id`, `resource_id`, `resource_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_object`
  ADD CONSTRAINT `fk_file_object_4` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_text`
  ADD CONSTRAINT `fk_file_text_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_data_profile`
  ADD CONSTRAINT `fk_file_data_profile_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `file_data_row`
  ADD CONSTRAINT `fk_file_data_row_1` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_document`
  ADD CONSTRAINT `fk_knowledge_document_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_document`
  ADD CONSTRAINT `fk_knowledge_document_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_document`
  ADD CONSTRAINT `fk_knowledge_document_3` FOREIGN KEY (`enterprise_id`, `active_file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_chunk`
  ADD CONSTRAINT `fk_knowledge_chunk_1` FOREIGN KEY (`enterprise_id`, `document_id`) REFERENCES `knowledge_document` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `knowledge_chunk`
  ADD CONSTRAINT `fk_knowledge_chunk_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_collection`
  ADD CONSTRAINT `fk_data_collection_1` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_collection`
  ADD CONSTRAINT `fk_data_collection_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_field`
  ADD CONSTRAINT `fk_data_field_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_generation`
  ADD CONSTRAINT `fk_data_generation_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_generation`
  ADD CONSTRAINT `fk_data_generation_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `data_record`
  ADD CONSTRAINT `fk_data_record_1` FOREIGN KEY (`enterprise_id`, `collection_id`) REFERENCES `data_collection` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_memory`
  ADD CONSTRAINT `fk_agent_memory_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_memory`
  ADD CONSTRAINT `fk_agent_memory_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_2` FOREIGN KEY (`enterprise_id`, `agent_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_3` FOREIGN KEY (`enterprise_id`, `preview_resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_4` FOREIGN KEY (`enterprise_id`, `agent_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_5` FOREIGN KEY (`enterprise_id`, `user_id`, `agent_id`, `hire_id`) REFERENCES `agent_hire` (`enterprise_id`, `user_id`, `agent_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_6` FOREIGN KEY (`enterprise_id`, `id`, `active_run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_message`
  ADD CONSTRAINT `fk_agent_message_1` FOREIGN KEY (`enterprise_id`, `conversation_id`) REFERENCES `agent_conversation` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_message`
  ADD CONSTRAINT `fk_agent_message_2` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_run`
  ADD CONSTRAINT `fk_agent_run_1` FOREIGN KEY (`enterprise_id`, `user_id`, `conversation_id`) REFERENCES `agent_conversation` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_run`
  ADD CONSTRAINT `fk_agent_run_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_run`
  ADD CONSTRAINT `fk_agent_run_3` FOREIGN KEY (`enterprise_id`, `conversation_id`, `input_message_id`) REFERENCES `agent_message` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_run`
  ADD CONSTRAINT `fk_agent_run_4` FOREIGN KEY (`enterprise_id`, `conversation_id`, `output_message_id`) REFERENCES `agent_message` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_run`
  ADD CONSTRAINT `fk_agent_run_5` FOREIGN KEY (`enterprise_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `agent_event`
  ADD CONSTRAINT `fk_agent_event_1` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_attempt`
  ADD CONSTRAINT `fk_run_attempt_1` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_attempt`
  ADD CONSTRAINT `fk_run_attempt_2` FOREIGN KEY (`enterprise_id`, `output_message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_step`
  ADD CONSTRAINT `fk_run_step_1` FOREIGN KEY (`enterprise_id`, `run_id`, `attempt_id`) REFERENCES `run_attempt` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_step`
  ADD CONSTRAINT `fk_run_step_2` FOREIGN KEY (`enterprise_id`, `attempt_id`, `parent_step_id`) REFERENCES `run_step` (`enterprise_id`, `attempt_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_1` FOREIGN KEY (`enterprise_id`, `actor_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_2` FOREIGN KEY (`enterprise_id`, `resource_id`, `resource_version_id`) REFERENCES `resource_version` (`enterprise_id`, `resource_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_3` FOREIGN KEY (`enterprise_id`, `attempt_id`, `step_id`) REFERENCES `run_step` (`enterprise_id`, `attempt_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_4` FOREIGN KEY (`enterprise_id`, `plugin_tool_id`) REFERENCES `plugin_tool` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_5` FOREIGN KEY (`enterprise_id`, `resource_id`) REFERENCES `resource` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `tool_call`
  ADD CONSTRAINT `fk_tool_call_6` FOREIGN KEY (`enterprise_id`, `run_id`, `attempt_id`) REFERENCES `run_attempt` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_approval`
  ADD CONSTRAINT `fk_run_approval_1` FOREIGN KEY (`enterprise_id`, `run_id`, `step_id`) REFERENCES `run_step` (`enterprise_id`, `run_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_approval`
  ADD CONSTRAINT `fk_run_approval_2` FOREIGN KEY (`enterprise_id`, `tool_call_id`) REFERENCES `tool_call` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_approval`
  ADD CONSTRAINT `fk_run_approval_3` FOREIGN KEY (`enterprise_id`, `approver_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `run_checkpoint`
  ADD CONSTRAINT `fk_run_checkpoint_1` FOREIGN KEY (`enterprise_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `message_attachment`
  ADD CONSTRAINT `fk_message_attachment_1` FOREIGN KEY (`enterprise_id`, `message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `message_attachment`
  ADD CONSTRAINT `fk_message_attachment_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `message_feedback`
  ADD CONSTRAINT `fk_message_feedback_1` FOREIGN KEY (`enterprise_id`, `message_id`) REFERENCES `agent_message` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `message_feedback`
  ADD CONSTRAINT `fk_message_feedback_2` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_task`
  ADD CONSTRAINT `fk_scheduled_task_1` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_task`
  ADD CONSTRAINT `fk_scheduled_task_2` FOREIGN KEY (`enterprise_id`, `owner_user_id`, `hire_id`) REFERENCES `agent_hire` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_task`
  ADD CONSTRAINT `fk_scheduled_task_3` FOREIGN KEY (`enterprise_id`, `agent_version_id`) REFERENCES `resource_version` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_task`
  ADD CONSTRAINT `fk_scheduled_task_4` FOREIGN KEY (`enterprise_id`, `id`, `active_occurrence_id`) REFERENCES `scheduled_occurrence` (`enterprise_id`, `schedule_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_occurrence`
  ADD CONSTRAINT `fk_scheduled_occurrence_1` FOREIGN KEY (`enterprise_id`, `schedule_id`) REFERENCES `scheduled_task` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `scheduled_occurrence`
  ADD CONSTRAINT `fk_scheduled_occurrence_2` FOREIGN KEY (`enterprise_id`, `conversation_id`, `run_id`) REFERENCES `agent_run` (`enterprise_id`, `conversation_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `todo_item`
  ADD CONSTRAINT `fk_todo_item_1` FOREIGN KEY (`enterprise_id`, `created_by`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `todo_item`
  ADD CONSTRAINT `fk_todo_item_2` FOREIGN KEY (`enterprise_id`, `owner_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `todo_item`
  ADD CONSTRAINT `fk_todo_item_3` FOREIGN KEY (`enterprise_id`, `team_id`) REFERENCES `enterprise_team` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `todo_history`
  ADD CONSTRAINT `fk_todo_history_1` FOREIGN KEY (`enterprise_id`, `todo_id`) REFERENCES `todo_item` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `todo_history`
  ADD CONSTRAINT `fk_todo_history_2` FOREIGN KEY (`enterprise_id`, `actor_user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_policy`
  ADD CONSTRAINT `fk_quota_policy_1` FOREIGN KEY (`enterprise_id`) REFERENCES `enterprise` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_bucket`
  ADD CONSTRAINT `fk_quota_bucket_1` FOREIGN KEY (`enterprise_id`, `policy_id`) REFERENCES `quota_policy` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `quota_entry`
  ADD CONSTRAINT `fk_quota_entry_1` FOREIGN KEY (`enterprise_id`, `bucket_id`) REFERENCES `quota_bucket` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `notification`
  ADD CONSTRAINT `fk_notification_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `api_request`
  ADD CONSTRAINT `fk_api_request_1` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 用户空间及项目元数据，文件保存在持久目录。
CREATE TABLE `user_workspace`
(
  `id`             VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`  VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`        VARCHAR(100) NOT NULL COMMENT '所属成员',
  `directory_path` VARCHAR(128) NOT NULL COMMENT '持久根目录内的相对目录',
  `initialized_at` DATETIME(3) NULL COMMENT '首次准备目录时间',
  `created_at`     DATETIME(3) NOT NULL COMMENT '创建时间',
  `updated_at`     DATETIME(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_workspace_1` (`enterprise_id`, `user_id`),
  UNIQUE KEY `uk_user_workspace_2` (`enterprise_id`, `user_id`, `id`),
  UNIQUE KEY `uk_user_workspace_3` (`directory_path`),
  UNIQUE KEY `uk_user_workspace_4` (`enterprise_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每个企业成员的持久用户工作空间';

CREATE TABLE `workspace_project`
(
  `id`                     VARCHAR(100)                     NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id`          VARCHAR(100)                     NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`                VARCHAR(100)                     NOT NULL COMMENT '所属成员',
  `workspace_id`           VARCHAR(100)                     NOT NULL COMMENT '所属用户工作空间',
  `name`                   VARCHAR(100)                     NOT NULL COMMENT '项目名称',
  `directory_path`         VARCHAR(512)                     NOT NULL COMMENT '工作空间内的项目目录',
  `directory_key`          VARCHAR(512) COLLATE utf8mb4_bin NOT NULL COMMENT '检查跨平台目录冲突的小写路径',
  `legacy_conversation_id` VARCHAR(100) NULL COMMENT '需要复制旧文件的会话编号',
  `initialized_at`         DATETIME(3) NULL COMMENT '首次准备项目文件的时间',
  `created_at`             DATETIME(3) NOT NULL COMMENT '创建时间',
  `updated_at`             DATETIME(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_project_1` (`enterprise_id`, `user_id`, `id`),
  UNIQUE KEY `uk_workspace_project_2` (`workspace_id`, `directory_key`),
  UNIQUE KEY `uk_workspace_project_3` (`legacy_conversation_id`),
  UNIQUE KEY `uk_workspace_project_4` (`enterprise_id`, `id`),
  KEY                      `idx_workspace_project_1` (`enterprise_id`, `user_id`, `created_at`, `id`),
  KEY                      `idx_workspace_project_2` (`enterprise_id`, `user_id`, `workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户项目目录，可以被同一用户的多个会话引用';

ALTER TABLE `agent_conversation`
  ADD COLUMN `project_id` VARCHAR(100) NULL, ADD KEY `idx_conversation_project` (`enterprise_id`, `user_id`, `project_id`);
ALTER TABLE `agent_conversation`
  ADD CONSTRAINT `fk_agent_conversation_7` FOREIGN KEY (`enterprise_id`, `user_id`, `project_id`) REFERENCES `workspace_project` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `user_workspace`
  ADD CONSTRAINT `fk_user_workspace_1` FOREIGN KEY (`enterprise_id`, `user_id`) REFERENCES `enterprise_member` (`enterprise_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `workspace_project`
  ADD CONSTRAINT `fk_workspace_project_1` FOREIGN KEY (`enterprise_id`, `user_id`, `workspace_id`) REFERENCES `user_workspace` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE `workspace_project_file`
(
  `id`            VARCHAR(100) NOT NULL COMMENT '服务端生成的稳定编号，作为不透明字符串传递',
  `enterprise_id` VARCHAR(100) NOT NULL COMMENT '所属企业，必须与已验证的请求或后台任务企业一致',
  `user_id`       VARCHAR(100) NOT NULL COMMENT '所属成员',
  `project_id`    VARCHAR(100) NOT NULL COMMENT '引用资料的项目',
  `file_id`       VARCHAR(100) NOT NULL COMMENT '保留的上传文件',
  `created_at`    DATETIME(3) NOT NULL COMMENT '建立引用的时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_project_file_1` (`project_id`, `file_id`),
  UNIQUE KEY `uk_workspace_project_file_2` (`enterprise_id`, `id`),
  KEY             `idx_workspace_project_file_1` (`enterprise_id`, `user_id`, `file_id`),
  KEY             `idx_workspace_project_file_2` (`enterprise_id`, `user_id`, `project_id`),
  KEY             `idx_workspace_project_file_3` (`enterprise_id`, `file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目保留的本人上传资料，不随原会话删除失去访问';
ALTER TABLE `workspace_project_file`
  ADD CONSTRAINT `fk_workspace_project_file_1` FOREIGN KEY (`enterprise_id`, `user_id`, `project_id`) REFERENCES `workspace_project` (`enterprise_id`, `user_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `workspace_project_file`
  ADD CONSTRAINT `fk_workspace_project_file_2` FOREIGN KEY (`enterprise_id`, `file_id`) REFERENCES `file_object` (`enterprise_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
