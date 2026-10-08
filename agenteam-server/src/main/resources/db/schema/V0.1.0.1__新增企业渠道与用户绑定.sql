-- 新增企业应用接入、独立加密凭证、用户绑定、一次性授权及通知偏好。
CREATE TABLE enterprise_integration (
  id varchar(100) NOT NULL COMMENT '接入配置编号，生成后保持不变',
  enterprise_id varchar(100) NOT NULL COMMENT '本地企业',
  provider_code varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端注册的渠道代码，首期 wecom 或 feishu',
  installation_type varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'self_built' COMMENT '首期只开放企业自建应用',
  region_code varchar(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'cn' COMMENT '平台区域，首期仅中国区',
  name varchar(80) NOT NULL COMMENT '管理员填写的接入名称',
  external_tenant_id varchar(191) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '企业微信企业编号或飞书企业标识，启用前必须验证',
  external_app_id varchar(191) COLLATE utf8mb4_bin NOT NULL COMMENT '企业微信应用编号或飞书应用编号',
  public_login_key varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '公开企业登录入口的随机标识，不是认证凭证',
  status varchar(20) NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿、enabled 启用、disabled 停用、deleted 删除',
  binding_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许发起新的用户绑定',
  login_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许已绑定用户从此入口登录',
  messaging_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许发送消息',
  config_schema_version int NOT NULL DEFAULT 1 COMMENT '非敏感配置格式版本',
  config_json json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '按渠道校验的非敏感配置，不接受密钥和任意接口地址',
  credential_revision bigint NOT NULL DEFAULT 1 COMMENT '密钥轮换版本，令牌缓存必须包含此版本',
  identity_verified_at datetime(3) DEFAULT NULL COMMENT '服务端验证外部企业和应用的时间',
  last_checked_at datetime(3) DEFAULT NULL COMMENT '最近一次主动校验时间',
  last_checked_revision bigint DEFAULT NULL COMMENT '此次校验对应的配置版本',
  last_check_status varchar(20) NOT NULL DEFAULT 'not_checked' COMMENT 'not_checked 未校验、passed 本次通过、failed 本次失败',
  last_check_error_code varchar(64) DEFAULT NULL COMMENT '脱敏校验错误代码',
  last_check_error_summary varchar(500) DEFAULT NULL COMMENT '脱敏的具体失败原因',
  created_by varchar(100) NOT NULL COMMENT '创建者，可为不属于本企业的全局超级管理员',
  updated_by varchar(100) NOT NULL COMMENT '最后维护者',
  revision bigint NOT NULL DEFAULT 1 COMMENT '管理配置版本，业务修改时递增',
  deleted_at datetime(3) DEFAULT NULL COMMENT '标记删除时间',
  deleted_token varchar(100) NOT NULL DEFAULT '' COMMENT '未删除时为空，删除后为本行编号',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_integration_enterprise (enterprise_id, id),
  UNIQUE KEY uk_integration_login (public_login_key),
  UNIQUE KEY uk_integration_external (provider_code, region_code, external_tenant_id, external_app_id, deleted_token),
  KEY idx_integration_list (enterprise_id, status, provider_code, id),
  CONSTRAINT fk_integration_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_integration_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
  CONSTRAINT fk_integration_updater FOREIGN KEY (updated_by) REFERENCES app_user(id),
  CONSTRAINT ck_integration_status CHECK (status IN ('draft', 'enabled', 'disabled', 'deleted')),
  CONSTRAINT ck_integration_check_status CHECK (last_check_status IN ('not_checked', 'passed', 'failed')),
  CONSTRAINT ck_integration_versions CHECK (revision > 0 AND credential_revision > 0 AND config_schema_version > 0),
  CONSTRAINT ck_integration_config CHECK (JSON_TYPE(config_json) = 'OBJECT'),
  CONSTRAINT ck_integration_enabled CHECK (status <> 'enabled' OR (external_tenant_id IS NOT NULL AND identity_verified_at IS NOT NULL)),
  CONSTRAINT ck_integration_flags CHECK (binding_enabled IN (0, 1) AND login_enabled IN (0, 1) AND messaging_enabled IN (0, 1)),
  CONSTRAINT ck_integration_deleted CHECK (
    (status = 'deleted' AND deleted_at IS NOT NULL AND deleted_token = id)
    OR (status <> 'deleted' AND deleted_at IS NULL AND deleted_token = '')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业配置的外部应用及实际启用能力';

CREATE TABLE enterprise_integration_secret (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  connection_id varchar(100) NOT NULL,
  secret_name varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '首期 app_secret，未来按实现增加其他秘密用途',
  encrypted_value json NOT NULL COMMENT '复用 EncryptedPayload：keyVersion、nonce、ciphertext；仅保存加密结果',
  revision bigint NOT NULL DEFAULT 1 COMMENT '此秘密版本，与接入凭据版本在同一事务更新',
  updated_by varchar(100) NOT NULL COMMENT '全局用户编号，允许超级管理员维护',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_integration_secret (enterprise_id, connection_id, secret_name),
  CONSTRAINT fk_integration_secret_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT fk_integration_secret_updater FOREIGN KEY (updated_by) REFERENCES app_user(id),
  CONSTRAINT ck_integration_secret_payload CHECK (JSON_TYPE(encrypted_value) = 'OBJECT' AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仅供渠道服务读取的应用密钥，不进入通用工具凭据目录';

CREATE TABLE user_channel_binding (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  connection_id varchar(100) NOT NULL,
  user_id varchar(100) NOT NULL COMMENT '被绑定的本地成员',
  external_subject_type varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '首期 wecom_userid 或 feishu_open_id',
  external_subject_id varchar(191) COLLATE utf8mb4_bin NOT NULL COMMENT '平台返回的成员编号，范围由接入配置确定',
  external_union_id varchar(191) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '平台实际返回时可保存，不能单独作为账号合并依据',
  display_name varchar(128) DEFAULT NULL COMMENT '授权时真实取得的显示名，没有取得时为空',
  status varchar(20) NOT NULL DEFAULT 'active' COMMENT 'active 可使用、disabled 已停用、revoked 已解绑',
  receive_enabled tinyint(1) NOT NULL DEFAULT 1 COMMENT '用户是否允许通过此绑定接收通知',
  external_login_enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '用户是否明确允许通过此绑定登录',
  authorized_at datetime(3) NOT NULL COMMENT '用户确认绑定的时间',
  last_authenticated_at datetime(3) DEFAULT NULL COMMENT '最近成功外部登录时间，不因更新此字段而改变绑定版本',
  revoked_at datetime(3) DEFAULT NULL,
  revision bigint NOT NULL DEFAULT 1 COMMENT '身份控制状态改变时递增，发送前与快照比较',
  active_user_id varchar(100) GENERATED ALWAYS AS (CASE WHEN status <> 'revoked' THEN user_id ELSE NULL END) STORED,
  active_subject_id varchar(191) COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN status <> 'revoked' THEN external_subject_id ELSE NULL END) STORED,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_binding_enterprise (enterprise_id, id),
  UNIQUE KEY uk_channel_binding_identity (enterprise_id, connection_id, user_id, id),
  UNIQUE KEY uk_channel_binding_active_user (enterprise_id, connection_id, active_user_id),
  UNIQUE KEY uk_channel_binding_active_subject (enterprise_id, connection_id, external_subject_type, active_subject_id),
  KEY idx_channel_binding_user (enterprise_id, user_id, status, id),
  CONSTRAINT fk_channel_binding_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT fk_channel_binding_member FOREIGN KEY (enterprise_id, user_id) REFERENCES enterprise_member(enterprise_id, user_id),
  CONSTRAINT ck_channel_binding_status CHECK (status IN ('active', 'disabled', 'revoked')),
  CONSTRAINT ck_channel_binding_flags CHECK (receive_enabled IN (0, 1) AND external_login_enabled IN (0, 1)),
  CONSTRAINT ck_channel_binding_revoked CHECK ((status = 'revoked' AND revoked_at IS NOT NULL) OR (status <> 'revoked' AND revoked_at IS NULL)),
  CONSTRAINT ck_channel_binding_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地成员与一个外部应用中的用户身份绑定，身份关联列不可就地修改';

CREATE TABLE channel_oauth_session (
  id varchar(100) NOT NULL,
  enterprise_id varchar(100) NOT NULL,
  connection_id varchar(100) NOT NULL,
  purpose varchar(16) NOT NULL COMMENT 'bind 绑定或 login 登录',
  initiator_user_id varchar(100) DEFAULT NULL COMMENT '绑定必须有发起者，未登录的外部登录为空',
  initiator_session_version bigint DEFAULT NULL COMMENT '发起绑定时的本地用户会话版本',
  connection_revision bigint NOT NULL COMMENT '发起时配置版本，变化后需重新授权',
  state_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '授权随机值的摘要，不保存随机值明文',
  browser_nonce_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联发起浏览器的独立随机值摘要',
  confirmation_token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '确认绑定的一次性随机凭据摘要',
  transient_encrypted_json json DEFAULT NULL COMMENT '授权校验随机材料或待确认的身份，加密保存，结束后清空',
  return_path varchar(500) NOT NULL COMMENT '服务端允许的本站返回路径，不允许任意外部地址',
  status varchar(32) NOT NULL DEFAULT 'pending' COMMENT 'pending、processing、awaiting_confirmation、consumed、failed、expired',
  expires_at datetime(3) NOT NULL COMMENT '总有效期五分钟',
  confirmation_expires_at datetime(3) DEFAULT NULL COMMENT '绑定确认最长一分钟且不超过总有效期',
  consumed_at datetime(3) DEFAULT NULL,
  result_code varchar(64) DEFAULT NULL COMMENT '不含平台敏感信息的处理结果',
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_channel_oauth_state (state_hash),
  UNIQUE KEY uk_channel_oauth_confirm (confirmation_token_hash),
  KEY idx_channel_oauth_expiry (status, expires_at, id),
  CONSTRAINT fk_channel_oauth_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT fk_channel_oauth_member FOREIGN KEY (enterprise_id, initiator_user_id) REFERENCES enterprise_member(enterprise_id, user_id),
  CONSTRAINT ck_channel_oauth_purpose CHECK (purpose IN ('bind', 'login')),
  CONSTRAINT ck_channel_oauth_initiator CHECK (
    (purpose = 'bind' AND initiator_user_id IS NOT NULL AND initiator_session_version IS NOT NULL)
    OR (purpose = 'login' AND initiator_user_id IS NULL AND initiator_session_version IS NULL)
  ),
  CONSTRAINT ck_channel_oauth_status CHECK (status IN ('pending', 'processing', 'awaiting_confirmation', 'consumed', 'failed', 'expired')),
  CONSTRAINT ck_channel_oauth_confirmation CHECK (status <> 'awaiting_confirmation' OR (purpose = 'bind' AND confirmation_token_hash IS NOT NULL AND confirmation_expires_at IS NOT NULL AND transient_encrypted_json IS NOT NULL)),
  CONSTRAINT ck_channel_oauth_expiry CHECK (expires_at > created_at AND (confirmation_expires_at IS NULL OR confirmation_expires_at <= expires_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次性用户授权流程及短期绑定确认';

CREATE TABLE user_channel_preference (
  enterprise_id varchar(100) NOT NULL,
  user_id varchar(100) NOT NULL,
  connection_id varchar(100) NOT NULL,
  category varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '已有通知目录中的正式类别，不由客户端随意增加',
  enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否将该类自动通知额外发到此渠道',
  revision bigint NOT NULL DEFAULT 1,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (enterprise_id, user_id, connection_id, category),
  CONSTRAINT fk_channel_preference_member FOREIGN KEY (enterprise_id, user_id) REFERENCES enterprise_member(enterprise_id, user_id),
  CONSTRAINT fk_channel_preference_connection FOREIGN KEY (enterprise_id, connection_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT ck_channel_preference CHECK (enabled IN (0, 1) AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户主动开启的自动业务通知渠道，缺少记录表示不转发';

-- 仅给已有的内置企业管理员增加新管理权限，不扩大自定义角色权限。
INSERT INTO sys_permission (code, name, scope, menu_key, menu_label, menu_path, sort_no)
VALUES ('integration.view', '查看企业消息与登录接入', 'admin', 'integrations', '消息与登录接入', '/admin?tab=integrations', 81),
       ('integration.manage', '配置企业消息与登录接入', 'admin', NULL, NULL, NULL, 0),
       ('integration.test', '校验接入并发送测试消息', 'admin', NULL, NULL, NULL, 0),
       ('notification.send.enterprise', '向本企业成员发送通知', 'admin', NULL, NULL, NULL, 0),
       ('notification.delivery.view', '查看本企业通知发送结果', 'admin', NULL, NULL, NULL, 0),
       ('notification.delivery.retry', '重试本企业失败的通知发送', 'admin', NULL, NULL, NULL, 0);

INSERT INTO sys_role_permission (enterprise_id, role_id, permission_code)
SELECT r.enterprise_id, r.id, p.code
FROM sys_role r
JOIN sys_permission p ON p.code IN ('integration.view', 'integration.manage', 'integration.test',
    'notification.send.enterprise', 'notification.delivery.view', 'notification.delivery.retry')
WHERE r.builtin = 1 AND r.code = 'enterprise-admin' AND r.deleted_at IS NULL;

UPDATE enterprise SET permission_version = permission_version + 1;

